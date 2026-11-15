# ColdTrace MLOps

Goal: warn **before** a fridge or freezer crosses its limit. Today an alert opens only once the temperature is already
out of range. A model will predict the temperature a few minutes ahead and raise an early warning.

| Step | State |
|---|---|
| 1. Synthetic training data (`generator/`) | Done |
| 2. Export and cleaning (`pipeline/prepare.py`) | Done |
| 3. Features and target (`pipeline/features.py`) | Done |
| 4. Training and evaluation (`pipeline/train.py`, `pipeline/evaluate.py`) | Done |
| 5. Tracking with MLflow (`mlflow.coldtrace.test`) | To do |
| 6. Serving (`POST /predict`) | To do |
| 7. Integration in the API and the monitor | To do |
| 8. Automated pipeline (one command, GitHub Actions) | To do |
| 9. Monitoring and retraining | To do |

## Why synthetic data

The real collection has many readings but very few incidents (7 temperature incidents in two weeks). The live
simulator is also too fast for forecasting: a fault crosses the limit in under a minute, while a real cooling failure
takes 5 to 30 minutes. The generator writes realistic history directly to MongoDB in about 2 minutes, and logs every
fault as ground truth.

The data lives in a **separate database, `coldtrace_ml`**, so the monitor and the platform collections stay untouched.
When the real ESP32 sensors arrive, the same pipeline retrains on their data.

## What the generator produces

For each sensor of `gateway-iot/simulator/sensors.json`, one reading every 5 s:

- **Normal behaviour:**
  - the compressor cycles around the setpoint (cold 2.5 °C, freezer -20.5 °C);
  - each unit has a small bias;
  - the door opens briefly (10 to 60 s) during working hours;
  - humidity follows the door;
  - sensor noise, and 0.3 % of readings are lost.
- **Injected faults,** about one every 9 hours per sensor:

| Fault | Effect | Duration |
|---|---|---|
| `cooling_failure` | warms toward room temperature, 0.08 to 0.6 °C/min at the start, then the unit is repaired and pulls back down | 20 to 120 min |
| `door_left_open` | door stays open, air warms | 3 to 25 min |
| `sensor_silence` | no readings | 2 to 40 min |
| `sensor_stuck` | the sensor repeats one value while the air keeps changing | 15 to 90 min |

Collections in `coldtrace_ml`:

| Collection | Content |
|---|---|
| `measurements` | Same fields as the real `measurements`, plus `synthetic: true` and `label`. The label is the active fault, `recovery` while the unit cools back down after one, or `null`. |
| `fault_log` | One document per fault: sensor, type, start, end, parameters (speed, minutes). The ground truth. |
| `rule_incidents` | What the current alert rules would open on this data (temperature, door, silence), to compare the model against. |

**`label` is the answer key: never use it as a model input.** It is only for evaluation.

Result of a 30-day run (seed 42):
- **Volume:** about 5.1 million readings, and about 750 faults, of which about 360 are cooling failures.
- **Rule incidents:** the current rules would open about 690 temperature incidents, of which about 90 are false alarms from short door openings, plus about 9,600 door incidents.
- **Lead time:** on a cooling failure, the rule alerts **about 6 minutes (median)** after it starts, and up to 29 minutes for slow failures. That is the time an early-warning model can win.

## Training pipeline (steps 2 to 4)

**Prepare** (`prepare.py`, about 20 s):
- groups the readings in MongoDB to one row per sensor per minute (5.1 million readings become 430,000 rows);
- fills the missing minutes, so time windows are real time windows;
- computes the features and the target;
- splits by time: the last 8 days are for testing, the model never sees them;
- writes Parquet files to `mlops/data/`, which git ignores.

**Features** (`features.py`, shared with serving later, so both compute the same inputs). All of them look only at the past:

| Feature | Meaning |
|---|---|
| `temp_rel` | current temperature minus the profile's upper limit (negative = safe) |
| `slope_2`, `slope_3`, `slope_5`, `slope_15` | °C per minute over the last 2, 3, 5, 15 minutes |
| `mean_10_rel`, `min_10_rel`, `max_10_rel`, `std_10`, `rise_10` | last 10 minutes: level, spread, rise from the lowest point |
| `door_s_10`, `door_now` | seconds the door was open in the last 10 min, door open now |
| `humidity`, `humidity_slope_5` | humidity and its trend |
| `missing_10` | minutes without readings in the last 10 |
| `is_freezer` | profile |

**Target:** the highest temperature of the next 10 minutes, minus the limit. A warning is raised when the prediction is above the limit plus a margin, while the door is closed.

The door condition matters. An open door already raises its own alert at once, and a short opening looks exactly like the start of a door left open. Warning on it only added false alarms: 85 % of them, before this rule was added.

**Train and evaluate** (`train.py`, about 15 s):
1. Trains LightGBM on the first part of the training period, with early stopping on the last 4 days (validation).
2. Chooses the margin on validation only: the earliest warnings with no more false alarms than the current rule.
3. Scores four methods on the test days against `fault_log`:
   - `rule`: the current alert engine;
   - `persist`: "the next 10 minutes will be as warm as the last 10";
   - `trend`: "the current slope continues";
   - `model`: LightGBM.

Results on the test days (seed 42, 92 cooling failures that crossed the limit):

| Method | Detected | Lead over the rule (median / mean) | False alarms per sensor per day | Forecast error |
|---|---|---|---|---|
| rule (today) | 92/92 | 0 / 0 min | 0.35 | |
| persist | 90/92 | -1 / -1.2 min | 0.19 | 0.62 °C |
| trend | 90/92 | 1 / 0.2 min | 0.36 | 0.25 °C |
| **model** | **92/92** | **4 / 4.9 min** | 0.44 | **0.17 °C** |

- **What was possible:** the most any method could win is the time from the failure start to the rule's alert, a median of 5.2 min. The model gets 4 of those minutes.
- **Slow failures:** on the 18 that take 10+ minutes to cross the limit, where an early warning matters most, the model warns **9 min before the rule** (median), out of 14 possible.
- **The cost:** slightly more false alarms than the rule (0.44 vs 0.35 per sensor per day, so about one extra every 11 days per sensor).

Outputs in `mlops/data/`:

| File | Content |
|---|---|
| `model/model.txt`, `model/model.json` | Model, feature list, chosen margin |
| `metrics.json` | The table above, plus the lead-time figures |
| `report/example_failure.png` | One test failure: temperature, prediction, model warning, rule alert |
| `report/feature_importance.png` | Which inputs the model uses |
| `report/faults.csv` | Each test failure with each method's warning time |

## Run

Start the team stack first (`gateway-iot`), because this joins its Docker network. Then, from this folder:

```powershell
cp .env.example .env               # then put a random password in ML_MONGO_PASSWORD
docker compose run --rm ml-user    # once: creates the coldtrace-ml MongoDB user
docker compose run --rm --build generator --days 30 --reset
```

The `coldtrace-ml` user can only read `coldtrace` and write `coldtrace_ml`. It is created with the root account from
`gateway-iot/.env`; no password is in the code.

Options:

| Option | Meaning |
|---|---|
| `--days 30` | Length of the history |
| `--seed 42` | Random seed |
| `--end 2026-11-15T00:00:00` | End of the history (default: now). With the same `--seed` and `--end`, the data is identical. |
| `--reset` | Replaces the existing generated data. Without it, the script refuses to overwrite. |

Then the training pipeline:

```powershell
docker compose run --rm --build pipeline prepare.py
docker compose run --rm pipeline train.py
```

The first build takes about 5 minutes (LightGBM, pandas); after that, each step takes seconds.

Tests (7 for the generator, 7 for the pipeline, run in the containers):

```powershell
docker compose run --rm --build --entrypoint pytest generator -q
docker compose run --rm --build --entrypoint pytest pipeline -q
```

To look at the data in Compass, open the `coldtrace_ml` database with the same connection as `coldtrace`.

## Files

| File | Purpose |
|---|---|
| `docker-compose.yml` | `ml-user` (one-shot user creation), `generator` and `pipeline`. More services will be added here: MLflow, model API. |
| `generator/generate.py` | The generator: physics per sensor, fault schedule, rule replay, MongoDB writes |
| `generator/test_generate.py` | Tests: same seed gives same data; normal stays in range; failures cross slowly; silence leaves a gap; a stuck sensor repeats one value; the rule replay matches the alert engine |
| `pipeline/prepare.py` | Export, per-minute resampling, features, target, time split |
| `pipeline/features.py` | Feature and target definitions (shared with serving) |
| `pipeline/train.py` | Training, margin choice, comparison with the rule and the baselines, charts |
| `pipeline/evaluate.py` | Event-level scoring: detection, lead time over the rule, false alarms |
| `pipeline/test_pipeline.py` | Tests: the target only looks ahead, features only look back, lead and false alarms are counted right |
| `scripts/create-ml-user.js` | Creates or updates the least-privilege MongoDB user |
| `.env.example` | Variables to copy to `.env` (ignored by git) |

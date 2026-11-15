# ColdTrace MLOps

**Full explanation, with the pipeline diagram and the results:** [docs/MLOPS.md](../docs/MLOPS.md). This README is
the technical reference: commands, options and files.

Goal: warn **before** a fridge or freezer crosses its limit. Today an alert opens only once the temperature is already
out of range. A model will predict the temperature a few minutes ahead and raise an early warning.

| Step | State |
|---|---|
| 1. Synthetic training data (`generator/`) | Done |
| 2. Export and cleaning (`pipeline/prepare.py`) | Done |
| 3. Features and target (`pipeline/features.py`) | Done |
| 4. Training and evaluation (`pipeline/train.py`, `pipeline/evaluate.py`) | Done |
| 5. Tracking and model registry with MLflow (`pipeline/tracking.py`, http://mlflow.coldtrace.test) | Done |
| 6. Serving (`serving/`, http://api.coldtrace.test/ml/docs) | Done |
| 7. Integration in the API and the monitor | To do |
| 8. Automated pipeline (`pipeline/run.py`, `.github/workflows/mlops.yml`) | Done |
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
| `report/learning_curve.png` | Error per boosting round on training and validation data, and the round kept by early stopping |
| `report/feature_importance.png` | Which inputs the model uses |
| `report/faults.csv` | Each test failure with each method's warning time |

## MLflow: tracking and model registry (step 5)

MLflow runs as a container (`mlflow`, version 2.18) and keeps everything in the `mlflow-data` Docker volume. Open it at
**http://mlflow.coldtrace.test**, through `middleware/proxy`.

Each `train.py` run is logged to the **`coldtrace-early-warning`** experiment with:
- **parameters:** LightGBM settings, chosen margin, rounds kept, data size, start of the test period;
- **metrics:** for each method (rule, persist, trend, model), its detection rate, lead time, false alarms and forecast error, plus the possible lead;
- **the learning curve:** `train_l1` and `validation_l1` for each round. Open the run, then **Model metrics**;
- **artifacts:** the charts, `faults.csv`, `metrics.json`, `prepare.json`, and the model itself (LightGBM, with its input signature and an example).

**Registry:** each run registers a new version of the model **`coldtrace-early-warning`**. The **`@production`** alias moves to the new version only if it is better than the current production model on all three:
1. it detects at least as many failures;
2. it raises no more false alarms;
3. it warns earlier on average.

The run's `promoted` and `promotion_reason` tags say what happened. `train.py --force-promote` overrides the check (for example to roll back).

Example from the first runs:

| Version | Run | Mean lead | False alarms/day | Result |
|---|---|---|---|---|
| 1 | default settings | 5.0 min | 0.44 | promoted: no production model yet |
| 2 | `--rounds 150` | 3.3 min | 0.28 | not promoted: warns later |

The model is loaded by name, never by file path. Serving (step 6) will use the same line:

```python
mlflow.pyfunc.load_model("models:/coldtrace-early-warning@production")
```

## Serving: the model behind an API (step 6)

The `model-api` container (FastAPI) loads **`models:/coldtrace-early-warning@production`** from MLflow. Every minute it
checks whether `@production` moved, and loads the new version if so. Promoting a model in MLflow therefore deploys it
without a restart or a code change. If MLflow is down, it keeps serving the model it already has.

It computes the inputs with the same `features.py` as training: the Docker image copies it from `pipeline/`. Training
and serving can't drift apart.

| Endpoint | Purpose |
|---|---|
| `GET /health` | Is a model loaded, which version, which margin |
| `GET /forecast/{sensorId}` | Reads the sensor's last 20 minutes from the platform database (`coldtrace`, read-only) and returns the forecast. Step 7 will use this. |
| `POST /predict` | The same forecast from readings sent in the request (at least 16 minutes; `profile`, optional `limitMax`) |

Addresses:
- **Through the proxy:** `http://api.coldtrace.test/ml/...`, and the interactive docs at **http://api.coldtrace.test/ml/docs**;
- **Directly:** `http://localhost:8001/...`, API calls only;
- **From the other containers:** `http://model-api:8000/...`.

Example answer:

```json
{"sensorId": "S03", "horizonMin": 10, "modelVersion": "1", "minute": "2026-11-15T20:47:00+00:00",
 "temperature": 3.15, "predictedMax": 6.21, "limitMax": 4.0, "margin": 0.25,
 "warning": true, "doorOpen": false, "profile": "cold"}
```

`warning` is true when `predictedMax` is above `limitMax + margin` and the door is closed: the same rule as in
evaluation. Unfinished minutes (fewer than 6 readings) are left out. Too little history gives a 422.

Live check with the simulator: Thaw on S03 at 20:46. The next minute, at **3.15 °C, still under the 4 °C limit**, the
service already answered `warning: true` with a predicted maximum of 6.2 °C.

## One command and CI (step 8)

**One command** runs the whole chain. Each stage is its own process, and the pipeline stops at the first failure:

```powershell
docker compose run --rm --build pipeline run.py              # prepare -> train -> register/promote -> check
docker compose run --rm --build pipeline run.py --generate   # regenerate the 30 days of data first
```

The final **check** loads `@production` by name and makes sure it predicts. About 1 minute in total, or 3 with `--generate`.

**GitHub Actions** ([.github/workflows/mlops.yml](../.github/workflows/mlops.yml)) runs on every push or pull request
that touches `mlops/`, and can also be started by hand (**Actions** tab, then **Run workflow**). It has three jobs:

| Job | What it proves |
|---|---|
| Unit tests | the 23 tests of the generator, pipeline and serving pass |
| Train, register, serve | **the whole pipeline works from scratch:** a fresh MongoDB and MLflow, 3 days of generated data, prepare, train, register, check, then the model API starts and warns for a failing fridge but not for a stable one (`ci/smoke_serving.py`). The metrics and charts are kept as the `mlops-report` artifact of the run. |
| Docker images | the generator, pipeline and model API images build |

The CI model is trained on 3 days only, so its scores are lower. CI checks the pipeline, not the model's quality: that is
the job of the 30-day run and of the promotion rule.

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
docker compose up -d mlflow                       # once; it keeps running
docker compose run --rm --build pipeline prepare.py
docker compose run --rm pipeline train.py
```

Start the model API (it keeps running):

```powershell
docker compose up -d --build model-api
curl http://localhost:8001/health
curl http://localhost:8001/forecast/S01
```

To see MLflow in the browser, add the `mlflow.coldtrace.test` name to the hosts file and start the proxy
([middleware/proxy/README.md](../middleware/proxy/README.md)).

The first build takes about 5 minutes (LightGBM, pandas); after that, each step takes seconds.

Tests (7 for the generator, 8 for the pipeline, 8 for serving, run in the containers):

```powershell
docker compose run --rm --build --entrypoint pytest generator -q
docker compose run --rm --build --entrypoint pytest pipeline -q
docker compose run --rm --build --no-deps --entrypoint pytest model-api -q
```

To look at the data in Compass, open the `coldtrace_ml` database with the same connection as `coldtrace`.

## Files

| File | Purpose |
|---|---|
| `docker-compose.yml` | `ml-user` (one-shot user creation), `generator`, `pipeline`, the `mlflow` server, and `model-api`. More services will be added here: MLflow, model API. |
| `generator/generate.py` | The generator: physics per sensor, fault schedule, rule replay, MongoDB writes |
| `generator/test_generate.py` | Tests: same seed gives same data; normal stays in range; failures cross slowly; silence leaves a gap; a stuck sensor repeats one value; the rule replay matches the alert engine |
| `pipeline/prepare.py` | Export, per-minute resampling, features, target, time split |
| `pipeline/features.py` | Feature and target definitions (shared with serving) |
| `pipeline/train.py` | Training, margin choice, comparison with the rule and the baselines, charts |
| `pipeline/tracking.py` | MLflow logging, model registration, promotion rule |
| `pipeline/evaluate.py` | Event-level scoring: detection, lead time over the rule, false alarms |
| `pipeline/test_pipeline.py` | Tests: the target only looks ahead, features only look back, lead and false alarms are counted right |
| `serving/app.py` | The model API: loads and hot-reloads `@production`, `/health`, `/predict`, `/forecast/{sensorId}` |
| `serving/forecast.py` | Raw readings to per-minute rows, features of the latest minute, warning rule |
| `serving/test_serving.py` | Tests: same resampling and features as training, history checks, door rule, endpoints |
| `pipeline/run.py` | The whole pipeline in one command: [generate], prepare, train, check |
| `ci/smoke_serving.py` | CI check of the model API: loads production, warns only for a failing fridge |
| `../.github/workflows/mlops.yml` | GitHub Actions: tests, full pipeline on a fresh database and MLflow, Docker images |
| `scripts/create-ml-user.js` | Creates or updates the least-privilege MongoDB user |
| `.env.example` | Variables to copy to `.env` (ignored by git) |

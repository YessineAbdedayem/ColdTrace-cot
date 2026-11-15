# ColdTrace MLOps

Goal: warn **before** a fridge or freezer crosses its limit. Today an alert opens only once the temperature is already
out of range. A model will predict the temperature a few minutes ahead and raise an early warning.

| Step | State |
|---|---|
| 1. Synthetic training data (`generator/`) | Done |
| 2. Export and cleaning | To do |
| 3. Features and target | To do |
| 4. Training and evaluation | To do |
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

Tests (7, run in the container):

```powershell
docker compose run --rm --build --entrypoint pytest generator -q
```

To look at the data in Compass, open the `coldtrace_ml` database with the same connection as `coldtrace`.

## Files

| File | Purpose |
|---|---|
| `docker-compose.yml` | `ml-user` (one-shot user creation) and `generator`. More services will be added here: MLflow, model API. |
| `generator/generate.py` | The generator: physics per sensor, fault schedule, rule replay, MongoDB writes |
| `generator/test_generate.py` | Tests: same seed gives same data; normal stays in range; failures cross slowly; silence leaves a gap; a stuck sensor repeats one value; the rule replay matches the alert engine |
| `scripts/create-ml-user.js` | Creates or updates the least-privilege MongoDB user |
| `.env.example` | Variables to copy to `.env` (ignored by git) |

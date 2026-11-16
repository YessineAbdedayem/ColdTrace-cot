"""Step 9a and 9b: is the production model right on live data, and does live data still look like training data?

The model API saves each live forecast in coldtrace_ml.predictions. Once its 10-minute horizon has passed, this
monitor reads what really happened in the platform's readings (coldtrace.measurements), and scores it:

  actualMax   the highest temperature of the next HORIZON_MIN minutes (same definition as the training target)
  error       predictedMax - actualMax, in °C
  crossed     actualMax above the limit: a warning was deserved

Then, for each model version, it summarises the last WINDOW_HOURS and writes the result to
coldtrace_ml.monitoring (shown by the model API's GET /monitoring) and to MLflow (experiment
"coldtrace-monitoring", one run per model version, one point per check), next to the error the model had in training.

Drift (drift.py): the live inputs saved with the predictions of the last WINDOW_HOURS are compared with the training
distribution of each input. The reference comes from the version's training run in MLflow (drift/drift_reference.json);
versions trained before it existed fall back to the local training data in $DATA_DIR/train.parquet, and say so.

After each check, retrain.py decides whether to retrain (step 9c).

  python monitor.py              check once
  python monitor.py --every 300  check every 5 minutes, for ever (the "monitor" service)
"""

import argparse
import logging
import os
import tempfile
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pandas as pd

import drift
from features import FEATURES, HORIZON_MIN

MODEL_NAME = os.environ.get("MODEL_NAME", "coldtrace-early-warning")
EXPERIMENT = "coldtrace-monitoring"
WINDOW_HOURS = 24
# The readings of the last horizon minute may still be arriving: wait a little more before scoring.
SETTLE_MIN = 2
# Like the training target (features.add_target): at least half of the horizon minutes must have readings.
MIN_MINUTES = HORIZON_MIN // 2
BATCH = 5000

log = logging.getLogger("monitor")


# ---- pure functions (unit tested) -----------------------------------------------------------------------------

def actual_max(readings: pd.DataFrame, minute: pd.Timestamp, horizon: int = HORIZON_MIN) -> float | None:
    """Highest temperature of the `horizon` minutes after `minute` (not including it), or None if too few readings.

    `readings` has columns ts (UTC) and temperature.
    """
    start, end = minute + pd.Timedelta(minutes=1), minute + pd.Timedelta(minutes=horizon + 1)
    window = readings[(readings["ts"] >= start) & (readings["ts"] < end)].dropna(subset=["temperature"])
    if window["ts"].dt.floor("1min").nunique() < MIN_MINUTES:
        return None
    return float(window["temperature"].max())


def outcome(prediction: dict, actual: float | None) -> dict:
    """The fields the monitor adds to a prediction."""
    if actual is None:
        return {"outcome": "no_data"}
    return {
        "outcome": "scored",
        "actualMax": round(actual, 2),
        "error": round(prediction["predictedMax"] - actual, 3),
        "crossed": bool(actual > prediction["limitMax"]),
    }


def _ratio(a, b):
    return round(a / b, 4) if b else None


def summarize(scored: pd.DataFrame) -> dict:
    """Live scores of one model version. `scored` has the columns error, warning, crossed, temperature, limitMax.

    Warning accuracy only counts minutes where the sensor was still below its limit: once it is above, the real
    alert is already open, and a warning has nothing left to announce.
    """
    before = scored[scored["temperature"] <= scored["limitMax"]]
    warned, crossed = before["warning"].astype(bool), before["crossed"].astype(bool)
    true_w = int((warned & crossed).sum())
    return {
        "n": int(len(scored)),
        "mae_c": round(float(scored["error"].abs().mean()), 4) if len(scored) else None,
        # > 0: the model predicts warmer than reality (more false warnings); < 0: colder (late warnings)
        "bias_c": round(float(scored["error"].mean()), 4) if len(scored) else None,
        "warning_minutes": int(warned.sum()),
        "true_warning_minutes": true_w,
        "false_warning_minutes": int((warned & ~crossed).sum()),
        "missed_minutes": int((~warned & crossed).sum()),
        # of the minutes it warned, how many were followed by a real crossing
        "precision": _ratio(true_w, int(warned.sum())),
        # of the minutes followed by a crossing, how many it warned
        "recall": _ratio(true_w, int(crossed.sum())),
    }


# ---- one check ------------------------------------------------------------------------------------------------

def score_pending(ml, platform, now: datetime) -> int:
    """Adds the outcome to the predictions whose horizon has passed. Returns how many were handled."""
    cutoff = now - timedelta(minutes=HORIZON_MIN + SETTLE_MIN)
    pending = list(ml.predictions.find({"outcome": None, "minute": {"$lte": cutoff}}).sort("minute", 1).limit(BATCH))
    if not pending:
        return 0
    by_sensor = {}
    for p in pending:
        by_sensor.setdefault((p.get("siteId"), p["sensorId"]), []).append(p)

    for (site_id, sensor_id), preds in by_sensor.items():
        start = min(p["minute"] for p in preds) + timedelta(minutes=1)
        end = max(p["minute"] for p in preds) + timedelta(minutes=HORIZON_MIN + 1)
        query = {"sensorId": sensor_id, "ts": {"$gte": start, "$lt": end}}
        if site_id:
            query["siteId"] = site_id
        docs = list(platform.measurements.find(query, {"_id": 0, "ts": 1, "temperature": 1}))
        readings = pd.DataFrame(docs, columns=["ts", "temperature"])
        readings["ts"] = pd.to_datetime(readings["ts"], utc=True)
        for p in preds:
            minute = pd.Timestamp(p["minute"]).tz_localize("UTC") if pd.Timestamp(p["minute"]).tzinfo is None \
                else pd.Timestamp(p["minute"])
            ml.predictions.update_one({"_id": p["_id"]},
                                      {"$set": {**outcome(p, actual_max(readings, minute)), "scoredAt": now}})
    return len(pending)


def training_mae(version: str) -> float | None:
    """The forecast error this version had on its test days, from its training run in MLflow."""
    try:
        from mlflow.tracking import MlflowClient
        client = MlflowClient()
        run_id = client.get_model_version(MODEL_NAME, version).run_id
        return client.get_run(run_id).data.metrics.get("model_mae_c")
    except Exception:
        return None


_references = {}        # model version -> drift reference from its training run
_local_reference = {}   # (path, mtime) -> reference computed from the local training data


def reference_for(version: str, use_mlflow: bool = True) -> tuple[dict | None, str | None]:
    """The drift reference of this model version, and where it comes from."""
    if version in _references:
        return _references[version], f"training run of version {version}"
    if use_mlflow:
        try:
            import mlflow.artifacts
            from mlflow.tracking import MlflowClient
            run_id = MlflowClient().get_model_version(MODEL_NAME, version).run_id
            with tempfile.TemporaryDirectory() as tmp:
                path = mlflow.artifacts.download_artifacts(
                    run_id=run_id, artifact_path=f"drift/{drift.REFERENCE_FILE}", dst_path=tmp)
                _references[version] = drift.load(path)
            return _references[version], f"training run of version {version}"
        except Exception:
            pass  # trained before drift references existed, or MLflow down: try the local data
    train_file = Path(os.environ.get("DATA_DIR", "/data")) / "train.parquet"
    if train_file.exists():
        key = (str(train_file), train_file.stat().st_mtime)
        if key not in _local_reference:
            _local_reference.clear()
            _local_reference[key] = drift.reference(pd.read_parquet(train_file, columns=FEATURES), FEATURES)
        return _local_reference[key], "local training data (mlops/data/train.parquet), not this version's own"
    return None, None


def to_mlflow(version: str, summary: dict, now: datetime):
    """One point per check, in one run per model version, so MLflow draws the live scores over time."""
    from mlflow.tracking import MlflowClient
    client = MlflowClient()
    exp = client.get_experiment_by_name(EXPERIMENT)
    exp_id = exp.experiment_id if exp else client.create_experiment(EXPERIMENT)
    runs = client.search_runs([exp_id], filter_string=f"tags.model_version = '{version}'", max_results=1)
    run_id = runs[0].info.run_id if runs else client.create_run(
        exp_id, run_name=f"live v{version}",
        tags={"model_name": MODEL_NAME, "model_version": version, "window_hours": str(WINDOW_HOURS)}).info.run_id
    ts_ms, step = int(now.timestamp() * 1000), int(now.timestamp() // 60)
    values = {f"live_{k}": v for k, v in summary.items()}
    d = summary.get("drift") or {}
    values["live_psi_max"] = d.get("psi_max")
    values.update({f"live_psi_{name}": v for name, v in (d.get("features") or {}).items()})
    for key, value in values.items():
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            client.log_metric(run_id, key, float(value), timestamp=ts_ms, step=step)


def check(ml, platform, now: datetime | None = None, use_mlflow: bool = True) -> list[dict]:
    now = now or datetime.now(timezone.utc)
    handled = score_pending(ml, platform, now)
    since = now - timedelta(hours=WINDOW_HOURS)
    scored_columns = ["modelVersion", "error", "warning", "crossed", "temperature", "limitMax"]
    scored = pd.DataFrame(list(ml.predictions.find(
        {"outcome": "scored", "minute": {"$gte": since}}, {"_id": 0, **{c: 1 for c in scored_columns}})),
        columns=scored_columns)
    scored["modelVersion"] = scored["modelVersion"].astype(str)
    # all recent predictions, scored or not, carry the inputs the model saw
    inputs = list(ml.predictions.find({"minute": {"$gte": since}}, {"_id": 0, "modelVersion": 1, "features": 1}))
    versions = sorted({str(r["modelVersion"]) for r in inputs} | set(scored["modelVersion"]), key=_version_key)

    summaries = []
    for version in versions:
        summary = summarize(scored[scored["modelVersion"] == version])
        train = training_mae(version) if use_mlflow else None
        summary["training_mae_c"] = round(train, 4) if train is not None else None
        # how much worse than in training: the signal for retraining (step 9c)
        summary["mae_vs_training"] = (round(summary["mae_c"] / train, 3)
                                      if train and summary["mae_c"] is not None else None)

        live = pd.DataFrame([r["features"] for r in inputs
                             if str(r["modelVersion"]) == version and r.get("features")])
        ref, source = reference_for(version, use_mlflow)
        summary["drift"] = ({**drift.report(ref, live), "reference": source} if ref else
                            {"status": "unknown", "rows": len(live), "psi_max": None, "features": {}, "top": [],
                             "reference": "none found (no drift_reference.json in MLflow, no local train.parquet)"})
        summary["drift_status"] = summary["drift"]["status"]

        doc = {"modelVersion": version, "computedAt": now, "windowHours": WINDOW_HOURS, **summary}
        ml.monitoring.insert_one(dict(doc))
        if use_mlflow:
            try:
                to_mlflow(version, summary, now)
            except Exception as e:
                log.warning("MLflow unavailable, scores kept in MongoDB only: %s", str(e).splitlines()[0][:200])
        summaries.append(doc)
        top = ", ".join(f"{t['feature']} {t['psi']:.2f}" for t in summary["drift"]["top"][:3]) or "-"
        log.info("v%s, last %d h: %d scored, error %s °C (training %s), precision %s, recall %s | drift %s (%s)",
                 version, WINDOW_HOURS, summary["n"], summary["mae_c"], summary["training_mae_c"],
                 summary["precision"], summary["recall"], summary["drift_status"], top)
    log.info("scored %d new predictions", handled)
    return summaries


def _version_key(v: str):
    return (0, int(v)) if v.isdigit() else (1, v)


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--every", type=int, default=0, help="seconds between checks (0: check once)")
    args = parser.parse_args()

    from pymongo import MongoClient
    client = MongoClient(os.environ["MONGO_URI"], serverSelectionTimeoutMS=5000)
    ml = client[os.environ.get("ML_DB", "coldtrace_ml")]
    platform = client[os.environ.get("SOURCE_DB", "coldtrace")]
    ml.monitoring.create_index("computedAt", name="computedAt")

    while True:
        try:
            check(ml, platform)
            # step 9c: retrain if the checks say so (retrain.py; AUTO_RETRAIN=false only logs the decision)
            import retrain
            retrain.maybe_retrain(ml)
        except Exception:
            if not args.every:
                raise
            log.exception("check failed, retrying at the next interval")
        if not args.every:
            return
        time.sleep(args.every)


if __name__ == "__main__":
    main()

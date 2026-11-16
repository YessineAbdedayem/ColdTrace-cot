"""Step 9c: retrain automatically when the monitoring says so.

The monitor (monitor.py) calls maybe_retrain() after each check. It looks at the recent checks of the production model
and retrains when one of these triggers fires:

  error     the live error stayed above ERROR_RATIO x the training error for ERROR_CHECKS checks in a row (30 min)
  drift     the live inputs drifted (drift.py) for DRIFT_CHECKS checks in a row (1 h)
  schedule  the production model is older than MAX_AGE_DAYS

At most one retraining per COOLDOWN_HOURS, whatever the trigger: a trigger that keeps firing does not retrain in a loop.

Retraining runs the normal pipeline (run.py) with the last LIVE_DAYS days of live readings added to the training set,
so the new model learns what it sees now. It is NOT deployed blindly: the promotion rule (tracking.py) only moves
@production if the new version beats the current one on the synthetic test days. The model API then loads it within a
minute. Each retraining is recorded in coldtrace_ml.retraining (shown by GET /monitoring) and tagged in MLflow
(tag `trigger` on the training run).

  python retrain.py                   decide once, and retrain if a trigger fires
  python retrain.py --dry-run         only print the decision
  python retrain.py --force "reason"  retrain now (ignores triggers and cooldown)

AUTO_RETRAIN=false turns automatic retraining off: the monitor then only logs what it would do.
"""

import argparse
import logging
import os
import subprocess
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

ERROR_RATIO = 1.5
ERROR_CHECKS = 6          # checks every 5 min: 30 min
MIN_SCORED = 200          # below this many scored predictions, the live error is too noisy to act on
DRIFT_CHECKS = 12         # 1 h
MAX_AGE_DAYS = 7
COOLDOWN_HOURS = 24
LIVE_DAYS = 7

HERE = Path(__file__).resolve().parent
log = logging.getLogger("monitor")


def _as_utc(dt: datetime | None) -> datetime | None:
    if dt is None:
        return None
    return dt.replace(tzinfo=timezone.utc) if dt.tzinfo is None else dt


def decide(history: list[dict], model_created_at: datetime | None, last_retrain_at: datetime | None,
           now: datetime) -> tuple[str | None, str]:
    """(trigger, reason), or (None, why not). `history`: the production model's monitoring checks, newest first."""
    last_retrain_at, model_created_at = _as_utc(last_retrain_at), _as_utc(model_created_at)
    if last_retrain_at and now - last_retrain_at < timedelta(hours=COOLDOWN_HOURS):
        return None, f"cooldown: last retraining at {last_retrain_at:%Y-%m-%d %H:%M} UTC"

    recent = history[:ERROR_CHECKS]
    if len(recent) == ERROR_CHECKS and all(
            (h.get("mae_vs_training") or 0) > ERROR_RATIO and (h.get("n") or 0) >= MIN_SCORED for h in recent):
        return "error", (f"live error above {ERROR_RATIO}x the training error for {ERROR_CHECKS} checks "
                         f"(now {recent[0]['mae_vs_training']}x, {recent[0]['mae_c']} °C)")

    recent = history[:DRIFT_CHECKS]
    if len(recent) == DRIFT_CHECKS and all(h.get("drift_status") == "drift" for h in recent):
        top = ", ".join(t["feature"] for t in (recent[0].get("drift") or {}).get("top", [])[:3])
        return "drift", f"inputs drifted for {DRIFT_CHECKS} checks in a row (most: {top})"

    if model_created_at and now - model_created_at > timedelta(days=MAX_AGE_DAYS):
        return "schedule", f"production model is {(now - model_created_at).days} days old (max {MAX_AGE_DAYS})"

    return None, "no trigger"


# ---- execution --------------------------------------------------------------------------------------------------

def production():
    """(version, created_at) of the model labelled production, or (None, None)."""
    from mlflow.tracking import MlflowClient

    from tracking import ALIAS, MODEL_NAME
    try:
        mv = MlflowClient().get_model_version_by_alias(MODEL_NAME, ALIAS)
    except Exception:
        return None, None
    return str(mv.version), datetime.fromtimestamp(int(mv.creation_timestamp) / 1000, tz=timezone.utc)


def latest_version() -> str | None:
    from mlflow.tracking import MlflowClient

    from tracking import MODEL_NAME
    versions = MlflowClient().search_model_versions(f"name='{MODEL_NAME}'")
    return str(max(int(v.version) for v in versions)) if versions else None


def run_pipeline(ml, trigger: str, reason: str, from_version: str | None, now: datetime) -> dict:
    """Runs run.py with live data, and records what happened in coldtrace_ml.retraining."""
    record = {"startedAt": now, "trigger": trigger, "reason": reason, "fromVersion": from_version,
              "liveDays": LIVE_DAYS, "status": "running"}
    record_id = ml.retraining.insert_one(dict(record)).inserted_id
    log.warning("RETRAINING (%s): %s", trigger, reason)

    result = subprocess.run([sys.executable, "run.py", "--live-days", str(LIVE_DAYS)], cwd=HERE,
                            env={**os.environ, "RETRAIN_TRIGGER": trigger}, capture_output=True, text=True)
    output = (result.stdout + result.stderr).strip().splitlines()
    for line in output:
        log.info("  | %s", line)

    new_prod, _ = production()
    update = {"finishedAt": datetime.now(timezone.utc), "outputTail": output[-25:]}
    if result.returncode == 0:
        update.update({"status": "done", "newVersion": latest_version(), "productionAfter": new_prod,
                       "promoted": new_prod is not None and new_prod != from_version})
    else:
        update.update({"status": "failed", "exitCode": result.returncode})
    ml.retraining.update_one({"_id": record_id}, {"$set": update})
    record.update(update)
    log.warning("RETRAINING %s: new version %s, production is now version %s%s", update["status"],
                update.get("newVersion"), update.get("productionAfter"),
                " (promoted)" if update.get("promoted") else " (not promoted: production kept)")
    return record


def maybe_retrain(ml, now: datetime | None = None, enabled: bool | None = None) -> dict | None:
    """Called by the monitor after each check."""
    now = now or datetime.now(timezone.utc)
    enabled = os.environ.get("AUTO_RETRAIN", "true").lower() == "true" if enabled is None else enabled
    version, created = production()
    if version is None:
        return None
    history = list(ml.monitoring.find({"modelVersion": version}).sort("computedAt", -1).limit(DRIFT_CHECKS))
    last = ml.retraining.find_one(sort=[("startedAt", -1)])
    trigger, reason = decide(history, created, last["startedAt"] if last else None, now)
    if trigger is None:
        log.info("retraining: %s", reason)
        return None
    if not enabled:
        log.info("retraining would start (%s: %s), but AUTO_RETRAIN is off", trigger, reason)
        return None
    return run_pipeline(ml, trigger, reason, version, now)


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dry-run", action="store_true", help="only print the decision")
    parser.add_argument("--force", metavar="REASON", help="retrain now, whatever the triggers and cooldown")
    args = parser.parse_args()

    from pymongo import MongoClient
    ml = MongoClient(os.environ["MONGO_URI"])[os.environ.get("ML_DB", "coldtrace_ml")]
    now = datetime.now(timezone.utc)
    if args.force:
        version, _ = production()
        run_pipeline(ml, "manual", args.force, version, now)
    elif args.dry_run:
        version, created = production()
        history = list(ml.monitoring.find({"modelVersion": version}).sort("computedAt", -1).limit(DRIFT_CHECKS))
        last = ml.retraining.find_one(sort=[("startedAt", -1)])
        print(decide(history, created, last["startedAt"] if last else None, now))
    else:
        maybe_retrain(ml, now)


if __name__ == "__main__":
    main()

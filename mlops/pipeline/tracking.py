"""Step 5: log a training run to MLflow, register the model, promote it if it beats production.

Used by train.py when MLFLOW_TRACKING_URI is set. Without it, training still works and only
writes files to $DATA_DIR.
"""

import json
import os
from pathlib import Path

EXPERIMENT = "coldtrace-early-warning"
MODEL_NAME = "coldtrace-early-warning"
ALIAS = "production"


def enabled() -> bool:
    return bool(os.environ.get("MLFLOW_TRACKING_URI"))


def _better(new: dict, current: dict | None) -> tuple[bool, str]:
    """Promotion rule: catch at least as many failures, warn earlier, without more false alarms."""
    if current is None:
        return True, "no production model yet"
    checks = [
        (new["model_detection_rate"] >= current.get("model_detection_rate", 0), "detection rate"),
        (new["model_false_alarms_per_sensor_day"] <= current.get("model_false_alarms_per_sensor_day", float("inf")),
         "false alarms"),
        (new["model_mean_lead_min"] > current.get("model_mean_lead_min", float("-inf")), "mean lead time"),
    ]
    failed = [name for ok, name in checks if not ok]
    if failed:
        return False, "not better than production on: " + ", ".join(failed)
    return True, "better than production"


def log_run(*, booster, history, params, cli_args, summary, extra, margin, data_dir: Path,
            sample_input, force_promote=False):
    import mlflow
    from mlflow.entities import Metric
    from mlflow.models import infer_signature
    from mlflow.tracking import MlflowClient

    mlflow.set_experiment(EXPERIMENT)
    client = MlflowClient()

    metrics = {}
    for row in summary.to_dict(orient="records"):
        name = row["method"]
        for key in ("detection_rate", "median_lead_min", "mean_lead_min", "false_alarms_per_sensor_day", "mae_c"):
            value = row.get(key)
            if value is not None and value == value:     # skip NaN
                metrics[f"{name}_{key}"] = float(value)
    metrics.update({k: float(v) for k, v in extra.items() if v is not None})

    prepare = json.loads((data_dir / "prepare.json").read_text(encoding="utf-8"))

    with mlflow.start_run() as run:
        mlflow.log_params({**params, **cli_args, "margin_c": margin, "best_iteration": booster.best_iteration,
                           "n_features": len(prepare["features"]), "rows_train": prepare["rows_train"],
                           "rows_test": prepare["rows_test"], "test_from": prepare["test_from"][:16]})
        mlflow.log_metrics(metrics)

        # the learning curve, one point per boosting round, so MLflow can draw it
        points = [Metric(f"{split}_l1", value, 0, step)
                  for split in ("train", "validation")
                  for step, value in enumerate(history[split]["l1"], start=1)]
        for i in range(0, len(points), 1000):
            client.log_batch(run.info.run_id, metrics=points[i:i + 1000])

        for path in sorted((data_dir / "report").glob("*")):
            mlflow.log_artifact(str(path), "report")
        mlflow.log_artifact(str(data_dir / "metrics.json"))
        mlflow.log_artifact(str(data_dir / "prepare.json"))
        mlflow.log_artifact(str(data_dir / "model" / "model.json"), "model_info")

        prediction = booster.predict(sample_input, num_iteration=booster.best_iteration)
        info = mlflow.lightgbm.log_model(
            booster, artifact_path="model",
            signature=infer_signature(sample_input, prediction),
            input_example=sample_input.head(3),
            registered_model_name=MODEL_NAME,
        )
        version = info.registered_model_version
        client.set_model_version_tag(MODEL_NAME, version, "margin_c", str(margin))

        # compare with the model currently labelled production
        try:
            current_version = client.get_model_version_by_alias(MODEL_NAME, ALIAS)
            current = client.get_run(current_version.run_id).data.metrics
            current_label = f"version {current_version.version}"
        except Exception:
            current, current_label = None, "none"
        promote, reason = _better(metrics, current)
        if force_promote and not promote:
            promote, reason = True, "forced with --force-promote (" + reason + ")"
        if promote:
            client.set_registered_model_alias(MODEL_NAME, ALIAS, version)
        mlflow.set_tags({"promoted": str(promote).lower(), "promotion_reason": reason,
                         "previous_production": current_label})

    print(f"\nMLflow run {run.info.run_id[:8]}: registered {MODEL_NAME} version {version}")
    print(f"  {'promoted to @' + ALIAS if promote else 'not promoted'}: {reason}")
    return run.info.run_id, version, promote

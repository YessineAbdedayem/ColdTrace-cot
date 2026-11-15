"""Step 4: train the early-warning model and compare it with the current rule and two baselines.

Reads   $DATA_DIR/train.parquet, test.parquet, faults.parquet   (written by prepare.py)
Writes  $DATA_DIR/model/model.txt, metrics.json, report/*.png (incl. learning_curve.png), report/faults.csv

Methods compared on the test days:
  rule      the current alert engine: a reading above the limit
  persist   baseline: the next 10 minutes will be as warm as the last 10
  trend     baseline: the current 5-minute slope continues for 10 minutes
  model     LightGBM predicting the highest temperature of the next 10 minutes
A forecast method warns when its prediction is above the limit plus a margin, while the door is
closed. The margin is chosen on a validation period (never on the test days): the earliest
warnings without more false alarms than the rule. Scored on cooling failures.
"""

import argparse
import json
import os
from datetime import timedelta
from pathlib import Path

import lightgbm as lgb
import matplotlib
import numpy as np
import pandas as pd

import tracking
from evaluate import compare, false_alarms_per_sensor_day
from features import FEATURES, HORIZON_MIN, TARGET

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

PARAMS = {
    "objective": "regression",
    "metric": "l1",              # mean absolute error in °C, readable and used for early stopping
    "learning_rate": 0.05,
    "num_leaves": 31,
    "min_data_in_leaf": 200,
    "feature_fraction": 0.9,
    "bagging_fraction": 0.8,
    "bagging_freq": 1,
    "verbose": -1,
    "seed": 42,
}
# °C relative to the limit: warn when the predicted max of the next 10 min is above limit + margin.
# A negative margin warns before the prediction itself reaches the limit.
MARGINS = [-1.0, -0.75, -0.5, -0.25, 0.0, 0.25, 0.5, 0.75, 1.0, 1.5]


def baselines(df):
    return {
        "persist": df["max_10_rel"].to_numpy(),
        "trend": (df["temp_rel"] + df["slope_5"].clip(lower=0) * HORIZON_MIN).to_numpy(),
    }


def warnings(rows, pred, margin):
    """A forecast warns when it is above limit + margin, only while the door is closed:
    an open door already raises its own alert at once, and a short opening looks exactly
    like the start of a door left open, so warning on it would only add false alarms."""
    return pd.Series((pred > margin) & (rows["door_now"].to_numpy() == 0), index=rows.index)


def mae(pred, y):
    return float(np.mean(np.abs(pred - y)))


def pick_margin(rows, faults, pred, false_budget):
    """Margin with the earliest warnings (median lead over the rule) whose false alarms stay
    within the budget. Ties go to the larger margin, which is the more cautious one."""
    rule = rows["temp_max"] > rows["limit_max"]
    best, best_lead = MARGINS[-1], None
    for margin in MARGINS:
        warn = warnings(rows, pred, margin)
        if false_alarms_per_sensor_day(rows, warn) > false_budget:
            continue
        summary, _ = compare(rows, faults, {"rule": rule, "x": warn})
        x = summary.set_index("method").loc["x"]
        lead = x["median_lead_min"] if x["detection_rate"] >= 0.95 else None
        if lead is not None and (best_lead is None or lead >= best_lead):
            best, best_lead = margin, lead
    return best


def plot_example(test, faults_detail, pred, out):
    """One cooling failure with the temperature, the limit, the model warning and the rule alert."""
    row = faults_detail.dropna(subset=["model_lead_min"]).query("fault == 'cooling_failure'")
    if row.empty:
        return
    f = row.sort_values("model_lead_min").iloc[len(row) // 2]   # a typical one, not the best
    window = (test["sensorId"] == f.sensorId) & test["ts"].between(f.start - timedelta(minutes=20),
                                                                   f.start + timedelta(minutes=60))
    d = test[window]
    fig, ax = plt.subplots(figsize=(9, 4))
    ax.plot(d["ts"], d["temp"], label="temperature", color="#1f77b4")
    ax.plot(d["ts"], d["limit_max"] + pred[window.to_numpy()], label="model: max of next 10 min",
            color="#ff7f0e", alpha=0.8)
    ax.axhline(d["limit_max"].iloc[0], color="red", ls="--", lw=1, label="limit")
    ax.axvline(f.start, color="grey", lw=1, label="failure starts")
    ax.axvline(f.model_at, color="#ff7f0e", lw=2, label=f"model warns (+{f.model_lead_min:.0f} min)")
    ax.axvline(f.rule_at, color="red", lw=2, label="rule alerts")
    ax.set_title(f"{f.sensorId}: cooling failure, test period")
    ax.set_ylabel("°C")
    ax.legend(fontsize=8, loc="upper left")
    fig.autofmt_xdate()
    fig.tight_layout()
    fig.savefig(out / "example_failure.png", dpi=120)
    plt.close(fig)


def plot_learning_curve(history, best_iteration, out):
    """Error per boosting round on the training and validation data."""
    fig, ax = plt.subplots(figsize=(7, 4))
    for name, color in (("train", "#1f77b4"), ("validation", "#ff7f0e")):
        values = history[name]["l1"]
        ax.plot(range(1, len(values) + 1), values, label=name, color=color)
    ax.axvline(best_iteration, color="grey", ls="--", lw=1, label=f"kept: round {best_iteration}")
    ax.set_yscale("log")
    ax.set_xlabel("boosting round")
    ax.set_ylabel("mean absolute error (°C, log scale)")
    ax.set_title("Learning curve")
    ax.legend()
    fig.tight_layout()
    fig.savefig(out / "learning_curve.png", dpi=120)
    plt.close(fig)


def plot_importance(booster, out):
    gain = pd.Series(booster.feature_importance("gain"), index=booster.feature_name()).sort_values()
    fig, ax = plt.subplots(figsize=(6, 4))
    (gain / gain.sum()).plot.barh(ax=ax, color="#1f77b4")
    ax.set_title("Feature importance (share of gain)")
    fig.tight_layout()
    fig.savefig(out / "feature_importance.png", dpi=120)
    plt.close(fig)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--val-days", type=float, default=4, help="end of the training period used for validation")
    parser.add_argument("--max-false-per-day", type=float, default=None,
                        help="false-alarm budget per sensor per day when choosing the margin "
                             "(default: no more than the current rule on the validation days)")
    parser.add_argument("--rounds", type=int, default=2000, help="maximum boosting rounds (early stopping)")
    parser.add_argument("--force-promote", action="store_true",
                        help="label this model production even if it is not better (MLflow only)")
    args = parser.parse_args()

    data = Path(os.environ.get("DATA_DIR", "/data"))
    model_dir, report_dir = data / "model", data / "report"
    model_dir.mkdir(exist_ok=True)
    report_dir.mkdir(exist_ok=True)

    train = pd.read_parquet(data / "train.parquet")
    test = pd.read_parquet(data / "test.parquet").reset_index(drop=True)
    faults = pd.read_parquet(data / "faults.parquet")

    cut = train["ts"].max() - timedelta(days=args.val_days)
    fit, val = train[train["ts"] < cut - timedelta(minutes=15)], train[train["ts"] >= cut].reset_index(drop=True)
    print(f"Fit {len(fit):,} rows, validation {len(val):,}, test {len(test):,}")

    history = {}
    train_set = lgb.Dataset(fit[FEATURES], fit[TARGET])
    booster = lgb.train(
        PARAMS,
        train_set,
        num_boost_round=args.rounds,
        valid_sets=[train_set, lgb.Dataset(val[FEATURES], val[TARGET], reference=train_set)],
        valid_names=["train", "validation"],
        callbacks=[lgb.early_stopping(50, verbose=False), lgb.record_evaluation(history)],
    )
    print(f"Trained {booster.best_iteration} rounds: error {history['train']['l1'][booster.best_iteration - 1]:.3f} °C"
          f" on training data, {history['validation']['l1'][booster.best_iteration - 1]:.3f} °C on validation")

    # margins chosen on validation only: never as many false alarms as today's rule, as early as possible
    rule_false_val = false_alarms_per_sensor_day(val, val["temp_max"] > val["limit_max"])
    budget = args.max_false_per_day if args.max_false_per_day is not None else rule_false_val
    print(f"False-alarm budget: {budget:.3f} per sensor per day (rule on validation: {rule_false_val:.3f})")
    val_preds = {"model": booster.predict(val[FEATURES], num_iteration=booster.best_iteration), **baselines(val)}
    margins = {name: pick_margin(val, faults, p, budget) for name, p in val_preds.items()}

    test_preds = {"model": booster.predict(test[FEATURES], num_iteration=booster.best_iteration), **baselines(test)}
    y = test[TARGET].to_numpy()
    # the rule opens an incident on any reading above the limit: at minute level, the minute's maximum
    methods = {"rule": test["temp_max"] > test["limit_max"]}
    for name, p in test_preds.items():
        methods[name] = warnings(test, p, margins[name])

    summary, detail = compare(test, faults, methods)
    summary["mae_c"] = summary["method"].map({n: mae(p, y) for n, p in test_preds.items()})
    summary["margin_c"] = summary["method"].map(margins)

    pd.set_option("display.width", 160)
    print("\nTest period, cooling failures:")
    print(summary.round(3).to_string(index=False))

    # context: the most any method could win is the time from the fault start to the rule's alert
    crossed = detail.dropna(subset=["rule_at"]).copy()
    crossed["possible_min"] = (crossed["rule_at"] - crossed["start"]).dt.total_seconds() / 60
    slow = crossed[crossed["possible_min"] >= 10]
    extra = {
        "possible_lead_median_min": float(crossed["possible_min"].median()),
        "slow_faults": int(len(slow)),
        "slow_possible_lead_median_min": float(slow["possible_min"].median()) if len(slow) else None,
        "slow_model_lead_median_min": float(slow["model_lead_min"].median()) if len(slow) else None,
    }
    print(f"\nMost any method could win (failure start -> rule alert): median {extra['possible_lead_median_min']:.1f} min")
    if len(slow):
        print(f"Slow failures ({len(slow)} taking 10+ min to cross): model warns {extra['slow_model_lead_median_min']:.1f} min"
              f" before the rule (median), out of {extra['slow_possible_lead_median_min']:.1f} possible")

    booster.save_model(str(model_dir / "model.txt"), num_iteration=booster.best_iteration)
    model_info = {"features": FEATURES, "target": TARGET, "horizon_min": HORIZON_MIN,
                  "margin_c": margins["model"], "best_iteration": booster.best_iteration, "params": PARAMS}
    (model_dir / "model.json").write_text(json.dumps(model_info, indent=2), encoding="utf-8")
    metrics = {"summary": summary.to_dict(orient="records"), **extra, **model_info}
    (data / "metrics.json").write_text(json.dumps(metrics, indent=2, default=str), encoding="utf-8")
    detail.to_csv(report_dir / "faults.csv", index=False)
    plot_example(test, detail, test_preds["model"], report_dir)
    plot_importance(booster, report_dir)
    plot_learning_curve(history, booster.best_iteration, report_dir)
    print(f"\nModel, metrics and charts written to {data}")

    if tracking.enabled():
        tracking.log_run(
            booster=booster, history=history, params=PARAMS,
            cli_args={"val_days": args.val_days, "max_false_per_day": budget, "max_rounds": args.rounds},
            summary=summary, extra=extra, margin=margins["model"], data_dir=data,
            sample_input=test[FEATURES].sample(500, random_state=0), force_promote=args.force_promote,
        )
    else:
        print("MLFLOW_TRACKING_URI is not set: run not logged to MLflow")


if __name__ == "__main__":
    main()

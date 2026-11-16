from datetime import datetime, timedelta, timezone

import pandas as pd
import pytest

import retrain
from prepare import add_live
from train import split

NOW = datetime(2026, 11, 20, 12, 0, tzinfo=timezone.utc)
FRESH = NOW - timedelta(days=1)      # model created yesterday


def check(mae_ratio=1.0, n=500, drift="ok"):
    return {"mae_vs_training": mae_ratio, "mae_c": 0.3, "n": n, "drift_status": drift,
            "drift": {"top": [{"feature": "std_10"}, {"feature": "slope_5"}]}}


def test_nothing_to_do_when_the_model_is_fine():
    assert retrain.decide([check()] * 12, FRESH, None, NOW)[0] is None


def test_a_lasting_high_error_retrains():
    history = [check(mae_ratio=2.0)] * retrain.ERROR_CHECKS
    trigger, reason = retrain.decide(history, FRESH, None, NOW)
    assert trigger == "error" and "2.0x" in reason


def test_one_bad_check_is_not_enough():
    history = [check(mae_ratio=2.0)] * (retrain.ERROR_CHECKS - 1) + [check()]
    assert retrain.decide(history, FRESH, None, NOW)[0] is None


def test_a_high_error_on_too_few_predictions_is_ignored():
    history = [check(mae_ratio=3.0, n=50)] * retrain.ERROR_CHECKS
    assert retrain.decide(history, FRESH, None, NOW)[0] is None


def test_lasting_drift_retrains_and_names_the_inputs():
    trigger, reason = retrain.decide([check(drift="drift")] * retrain.DRIFT_CHECKS, FRESH, None, NOW)
    assert trigger == "drift" and "std_10" in reason


def test_short_drift_does_not_retrain():
    history = [check(drift="drift")] * (retrain.DRIFT_CHECKS - 1)
    assert retrain.decide(history, FRESH, None, NOW)[0] is None


def test_an_old_model_is_retrained_on_schedule():
    old = NOW - timedelta(days=retrain.MAX_AGE_DAYS + 1)
    assert retrain.decide([check()], old, None, NOW)[0] == "schedule"


def test_cooldown_stops_a_retraining_loop():
    history = [check(drift="drift")] * retrain.DRIFT_CHECKS
    recent = NOW - timedelta(hours=retrain.COOLDOWN_HOURS - 1)
    trigger, reason = retrain.decide(history, FRESH, recent, NOW)
    assert trigger is None and reason.startswith("cooldown")
    # MongoDB gives naive UTC datetimes: they must work too
    assert retrain.decide(history, FRESH, recent.replace(tzinfo=None), NOW)[0] is None
    assert retrain.decide(history, FRESH, NOW - timedelta(hours=retrain.COOLDOWN_HOURS + 1), NOW)[0] == "drift"


# ---- live data in training ----------------------------------------------------------------------------------------

def rows(start, n, source=None):
    df = pd.DataFrame({"ts": pd.date_range(start, periods=n, freq="1h", tz="UTC"), "x": range(n), "label": None})
    return df.assign(source=source) if source else df


def test_live_rows_only_go_to_training_and_are_marked():
    train = add_live(rows("2026-10-01", 10), rows("2026-11-19", 5))
    assert list(train["source"].value_counts().sort_index()) == [5, 10]   # live, synthetic
    assert add_live(rows("2026-10-01", 10), pd.DataFrame())["source"].eq("synthetic").all()


def test_validation_stays_on_synthetic_days_even_with_newer_live_rows():
    synthetic = rows("2026-10-01", 24 * 10, "synthetic")      # 10 days
    live = rows("2026-11-19", 24, "live")                     # newer than every synthetic row
    fit, val = split(pd.concat([synthetic, live], ignore_index=True), val_days=2)
    assert (val["source"] == "synthetic").all() and len(val) > 0
    assert val["ts"].min() >= synthetic["ts"].max() - pd.Timedelta(days=2)
    assert (fit["source"] == "live").sum() == len(live)       # all live rows are fitted
    assert fit.loc[fit["source"] == "synthetic", "ts"].max() < val["ts"].min()


def test_live_rows_are_weighted_to_their_share_but_capped():
    from train import LIVE_MAX_WEIGHT, live_weights
    df = pd.DataFrame({"source": ["synthetic"] * 800 + ["live"] * 50})
    w = live_weights(df, share=0.2)
    assert (w[:800] == 1).all()
    assert w[800:].sum() / w.sum() == pytest.approx(0.2)          # 50 live rows weigh 20 % of the total
    tiny = pd.DataFrame({"source": ["synthetic"] * 100_000 + ["live"] * 10})
    assert live_weights(tiny).max() == LIVE_MAX_WEIGHT             # 10 rows cannot weigh 20 % of 100,000
    assert (live_weights(pd.DataFrame({"x": [1, 2]})) == 1).all()  # no source column: all equal


def test_the_drift_reference_follows_the_training_weights():
    import drift
    import numpy as np
    df = pd.DataFrame({"v": [0.0] * 900 + [10.0] * 100})

    def share_of_ten(ref):   # share of the reference in the bucket that holds the value 10
        bucket = int(np.argmax(drift.shares(np.array([10.0]), ref["edges"])))
        return ref["shares"][bucket]

    assert share_of_ten(drift.reference(df, ["v"])["v"]) == pytest.approx(0.1)
    weighted = drift.reference(df, ["v"], weights=[1.0] * 900 + [9.0] * 100)["v"]
    assert share_of_ten(weighted) == pytest.approx(0.5, abs=0.05)   # the heavy rows now fill half the data


def test_split_still_works_on_files_without_a_source_column():
    fit, val = split(rows("2026-10-01", 24 * 10), val_days=2)
    assert len(fit) > 0 and len(val) > 0 and fit["ts"].max() < val["ts"].min()

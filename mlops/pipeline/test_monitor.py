import numpy as np
import pandas as pd
import pytest

from features import HORIZON_MIN, add_features, add_target, TARGET
from monitor import actual_max, outcome, summarize

T0 = pd.Timestamp("2026-11-01 08:00", tz="UTC")


def readings(temps_per_minute, start=T0):
    """One reading every 5 s; each minute holds the given temperature."""
    ts = pd.date_range(start, periods=len(temps_per_minute) * 12, freq="5s")
    temp = np.repeat(temps_per_minute, 12)
    return pd.DataFrame({"ts": ts, "temperature": temp})


def test_actual_max_looks_only_at_the_next_minutes():
    temps = [2.0] * 30
    temps[5] = 9.0                       # in the future of minute 0 ... 4, and the present of minute 5
    r = readings(temps)
    assert actual_max(r, T0 + pd.Timedelta(minutes=4)) == 9.0
    assert actual_max(r, T0 + pd.Timedelta(minutes=5 - HORIZON_MIN)) == 9.0     # last minute of the horizon
    assert actual_max(r, T0 + pd.Timedelta(minutes=5 - HORIZON_MIN - 1)) == 2.0 # just outside it
    assert actual_max(r, T0 + pd.Timedelta(minutes=5)) == 2.0                   # the minute itself is not the future


def test_actual_max_matches_the_training_target():
    temps = list(np.linspace(2.0, 8.0, 40))
    r = readings(temps)
    minutes = pd.DataFrame({"temp": temps, "temp_max": temps, "humidity": 60.0, "door_s": 0.0, "n": 12.0},
                           index=pd.date_range(T0, periods=40, freq="1min", name="ts"))
    target = add_target(add_features(minutes, 4.0, False), 4.0)[TARGET]
    for m in (0, 10, 25):
        assert actual_max(r, T0 + pd.Timedelta(minutes=m)) - 4.0 == pytest.approx(target.iloc[m])


def test_too_few_readings_after_the_prediction_cannot_be_scored():
    r = readings([2.0] * 4)              # only 3 minutes after minute 0
    assert actual_max(r, T0) is None
    assert outcome({"predictedMax": 3.0, "limitMax": 4.0}, None) == {"outcome": "no_data"}


def test_outcome_has_the_error_and_whether_the_limit_was_crossed():
    o = outcome({"predictedMax": 6.0, "limitMax": 4.0}, 5.5)
    assert o["outcome"] == "scored" and o["error"] == pytest.approx(0.5) and o["crossed"] is True
    assert outcome({"predictedMax": 3.0, "limitMax": 4.0}, 3.2)["crossed"] is False


def test_summary_counts_warnings_only_while_still_below_the_limit():
    df = pd.DataFrame([
        # warning, crossed, temperature, error
        (True, True, 3.0, 0.2),    # right warning
        (True, False, 3.0, 1.0),   # false warning
        (False, True, 3.5, -0.8),  # missed
        (False, False, 2.5, 0.1),  # right silence
        (True, True, 5.0, 0.0),    # already above the limit: the real alert is open, not counted
    ], columns=["warning", "crossed", "temperature", "error"]).assign(limitMax=4.0)
    s = summarize(df)
    assert s["n"] == 5
    assert s["mae_c"] == pytest.approx((0.2 + 1.0 + 0.8 + 0.1 + 0.0) / 5)
    assert s["bias_c"] == pytest.approx((0.2 + 1.0 - 0.8 + 0.1) / 5)
    assert (s["true_warning_minutes"], s["false_warning_minutes"], s["missed_minutes"]) == (1, 1, 1)
    assert s["precision"] == 0.5 and s["recall"] == 0.5


def test_summary_without_warnings_has_no_precision():
    df = pd.DataFrame({"warning": [False], "crossed": [False], "temperature": [2.0], "error": [0.1],
                       "limitMax": [4.0]})
    s = summarize(df)
    assert s["precision"] is None and s["recall"] is None

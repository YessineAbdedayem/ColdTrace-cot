import numpy as np
import pandas as pd

from evaluate import compare, episodes
from features import FEATURES, HORIZON_MIN, TARGET, add_features, add_target, label_priority, usable

T0 = pd.Timestamp("2026-11-01", tz="UTC")


def minutes(temps, door_s=0.0):
    n = len(temps)
    return pd.DataFrame({
        "temp": temps, "temp_max": temps, "humidity": [60.0] * n,
        "door_s": [door_s] * n, "n": [12] * n,
    }, index=pd.date_range(T0, periods=n, freq="1min", name="ts"))


def test_target_is_the_max_of_the_next_minutes_only():
    temps = [2.0] * 20 + [9.0] + [2.0] * 20
    df = add_target(add_features(minutes(temps), 4.0, False), 4.0)
    spike = 20
    assert df[TARGET].iloc[spike - 1] == 9.0 - 4.0                 # the spike is in its future
    assert df[TARGET].iloc[spike - HORIZON_MIN] == 9.0 - 4.0       # still inside the horizon
    assert df[TARGET].iloc[spike - HORIZON_MIN - 1] == 2.0 - 4.0   # just outside it
    assert df[TARGET].iloc[spike] == 2.0 - 4.0                     # the current minute is not the future


def test_features_only_look_at_the_past():
    temps = list(np.linspace(2, 3, 30))
    a = add_features(minutes(temps), 4.0, False)
    b = add_features(minutes(temps[:20] + [50.0] * 10), 4.0, False)
    pd.testing.assert_frame_equal(a[FEATURES].iloc[:20], b[FEATURES].iloc[:20])


def test_rising_temperature_gives_a_positive_slope():
    df = add_features(minutes([2.0 + 0.3 * i for i in range(20)]), 4.0, False)
    assert abs(df["slope_2"].iloc[-1] - 0.3) < 1e-9
    assert abs(df["slope_15"].iloc[-1] - 0.3) < 1e-9


def test_minutes_without_readings_are_not_usable():
    m = minutes([2.0] * 30)
    m.loc[m.index[25], ["temp", "temp_max", "humidity"]] = np.nan
    m.loc[m.index[25], "n"] = 0
    df = add_features(m, 4.0, False)
    ok = usable(df)
    assert not ok.iloc[25]
    assert ok.iloc[20]


def test_label_priority():
    assert label_priority([None, "recovery", "cooling_failure"]) == "cooling_failure"
    assert label_priority([None, "recovery"]) == "recovery"
    assert label_priority([None]) is None


def test_lead_time_is_measured_against_the_rule():
    ts = pd.date_range(T0, periods=30, freq="1min")
    rows = pd.DataFrame({"ts": ts, "sensorId": "S01", "label": [None] * 10 + ["cooling_failure"] * 20})
    rule = pd.Series([False] * 18 + [True] * 12)       # crosses the limit at minute 18
    model = pd.Series([False] * 12 + [True] * 18)      # warns at minute 12
    faults = pd.DataFrame({"sensorId": ["S01"], "fault": ["cooling_failure"],
                           "start": [ts[10]], "end": [ts[29]]})
    summary, detail = compare(rows, faults, {"rule": rule, "model": model})
    assert detail["model_lead_min"].iloc[0] == 6
    s = summary.set_index("method")
    assert s.loc["model", "detection_rate"] == 1.0
    assert s.loc["model", "false_alarms_per_sensor_day"] == 0


def test_warnings_during_normal_operation_are_false_alarms():
    ts = pd.date_range(T0, periods=10, freq="1min")
    rows = pd.DataFrame({"ts": ts, "sensorId": "S01", "label": [None] * 10})
    warn = pd.Series([False, True, True, False, False, True, False, False, False, False])
    assert len(episodes(rows, warn)) == 2


def test_promotion_needs_earlier_warnings_without_losing_anything():
    from tracking import _better
    prod = {"model_detection_rate": 1.0, "model_false_alarms_per_sensor_day": 0.4, "model_mean_lead_min": 5.0}
    assert _better(prod, None)[0], "the first model is always promoted"
    assert _better({**prod, "model_mean_lead_min": 6.0}, prod)[0]
    assert not _better(prod, prod)[0], "an identical model is not better"
    assert not _better({**prod, "model_mean_lead_min": 6.0, "model_false_alarms_per_sensor_day": 0.5}, prod)[0]
    assert not _better({**prod, "model_mean_lead_min": 6.0, "model_detection_rate": 0.9}, prod)[0]

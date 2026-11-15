"""Feature definitions, shared by training and serving so both compute exactly the same inputs.

Input: one row per sensor per minute, with the columns of MINUTE_COLUMNS, sorted by time,
one row for every minute (minutes without readings have n == 0 and NaN values).
"""

import pandas as pd

HORIZON_MIN = 10          # the target looks this many minutes ahead
HISTORY_MIN = 15          # the longest window a feature needs

MINUTE_COLUMNS = ["temp", "temp_max", "humidity", "door_s", "n"]

FEATURES = [
    "temp_rel",           # current temperature minus the profile's upper limit (negative = safe)
    "slope_2",            # °C per minute over the last 2 minutes (reacts fastest)
    "slope_3",
    "slope_5",            # °C per minute over the last 5 minutes
    "slope_15",           # °C per minute over the last 15 minutes
    "mean_10_rel",        # average of the last 10 minutes, relative to the limit
    "min_10_rel",
    "max_10_rel",
    "std_10",             # 0 for a stuck sensor
    "rise_10",            # current temperature minus the lowest of the last 10 minutes
    "door_s_10",          # seconds the door was open in the last 10 minutes
    "door_now",           # 1 if the door was open during the current minute
    "humidity",
    "humidity_slope_5",
    "missing_10",         # minutes without readings in the last 10
    "is_freezer",
]

TARGET = "target_rel"     # highest temperature of the next HORIZON_MIN minutes, minus the limit


def add_features(minutes: pd.DataFrame, limit_max: float, is_freezer: bool) -> pd.DataFrame:
    """Adds the FEATURES columns to the per-minute rows of one sensor."""
    df = minutes.copy()
    temp = df["temp"]
    df["temp_rel"] = temp - limit_max
    df["slope_2"] = (temp - temp.shift(2)) / 2
    df["slope_3"] = (temp - temp.shift(3)) / 3
    df["slope_5"] = (temp - temp.shift(5)) / 5
    df["slope_15"] = (temp - temp.shift(15)) / 15
    window = temp.rolling(10, min_periods=5)
    df["mean_10_rel"] = window.mean() - limit_max
    df["min_10_rel"] = window.min() - limit_max
    df["max_10_rel"] = df["temp_max"].rolling(10, min_periods=5).max() - limit_max
    df["std_10"] = window.std()
    df["rise_10"] = temp - window.min()
    df["door_s_10"] = df["door_s"].rolling(10, min_periods=1).sum()
    df["door_now"] = (df["door_s"] > 0).astype(float)
    df["humidity_slope_5"] = (df["humidity"] - df["humidity"].shift(5)) / 5
    df["missing_10"] = (df["n"] == 0).astype(float).rolling(10, min_periods=1).sum()
    df["is_freezer"] = float(is_freezer)
    return df


def add_target(df: pd.DataFrame, limit_max: float) -> pd.DataFrame:
    """Highest temperature over the next HORIZON_MIN minutes (not including the current one)."""
    future_max = (
        df["temp_max"][::-1].rolling(HORIZON_MIN, min_periods=HORIZON_MIN // 2).max()[::-1].shift(-1)
    )
    df[TARGET] = future_max - limit_max
    return df


def usable(df: pd.DataFrame) -> pd.Series:
    """Rows where the current minute has readings and every feature is defined."""
    return (df["n"] > 0) & df[FEATURES].notna().all(axis=1)


def label_priority(labels) -> str | None:
    """One label per minute: a fault beats recovery, recovery beats normal."""
    labels = [x for x in labels if x]
    for name in ("cooling_failure", "door_left_open", "sensor_stuck", "recovery"):
        if name in labels:
            return name
    return None


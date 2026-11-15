"""From raw readings (one every few seconds) to one prediction, with the same features as training."""

import pandas as pd

from features import FEATURES, HISTORY_MIN, add_features

# A minute with fewer readings than this is still in progress: it is left out.
MIN_READINGS_PER_MINUTE = 6


class NotEnoughData(ValueError):
    pass


def to_minutes(readings: pd.DataFrame) -> pd.DataFrame:
    """Same per-minute rows as prepare.py: average and max temperature, humidity, door seconds, count."""
    df = readings.copy()
    df["ts"] = pd.to_datetime(df["ts"], utc=True)
    df["minute"] = df["ts"].dt.floor("1min")
    df["door"] = df["doorOpen"].fillna(False).astype(bool)
    g = df.groupby("minute")
    minutes = pd.DataFrame({
        "temp": g["temperature"].mean(),
        "temp_max": g["temperature"].max(),
        "humidity": g["humidity"].mean(),
        "door_s": g["door"].sum() * 5.0,
        "n": g.size().astype(float),
    })
    minutes.index.name = "ts"
    if len(minutes) and minutes["n"].iloc[-1] < MIN_READINGS_PER_MINUTE:
        minutes = minutes.iloc[:-1]
    if minutes.empty:
        return minutes
    full = pd.date_range(minutes.index.min(), minutes.index.max(), freq="1min", name="ts")
    minutes = minutes.reindex(full)
    minutes["n"] = minutes["n"].fillna(0)
    minutes["door_s"] = minutes["door_s"].fillna(0)
    return minutes


def feature_row(readings: pd.DataFrame, limit_max: float, is_freezer: bool) -> pd.DataFrame:
    """The features of the latest complete minute, as a one-row frame."""
    minutes = to_minutes(readings)
    if len(minutes) < HISTORY_MIN + 1:
        raise NotEnoughData(f"need at least {HISTORY_MIN + 1} minutes of readings, got {len(minutes)}")
    row = add_features(minutes, limit_max, is_freezer).iloc[[-1]]
    if row["n"].iloc[0] == 0 or row[FEATURES].isna().any(axis=None):
        raise NotEnoughData("the latest minute has no readings or too many gaps")
    return row


def predict(model, margin: float, readings: pd.DataFrame, limit_max: float, is_freezer: bool) -> dict:
    row = feature_row(readings, limit_max, is_freezer)
    predicted_rel = float(model.predict(row[FEATURES])[0])
    door_now = bool(row["door_now"].iloc[0])
    return {
        "minute": row.index[0].isoformat(),
        "temperature": round(float(row["temp"].iloc[0]), 2),
        "predictedMax": round(predicted_rel + limit_max, 2),
        "limitMax": limit_max,
        "margin": margin,
        # same rule as training: above limit + margin, and only while the door is closed
        "warning": bool(predicted_rel > margin and not door_now),
        "doorOpen": door_now,
    }

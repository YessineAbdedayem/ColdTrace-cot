"""Step 2-3: export the readings, resample to one row per minute, compute features and target,
split by time into train and test sets.

Reads   coldtrace_ml.measurements, fault_log, coldtrace.thresholds, the sensors file
Writes  $DATA_DIR/train.parquet, test.parquet, faults.parquet, prepare.json
"""

import argparse
import json
import os
import sys
import time
from datetime import timedelta
from pathlib import Path

import pandas as pd
from pymongo import MongoClient

from features import FEATURES, TARGET, add_features, add_target, label_priority, usable

DEFAULT_LIMITS = {"cold": 4.0, "freezer": -18.0}


def minute_rows(db):
    """One document per sensor per minute, computed in MongoDB to keep memory low."""
    pipeline = [
        {"$group": {
            "_id": {"s": "$sensorId", "m": {"$dateTrunc": {"date": "$ts", "unit": "minute"}}},
            "temp": {"$avg": "$temperature"},
            "temp_max": {"$max": "$temperature"},
            "humidity": {"$avg": "$humidity"},
            "door_n": {"$sum": {"$cond": ["$doorOpen", 1, 0]}},
            "n": {"$sum": 1},
            "labels": {"$addToSet": "$label"},
        }},
    ]
    for doc in db["measurements"].aggregate(pipeline, allowDiskUse=True):
        yield {
            "sensorId": doc["_id"]["s"], "ts": doc["_id"]["m"],
            "temp": doc["temp"], "temp_max": doc["temp_max"], "humidity": doc["humidity"],
            "door_s": doc["door_n"] * 5.0, "n": doc["n"], "label": label_priority(doc["labels"]),
        }


def load_limits(source_db):
    limits = dict(DEFAULT_LIMITS)
    for doc in source_db["thresholds"].find():
        if doc.get("temperatureMax") is not None:
            limits[doc["_id"]] = float(doc["temperatureMax"])
    return limits


def build(minutes, sensors, limits):
    frames = []
    for sensor in sensors:
        sid, profile = sensor["sensorId"], sensor["profile"]
        df = minutes[minutes["sensorId"] == sid].set_index("ts").sort_index()
        if df.empty:
            continue
        # one row for every minute, so time windows are real time windows
        full = pd.date_range(df.index.min(), df.index.max(), freq="1min", name="ts")
        df = df.reindex(full)
        df["n"] = df["n"].fillna(0)
        df["door_s"] = df["door_s"].fillna(0)
        df["sensorId"] = sid
        limit = limits[profile]
        df = add_target(add_features(df, limit, profile == "freezer"), limit)
        df["limit_max"] = limit
        df = df[usable(df)].reset_index()
        frames.append(df)
    return pd.concat(frames, ignore_index=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--test-days", type=float, default=8, help="last N days kept for testing (default 8)")
    parser.add_argument("--sensors", default=os.environ.get("SENSORS_FILE", "/sensors/sensors.json"))
    args = parser.parse_args()

    uri = os.environ.get("MONGO_URI") or sys.exit("MONGO_URI is not set (see mlops/.env.example)")
    out = Path(os.environ.get("DATA_DIR", "/data"))
    out.mkdir(parents=True, exist_ok=True)

    client = MongoClient(uri)
    ml_db = client[os.environ.get("ML_DB", "coldtrace_ml")]
    limits = load_limits(client[os.environ.get("SOURCE_DB", "coldtrace")])
    with open(args.sensors, encoding="utf-8") as f:
        sensors = json.load(f)

    started = time.time()
    print("Resampling readings to one row per minute (in MongoDB)...")
    minutes = pd.DataFrame(minute_rows(ml_db))
    if minutes.empty:
        sys.exit("No readings in the ML database: run the generator first.")
    minutes["ts"] = pd.to_datetime(minutes["ts"], utc=True)
    print(f"  {len(minutes):,} sensor-minutes in {time.time() - started:.0f} s")

    data = build(minutes, sensors, limits)
    data = data[data[TARGET].notna()]

    cutoff = data["ts"].max() - timedelta(days=args.test_days)
    # leave one horizon between train and test, so no training target looks into the test period
    train = data[data["ts"] < cutoff - timedelta(minutes=15)]
    test = data[data["ts"] >= cutoff]

    columns = ["ts", "sensorId", "limit_max", "temp", "temp_max", "label"] + FEATURES + [TARGET]
    train[columns].to_parquet(out / "train.parquet", index=False)
    test[columns].to_parquet(out / "test.parquet", index=False)

    faults = pd.DataFrame(ml_db["fault_log"].find({}, {"_id": 0, "sensorId": 1, "fault": 1, "start": 1, "end": 1}))
    faults["start"] = pd.to_datetime(faults["start"], utc=True)
    faults["end"] = pd.to_datetime(faults["end"], utc=True)
    faults.to_parquet(out / "faults.parquet", index=False)

    summary = {
        "rows_train": len(train), "rows_test": len(test),
        "train_from": str(train["ts"].min()), "train_to": str(train["ts"].max()),
        "test_from": str(test["ts"].min()), "test_to": str(test["ts"].max()),
        "features": FEATURES, "target": TARGET, "limits": limits,
    }
    (out / "prepare.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")

    print(f"Train: {len(train):,} rows  {summary['train_from'][:16]} -> {summary['train_to'][:16]}")
    print(f"Test:  {len(test):,} rows  {summary['test_from'][:16]} -> {summary['test_to'][:16]}")
    print(f"Faults: {len(faults)}   Files in {out}   ({time.time() - started:.0f} s)")


if __name__ == "__main__":
    main()

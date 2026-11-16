"""Saves each live forecast in coldtrace_ml.predictions, so the monitor can compare it later with what happened.

One document per sensor, minute and model version: the API asks about every 30 s, so the same minute is often
predicted twice. Only the first answer is kept (the inputs are the same).

Saving is best effort: a forecast is never refused because the log could not be written.
"""

import logging
from datetime import datetime, timezone

from pymongo import ASCENDING

log = logging.getLogger("model-api")

COLLECTION = "predictions"


class PredictionLog:
    def __init__(self, ml_db):
        self.coll = ml_db[COLLECTION]
        self.indexed = False
        self.failing = False

    def save(self, sensor: dict, result: dict, model_version: str, horizon_min: int, features: dict):
        try:
            if not self.indexed:
                self.coll.create_index([("sensorId", ASCENDING), ("minute", ASCENDING), ("modelVersion", ASCENDING)],
                                       unique=True, name="one_per_minute_and_version")
                # the monitor looks for predictions that have no outcome yet
                self.coll.create_index([("outcome", ASCENDING), ("minute", ASCENDING)], name="to_score")
                self.indexed = True
            minute = datetime.fromisoformat(result["minute"])
            self.coll.update_one(
                {"sensorId": sensor["_id"], "minute": minute, "modelVersion": model_version},
                {"$setOnInsert": document(sensor, result, model_version, horizon_min, features)},
                upsert=True,
            )
            if self.failing:
                log.info("Prediction log working again")
                self.failing = False
        except Exception as e:  # never fail the forecast itself
            if not self.failing:
                log.warning("Could not save the prediction (monitoring will miss it): %s", str(e).splitlines()[0][:200])
                self.failing = True


def document(sensor: dict, result: dict, model_version: str, horizon_min: int, features: dict) -> dict:
    return {
        "sensorId": sensor["_id"],
        "siteId": sensor.get("siteId"),
        "profile": result.get("profile"),
        "minute": datetime.fromisoformat(result["minute"]),
        "modelVersion": model_version,
        "horizonMin": horizon_min,
        "temperature": result["temperature"],
        "predictedMax": result["predictedMax"],
        "limitMax": result["limitMax"],
        "margin": result["margin"],
        "warning": result["warning"],
        "doorOpen": result["doorOpen"],
        "features": features,
        "createdAt": datetime.now(timezone.utc),
        # filled in by the monitor once the horizon has passed: "scored" (with actualMax and error) or "no_data"
        "outcome": None,
    }

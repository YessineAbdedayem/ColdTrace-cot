"""Step 6: the early-warning model behind an HTTP API.

Loads models:/coldtrace-early-warning@production from MLflow, and checks every minute whether
the production alias moved, so promoting a model in MLflow deploys it without a restart.

  GET  /health                 is a model loaded, which version
  POST /predict                forecast from readings sent in the request
  GET  /forecast/{sensorId}    forecast from the sensor's latest readings in MongoDB
"""

import logging
import os
import threading
import time
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone

import pandas as pd
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from features import HISTORY_MIN, HORIZON_MIN
from forecast import NotEnoughData, predict

MODEL_NAME = os.environ.get("MODEL_NAME", "coldtrace-early-warning")
ALIAS = os.environ.get("MODEL_ALIAS", "production")
RELOAD_SECONDS = int(os.environ.get("RELOAD_SECONDS", "60"))
DEFAULT_LIMITS = {"cold": 4.0, "freezer": -18.0}
# readings fetched for /forecast: the feature history plus a few minutes of slack
LOOKBACK_MIN = HISTORY_MIN + 5

log = logging.getLogger("model-api")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")


class Loaded:
    model = None
    version = None
    margin = None
    run_id = None
    loaded_at = None
    error = None


state = Loaded()
lock = threading.Lock()


def load_production():
    """Loads the production model if its version changed. Safe to call repeatedly."""
    import mlflow
    from mlflow.tracking import MlflowClient

    client = MlflowClient()
    mv = client.get_model_version_by_alias(MODEL_NAME, ALIAS)
    if mv.version == state.version:
        return
    model = mlflow.pyfunc.load_model(f"models:/{MODEL_NAME}@{ALIAS}")
    margin = float(mv.tags.get("margin_c", "0"))
    with lock:
        state.model, state.version, state.margin, state.run_id = model, mv.version, margin, mv.run_id
        state.loaded_at, state.error = datetime.now(timezone.utc).isoformat(), None
    log.info("Loaded %s version %s (margin %.2f °C)", MODEL_NAME, mv.version, margin)


def keep_loaded():
    while True:
        try:
            load_production()
        except Exception as e:  # MLflow down or no production model yet: keep serving the last one
            state.error = str(e).splitlines()[0][:200]
            log.warning("Could not load the production model: %s", state.error)
        time.sleep(RELOAD_SECONDS)


@asynccontextmanager
async def lifespan(_app):
    threading.Thread(target=keep_loaded, daemon=True).start()
    yield


app = FastAPI(title="ColdTrace early warning", lifespan=lifespan,
              description="Predicts the highest temperature of the next 10 minutes and warns before the limit.")


def current():
    with lock:
        if state.model is None:
            raise HTTPException(503, f"no model loaded yet ({state.error or 'loading'})")
        return state.model, state.margin, state.version


def answer(sensor_id, result, version):
    return {"sensorId": sensor_id, "horizonMin": HORIZON_MIN, "modelVersion": version, **result}


@app.get("/health")
def health():
    return {"status": "ok" if state.model is not None else "loading", "model": MODEL_NAME, "alias": ALIAS,
            "modelVersion": state.version, "runId": state.run_id, "loadedAt": state.loaded_at,
            "margin": state.margin, "lastError": state.error}


class ReadingIn(BaseModel):
    ts: datetime
    temperature: float
    humidity: float | None = None
    doorOpen: bool = False


class PredictIn(BaseModel):
    sensorId: str = Field(examples=["S01"])
    profile: str = Field("cold", pattern="^(cold|freezer)$")
    limitMax: float | None = Field(None, description="upper limit in °C; default from the profile")
    readings: list[ReadingIn] = Field(description=f"at least {HISTORY_MIN + 1} minutes of readings, oldest first")


@app.post("/predict")
def predict_from_readings(body: PredictIn):
    model, margin, version = current()
    readings = pd.DataFrame([r.model_dump() for r in body.readings])
    if readings.empty:
        raise HTTPException(422, "readings is empty")
    limit = body.limitMax if body.limitMax is not None else DEFAULT_LIMITS[body.profile]
    try:
        result = predict(model, margin, readings, limit, body.profile == "freezer")
    except NotEnoughData as e:
        raise HTTPException(422, str(e))
    return answer(body.sensorId, result, version)


_mongo = None


def platform_db():
    global _mongo
    if _mongo is None:
        from pymongo import MongoClient
        uri = os.environ.get("MONGO_URI")
        if not uri:
            raise HTTPException(503, "MONGO_URI is not set")
        _mongo = MongoClient(uri, serverSelectionTimeoutMS=3000)
    return _mongo[os.environ.get("SOURCE_DB", "coldtrace")]


@app.get("/forecast/{sensor_id}")
def forecast_sensor(sensor_id: str):
    if not sensor_id.replace("-", "").replace("_", "").isalnum() or len(sensor_id) > 40:
        raise HTTPException(400, "invalid sensor id")
    model, margin, version = current()
    db = platform_db()
    sensor = db["sensors"].find_one({"_id": sensor_id})
    if sensor is None:
        raise HTTPException(404, f"unknown sensor {sensor_id}")
    profile = sensor.get("profile") or "cold"
    threshold = db["thresholds"].find_one({"_id": profile}) or {}
    limit = threshold.get("temperatureMax")
    limit = float(limit) if limit is not None else DEFAULT_LIMITS.get(profile, 4.0)

    since = datetime.now(timezone.utc) - timedelta(minutes=LOOKBACK_MIN)
    docs = list(db["measurements"].find(
        {"siteId": sensor.get("siteId"), "sensorId": sensor_id, "ts": {"$gte": since}},
        {"_id": 0, "ts": 1, "temperature": 1, "humidity": 1, "doorOpen": 1},
    ).sort("ts", 1))
    docs = [d for d in docs if d.get("temperature") is not None]
    if not docs:
        raise HTTPException(422, f"no readings for {sensor_id} in the last {LOOKBACK_MIN} minutes")
    try:
        result = predict(model, margin, pd.DataFrame(docs), limit, profile == "freezer")
    except NotEnoughData as e:
        raise HTTPException(422, str(e))
    return answer(sensor_id, {**result, "profile": profile}, version)

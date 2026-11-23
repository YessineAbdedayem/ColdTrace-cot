import numpy as np
import pandas as pd
import pytest
from fastapi.testclient import TestClient

import app as service
from features import FEATURES, add_features
from forecast import NotEnoughData, feature_row, predict, to_minutes

T0 = pd.Timestamp("2026-11-01 08:00", tz="UTC")


def readings(minutes=20, start_temp=2.5, rise_per_min=0.0, door_last_minute=False):
    """One reading every 5 s."""
    n = minutes * 12
    ts = pd.date_range(T0, periods=n, freq="5s")
    temp = start_temp + rise_per_min * np.arange(n) / 12
    door = np.zeros(n, dtype=bool)
    if door_last_minute:
        door[-12:] = True
    return pd.DataFrame({"ts": ts, "temperature": temp, "humidity": 60.0, "doorOpen": door})


class FollowTrend:
    """Stand-in for the model: current temperature relative to the limit + 10 min of the 2-min slope."""
    def predict(self, X):
        return (X["temp_rel"] + 10 * X["slope_2"]).to_numpy()


def test_minutes_match_the_training_resampling():
    r = readings(minutes=3, rise_per_min=0.6)
    m = to_minutes(r)
    assert len(m) == 3 and (m["n"] == 12).all()
    assert m["temp_max"].iloc[0] == pytest.approx(r["temperature"].iloc[11])


def test_an_unfinished_last_minute_is_left_out():
    r = pd.concat([readings(minutes=20), readings(minutes=1).assign(ts=lambda d: d["ts"] + pd.Timedelta("20min")).head(3)])
    assert len(to_minutes(r)) == 20


def test_feature_row_uses_the_shared_feature_code():
    r = readings(minutes=20, rise_per_min=0.1)
    row = feature_row(r, 4.0, False)
    expected = add_features(to_minutes(r), 4.0, False).iloc[[-1]]
    pd.testing.assert_frame_equal(row[FEATURES], expected[FEATURES])


def test_too_little_history_is_refused():
    with pytest.raises(NotEnoughData):
        feature_row(readings(minutes=5), 4.0, False)


def test_rising_temperature_warns_and_stable_does_not():
    assert predict(FollowTrend(), 0.0, readings(rise_per_min=0.3, start_temp=0.5), 4.0, False)["warning"]
    assert not predict(FollowTrend(), 0.0, readings(), 4.0, False)["warning"]


def test_no_warning_while_the_door_is_open():
    result = predict(FollowTrend(), 0.0, readings(rise_per_min=0.3, start_temp=0.5, door_last_minute=True), 4.0, False)
    assert result["doorOpen"] and not result["warning"]


def test_predict_endpoint():
    service.state.model, service.state.margin, service.state.version = FollowTrend(), 0.0, "7"
    client = TestClient(service.app)
    r = readings(rise_per_min=0.3, start_temp=0.5)
    body = {"sensorId": "S01", "profile": "cold",
            "readings": [{"ts": t.isoformat(), "temperature": v, "humidity": 60, "doorOpen": False}
                         for t, v in zip(r["ts"], r["temperature"])]}
    res = client.post("/predict", json=body)
    assert res.status_code == 200, res.text
    out = res.json()
    assert out["warning"] is True and out["modelVersion"] == "7" and out["limitMax"] == 4.0

    body["readings"] = body["readings"][:30]
    assert client.post("/predict", json=body).status_code == 422


def test_the_saved_inputs_are_the_model_inputs():
    from forecast import features_of
    row = feature_row(readings(minutes=20, rise_per_min=0.1), 4.0, False)
    saved = features_of(row)
    assert list(saved) == FEATURES
    assert saved["temp_rel"] == pytest.approx(row["temp_rel"].iloc[0], abs=1e-4)


def test_prediction_document_waits_for_the_monitor():
    from prediction_log import document
    result = predict(FollowTrend(), 0.0, readings(rise_per_min=0.3, start_temp=0.5), 4.0, False)
    doc = document({"_id": "S01", "siteId": "site01"}, {**result, "profile": "cold"}, "7", 10, {"temp_rel": -1.0})
    assert doc["sensorId"] == "S01" and doc["siteId"] == "site01" and doc["modelVersion"] == "7"
    assert doc["minute"].tzinfo is not None and doc["predictedMax"] == result["predictedMax"]
    assert doc["outcome"] is None            # the monitor fills it once the horizon has passed


def test_a_broken_prediction_log_never_fails_the_forecast():
    from prediction_log import PredictionLog

    class Down:
        def __getitem__(self, name):
            return self

        def create_index(self, *a, **k):
            raise ConnectionError("mongo down")

    result = predict(FollowTrend(), 0.0, readings(), 4.0, False)
    PredictionLog(Down()).save({"_id": "S01"}, result, "7", 10, {})   # must not raise


def test_no_model_means_503():
    service.state.model = None
    assert TestClient(service.app).post("/predict", json={"sensorId": "S01", "readings": []}).status_code == 503


@pytest.mark.parametrize("override,expected", [(None, 6.0), ("freezer", -18.0)])
def test_forecast_resolves_site_profile_and_sensor_override(monkeypatch, override, expected):
    sensor = {"_id": "V01", "siteId": "virtual"}
    if override:
        sensor["profile"] = override
    docs = readings().to_dict("records")
    saved = []

    class Collection:
        def __init__(self, name):
            self.name = name

        def find_one(self, query):
            return {"sensors": sensor, "sites": {"defaultProfile": "custom"},
                    "thresholds": {"temperatureMax": expected}}[self.name]

        def find(self, query, projection):
            assert query["siteId"] == "virtual" and query["sensorId"] == "V01"
            return self

        def sort(self, *args):
            return docs

    class Database:
        def __getitem__(self, name):
            return Collection(name)

    class Log:
        def save(self, *args):
            saved.append(args)

    monkeypatch.setattr(service, "platform_db", Database)
    monkeypatch.setattr(service, "prediction_log", Log)
    service.state.model, service.state.margin, service.state.version = FollowTrend(), 0.0, "7"
    response = TestClient(service.app).get("/forecast/V01")
    assert response.status_code == 200, response.text
    assert response.json()["limitMax"] == expected
    assert saved[0][1]["profile"] == (override or "custom")
    assert list(saved[0][-1]) == FEATURES


@pytest.mark.parametrize("disabled", ["sensors", "sites"])
def test_forecast_refuses_disabled_business_entities(monkeypatch, disabled):
    class Database:
        def __getitem__(self, name):
            self.name = name
            return self

        def find_one(self, query):
            return {"_id": "V01", "siteId": "virtual", "active": self.name != disabled}

    monkeypatch.setattr(service, "platform_db", Database)
    service.state.model, service.state.margin, service.state.version = FollowTrend(), 0.0, "7"
    assert TestClient(service.app).get("/forecast/V01").status_code == 422

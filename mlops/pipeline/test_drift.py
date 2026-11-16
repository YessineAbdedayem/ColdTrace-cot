import json

import numpy as np
import pandas as pd
import pytest

import drift

rng = np.random.default_rng(0)


def train_like(n=20_000, shift=0.0):
    return pd.DataFrame({
        "temp_rel": rng.normal(-1.5 + shift, 0.5, n),     # continuous
        "door_now": (rng.random(n) < 0.1).astype(float),   # 0/1
        "is_freezer": np.zeros(n),                         # constant
    })


FEATURES = ["temp_rel", "door_now", "is_freezer"]


def test_same_distribution_gives_no_drift():
    ref = drift.reference(train_like(), FEATURES)
    r = drift.report(ref, train_like(5_000))
    assert r["status"] == "ok"
    assert r["features"]["temp_rel"] < drift.WATCH


def test_a_shifted_input_is_reported_as_drifting():
    ref = drift.reference(train_like(), FEATURES)
    r = drift.report(ref, train_like(5_000, shift=1.0))     # 2 standard deviations warmer
    assert r["status"] == "drift"
    assert r["top"][0]["feature"] == "temp_rel" and "temp_rel" in r["drifting"]
    assert r["features"]["door_now"] < drift.WATCH            # the others did not move


def test_inputs_with_few_values_get_few_buckets():
    ref = drift.reference(train_like(), FEATURES)
    assert len(ref["temp_rel"]["shares"]) == drift.BUCKETS
    assert len(ref["door_now"]["shares"]) <= 3
    assert sum(ref["door_now"]["shares"]) == pytest.approx(1.0)
    # door always open live: a large change on that input
    live = train_like(5_000).assign(door_now=1.0)
    assert drift.report(ref, live)["features"]["door_now"] > drift.DRIFT


def test_a_value_outside_the_training_range_lands_in_the_edge_buckets():
    ref = drift.reference(train_like(), FEATURES)
    s = drift.shares(np.array([-100.0, 100.0]), ref["temp_rel"]["edges"])
    assert s[0] == 0.5 and s[-1] == 0.5


def test_too_few_live_rows_is_not_judged():
    ref = drift.reference(train_like(), FEATURES)
    r = drift.report(ref, train_like(50))
    assert r["status"] == "not_enough_data" and r["psi_max"] is None


def test_psi_thresholds():
    assert drift.status_of(0.05) == "ok"
    assert drift.status_of(0.2) == "watch"
    assert drift.status_of(0.3) == "drift"
    assert drift.status_of(None) == "unknown"


def test_reference_survives_a_round_trip_to_json(tmp_path):
    ref = drift.reference(train_like(), FEATURES)
    drift.save(ref, tmp_path / drift.REFERENCE_FILE)
    loaded = drift.load(tmp_path / drift.REFERENCE_FILE)
    assert loaded == json.loads(json.dumps(ref))
    assert drift.report(loaded, train_like(5_000))["status"] == "ok"

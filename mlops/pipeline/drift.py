"""Step 9b: do the live inputs still look like the training data?

For each of the model's inputs, training saves its distribution in 10 buckets (drift_reference.json, logged to MLflow
with the model). The monitor puts the live inputs of the last 24 h into the same buckets and compares the two with the
Population Stability Index:

    PSI = sum over buckets of (live% - train%) * ln(live% / train%)

    < 0.10       ok      the input looks like in training
    0.10 - 0.25  watch   it has moved a little
    > 0.25       drift   it has really changed: the model sees data it was not trained on

A drifting input does not mean the model is wrong yet, but its training no longer covers what it sees.
"""

import json
import math
from pathlib import Path

import numpy as np
import pandas as pd

BUCKETS = 10
WATCH, DRIFT = 0.10, 0.25
# Below this many live rows, the comparison is mostly noise.
MIN_ROWS = 200
# An empty bucket would make ln() infinite: floor every share at this value.
EPS = 1e-4
REFERENCE_FILE = "drift_reference.json"


def reference(df: pd.DataFrame, features: list[str], buckets: int = BUCKETS, weights=None) -> dict:
    """Bucket edges (training quantiles) and the share of training rows in each bucket, for each input.

    With `weights` (the training weights of the rows), the reference describes the data as the model weighed it:
    rows are redrawn in proportion to their weight (fixed seed, so the same data gives the same reference).
    """
    if weights is not None and len(df) and not np.allclose(weights, weights[0]):
        p = np.asarray(weights, dtype=float) / np.sum(weights)
        df = df.iloc[np.random.default_rng(0).choice(len(df), size=len(df), p=p)]
    out = {}
    for name in features:
        values = df[name].dropna().to_numpy(dtype=float)
        if len(values) == 0:
            continue
        # inner edges only: buckets are (-inf, e1], (e1, e2], ..., (ek, +inf). Inputs with few distinct values
        # (door_now, is_freezer, missing_10) get fewer, wider buckets.
        edges = np.unique(np.quantile(values, np.linspace(0, 1, buckets + 1)[1:-1]))
        out[name] = {"edges": [float(e) for e in edges], "shares": shares(values, edges).tolist(),
                     "rows": int(len(values))}
    return out


def shares(values: np.ndarray, edges) -> np.ndarray:
    """Share of values in each bucket defined by the inner edges."""
    idx = np.searchsorted(np.asarray(edges, dtype=float), values, side="left")
    counts = np.bincount(idx, minlength=len(edges) + 1)
    return counts / max(len(values), 1)


def psi(expected, actual) -> float:
    e = np.clip(np.asarray(expected, dtype=float), EPS, None)
    a = np.clip(np.asarray(actual, dtype=float), EPS, None)
    return float(np.sum((a - e) * np.log(a / e)))


def status_of(value: float | None) -> str:
    if value is None or math.isnan(value):
        return "unknown"
    return "drift" if value > DRIFT else "watch" if value > WATCH else "ok"


def report(ref: dict, live: pd.DataFrame, min_rows: int = MIN_ROWS) -> dict:
    """Compares the live inputs with the reference. `live` has one column per input."""
    rows = int(len(live))
    if rows < min_rows:
        return {"status": "not_enough_data", "rows": rows, "psi_max": None, "features": {}, "top": []}
    per_feature = {}
    for name, r in ref.items():
        if name not in live:
            continue
        values = live[name].dropna().to_numpy(dtype=float)
        if len(values) == 0:
            continue
        per_feature[name] = round(psi(r["shares"], shares(values, r["edges"])), 4)
    if not per_feature:
        return {"status": "unknown", "rows": rows, "psi_max": None, "features": {}, "top": []}
    worst = sorted(per_feature.items(), key=lambda kv: kv[1], reverse=True)
    psi_max = worst[0][1]
    return {
        "status": status_of(psi_max),
        "rows": rows,
        "psi_max": psi_max,
        "features": per_feature,
        "top": [{"feature": n, "psi": v, "status": status_of(v)} for n, v in worst[:5]],
        "drifting": [n for n, v in worst if v > DRIFT],
    }


def save(ref: dict, path: Path):
    path.write_text(json.dumps(ref, indent=1), encoding="utf-8")


def load(path: Path) -> dict:
    return json.loads(Path(path).read_text(encoding="utf-8"))

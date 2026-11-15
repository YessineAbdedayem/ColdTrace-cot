"""Event-level evaluation: does a warning come earlier than the rule, and how often is it wrong?

Works on per-minute rows of one or more sensors with columns ts, sensorId, label, and a boolean
column per method ("warn" = the method says the limit will be crossed, or has been).
"""

import pandas as pd

# Faults the early warning is for. A door left open is already caught at once by the door
# alert, and silent or stuck sensors are other problems.
WARMING_FAULTS = ("cooling_failure",)


def episodes(rows: pd.DataFrame, warn: pd.Series) -> pd.DataFrame:
    """Merges consecutive warning minutes of a sensor into episodes (start ts, start label)."""
    df = rows.assign(warn=warn.values).sort_values(["sensorId", "ts"])
    out = []
    for sid, g in df.groupby("sensorId"):
        w = g["warn"].to_numpy()
        gap = g["ts"].diff().dt.total_seconds().fillna(60).to_numpy() > 60
        starts = w & (~pd.Series(w).shift(fill_value=False).to_numpy() | gap)
        out.append(g.loc[starts, ["sensorId", "ts", "label"]])
    return pd.concat(out, ignore_index=True) if out else pd.DataFrame(columns=["sensorId", "ts", "label"])


def false_alarms_per_sensor_day(rows: pd.DataFrame, warn: pd.Series) -> float:
    """Warning episodes that start while the unit is normal (no fault, no recovery)."""
    eps = episodes(rows, warn)
    false = eps[eps["label"].isna()]
    sensor_days = rows.groupby("sensorId")["ts"].agg(lambda t: (t.max() - t.min()).total_seconds() / 86400).sum()
    return len(false) / max(sensor_days, 1e-9)


def first_time(rows: pd.DataFrame, warn: pd.Series, sensor: str, start, end):
    mask = (rows["sensorId"] == sensor) & (rows["ts"] >= start) & (rows["ts"] <= end) & warn
    hit = rows.loc[mask, "ts"]
    return hit.min() if not hit.empty else None


def compare(rows: pd.DataFrame, faults: pd.DataFrame, methods: dict) -> tuple[pd.DataFrame, pd.DataFrame]:
    """methods = {"rule": bool Series, "model": bool Series, ...} aligned with rows.

    Returns (summary per method, detail per fault). Lead time is measured against the rule:
    positive = the method warned that many minutes before the rule's alert.
    """
    t0, t1 = rows["ts"].min(), rows["ts"].max()
    targets = faults[faults["fault"].isin(WARMING_FAULTS) & (faults["start"] >= t0) & (faults["end"] <= t1)]
    detail = []
    for f in targets.itertuples():
        # a fault "counts" for the rule only if the unit really crossed the limit
        rule_at = first_time(rows, methods["rule"], f.sensorId, f.start, f.end)
        row = {"sensorId": f.sensorId, "fault": f.fault, "start": f.start, "rule_at": rule_at}
        for name, warn in methods.items():
            at = first_time(rows, warn, f.sensorId, f.start, f.end)
            row[f"{name}_at"] = at
            row[f"{name}_lead_min"] = (rule_at - at).total_seconds() / 60 if (at is not None and rule_at is not None) else None
        detail.append(row)
    detail = pd.DataFrame(detail)

    summary = []
    crossed = detail[detail["rule_at"].notna()] if not detail.empty else detail
    for name, warn in methods.items():
        caught = crossed[f"{name}_at"].notna() if not crossed.empty else pd.Series(dtype=bool)
        leads = crossed[f"{name}_lead_min"].dropna() if not crossed.empty else pd.Series(dtype=float)
        summary.append({
            "method": name,
            "faults_that_crossed": len(crossed),
            "detected": int(caught.sum()),
            "detection_rate": float(caught.mean()) if len(caught) else 0.0,
            "median_lead_min": float(leads.median()) if len(leads) else 0.0,
            "mean_lead_min": float(leads.mean()) if len(leads) else 0.0,
            "false_alarms_per_sensor_day": false_alarms_per_sensor_day(rows, warn),
        })
    return pd.DataFrame(summary), detail

"""Synthetic training data for the ColdTrace MLOps pipeline.

Writes weeks of realistic readings for the simulated sensors into a separate database
(coldtrace_ml by default), with every injected fault logged as ground truth:

  measurements    one document every 5 s per sensor, same fields as the real collection,
                  plus synthetic=true and label: the active fault, "recovery" while the unit
                  cools back down after one, or null when normal
  fault_log       one document per injected fault: sensor, type, start, end, parameters
  rule_incidents  what the current threshold rules would have opened on this data,
                  to compare the model against the rules

The label field is the answer key: never use it as a model input.

Usage (inside the container):  python generate.py --days 30 --reset
"""

import argparse
import json
import os
import random
import sys
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone

TICK_SECONDS = 5
TICKS_PER_MIN = 60 // TICK_SECONDS

# Same rules as the alert engine (middleware/api, AlertRules): close after 3 in-range readings,
# a sensor is silent after 60 s without readings, any open door is an incident.
IN_RANGE_TO_CLOSE = 3
SILENT_AFTER_SECONDS = 60

# Used only when the thresholds collection is empty (same values as Threshold.DEFAULTS).
DEFAULT_LIMITS = {
    "cold": {"temperatureMin": 0.0, "temperatureMax": 4.0},
    "freezer": {"temperatureMin": None, "temperatureMax": -18.0},
}

# Normal behaviour per profile. Rates are in °C per minute.
PROFILES = {
    "cold": {
        "setpoint": 2.5, "band": 0.6,          # compressor keeps the air in setpoint ± band
        "cool_rate": (0.08, 0.15),             # compressor on
        "leak_rate": (0.02, 0.05),             # compressor off, heat leaking in
        "door_rate": (0.4, 0.9),               # air warming while the door is open
        "ambient": 22.0,
        "failure_rate": (0.08, 0.5),           # initial warming speed of a cooling failure
        "pulldown_rate": (0.15, 0.3),          # recovery after repair
        "humidity": 60.0,
    },
    "freezer": {
        "setpoint": -20.5, "band": 0.8,
        "cool_rate": (0.10, 0.20),
        "leak_rate": (0.02, 0.06),
        "door_rate": (0.6, 1.2),
        "ambient": 22.0,
        "failure_rate": (0.1, 0.6),
        "pulldown_rate": (0.2, 0.4),
        "humidity": 55.0,
    },
}

# Injected faults: share of each type, duration range in minutes.
FAULTS = {
    "cooling_failure": {"weight": 0.50, "minutes": (20, 120)},
    "door_left_open": {"weight": 0.25, "minutes": (3, 25)},
    "sensor_silence": {"weight": 0.15, "minutes": (2, 40)},
    "sensor_stuck": {"weight": 0.10, "minutes": (15, 90)},
}
FAULT_MEAN_GAP_HOURS = 8       # average time between two faults on one sensor
FAULT_MIN_GAP_MINUTES = 60     # quiet time after a fault, so episodes don't overlap
PACKET_LOSS = 0.003            # share of readings lost in transit
MEASUREMENT_NOISE = 0.03       # °C, sensor noise added on top of the air temperature


@dataclass
class Fault:
    sensor_id: str
    site_id: str
    kind: str
    start_tick: int
    end_tick: int
    params: dict = field(default_factory=dict)


@dataclass
class Reading:
    tick: int
    temperature: float
    humidity: float
    door_open: bool
    label: str | None


def per_tick(rate_per_min):
    return rate_per_min / TICKS_PER_MIN


def schedule_faults(sensor, n_ticks, rng):
    """Random, non-overlapping faults for one sensor."""
    faults = []
    kinds = list(FAULTS)
    weights = [FAULTS[k]["weight"] for k in kinds]
    tick = int(rng.expovariate(1 / (FAULT_MEAN_GAP_HOURS * 60)) * TICKS_PER_MIN)
    while tick < n_ticks:
        kind = rng.choices(kinds, weights)[0]
        low, high = FAULTS[kind]["minutes"]
        duration = int(rng.uniform(low, high) * TICKS_PER_MIN)
        end = min(tick + duration, n_ticks)
        params = {"minutes": round((end - tick) / TICKS_PER_MIN, 1)}
        profile = PROFILES[sensor["profile"]]
        if kind == "cooling_failure":
            params["rate"] = round(rng.uniform(*profile["failure_rate"]), 3)
        elif kind == "door_left_open":
            params["rate"] = round(rng.uniform(*profile["door_rate"]), 3)
        faults.append(Fault(sensor["sensorId"], sensor["siteId"], kind, tick, end, params))
        gap = FAULT_MIN_GAP_MINUTES + rng.expovariate(1 / (FAULT_MEAN_GAP_HOURS * 60))
        tick = end + int(gap * TICKS_PER_MIN)
    return faults


def door_chance(profile_name, hour):
    """Probability that a normal (short) door opening starts on this tick."""
    if not 7 <= hour < 20:
        return 0.0
    per_hour = 3.0 if profile_name == "cold" else 1.5
    return per_hour / (60 * TICKS_PER_MIN)


def simulate_sensor(sensor, start, n_ticks, rng):
    """Returns (readings, faults) for one sensor. Missing ticks are simply absent."""
    name = sensor["profile"]
    p = PROFILES[name]
    bias = rng.uniform(-0.3, 0.3)            # each unit runs slightly warmer or colder
    low = p["setpoint"] + bias - p["band"]
    high = p["setpoint"] + bias + p["band"]
    cool = per_tick(rng.uniform(*p["cool_rate"]))
    leak = per_tick(rng.uniform(*p["leak_rate"]))
    pulldown = per_tick(rng.uniform(*p["pulldown_rate"]))
    humidity_base = p["humidity"] + rng.uniform(-3, 3)

    faults = schedule_faults(sensor, n_ticks, rng)
    by_tick = {}
    for f in faults:
        for t in range(f.start_tick, f.end_tick):
            by_tick[t] = f

    temp = rng.uniform(low, high)
    humidity = humidity_base
    compressor_on = rng.random() < 0.5
    short_door_left = 0
    recovering = False
    stuck_value = None
    readings = []

    for tick in range(n_ticks):
        fault = by_tick.get(tick)
        kind = fault.kind if fault else None
        hour = (start + timedelta(seconds=tick * TICK_SECONDS)).hour

        # door: a long fault opening, or a short normal one during working hours
        if kind == "door_left_open":
            door = True
        elif short_door_left > 0:
            short_door_left -= 1
            door = True
        elif rng.random() < door_chance(name, hour):
            short_door_left = rng.randint(2, 12)  # 10 to 60 s
            door = True
        else:
            door = False

        # air temperature
        if kind == "cooling_failure":
            # warms toward the room temperature, fastest at the start
            k = per_tick(fault.params["rate"]) / max(p["ambient"] - p["setpoint"], 1)
            temp += (p["ambient"] - temp) * k
            recovering = True
        elif door:
            rate = fault.params["rate"] if kind == "door_left_open" else rng.uniform(*p["door_rate"])
            k = per_tick(rate) / max(p["ambient"] - p["setpoint"], 1)
            temp += (p["ambient"] - temp) * k
            compressor_on = True
            recovering = recovering or kind == "door_left_open"
        elif recovering:
            temp -= pulldown                     # compressor at full power after a fault
            if temp <= high:
                recovering = False
                compressor_on = True
        else:
            temp += -cool if compressor_on else leak
            if temp <= low:
                compressor_on = False
            elif temp >= high:
                compressor_on = True

        # humidity follows the door and the temperature loosely
        target_h = humidity_base + (8 if door else 0) + (5 if kind == "cooling_failure" else 0)
        humidity += (target_h - humidity) * 0.05 + rng.gauss(0, 0.3)
        humidity = min(95.0, max(30.0, humidity))

        if kind == "sensor_silence" or rng.random() < PACKET_LOSS:
            continue

        measured = temp + rng.gauss(0, MEASUREMENT_NOISE)
        if kind == "sensor_stuck":
            if stuck_value is None:
                stuck_value = round(measured, 2)
            measured = stuck_value
        else:
            stuck_value = None

        label = kind or ("recovery" if recovering else None)
        readings.append(Reading(tick, round(measured, 2), round(humidity, 1), door, label))

    return readings, faults


def replay_rules(sensor, readings, limits, start):
    """What the current alert engine would open on these readings (temperature, door, silence)."""
    t_min, t_max = limits.get("temperatureMin"), limits.get("temperatureMax")
    incidents = []
    open_by_metric = {}
    in_range = {"temperature": 0, "door": 0}
    previous_tick = None

    def at(tick):
        return start + timedelta(seconds=tick * TICK_SECONDS)

    def open_incident(metric, tick, value):
        open_by_metric[metric] = {
            "siteId": sensor["siteId"], "sensorId": sensor["sensorId"], "metric": metric,
            "openedAt": at(tick), "closedAt": None, "peakValue": value,
        }

    def close_incident(metric, tick):
        incident = open_by_metric.pop(metric)
        incident["closedAt"] = at(tick)
        incidents.append(incident)

    for r in readings:
        if previous_tick is not None and (r.tick - previous_tick) * TICK_SECONDS > SILENT_AFTER_SECONDS:
            incidents.append({
                "siteId": sensor["siteId"], "sensorId": sensor["sensorId"], "metric": "silence",
                "openedAt": at(previous_tick) + timedelta(seconds=SILENT_AFTER_SECONDS),
                "closedAt": at(r.tick), "peakValue": None,
            })
        previous_tick = r.tick

        values = {"temperature": r.temperature, "door": 1.0 if r.door_open else 0.0}
        bounds = {"temperature": (t_min, t_max), "door": (None, 0.0)}
        for metric, value in values.items():
            lo, hi = bounds[metric]
            breached = (lo is not None and value < lo) or (hi is not None and value > hi)
            if breached:
                in_range[metric] = 0
                if metric not in open_by_metric:
                    open_incident(metric, r.tick, value)
                else:
                    current = open_by_metric[metric]
                    if hi is not None and value > hi:
                        current["peakValue"] = max(current["peakValue"], value)
                    else:
                        current["peakValue"] = min(current["peakValue"], value)
            elif metric in open_by_metric:
                in_range[metric] += 1
                if in_range[metric] >= IN_RANGE_TO_CLOSE:
                    close_incident(metric, r.tick)
                    in_range[metric] = 0

    for metric in list(open_by_metric):
        incidents.append(open_by_metric.pop(metric))
    return incidents


def to_documents(sensor, readings, start):
    site, sensor_id = sensor["siteId"], sensor["sensorId"]
    for r in readings:
        ts = start + timedelta(seconds=r.tick * TICK_SECONDS)
        yield {
            "v": 1,
            "msgId": f"syn-{sensor_id}-{int(ts.timestamp())}",
            "siteId": site,
            "sensorId": sensor_id,
            "gatewayId": f"gw-{site}",
            "ts": ts,
            "tsSource": "device",
            "temperature": r.temperature,
            "humidity": r.humidity,
            "doorOpen": r.door_open,
            "receivedAt": ts + timedelta(milliseconds=200),
            "replayed": False,
            "synthetic": True,
            "label": r.label,
        }


def fault_documents(faults, start):
    for f in faults:
        yield {
            "siteId": f.site_id,
            "sensorId": f.sensor_id,
            "fault": f.kind,
            "start": start + timedelta(seconds=f.start_tick * TICK_SECONDS),
            "end": start + timedelta(seconds=f.end_tick * TICK_SECONDS),
            "params": f.params,
        }


def load_limits(source_db):
    limits = {k: dict(v) for k, v in DEFAULT_LIMITS.items()}
    for doc in source_db["thresholds"].find():
        limits[doc["_id"]] = {
            "temperatureMin": doc.get("temperatureMin"),
            "temperatureMax": doc.get("temperatureMax"),
        }
    return limits


def insert_in_batches(collection, documents, size=10_000):
    batch, total = [], 0
    for doc in documents:
        batch.append(doc)
        if len(batch) == size:
            collection.insert_many(batch, ordered=False)
            total += len(batch)
            batch = []
    if batch:
        collection.insert_many(batch, ordered=False)
        total += len(batch)
    return total


def lead_time_summary(faults, incidents):
    """For cooling failures: minutes between the fault start and the rule's temperature alert.
    This is the time an early-warning model can win."""
    leads, missed = [], 0
    temp_incidents = [i for i in incidents if i["metric"] == "temperature"]
    for f in faults:
        if f["fault"] != "cooling_failure":
            continue
        alert = next((i["openedAt"] for i in temp_incidents
                      if i["sensorId"] == f["sensorId"] and f["start"] <= i["openedAt"] <= f["end"]), None)
        if alert is None:
            missed += 1
        else:
            leads.append((alert - f["start"]).total_seconds() / 60)
    return leads, missed


def main():
    from pymongo import ASCENDING, MongoClient

    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--days", type=float, default=30, help="history length (default 30)")
    parser.add_argument("--seed", type=int, default=42, help="random seed, same seed = same data")
    parser.add_argument("--end", help="end of the history, ISO date (default: now)")
    parser.add_argument("--reset", action="store_true", help="drop the generated collections first")
    parser.add_argument("--sensors", default=os.environ.get("SENSORS_FILE", "/data/sensors.json"))
    args = parser.parse_args()

    uri = os.environ.get("MONGO_URI")
    if not uri:
        sys.exit("MONGO_URI is not set (see mlops/.env.example)")
    ml_db_name = os.environ.get("ML_DB", "coldtrace_ml")
    source_db_name = os.environ.get("SOURCE_DB", "coldtrace")

    end = datetime.fromisoformat(args.end) if args.end else datetime.now(timezone.utc)
    if end.tzinfo is None:
        end = end.replace(tzinfo=timezone.utc)
    end = end.replace(microsecond=0) - timedelta(seconds=end.second % TICK_SECONDS)
    n_ticks = int(args.days * 24 * 3600 / TICK_SECONDS)
    start = end - timedelta(seconds=n_ticks * TICK_SECONDS)

    with open(args.sensors, encoding="utf-8") as f:
        sensors = json.load(f)

    client = MongoClient(uri)
    ml_db = client[ml_db_name]
    limits = load_limits(client[source_db_name])

    names = ["measurements", "fault_log", "rule_incidents"]
    if args.reset:
        for name in names:
            ml_db[name].drop()
    elif ml_db["measurements"].estimated_document_count() > 0:
        sys.exit(f"{ml_db_name}.measurements already has data. Run again with --reset to replace it.")

    print(f"Generating {args.days:g} days for {len(sensors)} sensors, {start:%Y-%m-%d %H:%M} -> {end:%Y-%m-%d %H:%M} UTC, seed {args.seed}")
    rng = random.Random(args.seed)
    all_faults, all_incidents, total = [], [], 0
    for sensor in sensors:
        readings, faults = simulate_sensor(sensor, start, n_ticks, rng)
        incidents = replay_rules(sensor, readings, limits[sensor["profile"]], start)
        total += insert_in_batches(ml_db["measurements"], to_documents(sensor, readings, start))
        all_faults.extend(fault_documents(faults, start))
        all_incidents.extend(incidents)
        print(f"  {sensor['sensorId']} ({sensor['profile']}): {len(readings):,} readings, {len(faults)} faults")

    if all_faults:
        ml_db["fault_log"].insert_many(all_faults)
    if all_incidents:
        ml_db["rule_incidents"].insert_many(all_incidents)
    ml_db["measurements"].create_index([("sensorId", ASCENDING), ("ts", ASCENDING)], name="sensor_ts")
    ml_db["fault_log"].create_index([("sensorId", ASCENDING), ("start", ASCENDING)], name="sensor_start")
    ml_db["rule_incidents"].create_index([("sensorId", ASCENDING), ("openedAt", ASCENDING)], name="sensor_opened")

    print(f"\nWrote {total:,} measurements to {ml_db_name}.measurements")
    print("Faults injected:")
    for kind in FAULTS:
        print(f"  {kind:16} {sum(1 for f in all_faults if f['fault'] == kind)}")
    print("Incidents the current rules would open:")
    for metric in ("temperature", "door", "silence"):
        print(f"  {metric:16} {sum(1 for i in all_incidents if i['metric'] == metric)}")
    leads, missed = lead_time_summary(all_faults, all_incidents)
    if leads:
        leads.sort()
        print(f"Cooling failures: the rule alerts {statistics_line(leads)} after the failure starts"
              f" ({missed} never crossed the limit before repair)")


def statistics_line(values):
    median = values[len(values) // 2]
    return f"{median:.1f} min (median), {values[0]:.1f} to {values[-1]:.1f} min"


if __name__ == "__main__":
    main()

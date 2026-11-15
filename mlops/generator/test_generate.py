import random
from datetime import datetime, timezone

import generate as g

START = datetime(2026, 11, 1, tzinfo=timezone.utc)
COLD = {"siteId": "site01", "sensorId": "S01", "profile": "cold"}
FREEZER = {"siteId": "site02", "sensorId": "S05", "profile": "freezer"}
COLD_LIMITS = g.DEFAULT_LIMITS["cold"]
DAY = 24 * 3600 // g.TICK_SECONDS


def one_fault(monkeypatch, kind, minutes, start_minute=60, **params):
    """Replace the random schedule with a single known fault."""
    def schedule(sensor, n_ticks, rng):
        start = start_minute * g.TICKS_PER_MIN
        p = {"minutes": minutes, **params}
        return [g.Fault(sensor["sensorId"], sensor["siteId"], kind, start, start + minutes * g.TICKS_PER_MIN, p)]
    monkeypatch.setattr(g, "schedule_faults", schedule)


def test_same_seed_gives_same_data():
    a, _ = g.simulate_sensor(COLD, START, DAY, random.Random(7))
    b, _ = g.simulate_sensor(COLD, START, DAY, random.Random(7))
    assert a == b


def test_normal_operation_stays_inside_the_limits(monkeypatch):
    monkeypatch.setattr(g, "schedule_faults", lambda *a: [])
    monkeypatch.setattr(g, "door_chance", lambda *a: 0.0)
    for sensor in (COLD, FREEZER):
        readings, _ = g.simulate_sensor(sensor, START, DAY, random.Random(1))
        limits = g.DEFAULT_LIMITS[sensor["profile"]]
        assert all(r.temperature < limits["temperatureMax"] for r in readings)
        assert all(r.label is None for r in readings)


def test_cooling_failure_warms_slowly_then_crosses_the_limit(monkeypatch):
    one_fault(monkeypatch, "cooling_failure", minutes=60, rate=0.2)
    readings, _ = g.simulate_sensor(COLD, START, 3 * 60 * g.TICKS_PER_MIN, random.Random(2))
    during = [r for r in readings if r.label == "cooling_failure"]
    assert during, "the fault must label its readings"
    first_breach = next(r for r in during if r.temperature > COLD_LIMITS["temperatureMax"])
    minutes_to_breach = (first_breach.tick - during[0].tick) / g.TICKS_PER_MIN
    assert minutes_to_breach > 3, "a realistic failure takes minutes, not seconds, to cross the limit"
    assert readings[-1].temperature < COLD_LIMITS["temperatureMax"], "the unit recovers after repair"


def test_sensor_silence_leaves_a_gap(monkeypatch):
    one_fault(monkeypatch, "sensor_silence", minutes=10)
    readings, _ = g.simulate_sensor(COLD, START, 2 * 60 * g.TICKS_PER_MIN, random.Random(3))
    ticks = {r.tick for r in readings}
    gap = range(60 * g.TICKS_PER_MIN, 70 * g.TICKS_PER_MIN)
    assert not any(t in ticks for t in gap)


def test_stuck_sensor_repeats_one_value(monkeypatch):
    one_fault(monkeypatch, "sensor_stuck", minutes=20)
    readings, _ = g.simulate_sensor(COLD, START, 2 * 60 * g.TICKS_PER_MIN, random.Random(4))
    stuck = {r.temperature for r in readings if r.label == "sensor_stuck"}
    assert len(stuck) == 1


def test_rule_replay_opens_and_closes_like_the_alert_engine():
    temps = [3.0, 4.5, 5.0, 3.5, 3.6, 3.7, 3.0]
    readings = [g.Reading(i, t, 60.0, False, None) for i, t in enumerate(temps)]
    incidents = g.replay_rules(COLD, readings, COLD_LIMITS, START)
    temperature = [i for i in incidents if i["metric"] == "temperature"]
    assert len(temperature) == 1
    assert temperature[0]["peakValue"] == 5.0
    # closes on the 3rd in-range reading (tick 5), as in AlertRules
    assert (temperature[0]["closedAt"] - START).total_seconds() == 5 * g.TICK_SECONDS


def test_rule_replay_detects_silence():
    readings = [g.Reading(0, 3.0, 60.0, False, None), g.Reading(30, 3.0, 60.0, False, None)]
    incidents = g.replay_rules(COLD, readings, COLD_LIMITS, START)
    assert [i["metric"] for i in incidents] == ["silence"]

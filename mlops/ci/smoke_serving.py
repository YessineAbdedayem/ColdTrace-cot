"""CI check: the model API, started against the CI MLflow, loads production and answers /predict.

Usage: python smoke_serving.py http://localhost:8000
"""

import json
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone

base = sys.argv[1].rstrip("/")


def call(path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path, data=data, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=10) as res:
        return json.loads(res.read())


for _ in range(60):
    try:
        if call("/health").get("status") == "ok":
            break
    except (urllib.error.URLError, ConnectionError):
        pass
    time.sleep(2)
else:
    sys.exit("model API did not load a model within 2 minutes")
print("health:", call("/health"))

# 20 minutes of a fridge whose cooling fails halfway: steady at 2.5 °C, then +0.3 °C per minute
start = datetime.now(timezone.utc).replace(second=0, microsecond=0) - timedelta(minutes=20)
readings = []
for i in range(20 * 12):
    minute = i / 12
    temp = 2.5 + max(0.0, minute - 14) * 0.3
    readings.append({"ts": (start + timedelta(seconds=5 * i)).isoformat(), "temperature": round(temp, 2),
                     "humidity": 60.0, "doorOpen": False})

stable = call("/predict", {"sensorId": "CI", "profile": "cold",
                           "readings": [dict(r, temperature=2.5) for r in readings]})
failing = call("/predict", {"sensorId": "CI", "profile": "cold", "readings": readings})
print("stable: ", stable)
print("failing:", failing)
if stable["warning"]:
    sys.exit("a stable fridge must not raise a warning")
if not failing["warning"]:
    sys.exit("a fridge warming 0.3 °C/min for 5 minutes must raise a warning")
print("model API: OK")

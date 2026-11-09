# ColdTrace back-end: recap

**Scope:** `api-rest/`, the Jakarta EE 11 back-end
**Owner of the back-end skeleton:** Oussema Kard
**Contributions:** Khalil Hedhili (gateway message format, alert engine, Compose integration)
**Date:** 9 November 2026

## 1. Goal

The back-end receives the sensor readings sent by the IoT gateway, stores them in MongoDB, detects incidents and pushes
alerts live to the future PWA dashboard.

Done so far:

- a Jakarta EE 11 application on WildFly, in Docker;
- MQTT over TLS from the cloud broker to MongoDB, with duplicates ignored;
- incidents for temperature, humidity, door open and silent sensors;
- live alerts over WebSocket.

The REST endpoints (sites, sensors, history, incidents) are the next task. For now the API only exposes a health check.

## 2. Where it fits in the architecture

```
ESP32 / simulator ──> site broker ──> Node-RED gateway ──MQTTS──> cloud broker ──> coldtrace-api ──> MongoDB
                        (gateway-iot/)                            (port 8883)      (api-rest/)    └──> WebSocket /ws/alerts
```

| Who | What |
|---|---|
| Khalil | IoT gateway (Node-RED flow, offline buffering, message format); in `api-rest`: v1 message contract, gateway status, alert engine, WebSocket, Compose integration, tests |
| Yessine | Local dev stack: MongoDB init script, TLS certificates, broker accounts and ACL, multi-site simulator with a fault API |
| Oussema | `api-rest` skeleton: Maven/WildFly project, Docker image, MongoDB access, MQTT listener; then MQTTS defaults, ids from the topic, door-open and silent-sensor incidents, documentation |

## 3. Technologies and versions

| Item | Choice | Why |
|---|---|---|
| Language | Java 21 | LTS version |
| Platform | Jakarta EE 11 (`jakarta.jakartaee-api` 11.0.0, `provided`) | WildFly supplies the implementation |
| Server | WildFly 42 (`quay.io/wildfly/wildfly:42.0.0.Final-jdk21`) | Latest tag on quay.io. Its release notes say it supports EE 11 on Java SE 17 and 21 |
| Database driver | `mongodb-driver-sync` 5.14.0 | Latest 5.x on Maven Central |
| MQTT client | Eclipse Paho `mqttv3` 1.2.5 | Simple, stable, TLS and automatic reconnect |
| JSON | JSON-B (Yasson, included in WildFly) | Standard Jakarta API |
| Live alerts | Jakarta WebSocket | Standard Jakarta API, no extra library |
| Tests | JUnit 5 | Run during `docker build` |

No extra frameworks: no Spring, no Quarkus, no Lombok.

## 4. Project structure

```
api-rest/
├── pom.xml                          # WAR "coldtrace-api", Java 21, dependencies, test dependencies
├── Dockerfile                       # stage 1: Maven build + tests, stage 2: WildFly 42 + ROOT.war
├── README.md                        # how to build, configure, run and test
├── RECAP.md                         # this file
├── .gitignore                       # target/, *.pem, .env
└── src/
    ├── main/webapp/WEB-INF/beans.xml        # CDI, bean-discovery-mode="all"
    ├── main/java/tn/supcom/coldtrace/
    │   ├── RestApplication.java             # @ApplicationPath("/api")
    │   ├── HealthResource.java              # GET /api/health -> {"status":"ok"}
    │   ├── db/MongoProducer.java            # MongoClient, database from MONGO_URI, indexes, default thresholds
    │   ├── mqtt/MqttListener.java           # MQTTS subscriber -> measurements, gateways, alert engine
    │   ├── alert/
    │   │   ├── AlertEngine.java             # opens/updates/closes incidents, silent-sensor check
    │   │   ├── AlertRules.java              # pure decisions (limits, anti-flapping, door, silence)
    │   │   ├── AlertSocket.java             # WebSocket endpoint /ws/alerts
    │   │   ├── AlertBroadcaster.java        # sends each alert to the connected clients
    │   │   └── AlertEvent.java              # JSON pushed to clients
    │   └── model/                           # Measurement, GatewayStatus, Incident, Threshold, Sensor, Site
    └── test/java/tn/supcom/coldtrace/
        ├── mqtt/GatewayContractTest.java    # the message examples of MESSAGE-FORMAT.md
        └── alert/AlertRulesTest.java        # limits, anti-flapping, door, silence, JSON of an alert
```

## 5. What each component does

### REST (`RestApplication`, `HealthResource`)
`GET /api/health` returns `{"status":"ok"}`. It is used to check that the server and the deployment are up.

### MongoDB access (`MongoProducer`)
- **Client:** one `MongoClient` for the whole application, closed on shutdown.
- **Connection:**
  - the URI comes from `MONGO_URI`, using the app user with `authSource=coldtrace`;
  - the database name is read from the URI;
  - the log shows the hosts, database and user, **never the password**.
- **Preparation at startup:**
  - the indexes, with the same names as the Mongo init script: unique `msgId`, `{siteId, sensorId, ts: -1}`, and
    "one open incident per sensor and metric";
  - the default thresholds for the profiles that have none.

### MQTT listener (`MqttListener`)
- **Connection:**
  - MQTTS to `ssl://cloud-broker:8883` as `backend`, trusting the project CA (`MQTT_CA_PATH`);
  - fixed client id `coldtrace-api` with `cleanSession=false`, so the broker queues messages while the API is down;
  - retries every 5 s at startup, then reconnects automatically and subscribes again.
- **Topics (QoS 1):**

  | Topic | Action |
  |---|---|
  | `coldtrace/{siteId}/{sensorId}/telemetry` | validate, store in `measurements`, run the alert engine |
  | `coldtrace/{siteId}/gateway/status` | keep the latest status of the site's gateway in `gateways` |

- **Message handling:**
  - Site and sensor ids are taken from the **topic**, which the broker ACL checks.
  - Invalid messages (not JSON, wrong format version, missing `msgId`, no measure, bad date) are logged as WARNING
    and dropped.
  - A reading whose `msgId` is already stored (gateway replay, QoS 1) is ignored at debug level.
  - No exception can escape the callback.

### Alert engine (`AlertEngine`, `AlertRules`)
Each newly stored reading is checked against the limits of its sensor's **profile** (collection `thresholds`):

| Metric | Incident when | Source |
|---|---|---|
| `temperature` | outside the profile limits: cold 0 to 4 °C, freezer −18 °C or below | cahier des charges EF-03 |
| `humidity` | outside 50 to 70 % | cahier des charges EF-03 |
| `door` | `doorOpen: true` (value 1 = open, maximum 0) | |
| `silence` | no reading from a known sensor for more than 60 s (checked every 15 s) | |

- **One incident per problem:** there is one incident per sensor and metric while the problem lasts. A unique index
  on the open incidents enforces it.
- **Anti-flapping:** an incident closes after **3 consecutive normal readings**, so a value hovering at a limit does
  not open and close all the time. `peakValue` keeps the worst value.
- **Silence:** a `silence` incident closes as soon as the sensor sends a reading again. Its value is the number of
  seconds without a reading.
- **State:** the incident state lives in MongoDB, so a restart continues where it stopped.
- **Live alerts:** every opening and closing is pushed as JSON to the WebSocket clients of `/ws/alerts`.

The door and silence metrics reuse the same rules as temperature and humidity, so they get anti-flapping, the unique
open incident and the WebSocket events without extra code.

### Data stored

```js
// measurements: one document per message
{ v, msgId, siteId, sensorId, gatewayId, ts: ISODate, tsSource,
  temperature?, humidity?, doorOpen?, receivedAt: ISODate, replayed? }

// incidents
{ siteId, sensorId, metric, status: "open"|"closed", value, peakValue,
  thresholdMin, thresholdMax, openedAt: ISODate, closedAt?: ISODate }

// gateways: _id = siteId
{ status: "online"|"offline", gatewayId, ts, buffered, forwarded, replayed, rejected, dropped, updatedAt }
```

## 6. Configuration (environment variables)

| Variable | Default | Value with the team stack |
|---|---|---|
| `MONGO_URI` | `mongodb://localhost:27017/coldtrace` | `mongodb://coldtrace-app:<pwd>@mongo:27017/coldtrace?authSource=coldtrace` |
| `MQTT_URL` | `ssl://cloud-broker:8883` | same |
| `MQTT_USER` | `backend` | `backend` (named literally in the broker ACL) |
| `MQTT_PASSWORD` | (none) | `CLOUD_BACKEND_PASSWORD` from `gateway-iot/.env` |
| `MQTT_CA_PATH` | (none) | `/certs/ca.crt` (mounted read-only) |

**Security:** no secret is in the code or in git. Passwords live in `.env` files that git ignores, and the CA
certificate is mounted read-only. The WebSocket has no login yet.

## 7. Timeline

| Commit | Author | What |
|---|---|---|
| `9579c49` | Oussema | Maven WAR project, Java 21, EE 11, `beans.xml`, `/api/health` |
| `a643678` | Oussema | `Site`, `Sensor`, `Measurement` with BSON mapping |
| `b244deb` | Oussema | `MongoProducer` |
| `4ab3920` | Oussema | First MQTT listener |
| `84320b8` | Oussema | Dockerfile (WildFly 42 / JDK 21) and README |
| `12d3eac` | Khalil | Listener aligned with the gateway v1 format, gateway status, JUnit tests |
| `e80a69d` | Khalil | Message format and tests documented |
| `48365fc` | Khalil | Alert engine, WebSocket, API in docker compose, end-to-end smoke test |
| `3b1d999` | Oussema | Database name from `MONGO_URI`, log without the password |
| `b5a371d` | Oussema | Ids from the topic, MQTTS as `backend` by default |
| `4f5819f` | Oussema | Door-open and silent-sensor incidents in the alert engine |

The listener and the alerts were developed in parallel on two branches. The final version is built on top of Khalil's
alert engine, with Oussema's ingestion and alert additions as separate commits on top, so `main` gets no merge
conflict.

## 8. Tests and verification

**Unit tests** (`mvn test`, also run by `docker build`): **16 tests, 0 failures**.
- `GatewayContractTest` (7): the message examples of `MESSAGE-FORMAT.md`.
- `AlertRulesTest` (9): limits, anti-flapping, door, silence, alert JSON.

**Against the team's real stack** (5 containers + the API on the `coldtrace-gateway_default` network):

| Test | Result |
|---|---|
| `GET /api/health` | ✅ `{"status":"ok"}` |
| MQTTS connection as `backend` with the CA, app user on MongoDB | ✅ connected and subscribed to both topics |
| Indexes and default thresholds created at startup | ✅ |
| Khalil's end-to-end test (`alert-e2e.js`): `temp_rise` on S02 | ✅ alert received on the WebSocket 155 ms after the reading, incident closed after the fault was cleared |
| `door_open` on S01 | ✅ `door` incident, then `temperature` when the room passed 4 °C |
| `silent` on S08 | ✅ `silence` incident after 63 s, `peakValue` growing while it lasted |
| Faults cleared | ✅ all incidents closed, 0 still open, no error in the log |
| Broken message (`not-json`) | ✅ WARNING, dropped, API keeps running |
| Earlier tests (2 Nov): 10 sensors, duplicates, broker and gateway restarts | ✅ no duplicates, buffered readings replayed |

## 9. How to run it

From `gateway-iot/` (see its README for the first-time setup: `.env` and certificates):

```bash
docker compose up -d --build                 # whole stack, API included
curl http://localhost:8080/api/health        # set API_PORT in .env if 8080 is taken
```

To run the API on its own next to the stack, see `README.md`, section "How to build and run".

## 10. Known limits and next steps

- **Lost readings if MongoDB is down:** a reading is confirmed to the broker even when saving it fails, so it is lost.
  It should be retried or kept instead.
- **Sites:** the `sites` collection is still empty. Sensors are registered by the dev seed of the Compose stack.
- **Single instance:** the fixed MQTT client id means only one API instance can run at a time.
- **Next task:** REST endpoints for sites, sensors, measurements history and incidents (including acknowledging an
  incident).
- **Then:**
  - the PWA dashboard, using `/ws/alerts` for live alerts;
  - Jakarta Security for users;
  - a login on the WebSocket.

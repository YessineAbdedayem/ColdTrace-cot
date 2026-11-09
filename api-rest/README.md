# ColdTrace API (Jakarta EE 11 / WildFly)

Back-end skeleton for ColdTrace:

- `GET /api/health` returns `{"status":"ok"}`
- an MQTT listener subscribes to `coldtrace/+/+/telemetry` and `coldtrace/+/gateway/status` (QoS 1) on Mosquitto
- every valid reading is stored in `coldtrace.measurements` (indexes: unique `msgId`; `siteId`, `sensorId`, `ts` desc)
- the latest status of each site's gateway is kept in `coldtrace.gateways`, with the site id as `_id`

Stack: Java 21, Jakarta EE 11 (`jakarta.jakartaee-api` 11.0.0), WildFly 42 (`quay.io/wildfly/wildfly:42.0.0.Final-jdk21`),
MongoDB Java driver 5.14 (sync), Eclipse Paho MQTT v3 1.2.5.

```
src/main/java/tn/supcom/coldtrace/
├── RestApplication.java     # @ApplicationPath("/api")
├── HealthResource.java      # GET /api/health
├── model/                   # Site, Sensor, Measurement, GatewayStatus
├── db/MongoProducer.java    # MongoClient + MongoDatabase "coldtrace" producer, index creation
└── mqtt/MqttListener.java   # Paho subscriber -> measurements and gateways collections
```

## Message format

The API reads the messages the gateway sends, defined in
[gateway-iot/MESSAGE-FORMAT.md](../gateway-iot/MESSAGE-FORMAT.md). Topic `coldtrace/{siteId}/{sensorId}/telemetry`:

```json
{"v":1,"msgId":"site01-S01-1793632152000","siteId":"site01","sensorId":"S01","gatewayId":"gw-site01","ts":"2026-11-02T15:09:12.000Z","tsSource":"sensor","temperature":4.2,"humidity":61.5,"doorOpen":false,"receivedAt":"2026-11-02T15:09:12.185Z"}
```

- `v`, `msgId`, `siteId`, `sensorId`, `ts` and at least one of `temperature`, `humidity`, `doorOpen` are required.
- `ts` and `receivedAt` are ISO-8601 in the payload and stored as BSON dates.
- A message with a missing field, another format version, a bad date or invalid JSON is rejected and logged as a
  WARNING. It is not stored.
- A reading whose `msgId` is already stored is ignored, because a reading can arrive twice after a reconnect.

## Alerts

Each new reading is compared with the limits of its sensor's profile. A sensor must exist in `coldtrace.sensors` with a
`profile`, otherwise its readings are stored but raise no alert, and a WARNING is logged once.

| Profile (`coldtrace.thresholds`) | Temperature | Humidity |
|---|---|---|
| `cold` | 0 to 4 °C | 50 to 70 % |
| `freezer` | -18 °C or below | 50 to 70 % |

The API adds these defaults at startup only for profiles that have none, so edited limits are kept.

- One reading outside the limits opens an incident in `coldtrace.incidents`, one per sensor and metric.
- Three consecutive in-range readings close it. A new breach during those readings keeps it open.
- Connected clients of `ws://<host>:8080/ws/alerts` receive JSON when an incident opens or closes. The endpoint has no
  login yet.

```json
{"event":"incident.opened","incident":{"id":"65f000000000000000000001","siteId":"site01","sensorId":"S02","metric":"temperature","status":"open","value":4.1,"peakValue":4.1,"thresholdMin":0.0,"thresholdMax":4.0,"openedAt":"2026-11-09T12:00:00Z"}}
```

## Tests

`mvn test` needs JDK 21 or later. The tests check the API against the example messages in the format document, and
they also run during `docker build`.

## How to build and run

The simplest way is `gateway-iot/scripts/run-all.sh`, which starts the API with the rest of the stack and tests it.
The steps below run the API on its own.

### 1. Build the image

```bash
cd api-rest
docker build -t coldtrace-api .
```

The Dockerfile is multi-stage: Maven builds the WAR, then it is copied into WildFly as `ROOT.war`.
You don't need Java or Maven on your machine. To build just the WAR locally you need JDK 21: `mvn -q package`
(output: `target/coldtrace-api.war`).

### 2. Environment variables

| Variable | Required | Default | Meaning |
|---|---|---|---|
| `MONGO_URI` | no | `mongodb://localhost:27017` | MongoDB connection string (can include credentials) |
| `MQTT_URL` | no | `tcp://localhost:1883` | Broker URL. Production: `ssl://<host>:8883` |
| `MQTT_USER` | for MQTTS | (none) | Broker username (for example the `backend` account) |
| `MQTT_PASSWORD` | for MQTTS | (none) | Broker password |
| `MQTT_CA_PATH` | for MQTTS with a private CA | (none) | Path to the CA certificate (PEM) **inside the container** |

Never put real values in committed files. Put them in an `.env` file (ignored by git) and use `--env-file .env`.

Notes:
- The MQTT client id is fixed (`coldtrace-api`) with `cleanSession=false`, so the broker keeps the subscription and
  queues QoS 1 messages while the API is down. Only **one** API instance can be connected at a time.
- With `ssl://` the broker certificate must match the host name in `MQTT_URL` (CN or SAN).
- If the broker is unreachable at startup, the API keeps retrying every 5 s. After that, Paho reconnects
  automatically and the listener subscribes again.

### 3. Run

The container must be able to reach MongoDB and Mosquitto. **`localhost` inside the container is the container
itself**, not your machine. You have two options:

**a) Shared Docker network (recommended).** Put Mongo, Mosquitto and the API on the same network and use the
container names as hosts:

```bash
docker network create coldtrace
docker run -d --name mongo     --network coldtrace mongo:8
docker run -d --name mosquitto --network coldtrace eclipse-mosquitto:2.0.20   # needs a config, see below

docker run -d --name coldtrace-api --network coldtrace -p 8080:8080 \
  -e MONGO_URI=mongodb://mongo:27017 \
  -e MQTT_URL=tcp://mosquitto:1883 \
  coldtrace-api
```

**b) Services running on your machine** (or published ports of other containers): use `host.docker.internal`
(Docker Desktop on Windows/macOS; on Linux add `--add-host=host.docker.internal:host-gateway`):

```bash
docker run -d --name coldtrace-api -p 8080:8080 \
  -e MONGO_URI=mongodb://host.docker.internal:27017 \
  -e MQTT_URL=tcp://host.docker.internal:1883 \
  coldtrace-api
```

**MQTTS (production / gateway cloud broker):** mount the CA certificate read-only and pass the credentials:

```bash
docker run -d --name coldtrace-api -p 8080:8080 \
  --env-file .env \
  -v /path/to/ca.crt:/certs/ca.crt:ro \
  -e MQTT_URL=ssl://<broker-host>:8883 \
  -e MQTT_CA_PATH=/certs/ca.crt \
  coldtrace-api
```

with `.env` containing `MONGO_URI=...`, `MQTT_USER=...`, `MQTT_PASSWORD=...`.

If port 8080 is taken on your machine, map another one (`-p 18080:8080`).

Check it is up:

```bash
curl http://localhost:8080/api/health
# {"status":"ok"}
```

The log should show `Connected to MQTT broker ...` and
`Subscribed to coldtrace/+/+/telemetry, coldtrace/+/gateway/status (QoS 1)`.

### 4. Test with mosquitto_pub

For a local test only, Mosquitto 2 needs a config that allows anonymous clients on 1883
(`mosquitto.conf`: `listener 1883` and `allow_anonymous true`), mounted at `/mosquitto/config/mosquitto.conf`.

Publish a valid reading and an invalid one (from the Mosquitto container, so no local install is needed):

```bash
docker exec mosquitto mosquitto_pub -h localhost -q 1 -t coldtrace/site01/S01/telemetry \
  -m '{"v":1,"msgId":"test-1","siteId":"site01","sensorId":"S01","ts":"2026-11-02T20:15:00Z","temperature":2.4}'

docker exec mosquitto mosquitto_pub -h localhost -q 1 -t coldtrace/site01/S01/telemetry \
  -m '{"v":2,"msgId":"test-2","siteId":"site01","sensorId":"S01","ts":"2026-11-02T20:16:00Z","temperature":2.4}'
```

The second one shows up as a WARNING in `docker logs coldtrace-api`. Check what was stored:

```bash
docker exec mongo mongosh --quiet coldtrace --eval 'db.measurements.find().toArray()'
```

Against an MQTTS broker, add `-p 8883 --cafile ca.crt -u <user> -P <password>` to `mosquitto_pub`.

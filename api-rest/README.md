# ColdTrace API (Jakarta EE 11 / WildFly)

Back-end skeleton for ColdTrace:

- `GET /api/health` returns `{"status":"ok"}`
- an MQTT listener subscribes to `coldtrace/+/+/telemetry` and `coldtrace/+/gateway/status` (QoS 1) on Mosquitto
- every valid reading is stored in `coldtrace.measurements` (indexes: unique `msgId`; `siteId`, `sensorId`, `ts` desc)
- the latest status of each site's gateway is kept in `coldtrace.gateways`, with the site id as `_id`
- each new reading is checked against its sensor's limits, and incidents are opened and closed in `coldtrace.incidents`
- live alerts are pushed to WebSocket clients on `/ws/alerts`

Stack: Java 21, Jakarta EE 11 (`jakarta.jakartaee-api` 11.0.0), WildFly 42 (`quay.io/wildfly/wildfly:42.0.0.Final-jdk21`),
MongoDB Java driver 5.14 (sync), Eclipse Paho MQTT v3 1.2.5.

```
src/main/java/tn/supcom/coldtrace/
├── RestApplication.java     # @ApplicationPath("/api")
├── HealthResource.java      # GET /api/health
├── model/                   # Site, Sensor, Measurement, GatewayStatus, Incident, Threshold
├── db/MongoProducer.java    # MongoClient + MongoDatabase producer (database from MONGO_URI), indexes, default thresholds
├── mqtt/MqttListener.java   # Paho subscriber -> measurements and gateways collections
└── alert/                   # AlertEngine (incidents), AlertRules (pure decisions), AlertSocket + AlertBroadcaster (WebSocket)
```

## Message format

The API reads the messages the gateway sends, defined in
[gateway-iot/MESSAGE-FORMAT.md](../gateway-iot/MESSAGE-FORMAT.md). Topic `coldtrace/{siteId}/{sensorId}/telemetry`:

```json
{"v":1,"msgId":"site01-S01-1793632152000","siteId":"site01","sensorId":"S01","gatewayId":"gw-site01","ts":"2026-11-02T15:09:12.000Z","tsSource":"sensor","temperature":4.2,"humidity":61.5,"doorOpen":false,"receivedAt":"2026-11-02T15:09:12.185Z"}
```

- `v`, `msgId`, `ts` and at least one of `temperature`, `humidity`, `doorOpen` are required.
- `siteId` and `sensorId` are taken from the **topic**, which the broker ACL checks, not from the body.
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

Besides temperature and humidity, two more metrics need no profile limits:

| Metric | Incident when | Values |
|---|---|---|
| `door` | `doorOpen: true` | `value` 1 = open, `thresholdMax` 0 |
| `silence` | no reading from a known sensor for more than 60 s (checked every 15 s) | `value` = seconds without a reading, `peakValue` grows while it lasts |

- One reading outside the limits opens an incident in `coldtrace.incidents`, one per sensor and metric.
- Three consecutive in-range readings close it. A new breach during those readings keeps it open.
- A `silence` incident closes as soon as the sensor sends a reading again.
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
| `MONGO_URI` | no | `mongodb://localhost:27017/coldtrace` | Use the app user: `mongodb://<MONGO_APP_USER>:<MONGO_APP_PASSWORD>@mongo:27017/coldtrace?authSource=coldtrace`. The database name is taken from the URI path. The log shows the host, database and user, never the password |
| `MQTT_URL` | no | `ssl://cloud-broker:8883` | Broker URL. `ssl://host.docker.internal:8883` from outside the gateway network |
| `MQTT_USER` | no | `backend` | Keep `backend`: the cloud broker's ACL names it literally |
| `MQTT_PASSWORD` | yes | (none) | `CLOUD_BACKEND_PASSWORD` from `gateway-iot/.env` |
| `MQTT_CA_PATH` | for MQTTS with a private CA | (none) | Path to the CA certificate (PEM) **inside the container** |

Never put real values in committed files. Put them in an `.env` file (ignored by git) and use `--env-file .env`.

Notes:
- The MQTT client id is fixed (`coldtrace-api`) with `cleanSession=false`, so the broker keeps the subscription and
  queues QoS 1 messages while the API is down. Only **one** API instance can be connected at a time.
- With `ssl://` the broker certificate must match the host name in `MQTT_URL` (CN or SAN).
- If the broker is unreachable at startup, the API keeps retrying every 5 s. After that, Paho reconnects
  automatically and the listener subscribes again.

### 3. Run

**With the team stack (recommended).** `gateway-iot/docker-compose.yml` builds and starts the API together with the
brokers, the gateway, the simulator and MongoDB. See `gateway-iot/README.md`. In short, from `gateway-iot/`:

```bash
cp .env.example .env          # then change every change-me password
./scripts/gen-certs.sh        # dev CA + cloud-broker certificate
docker compose up -d --build  # API on http://localhost:8080 (set API_PORT in .env if 8080 is taken)
```

**On its own, next to the team stack.** This is useful to try a local change without rebuilding the whole stack.
**Stop the Compose `api` service first** (`docker compose stop api`): both would use the MQTT client id
`coldtrace-api` and would keep disconnecting each other.

1. Create `api-rest/.env` (ignored by git), with the values from `gateway-iot/.env`:
   ```properties
   MQTT_URL=ssl://cloud-broker:8883
   MQTT_USER=backend
   MQTT_PASSWORD=<CLOUD_BACKEND_PASSWORD>
   MQTT_CA_PATH=/certs/ca.crt
   MONGO_URI=mongodb://<MONGO_APP_USER>:<MONGO_APP_PASSWORD>@mongo:27017/coldtrace?authSource=coldtrace
   ```
   Keep `mongo` as the host here: it is the container's name on the Docker network. Only a tool running on your
   machine, such as MongoDB Compass, uses `localhost:27017`.
2. Join the gateway's network and mount the CA read-only (from `api-rest/`):
   ```bash
   docker run -d --name coldtrace-api --network coldtrace-gateway_default -p 18080:8080 \
     --env-file .env -v "$(pwd)/../gateway-iot/certs/ca.crt:/certs/ca.crt:ro" coldtrace-api
   ```
   PowerShell: replace the `\` line endings with a backtick, and use `-v "${PWD}\..\gateway-iot\certs\ca.crt:/certs/ca.crt:ro"`.
   After a change to `.env`, re-create the container (`docker rm -f coldtrace-api`, then `docker run` again):
   `docker restart` keeps the old settings.

Check it is up:

```bash
curl http://localhost:8080/api/health          # or :18080 for the container started on its own
# {"status":"ok"}
```

The log should show `MongoDB client for hosts [mongo:27017], database 'coldtrace', user '...'`,
`Connected to MQTT broker ssl://cloud-broker:8883` and
`Subscribed to coldtrace/+/+/telemetry, coldtrace/+/gateway/status (QoS 1)`.

Trigger incidents with the simulator's fault API, then look at `coldtrace.incidents` (for example in MongoDB Compass):

```bash
curl -X POST http://localhost:4000/faults -H 'Content-Type: application/json' -d '{"sensorId":"S01","fault":"door_open"}'
curl -X POST http://localhost:4000/faults -H 'Content-Type: application/json' -d '{"sensorId":"S08","fault":"silent"}'
curl -X POST http://localhost:4000/faults/clear
```

### 4. Test with mosquitto_pub

Only the gateway accounts may publish telemetry, so the test runs inside the cloud broker container with the
gateway's login (from `gateway-iot/`):

```bash
docker compose exec cloud-broker sh -c 'mosquitto_pub -h localhost -p 8883 --cafile /mosquitto/certs/ca.crt \
  -u $GW_USER -P $GW_PASS -q 1 -t coldtrace/site01/S01/telemetry \
  -m "{\"v\":1,\"msgId\":\"test-1\",\"ts\":\"2026-11-02T20:15:00Z\",\"temperature\":2.4}"'

docker compose exec cloud-broker sh -c 'mosquitto_pub -h localhost -p 8883 --cafile /mosquitto/certs/ca.crt \
  -u $GW_USER -P $GW_PASS -q 1 -t coldtrace/site01/S01/telemetry -m not-json'
```

The first one is stored with `siteId: "site01"` and `sensorId: "S01"`, taken from the topic. Publishing it again is
ignored, because its `msgId` is already saved. The second one shows up as a WARNING in the API log.

# ColdTrace IoT gateway (Node-RED)

The gateway sits on each site. It:

1. listens to the sensors on the **site broker** (`coldtrace/+/+/telemetry`),
2. **checks and cleans** each reading (bad ones are rejected and counted),
3. sends good readings to the **cloud broker over MQTTS** (TLS + login),
4. while the cloud is unreachable, **keeps readings on disk** and sends them
   again, in order, when the connection comes back (also after a gateway restart).

```
 ESP32 / simulator ──MQTT──▶ site broker ──▶ Node-RED gateway ──MQTTS:8883──▶ cloud broker ──▶ backend
                                              (check, buffer, replay)
```

No hardware yet, so everything runs on a laptop with Docker, and a
simulator plays the ESP32 sensors. The message format is a **draft**:
see [MESSAGE-FORMAT.md](MESSAGE-FORMAT.md).

## What's in this folder

| Path | What |
|---|---|
| `docker-compose.yml` | the 5 containers: site broker, cloud broker, Node-RED, simulator, MongoDB |
| `.env.example` | settings and passwords (copy to `.env`) |
| `scripts/gen-certs.sh` | creates the dev CA and the cloud broker's TLS certificate |
| `scripts/smoke-test.sh` | checks the services, telemetry and MongoDB indexes |
| `mosquitto/site/` | site broker config |
| `mosquitto/cloud/` | cloud broker config (TLS only, login, access rules in `acl`) |
| `mongo/init-app-user.sh` | creates the backend database user, collections and indexes |
| `node-red/flows.json` | the gateway flow |
| `node-red/settings.js` | Node-RED settings (disk storage for the buffer) |
| `simulator/` | ten fake sensors, fault-control API and model tests |
| `MESSAGE-FORMAT.md` | topics and JSON format (draft) |

## Run it

You need Docker (Docker Desktop on Windows/Mac) and `openssl`
(on Windows, run the script from Git Bash or WSL).

```bash
cd gateway-iot
cp .env.example .env            # then change the example passwords in .env
./scripts/gen-certs.sh          # creates certs/ (never committed)
docker compose up -d
```

MongoDB's init script creates the `coldtrace` database and its app user,
collections and indexes when the data volume is empty. Init scripts run only
on the first start with an empty volume; to run them again, use
`docker compose down -v` (this deletes the local database data).

Open the Node-RED editor at http://localhost:1880. The "Forward or buffer"
node shows the state, for example `cloud online | buffered: 0`.

Watch what arrives in the "cloud" (as the backend would), using the password
from `.env`:

```bash
docker compose exec cloud-broker mosquitto_sub -h cloud-broker -p 8883 \
  --cafile /mosquitto/certs/ca.crt -u backend -P "$CLOUD_BACKEND_PASSWORD" -t 'coldtrace/#' -v
```

The simulator publishes ten sensors from three sites every five seconds.
Logs: `docker compose logs -f node-red simulator`.

### Control simulator faults

The simulator control API listens on `127.0.0.1:4000` and has no authentication;
it is intended only for local development. `GET /sensors` lists each sensor's
site, profile, latest values and active fault. Set a fault on one sensor or all
sensors at a site with `POST /faults`; `POST /faults/clear` clears all faults.
Supported faults are `door_open`, `temp_rise`, `silent` and `none`. Random door
openings are disabled by default; faults can also be set at startup with
`SIM_FAULTS=S01=door_open,S05=silent`.

curl examples:

```bash
curl http://localhost:4000/sensors
curl -X POST http://localhost:4000/faults \
  -H 'Content-Type: application/json' \
  -d '{"sensorId":"S01","fault":"door_open"}'
curl -X POST http://localhost:4000/faults \
  -H 'Content-Type: application/json' \
  -d '{"siteId":"site02","fault":"temp_rise"}'
curl -X POST http://localhost:4000/faults/clear
```

PowerShell examples:

```powershell
Invoke-RestMethod -Uri 'http://localhost:4000/sensors'
Invoke-RestMethod -Method Post -Uri 'http://localhost:4000/faults' `
  -ContentType 'application/json' -Body '{"sensorId":"S01","fault":"door_open"}'
Invoke-RestMethod -Method Post -Uri 'http://localhost:4000/faults' `
  -ContentType 'application/json' -Body '{"siteId":"site02","fault":"temp_rise"}'
Invoke-RestMethod -Method Post -Uri 'http://localhost:4000/faults/clear'
```

Run the end-to-end local checks from `gateway-iot/` after the stack is up:

```bash
./scripts/smoke-test.sh
```

The smoke test checks that all five services are running, receives telemetry
from all ten sensor topics through the cloud broker, and verifies MongoDB's
ping and unique `msgId` index. Simulator model tests run with
`cd simulator && npm test`.

## Test a cloud outage

```bash
docker compose stop cloud-broker     # Node-RED shows "cloud offline | buffered: 12" and counting
docker compose restart node-red      # optional: the buffer survives a restart
docker compose start cloud-broker    # kept readings are sent, marked "replayed": true
```

Tested: with the cloud down, plus a gateway restart while offline and
another while online, every good reading arrived once, in order, and
every broken one was rejected.

## How the flow works

- **Sensors (site broker)**: subscribes with QoS 1 and a persistent session,
  so the site broker holds readings while Node-RED restarts.
- **MQTT adapter**: reads site and sensor from the topic and passes the
  reading to "Gateway input", the entry point shared by all protocols.
- **Check and clean**: checks JSON, number ranges and time, outputs the
  standard format. Rejected messages go to the "Rejected" debug node with the
  protocol and the reason.
- **Forward or buffer**: if the cloud is connected and nothing is waiting,
  sends right away. Otherwise adds the reading to a buffer saved on disk
  (`node-red/context/`). A 1-second timer sends the buffer back in batches of
  `REPLAY_BATCH` when the cloud is back. If the buffer reaches `BUFFER_MAX`
  the oldest readings are dropped. Capacity time is
  `BUFFER_MAX / (sensor count / interval in seconds)`: 20,000 readings at ten
  sensors every 5 s (2 readings/s) lasts about 2.8 hours; at three sensors
  every 10 s (0.3 readings/s) it lasts about 18.5 hours.
- **Gateway status**: online/offline messages (MQTT birth and last will) and a
  heartbeat every minute with counters, on `coldtrace/{siteId}/gateway/status`.

## Adding a protocol

Only MQTT is active today. A new protocol needs its own input node and
adapter; the checks, the buffer and the cloud connection stay as they are, and
the backend receives the same messages.

```
MQTT in ─▶ MQTT adapter ─▶ ┐
                           ├─▶ Gateway input ─▶ Check and clean ─▶ Forward or buffer ─▶ cloud
NEW in  ─▶ NEW adapter  ─▶ ┘
```

1. Add the protocol's input node. If it isn't built in, install it (see below).
2. Add a function node that sets these two properties:

   | Property | Value |
   |---|---|
   | `msg.source` | `{ protocol: "http", siteId: "site01", sensorId: "S01" }` |
   | `msg.payload` | the reading as in MESSAGE-FORMAT.md (JSON text, Buffer or object) |

   If the protocol doesn't carry the site id, use `env.get("SITE_ID")`.
3. Connect it to a "link out" node pointing to **Gateway input**.
4. If it listens on a new port, add the port to `node-red` in `docker-compose.yml`
   (`/udp` for UDP protocols).
5. Protect the input if it can be reached from the network: "Check and clean"
   checks the data, not who sent it.

If the protocol replies to each request (like HTTP), answer in the adapter's
branch: the rest of the flow sends nothing back.

**Extra nodes:** the same steps work with any input node. A node installed from
the palette is saved in `node-red/package.json`, which must be committed. After a
fresh clone, run this once (Node-RED won't start the flows if a node is missing):

```bash
docker compose run --rm --entrypoint npm node-red install --prefix /data
docker compose up -d
```

## Security in this setup

- Cloud broker accepts **only TLS** (port 8883) and **only logged-in users**.
- In production, each gateway logs in with its **site id as username** and the
  ACL lets it write only under its own site. The local development ACL lets the
  `site01` gateway also write `site02` and `site03` simulator telemetry; the
  backend user can read everything.
- Passwords live in `.env`, certificates and keys in `certs/`: both are ignored by git.
- Node-RED only gets `ca.crt` (to check the broker), never a private key.
- Ports are opened on `127.0.0.1` only.

Still to do (later steps): a login on the Node-RED editor (`adminAuth` in
`settings.js`), client certificates for the gateway (mutual TLS), and TLS
or at least a password on the site broker once real ESP32s are on the network.

## Moving to real hardware / the real cloud

- **Raspberry Pi**: same `docker compose up`, without the `simulator` service.
- **Real ESP32**: publish to `coldtrace/{siteId}/{sensorId}/telemetry` on the
  site broker (port 1883) with the JSON in MESSAGE-FORMAT.md.
- **Real cloud server**: copy `mosquitto/cloud/` and `certs/` there, run
  `./scripts/gen-certs.sh <server-ip-or-name>` so the certificate matches its
  address, and set `CLOUD_BROKER_HOST` in the gateway's `.env`.

## Connection details for the backend

- **MQTTS**: host `localhost`, port `8883` from the host; use `cloud-broker`,
  port `8883` from a container on the Compose network. Trust `certs/ca.crt`
  (mounted in the broker at `/mosquitto/certs/ca.crt`; mount the CA in a
  separate backend container as well). Username is `backend`;
  use `CLOUD_BACKEND_PASSWORD` from `.env`. Subscribe to
  `coldtrace/+/+/telemetry` and `coldtrace/+/gateway/status`.
- **MongoDB**: host URI `mongodb://localhost:27017/coldtrace`; container URI
  `mongodb://mongo:27017/coldtrace`. Authenticate with `MONGO_APP_USER` and
  `MONGO_APP_PASSWORD` from `.env`, using `authSource=coldtrace`, for example
  `mongodb://<user>:<password>@localhost:27017/coldtrace?authSource=coldtrace`
  from the host or `mongodb://<user>:<password>@mongo:27017/coldtrace?authSource=coldtrace`
  from a container.
  The root credentials are for administration only.
- **Docker network**: `coldtrace-gateway_default` (the Compose default network).
  Containers must join this network to resolve `cloud-broker` and `mongo`.
- **MongoDB collections**: `measurements` is a regular collection with a
  unique `msgId` index and `{siteId: 1, sensorId: 1, ts: -1}` index; empty
  collections are also created for `incidents`, `sites`, `sensors`,
  `thresholds` and `users`.

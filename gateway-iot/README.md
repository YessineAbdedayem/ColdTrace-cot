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
| `docker-compose.yml` | the 4 containers: site broker, cloud broker, Node-RED, simulator |
| `.env.example` | settings and passwords (copy to `.env`) |
| `scripts/gen-certs.sh` | creates the dev CA and the cloud broker's TLS certificate |
| `mosquitto/site/` | site broker config |
| `mosquitto/cloud/` | cloud broker config (TLS only, login, access rules in `acl`) |
| `node-red/flows.json` | the gateway flow |
| `node-red/settings.js` | Node-RED settings (disk storage for the buffer) |
| `simulator/` | fake sensors |
| `MESSAGE-FORMAT.md` | topics and JSON format (draft) |

## Run it

You need Docker (Docker Desktop on Windows/Mac) and `openssl`
(on Windows, run the script from Git Bash or WSL).

```bash
cd gateway-iot
cp .env.example .env            # then change the two passwords in .env
./scripts/gen-certs.sh          # creates certs/ (never committed)
docker compose up -d
```

Open the Node-RED editor at http://localhost:1880. The "Forward or buffer"
node shows the state, for example `cloud online | buffered: 0`.

Watch what arrives in the "cloud" (as the backend would):

```bash
docker compose exec cloud-broker mosquitto_sub -h cloud-broker -p 8883 \
  --cafile /mosquitto/certs/ca.crt -u backend -P change-me-backend -t 'coldtrace/#' -v
```

(use the backend password from your `.env`). Logs: `docker compose logs -f node-red simulator`.

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
  (20,000 readings, about 18 hours for 3 sensors every 10 s), the oldest are dropped.
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
- Each gateway logs in with its **site id as username** and the ACL lets it
  write only under its own site (`coldtrace/site01/...`). The backend user can read everything.
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

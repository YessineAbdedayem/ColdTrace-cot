# Message format and topics (DRAFT, to agree in the team call)

Nothing here is final. It is a starting point so the gateway, the backend
and the ESP32 code all speak the same language. Change it together, then
update the gateway's "Check and clean" node.

## Topics

| Topic | Who writes | Who reads | What |
|---|---|---|---|
| `coldtrace/{siteId}/{sensorId}/telemetry` | ESP32 (site broker), then gateway (cloud broker) | gateway, backend | one reading |
| `coldtrace/{siteId}/gateway/status` | gateway | backend | gateway online/offline + counters |
| `coldtrace/{siteId}/{sensorId}/cmd` | backend | gateway / ESP32 | commands (not used yet) |

`siteId` and `sensorId`: letters, digits, `-` or `_`, up to 32 characters.
Example: `coldtrace/site01/S01/telemetry`.

## 1. What a sensor sends (ESP32 → site broker)

```json
{ "ts": 1793632152, "temperature": 4.2, "humidity": 61.5, "doorOpen": false }
```

- The site and sensor ids come from the topic, not the message. Other
  protocols give them through their adapter (see "Adding a protocol" in README.md).
- `ts` is optional: Unix time in seconds (or milliseconds, or an ISO date).
  If it is missing or clearly wrong (an ESP32 without NTP sends 1970),
  the gateway uses its own clock.
- Each of `temperature` (°C), `humidity` (%) and `doorOpen` is optional,
  but at least one must be there. Short names `temp`, `hum`, `door` work too.

## 2. What the gateway sends to the cloud (gateway → cloud broker)

```json
{
  "v": 1,
  "msgId": "site01-S01-1793632152000",
  "siteId": "site01",
  "sensorId": "S01",
  "gatewayId": "gw-site01",
  "ts": "2026-11-02T15:09:12.000Z",
  "tsSource": "sensor",
  "temperature": 4.2,
  "humidity": 61.5,
  "doorOpen": false,
  "receivedAt": "2026-11-02T15:09:12.185Z",
  "replayed": true
}
```

| Field | Meaning |
|---|---|
| `v` | format version, so we can change the format later |
| `msgId` | unique id of the reading. The backend should ignore a `msgId` it already saved (a reading can arrive twice after a reconnect) |
| `ts` | when the reading was measured (UTC) |
| `tsSource` | `sensor` or `gateway`: who gave the time |
| `receivedAt` | when the gateway got it |
| `replayed` | only present when the reading was kept during a cloud outage and sent later |

Values are rounded to 0.1. Temperature must be between -40 and 85 °C and
humidity between 0 and 100 %, otherwise the reading is rejected (it stays
on the gateway's debug log and is counted, it is not sent to the cloud).

## 3. Gateway status (`coldtrace/{siteId}/gateway/status`, retained)

When it connects: `{"v":1,"status":"online"}`.
If it disappears or stops: `{"v":1,"status":"offline"}` (MQTT "last will").
Every minute:

```json
{ "v": 1, "gatewayId": "gw-site01", "siteId": "site01", "status": "online",
  "ts": "2026-11-02T15:12:02.646Z", "buffered": 0, "forwarded": 125,
  "replayed": 160, "rejected": 43, "dropped": 0 }
```

## Questions for the call

1. Are these field names OK for the backend and the MongoDB documents?
2. Do we need more measurements (battery, signal strength)?
3. Alert thresholds (for example above 8 °C): in the backend only, or also on the gateway?
4. One reading per message (as here) or several per message?

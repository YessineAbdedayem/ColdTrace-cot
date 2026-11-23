# ColdTrace ESP32 sensor firmware

`coldtrace-esp32/` turns an ESP32 into a ColdTrace sensor. It measures temperature and humidity (DHT22) and the door
(reed switch), and publishes to the **site broker** next to the gateway:

```
ESP32 ──MQTT (Wi-Fi, local network)──▶ site broker :1883 ──▶ Node-RED gateway ──MQTTS──▶ cloud
topic: coldtrace/<SITE_ID>/<SENSOR_ID>/telemetry
```

It sends the same messages as the simulator, in the format of [MESSAGE-FORMAT.md](../../docs/MESSAGE-FORMAT.md):

```json
{"v":1,"msgId":"site01-S01-1a2b3c4d-42","seq":42,"ts":1793632152,"temperature":4.2,"humidity":61.5,"doorOpen":false}
```

| Behaviour | How |
|---|---|
| One reading every 5 s | `SAMPLE_INTERVAL_MS` |
| Door changes sent at once | debounced 50 ms, a reading with `doorOpen` only |
| No duplicates in the database | stable `msgId` = site, sensor, random boot id, sequence number |
| Correct time | NTP (UTC). Before NTP answers, `ts` is left out and the gateway stamps the reading on arrival |
| Wi-Fi or broker down | readings wait in RAM (`BUFFER_READINGS`, 600 = 50 min) and leave in order; the oldest are dropped when full |
| Wi-Fi down for 10 min | the board restarts |
| Sensor failure (DHT22 not answering) | the door state is still sent; the missing temperature shows as a gap |

## Wiring

| Part | ESP32 pin | Notes |
|---|---|---|
| DHT22 data | GPIO 4 (`DHT_PIN`) | 10 kΩ pull-up to 3.3 V; VCC 3.3 V, GND |
| Reed switch | GPIO 27 (`DOOR_PIN`) and GND | internal pull-up; magnet on the door: closed (LOW) = door shut |
| Status LED | GPIO 2 (`STATUS_LED`) | on while connected to the broker |

The DHT22 measures −40 to 80 °C, so it works for cold rooms and freezers (−18 °C). For a probe inside a freezer,
a waterproof DS18B20 is more robust; replace the two `dht.read...()` calls.

## Build and flash

1. Arduino IDE 2 with the **esp32** board package (Espressif, core 3.x). Board: *ESP32 Dev Module*.
2. Library Manager: **PubSubClient** (Nick O'Leary), **DHT sensor library** and **Adafruit Unified Sensor** (Adafruit).
3. Copy `coldtrace-esp32/config.example.h` to `coldtrace-esp32/config.h` (ignored by git) and set the Wi-Fi, the
   site broker's address (the gateway machine, e.g. the Raspberry Pi) and the **site and sensor ids**.
4. On the gateway machine, set `SITE_BROKER_BIND=0.0.0.0` in `gateway-iot/.env` and run `docker compose up -d`:
   by default the site broker only listens on 127.0.0.1, which an ESP32 cannot reach.
5. Register the sensor in the back office (https://admin.coldtrace.test, *Sensors*, same ids), otherwise the backend
   quarantines its readings as `unknown_sensor`.
6. Upload, then open the serial monitor at 115200 baud: it prints the topic and each connection.

With `arduino-cli`:

```bash
arduino-cli core install esp32:esp32
arduino-cli lib install PubSubClient "DHT sensor library" "Adafruit Unified Sensor"
arduino-cli compile --fqbn esp32:esp32:esp32 coldtrace-esp32
arduino-cli upload  --fqbn esp32:esp32:esp32 -p COM5 coldtrace-esp32
```

## Check it arrives

On the gateway machine: `docker compose exec site-broker mosquitto_sub -t 'coldtrace/#' -v` shows the raw readings;
the monitor (https://coldtrace.test) shows the tile of the sensor within 5 s.

The site broker is plain MQTT on the local network, as in the development setup. On a shared network, add a
password file to `mosquitto/site/mosquitto.conf` and set `MQTT_USER` / `MQTT_PASSWORD`.

// ColdTrace ESP32 sensor: copy this file to config.h (ignored by git) and fill it in.
#pragma once

// --- Wi-Fi of the site -------------------------------------------------------------------------------
#define WIFI_SSID       "my-site-wifi"
#define WIFI_PASSWORD   "change-me"

// --- Site broker (the Mosquitto next to the gateway, gateway-iot/mosquitto/site) ----------------------
// The gateway's machine on the site network (e.g. the Raspberry Pi), port 1883, plain MQTT on the local network.
#define MQTT_HOST       "192.168.1.20"
#define MQTT_PORT       1883
// Leave empty when the site broker allows anonymous clients (the development setup does).
#define MQTT_USER       ""
#define MQTT_PASSWORD   ""

// --- Identity: must match the sensor registered in the back office (Sensors tab) -------------------------
// Letters, digits, '-' or '_', at most 32 characters. The topic is coldtrace/<SITE_ID>/<SENSOR_ID>/telemetry.
#define SITE_ID         "site01"
#define SENSOR_ID       "S01"

// --- Wiring --------------------------------------------------------------------------------------------
#define DHT_PIN         4      // DHT22 data pin (with a 10 kOhm pull-up to 3.3 V)
#define DOOR_PIN        27     // reed switch to GND; the magnet closes it when the door is shut
#define DOOR_OPEN_LEVEL HIGH   // with INPUT_PULLUP: switch open (magnet away) reads HIGH = door open
#define STATUS_LED      2      // on-board LED: on while connected to the broker (-1 for none)

// --- Timing --------------------------------------------------------------------------------------------
#define SAMPLE_INTERVAL_MS  5000UL    // one reading every 5 s, like the simulator
#define BUFFER_READINGS     600       // kept in RAM while the broker is unreachable (50 min at 5 s)
#define NTP_SERVER          "pool.ntp.org"

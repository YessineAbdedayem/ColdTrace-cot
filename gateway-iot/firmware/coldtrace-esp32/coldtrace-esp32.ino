// ColdTrace ESP32 sensor firmware.
//
// Measures temperature and humidity (DHT22) and the door (reed switch), and publishes each reading to the site
// broker on coldtrace/<site>/<sensor>/telemetry, in the format of docs/MESSAGE-FORMAT.md:
//   {"v":1,"msgId":"site01-S01-1a2b3c4d-42","seq":42,"ts":1793632152,"temperature":4.2,"humidity":61.5,"doorOpen":false}
//
// - msgId is unique and stable: <site>-<sensor>-<boot id>-<sequence>. A reading sent twice (reconnect) is stored
//   once by the backend.
// - ts is the measurement time from NTP (Unix seconds). Until NTP answers, ts is left out and the gateway stamps
//   the reading when it arrives; such readings are never buffered, since their time would be wrong.
// - A door change is sent at once, without waiting for the next 5 s tick.
// - While Wi-Fi or the broker is down, readings wait in RAM (BUFFER_READINGS) and leave in order when it is back.
//   The gateway then keeps them on disk during cloud outages.
//
// Board: any ESP32 (Arduino core 3.x). Libraries (Library Manager): "PubSubClient" (Nick O'Leary),
// "DHT sensor library" and "Adafruit Unified Sensor" (Adafruit). Settings: copy config.example.h to config.h.

#include <WiFi.h>
#include <PubSubClient.h>
#include <DHT.h>
#include <time.h>
#include <esp_system.h>
#include "config.h"

struct Reading {
  uint32_t seq;
  uint32_t ts;        // Unix seconds, 0 = unknown (the gateway uses its own clock)
  float temperature;  // NAN = not measured
  float humidity;     // NAN = not measured
  int8_t doorOpen;    // -1 unknown, 0 shut, 1 open
};

static WiFiClient net;
static PubSubClient mqtt(net);
static DHT dht(DHT_PIN, DHT22);

static Reading queue[BUFFER_READINGS];
static size_t head = 0, count = 0;      // ring buffer: oldest at head
static uint32_t dropped = 0;
static uint32_t seq = 0;
static char bootId[9];
static char topic[96];

static unsigned long lastSample = 0, lastMqttTry = 0, lastWifiTry = 0, wifiLostAt = 0;
static int lastDoor = -1;
static unsigned long doorChangedAt = 0;
static int doorCandidate = -1;

// ---------- time ----------

static bool timeValid() {
  return time(nullptr) > 1600000000;   // after 2020: NTP has answered
}

// ---------- buffer ----------

static void enqueue(const Reading& r) {
  if (count == BUFFER_READINGS) {      // full: drop the oldest, keep the newest
    head = (head + 1) % BUFFER_READINGS;
    count--;
    dropped++;
  }
  queue[(head + count) % BUFFER_READINGS] = r;
  count++;
}

static void pop() {
  head = (head + 1) % BUFFER_READINGS;
  count--;
}

// ---------- readings ----------

static int readDoor() {
  return digitalRead(DOOR_PIN) == DOOR_OPEN_LEVEL ? 1 : 0;
}

static void takeReading(bool doorOnly) {
  Reading r;
  r.seq = ++seq;
  r.ts = timeValid() ? (uint32_t) time(nullptr) : 0;
  r.temperature = doorOnly ? NAN : dht.readTemperature();
  r.humidity = doorOnly ? NAN : dht.readHumidity();
  r.doorOpen = (int8_t) (lastDoor < 0 ? readDoor() : lastDoor);
  if (!doorOnly && (isnan(r.temperature) || isnan(r.humidity))) {
    Serial.println("DHT22 read failed: sending the door state only");
  }
  if (r.ts == 0 && !mqtt.connected()) return;   // no reliable time and no link: nothing useful to keep
  enqueue(r);
}

static size_t format(const Reading& r, char* out, size_t size) {
  int n = snprintf(out, size, "{\"v\":1,\"msgId\":\"%s-%s-%s-%lu\",\"seq\":%lu", SITE_ID, SENSOR_ID, bootId,
                   (unsigned long) r.seq, (unsigned long) r.seq);
  if (r.ts) n += snprintf(out + n, size - n, ",\"ts\":%lu", (unsigned long) r.ts);
  if (!isnan(r.temperature)) n += snprintf(out + n, size - n, ",\"temperature\":%.1f", r.temperature);
  if (!isnan(r.humidity)) n += snprintf(out + n, size - n, ",\"humidity\":%.1f", r.humidity);
  if (r.doorOpen >= 0) n += snprintf(out + n, size - n, ",\"doorOpen\":%s", r.doorOpen ? "true" : "false");
  n += snprintf(out + n, size - n, "}");
  return (size_t) n;
}

// Sends what waits, oldest first; stops at the first failure so the order is kept.
static void flush() {
  char payload[256];
  int sent = 0;
  while (count > 0 && mqtt.connected() && sent < 20) {
    format(queue[head], payload, sizeof payload);
    if (!mqtt.publish(topic, payload)) break;
    pop();
    sent++;
  }
  if (dropped && count == 0) {
    Serial.printf("Buffer was full: %lu readings were lost\n", (unsigned long) dropped);
    dropped = 0;
  }
}

// ---------- connections ----------

static void ensureWifi() {
  if (WiFi.status() == WL_CONNECTED) { wifiLostAt = 0; return; }
  if (!wifiLostAt) wifiLostAt = millis();
  if (millis() - wifiLostAt > 10UL * 60 * 1000) {
    Serial.println("No Wi-Fi for 10 minutes: restarting");
    ESP.restart();   // RAM readings are lost; the gateway marks the gap as silence
  }
  if (millis() - lastWifiTry < 10000 && lastWifiTry) return;
  lastWifiTry = millis();
  Serial.printf("Connecting to Wi-Fi %s...\n", WIFI_SSID);
  WiFi.disconnect();
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
}

static void ensureMqtt() {
  if (mqtt.connected() || WiFi.status() != WL_CONNECTED) return;
  if (millis() - lastMqttTry < 5000 && lastMqttTry) return;
  lastMqttTry = millis();
  char clientId[48];
  snprintf(clientId, sizeof clientId, "coldtrace-%s-%s", SITE_ID, SENSOR_ID);
  bool ok = strlen(MQTT_USER) ? mqtt.connect(clientId, MQTT_USER, MQTT_PASSWORD) : mqtt.connect(clientId);
  Serial.printf(ok ? "Connected to %s:%d\n" : "Broker %s:%d unreachable, retrying\n", MQTT_HOST, MQTT_PORT);
}

// ---------- Arduino ----------

void setup() {
  Serial.begin(115200);
  pinMode(DOOR_PIN, INPUT_PULLUP);
  if (STATUS_LED >= 0) pinMode(STATUS_LED, OUTPUT);
  dht.begin();
  snprintf(bootId, sizeof bootId, "%08lx", (unsigned long) esp_random());
  snprintf(topic, sizeof topic, "coldtrace/%s/%s/telemetry", SITE_ID, SENSOR_ID);
  lastDoor = readDoor();

  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  lastWifiTry = millis();
  configTime(0, 0, NTP_SERVER);   // UTC; the gateway and the backend work in UTC

  mqtt.setServer(MQTT_HOST, MQTT_PORT);
  mqtt.setBufferSize(512);
  mqtt.setKeepAlive(30);
  Serial.printf("ColdTrace sensor %s/%s, boot %s, topic %s\n", SITE_ID, SENSOR_ID, bootId, topic);
}

void loop() {
  ensureWifi();
  ensureMqtt();
  mqtt.loop();
  if (STATUS_LED >= 0) digitalWrite(STATUS_LED, mqtt.connected() ? HIGH : LOW);

  // door: debounced 50 ms, sent as soon as it changes
  int door = readDoor();
  if (door != doorCandidate) { doorCandidate = door; doorChangedAt = millis(); }
  if (door != lastDoor && millis() - doorChangedAt > 50) {
    lastDoor = door;
    Serial.println(door ? "Door opened" : "Door shut");
    takeReading(true);
  }

  if (millis() - lastSample >= SAMPLE_INTERVAL_MS || lastSample == 0) {
    lastSample = millis();
    takeReading(false);
  }
  flush();
  delay(10);
}

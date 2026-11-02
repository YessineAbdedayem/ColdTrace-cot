// Fake ESP32 sensors: each one publishes temperature, humidity and door state
// to the site broker, like the real ESP32 will.
//
// Settings (environment variables, all optional):
//   BROKER_URL       mqtt://localhost:1883
//   SITE_ID          site01
//   SIM_SENSORS      S01,S02,S03
//   SIM_INTERVAL_MS  5000
//   SIM_BAD_RATE     0.05   share of broken messages, to test the gateway's checks
const mqtt = require("mqtt");

const BROKER_URL = process.env.BROKER_URL || "mqtt://localhost:1883";
const SITE_ID = process.env.SITE_ID || "site01";
const SENSORS = (process.env.SIM_SENSORS || "S01,S02,S03").split(",").map(s => s.trim()).filter(Boolean);
const INTERVAL = Number(process.env.SIM_INTERVAL_MS) || 5000;
const BAD_RATE = Number(process.env.SIM_BAD_RATE ?? 0.05);

// Each fridge starts around 4 °C. Opening the door warms it up a little.
const state = Object.fromEntries(SENSORS.map(id => [id, { temp: 3 + Math.random() * 2, hum: 60, door: false }]));

function nextReading(s) {
    if (!s.door && Math.random() < 0.03) s.door = true;          // someone opens the door
    else if (s.door && Math.random() < 0.4) s.door = false;      // ...and closes it
    const target = s.door ? 9 : 4;
    s.temp += (target - s.temp) * 0.15 + (Math.random() - 0.5) * 0.2;
    s.hum = Math.min(95, Math.max(40, s.hum + (Math.random() - 0.5) * 2 + (s.door ? 1.5 : -0.5)));
    return {
        ts: Math.floor(Date.now() / 1000),        // epoch seconds, like an ESP32 after NTP sync
        temperature: Number(s.temp.toFixed(2)),
        humidity: Number(s.hum.toFixed(1)),
        doorOpen: s.door
    };
}

// Broken messages the gateway must reject
const BAD = [
    () => "this is not json",
    () => JSON.stringify({ temperature: "hot" }),
    () => JSON.stringify({ temperature: 999 }),
    () => JSON.stringify({ hello: "world" })
];

const client = mqtt.connect(BROKER_URL, { clientId: `sim-${SITE_ID}-${process.pid}` });
client.on("connect", () => console.log(`connected to ${BROKER_URL}, sensors: ${SENSORS.join(", ")}`));
client.on("error", err => console.error("mqtt error:", err.message));

setInterval(() => {
    if (!client.connected) return;
    for (const id of SENSORS) {
        const topic = `coldtrace/${SITE_ID}/${id}/telemetry`;
        const bad = Math.random() < BAD_RATE;
        const payload = bad ? BAD[Math.floor(Math.random() * BAD.length)]() : JSON.stringify(nextReading(state[id]));
        client.publish(topic, payload, { qos: 1 });
        console.log(`${bad ? "BAD " : ""}${topic} ${payload}`);
    }
}, INTERVAL);

// Fake ESP32 sensors: each one publishes to the site broker, like the real
// ESP32s will. The exported model functions can also be tested without MQTT.
//
// Settings (environment variables, all optional):
//   BROKER_URL       mqtt://localhost:1883
//   SIM_INTERVAL_MS  5000
//   SIM_BAD_RATE     0.05   share of broken messages, to test the gateway's checks
//   SIM_RANDOM_DOOR  false  allow short, random door openings
//   SIM_FAULTS       S01=door_open,S05=silent
//   SIM_CONTROL_PORT 4000   localhost-only HTTP fault API

const http = require("node:http");
const sensorConfigs = require("./sensors.json");

const PROFILES = {
    cold: { setpoint: 2.5, normalMin: 1.5, normalMax: 3.5, alarmLimit: 4, targetTicks: 9, doorOffset: 0.6 },
    freezer: { setpoint: -20.5, normalMin: -22, normalMax: -19, alarmLimit: -18, targetTicks: 14, doorOffset: 1 }
};
const FAULTS = new Set(["door_open", "temp_rise", "silent", "none"]);
const BAD = [
    () => "this is not json",
    () => JSON.stringify({ temperature: "hot" }),
    () => JSON.stringify({ temperature: 999 }),
    () => JSON.stringify({ hello: "world" })
];
const FAULT_TARGET_MARGIN = 0.2;
const MAX_TEMPERATURE = 12;

function createSensor(config, random = Math.random) {
    const profile = PROFILES[config.profile];
    if (!profile) throw new Error(`Unknown profile "${config.profile}" for ${config.sensorId}`);
    return {
        ...config,
        temperature: profile.normalMin + random() * (profile.normalMax - profile.normalMin),
        humidity: 60 + (random() - 0.5) * 4,
        doorOpen: false,
        fault: "none"
    };
}

function createSensors(configs = sensorConfigs, random = Math.random) {
    return configs.map(config => createSensor(config, random));
}

function setFault(sensor, fault) {
    if (!FAULTS.has(fault)) throw new Error(`Unsupported fault "${fault}"`);
    sensor.fault = fault;
    if (fault === "door_open" || fault === "temp_rise") {
        const profile = PROFILES[sensor.profile];
        sensor.faultWarmingPerTick = Math.max(
            (profile.alarmLimit + FAULT_TARGET_MARGIN - sensor.temperature) / profile.targetTicks,
            0.05
        );
    } else {
        sensor.faultWarmingPerTick = null;
    }
}

function shouldPublish(sensor) {
    return sensor.fault !== "silent";
}

function nextReading(sensor, options = {}) {
    const profile = PROFILES[sensor.profile];
    const random = options.random || Math.random;
    const randomDoor = options.randomDoor === true;

    if (sensor.fault === "door_open") {
        sensor.doorOpen = true;
        sensor.temperature = Math.min(MAX_TEMPERATURE, sensor.temperature + sensor.faultWarmingPerTick);
        sensor.humidity = Math.min(70, sensor.humidity + 0.25);
    } else if (sensor.fault === "temp_rise") {
        sensor.doorOpen = false;
        sensor.temperature = Math.min(MAX_TEMPERATURE, sensor.temperature + sensor.faultWarmingPerTick);
        sensor.humidity += (60 - sensor.humidity) * 0.15;
    } else {
        if (randomDoor) {
            if (!sensor.doorOpen && random() < 0.01) sensor.doorOpen = true;
            else if (sensor.doorOpen && random() < 0.4) sensor.doorOpen = false;
        } else {
            sensor.doorOpen = false;
        }

        const target = profile.setpoint + (sensor.doorOpen ? profile.doorOffset : 0);
        sensor.temperature += (target - sensor.temperature) * 0.15 + (random() - 0.5) * 0.06;
        const humidityTarget = sensor.doorOpen ? 63 : 60;
        sensor.humidity += (humidityTarget - sensor.humidity) * 0.15 + (random() - 0.5) * 0.4;
        sensor.humidity = Math.min(65, Math.max(55, sensor.humidity));
    }

    return {
        ts: Math.floor((options.now ?? Date.now()) / 1000),
        temperature: Number(sensor.temperature.toFixed(2)),
        humidity: Number(sensor.humidity.toFixed(1)),
        doorOpen: sensor.doorOpen
    };
}

function applyStartupFaults(specification, sensors) {
    if (!specification) return;
    for (const assignment of specification.split(",")) {
        const [sensorId, fault, ...extra] = assignment.trim().split("=");
        const sensor = sensors.find(item => item.sensorId === sensorId);
        if (extra.length || !sensor || !FAULTS.has(fault)) {
            throw new Error(`Invalid SIM_FAULTS entry "${assignment}"`);
        }
        setFault(sensor, fault);
    }
}

function sensorSummary(sensor) {
    return {
        sensorId: sensor.sensorId,
        siteId: sensor.siteId,
        profile: sensor.profile,
        temperature: Number(sensor.temperature.toFixed(2)),
        humidity: Number(sensor.humidity.toFixed(1)),
        doorOpen: sensor.doorOpen,
        fault: sensor.fault
    };
}

function sendJson(response, status, value) {
    response.writeHead(status, { "Content-Type": "application/json; charset=utf-8" });
    response.end(JSON.stringify(value));
}

function startControlServer(sensors, port = 4000) {
    const server = http.createServer((request, response) => {
        const path = new URL(request.url, "http://localhost").pathname;

        if (request.method === "GET" && path === "/sensors") {
            return sendJson(response, 200, sensors.map(sensorSummary));
        }
        if (request.method === "POST" && path === "/faults/clear") {
            sensors.forEach(sensor => setFault(sensor, "none"));
            return sendJson(response, 200, { cleared: sensors.length });
        }
        if (request.method === "POST" && path === "/faults") {
            if (!/^application\/json(?:\s*;|$)/i.test(request.headers["content-type"] || "")) {
                return sendJson(response, 415, { error: "Content-Type must be application/json" });
            }

            let body = "";
            let tooLarge = false;
            request.setEncoding("utf8");
            request.on("data", chunk => {
                if (body.length + chunk.length > 1024 * 1024) tooLarge = true;
                else if (!tooLarge) body += chunk;
            });
            request.on("end", () => {
                if (tooLarge) return sendJson(response, 413, { error: "Request body is too large" });
                let value;
                try {
                    value = JSON.parse(body);
                } catch {
                    return sendJson(response, 400, { error: "Request body must be valid JSON" });
                }
                if (!value || typeof value !== "object" || Array.isArray(value)) {
                    return sendJson(response, 400, { error: "Request body must be a JSON object" });
                }
                if (!FAULTS.has(value.fault)) {
                    return sendJson(response, 400, { error: "fault must be door_open, temp_rise, silent, or none" });
                }
                const hasSensor = typeof value.sensorId === "string";
                const hasSite = typeof value.siteId === "string";
                if (hasSensor === hasSite) {
                    return sendJson(response, 400, { error: "Provide exactly one of sensorId or siteId" });
                }

                const targets = hasSensor
                    ? sensors.filter(sensor => sensor.sensorId === value.sensorId)
                    : sensors.filter(sensor => sensor.siteId === value.siteId);
                if (targets.length === 0) {
                    return sendJson(response, 404, { error: `Unknown ${hasSensor ? "sensor" : "site"}` });
                }
                targets.forEach(sensor => setFault(sensor, value.fault));
                return sendJson(response, 200, {
                    updated: targets.map(sensor => sensor.sensorId),
                    fault: value.fault
                });
            });
            return;
        }

        if (["/sensors", "/faults", "/faults/clear"].includes(path)) {
            response.setHeader("Allow", path === "/sensors" ? "GET" : "POST");
            return sendJson(response, 405, { error: "Method not allowed" });
        }
        return sendJson(response, 404, { error: "Not found" });
    });

    server.listen(port, "0.0.0.0", () => {
        console.log(`fault control API listening on 0.0.0.0:${port}`);
    });
    return server;
}

function startSimulator() {
    const mqtt = require("mqtt");
    const sensors = createSensors();
    const brokerUrl = process.env.BROKER_URL || "mqtt://localhost:1883";
    const interval = Number(process.env.SIM_INTERVAL_MS ?? 5000);
    const badRate = Number(process.env.SIM_BAD_RATE ?? 0.05);
    const controlPort = Number(process.env.SIM_CONTROL_PORT ?? 4000);
    const randomDoor = process.env.SIM_RANDOM_DOOR === "true";

    if (!Number.isFinite(interval) || interval < 1) throw new Error("SIM_INTERVAL_MS must be a positive number");
    if (!Number.isFinite(badRate) || badRate < 0 || badRate > 1) {
        throw new Error("SIM_BAD_RATE must be a number between 0 and 1");
    }
    if (!Number.isInteger(controlPort) || controlPort < 1 || controlPort > 65535) {
        throw new Error("SIM_CONTROL_PORT must be an integer between 1 and 65535");
    }
    applyStartupFaults(process.env.SIM_FAULTS, sensors);

    const server = startControlServer(sensors, controlPort);
    const client = mqtt.connect(brokerUrl, { clientId: `sim-coldtrace-${process.pid}` });
    client.on("connect", () => console.log(`connected to ${brokerUrl}, sensors: ${sensors.length}`));
    client.on("error", error => console.error("mqtt error:", error.message));

    setInterval(() => {
        if (!client.connected) return;
        for (const sensor of sensors) {
            if (!shouldPublish(sensor)) continue;
            const topic = `coldtrace/${sensor.siteId}/${sensor.sensorId}/telemetry`;
            const bad = Math.random() < badRate;
            const payload = bad
                ? BAD[Math.floor(Math.random() * BAD.length)]()
                : JSON.stringify(nextReading(sensor, { randomDoor }));
            client.publish(topic, payload, { qos: 1 }, error => {
                if (error) console.error(`publish error for ${topic}:`, error.message);
            });
            console.log(`${bad ? "BAD " : ""}${topic} ${payload}`);
        }
    }, interval);

    function shutdown() {
        server.close();
        client.end(false, () => process.exit(0));
    }
    process.once("SIGINT", shutdown);
    process.once("SIGTERM", shutdown);
}

if (require.main === module) {
    try {
        startSimulator();
    } catch (error) {
        console.error("simulator startup failed:", error.message);
        process.exitCode = 1;
    }
}

module.exports = {
    PROFILES,
    FAULTS,
    createSensor,
    createSensors,
    setFault,
    shouldPublish,
    nextReading,
    applyStartupFaults,
    sensorSummary,
    startControlServer
};

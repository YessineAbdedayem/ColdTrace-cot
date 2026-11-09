// End-to-end alert check, run by scripts/smoke-test.sh inside the simulator container.
// It warms one sensor, expects an alert over WebSocket within the 10-second target,
// then clears the fault and expects the incident to close.

const SENSOR = "S02";
const ALERTS_URL = process.env.ALERTS_URL || "ws://api:8080/ws/alerts";
const CONTROL_URL = `http://127.0.0.1:${process.env.SIM_CONTROL_PORT || 4000}`;
const TARGET_MS = 10000;
const OPEN_TIMEOUT_MS = 180000;
const CLOSE_TIMEOUT_MS = 120000;

async function post(path, body) {
    const response = await fetch(CONTROL_URL + path, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body || {})
    });
    if (!response.ok) throw new Error(`POST ${path} returned ${response.status}`);
}

function connect() {
    return new Promise((resolve, reject) => {
        const socket = new WebSocket(ALERTS_URL);
        socket.addEventListener("open", () => resolve(socket), { once: true });
        socket.addEventListener("error", () => reject(new Error(`Cannot connect to ${ALERTS_URL}`)), { once: true });
    });
}

function waitForEvent(socket, name, matches, timeoutMs) {
    return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
            socket.removeEventListener("message", onMessage);
            reject(new Error(`No ${name} event within ${timeoutMs / 1000}s`));
        }, timeoutMs);
        function onMessage(message) {
            const data = JSON.parse(message.data);
            if (data.event === name && matches(data.incident)) {
                clearTimeout(timer);
                socket.removeEventListener("message", onMessage);
                resolve({ incident: data.incident, receivedAt: Date.now() });
            }
        }
        socket.addEventListener("message", onMessage);
    });
}

async function main() {
    await post("/faults/clear");
    const socket = await connect();
    try {
        const isTarget = incident => incident.sensorId === SENSOR && incident.metric === "temperature";
        const opening = waitForEvent(socket, "incident.opened", isTarget, OPEN_TIMEOUT_MS);
        await post("/faults", { sensorId: SENSOR, fault: "temp_rise" });
        const opened = await opening;

        const latency = opened.receivedAt - Date.parse(opened.incident.openedAt);
        console.log(`  Alert for ${SENSOR} at ${opened.incident.value} C, ${latency} ms after the reading`);
        if (latency >= TARGET_MS) throw new Error(`Alert took ${latency} ms, target is under ${TARGET_MS} ms`);

        const closing = waitForEvent(socket, "incident.closed", i => i.id === opened.incident.id, CLOSE_TIMEOUT_MS);
        await post("/faults/clear");
        const closed = await closing;
        console.log(`  Incident closed at ${closed.incident.closedAt}, peak ${closed.incident.peakValue} C`);
    } finally {
        socket.close();
        await post("/faults/clear");
    }
}

main().catch(error => {
    console.error(`  ${error.message}`);
    process.exitCode = 1;
});

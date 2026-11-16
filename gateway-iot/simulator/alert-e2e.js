// End-to-end alert check, run by scripts/smoke-test.sh inside the simulator container.
// It warms one sensor, expects an alert over WebSocket within the 10-second target,
// then clears the fault and expects the incident to close.

//
// The alert WebSocket needs a login (middleware/iam): give ALERTS_TOKEN, or IAM_USERNAME and IAM_PASSWORD.

const SENSOR = "S02";
const ALERTS_URL = process.env.ALERTS_URL || "ws://api:8080/ws/alerts";
const IAM_URL = process.env.IAM_URL || "http://iam:8080";
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

async function login() {
    if (process.env.ALERTS_TOKEN) return process.env.ALERTS_TOKEN;
    const { IAM_USERNAME: username, IAM_PASSWORD: password } = process.env;
    if (!username || !password) throw new Error("Set IAM_USERNAME and IAM_PASSWORD: the alert WebSocket needs a login");
    const response = await fetch(IAM_URL + "/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username, password })
    });
    if (!response.ok) throw new Error(`Login as ${username} refused (${response.status})`);
    return (await response.json()).accessToken;
}

function connect(token) {
    return new Promise((resolve, reject) => {
        const socket = new WebSocket(`${ALERTS_URL}?access_token=${encodeURIComponent(token)}`);
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
    const socket = await connect(await login());
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

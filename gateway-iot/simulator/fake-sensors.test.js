const test = require("node:test");
const assert = require("node:assert/strict");
const { once } = require("node:events");
const sensorConfigs = require("./sensors.json");
const {
    createSensor,
    createSensors,
    setFault,
    shouldPublish,
    nextReading,
    startControlServer
} = require("./fake-sensors");

test("normal readings stay inside the project limits for 500 ticks", () => {
    for (const profile of ["cold", "freezer"]) {
        const sensor = createSensor({
            siteId: "site01",
            sensorId: "S01",
            profile
        });
        for (let tick = 0; tick < 500; tick++) {
            const reading = nextReading(sensor);
            if (profile === "cold") {
                assert.ok(reading.temperature >= 0 && reading.temperature <= 4);
            } else {
                assert.ok(reading.temperature <= -18);
            }
            assert.ok(reading.humidity >= 50 && reading.humidity <= 70);
            assert.equal(reading.doorOpen, false);
        }
    }
});

test("door_open raises temperature out of range within the expected window", () => {
    for (const profile of ["cold", "freezer"]) {
        const sensor = createSensor({ siteId: "site01", sensorId: "S01", profile }, () => 0.5);
        setFault(sensor, "door_open");
        let outOfRangeAt = null;
        for (let tick = 1; tick <= 18; tick++) {
            const reading = nextReading(sensor);
            assert.equal(reading.doorOpen, true);
            if (profile === "cold" ? reading.temperature > 4 : reading.temperature > -18) {
                outOfRangeAt = tick;
                break;
            }
        }
        assert.ok(outOfRangeAt !== null, `${profile} should become too warm`);
        if (profile === "cold") assert.ok(outOfRangeAt >= 6 && outOfRangeAt <= 12);
        else assert.ok(outOfRangeAt >= 10 && outOfRangeAt <= 18);
    }
});

test("temp_rise warms a cold sensor while keeping the door closed", () => {
    const sensor = createSensor({ siteId: "site01", sensorId: "S01", profile: "cold" }, () => 0.5);
    setFault(sensor, "temp_rise");
    const readings = Array.from({ length: 12 }, () => nextReading(sensor));
    assert.ok(readings.some(reading => reading.temperature > 4));
    assert.ok(readings.every(reading => reading.doorOpen === false));
});

test("silent sensors are excluded from publishing", () => {
    const sensor = createSensor({ siteId: "site02", sensorId: "S05", profile: "freezer" });
    setFault(sensor, "silent");
    assert.equal(shouldPublish(sensor), false);
    setFault(sensor, "none");
    assert.equal(shouldPublish(sensor), true);
});

test("clearing a fault gradually returns readings to normal", () => {
    const sensor = createSensor({ siteId: "site01", sensorId: "S01", profile: "cold" }, () => 0.5);
    setFault(sensor, "door_open");
    for (let tick = 0; tick < 50; tick++) nextReading(sensor);
    assert.ok(sensor.temperature > 4);
    setFault(sensor, "none");
    for (let tick = 0; tick < 60; tick++) nextReading(sensor);
    assert.ok(sensor.temperature >= 0 && sensor.temperature <= 4);
    assert.ok(sensor.humidity >= 55 && sensor.humidity <= 65);
    assert.equal(sensor.doorOpen, false);
});

test("the configured simulator has ten unique sensors across three sites", () => {
    const ids = sensorConfigs.map(sensor => sensor.sensorId);
    const sites = new Set(sensorConfigs.map(sensor => sensor.siteId));
    assert.equal(sensorConfigs.length, 10);
    assert.equal(new Set(ids).size, 10);
    assert.deepEqual([...sites].sort(), ["site01", "site02", "site03"]);
    assert.equal(createSensors().length, 10);
});

test("fault API validates requests and applies and clears site faults", async t => {
    const sensors = createSensors();
    const server = startControlServer(sensors, 0);
    await once(server, "listening");
    t.after(() => new Promise(resolve => server.close(resolve)));
    const address = server.address();
    const baseUrl = `http://127.0.0.1:${address.port}`;

    const listing = await fetch(`${baseUrl}/sensors`);
    assert.equal(listing.status, 200);
    assert.equal((await listing.json()).length, 10);

    const invalid = await fetch(`${baseUrl}/faults`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: "{"
    });
    assert.equal(invalid.status, 400);

    const setFaultResponse = await fetch(`${baseUrl}/faults`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ siteId: "site02", fault: "silent" })
    });
    assert.equal(setFaultResponse.status, 200);
    assert.deepEqual((await setFaultResponse.json()).updated, ["S05", "S06", "S07"]);
    assert.ok(sensors.filter(sensor => sensor.siteId === "site02").every(sensor => !shouldPublish(sensor)));

    const clearResponse = await fetch(`${baseUrl}/faults/clear`, { method: "POST" });
    assert.equal(clearResponse.status, 200);
    assert.ok(sensors.every(sensor => sensor.fault === "none"));
});

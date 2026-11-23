// Dev only: registers the simulated sensors so the alert engine knows each one's profile.
// It runs at every start and only adds missing sensors, so later edits are kept.
const sensors = JSON.parse(require("fs").readFileSync("/seed/sensors.json", "utf8"));

for (const sensor of sensors) {
    db.sites.updateOne({ _id: sensor.siteId }, {
        $setOnInsert: { name: sensor.siteName, location: "Development simulation", active: true }
    }, { upsert: true });
    db.sensors.updateOne(
        { _id: sensor.sensorId },
        { $setOnInsert: { siteId: sensor.siteId, name: `${sensor.siteName} ${sensor.sensorId}`, profile: sensor.profile } },
        { upsert: true }
    );
}
print(`Dev sensors ready: ${sensors.length}`);

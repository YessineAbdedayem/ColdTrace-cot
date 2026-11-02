#!/bin/sh
set -eu

# MongoDB runs init scripts only on the first start with an empty data volume.
# To run this again, use: docker compose down -v
mongosh --quiet \
  --username "$MONGO_INITDB_ROOT_USERNAME" \
  --password "$MONGO_INITDB_ROOT_PASSWORD" \
  --authenticationDatabase admin \
  --eval '
    const databaseName = process.env.MONGO_DB || "coldtrace";
    const database = db.getSiblingDB(databaseName);
    database.createUser({
      user: process.env.MONGO_APP_USER,
      pwd: process.env.MONGO_APP_PASSWORD,
      roles: [{ role: "readWrite", db: databaseName }]
    });
    const measurements = database.getCollection("measurements");
    measurements.createIndex({ msgId: 1 }, { unique: true, name: "msgId_unique" });
    measurements.createIndex(
      { siteId: 1, sensorId: 1, ts: -1 },
      { name: "site_sensor_ts" }
    );
    for (const name of ["incidents", "sites", "sensors", "thresholds", "users"]) {
      database.createCollection(name);
    }
    print(`Initialized ${databaseName} for the ColdTrace backend.`);
  '

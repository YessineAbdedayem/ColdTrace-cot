// Only used by hosting.compose.yml against its isolated, in-memory MongoDB.
db.measurements.createIndex({msgId: 1}, {unique: true});
db.thresholds.updateOne({_id: "cold"}, {$setOnInsert: {temperatureMin: 0, temperatureMax: 4, humidityMin: 30, humidityMax: 80}}, {upsert: true});
db.thresholds.updateOne({_id: "freezer"}, {$setOnInsert: {temperatureMin: -25, temperatureMax: -18, humidityMin: 30, humidityMax: 80}}, {upsert: true});

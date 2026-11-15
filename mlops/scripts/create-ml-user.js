// Creates (or updates the password of) the MongoDB user of the MLOps pipeline.
// Least privilege: read the platform database, read/write only the ML database.
// Run with:  docker compose run --rm ml-user
const user = process.env.ML_MONGO_USER;
const pwd = process.env.ML_MONGO_PASSWORD;
const mlDb = process.env.ML_DB || "coldtrace_ml";
const sourceDb = process.env.SOURCE_DB || "coldtrace";

if (!user || !pwd) {
    print("ML_MONGO_USER and ML_MONGO_PASSWORD must be set in mlops/.env");
    quit(1);
}

const roles = [
    { role: "readWrite", db: mlDb },
    { role: "read", db: sourceDb },
];
const admin = db.getSiblingDB("admin");
if (admin.getUser(user)) {
    admin.updateUser(user, { pwd, roles });
    print(`Updated MongoDB user ${user}`);
} else {
    admin.createUser({ user, pwd, roles });
    print(`Created MongoDB user ${user}`);
}

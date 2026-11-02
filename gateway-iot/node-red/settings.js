// Node-RED settings for the ColdTrace gateway.
// Everything that changes between laptops / sites comes from environment
// variables (see ../.env.example), so flows.json stays the same everywhere.

// Status topic built from the site id, used by the broker birth/will messages.
process.env.GATEWAY_STATUS_TOPIC =
    `coldtrace/${process.env.SITE_ID || "site01"}/gateway/status`;

module.exports = {
    flowFile: "flows.json",
    flowFilePretty: true,

    // flows_cred.json only holds "${...}" references to env vars, no real
    // secrets, so it does not need to be encrypted.
    credentialSecret: false,

    uiPort: process.env.PORT || 1880,

    // "file" keeps the offline buffer on disk, so it survives a restart.
    contextStorage: {
        default: { module: "memory" },
        file: { module: "localfilesystem", config: { flushInterval: 5 } }
    },

    logging: {
        console: { level: "info", metrics: false, audit: false }
    },

    // TODO (security step): protect the editor with a login, see
    // https://nodered.org/docs/user-guide/runtime/securing-node-red
    // adminAuth: { type: "credentials", users: [ ... ] },

    editorTheme: { projects: { enabled: false } }
};

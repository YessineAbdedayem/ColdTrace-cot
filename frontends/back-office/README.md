# ColdTrace back office

The administration PWA, served by WildFly on **https://admin.coldtrace.test** (virtual host `admin-host`,
`middleware/wildfly/cli/add-host.cli` and `coldtrace-backoffice.cli`). For **administrators only**: sign in with an
account that has the `admin` role. The API and the login server check the role again on every change.

| Tab | What | Calls |
|---|---|---|
| Overview | sites, sensors online, users, ingestion queue, gateways and their counters | `GET /api/sites`, `/api/sensors`, `/api/gateways`, `/api/ingestion`, IAM `GET /users` |
| Sites | add, edit, deactivate/reactivate; default threshold profile; **operators (drivers) who receive the site's alerts on their phone** | `/api/sites` |
| Sensors | register (id = the one in the MQTT topic), edit, move to another site, deactivate/reactivate | `/api/sensors` |
| Thresholds | limits per profile (temperature, humidity); add a profile | `/api/thresholds/{profile}` |
| Users | add (random first password), edit roles/name, enable/disable, new password, delete | IAM `/users` |
| Ingestion | durable inbox state and the quarantined (rejected) messages with their reason | `/api/ingestion`, `/api/ingestion/quarantine` |

Nothing is ever deleted from the history: "deactivate" keeps the sensor's readings and incidents and stops its
alerts. Files: `index.html`, `admin.css`, `admin.js`, `sw.js`, `manifest.webmanifest`; the icons are the front office's
(copied by `middleware/wildfly/Dockerfile`).

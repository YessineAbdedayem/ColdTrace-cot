# Postman collection

`ColdTrace.postman_collection.json` tests the REST API and the login server end to end: 44 requests in 7 folders,
each with its checks (status codes, fields, roles).

| Folder | What it checks |
|---|---|
| 1. Login (IAM) | public key, wrong password refused, admin login (saves the token), `/auth/me` |
| 2. Health and security | public health check; 401 without a token and with a forged one |
| 3. Monitoring | sensors, one site, one sensor, 404, gateways, open incidents, 400 on a bad filter, acknowledgement signed by the caller |
| 4. Catalog (admin) | thresholds, out-of-range limit refused, create/edit/deactivate a test site and sensor, operators assigned to a site, unknown field refused, ingestion state and quarantine |
| 5. History, indicators and reports | history of S01, 400 on bad input, KPI for all sites and one site, CSV and PDF compliance reports, raw readings CSV |
| 6. Alerts on phones | push status and VAPID key, subscription to a non-push host refused |
| 7. Users (IAM) | list without password hashes, weak password refused, create an operator, **operator gets 403 on a threshold change** and can read reports, delete |

## Run it

1. Start the stack (`.\start`, or `docker compose up -d` in `gateway-iot/`).
2. Postman: *Import* both files, select the environment **ColdTrace dev (coldtrace.test)**, and set `adminPassword`
   (`IAM_ADMIN_PASSWORD` in `gateway-iot/.env`).
3. The development CA must be trusted by the PC (`.\start` does it), or turn off *SSL certificate verification*.
4. *Run collection*.

From a terminal, with [newman](https://www.npmjs.com/package/newman):

```bash
npx newman run docs/postman/ColdTrace.postman_collection.json \
  -e docs/postman/ColdTrace-dev.postman_environment.json \
  --env-var adminPassword="$(grep ^IAM_ADMIN_PASSWORD= gateway-iot/.env | cut -d= -f2)" \
  --ssl-extra-ca-certs gateway-iot/certs/ca.crt
```

For a cloud deployment, duplicate the environment and set `apiUrl` / `iamUrl` to `https://api.<domain>` and
`https://iam.<domain>`.

The run leaves a deactivated site `pm-site` and sensor `PM01` (deactivating never deletes history); later runs reuse them.

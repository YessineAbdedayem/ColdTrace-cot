# ColdTrace on WildFly

**One WildFly 42 serves all of ColdTrace**, with one virtual host per name, as the course asks ("les frontends doivent
être servis depuis WildFly"). It is configured with the course's three scripts, unchanged, and one `.properties` file
per environment.

| Address | Virtual host | Serves | Configured by |
|---|---|---|---|
| https://www.coldtrace.test (and https://coldtrace.test) | `default-host` | Front-office (the monitor PWA), static files | `default-host.cli` |
| https://admin.coldtrace.test | `admin-host` | Back-office (placeholder page until it is built) | `add-host.cli` |
| https://api.coldtrace.test | `api-host` | `api-1.0.war`: REST API (`/api/...`) and alert WebSocket (`/ws/alerts`) | `add-api.cli` |
| https://iam.coldtrace.test | `iam-host` | `iam-1.0.war`: login server (`/auth/...`, `/users`) | `add-api.cli` |
| https://api.coldtrace.test/ml/ | `api-host`, location `/ml` | MLOps model API (reverse proxy to `model-api:8000`) | `coldtrace-services.cli` |
| https://mlflow.coldtrace.test | `mlflow-host` | MLflow (reverse proxy to `mlflow:5000`) | `coldtrace-services.cli` |

- **HTTP:** `http://` on port 80 redirects to `https://`, by the course's filter.
- **Unknown names:** any other name, `localhost` included, lands on the front-office, because `default-host` is the default.

## How the pieces fit

```
browser ── https://www.coldtrace.test ──▶ WildFly default-host ── static files (monitor)
   │
   ├── https://iam.coldtrace.test/auth/login ──▶ iam-host ── iam-1.0.war ── token
   ├── https://api.coldtrace.test/api/...    ──▶ api-host ── api-1.0.war ── MongoDB
   └── wss://api.coldtrace.test/ws/alerts    ──▶ api-host ── api-1.0.war (live alerts)
```

- **CORS:** the monitor calls the API and the login server **directly on their own names**. That is cross-origin, allowed by the CORS filters of the course scripts. They only accept `https://` origins of the same domain, and refuse any other site.
- **Where the monitor sends its calls:** it derives `api.<domain>` and `iam.<domain>` from its own address (`frontends/front-office/monitor.js`). The same files work in dev (`coldtrace.test`) and production (`coldtrace.tld`) without changes.
- **Preflight:** a browser first sends an `OPTIONS` request to ask permission. That request carries no token, so the API lets `OPTIONS` through without a login (`Access.isPreflight`); the real request that follows still needs its token.

## The course scripts and ours

`cli/default-host.cli`, `cli/add-host.cli` and `cli/add-api.cli` are the course's scripts, **unchanged**. ColdTrace needs
a few more settings, kept in separate files so the course scripts stay untouched:

| Script | Purpose |
|---|---|
| `coldtrace-security.cli` | Jakarta Security with our own token mechanisms (API and IAM): integrated JASPI off for the `other` security domain |
| `coldtrace-frontoffice.cli` | PWA headers: `application/manifest+json` for the manifest, `Service-Worker-Allowed: /` for `sw.js` |
| `coldtrace-tls.cli` | HTTPS with our certificate (keystore `tls.keystore`, password from the `WEB_TLS_PASSWORD` environment variable) instead of WildFly's self-signed one |
| `coldtrace-internal.cli` | An internal HTTP port (8080) for the other containers, not published to the PC (see below) |
| `coldtrace-services.cli` | MLflow and the model API (Python services) behind WildFly's reverse proxy |

**Order:**
1. `coldtrace-security`
2. `default-host` (it creates the shared filters)
3. `coldtrace-frontoffice`
4. `add-host` (admin)
5. `add-api` (api)
6. `add-api` (iam)
7. `coldtrace-tls`
8. `coldtrace-internal`
9. `coldtrace-services`

## Properties: one file per script and per environment

| File | Contents (dev) |
|---|---|
| `config/default-host-dev.properties` | `default.path=/opt/jboss/www/frontoffice`, `default.aliases=www.coldtrace.test`, `default.domains=coldtrace.test` |
| `config/admin-host-dev.properties` | `host.path`, `host.aliases=admin.coldtrace.test`, `host.key=admin`, `host.domains` |
| `config/api-dev.properties` | `api.name=api`, `api.version=1.0`, `api.aliases=api.coldtrace.test`, `api.context`, `api.domains` |
| `config/iam-dev.properties` | `api.name=iam`, `api.version=1.0`, `api.aliases=iam.coldtrace.test`, ... |
| `config/coldtrace-dev.properties` | our scripts: keystore path, internal port, MLflow and model API targets |

Each has a `-prod` version with example values (`coldtrace.tld`, `/var/opt/wildfly/www/...`): copy them with the real
domain. There are no secrets in these files.

## Two ways to apply them, same scripts

**Development (Docker):** `configure.sh` applies every script **when the image is built**, on an embedded server, with
the `-dev` properties:
- `Dockerfile` builds both WARs (their unit tests run there too) and copies the front-office files;
- it names the WARs `api-1.0.war` and `iam-1.0.war`, as `add-api.cli` expects.

**A real server:** `deploy.sh` runs each script with the course's command, then deploys the WARs:

```bash
$JBOSS_HOME/bin/jboss-cli.sh --connect --controller=remote+https://votre-hote:9993 --file=script.cli --properties=script.properties
```

```bash
JBOSS_HOME=/opt/wildfly ./deploy.sh prod remote+https://coldtrace.tld:9993 ./wars
```

The CI workflow ([wildfly-platform.yml](../../.github/workflows/wildfly-platform.yml)) runs `add-api.cli` this way on
the running test server, and packages a deployment: the two WARs, the front-office, and these scripts and properties.

## Run it (development)

`.\start` at the repository root does everything below. By hand, once per PC:

1. **Names → this PC.** In PowerShell as administrator:
   ```powershell
   Add-Content -Path C:\Windows\System32\drivers\etc\hosts -Encoding ASCII -Value "`n127.0.0.1   coldtrace.test www.coldtrace.test api.coldtrace.test iam.coldtrace.test admin.coldtrace.test mlflow.coldtrace.test"
   ipconfig /flushdns
   ```
2. **Certificate.** Add `WEB_TLS_PASSWORD=<random>` to `gateway-iot/.env`, then create the certificate (Git Bash):
   ```bash
   WEB_TLS_PASSWORD=<same value> middleware/wildfly/gen-web-cert.sh
   ```
   It writes `gateway-iot/certs/web.p12`, signed by the development CA of `gateway-iot/scripts/gen-certs.sh`. Git ignores it.
3. **Trust the development CA** in Windows (current user, no administrator rights; Chrome and Edge use it):
   ```powershell
   Import-Certificate -FilePath gateway-iot\certs\ca.crt -CertStoreLocation Cert:\CurrentUser\Root
   ```
4. **Start** (from `gateway-iot`):
   ```powershell
   docker compose up -d --build
   ```
   Then open https://coldtrace.test.

To edit the front-office without rebuilding, use the override:
```powershell
docker compose -f docker-compose.yml -f docker-compose.frontend-dev.yml up -d
```
It mounts the files read-only over `default.path`.

## Ports

| Port | Published to | Used for |
|---|---|---|
| 443 | the PC (127.0.0.1) | HTTPS, every name |
| 80 | the PC (127.0.0.1) | redirects to HTTPS (course filter) |
| 8080 | Docker network only | internal HTTP: the other containers use the full names, e.g. `http://iam.coldtrace.test:8080` (the API fetches the IAM's public key there) |

WildFly listens on 80 and 443 inside the container, so the course filters, which test for ports 80 and 443, apply
as written. Compose allows a normal user to bind these ports (`net.ipv4.ip_unprivileged_port_start: 0`).

## What we found while applying the course scripts

- **One alias per host:** `:resolve-expression` fails on a comma, so each name in `*.aliases` must be a single name. The bare domain still reaches the front-office, because it is the default host.
- **The echo line:** `add-api.cli` prints `>>> Initializing module infrastructure for: ${api.name}` with the placeholder unresolved. This is only the echo line; the host itself gets the right name.
- **Unknown paths:** the course's SPA filter answers them with **404 and `index.html` as the body**.
- **HSTS preload:** the HSTS header has `includeSubDomains; preload`. Once a browser has seen it, it uses HTTPS for every `*.coldtrace.test` name for 2 years.

## Files

| File | Purpose |
|---|---|
| `Dockerfile`, `Dockerfile.dockerignore` | The platform image (build context: repository root) |
| `configure.sh` | Applies the scripts at build time (embedded server) |
| `deploy.sh` | Applies them on a running server with the course's command, then deploys the WARs |
| `cli/` | The course's three scripts and ours |
| `config/` | One properties file per script and per environment |
| `www/backoffice/` | Placeholder page of the back-office |
| `gen-web-cert.sh` | Creates the development HTTPS certificate |

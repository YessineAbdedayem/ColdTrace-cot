# Reverse proxy (dev)

One **WildFly** container (its web server, Undertow) on port 80 that gives each ColdTrace service its own name, without
port numbers:

| Address | Service |
|---|---|
| http://coldtrace.test | Front Office (monitor) |
| http://api.coldtrace.test | REST API (`/api/...`), alert WebSocket (`/ws/alerts`), and the early-warning model (`/ml/...`, docs at `/ml/docs`) |
| http://mlflow.coldtrace.test | MLflow: training runs and model registry (start it from `mlops/`) |
| http://iam.coldtrace.test | Login server ([middleware/iam](../iam/README.md)): `/auth/login`, `/auth/me`, `/users` |

The old addresses (`localhost:3000`, `localhost:18080`) keep working.

## 1. Add the names to the hosts file (once per PC)

In PowerShell **as administrator**:

```powershell
Add-Content -Path C:\Windows\System32\drivers\etc\hosts -Encoding ASCII -Value @"

# ColdTrace (dev)
127.0.0.1   coldtrace.test
127.0.0.1   api.coldtrace.test
127.0.0.1   iam.coldtrace.test
127.0.0.1   mlflow.coldtrace.test
"@
ipconfig /flushdns
ping coldtrace.test     # must answer from 127.0.0.1
```

On Linux or macOS, add the same lines to `/etc/hosts` with `sudo`.

`.test` is reserved for local testing, so it never clashes with a real website.

## 2. Start the proxy

Start the team stack first, because the proxy joins its Docker network (`coldtrace-gateway_default`):

```powershell
cd gateway-iot
docker compose up -d
cd ..\middleware\proxy
docker compose up -d --build
```

`.\start` (repository root) does this for you when the names are in the hosts file. The first build takes a minute;
WildFly then needs about 10 seconds to start.

To stop it, run `docker compose down` from this folder. The team stack keeps running.

## How it works

- **Name lookup:** the browser looks up `coldtrace.test` in the hosts file and gets 127.0.0.1. It then connects to port 80 on your PC, where this proxy listens (WildFly's port 8080 inside the container).
- **One virtual host per name:** each name is an Undertow virtual host whose locations point to a **reverse-proxy handler**, which forwards the request to a container on the Compose network:

| Name | Location | Forwarded to |
|---|---|---|
| `coldtrace.test` | `/` | `front-office:8080` (the monitor on WildFly, which forwards `/api`, `/auth` and `/ws` itself) |
| `api.coldtrace.test` | `/` | `api:8080` (REST API and the `/ws/alerts` WebSocket) |
| `api.coldtrace.test` | `/ml` | `model-api:8000`, with the `/ml` prefix removed |
| `iam.coldtrace.test` | `/` | `iam:8080` |
| `mlflow.coldtrace.test` | `/` | `mlflow:5000` |
| any other name | `/` | a small page listing the names above |

- **Resilience:** container names are looked up when a request comes, not at startup. So the proxy starts even if a service is down, and follows a container after Compose recreates it. A service that is down answers 503.
- **No retries:** requests are never retried (`max-retries=0`), so a POST is never sent twice.
- **No token in the logs:** the proxy has no access log, so the WebSocket's `access_token` query is never written anywhere.

Local HTTP `*.coldtrace.test` is not a secure context for PWA installation: use localhost in development, or configure
a trusted HTTPS entry point in production.

## Files

| File | Purpose |
|---|---|
| `Dockerfile` | WildFly 42 image; applies `wildfly/proxy.cli` once, at build time |
| `wildfly/proxy.cli` | The configuration: outbound sockets, reverse-proxy handlers, virtual hosts |
| `wildfly/proxy.properties` | The target containers and ports (no secrets) |
| `wildfly/welcome/index.html` | The page shown for an unknown name |
| `docker-compose.yml` | Builds and runs the proxy on `127.0.0.1:80`, in the team stack's network |

## Adding a service later

1. Add the name to the hosts file.
2. In `wildfly/proxy.cli`, add an outbound socket binding, a `reverse-proxy` handler and a host with its location, like the existing ones. Put the container and port in `wildfly/proxy.properties`.
3. Rebuild: `docker compose up -d --build`.

## Problems

| Symptom | Fix |
|---|---|
| `ping coldtrace.test` can't find the host | The hosts file wasn't saved. Redo step 1 in an administrator window. |
| `network coldtrace-gateway_default not found` | Start the team stack (`gateway-iot`) first. |
| `port 80 is already allocated` | Something else uses port 80 (IIS, Skype, Apache). Stop it, or change `127.0.0.1:80:8080` to `127.0.0.1:8088:8080` and use `http://coldtrace.test:8088`. |
| `503 Service Unavailable` on one name | The target service is down or still starting: run `docker compose ps` in `gateway-iot` (or `mlops` for MLflow and the model). |
| Nothing answers right after a start | WildFly needs about 10 seconds: wait and reload. |

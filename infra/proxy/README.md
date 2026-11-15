# Reverse proxy (dev)

One Nginx container on port 80 that gives each ColdTrace service its own name, without port numbers:

| Address | Service |
|---|---|
| http://coldtrace.test | Front Office (monitor) |
| http://api.coldtrace.test | REST API (`/api/...`), alert WebSocket (`/ws/alerts`), and the early-warning model (`/ml/...`, docs at `/ml/docs`) |
| http://mlflow.coldtrace.test | MLflow: training runs and model registry (start it from `mlops/`) |
| `iam.coldtrace.test` | Reserved for the login server. It answers 404 until that service exists. |

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
cd ..\infra\proxy
docker compose up -d
```

To stop it, run `docker compose down` from this folder. The team stack keeps running.

## How it works

- The browser looks up `coldtrace.test` in the hosts file and gets 127.0.0.1. It then connects to port 80 on your PC, where this proxy listens.
- Nginx reads the requested name (the `Host` header) and forwards the request to the matching container, `front-office:80` or `api:8080`.
- Nginx resolves those names through Docker's DNS on each request. So the proxy starts even if a service is down, and it follows a container after it is recreated.
- Any other name gets a plain 404.

## Adding a service later

Add the name to the hosts file, add a `server` block to [nginx.conf](nginx.conf) like the existing ones, then reload Nginx:

```powershell
docker compose exec proxy nginx -s reload
```

## Problems

| Symptom | Fix |
|---|---|
| `ping coldtrace.test` can't find the host | The hosts file wasn't saved. Redo step 1 in an administrator window. |
| `network coldtrace-gateway_default not found` | Start the team stack (`gateway-iot`) first. |
| `port 80 is already allocated` | Something else uses port 80 (IIS, Skype, Apache). Stop it, or change `127.0.0.1:80:80` to `127.0.0.1:8081:80` and use `http://coldtrace.test:8081`. |
| `502 Bad Gateway` | The target service is down: run `docker compose ps` in `gateway-iot`. |

# Deploying ColdTrace on a cloud server (IaaS, Ubuntu)

The cloud server runs the same Docker stack as a developer PC (`gateway-iot/docker-compose.yml`), with the real
domain, public HTTPS and MQTTS open to the sites' gateways (`deploy/docker-compose.cloud.yml`). The code comes from
GitHub: **`git clone`** the first time, **`git pull`** for each update, as the specification asks.

```
          Internet                                        Cloud server (Ubuntu, Docker)
 browsers ──HTTPS 443──▶  WildFly  www.  admin.  api.  iam.  mlflow.<domain>  ──▶ MongoDB (local only)
 site gateways ─MQTTS 8883─▶  cloud broker (Mosquitto, TLS + login + ACL)  ──▶ WildFly API (durable inbox)
```

## 1. The server

- Ubuntu 22.04 or 24.04, 2 vCPU, 4 GB RAM, 30 GB disk (any provider: Azure, AWS, OVH, Scaleway, …).
- Inbound ports in the provider's firewall: **22** (SSH), **80** (certificate challenge and redirect to HTTPS),
  **443** (HTTPS), **8883** (MQTTS for the gateways).
- DNS **A records** to the server's address: `<domain>`, `www.`, `api.`, `iam.`, `admin.`, `mlflow.` and `mqtt.`.

## 2. First installation (once)

```bash
ssh ubuntu@<server>
curl -fsSLO https://raw.githubusercontent.com/<owner>/<repo>/main/deploy/ubuntu/setup.sh
sudo bash setup.sh --domain coldtrace.example.com --email ops@example.com \
                   --repo https://github.com/<owner>/<repo>.git
```

[`ubuntu/setup.sh`](ubuntu/setup.sh) installs Docker, git, certbot and the firewall, clones the repository into
`/opt/coldtrace` (user `coldtrace`), writes `gateway-iot/.env` with random passwords and the domain, creates the
MQTTS certificates (private CA) and the HTTPS certificate (Let's Encrypt, renewed automatically by certbot's timer,
which then rebuilds the WildFly keystore with [`ubuntu/web-cert.sh`](ubuntu/web-cert.sh)), builds and starts the stack.
It can run again safely. Before the DNS is ready, add `--self-signed` to use the development CA instead.

The passwords of `admin`, `qualite` and `operateur` are in `/opt/coldtrace/gateway-iot/.env` (`IAM_*_PASSWORD`).
Sign in on `https://admin.<domain>` and create the real users, sites and sensors.

## 3. Updates: `git pull`

```bash
sudo -u coldtrace /opt/coldtrace/deploy/ubuntu/update.sh
```

[`ubuntu/update.sh`](ubuntu/update.sh) pulls the branch (fast-forward only), regenerates the WildFly configuration
for the domain ([`ubuntu/render-config.sh`](ubuntu/render-config.sh)), rebuilds the images, restarts what changed and
waits until `https://api.<domain>/api/health` answers. Data stays in the Docker volumes (MongoDB, the durable inbox,
the token and push keys).

### From GitHub Actions

[`.github/workflows/deploy.yml`](../.github/workflows/deploy.yml) runs `update.sh` over SSH after the
*WildFly platform* checks pass on `main`, or by hand (*Run workflow*). Set up once:

```bash
# on the server, as coldtrace: a key that may only log in as this user
sudo -u coldtrace ssh-keygen -t ed25519 -N '' -f /home/coldtrace/.ssh/deploy
sudo -u coldtrace sh -c 'cat ~/.ssh/deploy.pub >> ~/.ssh/authorized_keys'
sudo cat /home/coldtrace/.ssh/deploy      # -> secret DEPLOY_SSH_KEY, then delete this file
ssh-keyscan -t ed25519 <server>            # (from your PC) -> secret DEPLOY_KNOWN_HOSTS
```

In the repository settings: variables `DEPLOY_HOST` (and optionally `DEPLOY_USER`, `DEPLOY_DOMAIN`), secrets
`DEPLOY_SSH_KEY` and `DEPLOY_KNOWN_HOSTS`, and an environment `production` (add required reviewers there to approve
each deployment). Without `DEPLOY_HOST` the workflow is skipped.

## 4. Connecting a site's gateway

On the site's Raspberry Pi (or PC), in `gateway-iot/.env`:

```
CLOUD_BROKER_HOST=mqtt.<domain>
CLOUD_BROKER_PORT=8883
CLOUD_GATEWAY_USER=site01            # the site id
CLOUD_GATEWAY_PASSWORD=<CLOUD_GATEWAY_PASSWORD from the server's .env>
SITE_BROKER_BIND=0.0.0.0             # the ESP32 sensors publish to the gateway
```

and copy the server's `gateway-iot/certs/ca.crt` to the gateway's `gateway-iot/certs/ca.crt` (the broker's
certificate is signed by the server's private CA). Then start only the gateway services:
`docker compose up -d --no-deps site-broker node-red`. The ESP32 firmware is in [`gateway-iot/firmware`](../gateway-iot/firmware/README.md).

The broker's access list (`gateway-iot/mosquitto/cloud/acl`) limits each gateway to its own site's topics; the
development stack ships one gateway login (`CLOUD_GATEWAY_USER`). For more sites, add a user per site to the
broker's password file in the same way.

## 5. Operations

| Task | Command (in `/opt/coldtrace`, as `coldtrace`) |
|---|---|
| State | `docker compose --project-directory gateway-iot -f gateway-iot/docker-compose.yml -f deploy/docker-compose.cloud.yml ps` |
| Logs | same, `logs -f --tail 200 wildfly` |
| Back up MongoDB | same, `exec mongo sh -c 'mongodump --archive --gzip -u "$MONGO_INITDB_ROOT_USERNAME" -p "$MONGO_INITDB_ROOT_PASSWORD"' > backup-$(date +%F).gz` |
| Stop (data kept) | same, `stop` |

The demo simulator (ten fake sensors) also runs in the cloud stack, so the monitor shows live data before real
sensors are installed. To stop it: `... stop simulator` and deactivate the simulated sensors in the back office.

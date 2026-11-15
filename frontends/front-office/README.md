# ColdTrace Front Office

The existing monitor has moved here from `middleware/api/src/main/webapp/monitor/`.
The HTML, CSS and JavaScript are preserved without changes. It remains a development
dashboard with real backend data, simulator controls and incident acknowledgement.

## Run

Start Docker Desktop, then from the repository root:

```powershell
cd gateway-iot
docker compose up -d --build
```

Open **http://localhost:3000/**. The backend keeps its existing port: `API_PORT`
(18080 on the development PC, 8080 by default). Set `FRONT_OFFICE_PORT` in
`gateway-iot/.env` to change only the frontend port.

The existing `.env` and certificates must already be present; first-time setup is
documented in [the gateway README](../../gateway-iot/README.md). Java/Maven are
provided by the API Docker build. There is no frontend build step or npm dependency.

With the backend already running, build/start only the frontend:

```powershell
docker compose up -d --build --no-deps front-office
```

After editing HTML/CSS/JavaScript or `nginx.conf`, rebuild `front-office` with the
same command. Files are copied into the image; they are not mounted live.

## REST and WebSocket configuration

| Browser request | Nginx forwards to |
|---|---|
| `GET /` and static assets | Files in this directory, copied into the Nginx image |
| `/api/*` | `http://api:8080/api/*`, preserving path, query string, method and body |
| `/ws/alerts` | The same backend path with HTTP/1.1 WebSocket upgrade headers |

The browser only talks to its own frontend origin. It never needs a Docker hostname
or the backend host port. `nginx.conf` is the single place that names the API service
and its internal port. Docker DNS is re-resolved so the proxy can recover after API
container replacement. The existing browser reconnection/backoff logic is unchanged.
Nginx forwards requests to the existing development fault proxy; it does not connect
the browser directly to the simulator. Backend CORS/security settings are unchanged.

The old URL `http://localhost:<API_PORT>/monitor/` is no longer served. Use the new
frontend URL instead. The backend root is now an informational API page. No copy of
the dashboard is included in the WAR, and no fixed-port redirect is added to the API.

## Verify the dashboard

- All three sites and ten sensor cards appear; temperature, humidity, age and curves refresh every 5 seconds.
- API, Alert link and gateway indicators become green. The logbook loads recent stored events.
- Door opens the selected simulated door and creates a door incident. Heat warms the sensor until its temperature exceeds the profile limit.
- Mute stops readings; a silence incident appears after more than 60 seconds, checked every 15 seconds.
- Acquitter records an operator and comment on an open incident without closing it.
- Reset restores the selected sensor; normal readings close incidents according to existing recovery rules.
- During a temporary network interruption, the alert indicator goes offline and reconnects when the connection returns.

These actions create persistent demonstration incidents. They do not use mocked data.
Reset only the sensors you test; avoid clearing other people's active scenarios.

From `gateway-iot/`, `docker compose logs -f front-office api` shows proxy/backend logs.
`docker compose stop` stops the stack while preserving data. The existing full smoke
test also checks frontend loading and the REST proxy, but it removes selected test
incidents and resets simulator faults; use it only on a disposable test dataset.

Back Office, authentication and PWA features remain outside this migration.

# ColdTrace Front Office

The existing monitor is now an installable Progressive Web Application with real
backend data, simulator controls and incident acknowledgement. It retains the dark
industrial interface and its three sites/ten sensors. It is served independently
of the WildFly WAR, with no frontend framework or runtime npm dependency.

**Early warnings** from the MLOps model (`early_warning` incidents, see
[middleware/api/README.md](../../middleware/api/README.md)) appear in yellow:
- **the sensor tile** gets a yellow outline, temperature and sparkline, and the flag "EARLY WARNING";
- **the logbook** gets a dashed yellow row, for example "forecast 6.2 °C within 10 min · limit 4.0 °C · was 3.1 °C · model v1";
- **the feed** gets a yellow ▲ line, and a ▼ line that says why it closed (forecast back to normal, replaced by the
  real alert, or no forecast for 3 min).

The real alert keeps its orange style.

**Login.** The monitor opens on a sign-in screen. The accounts come from the login server
([middleware/iam](../../middleware/iam/README.md)): `admin`, `qualite`, `operateur`, with passwords in
`gateway-iot/.env` (`IAM_*_PASSWORD`).
- **Where login goes:** Nginx forwards `/auth/` to the IAM, so login stays on the page's origin and needs no CORS.
- **The token** is sent as `Authorization: Bearer` on every `/api/` call, and as `?access_token=` on the alert WebSocket.
  Nginx doesn't write that WebSocket line to its access log.
- **The session** lives in `sessionStorage`: closing the tab, the token's expiry (8 h), any 401 from the API, or
  **Log out** returns to the sign-in screen, and clears the sensors and incidents from the page.
- **The header** shows the user's name and roles.
- **Acknowledgements** are signed by the logged-in account: there is no name to type any more.
- **The fault buttons** (Door, Thaw, Mute, Reset, Clear all) only work for `admin` and `operator`; the API enforces
  the same rule.

The shell cache is `coldtrace-shell-v7`. `/auth/` is never cached.

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
provided by the API Docker build. No npm install is required to run the application;
Playwright is a development-only dependency for browser tests.

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
container replacement. The browser maintains one WebSocket and one reconnect timer,
with backoff from 1 to 15 seconds; offline browsers do not run reconnect loops.
Nginx forwards requests to the existing development fault proxy; it does not connect
the browser directly to the simulator. Backend CORS/security settings are unchanged.

The old URL `http://localhost:<API_PORT>/monitor/` is no longer served. Use the new
frontend URL instead. The backend root is now an informational API page. No copy of
the dashboard is included in the WAR, and no fixed-port redirect is added to the API.

## Verify the dashboard

- The sign-in screen appears first; after signing in (for example as `operateur`), the header shows the account.
- All three sites and ten sensor cards appear; temperature, humidity, age and curves refresh every 5 seconds.
- API, Alert link and gateway indicators become green. The logbook loads recent stored events.
- Door opens the selected simulated door and creates a door incident. Thaw simulates a cooling failure: the temperature rises until it exceeds the profile limit.
- Mute stops readings; a silence incident appears after more than 60 seconds, checked every 15 seconds.
- Acquitter records the logged-in user and an optional comment on an open incident, without closing it.
- Signed in as `qualite`, the fault buttons stay disabled (operators and administrators only).
- Reset restores the selected sensor; normal readings close incidents according to existing recovery rules.
- During a temporary network interruption, the alert indicator goes offline and reconnects when the connection returns.

These actions create persistent demonstration incidents. They do not use mocked data.
Reset only the sensors you test; avoid clearing other people's active scenarios.

From `gateway-iot/`, `docker compose logs -f front-office api` shows proxy/backend logs.
`docker compose stop` stops the stack while preserving data. The existing full smoke
test also checks frontend loading and the REST proxy, but it removes selected test
incidents and resets simulator faults; use it only on a disposable test dataset.

## Install as an application

Visit **http://localhost:3000/** online first. In supported Chrome/Edge browsers,
use **Install app** when offered or the browser's install menu/address-bar icon.
The manifest provides standalone display, portrait/landscape support, dark theme
colors and original 192/512 px PNG icons, including a separate maskable icon.
The editable original is `icons/icon.svg`; the symbol stays inside the maskable
safe area. An Apple touch icon is also provided.

Safari on iPhone/iPad uses Share → Add to Home Screen instead of the Chrome install
prompt. Installation UI depends on browser/device policy; private browsing may
disable installation or persistent shell storage. Mobile Safari and native mobile
installation were not tested here. There are no push notifications in this task.

`localhost` is a secure context for local development. Production needs HTTPS.
A phone opening this PC's plain HTTP LAN IP does not receive the localhost secure
context exception: use an HTTPS deployment for actual device installation.

## Offline and connection states

The service worker saves **only the application shell**: HTML, CSS, JavaScript,
manifest and local icons. After a successful initial visit, it can reopen the
interface offline where browser storage permits. Optional Google typography loads
only as an enhancement; offline use falls back to system fonts.

No measurements, incidents, gateway responses, operator names, comments or commands
are stored in Cache Storage, localStorage or IndexedDB. `/api/*`, `/ws/alerts`,
mutations and third-party assets are excluded from service-worker interception.
REST requests use `cache: no-store`; Nginx also marks API responses `no-store`.

| Connection state | UI behavior |
|---|---|
| Browser offline | Immediately hides measurement values/curves, marks cards Not live, disables acknowledgements and simulation controls |
| REST backend unavailable | Same protection after a failed/timed-out REST refresh, even if `navigator.onLine` is true |
| WebSocket disconnected | Explicit Alerts disconnected state; REST polling and REST-backed actions can continue while REST is available |
| Gateway reports offline | Separate warning plus gateway status; existing sensor age/silence rules are preserved |

During an in-session outage, incident rows already in memory remain visible as an
explicitly **not current** snapshot; values are hidden. An offline reload has no
saved readings or incidents to restore. No action is queued or retried automatically.
If a connection fails after submitting an action, check its current state after
reconnection before trying again: the backend may have received it.

REST normally refreshes every 5 seconds; each request times out after 8 seconds.
A REST outage can therefore take up to roughly one polling interval plus the
timeout to detect. Browser offline events act immediately. On recovery, the app
refreshes measurements, incidents, gateway/fault state and event history before
restoring the relevant controls. Historical and live events are deduplicated.

Responsive layouts cover desktop, laptop, tablet, mobile portrait and landscape.
All incident information remains available; Sensors/Incidents links help jump
between sections. Controls have touch-friendly heights, keyboard focus is visible,
connection messages are announced politely, and reduced-motion settings are respected.

## Shell updates and development

`sw.js` defines a versioned `coldtrace-shell-*` cache. **Bump its version whenever
any precached shell asset changes**, then rebuild the frontend image. The worker
precaches a complete snapshot and removes only obsolete ColdTrace shell caches
when the next version activates. A new worker waits; **Update app** activates it
and reloads when the operator chooses. Open acknowledgement forms and pending
actions block that button. Other tabs are offered a reload rather than losing
an unfinished form automatically.

Static requests use network-first with HTTP cache bypass, falling back to the
installed snapshot only when the static server is unreachable. Online assets are
therefore not permanently pinned behind an old worker. Nginx serves `sw.js` with
JavaScript MIME type, root scope and `no-store`; the manifest is served as
`application/manifest+json` with revalidation.

For a clean development reset, use DevTools → Application → Service Workers →
Unregister, then remove only `coldtrace-shell-*` entries under Cache Storage and
reload online. This does not touch MongoDB. Do not use a blanket browser storage
clear if you have other applications on the same origin. Browser eviction can
remove the offline shell; visit online again to prepare it.

## Automated PWA checks

From this folder:

```powershell
npm ci
npm test
npx playwright install chromium
npm run test:browser
```

These are development tools, excluded from the Docker image. Unit tests validate
manifest/icons, shell-only caching, offline fallback, update activation and cache
cleanup. The browser runner requires the live stack and checks install eligibility,
four required viewport sizes plus mobile landscape, service-worker control, offline
reload, blocked actions, backend failure with the browser still online, and recovery.
An isolated temporary localhost server tests two shell versions and safe updates;
it never replaces operational data in the running app.

To use an installed Chrome instead of downloading test Chromium:

```powershell
$env:BROWSER_EXECUTABLE = 'C:\Program Files\Google\Chrome\Application\chrome.exe'
npm run test:browser
```

Optionally set `$env:PWA_TEST_ACTIONS = '1'` before the browser test to create one
real door incident and inspect acknowledgement forms at every viewport. It restores
only that sensor's previous fault, without deleting the incident. With the option
unset, tests do not submit simulator controls. Screenshots/results are written to
ignored `test-results/`. `FRONT_OFFICE_URL` overrides the test URL when required.

The Back Office and push notifications remain outside this task. The login (IAM) is done: see "Login" above.

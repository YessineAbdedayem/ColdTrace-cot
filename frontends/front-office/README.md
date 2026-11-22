# ColdTrace Front Office

The existing monitor is now an installable Progressive Web Application with real
backend data, simulator controls and incident acknowledgement. It retains the dark
industrial interface and its three sites/ten sensors. It has no frontend framework or runtime npm dependency.

It is served by **the ColdTrace WildFly** ([middleware/wildfly](../../middleware/wildfly/README.md)), as static files of
its default virtual host (course script `default-host.cli`), at **https://www.coldtrace.test** (and
https://coldtrace.test). The API and the login server are other virtual hosts of the same WildFly.

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
- **Where login goes:** to `https://iam.<domain>/auth/login`, the login server's own host (cross-origin, allowed by
  WildFly's CORS filters).
- **The token** is sent as `Authorization: Bearer` on every call to `https://api.<domain>/api/...`, and as
  `?access_token=` on the alert WebSocket `wss://api.<domain>/ws/alerts`.
- **The session** lives in `sessionStorage`: closing the tab, the token's expiry (8 h), any 401 from the API, or
  **Log out** returns to the sign-in screen, and clears the sensors and incidents from the page.
- **The header** shows the user's name and roles.
- **Acknowledgements** are signed by the logged-in account: there is no name to type any more.
- **The fault buttons** (Door, Thaw, Mute, Reset, Clear all) only work for `admin` and `operator`; the API enforces
  the same rule.

**History & reports** (section below the logbook, every role):
- pick a sensor and a period (1 h, 6 h, 24 h, 7 d, 30 d): **temperature and humidity charts** (Chart.js 4.6.1,
  served from `vendor/`, so it works without a CDN) with the average line, the min–max band, the profile limits
  (dashed) and the out-of-range parts in orange; hover shows the values of a time bucket;
- **indicators** for all sites or one: compliance (share of readings within limits), readings, incidents by type,
  time out of range, mean time to acknowledge, unacknowledged incidents; and a table per sensor;
- **exports**: compliance report as PDF or CSV, raw readings as CSV (`/api/reports/...`);
- the incidents of the sensor in the period, and the chart data as a table.

**Alerts on this device** (`notify.js`, Web Push): the *Enable alerts on this device* button asks for notification
permission, subscribes the browser and sends a test notification. New incidents then appear as system notifications
even when the app is closed; a click opens the logbook. The subscription stays when the session ends (a driver keeps
receiving alerts) and is re-linked to whoever signs in on the device. Needs HTTPS and the service worker.

The shell cache is `coldtrace-shell-phase-b-2`. The scripts (`vendor/chart.umd.min.js`, `monitor.js`, `reports.js`,
`notify.js`, `pwa.js`) are loaded from `<head>` with `defer`: downloaded at once, run in this order after the page is
parsed. Only same-origin shell files are cached: the API and the login server, on
other hosts, never are.

## Run

From the repository root, `.\start` builds and starts everything, including the WildFly. Or from `gateway-iot/`:

```powershell
docker compose up -d --build
```

Open **https://coldtrace.test**. First-time setup is in [middleware/wildfly/README.md](../../middleware/wildfly/README.md):
- the `*.coldtrace.test` names in the hosts file;
- the HTTPS certificate;
- trusting the development CA.

The WildFly image copies only the public files: `index.html`, `monitor.css`, `monitor.js`, `pwa.js`, `sw.js`,
`manifest.webmanifest` and `icons/`. After editing them, rebuild with `docker compose up -d --build wildfly`. For live
development, add `-f docker-compose.yml -f docker-compose.frontend-dev.yml` before `up`: the files are then mounted
read-only over the copies.

## Where the page sends its requests

| Request | Goes to |
|---|---|
| The page and its assets | `https://www.<domain>/...`, the WildFly's default host |
| Login | `https://iam.<domain>/auth/login` |
| REST | `https://api.<domain>/api/...` |
| Live alerts | `wss://api.<domain>/ws/alerts?access_token=...` |

`monitor.js` derives `api.<domain>` and `iam.<domain>` from the page's own address (scheme, domain and port). The same
files therefore work in development (`coldtrace.test`) and production (`coldtrace.tld`) with no configuration. The
browser keeps one WebSocket and one reconnect timer, with backoff from 1 to 15 seconds; offline browsers do not run
reconnect loops.

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

From `gateway-iot/`, `docker compose logs -f wildfly` shows the WildFly's logs (front-office, API and login server).
`docker compose stop` stops the stack while preserving data. The full smoke test (`gateway-iot/scripts/smoke-test.sh`)
also checks that WildFly serves the front-office and the API, but it removes selected test incidents and resets
simulator faults; use it only on a disposable test dataset.

The CI workflow [wildfly-platform.yml](../../.github/workflows/wildfly-platform.yml) runs `tests/hosting-smoke.cjs` and
`tests/pwa-browser.cjs` against an isolated stack (`tests/hosting.compose.yml`, published on port 13443).

## Install as an application

Visit **https://coldtrace.test** online first. In supported Chrome/Edge browsers,
use **Install app** when offered or the browser's install menu/address-bar icon.
The manifest provides standalone display, portrait/landscape support, dark theme
colors and original 192/512 px PNG icons, including a separate maskable icon.
The editable original is `icons/icon.svg`; the symbol stays inside the maskable
safe area. An Apple touch icon is also provided.

Safari on iPhone/iPad uses Share → Add to Home Screen instead of the Chrome install
prompt. Installation UI depends on browser/device policy; private browsing may
disable installation or persistent shell storage. Mobile Safari and native mobile
installation were not tested here. There are no push notifications in this task.

The site is served over HTTPS, which installation requires. On this PC the browser trusts the certificate once the
development CA is trusted. A phone would need the same CA, or a real certificate on a real domain.

## Offline and connection states

The service worker saves **only the application shell**: HTML, CSS, JavaScript,
manifest and local icons. After a successful initial visit, it can reopen the
interface offline where browser storage permits. Optional Google typography loads
only as an enhancement; offline use falls back to system fonts.

No measurements, incidents, gateway responses, operator names, comments or commands
are stored in Cache Storage, localStorage or IndexedDB. `/api/*`, `/ws/alerts`,
mutations and third-party assets are excluded from service-worker interception.
REST requests use `cache: no-store`; Undertow also marks responses `no-store`.

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
therefore not permanently pinned behind an old worker. Undertow serves `sw.js` with
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
$env:PWA_TEST_USER = 'operateur'
# Set PWA_TEST_PASSWORD to this dedicated test account's password without committing it.
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

# ColdTrace IAM (login server)

The login server of ColdTrace, built with **Jakarta Security** on WildFly, as the specification asks ("Jakarta
Security, authentification par rôle"). It checks usernames and passwords, and gives each user a signed **access token**
that carries their roles. The other services check that token without asking the IAM again.

```
browser ──POST /auth/login──> iam ── checks the password (coldtrace.users, PBKDF2)
                                  └─> signed token (JWT, RS256): user + roles, valid 8 h
browser ──Authorization: Bearer <token>──> api, iam ── check the signature with the public key, then @RolesAllowed
```

**Status.** Step 1 is done: the IAM runs on its own, with login, tokens and user management. Step 2 will protect the
API and the alert WebSocket with these tokens, and add a login screen to the front-office. Until then, the API and the
monitor work as before, without a login.

## Roles

These are the three profiles of the specification:

| Role | Who | Can (once step 2 protects the API) |
|---|---|---|
| `admin` | administrator | everything, including users (back-office) |
| `quality` | quality manager | alerts, history, acknowledgement, reports |
| `operator` | driver or operator | alerts and acknowledgement |

## Endpoints

| Method and path | Who | What |
|---|---|---|
| `POST /auth/login` | anyone | `{username, password}` → `{accessToken, tokenType, expiresIn, expiresAt, user}` |
| `GET /auth/me` | logged in | the user of the token |
| `POST /auth/password` | logged in | `{currentPassword, newPassword}`: change one's own password |
| `GET /auth/public-key` | anyone | the public key that checks tokens (PEM), for the other services |
| `GET /users`, `GET /users/{username}` | `admin` | the users, never with their password hash |
| `POST /users` | `admin` | `{username, name, roles, password}` |
| `PUT /users/{username}` | `admin` | `{name?, roles?, enabled?, password?}`: only the fields given change |
| `DELETE /users/{username}` | `admin` | delete a user |
| `GET /health` | anyone | `{"status": "ok"}` |

Addresses:
- from the PC: `http://localhost:8081` (`IAM_PORT`);
- through the proxy: `http://iam.coldtrace.test`;
- from the other containers: `http://iam:8080`.

## Security choices

| Topic | Choice |
|---|---|
| Passwords | Jakarta Security's `Pbkdf2PasswordHash`, PBKDF2-HMAC-SHA256, **210,000 iterations**, 32-byte salt (OWASP 2023). At least 10 characters, without the username, and not the `change-me` examples. |
| Tokens | JWT signed **RS256** with a 2048-bit RSA key. Checked: signature, algorithm (only RS256, so `none` and HMAC tricks are refused), expiry (30 s leeway), issuer `coldtrace-iam`, audience `coldtrace`. Valid 8 h (`TOKEN_TTL_MINUTES`). |
| Keys | Created on the first start in the `iam-keys` Docker volume, and reused after. The private key never leaves it; the public key is served at `/auth/public-key`. |
| Password guessing | 5 wrong passwords for a username within 15 min lock it for 5 min (HTTP 429). The answer is the same whether the username exists or not, and takes the same time. |
| Administrators | There is always at least one enabled administrator: the last one can't be disabled, deleted, or lose the role. Nobody can delete their own account. |
| Logs | Logins, refusals and user changes are logged with the username, never with a password or token. |

**How Jakarta Security is used:**
- `MongoIdentityStore` is an `IdentityStore` that checks passwords against `coldtrace.users`. `/auth/login` goes
  through the container's `IdentityStoreHandler`.
- `BearerTokenMechanism` is an `HttpAuthenticationMechanism` that reads `Authorization: Bearer`, checks the token, and
  logs the caller in with their roles. That is what makes `@RolesAllowed` work.
- The Dockerfile sets `integrated-jaspi=false` on WildFly's `other` security domain. WildFly then trusts the caller
  the mechanism logs in, instead of looking it up again in its own user files.

## First users

There are no default passwords. At startup, the IAM creates these users from `gateway-iot/.env`, **only if they are
missing**, and never changes an existing account:

| Variable | User |
|---|---|
| `IAM_ADMIN_USER` (default `admin`) and `IAM_ADMIN_PASSWORD` | the administrator, created while there is no enabled administrator |
| `IAM_QUALITY_PASSWORD` (optional) | `qualite` (role `quality`) |
| `IAM_OPERATOR_PASSWORD` (optional) | `operateur` (role `operator`) |

`.\start` (repository root) fills these with random passwords when they are empty or still `change-me...`. Read them
in `gateway-iot/.env`. A password that breaks the policy is refused, and the log says why.

## Try it (PowerShell)

```powershell
$pw = (Select-String -Path gateway-iot\.env -Pattern '^IAM_ADMIN_PASSWORD=').Line.Split('=', 2)[1]
$login = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8081/auth/login -ContentType application/json `
  -Body (@{ username = 'admin'; password = $pw } | ConvertTo-Json)
$h = @{ Authorization = "Bearer $($login.accessToken)" }
Invoke-RestMethod http://127.0.0.1:8081/auth/me -Headers $h
Invoke-RestMethod http://127.0.0.1:8081/users -Headers $h
Invoke-RestMethod -Method Post http://127.0.0.1:8081/users -Headers $h -ContentType application/json `
  -Body (@{ username = 'k.hedhili'; name = 'Khalil'; roles = @('quality'); password = 'choose-a-long-one' } | ConvertTo-Json)
```

## Build, run, test

```powershell
cd gateway-iot
docker compose up -d --build iam
docker compose logs -f iam        # "Created user ...", "IAM ready: 3 user(s), 1 enabled administrator(s)"
```

The unit tests (17) run during `docker build`:
- the JWT checks: forged payload, another key, `none` algorithm, expiry, issuer, audience, garbage;
- the keys, the login guard, the password and username rules.

To run them alone, with JDK 21: `mvn test`.

**Live test, 16 Nov 2026 (all passed):**
- **Login:** wrong password and unknown user give the same 401; the token has the right claims, RS256.
- **Endpoints:** `/auth/me`; `/users` works with admin and gives 403 with quality or no token; a forged token gives 401.
- **User management:** create, duplicate (409), disable, then login refused; the last admin is protected; nobody can
  delete themselves.
- **Guessing:** the lockout's 6th try gives 429.

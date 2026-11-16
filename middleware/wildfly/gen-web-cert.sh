#!/usr/bin/env bash
# Creates the HTTPS certificate of the ColdTrace WildFly (development only), signed by the development CA that
# gateway-iot/scripts/gen-certs.sh creates for MQTTS (gateway-iot/certs/ca.crt). Trust that CA once on the PC and the
# browser accepts https://coldtrace.test, https://api.coldtrace.test, ... without warnings.
#
# Writes, in gateway-iot/certs/ (ignored by git):
#   web.crt, web.key   the certificate and its private key
#   web.p12            both in a PKCS12 keystore, protected by WEB_TLS_PASSWORD (WildFly reads it)
#
# Usage: WEB_TLS_PASSWORD=... middleware/wildfly/gen-web-cert.sh [domain]     (domain: coldtrace.test by default)
# Runs again safely: it replaces the web certificate, never the CA.
set -euo pipefail

DOMAIN="${1:-coldtrace.test}"
DAYS=825   # browsers refuse server certificates valid longer than about 2 years
: "${WEB_TLS_PASSWORD:?Set WEB_TLS_PASSWORD (it is in gateway-iot/.env)}"

# CERTS_DIR: another folder holding ca.crt/ca.key (CI); by default gateway-iot/certs.
CERTS="$(cd "${CERTS_DIR:-$(dirname "$0")/../../gateway-iot/certs}" && pwd)"
cd "$CERTS"
[[ -f ca.crt && -f ca.key ]] || { echo "No CA in $CERTS: run gateway-iot/scripts/gen-certs.sh first." >&2; exit 1; }

SAN="DNS:$DOMAIN,DNS:*.$DOMAIN,DNS:www.$DOMAIN,DNS:api.$DOMAIN,DNS:iam.$DOMAIN,DNS:admin.$DOMAIN,DNS:mlflow.$DOMAIN,DNS:localhost,IP:127.0.0.1"

# Git Bash otherwise rewrites OpenSSL's /CN= subject as a Windows path.
MSYS2_ARG_CONV_EXCL='/CN=' openssl req -newkey rsa:2048 -nodes -sha256 \
  -keyout web.key -out web.csr -subj "/CN=$DOMAIN"
printf "subjectAltName=%s\nextendedKeyUsage=serverAuth\nkeyUsage=digitalSignature,keyEncipherment\n" "$SAN" > web.ext
openssl x509 -req -in web.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -out web.crt -days "$DAYS" -sha256 -extfile web.ext
openssl pkcs12 -export -in web.crt -inkey web.key -certfile ca.crt -name coldtrace \
  -out web.p12 -passout env:WEB_TLS_PASSWORD
rm -f web.csr web.ext
chmod 644 web.p12 web.crt   # WildFly runs as another user in the container; the keystore is password-protected

echo "Created $CERTS/web.p12 for $DOMAIN and *.$DOMAIN (valid $DAYS days)."

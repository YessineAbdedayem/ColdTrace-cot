#!/usr/bin/env bash
# Creates a small private certificate authority (CA) and a TLS certificate
# for the cloud broker. Development only: on the real cloud server you can
# reuse this CA or use a public certificate (for example Let's Encrypt).
#
# Usage:   ./scripts/gen-certs.sh [extra-hostname-or-ip ...]
# Example: ./scripts/gen-certs.sh 192.168.1.20 coldtrace.example.com
#
# Output (in ./certs, ignored by git):
#   ca.crt / ca.key                  the CA (keep ca.key private)
#   cloud-broker.crt / .key          the broker's certificate
# Clients (Node-RED, backend) only need ca.crt to trust the broker.
set -euo pipefail

cd "$(dirname "$0")/../certs"
DAYS=825

openssl_req() {
  # Git Bash otherwise rewrites OpenSSL's /CN= subject as a Windows path.
  MSYS2_ARG_CONV_EXCL='/CN=' openssl req "$@"
}

# Names the certificate is valid for from the host and Docker containers.
SAN="DNS:localhost,DNS:cloud-broker,DNS:host.docker.internal,IP:127.0.0.1"
for extra in "$@"; do
  if [[ "$extra" =~ ^[0-9.]+$ ]]; then SAN="$SAN,IP:$extra"; else SAN="$SAN,DNS:$extra"; fi
done

if [[ -f ca.key && -f ca.crt ]]; then
  echo "Reusing existing CA (delete certs/ca.* to start over)."
elif [[ -e ca.key || -e ca.crt ]]; then
  echo "Incomplete CA found; remove certs/ca.key and certs/ca.crt, then retry." >&2
  exit 1
else
  echo "Creating the ColdTrace dev CA..."
  openssl_req -x509 -newkey rsa:2048 -nodes -sha256 -days 3650 \
    -keyout ca.key -out ca.crt -subj "/CN=ColdTrace Dev CA"
fi

echo "Creating the cloud broker certificate for: $SAN"
openssl_req -newkey rsa:2048 -nodes -sha256 \
  -keyout cloud-broker.key -out cloud-broker.csr -subj "/CN=cloud-broker"
printf "subjectAltName=%s\nextendedKeyUsage=serverAuth\n" "$SAN" > cloud-broker.ext
openssl x509 -req -in cloud-broker.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -out cloud-broker.crt -days "$DAYS" -sha256 -extfile cloud-broker.ext
rm -f cloud-broker.csr cloud-broker.ext ca.srl

# The broker runs as another user inside its container, so it must be able to
# read its key. Fine for dev; on the server, give the key to the mosquitto user only.
chmod 644 cloud-broker.key ca.crt cloud-broker.crt
chmod 600 ca.key

echo
echo "Done. Files in $(pwd):"
ls -1

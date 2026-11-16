#!/usr/bin/env bash
# Applies the WildFly configuration at image build time, on an embedded server (no running server needed).
# Same scripts and properties as a real deployment, where each one is run against the live server with:
#   $JBOSS_HOME/bin/jboss-cli.sh --connect --controller=remote+https://HOST:9993 --file=SCRIPT --properties=PROPS
# (see deploy.sh).
#
# Usage: configure.sh ENV      (ENV = dev or prod: picks config/<name>-<ENV>.properties)
set -euo pipefail

ENV="${1:-dev}"
CLI=/opt/jboss/wildfly/bin/jboss-cli.sh
SCRIPTS=/opt/jboss/cli
CONFIG=/opt/jboss/config

# Order matters: default-host.cli creates the filters (HSTS, https redirect, CORS...) that the others reuse.
STEPS=(
  "coldtrace-security.cli coldtrace-$ENV.properties"
  "default-host.cli       default-host-$ENV.properties"
  "coldtrace-frontoffice.cli coldtrace-$ENV.properties"
  "add-host.cli           admin-host-$ENV.properties"
  "add-api.cli            api-$ENV.properties"
  "add-api.cli            iam-$ENV.properties"
  "coldtrace-tls.cli      coldtrace-$ENV.properties"
  "coldtrace-internal.cli coldtrace-$ENV.properties"
  "coldtrace-services.cli coldtrace-$ENV.properties"
)

for step in "${STEPS[@]}"; do
  read -r script props <<<"$step"
  echo ">>> $script with $props"
  # Each script runs on its own embedded server, as it would in its own jboss-cli session.
  { echo 'embed-server --std-out=discard'; cat "$SCRIPTS/$script"; echo; echo 'stop-embedded-server'; } > /tmp/step.cli
  "$CLI" --file=/tmp/step.cli --properties="$CONFIG/$props"
done

rm -rf /tmp/step.cli /opt/jboss/wildfly/standalone/configuration/standalone_xml_history

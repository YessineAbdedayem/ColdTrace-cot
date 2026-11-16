#!/usr/bin/env bash
# Deploys ColdTrace on a running WildFly with the course's command, one script per step:
#   $JBOSS_HOME/bin/jboss-cli.sh --connect --controller=remote+https://HOST:9993 --file=SCRIPT --properties=PROPS
#
# Usage: deploy.sh ENV CONTROLLER [WARS_DIR]
#   ENV         dev or prod: picks config/<name>-<ENV>.properties (copy the prod examples and set the real domain)
#   CONTROLLER  the management address, e.g. remote+https://coldtrace.tld:9993
#   WARS_DIR    folder holding api-1.0.war and iam-1.0.war (default: the current folder)
#
# Before: copy the front-office files to default.path and the back-office files to host.path (see the properties),
# and the HTTPS keystore to tls.keystore. JBOSS_HOME must point to a WildFly installation (for jboss-cli.sh).
# The management user is asked for by jboss-cli.sh (never stored here).
#
# In Docker (dev) the same scripts are applied at build time by configure.sh instead.
set -euo pipefail

ENV="${1:?ENV: dev or prod}"
CONTROLLER="${2:?CONTROLLER: e.g. remote+https://coldtrace.tld:9993}"
WARS="${3:-.}"
HERE="$(cd "$(dirname "$0")" && pwd)"
CLI="${JBOSS_HOME:?Set JBOSS_HOME}/bin/jboss-cli.sh"

run() {   # run SCRIPT PROPERTIES
  echo ">>> $1 with $2"
  "$CLI" --connect --controller="$CONTROLLER" --file="$HERE/cli/$1" --properties="$HERE/config/$2"
}

run coldtrace-security.cli     "coldtrace-$ENV.properties"
run default-host.cli           "default-host-$ENV.properties"     # ends with a reload
run coldtrace-frontoffice.cli  "coldtrace-$ENV.properties"
run add-host.cli               "admin-host-$ENV.properties"
run add-api.cli                "api-$ENV.properties"
run add-api.cli                "iam-$ENV.properties"
run coldtrace-tls.cli          "coldtrace-$ENV.properties"
run coldtrace-internal.cli     "coldtrace-$ENV.properties"
run coldtrace-services.cli     "coldtrace-$ENV.properties"

# The applications, under the names the hosts expect (default-web-module = <name>-<version>.war)
"$CLI" --connect --controller="$CONTROLLER" --command="deploy $WARS/api-1.0.war --force"
"$CLI" --connect --controller="$CONTROLLER" --command="deploy $WARS/iam-1.0.war --force"
"$CLI" --connect --controller="$CONTROLLER" --command=":reload"
echo "Deployed. Check https://<domain>/, https://api.<domain>/api/health and https://iam.<domain>/auth/public-key"

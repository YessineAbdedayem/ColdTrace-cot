# ColdTrace platform: ONE WildFly 42 (Jakarta EE 11) that serves everything, one virtual host per name,
# as the course asks (frontends served from WildFly):
#
#   default host  www.coldtrace.test, coldtrace.test   front-office (static PWA)      default-host.cli
#   admin-host    admin.coldtrace.test                 back-office (static)          add-host.cli
#   api-host      api.coldtrace.test                   api-1.0.war (REST, WebSocket) add-api.cli
#   iam-host      iam.coldtrace.test                   iam-1.0.war (login)           add-api.cli
#
# Build context: the repository root (see gateway-iot/docker-compose.yml).
# ARG ENV picks the properties files: config/<script>-<ENV>.properties.

# --- 1. Build the two WARs (their unit tests run here too) -----------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS api
WORKDIR /build
COPY middleware/api/pom.xml .
RUN mvn -q -B dependency:go-offline
COPY middleware/api/src ./src
RUN mvn -q -B package

FROM maven:3.9-eclipse-temurin-21 AS iam
WORKDIR /build
COPY middleware/iam/pom.xml .
RUN mvn -q -B dependency:go-offline
COPY middleware/iam/src ./src
RUN mvn -q -B package

# --- 2. WildFly ------------------------------------------------------------------------------------------------
FROM quay.io/wildfly/wildfly:42.0.0.Final-jdk21
ARG ENV=dev

# Static sites: only the public files, never tests, READMEs or configuration.
COPY frontends/front-office/index.html frontends/front-office/monitor.css frontends/front-office/monitor.js \
     frontends/front-office/pwa.js frontends/front-office/sw.js frontends/front-office/manifest.webmanifest \
     /opt/jboss/www/frontoffice/
COPY frontends/front-office/icons/ /opt/jboss/www/frontoffice/icons/
COPY middleware/wildfly/www/backoffice/ /opt/jboss/www/backoffice/

# Server configuration: the course scripts and ours, applied once with the properties of $ENV.
COPY middleware/wildfly/cli/ /opt/jboss/cli/
COPY middleware/wildfly/config/ /opt/jboss/config/
COPY middleware/wildfly/configure.sh /opt/jboss/configure.sh
RUN bash /opt/jboss/configure.sh "$ENV"

# The applications, named <name>-<version>.war as add-api.cli expects (host default-web-module).
COPY --from=api /build/target/coldtrace-api.war /opt/jboss/wildfly/standalone/deployments/api-1.0.war
COPY --from=iam /build/target/coldtrace-iam.war /opt/jboss/wildfly/standalone/deployments/iam-1.0.war

# The IAM's token signing keys live in /keys (a Docker volume); the TLS keystore is mounted in /certs.
USER root
RUN mkdir -p /keys /certs && chown jboss:jboss /keys
USER jboss

# Listen on the standard ports, so the course scripts' "port 80 -> https" redirect and "port 443 -> HSTS" filters
# apply as written (docker-compose lets the container bind ports below 1024).
# The short DNS cache lets the reverse proxies follow a container that Compose recreated.
EXPOSE 80 443
CMD ["/opt/jboss/wildfly/bin/standalone.sh", "-b", "0.0.0.0",      "-Djboss.http.port=80", "-Djboss.https.port=443",      "-Dsun.net.inetaddr.ttl=5", "-Dsun.net.inetaddr.negative.ttl=1"]

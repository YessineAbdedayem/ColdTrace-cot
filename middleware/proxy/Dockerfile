# ColdTrace reverse proxy (dev) on WildFly/Undertow. The configuration is applied once, at build time.
FROM quay.io/wildfly/wildfly:42.0.0.Final-jdk21
COPY wildfly/ /opt/jboss/proxy/
RUN { echo 'embed-server --std-out=discard'; cat /opt/jboss/proxy/proxy.cli; echo 'stop-embedded-server'; } > /tmp/proxy.cli \
 && /opt/jboss/wildfly/bin/jboss-cli.sh --resolve-parameter-values --file=/tmp/proxy.cli \
    --properties=/opt/jboss/proxy/proxy.properties \
 && rm -rf /tmp/proxy.cli /opt/jboss/wildfly/standalone/configuration/standalone_xml_history
EXPOSE 8080
# Look container names up again soon, so the proxy follows a container that Compose recreated.
ENV JAVA_OPTS_APPEND="-Dsun.net.inetaddr.ttl=5 -Dsun.net.inetaddr.negative.ttl=1"

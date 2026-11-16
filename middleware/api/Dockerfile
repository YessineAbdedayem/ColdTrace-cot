# Stage 1: build the WAR
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package

# Stage 2: WildFly 42 (Jakarta EE 11) on JDK 21
FROM quay.io/wildfly/wildfly:42.0.0.Final-jdk21
# Jakarta Security with our own mechanism (security/BearerTokenMechanism.java): WildFly must trust the caller it logs
# in, not look it up again in its own user files (integrated JASPI off for the "other" application security domain).
RUN /opt/jboss/wildfly/bin/jboss-cli.sh --commands="embed-server --std-out=discard,\
/subsystem=undertow/application-security-domain=other:write-attribute(name=integrated-jaspi,value=false),\
stop-embedded-server" \
 && rm -rf /opt/jboss/wildfly/standalone/configuration/standalone_xml_history
COPY --from=build /build/target/coldtrace-api.war /opt/jboss/wildfly/standalone/deployments/ROOT.war
EXPOSE 8080

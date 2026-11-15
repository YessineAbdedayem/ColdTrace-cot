# Stage 1: build the WAR
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package

# Stage 2: WildFly 42 (Jakarta EE 11) on JDK 21
FROM quay.io/wildfly/wildfly:42.0.0.Final-jdk21
COPY --from=build /build/target/coldtrace-api.war /opt/jboss/wildfly/standalone/deployments/ROOT.war
EXPOSE 8080

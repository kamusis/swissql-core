FROM maven:3.9.6-eclipse-temurin-21 AS build

WORKDIR /workspace

COPY pom.xml ./
RUN mvn -B -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests package

RUN JAR_PATH=$(ls -1 target/*.jar | grep -v '\\.original$' | head -n 1) && \
    test -n "$JAR_PATH" && \
    cp "$JAR_PATH" /workspace/swissql-server.jar

FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /workspace/swissql-server.jar /app/swissql-server.jar

ENV JAVA_OPTS=""
ENV SPRING_CONFIG_ADDITIONAL_LOCATION="optional:file:/config/"
ENV SPRING_CONFIG_IMPORT="optional:configtree:/run/secrets/"
ENV SWISSQL_SERVER_PORT=8080
ENV SWISSQL_LOG_LEVEL=INFO
ENV SWISSQL_AUDIT_LOG_LEVEL=INFO
# When set, audit entries are written to a rolling file in this directory.
# Mount an external volume here to persist audit logs across container restarts.
# Example: -v /host/audit:/audit -e SWISSQL_AUDIT_LOG_DIR=/audit
ENV SWISSQL_AUDIT_LOG_DIR=""
ENV SWISSQL_POOL_MAX_SIZE=5
ENV SWISSQL_POOL_MIN_IDLE=1
ENV SWISSQL_POOL_CONNECTION_TIMEOUT_MS=5000

EXPOSE ${SWISSQL_SERVER_PORT}

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/swissql-server.jar --server.port=${SWISSQL_SERVER_PORT}"]

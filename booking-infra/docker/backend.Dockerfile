# syntax=docker/dockerfile:1.7
#
# Spring Boot API. Build context is booking-be/.
#
# Two stages: Maven and the JDK stay in the builder, so the image that ships to
# the VPS carries a JRE and one jar and nothing that could compile code.

# ---- stage 1: build ---------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies resolve from the pom alone. Copying it first means a source-only
# change reuses this layer instead of re-downloading the world.
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp dependency:go-offline

COPY src ./src
# Tests are skipped deliberately: the suite drives Postgres through
# Testcontainers, which needs a Docker socket the builder does not have. CI runs
# `mvn verify` on every push; this stage only packages what CI already proved.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp package -DskipTests && \
    mv target/*.jar /build/app.jar

# ---- stage 2: runtime -------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S app && adduser -S -G app app
WORKDIR /app

COPY --from=build --chown=app:app /build/app.jar ./app.jar

USER app
EXPOSE 8074

# The JVM sees the container's cgroup limit, so a percentage tracks whatever
# mem_limit the compose file sets rather than hard-coding a heap size.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

# busybox wget ships in the base image; installing curl just to poll a health
# endpoint would add a package layer for nothing.
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD wget -q -O- http://127.0.0.1:8074/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY . .
RUN mvn -B -T 1C package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
# dist = thin birthday-bot.jar + birthday-launcher.jar + libs/ + sha256 manifest
COPY --from=build /build/app/target/dist/ ./
RUN useradd --system --create-home bot && chown -R bot:bot /app
USER bot
ENV TZ=UTC
EXPOSE 8080
# Liveness without an open port: the bot touches data/bot.liveness on every heartbeat
# (status.heartbeat-interval, 5m by default). The first field of the file is the epoch
# in milliseconds; staler than 15 minutes means the JVM is alive but the bot is hung -
# exactly the failure /healthz cannot report when the web app is disabled.
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
  CMD ["sh", "-c", "f=/app/data/bot.liveness; test -f \"$f\" && [ $(( $(date +%s%3N) - $(cut -d' ' -f1 \"$f\") )) -lt 900000 ]"]
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "birthday-launcher.jar"]

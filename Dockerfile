FROM gradle:8.10.2-jdk17 AS build
WORKDIR /workspace

COPY build.gradle.kts settings.gradle.kts /workspace/
COPY src /workspace/src

RUN gradle --no-daemon installDist

FROM eclipse-temurin:17-jre
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /workspace/build/install/purr-server /app/

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=5s --start-period=15s --retries=12 \
    CMD curl -fsS http://127.0.0.1:8080/health || exit 1

CMD ["./bin/purr-server"]

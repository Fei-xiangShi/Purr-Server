# syntax=docker/dockerfile:1.7

FROM gradle:8.10.2-jdk17@sha256:c2900027f3f0681c2cbfb09d527813851ad67aeafbb409997297efa2df20e748 AS build
WORKDIR /workspace

COPY build.gradle.kts settings.gradle.kts /workspace/
COPY gradlew /workspace/gradlew
COPY gradle /workspace/gradle
COPY domain/build.gradle.kts /workspace/domain/build.gradle.kts
COPY application/build.gradle.kts /workspace/application/build.gradle.kts
COPY infrastructure/build.gradle.kts /workspace/infrastructure/build.gradle.kts

# Keep dependency resolution independent from source changes so Docker can
# reuse it until a Gradle build file or the version catalog changes.
RUN gradle --no-daemon dependencies

COPY src /workspace/src
COPY domain/src /workspace/domain/src
COPY application/src /workspace/application/src
COPY infrastructure/src /workspace/infrastructure/src

# The Gradle base image already contains the pinned 8.10.2 distribution. Using
# it directly avoids a second wrapper download during image builds.
RUN --mount=type=cache,id=purr-server-gradle-build-cache,target=/home/gradle/.gradle/caches/build-cache-1,sharing=locked \
    gradle --no-daemon --build-cache installDist

FROM eclipse-temurin:17-jre@sha256:1824944ef1bd572d1ff0952afeb2fec7931d77c972c4fbc4dfcdf89f758fb490
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && groupadd --system purr \
    && useradd --system --gid purr --home-dir /app --shell /usr/sbin/nologin purr \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build --chown=purr:purr /workspace/build/install/purr-server /app/

USER purr

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=5s --start-period=15s --retries=12 \
    CMD curl -fsS http://127.0.0.1:8080/health/ready || exit 1

CMD ["./bin/purr-server"]

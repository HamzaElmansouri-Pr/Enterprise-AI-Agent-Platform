FROM eclipse-temurin:21.0.13.1_2-jdk-jammy@sha256:4b3d0998ee65462bd86ff060c87119b3575294cb3d0927372afa6f3708b0c8db AS build
RUN apt-get update \
    && apt-get install -y --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src src
# Database integration tests run in host/CI verify, never via a Docker socket in builds.
RUN ./mvnw -B -ntp package

FROM eclipse-temurin:21.0.13.1_2-jre-jammy@sha256:cdbc48132dd37b9e65cfccf2417c5668ac75046c4168af5486526eb9788f0e8f
ARG RELEASE_SHA=development
LABEL org.opencontainers.image.revision=$RELEASE_SHA
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 app \
    && useradd --uid 10001 --gid app --no-create-home app
WORKDIR /app
COPY --from=build --chown=app:app /build/target/control-plane-0.0.1-SNAPSHOT.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

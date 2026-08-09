# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:21.0.11_10-jdk-alpine-3.23 AS build

WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw --batch-mode --no-transfer-progress dependency:go-offline

COPY src src
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw --batch-mode --no-transfer-progress -DskipTests package

FROM eclipse-temurin:21.0.11_10-jre-alpine-3.23 AS runtime

RUN addgroup -S -g 10001 assetpulse \
    && adduser -S -D -H -u 10001 -G assetpulse assetpulse

WORKDIR /app

COPY --from=build --chown=assetpulse:assetpulse \
    /workspace/target/assetpulse-backend-0.0.1-SNAPSHOT.jar app.jar

USER 10001:10001

EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]

# syntax=docker/dockerfile:1.7

FROM node:24.14.0-alpine3.23 AS frontend-build

WORKDIR /workspace/frontend

COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci

COPY frontend/ ./
RUN npm run build

FROM eclipse-temurin:21.0.11_10-jdk-alpine-3.23 AS backend-build

WORKDIR /workspace/backend

COPY backend/.mvn .mvn
COPY backend/mvnw backend/pom.xml ./
RUN chmod +x mvnw
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw --batch-mode --no-transfer-progress dependency:go-offline

COPY backend/src src
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw --batch-mode --no-transfer-progress -DskipTests package

FROM eclipse-temurin:21.0.11_10-jre-alpine-3.23 AS runtime

RUN apk add --no-cache bash gettext nginx tini \
    && addgroup -S -g 10001 assetpulse \
    && adduser -S -D -H -h /nonexistent -u 10001 -G assetpulse assetpulse

WORKDIR /app

COPY --from=backend-build --chown=10001:10001 \
    /workspace/backend/target/assetpulse-backend-0.0.1-SNAPSHOT.jar \
    /app/app.jar
COPY --from=frontend-build --chown=10001:10001 \
    /workspace/frontend/dist \
    /usr/share/nginx/html
COPY --chown=10001:10001 frontend/nginx.conf /etc/nginx/templates/nginx.conf.template
COPY --chmod=0755 --chown=10001:10001 infra/render/start.sh /app/start.sh

ENV FRONTEND_PORT=8080 \
    BACKEND_UPSTREAM=127.0.0.1:8081 \
    SERVER_ADDRESS=127.0.0.1 \
    SERVER_PORT=8081 \
    SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=4 \
    SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE=0 \
    SERVER_TOMCAT_THREADS_MAX=20 \
    SERVER_TOMCAT_THREADS_MIN_SPARE=2 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=55.0 -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp"

USER 10001:10001

EXPOSE 8080

STOPSIGNAL SIGTERM

ENTRYPOINT ["/sbin/tini", "-g", "--"]
CMD ["/app/start.sh"]

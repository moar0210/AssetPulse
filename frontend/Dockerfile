# syntax=docker/dockerfile:1.7

FROM node:24.14.0-alpine3.23 AS build

WORKDIR /workspace

COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci

COPY . .
RUN npm run build

FROM nginx:1.29.8-alpine3.23 AS runtime

ENV BACKEND_UPSTREAM=backend:8080 \
    NGINX_ENVSUBST_FILTER=^BACKEND_UPSTREAM$ \
    NGINX_ENVSUBST_OUTPUT_DIR=/tmp

COPY nginx.conf /etc/nginx/templates/nginx.conf.template
COPY --from=build --chown=nginx:nginx /workspace/dist /usr/share/nginx/html

USER nginx

EXPOSE 8080

CMD ["nginx", "-c", "/tmp/nginx.conf", "-g", "daemon off;"]

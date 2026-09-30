# ---- Build stage: type-check and bundle the Vue app -----------------------------------------------
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# ---- Runtime stage: nginx serving the SPA and proxying /api to the backend ------------------------
FROM nginx:1.27-alpine
# A template rather than a config: the image's entrypoint substitutes the variables below into it at
# start, so one image serves both the compose stack (these defaults: Docker's embedded DNS and the
# `backend` service) and fly.io (fly/fly.frontend.toml). The filter keeps nginx's own $variables intact.
COPY nginx.conf /etc/nginx/templates/default.conf.template
# OIDC_AUTHORITY empty means no login: the web interface reads from an API running its anonymous
# read-only mode, and offers no way to write (#118). The compose stack names its Keycloak realm.
ENV NGINX_RESOLVER="127.0.0.11 valid=10s ipv6=off" \
    BACKEND_URL="http://backend:8080" \
    OIDC_AUTHORITY="" \
    OIDC_CLIENT_ID="sdlc-ui" \
    NGINX_ENVSUBST_FILTER="^(NGINX_RESOLVER|BACKEND_URL|OIDC_AUTHORITY|OIDC_CLIENT_ID)$"
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80

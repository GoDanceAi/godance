# =====================================================================
# Godance (self-hosted) — the web front: Caddy, with the app (app/) built in.
#
# Build context is app/; docker-compose.yml passes the build arguments:
#   GODANCE_URL       the API's address, built into the app (VITE_API_URL)
#   VITE_GALLERY_URL  where the Studio reads gallery recipes (/gallery/*.json);
#                     empty means the app's default, https://godance.ai
#   GIT_SHA           shown as the build on <html data-build>
#
# web.Dockerfile.dockerignore keeps node_modules, dist and any local .env
# files out of the build.
# =====================================================================

FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
ARG GODANCE_URL
ARG VITE_GALLERY_URL=
ARG GIT_SHA=unknown
RUN test -n "$GODANCE_URL" || { echo "web.Dockerfile: the GODANCE_URL build argument is required" >&2; exit 1; } \
 && VITE_API_URL="$GODANCE_URL" \
    VITE_GALLERY_URL="$VITE_GALLERY_URL" \
    GITHUB_SHA="${GIT_SHA#unknown}" \
    npm run build

FROM caddy:2-alpine
COPY --from=build /app/dist /srv/app
# The app knows the API only by the address it was built with. Keep that
# address, and refuse to serve the app once GODANCE_URL says otherwise: the
# app would call an API that is not there.
ARG GODANCE_URL
RUN printf '%s\n' "$GODANCE_URL" > /etc/caddy/app-built-for
CMD ["sh", "-c", "built=$(cat /etc/caddy/app-built-for); if [ \"$built\" != \"$GODANCE_URL\" ]; then echo \"web: the app in this image was built for $built, but GODANCE_URL is now $GODANCE_URL. Rebuild it: docker compose build web, then docker compose up -d\" >&2; exit 1; fi; exec caddy run --config /etc/caddy/Caddyfile --adapter caddyfile"]

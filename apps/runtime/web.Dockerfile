FROM caddy:2.10-alpine
COPY apps/frontend/dist /srv
COPY apps/runtime/Caddyfile /etc/caddy/Caddyfile

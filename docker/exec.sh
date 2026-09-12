#!/bin/sh
set -eu

PANEL_UID="${PUID:-1000}"
PANEL_GID="${PGID:-1000}"

umask "${UMASK:-022}"
mkdir -p /config

if [ "$(id -u)" = "0" ]; then
  addgroup -S -g "$PANEL_GID" open-panel 2>/dev/null || true
  adduser -S -D -H -u "$PANEL_UID" -G open-panel open-panel 2>/dev/null || true
  if [ -n "${DOCKER_GID:-}" ]; then
    addgroup -S -g "$DOCKER_GID" docker-host 2>/dev/null || true
    addgroup open-panel docker-host 2>/dev/null || true
  fi
  chown -R "$PANEL_UID:$PANEL_GID" /config
  exec su-exec "$PANEL_UID:$PANEL_GID" /run.sh
fi

exec /run.sh

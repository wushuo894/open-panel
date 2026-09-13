#!/bin/sh
set -u

export LANG=C.UTF-8
export LC_ALL=C.UTF-8

PANEL_PID=""
STOPPING=0

terminate() {
  STOPPING=1
  if [ -n "$PANEL_PID" ]; then
    kill -TERM "$PANEL_PID" 2>/dev/null || true
  fi
}

trap terminate TERM INT

while :; do
  java ${JAVA_OPTS:-} \
    -XX:+IgnoreUnrecognizedVMOptions \
    -XX:+UseStringDeduplication \
    -XX:+UseCompactObjectHeaders \
    --enable-native-access=ALL-UNNAMED \
    -Dfile.encoding=UTF-8 \
    -jar /usr/app/open-panel.jar &
  PANEL_PID=$!
  wait "$PANEL_PID"
  EXIT_CODE=$?
  PANEL_PID=""

  if [ "$STOPPING" -eq 1 ]; then
    exit 0
  fi
  if [ "$EXIT_CODE" -ne 0 ]; then
    exit "$EXIT_CODE"
  fi
done

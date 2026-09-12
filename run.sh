#!/bin/sh
set -eu

JAR_PATH="${OPEN_PANEL_JAR:-./open-panel-application/target/open-panel.jar}"
exec java ${JAVA_OPTS:-} -jar "$JAR_PATH" "$@"

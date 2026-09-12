#!/bin/sh
set -eu

export LANG=C.UTF-8
export LC_ALL=C.UTF-8

exec java ${JAVA_OPTS:-} \
  -XX:+IgnoreUnrecognizedVMOptions \
  -XX:+UseStringDeduplication \
  -XX:+UseCompactObjectHeaders \
  --enable-native-access=ALL-UNNAMED \
  -Dfile.encoding=UTF-8 \
  -jar /usr/app/open-panel.jar

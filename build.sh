#!/bin/sh
# Builds, tests and packages the service using nothing but the JDK (javac, jar, java, jlink).
# No build tool to download, upgrade or trust. Requires JDK 21 or newer.
#
#   ./build.sh test      compile and run the test suite
#   ./build.sh package   build target/app.jar (modular, executable)
#   ./build.sh image     build a minimal self-contained runtime in target/image (jlink)
#   ./build.sh run       run the packaged jar
#   ./build.sh all       clean + test + package + image (default)
#   ./build.sh clean
#
# STRICT=1 turns compiler warnings into errors. CI sets it on the baseline JDK only, so a future
# JDK that adds new lint categories can never break a plain local build.
set -eu

cd "$(dirname "$0")"

RELEASE=25
OUT=target
JAVAC_FLAGS="--release $RELEASE -encoding UTF-8 -Xlint:all -parameters"
if [ "${STRICT:-0}" = "1" ]; then JAVAC_FLAGS="$JAVAC_FLAGS -Werror"; fi

log() { printf '==> %s\n' "$*"; }

clean() {
  rm -rf "$OUT"
}

compile_main() {
  log "Compiling main sources"
  rm -rf "$OUT/classes"
  mkdir -p "$OUT/classes"
  find src/main/java -name '*.java' | sort > "$OUT/main-sources.txt"
  # shellcheck disable=SC2086
  javac $JAVAC_FLAGS -d "$OUT/classes" @"$OUT/main-sources.txt"
  cp -R src/main/resources/. "$OUT/classes/"
}

run_tests() {
  log "Compiling tests"
  rm -rf "$OUT/test-classes"
  mkdir -p "$OUT/test-classes"
  # Tests run on the class path, so the module descriptor is left out.
  find src/main/java src/test/java -name '*.java' ! -name module-info.java | sort > "$OUT/test-sources.txt"
  # shellcheck disable=SC2086
  javac $JAVAC_FLAGS -d "$OUT/test-classes" @"$OUT/test-sources.txt"
  cp -R src/main/resources/. "$OUT/test-classes/"
  log "Running tests"
  java -ea -cp "$OUT/test-classes" com.example.app.testing.TestRunner "$OUT/test-classes"
}

package() {
  compile_main
  log "Packaging $OUT/app.jar"
  rm -f "$OUT/app.jar"
  jar --create --file "$OUT/app.jar" --main-class com.example.app.Main -C "$OUT/classes" .
}

image() {
  [ -f "$OUT/app.jar" ] || package
  log "Linking runtime image $OUT/image"
  rm -rf "$OUT/image"
  jlink \
    --module-path "$OUT/app.jar" \
    --add-modules com.example.app \
    --strip-debug --no-header-files --no-man-pages --compress=zip-6 \
    --output "$OUT/image"
  log "Image size: $(du -sh "$OUT/image" | cut -f1). Run: $OUT/image/bin/java -m com.example.app"
}

case "${1:-all}" in
  clean)   clean ;;
  test)    run_tests ;;
  package) package ;;
  image)   image ;;
  run)     [ -f "$OUT/app.jar" ] || package; exec java -XX:+ExitOnOutOfMemoryError -p "$OUT/app.jar" -m com.example.app ;;
  all)     clean; run_tests; package; image ;;
  *)       echo "usage: $0 [all|clean|test|package|image|run]" >&2; exit 64 ;;
esac

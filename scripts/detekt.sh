#!/usr/bin/env bash
#
# Runs detekt static analysis via the standalone CLI (decoupled from Gradle, so it
# never touches the app build). Downloads and caches the CLI jar on first run.
#
#   scripts/detekt.sh                 # analyse app/src/main/java
#   scripts/detekt.sh --create-baseline \
#       --baseline config/detekt/baseline.xml   # grandfather current findings
#
# Exit code is non-zero when detekt reports findings.
set -euo pipefail

DETEKT_VERSION="1.23.8"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE_DIR="${HOME}/.cache/detekt"
JAR="${CACHE_DIR}/detekt-cli-${DETEKT_VERSION}-all.jar"
BASELINE="${ROOT}/config/detekt/baseline.xml"

mkdir -p "${CACHE_DIR}"
if [ ! -f "${JAR}" ]; then
  echo "Downloading detekt-cli ${DETEKT_VERSION}…"
  curl -fsSL \
    "https://repo1.maven.org/maven2/io/gitlab/arturbosch/detekt/detekt-cli/${DETEKT_VERSION}/detekt-cli-${DETEKT_VERSION}-all.jar" \
    -o "${JAR}"
fi

ARGS=(
  --config "${ROOT}/config/detekt/detekt.yml"
  --build-upon-default-config
  --input "${ROOT}/app/src/main/java"
  --report "sarif:${ROOT}/build/reports/detekt/detekt.sarif"
  --report "html:${ROOT}/build/reports/detekt/detekt.html"
)
# Apply the baseline (grandfathered findings) only when it exists and the caller
# isn't currently (re)generating it.
if [ -f "${BASELINE}" ] && [[ " $* " != *" --create-baseline "* ]] && [[ " $* " != *" --baseline "* ]]; then
  ARGS+=(--baseline "${BASELINE}")
fi

mkdir -p "${ROOT}/build/reports/detekt"
exec java -jar "${JAR}" "${ARGS[@]}" "$@"

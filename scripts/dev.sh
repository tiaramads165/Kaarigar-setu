#!/usr/bin/env bash
# Starts the ML service (:8001) and Tomcat 10.1 (:8080) locally. Ctrl-C stops both.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
export PATH="$JAVA_HOME/bin:$PATH"
[ -f .env.local ] || ./scripts/dev-setup.sh
# values given on the command line win over .env.local (e.g. STT_PROVIDER=stub ./scripts/dev.sh for the scripted tests)
CALLER_STT="${STT_PROVIDER:-}"
CALLER_PUBLIC_BASE_URL="${PUBLIC_BASE_URL:-}"
set -a; . ./.env.local; set +a
[ -z "$CALLER_STT" ] || STT_PROVIDER="$CALLER_STT"
[ -z "$CALLER_PUBLIC_BASE_URL" ] || PUBLIC_BASE_URL="$CALLER_PUBLIC_BASE_URL"
# QR codes must hold an address a PHONE can open ('localhost' on a phone is the phone itself). Unless PUBLIC_BASE_URL was set by the caller,
# use this computer's network address; the app is then reachable from a phone on the same Wi-Fi at that address.
if [ -z "${CALLER_PUBLIC_BASE_URL:-}" ]; then
  LAN_IP="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || hostname -I 2>/dev/null | awk '{print $1}')"
  if [ -n "$LAN_IP" ]; then export PUBLIC_BASE_URL="http://$LAN_IP:${APP_PORT:-8090}"; fi
fi
echo "Credential QR codes will open: ${PUBLIC_BASE_URL:-unset}"
export AUDIT_LOG_PATH="${AUDIT_LOG_PATH:-$PWD/data/audit.log}"
export KEYSTORE_PATH="$PWD/${KEYSTORE_PATH#./}"
mkdir -p data/logs

( cd kaarigarsetu-ml-service && INTERNAL_API_TOKEN="$ML_INTERNAL_TOKEN" STT_PROVIDER="${STT_PROVIDER:-faster-whisper}" WHISPER_MODEL="${WHISPER_MODEL:-large-v3-turbo}" APP_ENV="$APP_ENV" \
  exec .venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 8001 ) > data/logs/ml.log 2>&1 &
ML_PID=$!
trap 'kill $ML_PID 2>/dev/null || true' EXIT

cd kaarigarsetu-java
mvn -q -DskipTests package
PORT="${APP_PORT:-8090}"
# Cargo also opens RMI/shutdown and AJP ports; offset them so a second Tomcat on this machine does not collide.
# CARGO_JVMARGS lets a caller add JVM flags to Tomcat (scripts/coverage.sh uses it to attach the JaCoCo agent).
exec mvn cargo:run ${CARGO_JVMARGS:+"-Dcargo.jvmargs=$CARGO_JVMARGS"} -Dcargo.servlet.port="$PORT" -Dcargo.rmi.port="$((PORT + 125))" -Dcargo.tomcat.ajp.port="$((PORT - 71))"

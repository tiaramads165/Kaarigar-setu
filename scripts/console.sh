#!/usr/bin/env bash
# Starts the JavaFX moderator console.   ./scripts/console.sh [backend-url]       (default: http://localhost:$APP_PORT, i.e. 8090)
#
# Why a script and not just `java -jar`: on macOS, /usr/bin/java is a placeholder that fails with "Unable to locate a Java Runtime" unless a
# JDK is linked into the default path, and Homebrew's JDKs are not. This finds a suitable JDK (17 or newer) itself, builds the jar when it is
# missing or older than the sources, and starts the console. The jar bundles the JavaFX natives of the machine that built it, so build it on
# the OS and CPU you run it on (this script does that automatically).
set -euo pipefail
cd "$(dirname "$0")/.."

java_major() { "$1" -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p'; }
usable() { [ -x "$1" ] && [ "$(java_major "$1" 2>/dev/null || echo 0)" -ge 17 ] 2>/dev/null; }

JAVA_BIN=""
for candidate in "${JAVA_HOME:+$JAVA_HOME/bin/java}" \
                 "$(/usr/libexec/java_home -v 17+ 2>/dev/null | sed 's#$#/bin/java#')" \
                 /opt/homebrew/opt/openjdk@21/bin/java /opt/homebrew/opt/openjdk@17/bin/java /opt/homebrew/opt/openjdk/bin/java \
                 /usr/local/opt/openjdk@21/bin/java /usr/local/opt/openjdk/bin/java \
                 "$(command -v java 2>/dev/null || true)"; do
  [ -n "$candidate" ] && usable "$candidate" && { JAVA_BIN="$candidate"; break; }
done
if [ -z "$JAVA_BIN" ]; then
  echo "No Java 17 or newer found. Install one (macOS: brew install openjdk@21) and run this again." >&2
  exit 1
fi
export JAVA_HOME="$(cd "$(dirname "$JAVA_BIN")/.." && pwd)"

JAR="kaarigarsetu-moderator-console/target/kaarigarsetu-moderator-console.jar"
if [ ! -f "$JAR" ] || [ -n "$(find kaarigarsetu-moderator-console/src kaarigarsetu-moderator-console/pom.xml -newer "$JAR" -print -quit 2>/dev/null)" ]; then
  command -v mvn >/dev/null || { echo "Maven is needed to build the console (brew install maven)." >&2; exit 1; }
  echo "Building the console..."
  (cd kaarigarsetu-moderator-console && PATH="$JAVA_HOME/bin:$PATH" mvn -q -DskipTests package)
fi

PORT="${APP_PORT:-}"
if [ -z "$PORT" ] && [ -f .env.local ]; then PORT="$(sed -n 's/^APP_PORT=\([0-9]*\).*/\1/p' .env.local | head -1)"; fi
URL="${1:-${KS_BASE_URL:-http://localhost:${PORT:-8090}}}"

echo "Starting the moderator console against $URL (Java $(java_major "$JAVA_BIN"))"
exec "$JAVA_BIN" --enable-native-access=ALL-UNNAMED -jar "$JAR" "$URL"

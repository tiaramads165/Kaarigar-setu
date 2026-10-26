#!/usr/bin/env bash
# Fills the DEVELOPMENT database with made-up workers and employers (names, assessments, employer feedback, credentials, trust scores) and
# lets you sign in as any of them with one click at /demo, so you do not have to register and answer everything by hand.
#   ./scripts/demo-seed.sh            create the demo accounts (does nothing if they exist)
#   ./scripts/demo-seed.sh --reset    delete the demo accounts and everything attached to them, then create them again
# Needs the ML service running (./scripts/dev.sh) for the employer-standing part of the trust score; without it the scores are still computed.
# Safety: refuses to run unless APP_ENV=dev. It connects as the database OWNER (the application's own role may not delete employers), so it
# is for local use only. Demo phone numbers (90000 00101 to 00299) are made up and never contacted.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
export PATH="$JAVA_HOME/bin:$PATH"
[ -f .env.local ] || { echo "Run ./scripts/dev-setup.sh first."; exit 1; }
set -a; . ./.env.local; set +a
[ "${APP_ENV:-}" = "dev" ] || { echo "Refusing: APP_ENV is '${APP_ENV:-}', demo data is for development only."; exit 2; }

# the owner role (dev-setup created the database as the current OS user); local trust authentication ignores the password
export DB_USER="${DEMO_DB_USER:-$(whoami)}" DB_PASSWORD="${DEMO_DB_PASSWORD:-unused}"
export AUDIT_LOG_PATH="${AUDIT_LOG_PATH:-$PWD/data/audit.log}" KEYSTORE_PATH="$PWD/${KEYSTORE_PATH#./}"

[ -d kaarigarsetu-java/target/classes ] || (cd kaarigarsetu-java && mvn -q -DskipTests package)
CP_FILE=kaarigarsetu-java/target/demo.classpath
if [ ! -f "$CP_FILE" ] || [ kaarigarsetu-java/pom.xml -nt "$CP_FILE" ]; then
  (cd kaarigarsetu-java && mvn -q dependency:build-classpath -DincludeScope=compile -Dmdep.outputFile=target/demo.classpath >/dev/null)
fi
exec java --enable-native-access=ALL-UNNAMED -cp "kaarigarsetu-java/target/classes:$(cat "$CP_FILE")" com.kaarigarsetu.admin.DemoSeed "$@"

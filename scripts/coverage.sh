#!/usr/bin/env bash
# Java coverage that includes the servlet layer: unit/integration tests (JaCoCo via Maven) PLUS a live end-to-end run
# (scripts/e2e.py against Tomcat started with the JaCoCo agent), merged into one report.
#   ./scripts/coverage.sh        ->  kaarigarsetu-java/target/site/jacoco-merged/index.html  and  jacoco-merged.csv
# Needs: Postgres with the *_test database (TEST_DB_URL), and the same prerequisites as scripts/dev.sh.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
export PATH="$JAVA_HOME/bin:$PATH"
AGENT="$HOME/.m2/repository/org/jacoco/org.jacoco.agent/0.8.12/org.jacoco.agent-0.8.12-runtime.jar"
CLI_DIR="$PWD/data/tools"; mkdir -p "$CLI_DIR"
CLI="$CLI_DIR/org.jacoco.cli-0.8.12-nodeps.jar"
PORT="${APP_PORT:-8090}"

( cd kaarigarsetu-java && TEST_DB_URL="${TEST_DB_URL:?set TEST_DB_URL, e.g. jdbc:postgresql://localhost:5432/ks_blueprint_test}" mvn -q test )
[ -f "$CLI" ] || ( cd kaarigarsetu-java && mvn -q dependency:copy -Dartifact=org.jacoco:org.jacoco.cli:0.8.12:jar:nodeps -DoutputDirectory="$CLI_DIR" )

rm -f kaarigarsetu-java/target/jacoco-e2e.exec
STT_PROVIDER=stub CARGO_JVMARGS="-javaagent:$AGENT=destfile=$PWD/kaarigarsetu-java/target/jacoco-e2e.exec,append=false" ./scripts/dev.sh > data/logs/coverage-dev.log 2>&1 &
for i in $(seq 1 60); do curl -fs "http://localhost:$PORT/health" >/dev/null 2>&1 && break; sleep 3; done
JAVA_LOG="$PWD/data/logs/coverage-dev.log" python3 scripts/e2e.py | tail -3
# SIGTERM (not KILL) so the agent's shutdown hook writes the data file
pkill -TERM -f "catalina.home=.*kaarigarsetu-java/target/cargo" || true
pkill -TERM -f "uvicorn app.main:app" || true
sleep 5

java -jar "$CLI" merge kaarigarsetu-java/target/jacoco.exec kaarigarsetu-java/target/jacoco-e2e.exec --destfile kaarigarsetu-java/target/jacoco-merged.exec
java -jar "$CLI" report kaarigarsetu-java/target/jacoco-merged.exec --classfiles kaarigarsetu-java/target/classes \
  --sourcefiles kaarigarsetu-java/src/main/java --html kaarigarsetu-java/target/site/jacoco-merged --csv kaarigarsetu-java/target/site/jacoco-merged.csv
echo "report: kaarigarsetu-java/target/site/jacoco-merged/index.html"

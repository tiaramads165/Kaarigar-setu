#!/usr/bin/env bash
# The operator's tool for moderator accounts. Run it on the server with database-OWNER rights; the web application itself cannot create or change moderators.
#   ./scripts/moderator-admin.sh add alice "Alice Rao" 9876543210     (the password is read from standard input / prompted; at least 12 characters)
#   ./scripts/moderator-admin.sh list
#   ./scripts/moderator-admin.sh disable alice      (their open sessions stop working on their next request)
#   ./scripts/moderator-admin.sh enable alice
#   ./scripts/moderator-admin.sh set-password alice
# Create at least TWO moderators: vetting an employer and ruling on a dispute need a second person to approve. Each change is written to the database audit trail.
# Connection: DB_HOST, DB_PORT, DB_NAME from .env.local; user ADMIN_DB_USER (default: your operating-system user, the database owner in development), ADMIN_DB_PASSWORD if needed.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
export PATH="$JAVA_HOME/bin:$PATH"
[ -f .env.local ] && { set -a; . ./.env.local; set +a; }
# in CI and similar setups the owner is given through the standard PG* variables
export ADMIN_DB_USER="${ADMIN_DB_USER:-${PGUSER:-}}" ADMIN_DB_PASSWORD="${ADMIN_DB_PASSWORD:-${PGPASSWORD:-}}"
LIB="kaarigarsetu-java/target/kaarigarsetu-java/WEB-INF/lib"
[ -d "$LIB" ] || { echo "build first: (cd kaarigarsetu-java && mvn -q -DskipTests package)" >&2; exit 1; }
exec java -cp "kaarigarsetu-java/target/classes:$LIB/*" com.kaarigarsetu.admin.ModeratorAdminCli "$@"

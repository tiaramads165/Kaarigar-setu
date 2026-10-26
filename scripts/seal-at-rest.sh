#!/usr/bin/env bash
# The operator's tool for encryption at rest (transcripts, scores, ratings, names, signed credential payloads, credential export files, the audit file).
#   ./scripts/seal-at-rest.sh check                   what is encrypted, what is still plain, what an older key sealed, and whether every value opens (exit 1 if anything is plain)
#   ./scripts/seal-at-rest.sh seal                    encrypt what is plain, re-encrypt what an older key sealed; safe while the application runs
#   ./scripts/seal-at-rest.sh seal --app-stopped      the same, and also encrypt the audit FILE in place (stop the application first: it appends to that file)
#   ./scripts/seal-at-rest.sh audit-read [lines]      print the last lines of the audit file, decrypted
# Run it after migration 010 (existing values are still plain), and when rotating the key (new key FIRST in DATA_ENC_KEYS, then 'seal', then 'check', and only
# then drop the old key; never drop a key that sealed an audit line: the audit table and file cannot be re-encrypted).
# Connection: DB_HOST, DB_PORT, DB_NAME from .env.local; ADMIN_DB_USER (default: your operating-system user) and ADMIN_DB_PASSWORD. Keys are read from the environment.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
export PATH="$JAVA_HOME/bin:$PATH"
[ -f .env.local ] && { set -a; . ./.env.local; set +a; }
export ADMIN_DB_USER="${ADMIN_DB_USER:-${PGUSER:-}}" ADMIN_DB_PASSWORD="${ADMIN_DB_PASSWORD:-${PGPASSWORD:-}}"
export AUDIT_LOG_PATH="${AUDIT_LOG_PATH:-$PWD/data/audit.log}"
LIB="kaarigarsetu-java/target/kaarigarsetu-java/WEB-INF/lib"
[ -d "$LIB" ] || { echo "build first: (cd kaarigarsetu-java && mvn -q -DskipTests package)" >&2; exit 1; }
exec java -cp "kaarigarsetu-java/target/classes:$LIB/*" com.kaarigarsetu.admin.SealAtRestCli "$@"

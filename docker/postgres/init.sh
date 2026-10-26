#!/usr/bin/env bash
# Runs once, on first start of an empty Postgres volume (docker-entrypoint-initdb.d): schema, every migration in order,
# then the least-privilege application role. The application never connects as the owner.
set -euo pipefail
DB_SCRIPTS_DIR="${DB_SCRIPTS_DIR:-/database}"
: "${APP_DB_PASSWORD:?APP_DB_PASSWORD is required}"
PSQL=(psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -q)

"${PSQL[@]}" -f "$DB_SCRIPTS_DIR/schema.sql"
"${PSQL[@]}" -c "CREATE TABLE IF NOT EXISTS schema_migrations (filename TEXT PRIMARY KEY, applied_at TIMESTAMP DEFAULT now())"
for file in "$DB_SCRIPTS_DIR"/migrations/*.sql; do
  "${PSQL[@]}" -f "$file"
  "${PSQL[@]}" -c "INSERT INTO schema_migrations (filename) VALUES ('$(basename "$file")')"
  echo "applied $(basename "$file")"
done
"${PSQL[@]}" -v app_password="$APP_DB_PASSWORD" -f "$DB_SCRIPTS_DIR/setup_roles.sql"
"${PSQL[@]}" -c "GRANT SELECT, INSERT ON schema_migrations TO kaarigar_app"
echo "database initialised; application role kaarigar_app is DML-only"

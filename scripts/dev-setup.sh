#!/usr/bin/env bash
# One-time (idempotent) local setup: secrets in .env.local, database + migrations + least-privilege role,
# the credential-signing keystore, the Python venv, and the WAR build. Nothing secret is written to a tracked file.
set -euo pipefail
cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
export PATH="$JAVA_HOME/bin:/opt/homebrew/opt/postgresql@16/bin:$PATH"
b64() { openssl rand -base64 "$1"; }

if [ ! -f .env.local ]; then
  umask 077
  DBPASS="$(openssl rand -hex 16)"
  cat > .env.local <<ENV
APP_ENV=dev
DB_HOST=localhost
DB_PORT=5432
DB_NAME=${DB_NAME:-ks_blueprint}
DB_USER=kaarigar_app
DB_PASSWORD=${DBPASS}
ML_SERVICE_URL=http://localhost:8001
ML_INTERNAL_TOKEN=$(openssl rand -hex 24)
PHONE_ENC_KEY=$(b64 32)
DATA_ENC_KEYS=k1:$(b64 32)         # encrypts stored transcripts, scores, the audit file and credential exports: first key encrypts, all keys decrypt (rotate: put a new key FIRST)
PHONE_HMAC_KEY=$(b64 32)
OTP_PEPPER=$(openssl rand -hex 16)
KEYSTORE_PATH=data/credential-keystore.p12
KEYSTORE_PASSWORD=$(openssl rand -hex 16)
KEY_ALIAS=credential-signing
RSA_KEY_BITS=3072
COOKIE_SECURE=false
PUBLIC_BASE_URL=http://localhost:${APP_PORT:-8090}
APP_PORT=${APP_PORT:-8090}
MODERATOR_PASSWORD_PLAIN=$(openssl rand -hex 8)
MODERATOR2_PASSWORD_PLAIN=$(openssl rand -hex 8)
STT_PROVIDER=${STT_PROVIDER:-faster-whisper}
WHISPER_MODEL=${WHISPER_MODEL:-large-v3-turbo}
DEMO_LOGIN=true            # DEV ONLY: one-click sign-in at /demo for the accounts made by scripts/demo-seed.sh (refused unless APP_ENV=dev)
TRUST_FORWARDED_FOR=true   # DEV ONLY: lets scripts/e2e.py simulate distinct client IPs. Keep false unless behind a trusted proxy.
ENV
  echo "Generated .env.local with fresh development secrets (git-ignored)."
fi
# older .env.local files predate encryption at rest: give them a data key (without it the application refuses to start)
grep -q '^DATA_ENC_KEYS=\|^DATA_ENC_KEY=' .env.local || printf 'DATA_ENC_KEYS=k1:%s\n' "$(openssl rand -base64 32)" >> .env.local
# older .env.local files predate named moderators: add the second development moderator's password
grep -q '^MODERATOR2_PASSWORD_PLAIN=' .env.local || printf 'MODERATOR2_PASSWORD_PLAIN=%s\n' "$(openssl rand -hex 8)" >> .env.local
set -a; . ./.env.local; set +a

# ---- database: owner connection is the current OS user; the application connects as the restricted role ----
createdb "$DB_NAME" 2>/dev/null || true
if [ "$(psql -d "$DB_NAME" -Atc "SELECT to_regclass('public.workers') IS NOT NULL")" != "t" ]; then
  psql -q -v ON_ERROR_STOP=1 -d "$DB_NAME" -f database/schema.sql
fi
psql -q -d "$DB_NAME" -c "CREATE TABLE IF NOT EXISTS schema_migrations (filename TEXT PRIMARY KEY, applied_at TIMESTAMP DEFAULT now())"
for f in database/migrations/*.sql; do
  name="$(basename "$f")"
  if [ "$(psql -d "$DB_NAME" -Atc "SELECT count(*) FROM schema_migrations WHERE filename='$name'")" = "0" ]; then
    psql -q -v ON_ERROR_STOP=1 -d "$DB_NAME" -f "$f"
    psql -q -d "$DB_NAME" -c "INSERT INTO schema_migrations (filename) VALUES ('$name')"
    echo "applied $name"
  fi
done
psql -q -v ON_ERROR_STOP=1 -v app_password="$DB_PASSWORD" -d "$DB_NAME" -f database/setup_roles.sql
psql -q -d "$DB_NAME" -c "GRANT SELECT, INSERT ON schema_migrations TO kaarigar_app" >/dev/null

# ---- signing key (once) ----
./scripts/generate-signing-key.sh "$KEYSTORE_PATH" "$RSA_KEY_BITS"

# ---- Python virtualenv ----
if [ ! -d kaarigarsetu-ml-service/.venv ]; then
  python3.11 -m venv kaarigarsetu-ml-service/.venv
  kaarigarsetu-ml-service/.venv/bin/pip install -q --upgrade pip
  kaarigarsetu-ml-service/.venv/bin/pip install -q -r kaarigarsetu-ml-service/requirements.txt
fi
# Real speech-to-text. Default: faster-whisper (the same Whisper model in 8-bit, no PyTorch: about 0.8 GB model, 1.3 GB RAM). Downloads a pinned model on first use.
# SKIP_WHISPER=1 skips it (use STT_PROVIDER=stub, a labelled test fixture). STT_PROVIDER=whisper selects the original openai-whisper (PyTorch, about 1.6 GB model, 3+ GB RAM).
if [ -z "${SKIP_WHISPER:-}" ]; then
  if [ "${STT_PROVIDER:-faster-whisper}" = "whisper" ]; then
    if ! kaarigarsetu-ml-service/.venv/bin/python -c "import whisper" 2>/dev/null; then
      kaarigarsetu-ml-service/.venv/bin/pip install -q "setuptools<81" wheel
      kaarigarsetu-ml-service/.venv/bin/pip install -q --no-build-isolation -r kaarigarsetu-ml-service/requirements-whisper.txt
    fi
  elif ! kaarigarsetu-ml-service/.venv/bin/python -c "import faster_whisper" 2>/dev/null; then
    kaarigarsetu-ml-service/.venv/bin/pip install -q -r kaarigarsetu-ml-service/requirements-faster-whisper.txt
  fi
fi

# ---- Java build + two named development moderators (vetting and dispute rulings need a second person) ----
(cd kaarigarsetu-java && mvn -q -DskipTests package)
if [ "$(psql -d "$DB_NAME" -Atc "SELECT count(*) FROM moderators")" = "0" ]; then
  # development accounts: "moderator" and "moderator2", passwords in .env.local; the sign-in codes go to the server log (scripts/otp.sh)
  printf '%s\n' "$MODERATOR_PASSWORD_PLAIN"  | ./scripts/moderator-admin.sh add moderator  "Development Moderator One" 9000000901
  printf '%s\n' "$MODERATOR2_PASSWORD_PLAIN" | ./scripts/moderator-admin.sh add moderator2 "Development Moderator Two" 9000000902
fi
# convert any values an older version left in plain text (instant on a fresh database; the audit file is done with --app-stopped, see docs/SECURITY_RUNBOOK.md)
./scripts/seal-at-rest.sh seal
echo "Setup complete. Start everything with ./scripts/dev.sh"

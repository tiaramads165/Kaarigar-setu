#!/usr/bin/env bash
# One-time setup for `docker compose`: generates all secrets into ./.env (git-ignored, mode 600), builds the images and
# prints how to create the moderator accounts. Re-running keeps an existing .env.   Needs: docker, openssl.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -f .env ]; then
  echo ".env already exists; keeping it. Delete it (and the volumes: docker compose down -v) to start over."
  exit 0
fi
command -v docker >/dev/null || { echo "docker is required"; exit 1; }

umask 077
cat > .env <<ENV
DB_NAME=kaarigar_setu
DB_OWNER_PASSWORD=$(openssl rand -hex 16)
DB_PASSWORD=$(openssl rand -hex 16)
ML_INTERNAL_TOKEN=$(openssl rand -hex 24)
PHONE_ENC_KEY=$(openssl rand -base64 32)
DATA_ENC_KEYS=k1:$(openssl rand -base64 32)
PHONE_HMAC_KEY=$(openssl rand -base64 32)
OTP_PEPPER=$(openssl rand -hex 16)
AUDIT_HMAC_KEY=$(openssl rand -hex 32)
KEYSTORE_PASSWORD=$(openssl rand -hex 16)
TWO_PERSON_ACTIONS=VET_EMPLOYER,DISPUTE_DECISION
PUBLIC_BASE_URL=https://localhost:8443
PUBLIC_HOST=localhost
APP_ENV=staging
STT_PROVIDER=stub
WITH_WHISPER=0
LLM_API_KEY=
ENV

docker compose build
echo
echo "Setup complete."
echo "  Start:  docker compose up -d      then open https://localhost:8443"
echo "  Then create at least TWO moderators (vetting and dispute rulings need a second person to approve). Moderators are made only by the operator, with the"
echo "  database owner's credentials, never by the web application. The password is read from standard input:"
echo "    set -a; . ./.env; set +a"
echo "    printf '%s\\n' 'a-long-password' | docker compose run -T --rm --no-deps -e ADMIN_DB_USER=ks_owner -e ADMIN_DB_PASSWORD=\"\$DB_OWNER_PASSWORD\" app moderator-admin add alice \"Alice Rao\" 9876543210"
echo "  (each moderator's sign-in code goes to their own number; list them with: ... app moderator-admin list)"
echo "  OTP codes (dev sender) appear in:  docker compose logs -f app"

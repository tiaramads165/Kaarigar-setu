#!/usr/bin/env bash
# Rotates the credential-signing key WITHOUT invalidating credentials already issued.
#
#   KEYSTORE_PASSWORD=...  ./scripts/rotate-signing-key.sh [keystore] [retired-keys-dir]
#
# What it does
#   1. Saves the current PUBLIC key as <retired-dir>/<keyId>.pem. The application loads that directory (RETIRED_KEYS_DIR) and keeps verifying
#      every credential signed with a retired key, because each credential records the id of the key that signed it.
#   2. Moves the old keystore (it holds the old PRIVATE key) to <keystore-dir>/archive/ with mode 600. Keep that copy offline or destroy it: a retired
#      private key must never sign again.
#   3. Creates a new key pair in the keystore. Credentials issued from now on carry the new key id.
# Afterwards RESTART the application. Anyone who pinned the old public key keeps working for old credentials; give them the new one for new credentials
# (GET /api/credential/keys lists every key, active and retired).
set -euo pipefail
: "${KEYSTORE_PASSWORD:?set KEYSTORE_PASSWORD}"
KEYSTORE="${1:-${KEYSTORE_PATH:-data/credential-keystore.p12}}"
RETIRED="${2:-${RETIRED_KEYS_DIR:-data/keys/retired}}"
ALIAS="${KEY_ALIAS:-credential-signing}"
BITS="${RSA_KEY_BITS:-3072}"
[ -f "$KEYSTORE" ] || { echo "No keystore at $KEYSTORE" >&2; exit 1; }
command -v keytool >/dev/null && command -v openssl >/dev/null || { echo "keytool and openssl are required" >&2; exit 1; }
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

keytool -exportcert -rfc -alias "$ALIAS" -keystore "$KEYSTORE" -storepass "$KEYSTORE_PASSWORD" > "$WORK/cert.pem"
openssl x509 -in "$WORK/cert.pem" -pubkey -noout > "$WORK/public.pem"
KEYID="$(openssl pkey -pubin -in "$WORK/public.pem" -outform DER | openssl dgst -sha256 -binary | od -An -tx1 | tr -d ' \n' | cut -c1-16)"
echo "Retiring key $KEYID"

mkdir -p "$RETIRED" "$(dirname "$KEYSTORE")/archive"
if [ -e "$RETIRED/$KEYID.pem" ]; then echo "$RETIRED/$KEYID.pem already exists; refusing to continue" >&2; exit 1; fi
cp "$WORK/public.pem" "$RETIRED/$KEYID.pem"
chmod 644 "$RETIRED/$KEYID.pem"

ARCHIVE="$(dirname "$KEYSTORE")/archive/$(basename "$KEYSTORE").retired-$KEYID"
mv "$KEYSTORE" "$ARCHIVE"
chmod 600 "$ARCHIVE"
keytool -genkeypair -alias "$ALIAS" -keyalg RSA -keysize "$BITS" -validity 3650 \
        -dname "CN=Kaarigar Setu Credential Issuer, O=Kaarigar Setu" \
        -storetype PKCS12 -keystore "$KEYSTORE" -storepass "$KEYSTORE_PASSWORD" -keypass "$KEYSTORE_PASSWORD" 2>/dev/null
chmod 600 "$KEYSTORE"
keytool -exportcert -rfc -alias "$ALIAS" -keystore "$KEYSTORE" -storepass "$KEYSTORE_PASSWORD" > "$WORK/new.pem"
NEWID="$(openssl x509 -in "$WORK/new.pem" -pubkey -noout | openssl pkey -pubin -outform DER | openssl dgst -sha256 -binary | od -An -tx1 | tr -d ' \n' | cut -c1-16)"
echo "New signing key: $NEWID"
echo "Old public key kept in:   $RETIRED/$KEYID.pem"
echo "Old PRIVATE key archived: $ARCHIVE  (store offline or destroy it)"
echo "Restart the application so it loads the new key."

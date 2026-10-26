#!/usr/bin/env bash
# Creates the credential-signing RSA key pair ONCE, at deploy time (blueprint Phase 4). The keystore stays outside
# git (see .gitignore) and the app only needs its path and password from the environment.
#   KEYSTORE_PASSWORD=...  ./scripts/generate-signing-key.sh [path] [bits]
set -euo pipefail
: "${KEYSTORE_PASSWORD:?set KEYSTORE_PASSWORD (at least 6 characters)}"
KEYSTORE="${1:-${KEYSTORE_PATH:-data/credential-keystore.p12}}"
BITS="${2:-${RSA_KEY_BITS:-3072}}"
ALIAS="${KEY_ALIAS:-credential-signing}"
if [ -f "$KEYSTORE" ]; then echo "Keystore already exists at $KEYSTORE; refusing to overwrite (rotate keys deliberately)."; exit 0; fi
mkdir -p "$(dirname "$KEYSTORE")"
keytool -genkeypair -alias "$ALIAS" -keyalg RSA -keysize "$BITS" -validity 3650 \
        -dname "CN=Kaarigar Setu Credential Issuer, O=Kaarigar Setu" \
        -storetype PKCS12 -keystore "$KEYSTORE" -storepass "$KEYSTORE_PASSWORD" -keypass "$KEYSTORE_PASSWORD"
chmod 600 "$KEYSTORE"
echo "Created $BITS-bit RSA signing key in $KEYSTORE (alias $ALIAS)."

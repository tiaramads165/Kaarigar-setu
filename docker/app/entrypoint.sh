#!/usr/bin/env bash
# Container entrypoint for the Java service.
#   (no args)        create the credential-signing keystore on first start, then run Tomcat
#   hash-password    read a password on stdin, print its PBKDF2 hash
#   moderator-admin  create / list / disable moderator accounts (needs database-OWNER credentials in ADMIN_DB_USER and ADMIN_DB_PASSWORD; password on stdin)
set -euo pipefail
APP_DIR="${APP_DIR:-/usr/local/tomcat/webapps/ROOT}"

if [ "${1:-}" = "hash-password" ]; then
  exec java -cp "$APP_DIR/WEB-INF/classes" com.kaarigarsetu.security.PasswordHasher
fi

if [ "${1:-}" = "moderator-admin" ]; then
  shift
  exec java -cp "$APP_DIR/WEB-INF/classes:$APP_DIR/WEB-INF/lib/*" com.kaarigarsetu.admin.ModeratorAdminCli "$@"
fi

KEYSTORE_PATH="${KEYSTORE_PATH:-/usr/local/tomcat/data/credential-keystore.p12}"
if [ ! -f "$KEYSTORE_PATH" ]; then
  : "${KEYSTORE_PASSWORD:?KEYSTORE_PASSWORD is required to create the signing key}"
  mkdir -p "$(dirname "$KEYSTORE_PATH")"
  # Created once, kept on the data volume. Losing this file means every issued credential stops verifying.
  keytool -genkeypair -alias "${KEY_ALIAS:-credential-signing}" -keyalg RSA -keysize "${RSA_KEY_BITS:-3072}" -validity 3650 \
          -dname "CN=Kaarigar Setu Credential Issuer, O=Kaarigar Setu" -storetype PKCS12 \
          -keystore "$KEYSTORE_PATH" -storepass "$KEYSTORE_PASSWORD" -keypass "$KEYSTORE_PASSWORD" >/dev/null
  chmod 600 "$KEYSTORE_PATH"
  echo "created ${RSA_KEY_BITS:-3072}-bit RSA signing key at $KEYSTORE_PATH"
fi
exec catalina.sh run

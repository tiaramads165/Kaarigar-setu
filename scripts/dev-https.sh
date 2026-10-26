#!/usr/bin/env bash
# Development HTTPS for phones. The microphone only works on https:// (or http://localhost on the computer itself), so a phone that opens the
# app by this computer's network address over plain http gets "This phone cannot record audio". This makes a self-signed certificate for this
# computer's addresses (kept in data/, git-ignored) and starts an HTTPS front on port 8443 that passes requests to the running app.
#
#   ./scripts/dev.sh          (terminal 1: the app)
#   ./scripts/dev-https.sh    (terminal 2: this)  then open the https:// address it prints, on the phone
#
# The phone shows a certificate warning (the certificate is self-signed, for development only): choose to continue. To avoid it, create a
# locally trusted certificate with mkcert and pass it with CERT=... KEY=... Never use this for real users.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .env.local ] && { set -a; . ./.env.local; set +a; }
APP_PORT="${APP_PORT:-8090}"; TLS_PORT="${TLS_PORT:-8443}"
LAN_IP="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || hostname -I 2>/dev/null | awk '{print $1}' || true)"
CERT="${CERT:-data/dev-tls.crt}"; KEY="${KEY:-data/dev-tls.key}"
mkdir -p data
if [ ! -f "$CERT" ] || [ ! -f "$KEY" ]; then
  CONF="$(mktemp)"; trap 'rm -f "$CONF"' EXIT
  cat > "$CONF" <<CONF2
[req]
distinguished_name = dn
x509_extensions = ext
prompt = no
[dn]
CN = Kaarigar Setu local development
[ext]
subjectAltName = DNS:localhost,IP:127.0.0.1${LAN_IP:+,IP:$LAN_IP}
basicConstraints = CA:FALSE
keyUsage = digitalSignature,keyEncipherment
extendedKeyUsage = serverAuth
CONF2
  umask 077
  openssl req -x509 -newkey rsa:2048 -sha256 -days 30 -nodes -keyout "$KEY" -out "$CERT" -config "$CONF" 2>/dev/null
  echo "Made a development certificate for localhost${LAN_IP:+ and $LAN_IP} (valid 30 days): $CERT"
fi
curl -s -o /dev/null -m 3 "http://127.0.0.1:$APP_PORT/health" || { echo "The app is not answering on port $APP_PORT. Start it first with ./scripts/dev.sh" >&2; exit 1; }
echo "Open on this computer:  https://localhost:$TLS_PORT   (or simply http://localhost:$APP_PORT)"
[ -n "${LAN_IP:-}" ] && echo "Open on the phone:      https://$LAN_IP:$TLS_PORT   (same Wi-Fi; accept the certificate warning once)"
echo "Tip: for the credential QR code to open over https too, start the app with PUBLIC_BASE_URL=https://${LAN_IP:-<this-computer>}:$TLS_PORT"
exec python3 scripts/dev_https_proxy.py --listen "$TLS_PORT" --backend "$APP_PORT" --cert "$CERT" --key "$KEY"

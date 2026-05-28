#!/bin/sh
# ef-update.sh — Download and install TLS certificate from EF update server
#
# Called by the Node.js admin server when the TD taps "Update Device Now".
# Progress is written to stdout as tagged lines:
#   STEP:<message>   — starting a step (shows spinner in UI)
#   OK:<message>     — step succeeded
#   ERROR:<message>  — fatal error (script exits 1)
#   DONE:<message>   — all steps complete (server will restart service)
#
# Setup (one-time, done by IT over SSH):
#   echo '<token>' > /etc/ef-cert-token
#
# NOTE: The JSON field names (cert_url / key_url) must match the API response.
#       Run: curl -H "Authorization: Bearer $TOKEN" https://api.eftours.travel/router-cert
#       and check the field names if the parse step fails.

CERT_API="https://api.eftours.travel/router-cert"
TOKEN_FILE="/etc/ef-cert-token"
SSL_DIR="/root/tour-audio-server/ssl"
STATUS_FILE="/tmp/ef-status.json"

# ── Read bearer token ─────────────────────────────────────────────────────────
if [ ! -f "$TOKEN_FILE" ]; then
    echo "ERROR:No API token found at $TOKEN_FILE — contact EF IT to set this up"
    exit 1
fi
TOKEN=$(sed 's/[[:space:]]//g' "$TOKEN_FILE")

# ── Fetch pre-signed S3 URLs from API Gateway ─────────────────────────────────
echo "STEP:Fetching certificate download URLs from EF server"
RESPONSE_FILE=$(mktemp /tmp/ef-response.XXXXXX)
curl -sf --max-time 15 \
    -H "Authorization: Bearer $TOKEN" \
    -o "$RESPONSE_FILE" \
    "$CERT_API" || {
    rm -f "$RESPONSE_FILE"
    echo "ERROR:Could not reach update server — check internet connection"
    exit 1
}

# Parse JSON using sed (grep -o truncates on long lines in BusyBox)
CERT_URL=$(sed -n 's/.*"cert_url"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$RESPONSE_FILE")
KEY_URL=$(sed -n  's/.*"key_url"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p'  "$RESPONSE_FILE")
rm -f "$RESPONSE_FILE"

if [ -z "$CERT_URL" ] || [ -z "$KEY_URL" ]; then
    echo "ERROR:Unexpected API response — could not parse cert_url / key_url fields"
    exit 1
fi
echo "OK:Got pre-signed download URLs"

# ── Download files into a temp directory ─────────────────────────────────────
TMPDIR=$(mktemp -d /tmp/ef-cert.XXXXXX)

echo "STEP:Downloading certificate chain"
curl -sf --max-time 30 -o "$TMPDIR/server.crt" "$CERT_URL" || {
    echo "ERROR:Certificate download failed — pre-signed URL may have expired"
    rm -rf "$TMPDIR"; exit 1
}
echo "OK:Certificate downloaded"

echo "STEP:Downloading private key"
curl -sf --max-time 30 -o "$TMPDIR/server.key" "$KEY_URL" || {
    echo "ERROR:Private key download failed — pre-signed URL may have expired"
    rm -rf "$TMPDIR"; exit 1
}
echo "OK:Private key downloaded"

# ── Validate: cert is parseable and matches the key ──────────────────────────
echo "STEP:Validating certificate"
openssl x509 -noout -in "$TMPDIR/server.crt" 2>/dev/null || {
    echo "ERROR:Downloaded file is not a valid certificate"
    rm -rf "$TMPDIR"; exit 1
}

# Compare public keys — works for both RSA and EC certificates
CERT_PUB=$(openssl x509 -noout -pubkey -in "$TMPDIR/server.crt" 2>/dev/null | md5sum | cut -d' ' -f1)
# Try EC key first, fall back to RSA
KEY_PUB=$(openssl ec -pubout -in "$TMPDIR/server.key" 2>/dev/null | md5sum | cut -d' ' -f1)
if [ -z "$KEY_PUB" ]; then
    KEY_PUB=$(openssl rsa -pubout -in "$TMPDIR/server.key" 2>/dev/null | md5sum | cut -d' ' -f1)
fi
if [ -z "$CERT_PUB" ] || [ -z "$KEY_PUB" ] || [ "$CERT_PUB" != "$KEY_PUB" ]; then
    echo "ERROR:Certificate and private key do not match — aborting install"
    rm -rf "$TMPDIR"; exit 1
fi

EXPIRY_RAW=$(openssl x509 -noout -enddate -in "$TMPDIR/server.crt" | cut -d= -f2)
echo "OK:Certificate valid — expires $EXPIRY_RAW"

# ── Backup existing certs (timestamped) ──────────────────────────────────────
echo "STEP:Backing up current certificate"
mkdir -p "$SSL_DIR/backup"
STAMP=$(date +%Y%m%d-%H%M%S)
[ -f "$SSL_DIR/server.crt" ] && cp "$SSL_DIR/server.crt" "$SSL_DIR/backup/server.crt.$STAMP"
[ -f "$SSL_DIR/server.key" ] && cp "$SSL_DIR/server.key" "$SSL_DIR/backup/server.key.$STAMP"
echo "OK:Backup saved as server.crt.$STAMP"

# ── Install ───────────────────────────────────────────────────────────────────
echo "STEP:Installing new certificate"
cp "$TMPDIR/server.crt" "$SSL_DIR/server.crt"
cp "$TMPDIR/server.key" "$SSL_DIR/server.key"
chmod 600 "$SSL_DIR/server.key"
rm -rf "$TMPDIR"
echo "OK:Certificate installed"

# ── Update /tmp/ef-status.json ────────────────────────────────────────────────
echo "STEP:Updating device status"

# Compute days remaining — BusyBox date uses -D for input format, -d for value
EXPIRY_STRIPPED=$(echo "$EXPIRY_RAW" | sed 's/ GMT$//')
EXPIRY_EPOCH=$(date -D "%b %e %H:%M:%S %Y" -d "$EXPIRY_STRIPPED" +%s 2>/dev/null || echo "0")
NOW_EPOCH=$(date +%s)
if [ "$EXPIRY_EPOCH" -gt 0 ]; then
    DAYS_LEFT=$(( (EXPIRY_EPOCH - NOW_EPOCH) / 86400 ))
    EXPIRY_ISO=$(date -D "%b %e %H:%M:%S %Y" -d "$EXPIRY_STRIPPED" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || echo "")
else
    # Fallback if date parsing fails — cert is valid, expiry unknown
    DAYS_LEFT=90
    EXPIRY_ISO=""
fi

NOW_ISO=$(date +%Y-%m-%dT%H:%M:%SZ)
cat > "$STATUS_FILE" << EOF
{
  "certificate": {
    "valid": true,
    "expiresAt": "$EXPIRY_ISO",
    "daysRemaining": $DAYS_LEFT
  },
  "lastUpdate": {
    "timestamp": "$NOW_ISO",
    "outcome": "success",
    "stepsCompleted": 7
  }
}
EOF
echo "OK:Status updated — $DAYS_LEFT days until expiry"

# ── Signal completion — Node.js server will handle the service restart ────────
echo "DONE:Certificate updated successfully. $DAYS_LEFT days until expiry."

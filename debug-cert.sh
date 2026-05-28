#!/bin/sh
# debug-cert.sh — Check what format the downloaded cert/key files are in

TOKEN=$(sed 's/[[:space:]]//g' /etc/ef-cert-token)

echo "=== Fetching pre-signed URLs ==="
curl -sf -H "Authorization: Bearer $TOKEN" \
  https://api.eftours.travel/router-cert -o /tmp/r.json || {
  echo "ERROR: curl failed"
  exit 1
}

CERT_URL=$(sed -n 's/.*"cert_url"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' /tmp/r.json)
KEY_URL=$(sed -n  's/.*"key_url"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p'  /tmp/r.json)

echo "CERT_URL length: $(echo "$CERT_URL" | wc -c)"
echo "KEY_URL length:  $(echo "$KEY_URL"  | wc -c)"

echo ""
echo "=== Downloading files ==="
curl -sf -o /tmp/t.crt "$CERT_URL" && echo "cert downloaded OK" || echo "cert download FAILED"
curl -sf -o /tmp/t.key "$KEY_URL"  && echo "key downloaded OK"  || echo "key download FAILED"

echo ""
echo "=== File headers (BEGIN line) ==="
echo "cert: $(head -1 /tmp/t.crt)"
echo "key:  $(head -1 /tmp/t.key)"

echo ""
echo "=== Cert modulus (first 60 chars) ==="
openssl x509 -noout -modulus -in /tmp/t.crt 2>&1 | cut -c1-60

echo ""
echo "=== Key modulus via 'openssl rsa' (first 60 chars) ==="
openssl rsa -noout -modulus -in /tmp/t.key 2>&1 | cut -c1-60

echo ""
echo "=== Key modulus via 'openssl pkey' (first 60 chars) ==="
openssl pkey -noout -modulus -in /tmp/t.key 2>&1 | cut -c1-60

echo ""
echo "=== MD5 comparison ==="
CERT_MOD=$(openssl x509 -noout -modulus -in /tmp/t.crt 2>/dev/null | md5sum | cut -d' ' -f1)
KEY_MOD_RSA=$(openssl rsa  -noout -modulus -in /tmp/t.key 2>/dev/null | md5sum | cut -d' ' -f1)
KEY_MOD_PKY=$(openssl pkey -noout -modulus -in /tmp/t.key 2>/dev/null | md5sum | cut -d' ' -f1)
echo "cert modulus md5:       $CERT_MOD"
echo "key modulus (rsa) md5:  $KEY_MOD_RSA"
echo "key modulus (pkey) md5: $KEY_MOD_PKY"

rm -f /tmp/r.json /tmp/t.crt /tmp/t.key

#!/bin/bash

# Create SSL directory
mkdir -p ssl

# Generate self-signed certificate.
# Includes a subjectAltName (SAN) covering both the domain and the IP —
# without it, Android/Chrome reject the cert outright (ERR_CERT_COMMON_NAME_INVALID)
# since CN-based hostname matching was removed years ago (RFC 2818 deprecated it;
# Chrome ~58+ and Android 7+ no longer fall back to CN). iOS/Safari is more
# lenient here, especially once a cert has been manually trusted, which is why
# this only showed up as a warning on Android.
openssl req -x509 -newkey rsa:4096 -keyout ssl/server.key -out ssl/server.crt -days 365 -nodes \
  -subj "/C=US/ST=State/L=City/O=EF Tours/CN=audio.eftours.travel" \
  -addext "subjectAltName=DNS:audio.eftours.travel,DNS:192.168.8.1,IP:192.168.8.1"

echo "SSL certificates generated in ./ssl/"
echo "server.key and server.crt are ready to use"


#!/bin/bash

# Create SSL directory
mkdir -p ssl

# Generate self-signed certificate
openssl req -x509 -newkey rsa:4096 -keyout ssl/server.key -out ssl/server.crt -days 365 -nodes -subj "/C=US/ST=State/L=City/O=EF Tours/CN=192.168.8.1"

echo "SSL certificates generated in ./ssl/"
echo "server.key and server.crt are ready to use"


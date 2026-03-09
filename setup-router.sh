#!/bin/bash
# Complete setup script for tour-audio-server on GL.iNet router
# Run this script on the router after copying files

set -e  # Exit on error

echo "=========================================="
echo "Tour Audio Server - Complete Setup"
echo "=========================================="
echo ""

# Step 1: Create SSL directory
echo "[1/6] Creating SSL directory..."
cd /root/tour-audio-server
mkdir -p ssl

# Step 2: Install OpenSSL if needed
echo "[2/6] Installing OpenSSL..."
opkg update
opkg install openssl-util

# Step 3: Generate SSL certificates with ef.tour domain
echo "[3/6] Generating SSL certificates..."
if [ -f ssl/server.key ]; then
    echo "Backing up existing certificates..."
    mv ssl/server.key ssl/server.key.bak
    mv ssl/server.crt ssl/server.crt.bak
fi

openssl req -x509 -newkey rsa:2048 \
  -keyout ssl/server.key \
  -out ssl/server.crt \
  -days 365 \
  -nodes \
  -subj "/C=US/ST=State/L=City/O=EF Tours/CN=ef.tour" \
  -addext "subjectAltName=DNS:ef.tour,DNS:192.168.8.1,IP:192.168.8.1"

echo "SSL certificates generated:"
ls -lh ssl/server.key ssl/server.crt

# Step 4: Install Node.js dependencies
echo "[4/6] Installing Node.js dependencies..."
npm install --production

# Step 5: Configure DNS for ef.tour domain
echo "[5/6] Configuring DNS for ef.tour domain..."
# Check if entry already exists
if ! uci show dhcp | grep -q "ef.tour"; then
    uci add_list dhcp.@dnsmasq[0].address='/ef.tour/192.168.8.1'
    uci commit dhcp
    /etc/init.d/dnsmasq restart
    echo "DNS entry added for ef.tour"
else
    echo "DNS entry for ef.tour already exists"
fi

# Step 6: Configure port forwarding (443 → 3000)
echo "[6/6] Configuring port forwarding..."
# Add to firewall.user if not already there
if ! grep -q "REDIRECT --to-port 3000" /etc/firewall.user 2>/dev/null; then
    cat >> /etc/firewall.user << 'EOF'
# Port forwarding for tour-audio-server (HTTPS 443 → 3000)
iptables -t nat -A PREROUTING -p tcp --dport 443 -j REDIRECT --to-port 3000
EOF
    echo "Port forwarding rule added to /etc/firewall.user"
else
    echo "Port forwarding rule already exists"
fi

# Apply port forwarding immediately
iptables -t nat -C PREROUTING -p tcp --dport 443 -j REDIRECT --to-port 3000 2>/dev/null || \
    iptables -t nat -A PREROUTING -p tcp --dport 443 -j REDIRECT --to-port 3000

echo "Port forwarding configured (443 → 3000)"

# Restart the server
echo ""
echo "=========================================="
echo "Setup Complete! Restarting server..."
echo "=========================================="
/etc/init.d/tour-audio restart

sleep 2

# Verify server is running
if ps | grep -v grep | grep -q "node.*server.js"; then
    echo ""
    echo "✓ Server is running!"
    echo ""
    echo "Access the tour audio system at:"
    echo "  Guide:   https://ef.tour/guide.html"
    echo "  Tourist: https://ef.tour/tourist.html"
    echo "  Health:  https://ef.tour/health"
    echo ""
    echo "Or using IP address:"
    echo "  Guide:   https://192.168.8.1:3000/guide.html"
    echo "  Tourist: https://192.168.8.1:3000/tourist.html"
    echo "  Health:  https://192.168.8.1:3000/health"
    echo ""
    echo "Note: You'll see a security warning about the"
    echo "self-signed certificate - this is normal."
    echo "Click 'Advanced' → 'Proceed' to continue."
else
    echo ""
    echo "✗ Server failed to start. Check logs:"
    echo "  logread | grep tour-audio"
    echo ""
    echo "Or try running manually:"
    echo "  cd /root/tour-audio-server"
    echo "  node server.js"
fi

echo ""
echo "=========================================="


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

# Step 3: Generate SSL certificates with audio.eftours.travel domain
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
  -subj "/C=US/ST=State/L=City/O=EF Tours/CN=audio.eftours.travel" \
  -addext "subjectAltName=DNS:audio.eftours.travel,DNS:192.168.8.1,IP:192.168.8.1"

echo "SSL certificates generated:"
ls -lh ssl/server.key ssl/server.crt

# Step 4: Install Node.js dependencies
echo "[4/6] Installing Node.js dependencies..."
npm install --production

# Step 5: Configure DNS for audio.eftours.travel domain
echo "[5/6] Configuring DNS for audio.eftours.travel domain..."
# Check if entry already exists
if ! uci show dhcp | grep -q "audio.eftours.travel"; then
    uci add_list dhcp.@dnsmasq[0].address='/audio.eftours.travel/192.168.8.1'
    uci commit dhcp
    /etc/init.d/dnsmasq restart
    echo "DNS entry added for audio.eftours.travel"
else
    echo "DNS entry for audio.eftours.travel already exists"
fi

# Step 6: Configure port forwarding (targeted to router IP only)
echo "[6/6] Configuring port forwarding..."
# Remove any old broad redirect rules first
sed -i '/REDIRECT --to-port[s]* 8888/d' /etc/firewall.user 2>/dev/null
sed -i '/REDIRECT --to-port[s]* 3000/d' /etc/firewall.user 2>/dev/null

# Add targeted rules (only redirect traffic destined for 192.168.8.1)
cat >> /etc/firewall.user << 'EOF'
# Port forwarding for tour-audio-server (targeted to router IP only)
iptables -t nat -A PREROUTING -i br-lan -d 192.168.8.1 -p tcp --dport 80  -j REDIRECT --to-ports 8888
iptables -t nat -A PREROUTING -i br-lan -d 192.168.8.1 -p tcp --dport 443 -j REDIRECT --to-ports 3000
EOF
echo "Port forwarding rules added to /etc/firewall.user"

# Apply immediately (remove old broad rules, add targeted ones)
while iptables -t nat -D PREROUTING -p tcp --dport 443 -j REDIRECT --to-port 3000 2>/dev/null; do true; done
while iptables -t nat -D PREROUTING -p tcp --dport 80  -j REDIRECT --to-port 8888 2>/dev/null; do true; done
iptables -t nat -A PREROUTING -i br-lan -d 192.168.8.1 -p tcp --dport 80  -j REDIRECT --to-ports 8888
iptables -t nat -A PREROUTING -i br-lan -d 192.168.8.1 -p tcp --dport 443 -j REDIRECT --to-ports 3000

echo "Port forwarding configured (80→8888, 443→3000, targeted to 192.168.8.1 only)"

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
    echo "  Guide:   https://audio.eftours.travel/guide.html"
    echo "  Tourist: https://audio.eftours.travel/"
    echo "  Health:  https://audio.eftours.travel/health"
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


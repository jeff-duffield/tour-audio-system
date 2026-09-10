#!/bin/bash
# Bootstrap script for tour-audio-server on a freshly flashed GL.iNet router.
#
# Usage: setup-router.sh <router-number>
#   router-number: this unit's assigned number in the fleet (see README >
#   Router Provisioning at Scale, and the Fleet Registry in secrets.txt for
#   which numbers are already taken). Determines the unique WiFi SSIDs this
#   router broadcasts, so multiple routers used near each other never
#   collide on the same network name.
#
# Run this AFTER Steps 1-3 of the Setup Guide in README.md (initial WiFi
# config, Node.js install, and `scp -r tour-audio-server` to /root/).
#
# What this script does NOT do (see README > Router Provisioning at Scale):
#   - Install the real Let's Encrypt certificate. That happens afterward via
#     the Audio System Admin portal's "Update Device Now" (which runs
#     ef-update.sh). This script only bootstraps a TEMPORARY self-signed
#     cert so HTTPS has something to serve until then.
#   - Place /etc/ef-cert-token - a one-time, IT-managed step (same token
#     works across the whole fleet).
#   - Set the WiFi password - shared across the fleet, set once manually in
#     Setup Guide Step 1 (see secrets.txt). Only the SSIDs are per-router.
#   - Enable NTP auto-sync - a one-time, separate manual step (Setup Guide
#     Step 8). This script sets the timezone but not the NTP enable flag.
#   - DHCP / WiFi optimizations - run separately since they're independent,
#     tour-size-dependent tunables (optimize-dhcp.sh / optimize-wifi.sh).

set -e  # Exit on error

ROUTER_NUM="$1"
if [ -z "$ROUTER_NUM" ]; then
    echo "Usage: $0 <router-number>"
    echo "  e.g. $0 1   -> EF-TOUR-001 / EF-TOUR-GUIDE       (legacy, router 1 only)"
    echo "       $0 2   -> EF-TOUR-002 / EF-TOUR-GUIDE-002"
    echo "Check secrets.txt > Fleet Registry for the next available number."
    exit 1
fi
ROUTER_PAD=$(printf "%03d" "$ROUTER_NUM")
TOURIST_SSID="EF-TOUR-${ROUTER_PAD}"
if [ "$ROUTER_NUM" = "1" ]; then
    GUIDE_SSID="EF-TOUR-GUIDE"   # legacy unnumbered name - router 1 only
else
    GUIDE_SSID="EF-TOUR-GUIDE-${ROUTER_PAD}"
fi

echo "=========================================="
echo "Tour Audio Server - Router Bootstrap (Router #${ROUTER_NUM})"
echo "=========================================="
echo ""

cd /root/tour-audio-server

# Step 1: Set unique WiFi network names (SSIDs) for this router number
echo "[1/7] Setting WiFi network names..."
RADIO_2G=$(uci show wireless | grep '2g' | grep "radio" | cut -d'.' -f2 | cut -d'=' -f1 | head -n1)
RADIO_5G=$(uci show wireless | grep '5g' | grep "radio" | cut -d'.' -f2 | cut -d'=' -f1 | head -n1)
IFACE_2G=$(uci show wireless | grep "device='${RADIO_2G}'" | grep "wifi-iface" | cut -d'.' -f2 | cut -d'=' -f1 | head -n1)
IFACE_5G=$(uci show wireless | grep "device='${RADIO_5G}'" | grep "wifi-iface" | cut -d'.' -f2 | cut -d'=' -f1 | head -n1)

if [ -n "$IFACE_2G" ]; then
    uci set wireless.${IFACE_2G}.ssid="$TOURIST_SSID"
    echo "2.4GHz (tourist) SSID -> $TOURIST_SSID"
else
    echo "WARNING: could not find the 2.4GHz wifi-iface - set the tourist SSID manually."
fi
if [ -n "$IFACE_5G" ]; then
    uci set wireless.${IFACE_5G}.ssid="$GUIDE_SSID"
    echo "5GHz (guide) SSID -> $GUIDE_SSID"
else
    echo "WARNING: could not find the 5GHz wifi-iface - set the guide SSID manually."
fi
uci commit wireless
wifi reload
echo "WiFi password is unchanged (shared fleet password from Setup Guide Step 1 / secrets.txt)."

# Step 2: Bootstrap a temporary self-signed SSL certificate
echo "[2/7] Bootstrapping temporary self-signed SSL certificate..."
mkdir -p ssl
if [ -f ssl/server.crt ] && [ -f ssl/server.key ]; then
    echo "ssl/server.crt already present (real cert installed, or already"
    echo "bootstrapped) - leaving it alone. Delete it manually first if you"
    echo "need to force-regenerate the temporary cert."
else
    opkg update
    opkg install openssl-util
    openssl req -x509 -newkey rsa:2048 \
      -keyout ssl/server.key \
      -out ssl/server.crt \
      -days 365 \
      -nodes \
      -subj "/C=US/ST=State/L=City/O=EF Tours/CN=audio.eftours.travel" \
      -addext "subjectAltName=DNS:audio.eftours.travel,IP:192.168.8.1"
    echo "Temporary self-signed certificate created. Replace it via the"
    echo "Admin portal's 'Update Device Now' once /etc/ef-cert-token is in place."
fi

# Step 3: Install Node.js dependencies
echo "[3/7] Installing Node.js dependencies..."
npm install --production

# Step 4: Set up auto-start service
echo "[4/7] Setting up auto-start service..."
if [ ! -f /etc/init.d/tour-audio ]; then
    cat > /etc/init.d/tour-audio << 'EOF'
#!/bin/sh /etc/rc.common

START=99
STOP=10

USE_PROCD=1

start_service() {
    procd_open_instance
    procd_set_param command /usr/bin/node /root/tour-audio-server/server.js
    procd_set_param respawn
    procd_set_param stdout 1
    procd_set_param stderr 1
    procd_close_instance
}
EOF
    chmod +x /etc/init.d/tour-audio
    /etc/init.d/tour-audio enable
    echo "Auto-start service installed and enabled."
else
    echo "Auto-start service already exists - skipping."
fi

# Step 5: Configure DNS (audio.eftours.travel + captive portal probe domains)
echo "[5/7] Configuring DNS..."
if ! uci show dhcp | grep -q "audio.eftours.travel"; then
    uci add_list dhcp.@dnsmasq[0].address='/audio.eftours.travel/192.168.8.1'
    echo "DNS entry added for audio.eftours.travel"
else
    echo "DNS entry for audio.eftours.travel already exists"
fi

# These make iOS/Android/Windows connectivity probes hit our server so the
# captive portal UI triggers reliably (see README Step 6 for details). Do
# NOT add a wildcard entry here - that would break general internet access.
for host in captive.apple.com www.appleiphonecell.com www.apple.com \
            connectivitycheck.gstatic.com clients3.google.com www.msftconnecttest.com; do
    if ! uci show dhcp | grep -q "$host"; then
        uci add_list dhcp.@dnsmasq[0].address="/$host/192.168.8.1"
    fi
done

# RFC 8910 Captive-Portal DHCP option (114) - lets Android trigger the
# captive portal UI directly, independent of the DNS probe intercepts above
if ! uci show dhcp | grep -q "114,https://audio.eftours.travel/captive"; then
    uci add_list dhcp.@dnsmasq[0].dhcp_option='114,https://audio.eftours.travel/captive'
    echo "DHCP option 114 (Captive-Portal URL) added"
else
    echo "DHCP option 114 already configured"
fi

uci commit dhcp
/etc/init.d/dnsmasq restart
echo "Captive portal probe domains configured"

# Step 6: Set timezone (so logs/timestamps read correctly; NTP itself still
# needs to be enabled separately - see README Setup Guide Step 8)
echo "[6/7] Setting timezone..."
uci set system.@system[0].timezone='EST5EDT,M3.2.0,M11.1.0'
uci set system.@system[0].zonename='America/New York'
uci commit system
echo "Timezone set to America/New York"

# Step 7: Configure port forwarding (targeted to router IP only)
echo "[7/7] Configuring port forwarding..."
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

# Apply immediately (remove old rules first to avoid duplicates on re-run)
while iptables -t nat -D PREROUTING -i br-lan -d 192.168.8.1 -p tcp --dport 443 -j REDIRECT --to-ports 3000 2>/dev/null; do true; done
while iptables -t nat -D PREROUTING -i br-lan -d 192.168.8.1 -p tcp --dport 80  -j REDIRECT --to-ports 8888 2>/dev/null; do true; done
iptables -t nat -A PREROUTING -i br-lan -d 192.168.8.1 -p tcp --dport 80  -j REDIRECT --to-ports 8888
iptables -t nat -A PREROUTING -i br-lan -d 192.168.8.1 -p tcp --dport 443 -j REDIRECT --to-ports 3000

echo "Port forwarding configured (80→8888, 443→3000, targeted to 192.168.8.1 only)"

# Restart the server
echo ""
echo "=========================================="
echo "Bootstrap complete! Restarting server..."
echo "=========================================="
/etc/init.d/tour-audio restart

sleep 2

# Verify server is running
if ps | grep -v grep | grep -q "node.*server.js"; then
    echo ""
    echo "✓ Server is running!"
    echo ""
    echo "Router #${ROUTER_NUM} WiFi networks:"
    echo "  Tourist (2.4GHz): $TOURIST_SSID"
    echo "  Guide   (5GHz):   $GUIDE_SSID"
    echo ""
    echo "Remaining manual steps (see README > Router Provisioning at Scale):"
    echo "  1. Add Router #${ROUTER_NUM} + its SSIDs to the Fleet Registry in secrets.txt"
    echo "     so the next person provisioning a router doesn't reuse this number."
    echo "  2. echo 'TOKEN' > /etc/ef-cert-token   (IT-managed, one-time)"
    echo "  3. Open the Admin portal and tap 'Update Device Now' to install"
    echo "     the real Let's Encrypt certificate"
    echo "  4. Enable NTP auto-sync (Setup Guide Step 8)"
    echo "  5. Run optimize-dhcp.sh / optimize-wifi.sh if this tour size needs them"
    echo ""
    echo "Until the real certificate is installed, expect a one-time browser"
    echo "security warning (temporary self-signed cert) at:"
    echo "  Guide:   https://192.168.8.1:3000/guide.html"
    echo "  Listener: https://192.168.8.1:3000/"
    echo "  Health:  https://192.168.8.1:3000/health"
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

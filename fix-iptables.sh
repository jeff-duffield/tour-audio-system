#!/bin/sh
# fix-iptables.sh
# Fixes port forwarding rules so ONLY traffic for 192.168.8.1 (audio.eftours.travel)
# is redirected to our Node.js server. All other traffic passes through normally.

echo "=== Current NAT PREROUTING rules ==="
iptables -t nat -L PREROUTING -n -v --line-numbers

echo ""
echo "=== Removing all broad redirect rules (no -d flag) ==="
# Delete in a loop until none remain
while iptables -t nat -D PREROUTING -i br-lan -p tcp --dport 80 -j REDIRECT --to-ports 8888 2>/dev/null; do
  echo "  removed broad port 80 rule"
done
while iptables -t nat -D PREROUTING -i br-lan -p tcp --dport 443 -j REDIRECT --to-ports 3000 2>/dev/null; do
  echo "  removed broad port 443 rule"
done
# Also remove rules without -i br-lan (setup-router.sh didn't include it)
while iptables -t nat -D PREROUTING -p tcp --dport 80 -j REDIRECT --to-port 8888 2>/dev/null; do
  echo "  removed broad port 80 rule (no iface)"
done
while iptables -t nat -D PREROUTING -p tcp --dport 443 -j REDIRECT --to-port 3000 2>/dev/null; do
  echo "  removed broad port 443 rule (no iface)"
done
# Same with --to-ports (plural) variant
while iptables -t nat -D PREROUTING -p tcp --dport 443 -j REDIRECT --to-ports 3000 2>/dev/null; do
  echo "  removed broad port 443 rule (to-ports)"
done
while iptables -t nat -D PREROUTING -p tcp --dport 80 -j REDIRECT --to-ports 8888 2>/dev/null; do
  echo "  removed broad port 80 rule (to-ports)"
done

echo ""
echo "=== Adding targeted rules (only for 192.168.8.1) ==="
iptables -t nat -A PREROUTING -i br-lan -d 192.168.8.1 -p tcp --dport 80  -j REDIRECT --to-ports 8888
echo "  added: br-lan -d 192.168.8.1 port 80 -> 8888"
iptables -t nat -A PREROUTING -i br-lan -d 192.168.8.1 -p tcp --dport 443 -j REDIRECT --to-ports 3000
echo "  added: br-lan -d 192.168.8.1 port 443 -> 3000"

echo ""
echo "=== Updating /etc/firewall.user for persistence ==="
# Remove old broad rules from firewall.user
sed -i '/REDIRECT --to-port[s]* 8888/d' /etc/firewall.user
sed -i '/REDIRECT --to-port[s]* 3000/d' /etc/firewall.user

# Add targeted rules
cat >> /etc/firewall.user << 'EOF'
# Port forwarding for tour-audio-server (targeted to router IP only)
iptables -t nat -A PREROUTING -i br-lan -d 192.168.8.1 -p tcp --dport 80  -j REDIRECT --to-ports 8888
iptables -t nat -A PREROUTING -i br-lan -d 192.168.8.1 -p tcp --dport 443 -j REDIRECT --to-ports 3000
EOF
echo "  /etc/firewall.user updated"

echo ""
echo "=== Final NAT PREROUTING rules ==="
iptables -t nat -L PREROUTING -n -v

echo ""
echo "=== Test: can we reach google.com? ==="
curl -s --max-time 5 http://google.com -o /dev/null -w "HTTP %{http_code}\n" && echo "PASS" || echo "FAIL"

echo ""
echo "=== Done. Reconnect your device to tour WiFi and try espn.com ==="

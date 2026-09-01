#!/bin/sh
# diagnose-relay.sh — checks why audio.eftours.travel may not resolve
# in Safari once Private Relay resumes. Run on the router:
#   ssh root@192.168.8.1
#   sh /tmp/diagnose-relay.sh

echo "=== 1. Local DNS record for audio.eftours.travel ==="
echo "  (should return 192.168.8.1)"
nslookup audio.eftours.travel 127.0.0.1

echo ""
echo "=== 2. dnsmasq.conf entries ==="
cat /etc/dnsmasq.conf 2>/dev/null

echo ""
echo "=== 3. UCI dhcp config (dnsmasq section) ==="
uci show dhcp | grep -E "dnsmasq|address|dhcp_option|option6|list dhcp_option"

echo ""
echo "=== 4. Private Relay health-check domain blocks ==="
echo "  mask.icloud.com / mask-h2.icloud.com should NOT resolve to a real IP"
nslookup mask.icloud.com 127.0.0.1
nslookup mask-h2.icloud.com 127.0.0.1

echo ""
echo "=== 5. DHCP Option 114 (RFC 8910 captive portal URL) ==="
uci show dhcp | grep -i "114\|option114"

echo ""
echo "=== 6. iptables NAT rules (port forwarding to server) ==="
iptables -t nat -L PREROUTING -n --line-numbers | grep -E "80|443|8888|3000"

echo ""
echo "=== 7. dnsmasq running? ==="
ps | grep dnsmasq | grep -v grep

echo ""
echo "=== 8. Recent captive-probe hits from server log ==="
logread 2>/dev/null | grep captive-probe | tail -20

echo ""
echo "=== Done ==="

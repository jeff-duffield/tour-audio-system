#!/bin/sh
# fix-dns.sh — Remove the wildcard DNS entry that blocks internet access
# Run on the router: sh /tmp/fix-dns.sh

echo "=== Current dnsmasq.conf ==="
cat /etc/dnsmasq.conf

echo ""
echo "=== Removing wildcard address=/#/ from /etc/dnsmasq.conf ==="
sed -i '/address=\/#\//d' /etc/dnsmasq.conf

echo "=== Removing detectportal.firefox.com (needed for captive portal check) ==="
sed -i '/detectportal\.firefox\.com/d' /etc/dnsmasq.conf

echo "=== Removing clients3.google.com and play.googleapis.com (not needed) ==="
sed -i '/clients3\.google\.com/d' /etc/dnsmasq.conf
sed -i '/play\.googleapis\.com/d' /etc/dnsmasq.conf

echo "=== Removing www.apple.com (too broad, breaks Apple services) ==="
sed -i '/www\.apple\.com/d' /etc/dnsmasq.conf

echo "=== Adding connectivitycheck.android.com if missing ==="
grep -q 'connectivitycheck.android.com' /etc/dnsmasq.conf || \
  echo 'address=/connectivitycheck.android.com/192.168.8.1' >> /etc/dnsmasq.conf

echo "=== Adding audio.eftours.travel if missing ==="
grep -q 'audio.eftours.travel' /etc/dnsmasq.conf || \
  echo 'address=/audio.eftours.travel/192.168.8.1' >> /etc/dnsmasq.conf

echo ""
echo "=== Updated dnsmasq.conf ==="
cat /etc/dnsmasq.conf

echo ""
echo "=== Restarting dnsmasq ==="
/etc/init.d/dnsmasq restart
sleep 2

echo ""
echo "=== DNS verification ==="
echo "api.eftours.travel (should be a real IP, NOT 192.168.8.1):"
nslookup api.eftours.travel

echo ""
echo "audio.eftours.travel (should be 192.168.8.1):"
nslookup audio.eftours.travel

echo ""
echo "google.com (should be a real IP):"
nslookup google.com

echo "=== Done ==="

#!/bin/sh
# fix-captive-portal.sh
# Removes ALL captive portal DNS interceptions.
# Keeps ONLY audio.eftours.travel pointing to the router.
#
# Why: Intercepting connectivitycheck.gstatic.com/captive.apple.com causes
# Chrome and iOS to enter "captive portal mode" which blocks all HTTPS traffic.
# Tourists navigate to audio.eftours.travel directly instead.

echo "=== Removing captive portal entries from /etc/dnsmasq.conf ==="
sed -i '/captive\.apple\.com/d'         /etc/dnsmasq.conf
sed -i '/connectivitycheck/d'           /etc/dnsmasq.conf
sed -i '/msftconnecttest/d'             /etc/dnsmasq.conf
sed -i '/msftncsi/d'                    /etc/dnsmasq.conf
sed -i '/clients3\.google\.com/d'       /etc/dnsmasq.conf
sed -i '/play\.googleapis\.com/d'       /etc/dnsmasq.conf
sed -i '/www\.apple\.com/d'             /etc/dnsmasq.conf
sed -i '/detectportal/d'                /etc/dnsmasq.conf
sed -i '/address=\/#\//d'               /etc/dnsmasq.conf

echo "=== Removing captive portal entries from UCI ==="
uci -q del_list dhcp.@dnsmasq[0].address='/captive.apple.com/192.168.8.1'
uci -q del_list dhcp.@dnsmasq[0].address='/www.apple.com/192.168.8.1'
uci -q del_list dhcp.@dnsmasq[0].address='/connectivitycheck.gstatic.com/192.168.8.1'
uci -q del_list dhcp.@dnsmasq[0].address='/connectivitycheck.android.com/192.168.8.1'
uci -q del_list dhcp.@dnsmasq[0].address='/www.msftconnecttest.com/192.168.8.1'
uci -q del_list dhcp.@dnsmasq[0].address='/detectportal.firefox.com/192.168.8.1'
uci -q del_list dhcp.@dnsmasq[0].address='/clients3.google.com/192.168.8.1'
uci -q del_list dhcp.@dnsmasq[0].address='/play.googleapis.com/192.168.8.1'

echo "=== Ensuring audio.eftours.travel is present in both ==="
grep -q 'audio.eftours.travel' /etc/dnsmasq.conf || \
  echo 'address=/audio.eftours.travel/192.168.8.1' >> /etc/dnsmasq.conf

uci show dhcp | grep -q 'audio.eftours.travel' || \
  uci add_list dhcp.@dnsmasq[0].address='/audio.eftours.travel/192.168.8.1'

uci commit dhcp
/etc/init.d/dnsmasq restart
sleep 2

echo ""
echo "=== RESULT: UCI address entries (should only be audio.eftours.travel) ==="
uci show dhcp | grep address

echo ""
echo "=== RESULT: /etc/dnsmasq.conf address entries ==="
grep 'address=' /etc/dnsmasq.conf

echo ""
echo "=== DNS check: connectivitycheck.gstatic.com ==="
echo "  (should resolve to a REAL Google IP, NOT 192.168.8.1)"
nslookup connectivitycheck.gstatic.com

echo ""
echo "=== DNS check: audio.eftours.travel ==="
echo "  (should resolve to 192.168.8.1)"
nslookup audio.eftours.travel

echo ""
echo "=== Done. Reconnect your device to the tour WiFi and test. ==="

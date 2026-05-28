#!/bin/sh
# fix-connectivity.sh
# 1. Removes the dnsmasq UCI entries that make Chrome think there's a captive portal
# 2. Diagnoses why curl can't reach api.eftours.travel

echo "========================================"
echo " STEP 1: Fix Chrome captive portal block"
echo "========================================"
echo ""
echo "--- UCI dnsmasq addresses BEFORE fix ---"
uci show dhcp | grep address

echo ""
echo "Removing connectivitycheck.gstatic.com (causes Chrome to block all traffic)..."
uci -q del_list dhcp.@dnsmasq[0].address='/connectivitycheck.gstatic.com/192.168.8.1' && \
  echo "  removed" || echo "  not found in UCI (already clean)"

echo "Removing connectivitycheck.android.com..."
uci -q del_list dhcp.@dnsmasq[0].address='/connectivitycheck.android.com/192.168.8.1' && \
  echo "  removed" || echo "  not found in UCI"

echo "Removing www.apple.com (too broad, breaks Apple services)..."
uci -q del_list dhcp.@dnsmasq[0].address='/www.apple.com/192.168.8.1' && \
  echo "  removed" || echo "  not found in UCI"

echo "Removing clients3.google.com and play.googleapis.com..."
uci -q del_list dhcp.@dnsmasq[0].address='/clients3.google.com/192.168.8.1' 2>/dev/null
uci -q del_list dhcp.@dnsmasq[0].address='/play.googleapis.com/192.168.8.1' 2>/dev/null

uci commit dhcp
/etc/init.d/dnsmasq restart
sleep 2

echo ""
echo "--- UCI dnsmasq addresses AFTER fix ---"
uci show dhcp | grep address

echo ""
echo "--- /etc/dnsmasq.conf ---"
cat /etc/dnsmasq.conf

echo ""
echo "========================================"
echo " STEP 2: Diagnose router internet access"
echo "========================================"
echo ""

echo "--- Repeater/uplink status ---"
ubus call repeater status 2>/dev/null || echo "(repeater not connected)"

echo ""
echo "--- Network interfaces ---"
ip addr show apcli0 2>/dev/null || echo "apcli0 not found"

echo ""
echo "--- Default route ---"
ip route | grep default || echo "NO DEFAULT ROUTE - router has no uplink"

echo ""
echo "--- Ping 8.8.8.8 (bypasses DNS, tests raw routing) ---"
ping -c 2 -W 3 8.8.8.8 && echo "ROUTING OK" || echo "ROUTING FAILED - no internet path"

echo ""
echo "--- DNS: api.eftours.travel ---"
nslookup api.eftours.travel

echo ""
echo "--- curl verbose to api.eftours.travel ---"
curl -v --max-time 10 \
  https://api.eftours.travel/router-cert \
  -o /dev/null 2>&1 | grep -E "Trying|Connected|SSL|error|curl|HTTP|Failed|resolve"

echo ""
echo "========================================"
echo " Done"
echo "========================================"

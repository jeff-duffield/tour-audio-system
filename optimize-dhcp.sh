#!/bin/sh

# Optimize DHCP and DNS for connection storms
# Run this on the GL.iNet router to improve connection handling

echo "Optimizing router for connection storms..."

# Backup current config
cp /etc/config/dhcp /etc/config/dhcp.backup
echo "✅ Backed up /etc/config/dhcp"

# Increase DHCP lease time to reduce churn
uci set dhcp.lan.leasetime='2h'

# Increase DHCP limit
uci set dhcp.lan.limit='100'

# Enable DNS caching
uci set dhcp.@dnsmasq[0].cachesize='1000'

# Reduce DHCP timeout (faster retries)
uci set dhcp.@dnsmasq[0].dhcp_rapid_commit='1'

# Increase max DHCP leases
uci set dhcp.@dnsmasq[0].dhcp_max='100'

# Commit changes
uci commit dhcp

echo "✅ DHCP optimizations applied"

# Restart dnsmasq to apply changes
/etc/init.d/dnsmasq restart

echo "✅ DNS/DHCP service restarted"
echo ""
echo "Optimizations complete! Router can now handle connection storms better."
echo ""
echo "Changes made:"
echo "  - DHCP lease time: 2 hours (reduces renewal traffic)"
echo "  - DHCP limit: 100 clients"
echo "  - DNS cache: 1000 entries"
echo "  - Rapid commit: Enabled (faster DHCP)"
echo ""
echo "To revert: cp /etc/config/dhcp.backup /etc/config/dhcp && /etc/init.d/dnsmasq restart"


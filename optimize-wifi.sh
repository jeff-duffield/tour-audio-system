#!/bin/sh

# Optimize WiFi for high client count and connection storms
# Run this on the GL.iNet router

echo "Optimizing WiFi for 40+ concurrent clients..."

# Backup current config
cp /etc/config/wireless /etc/config/wireless.backup
echo "✅ Backed up /etc/config/wireless"

# Get the radio device names
RADIO_2G=$(uci show wireless | grep '2g' | grep "radio" | cut -d'.' -f2 | cut -d'=' -f1 | head -n1)
RADIO_5G=$(uci show wireless | grep '5g' | grep "radio" | cut -d'.' -f2 | cut -d'=' -f1 | head -n1)

echo "Found radios: 2.4GHz=$RADIO_2G, 5GHz=$RADIO_5G"

# Optimize 2.4GHz radio (if exists)
if [ -n "$RADIO_2G" ]; then
    echo "Optimizing 2.4GHz radio..."
    
    # Use 20MHz channel width for better compatibility
    uci set wireless.${RADIO_2G}.htmode='HT20'
    
    # Set to channel 6 (less interference)
    uci set wireless.${RADIO_2G}.channel='6'
    
    # Increase transmit power
    uci set wireless.${RADIO_2G}.txpower='20'
fi

# Optimize 5GHz radio (if exists) - RECOMMENDED for 40+ clients
if [ -n "$RADIO_5G" ]; then
    echo "Optimizing 5GHz radio..."
    
    # Use 80MHz channel width for better throughput
    uci set wireless.${RADIO_5G}.htmode='VHT80'
    
    # Use DFS channel for less interference
    uci set wireless.${RADIO_5G}.channel='auto'
    
    # Increase transmit power
    uci set wireless.${RADIO_5G}.txpower='23'
fi

# Optimize WiFi interface settings
# Find the WiFi interface
WIFI_IFACE=$(uci show wireless | grep "device='${RADIO_5G}'" | grep "wifi-iface" | cut -d'.' -f2 | cut -d'=' -f1 | head -n1)

if [ -n "$WIFI_IFACE" ]; then
    echo "Optimizing WiFi interface: $WIFI_IFACE"
    
    # Increase maximum clients
    uci set wireless.${WIFI_IFACE}.maxassoc='60'
    
    # Disable isolation (allow client-to-client if needed)
    uci set wireless.${WIFI_IFACE}.isolate='0'
    
    # Enable WMM for better QoS
    uci set wireless.${WIFI_IFACE}.wmm='1'
    
    # Disable 802.11w (can cause connection issues)
    uci set wireless.${WIFI_IFACE}.ieee80211w='0'
fi

# Commit changes
uci commit wireless

echo "✅ WiFi optimizations applied"

# Restart WiFi to apply changes
wifi reload

echo "✅ WiFi restarted"
echo ""
echo "Optimizations complete!"
echo ""
echo "Changes made:"
echo "  - 2.4GHz: Channel 6, HT20 mode"
echo "  - 5GHz: Auto channel, VHT80 mode (RECOMMENDED)"
echo "  - Max associations: 60 clients"
echo "  - WMM enabled for better audio QoS"
echo ""
echo "⭐ RECOMMENDATION: Use 5GHz WiFi for best performance with 40+ clients"
echo ""
echo "To revert: cp /etc/config/wireless.backup /etc/config/wireless && wifi reload"


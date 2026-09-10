#!/bin/sh

# Optimize WiFi for high client count and connection storms
# Run this on the GL.iNet router
#
# Settings below match Router 1's proven-good production configuration
# (WiFi 6 HE modes, full transmit power, client isolation ON for tourist
# privacy/security). Safe to re-run after a golden-image restore or on
# any router still on factory-default WiFi settings.

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

    # WiFi 6 (HE) mode, 40MHz channel width
    uci set wireless.${RADIO_2G}.htmode='HE40'

    # Fixed channel 11 - adjust per-venue if interference is observed
    uci set wireless.${RADIO_2G}.channel='11'

    # Max transmit power
    uci set wireless.${RADIO_2G}.txpower='100'
fi

# Optimize 5GHz radio (if exists) - RECOMMENDED for 40+ clients
if [ -n "$RADIO_5G" ]; then
    echo "Optimizing 5GHz radio..."

    # WiFi 6 (HE) mode, 80MHz channel width
    uci set wireless.${RADIO_5G}.htmode='HE80'

    # Auto channel selection (avoids DFS/interference issues)
    uci set wireless.${RADIO_5G}.channel='auto'

    # Max transmit power
    uci set wireless.${RADIO_5G}.txpower='100'
fi

# Optimize both WiFi interfaces (tourist 2.4GHz + guide 5GHz)
IFACE_2G=$(uci show wireless | grep "device='${RADIO_2G}'" | grep "wifi-iface" | cut -d'.' -f2 | cut -d'=' -f1 | head -n1)
IFACE_5G=$(uci show wireless | grep "device='${RADIO_5G}'" | grep "wifi-iface" | cut -d'.' -f2 | cut -d'=' -f1 | head -n1)

for WIFI_IFACE in "$IFACE_2G" "$IFACE_5G"; do
    if [ -n "$WIFI_IFACE" ]; then
        echo "Optimizing WiFi interface: $WIFI_IFACE"

        # Increase maximum clients per band
        uci set wireless.${WIFI_IFACE}.maxassoc='60'

        # Keep client isolation ON - tourist devices shouldn't see each other
        uci set wireless.${WIFI_IFACE}.isolate='1'

        # Enable WMM for better audio QoS
        uci set wireless.${WIFI_IFACE}.wmm='1'
    fi
done

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
echo "  - 2.4GHz: Channel 11, HE40 mode (WiFi 6)"
echo "  - 5GHz: Auto channel, HE80 mode (WiFi 6)"
echo "  - Transmit power: 100 (max) on both bands"
echo "  - Max associations: 60 clients per band"
echo "  - Client isolation: ON (tourist devices can't see each other)"
echo "  - WMM enabled for better audio QoS"
echo ""
echo "⭐ RECOMMENDATION: Use 5GHz WiFi for best performance with 40+ clients"
echo ""
echo "To revert: cp /etc/config/wireless.backup /etc/config/wireless && wifi reload"


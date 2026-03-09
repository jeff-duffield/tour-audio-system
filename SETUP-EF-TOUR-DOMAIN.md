# Setting Up ef.tour Domain on Router

## Overview

To make it easier for users to access the tour audio system, you can configure the router to resolve `ef.tour` to `192.168.8.1`. This allows users to type `https://ef.tour/guide.html` instead of `https://192.168.8.1:3000/guide.html`.

## Prerequisites

1. SSL certificates generated (see QUICK-FIX.md)
2. Server running on port 3000 (or port 443 for standard HTTPS)

## Option 1: Use Port 443 (Standard HTTPS Port) - Recommended

This allows users to access `https://ef.tour/guide.html` without specifying a port.

### Step 1: Configure DNS on Router

SSH into the router and add DNS entry for ef.tour:

```bash
ssh root@192.168.8.1

# Edit dnsmasq configuration
vi /etc/config/dhcp
```

Add this line in the `config dnsmasq` section:

```
config dnsmasq
    option domainneeded '1'
    option localise_queries '1'
    # ... other options ...
    list address '/ef.tour/192.168.8.1'
```

Or use this command to add it automatically:

```bash
uci add_list dhcp.@dnsmasq[0].address='/ef.tour/192.168.8.1'
uci commit dhcp
/etc/init.d/dnsmasq restart
```

### Step 2: Change Server to Port 443

Edit the server startup to use port 443 (standard HTTPS port):

```bash
cd /root/tour-audio-server

# Set environment variable for port 443
export PORT=443

# Restart server
/etc/init.d/tour-audio restart
```

Or modify `/etc/init.d/tour-audio` to set PORT=443:

```bash
vi /etc/init.d/tour-audio
```

Change the start_service function to:

```bash
start_service() {
    procd_open_instance
    procd_set_param env PORT=443
    procd_set_param command /usr/bin/node /root/tour-audio-server/server.js
    procd_set_param respawn
    procd_set_param stdout 1
    procd_set_param stderr 1
    procd_close_instance
}
```

Then restart:

```bash
/etc/init.d/tour-audio restart
```

### Step 3: Update SSL Certificate

Regenerate the SSL certificate with ef.tour as the Common Name:

```bash
cd /root/tour-audio-server

# Backup old certificates
mv ssl/server.key ssl/server.key.bak
mv ssl/server.crt ssl/server.crt.bak

# Generate new certificate with ef.tour
openssl req -x509 -newkey rsa:2048 \
  -keyout ssl/server.key \
  -out ssl/server.crt \
  -days 365 \
  -nodes \
  -subj "/C=US/ST=State/L=City/O=EF Tours/CN=ef.tour" \
  -addext "subjectAltName=DNS:ef.tour,DNS:192.168.8.1,IP:192.168.8.1"

# Restart server
/etc/init.d/tour-audio restart
```

### Step 4: Test

From any device connected to the router's WiFi:

```
https://ef.tour/guide.html
https://ef.tour/tourist.html
https://ef.tour/health
```

## Option 2: Keep Port 3000 with DNS Redirect

If you want to keep the server on port 3000:

### Step 1: Add DNS Entry

```bash
ssh root@192.168.8.1

uci add_list dhcp.@dnsmasq[0].address='/ef.tour/192.168.8.1'
uci commit dhcp
/etc/init.d/dnsmasq restart
```

### Step 2: Access with Port Number

Users will need to access:

```
https://ef.tour:3000/guide.html
https://ef.tour:3000/tourist.html
```

## Option 3: Port Forwarding (Port 443 → 3000)

Keep server on port 3000 but redirect port 443 to 3000:

```bash
ssh root@192.168.8.1

# Add DNS entry
uci add_list dhcp.@dnsmasq[0].address='/ef.tour/192.168.8.1'
uci commit dhcp
/etc/init.d/dnsmasq restart

# Add port forwarding rule (443 → 3000)
iptables -t nat -A PREROUTING -p tcp --dport 443 -j REDIRECT --to-port 3000

# Make it persistent
cat >> /etc/firewall.user << 'EOF'
iptables -t nat -A PREROUTING -p tcp --dport 443 -j REDIRECT --to-port 3000
EOF

# Restart firewall
/etc/init.d/firewall restart
```

Now users can access:

```
https://ef.tour/guide.html
https://ef.tour/tourist.html
```

## Verification

Test DNS resolution:

```bash
# From router
nslookup ef.tour

# Should return: 192.168.8.1
```

Test from a connected device:

```bash
# Windows
nslookup ef.tour

# Should return: 192.168.8.1
```

## Recommended Setup

**For best user experience, use Option 1 or Option 3:**

- Users access: `https://ef.tour/guide.html` and `https://ef.tour/tourist.html`
- No port number needed
- Clean, simple URLs
- Professional appearance

## Troubleshooting

### DNS not resolving

```bash
# Check dnsmasq config
uci show dhcp | grep ef.tour

# Restart dnsmasq
/etc/init.d/dnsmasq restart

# Check dnsmasq is running
ps | grep dnsmasq
```

### Port 443 already in use

```bash
# Check what's using port 443
netstat -tulpn | grep :443

# If it's the router admin interface, you may need to change it
# Or use Option 3 (port forwarding) instead
```


# Tour Audio Server

Real-time audio streaming from tour guide to tourists' smartphones via local WiFi.

## Quick Reference

**Router Password:** `EFTours2026!`

### Deploy Updates to Router

**Most common: Deploy HTML files only (no restart needed)**
```bash
# Deploy all HTML files (UI changes only)
scp public/*.html root@192.168.8.1:/root/tour-audio-server/public/

# Or deploy specific files
scp public/index.html root@192.168.8.1:/root/tour-audio-server/public/
scp public/guide.html root@192.168.8.1:/root/tour-audio-server/public/
scp public/welcome.html root@192.168.8.1:/root/tour-audio-server/public/

# Just refresh browser - no restart needed!
```

**Less common: Deploy server.js (requires restart)**
```bash
# Deploy server changes
scp server.js root@192.168.8.1:/root/tour-audio-server/

# Restart server
ssh root@192.168.8.1
/etc/init.d/tour-audio restart
```

**Rare: Deploy everything (initial setup or major changes)**
```bash
# Deploy all files
scp -r public root@192.168.8.1:/root/tour-audio-server/
scp server.js root@192.168.8.1:/root/tour-audio-server/

# Restart server
ssh root@192.168.8.1
/etc/init.d/tour-audio restart
```

**Password:** `EFTours2026!`

### Access URLs

- **Welcome Page:** `http://ef.tour/` (no security warning)
- **Listener Page:** `https://ef.tour/` or `https://ef.tour/index.html`
- **Guide Page:** `https://ef.tour/guide.html`

---

## Overview

This solution allows a tour guide to broadcast live audio commentary to tourists' phones without requiring:
- Internet connectivity
- App downloads (works in browser)
- Special hardware beyond a portable WiFi router

## Architecture Options

### Option A: Router-Only (Recommended for Simplicity)

Use a more capable GL.iNet router (ARM-based) that can run both the WiFi access point
and the Node.js audio server on a single device.

**Recommended routers:**

| Router | CPU | RAM | Storage | Price | Notes |
|--------|-----|-----|---------|-------|-------|
| GL-MT2500 (Brume 2) | ARM Cortex-A53 | 1GB | 8GB eMMC | ~$70 | Best value; runs Node.js well |
| GL-MT3000 (Beryl AX) | ARM Cortex-A53 | 1GB | 8GB eMMC | ~$100 | Adds WiFi 6 support |
| GL-AXT1800 (Slate AX) | ARM Quad-core | 1GB | 8GB eMMC | ~$120 | Best WiFi range |

**Why ARM matters:** The budget GL-MT300N (MIPS-based, 128MB RAM) cannot reliably run
Node.js. ARM-based GL.iNet routers run OpenWrt on hardware powerful enough to serve
the audio application directly — no Raspberry Pi needed.

### Option B: Router + Phone Hotspot

Use any GL.iNet router purely as a WiFi access point, and run the server from a
dedicated phone or laptop connected to the router. Good for testing but less portable.

---

## Hardware You Need (Option A)

| Component | Example | Cost |
|-----------|---------|------|
| Router | GL-MT2500 (Brume 2) | ~$70 |
| USB battery pack | Any 10,000mAh+ with 5V/2A output | ~$20 |
| USB-C cable | Powers the router from the battery pack | included |
| **Total** | | **~$90** |

That's it. One router, one battery pack.

---

## Setup Guide

### Step 1: Initial Router Configuration

1. Power on the GL-MT2500 and connect to its WiFi (default SSID on the label)
2. Open a browser and go to `http://192.168.8.1`
3. Set an admin password when prompted.  Use:  EFTours2026!
4. Connect the router to another Wifi signal as a repeater (or connect to the internet via ethernet)
4. Go to **Wireless** settings and change the SSID to your tour name (e.g., `EFTourAudio-1`)
5. Set a WiFi password

### Step 2: Install Node.js on the Router

SSH into the router and install Node.js:

```bash
# Connect via SSH - use the admin password you setup in Step 1.3
ssh root@192.168.8.1

# Update package list
opkg update

# Install Node.js (ARM-based GL.iNet routers support this)
opkg install node node-npm
```

### Step 3: Deploy the Audio Server

Transfer the tour-audio-server files to the router:

```bash
# From your computer, copy files to the router
scp -r tour-audio-server root@192.168.8.1:/root/

# SSH back into the router - use the admin password you setup in Step 1.3
ssh root@192.168.8.1

# Install dependencies
cd /root/tour-audio-server
npm install --production

# Test that it works
node server.js
```

You should see:
```
Tour Audio Server running on port 3000
Guide interface: http://localhost:3000/guide.html
Listener interface: http://localhost:3000/ (or /index.html)
```

### Step 4: Set Up Auto-Start

Create an init script so the server starts automatically when the router powers on:

```bash
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

# Make it executable and enable it
chmod +x /etc/init.d/tour-audio
/etc/init.d/tour-audio enable
/etc/init.d/tour-audio start
```

### Step 5: Set Up SSL Certificates

Generate self-signed SSL certificates for HTTPS (required for microphone access):

```bash
# SSH into the router
ssh root@192.168.8.1

# Create SSL directory
cd /root/tour-audio-server
mkdir -p ssl

# Generate self-signed certificate
openssl req -x509 -newkey rsa:2048 -nodes \
  -keyout ssl/server.key \
  -out ssl/server.crt \
  -days 365 \
  -subj "/C=US/ST=State/L=City/O=EF Tours/CN=ef.tour"

# Verify files were created
ls -l ssl/
```

### Step 6: Configure DNS for ef.tour Domain

Set up local DNS resolution so users can access the app via `ef.tour`:

```bash
# Add DNS entry for ef.tour
cat >> /etc/dnsmasq.conf << 'EOF'

# Local domain for tour audio
address=/ef.tour/192.168.8.1
EOF

# Restart dnsmasq
/etc/init.d/dnsmasq restart

# Test DNS resolution
nslookup ef.tour 127.0.0.1
```

Should return `192.168.8.1`.

### Step 7: Set Up Port Forwarding

Forward standard HTTP/HTTPS ports to the audio server:

```bash
# Add port forwarding rules (restrict to LAN interface)
iptables -t nat -A PREROUTING -i br-lan -p tcp --dport 80 -j REDIRECT --to-ports 8888
iptables -t nat -A PREROUTING -i br-lan -p tcp --dport 443 -j REDIRECT --to-ports 3000

# Make rules persistent
cat >> /etc/firewall.user << 'EOF'
# Port forwarding for tour audio server
iptables -t nat -A PREROUTING -i br-lan -p tcp --dport 80 -j REDIRECT --to-ports 8888
iptables -t nat -A PREROUTING -i br-lan -p tcp --dport 443 -j REDIRECT --to-ports 3000
EOF

# Verify rules
iptables -t nat -L PREROUTING -n -v
```

**Note:** The `-i br-lan` flag restricts port forwarding to LAN traffic only, preventing conflicts with the router's WAN connection.

### Step 8: Set Up Captive Portal (Recommended)

Configure automatic captive portal detection so users are automatically directed to the welcome page when they connect to WiFi:

```bash
# Add captive portal detection URLs to dnsmasq
cat >> /etc/dnsmasq.conf << 'EOF'

# Captive portal detection URLs
address=/#/192.168.8.1
address=/captive.apple.com/192.168.8.1
address=/connectivitycheck.gstatic.com/192.168.8.1
address=/www.msftconnecttest.com/192.168.8.1
address=/detectportal.firefox.com/192.168.8.1
address=/clients3.google.com/192.168.8.1
address=/play.googleapis.com/192.168.8.1
EOF

# Restart dnsmasq
/etc/init.d/dnsmasq restart

# Restart tour audio server to load captive portal handlers
/etc/init.d/tour-audio restart
```

**How it works:**
- The wildcard DNS entry (`address=/#/192.168.8.1`) redirects ALL domain requests to the router
- When devices connect to WiFi, they check specific URLs to detect captive portals
- The router redirects these checks, triggering the "Sign in to network" notification
- Tapping the notification opens the welcome page automatically
- The server.js includes handlers that redirect captive portal checks to the welcome page

**Testing the captive portal:**
1. On a phone, **forget the WiFi network** (important!)
2. Turn WiFi off, wait 10 seconds, turn WiFi on
3. Reconnect to the network
4. Should see "Sign in to network" notification
5. Tapping it opens the welcome page at `http://ef.tour/`

**Note:** Users must forget and reconnect to the network for captive portal detection to trigger. Once connected, they can bookmark `ef.tour` for easy access.

---

## Usage

### For the Tour Guide:
1. Power on the router (connect USB battery pack)
2. Wait ~30 seconds for it to boot
3. Connect your phone to the tour WiFi network
4. Open browser and go to `https://ef.tour/guide.html`
5. Click through security warning (Advanced → Proceed)
6. Tap **Start Broadcasting**
7. Speak — your voice streams to all connected tourists

### For Tourists:

**With Captive Portal (Automatic):**
1. Connect to the tour WiFi network (e.g., `EF-Tour-Paris`)
2. Tap the "Sign in to network" notification that appears
3. Welcome page opens automatically
4. Tap "Start Listening"
5. Click through security warning (Advanced → Proceed)
6. Tap **Start Listening** on audio page
7. Use headphones for best experience
8. Adjust volume as needed

**Without Captive Portal (Manual):**
1. Connect to the tour WiFi network
2. Open any browser
3. Type: `ef.tour`
4. Follow steps 4-8 above

**Note:** The screen will stay on while listening to prevent audio interruption on iPhone.

---

## Network Diagram

```
                    +--------------------------------+
                    |   GL-MT3000 Router             |
                    |                                |
                    |  [WiFi Access Point]           |
                    |  [Node.js Dual Server]         |
                    |    - HTTP (8888): Welcome      |
                    |    - HTTPS (3000): Audio App   |
                    |  [dnsmasq DNS + Captive Portal]|
                    |  [Port Forwarding: 80→8888,    |
                    |                    443→3000]   |
                    +-----+----------+---------------+
                          |          |
                     WiFi |          | WiFi
                          |          |
                    +-----+--+  +---+--------+
                    | Guide  |  | Tourist    |
                    | Phone  |  | Phones     |
                    | (mic)  |  | (speakers) |
                    +--------+  +------------+
                                     |
                          "Sign in to network"
                          notification appears
                          automatically!
```

No Raspberry Pi. No ethernet cables. One device does everything.

---

## Troubleshooting

### Captive portal not appearing
- **Forget the network first:** Devices must forget and reconnect for captive portal detection
- Check DNS configuration: `cat /etc/dnsmasq.conf | grep -A 10 "Captive portal"`
- Verify wildcard DNS is working: `nslookup google.com 127.0.0.1` (should return 192.168.8.1)
- Restart dnsmasq: `/etc/init.d/dnsmasq restart`
- Check server captive portal handlers are working: `curl http://192.168.8.1:8888/hotspot-detect.html`

### Can't access ef.tour
- Check DNS entry: `nslookup ef.tour 127.0.0.1` (should return 192.168.8.1)
- Verify port forwarding: `iptables -t nat -L PREROUTING -n -v`
- Check both servers are running: `ps | grep node`
- Test direct access: `http://192.168.8.1:8888/` (HTTP) and `https://192.168.8.1:3000/` (HTTPS)

### Audio cutting out
- Move tourists closer to the router
- Reduce the number of connected devices
- Check battery pack charge level
- Try changing the WiFi channel to reduce interference

### Can't connect to tour WiFi
- Verify WiFi name and password in router admin panel
- Restart the router
- Check that the server is running: `curl http://localhost:3000/health`

### Audio delay / latency
- Current latency is ~200-300ms, which is normal for tour commentary
- Ensure no other services are consuming router CPU
- Check number of connected clients: `curl http://localhost:3000/health`

### Server won't start after reboot
- Check init script: `/etc/init.d/tour-audio status`
- Check logs: `logread | grep tour-audio`
- Manually test: `cd /root/tour-audio-server && node server.js`

### Node.js won't install
- Confirm your router is ARM-based (GL-MT2500, GL-MT3000, or GL-AXT1800)
- MIPS-based routers (GL-MT300N) do NOT support Node.js reliably
- Run `uname -m` — you need `aarch64` or `armv7l`
- Router needs internet access to download packages: `ping -c 3 8.8.8.8`

---

## Advanced Configuration

### Captive Portal Detection Handlers

The server.js file includes routes that redirect captive portal detection requests to the welcome page. These work in conjunction with the DNS configuration:

**HTTP Server (port 8888) - Redirects to welcome page:**
```javascript
httpApp.get('/hotspot-detect.html', (req, res) => {
    res.redirect('/welcome.html');
});

httpApp.get('/generate_204', (req, res) => {
    res.redirect(302, '/welcome.html');
});

httpApp.get('/gen_204', (req, res) => {
    res.redirect(302, '/welcome.html');
});
```

**HTTPS Server (port 3000) - Redirects to HTTP welcome:**
```javascript
app.get('/hotspot-detect.html', (req, res) => {
    res.redirect('http://ef.tour/');
});

app.get('/generate_204', (req, res) => {
    res.redirect(302, 'http://ef.tour/');
});
```

These handlers are automatically included in server.js and work with the DNS wildcard redirect.

### Changing the WiFi Network Name

1. SSH into the router or use the admin panel at `192.168.8.1`
2. Go to Wireless settings
3. Change SSID to your preferred name (e.g., `EF-Tour-Rome`)

### Monitoring Connected Clients

Visit `http://192.168.8.1:3000/health` to see:
```json
{
    "status": "ok",
    "clients": 23,
    "uptime": 3600
}
```

---

## Packing List (Per Tour Kit)

- [ ] GL-MT2500 router
- [ ] USB-C cable
- [ ] 10,000mAh USB battery pack
- [ ] Printed quick-start card for tourists (WiFi name, password, URL)
- [ ] Small carry pouch or bag

---

## File Structure

```
tour-audio-server/
├── server.js                      # Main server (dual HTTP/HTTPS)
├── package.json                   # Node.js dependencies
├── README.md                      # This file
├── DEPLOY-WAKE-LOCK.md           # Wake lock feature documentation
├── IOS-SILENT-MODE-LIMITATION.md # iOS silent mode info
├── SETUP-EF-TOUR-DOMAIN.md       # Domain setup guide
├── ssl/
│   ├── server.crt                # Self-signed SSL certificate
│   └── server.key                # SSL private key
└── public/
    ├── welcome.html              # Welcome page (HTTP, no warning)
    ├── index.html                # Listener interface (HTTPS)
    ├── guide.html                # Tour guide broadcast interface (HTTPS)
    └── EF-Logos_EF Logo Pink.png # EF Tours logo
```

## Features

### Current Features
- ✅ **Real-time audio streaming** via WebSocket (WSS)
- ✅ **HTTPS with self-signed certificates** for microphone access
- ✅ **HTTP welcome page** to explain security warnings
- ✅ **Captive portal** automatically directs users to welcome page when connecting to WiFi
- ✅ **Custom domain** (`ef.tour`) for easy access
- ✅ **iOS Safari compatibility** using Web Audio API
- ✅ **Wake Lock API** prevents iPhone auto-lock during playback
- ✅ **iOS silent mode detection** with user-friendly warnings
- ✅ **Volume control** for listeners
- ✅ **Audio visualization** with waveforms for both guide and listeners
- ✅ **Broadcast indicators** with pulsing dots showing active transmission
- ✅ **Microphone test** feature for guide
- ✅ **Audio test** feature for listeners
- ✅ **Low latency** (~200-300ms) with drift correction
- ✅ **EF brand styling** with color-coded pages (blue for listeners, pink for guide)
- ✅ **Dual-server architecture** (HTTP on port 8888, HTTPS on port 3000)
- ✅ **Port forwarding** (80→8888, 443→3000)

### Known Limitations
- **iOS Silent Mode:** Audio won't play through speakers if silent switch is on (use headphones)
- **Security Warnings:** Self-signed SSL certificates trigger browser warnings (explained on welcome page)
- **Wake Lock Support:** Requires iOS 16.4+ (older devices need manual auto-lock adjustment)
- **Captive Portal:** Requires users to forget and reconnect to network for initial detection

---

## Cost Comparison

| Component | Our Solution | AudioFetch Express |
|-----------|-------------|--------------------|
| Hardware  | ~$90 total  | $649-748 per unit  |
| Ongoing   | None        | None               |
| App required | No (browser) | Yes (app store) |
| Devices   | 1 (router)  | 1 (AudioFetch box) |

---

## License

Internal EF use only.

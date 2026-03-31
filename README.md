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

### Run Load Test

```bash
# Install dependencies (first time only)
npm.cmd install

#### Open PowerShell and add Node.js to your PATH

Run this in PowerShell, adjusting the path to match where you extracted Node.js:

```powershell
$env:PATH = "C:\Users\JeffDuffield\OneDrive - Education First\Node JS\node-v24.14.0-win-x64;$env:PATH"
```

> ⚠️ This only lasts for the current PowerShell session. You'll need to re-run it each time you open a new terminal.

#### Allow npm scripts to run

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
cd "C:\Users\JeffDuffield\OneDrive - Education First\Documents\tour-audio-server"
npm.cmd install

# Run with 40 simulated clients for 2 minutes
node load-test.js --clients 80 --duration 120
```

> Make sure the guide app is actively broadcasting before starting the test, otherwise clients will connect but receive no audio data.

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

## Performance Optimizations for 40+ Concurrent Users

The system has been optimized to handle 50+ simultaneous connections with 0% packet loss. These optimizations are **critical** for production deployment.

### Overview of Optimizations

| Component | Key Optimizations | Result |
|-----------|-------------------|--------|
| **Server** | 64KB backpressure, debounced broadcasts | 0% skip rate @ 52 clients |
| **Guide App** | No client-side throttling, 1024 buffer | Continuous audio stream |
| **Listener App** | 100ms initial buffer, drift handling | No gaps or stuttering |
| **Router DHCP** | 100 client pool, rapid commit | Handles connection storms |
| **Router WiFi** | 60 max clients/band, WMM QoS | Supports 120 total clients |

### Step 9: Apply Router DHCP Optimizations

Run the DHCP optimization script to handle connection storms (when many users join WiFi simultaneously):

```bash
# Upload optimization script to router
scp optimize-dhcp.sh root@192.168.8.1:/root/

# SSH into router
ssh root@192.168.8.1

# Run DHCP optimization
chmod +x /root/optimize-dhcp.sh
/root/optimize-dhcp.sh
```

**What this does:**
- Increases DHCP pool from 50 → 100 clients
- Reduces lease time to 2 hours (faster IP reclamation)
- Enables rapid commit (2x faster DHCP assignments)
- Increases DNS cache to 1000 entries

**Expected improvement:** 80-90% reduction in connection failures during mass join events

---

### Step 10: Apply Router WiFi Optimizations

Optimize WiFi settings for high client count and enable 5GHz band:

```bash
# Upload WiFi optimization script
scp optimize-wifi.sh root@192.168.8.1:/root/

# SSH into router
ssh root@192.168.8.1

# Run WiFi optimization
chmod +x /root/optimize-wifi.sh
/root/optimize-wifi.sh
```

**What this does:**
- Increases max associations from 30 → 60 clients per band
- Enables WMM (WiFi Multimedia QoS) for audio priority
- Optimizes 2.4GHz: Channel 6, HE40 mode (WiFi 6)
- Optimizes 5GHz: Auto channel, HE80 mode (WiFi 6)
- Enables client isolation for security (can be disabled if needed)

**Important:** Encourage users to connect to **5GHz (EF-TOUR-001)** for best performance with 40+ people.

**Manual WiFi optimization (if script doesn't match your router):**

```bash
ssh root@192.168.8.1

# Set max clients to 60 for both bands
uci set wireless.wifi2g.maxassoc='60'
uci set wireless.wifi5g.maxassoc='60'

# Enable WMM (QoS) for audio priority
uci set wireless.wifi2g.wmm='1'
uci set wireless.wifi5g.wmm='1'

# Enable client isolation for security
uci set wireless.wifi2g.isolate='1'
uci set wireless.wifi5g.isolate='1'

# Apply changes
uci commit wireless
wifi reload
```

**Verification:**

```bash
# Check DHCP settings
uci show dhcp.lan | grep -E "leasetime|limit"
# Should show: leasetime='2h' and limit='100'

# Check WiFi settings
uci show wireless | grep -E "maxassoc|wmm"
# Should show: maxassoc='60' and wmm='1' for both bands

# Check both WiFi bands are active
wifi status | grep -c "up"
# Should show: 2 (both 2.4GHz and 5GHz enabled)
```

---

### Audio Performance Optimizations (Already Implemented)

The following optimizations are already built into the code:

#### Server-Side (server.js):

**1. Backpressure Threshold: 64KB**
```javascript
// Only skip clients with >64KB buffered (about 2 seconds of audio)
const bufferSize = client.bufferedAmount || 0;
if (bufferSize > 65536) {
    skippedThisRound++;
    skippedClients++;
    continue;
}
```
- **Impact:** Skip rate decreased from 43% → 0% with 52 clients
- **Rationale:** Previous 16KB threshold was too aggressive

**2. Debounced Client Count Broadcasts**
```javascript
// Only broadcast client count after 500ms of no changes
let clientCountBroadcastTimeout = null;
function broadcastClientCount() {
    if (clientCountBroadcastTimeout) {
        clearTimeout(clientCountBroadcastTimeout);
    }
    clientCountBroadcastTimeout = setTimeout(() => {
        // Broadcast after quiet period
    }, 500);
}
```
- **Impact:** Prevents message storms during mass connections
- **Rationale:** 40 people connecting = 1,600+ messages without debouncing

**3. Global Error Handlers**
```javascript
// Prevent crashes from uncaught exceptions
process.on('uncaughtException', (error) => {
    console.error('UNCAUGHT EXCEPTION:', error);
    // Don't exit - log and continue
});

// Graceful shutdown
process.on('SIGTERM', () => {
    wss.close();
    server.close();
    // ... cleanup
});
```
- **Impact:** Server stays running even with unexpected errors
- **Rationale:** Procd auto-restart still available as backup

#### Guide App (guide.html):

**1. Optimized Buffer Size: 1024 samples**
```javascript
// 1024 samples = 64ms chunks at 16kHz
scriptProcessor = audioContext.createScriptProcessor(1024, 1, 1);
```
- **Impact:** Stable continuous audio capture
- **Rationale:** 512 samples too small, caused processing gaps

**2. No Client-Side Backpressure**
```javascript
// Always send all audio - no throttling
ws.send(pcmData.buffer);
```
- **Impact:** Fixed cutouts with even 1 listener
- **Rationale:** Let server handle backpressure, not guide

**3. 16kHz Sample Rate**
```javascript
audioContext = new AudioContext({ sampleRate: 16000 });
```
- **Impact:** Good voice quality, reasonable bandwidth (256 Kbps/client)
- **Rationale:** Balance between quality and capacity

**4. PCM Int16 Encoding**
```javascript
// Convert Float32 to Int16 (2 bytes per sample)
const pcmData = new Int16Array(inputData.length);
for (let i = 0; i < inputData.length; i++) {
    const s = Math.max(-1, Math.min(1, inputData[i]));
    pcmData[i] = s < 0 ? s * 32768 : s * 32767;
}
```
- **Impact:** Efficient encoding, low CPU usage
- **Rationale:** Tried Opus codec but MediaRecorder was unstable

#### Listener App (index.html):

**1. 100ms Initial Buffer**
```javascript
if (receivedPacketCount === 1) {
    // First packet: add buffer to prevent underruns
    nextPlayTime = audioContext.currentTime + 0.1;
}
```
- **Impact:** Eliminated audio gaps and stuttering
- **Rationale:** Zero-latency playback caused buffer underruns

**2. Drift Handling**
```javascript
// Check for drift and reset if needed
const drift = nextPlayTime - audioContext.currentTime;
if (drift > 1.0) {
    // More than 1 second ahead - reset
    nextPlayTime = audioContext.currentTime + 0.1;
} else if (drift < 0) {
    // Behind current time - catch up
    nextPlayTime = audioContext.currentTime + 0.05;
}
```
- **Impact:** Graceful handling of network jitter
- **Rationale:** Prevents buffer overflow/underflow

---

### Performance Testing Results

**Test Configuration:**
- Router: GL-MT3000 (WiFi 6)
- Clients: 52 simultaneous connections (1 guide + 51 listeners)
- Duration: 97 seconds of continuous broadcast

**Results:**
```json
{
  "clients": 52,
  "skipRate": "0.00%",
  "slowClients": 0,
  "maxBufferSize": 0,
  "totalBroadcasts": 1550
}
```

**Capacity:**
- **Tested:** 52 clients with 0% packet loss ✅
- **Theoretical max:** 120 clients (60 per WiFi band)
- **Recommended max:** 60 clients for production use
- **Bandwidth per client:** 256 Kbps (16kHz × 2 bytes × 8 bits)
- **Total bandwidth @ 60 clients:** ~15 Mbps (well within WiFi 6 capacity)

---

### Monitoring Performance During Tours

Check system health in real-time:

```bash
# Quick health check
curl -k https://192.168.8.1:3000/health

# Example output:
{
  "status": "ok",
  "clients": 42,
  "uptime": 127.01,
  "performance": {
    "totalBroadcasts": 1423,
    "skippedPackets": 0,
    "skipRate": "0.00%",
    "avgBufferSize": 0,
    "maxBufferSize": 0,
    "slowClients": 0
  }
}
```

**What to look for:**
- ✅ `skipRate < 5%` - Excellent
- ⚠️ `skipRate 5-15%` - Acceptable (some clients on poor WiFi)
- ❌ `skipRate > 15%` - Problem (see troubleshooting below)

**Real-time monitoring during broadcast:**

```bash
ssh root@192.168.8.1

# Monitor connections and performance
watch -n 2 'echo "=== Connected Clients ==="; \
cat /tmp/dhcp.leases | wc -l; \
echo ""; \
echo "=== Server Health ==="; \
curl -s -k https://127.0.0.1:3000/health 2>/dev/null; \
echo ""; \
echo "=== Memory ==="; \
free -m | grep Mem'
```

---

### Troubleshooting High Load

**If skip rate > 15% with many clients:**

1. **Check WiFi band distribution:**
   ```bash
   ssh root@192.168.8.1

   # Count clients on 2.4GHz
   iw dev ra0 station dump 2>/dev/null | grep Station | wc -l

   # Count clients on 5GHz
   iw dev rax0 station dump 2>/dev/null | grep Station | wc -l
   ```
   - **Goal:** Distribute evenly between both bands
   - **Action:** Direct users to connect to "EF-TOUR-001" (5GHz)

2. **Reduce sample rate (last resort):**
   ```javascript
   // In guide.html, change:
   audioContext = new AudioContext({ sampleRate: 12000 });  // Was 16000

   // 25% less bandwidth per client
   ```

3. **Increase backpressure tolerance:**
   ```javascript
   // In server.js, change:
   if (bufferSize > 131072) {  // 128KB instead of 64KB
       skippedThisRound++;
       continue;
   }
   ```

**If many clients fail to connect:**

1. **Stagger connections:**
   - Have groups of 10-15 connect at a time
   - Wait 30 seconds between groups

2. **Use waiting room page:**
   - Already available at `waiting-room.html`
   - Implements random delays to spread connections

3. **Verify optimizations are applied:**
   ```bash
   # Check DHCP pool
   uci show dhcp.lan.limit
   # Should show: 100

   # Check WiFi max associations
   uci show wireless | grep maxassoc
   # Should show: 60 for both bands
   ```

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
├── server.js                          # Main server (dual HTTP/HTTPS)
├── package.json                       # Node.js dependencies
├── README.md                          # This file
├── DEPLOY-WAKE-LOCK.md               # Wake lock feature documentation
├── IOS-SILENT-MODE-LIMITATION.md     # iOS silent mode info
├── SETUP-EF-TOUR-DOMAIN.md           # Domain setup guide
├── SECURITY-RELIABILITY-AUDIT.md     # Security checklist and pre-tour verification
├── optimize-dhcp.sh                  # Router DHCP optimization script
├── optimize-wifi.sh                  # Router WiFi optimization script
├── setup-router.sh                   # Complete router setup automation
├── deploy-to-router.ps1              # PowerShell deployment helper
├── ssl/
│   ├── server.crt                    # Self-signed SSL certificate
│   └── server.key                    # SSL private key
└── public/
    ├── welcome.html                  # Welcome page (HTTP, no warning)
    ├── index.html                    # Listener interface (HTTPS)
    ├── guide.html                    # Tour guide broadcast interface (HTTPS)
    ├── waiting-room.html             # Waiting room for staggered connections
    └── EF-Logos_EF Logo Pink.png     # EF Tours logo
```

## Features

### Current Features

**Audio Streaming:**
- ✅ **Real-time audio streaming** via WebSocket (WSS)
- ✅ **Low latency** (~200-300ms end-to-end)
- ✅ **PCM Int16 encoding** for reliable real-time transmission
- ✅ **16kHz sample rate** (good voice quality, efficient bandwidth)
- ✅ **1024 sample buffer** (64ms chunks for stability)
- ✅ **Drift correction** prevents buffer overflow/underflow
- ✅ **100ms initial buffer** eliminates audio gaps

**High-Capacity Performance:**
- ✅ **50+ concurrent users** tested with 0% packet loss
- ✅ **64KB backpressure threshold** for smooth streaming
- ✅ **Debounced broadcasts** prevent message storms
- ✅ **Global error handlers** prevent server crashes
- ✅ **Auto-restart** on crash (procd integration)
- ✅ **Real-time performance monitoring** via /health endpoint

**Network & Connectivity:**
- ✅ **HTTPS with self-signed certificates** for microphone access
- ✅ **HTTP welcome page** to explain security warnings
- ✅ **Captive portal** automatically directs users to welcome page
- ✅ **Custom domain** (`ef.tour`) for easy access
- ✅ **Dual-server architecture** (HTTP on 8888, HTTPS on 3000)
- ✅ **Port forwarding** (80→8888, 443→3000)
- ✅ **Optimized DHCP** (100 client pool, rapid commit)
- ✅ **Optimized WiFi** (60 clients/band, WMM QoS)
- ✅ **Dual-band support** (2.4GHz + 5GHz WiFi 6)

**User Experience:**
- ✅ **iOS Safari compatibility** using Web Audio API
- ✅ **Wake Lock API** prevents screen lock during broadcast (guide) and playback (listeners)
- ✅ **iOS silent mode detection** with user-friendly warnings
- ✅ **Volume control** for listeners
- ✅ **Audio visualization** with waveforms for both guide and listeners
- ✅ **Broadcast indicators** with pulsing dots showing active transmission
- ✅ **Microphone test** feature for guide
- ✅ **Audio test** feature for listeners
- ✅ **EF brand styling** with color-coded pages (blue for listeners, pink for guide)
- ✅ **Waiting room page** for staggered connections

### Performance Specifications

| Metric | Value | Notes |
|--------|-------|-------|
| **Max Tested Users** | 52 clients | 0% packet loss |
| **Recommended Max** | 60 clients | For production use |
| **Theoretical Max** | 120 clients | 60 per WiFi band |
| **Skip Rate @ 52 clients** | 0.00% | Perfect performance |
| **Bandwidth per Client** | 256 Kbps | 16kHz × 2 bytes × 8 bits |
| **Total Bandwidth @ 60** | ~15 Mbps | Well within WiFi 6 capacity |
| **Latency** | 200-300ms | Acceptable for tour commentary |
| **Sample Rate** | 16 kHz | Good voice quality |
| **Audio Encoding** | PCM Int16 | Lossless, low CPU |
| **Buffer Size** | 1024 samples | 64ms chunks |
| **Initial Buffer** | 100ms | Prevents underruns |
| **Backpressure** | 64KB | ~2 seconds of audio |

### Known Limitations
- **iOS Silent Mode:** Audio won't play through speakers if silent switch is on (use headphones)
- **Security Warnings:** Self-signed SSL certificates trigger browser warnings (explained on welcome page)
- **Wake Lock Support:** Requires iOS 16.4+ (older devices need manual auto-lock adjustment)
- **Captive Portal:** Requires users to forget and reconnect to network for initial detection
- **Connection Storms:** Best to stagger connections in groups of 10-15 for 40+ users
- **WiFi Saturation:** Performance degrades if too many users on 2.4GHz band (use 5GHz)

---

## Load Testing

Before deploying to a real tour, test the server's capacity with the included load testing tool.

### What the Load Test Simulates

The `load-test.js` script simulates multiple listener clients connecting to your server:

✅ **What it tests:**
- WebSocket connection handling (multiple simultaneous connections)
- Server CPU/memory under load
- Message broadcast performance
- Network throughput

⚠️ **What it does NOT test:**
- Router WiFi capacity (clients run from one device)
- DHCP performance (no real WiFi connections)
- Physical WiFi interference
- Connection storms (all clients use same network interface)

**Bottom line:** This tests **server load**, not **network load**. Use it to verify the Node.js server can handle 40+ WebSocket connections, but you still need a real-world test for WiFi capacity.

---

### Prerequisites

The load test requires the `ws` package (WebSocket client):

```bash
# Install dependencies (if not already done)
npm install
```

---

### Running a Load Test

#### **Basic Test (20 clients, 60 seconds):**

```bash
node load-test.js
```

#### **Simulate Your Tour (40 clients, 2 minutes):**

```bash
node load-test.js --clients 40 --duration 120
```

#### **Stress Test (60 clients, 5 minutes):**

```bash
node load-test.js --clients 60 --duration 300 --ramp 15
```

---

### Command Line Options

| Option | Default | Description |
|--------|---------|-------------|
| `--clients <number>` | 20 | Number of simulated listener clients |
| `--duration <seconds>` | 60 | How long to run the test |
| `--ramp <seconds>` | 10 | Time to gradually add all clients (prevents connection storm) |
| `--host <hostname>` | 192.168.8.1 | Server hostname or IP |
| `--port <number>` | 3000 | Server port |
| `--no-ssl` | (disabled) | Use WS instead of WSS |

---

### Example Test Scenarios

#### **1. Quick Validation (Post-Deployment):**
```bash
# 10 clients for 30 seconds - verify server is working
node load-test.js --clients 10 --duration 30
```

#### **2. Tour Capacity Test (40 tourists):**
```bash
# Simulate a 2-minute tour with 40 people
node load-test.js --clients 40 --duration 120 --ramp 20
```

#### **3. Maximum Capacity Test:**
```bash
# Push to 60 clients for 5 minutes
node load-test.js --clients 60 --duration 300 --ramp 30
```

#### **4. Connection Storm Test:**
```bash
# 50 clients connecting in 5 seconds (aggressive)
node load-test.js --clients 50 --duration 60 --ramp 5
```

---

### Understanding the Output

#### **During the Test:**

```
╔════════════════════════════════════════════════════╗
║     Tour Audio Server - Load Test                 ║
╚════════════════════════════════════════════════════╝

Configuration:
  Clients:    40
  Duration:   120s
  Ramp-up:    20s
  Server:     wss://192.168.8.1:3000

────────────────────────────────────────────────────────

[Client 1] Connecting to wss://192.168.8.1:3000...
[Client 1] ✅ Connected (Total: 1)
[Client 2] Connecting to wss://192.168.8.1:3000...
[Client 2] ✅ Connected (Total: 2)
...

[25.0s] Current Statistics:
────────────────────────────────────────────────────────
  Connected:        40
  Disconnected:     0
  Errors:           0
  Messages Received: 12,340
  Bytes Received:    25.45 KB
  Message Rate:      493.6 msg/sec
  Data Rate:         1.02 KB/sec
```

**What to look for:**
- ✅ `Connected: 40` (all clients connected successfully)
- ✅ `Errors: 0` (no connection failures)
- ✅ `Messages Received` increasing steadily
- ✅ `Message Rate` should be stable (not dropping)

**Warning signs:**
- ⚠️ `Connected < target` (some clients failed to connect)
- ⚠️ `Errors > 0` (connection or message errors)
- ⚠️ `⚠️ Warning: X clients haven't received data in 5+ seconds`

---

#### **Final Report:**

```
╔════════════════════════════════════════════════════╗
║              FINAL TEST REPORT                     ║
╚════════════════════════════════════════════════════╝

Overall Statistics:
────────────────────────────────────────────────────────
  Test Duration:        120.0s
  Target Clients:       40
  Successfully Connected: 40
  Connection Failures:   0
  Total Disconnects:     0
  Total Errors:          0
  Messages Received:     58,920
  Bytes Received:        121.50 MB
  Avg Message Rate:      490.7 msg/sec
  Avg Data Rate:         1.01 KB/sec

Per-Client Statistics:
────────────────────────────────────────────────────────
  Avg messages per client: 1473
  Min messages: 1468
  Max messages: 1478

════════════════════════════════════════════════════════
  Test complete! ✅
════════════════════════════════════════════════════════
```

**Success criteria:**
- ✅ `Connection Failures: 0`
- ✅ `Total Errors: 0`
- ✅ `Min messages` close to `Max messages` (even distribution)
- ✅ No problematic clients listed

---

### Interpreting Results

#### **✅ GOOD - Example with 40 clients:**
```
Successfully Connected: 40      ← All connected
Total Errors:          0        ← No errors
Avg messages per client: 1473   ← Even distribution
Min messages: 1468              ← Within 1% of average
Max messages: 1478
```
**Verdict:** Server handled the load perfectly. Safe to deploy.

---

#### **⚠️ WARNING - Example with 60 clients:**
```
Successfully Connected: 58      ← 2 failed to connect
Total Errors:          12       ← Some errors occurred
Avg messages per client: 1250
Min messages: 890               ← Some clients got 30% fewer messages
Max messages: 1310

⚠️ 8 clients had issues:
   Client 23: 890 msgs, 5 errors
   Client 47: 920 msgs, 3 errors
```
**Verdict:** Server is at capacity. Stay under 60 clients or optimize further.

---

#### **❌ FAILURE - Example with 80 clients:**
```
Successfully Connected: 72      ← 10% connection failure rate
Total Errors:          245      ← Many errors
Avg messages per client: 850
Min messages: 120               ← Some clients got almost nothing

⚠️ 28 clients had issues:
   Client 15: 120 msgs, 45 errors
   Client 38: 230 msgs, 32 errors
   ...
```
**Verdict:** Server overloaded. Reduce client count or upgrade hardware.

---

### Checking Server Health During Test

While the load test is running, check the server's `/health` endpoint:

```bash
# In another terminal window
curl -k https://192.168.8.1:3000/health
```

**Example healthy output:**
```json
{
  "status": "ok",
  "clients": 40,
  "uptime": 125.5,
  "performance": {
    "totalBroadcasts": 1420,
    "skippedPackets": 0,
    "skipRate": "0.00%",
    "avgBufferSize": 0,
    "maxBufferSize": 0,
    "slowClients": 0
  }
}
```

**What to check:**
- ✅ `clients: 40` (matches load test client count)
- ✅ `skipRate: "0.00%"` (no packets dropped)
- ✅ `slowClients: 0` (all clients keeping up)

**Warning signs:**
- ⚠️ `skipRate > 5%` (server dropping packets)
- ⚠️ `slowClients > 0` (some clients lagging)
- ⚠️ `maxBufferSize > 100000` (backpressure building up)

---

### Monitoring Router Resources During Test

SSH into the router and check CPU/memory:

```bash
ssh root@192.168.8.1

# Check CPU and memory usage
top -n 1

# Look for:
# CPU: Should be < 80% for node process
# Mem: Should have > 100MB available
```

**Healthy output:**
```
  PID  PPID USER     STAT   VSZ %VSZ CPU %CPU COMMAND
 1234     1 root     S    150M  29%   0  45% node /root/tour-audio-server/server.js

Mem: 512000K used, 100000K free, 0K shrd, 12000K buff, 180000K cached
                      ↑
                   Good! >100MB free
```

**Warning signs:**
- ⚠️ CPU > 90% sustained (router struggling)
- ⚠️ Free memory < 50MB (running out of RAM)

---

### Load Test vs. Real-World Test

| Aspect | Load Test | Real-World Test |
|--------|-----------|-----------------|
| **Server capacity** | ✅ Tests | ✅ Tests |
| **WebSocket handling** | ✅ Tests | ✅ Tests |
| **WiFi capacity** | ❌ Does not test | ✅ Tests |
| **DHCP performance** | ❌ Does not test | ✅ Tests |
| **Connection storms** | ⚠️ Limited | ✅ Full test |
| **Physical interference** | ❌ Does not test | ✅ Tests |
| **Client device compatibility** | ❌ Does not test | ✅ Tests |
| **Network jitter** | ⚠️ Same network | ✅ Real WiFi |

**Bottom line:** Use load test to validate server can handle the client count, but still do a real-world test with actual people and devices to validate the complete system.

---

### Pre-Tour Test Checklist

Before your next tour with real people:

1. **✅ Run load test locally (from your laptop):**
   ```bash
   node load-test.js --clients 40 --duration 120
   ```
   - Verify 0 connection failures
   - Verify 0% skip rate

2. **✅ Check server health endpoint:**
   ```bash
   curl -k https://192.168.8.1:3000/health
   ```

3. **✅ Run load test from a phone on WiFi:**
   - Connect phone to tour WiFi
   - Install Node.js/Termux on phone (or use laptop on WiFi)
   - Run smaller test (10-20 clients)
   - Verifies WiFi + server together

4. **✅ Test with 2-3 real people:**
   - Have them connect to WiFi
   - Have them open listener page
   - Guide broadcasts for 5 minutes
   - Ask about audio quality

5. **✅ Create backup config:**
   ```bash
   ssh root@192.168.8.1 sysupgrade -b /tmp/backup.tar.gz
   scp root@192.168.8.1:/tmp/backup.tar.gz ./router-backup-$(date +%Y%m%d).tar.gz
   ```

---

### Troubleshooting Load Test Issues

#### **Issue: "connect ECONNREFUSED"**
```
[Client 1] ❌ Error: connect ECONNREFUSED 192.168.8.1:3000
```

**Cause:** Server is not running or not accessible

**Fix:**
```bash
# Check if server is running
ssh root@192.168.8.1
/etc/init.d/tour-audio status

# If not running, start it
/etc/init.d/tour-audio start

# Verify it's listening on port 3000
netstat -tlnp | grep 3000
```

---

#### **Issue: SSL certificate errors**
```
[Client 1] ❌ Error: self signed certificate
```

**Cause:** Node.js rejecting self-signed certificate (should be ignored by load-test.js)

**Fix:** Load test should already handle this. If not:
```bash
# Verify you're using the latest load-test.js
# The script includes: rejectUnauthorized: false
```

---

#### **Issue: High skip rate during load test**
```
"skipRate": "25.00%"
```

**Cause:** Server can't keep up with broadcast rate

**Possible solutions:**
1. **Verify guide is broadcasting:** Make sure guide interface is actively broadcasting
2. **Check network:** Load test might be on different network than guide
3. **Reduce client count:** Try with fewer clients first
4. **Check CPU:** Router might be overloaded

**Diagnosis:**
```bash
ssh root@192.168.8.1

# Check CPU usage
top -n 1

# Check memory
free -m

# Restart server if needed
/etc/init.d/tour-audio restart
```

---

#### **Issue: Some clients don't receive messages**
```
⚠️ 5 clients had issues:
   Client 23: 120 msgs, 0 errors
   Client 47: 95 msgs, 0 errors
```

**Cause:** Network congestion or server backpressure

**Fix:**
1. **Check skip rate** - should be < 5%
2. **Reduce ramp time** - try `--ramp 30` for slower connection
3. **Check if guide is actually broadcasting** - audio data needed

---

### Advanced: Running Load Test from Multiple Devices

For more realistic WiFi testing, run the load test from multiple phones/laptops connected to the tour WiFi:

**Device 1 (Laptop):**
```bash
node load-test.js --clients 20 --duration 120
```

**Device 2 (Phone with Termux):**
```bash
node load-test.js --clients 20 --duration 120
```

This tests both server capacity AND WiFi capacity simultaneously.

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

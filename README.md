# Tour Audio Server

Real-time audio streaming from tour guide to tourists' smartphones via local WiFi.

## Quick Reference

**Router Password:** See `secrets.txt` (same folder as this README)

**EF-TOUR-001 Network Password:** EFLearnFun

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

**Password:** See `secrets.txt` (same folder as this README)

### Run Load Test

```bash
# Install dependencies (first time only)
npm.cmd install
```

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
node load-test.js --clients 40 --duration 120
```

> Make sure the guide app is actively broadcasting before starting the test, otherwise clients will connect but receive no audio data.

### Access URLs

- **Listener Page:** `https://audio.eftours.travel/` or `https://audio.eftours.travel/index.html`
- **Guide Page:** `https://audio.eftours.travel/guide.html`
- **Audio System Admin:** `https://audio.eftours.travel/admin?token=EFAdminAudio02141`
- **Health Check:** `https://audio.eftours.travel/health`

---

## Audio System Admin Portal

The Audio System Admin portal lets a Tour Director connect the router to hotel Wi-Fi and install SSL certificate updates — without needing to know the router password or use SSH.

### How Access Works

Access is granted via a **fixed token** baked into the URL. The token never changes and never needs to be regenerated. Sessions last 8 hours after opening the link.

**Admin URL (bookmark this):**
```
https://audio.eftours.travel/admin?token=EFAdminAudio02141
```

### On-Screen QR Code + URL Page

Once logged into the admin portal, tap **"📱 Show QR code for this device"** at the bottom of the page. This opens `/admin/qr`, which shows:

- The **QR code** (scannable from another phone)
- The full **admin URL** in a copyable text box
- A **"📋 Copy URL"** button — tap to copy to clipboard

This page works for both scenarios: a TD on their phone scans the QR, a TD on a laptop copies the URL.

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
3. Set an admin password when prompted. Use the router admin password from `secrets.txt`.
4. Connect the router to another Wifi signal as a repeater (or connect to the internet via ethernet)
5. Set the WiFi password for both the 2.4GHz and 5GHz networks to the shared fleet password (see `secrets.txt`)

> Leave the SSIDs (network names) as default for now — `setup-router.sh <router-number>` (Step 3 below) sets the correct unique tourist/guide network names automatically. See **Router Provisioning at Scale** for the naming convention and to claim the next available router number from the Fleet Registry.

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

### Step 5: Set Up SSL Certificate

The SSL certificate for `audio.eftours.travel` is a **CA-signed Let's Encrypt certificate** managed by EF IT. It is installed via the Audio System Admin portal — no manual OpenSSL steps needed.

> Before the real certificate is installed, `setup-router.sh` bootstraps a **temporary self-signed certificate** so the HTTPS server has something to start with. Expect a one-time browser security warning until "Update Device" replaces it with the trusted cert below.

**One-time setup (done by IT over SSH):**
```bash
# Place the certificate API token on the router
ssh root@192.168.8.1
echo 'YOUR_CERT_TOKEN_HERE' > /etc/ef-cert-token
```

The certificate is then installed by the Tour Director via the admin portal:
1. Open the **Audio System Admin** URL on a device connected to the tour WiFi
2. Connect the router to internet (Step 1 in the portal)
3. Tap **Update Device Now** (Step 2 in the portal)
4. The router fetches a fresh certificate from `https://api.eftours.travel/router-cert` and installs it automatically

**Certificate details:**
- Domain: `audio.eftours.travel`
- Issuer: Let's Encrypt (trusted by all browsers — no security warnings)
- Renewal: Certificate expires every ~90 days; TD runs "Update Device" to renew
- Token file: `/etc/ef-cert-token`

### Step 6: Configure DNS for audio.eftours.travel

Set up local DNS so devices on the tour WiFi resolve `audio.eftours.travel` to the router:

```bash
# Add DNS entry
echo 'address=/audio.eftours.travel/192.168.8.1' >> /etc/dnsmasq.conf

# Also add via UCI for persistence across firmware updates
uci add_list dhcp.@dnsmasq[0].address='/audio.eftours.travel/192.168.8.1'
uci commit dhcp

# Restart dnsmasq
/etc/init.d/dnsmasq restart

# Test DNS resolution (should return 192.168.8.1)
nslookup audio.eftours.travel 127.0.0.1
```

**Also add captive portal probe domains** so that iOS, Android, and Windows devices trigger the captive portal UI when joining the network and iCloud Private Relay stays suspended for the session:

```bash
cat >> /etc/dnsmasq.conf << 'EOF'

# Captive portal host intercept — OS connectivity probes
address=/captive.apple.com/192.168.8.1
address=/www.appleiphonecell.com/192.168.8.1
address=/www.apple.com/192.168.8.1
address=/connectivitycheck.gstatic.com/192.168.8.1
address=/clients3.google.com/192.168.8.1
address=/www.msftconnecttest.com/192.168.8.1
EOF

/etc/init.d/dnsmasq restart
```

The HTTP server responds to requests on these domains with a `302` redirect to `/connected.html` (never `204`), which keeps iOS in captive-portal mode and suspends Private Relay for the entire session. Do NOT add wildcard DNS entries (`address=/#/`) — those intercept all traffic and break general internet access.

### Step 7: Set Up Port Forwarding

Forward standard ports to the audio server — **targeted to the router's IP only** so internet traffic passes through normally:

```bash
# Add targeted port forwarding rules
cat >> /etc/firewall.user << 'EOF'
# Port forwarding for tour-audio-server (targeted to router IP only)
iptables -t nat -A PREROUTING -i br-lan -d 192.168.8.1 -p tcp --dport 80  -j REDIRECT --to-ports 8888
iptables -t nat -A PREROUTING -i br-lan -d 192.168.8.1 -p tcp --dport 443 -j REDIRECT --to-ports 3000
EOF

# Apply immediately
iptables -t nat -A PREROUTING -i br-lan -d 192.168.8.1 -p tcp --dport 80  -j REDIRECT --to-ports 8888
iptables -t nat -A PREROUTING -i br-lan -d 192.168.8.1 -p tcp --dport 443 -j REDIRECT --to-ports 3000

# Verify rules
iptables -t nat -L PREROUTING -n -v | grep REDIRECT
```

**Why `-d 192.168.8.1`:** This restricts redirection to traffic destined for the router itself. Without this, ALL internet traffic on port 80/443 gets intercepted, breaking internet access for connected devices.

### Step 8: Enable NTP Auto-Sync

The router has no battery-backed clock and resets to the past after a reboot. NTP sync is required for SSL certificate validation to succeed:

```bash
uci set system.ntp.enabled=1
uci set system.ntp.enable_server=0
uci commit system
/etc/init.d/sysntpd restart
```

This ensures the router's clock is always correct on boot, preventing SSL handshake failures.

---

## Performance Optimizations for 40+ Concurrent Users

The system has been optimized to handle 50+ simultaneous connections with 0% packet loss. These optimizations are **critical** for production deployment.

### Overview of Optimizations

| Component | Key Optimizations | Result |
|-----------|-------------------|--------|
| **Server** | 64KB backpressure, debounced broadcasts | 0% skip rate @ 52 clients |
| **Guide App** | No client-side throttling, tunable capture buffer (1024/2048/4096) | Continuous audio stream |
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

**WiFi bands are split into two SSIDs:**
- **Tourists → 2.4GHz** — better range and wall/crowd penetration; more than enough bandwidth for audio
- **Guides → 5GHz** — less congestion from tourist devices; guide is usually close to the router

Router 1 (legacy) uses `EF-TOUR-001` (tourist) and `EF-TOUR-GUIDE` (guide). Additional fleet routers get numbered SSIDs (`EF-TOUR-002` / `EF-TOUR-GUIDE-002`, etc.) — see **Router Provisioning at Scale** for the naming convention. All routers share the same WiFi password (see `secrets.txt`).

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

**1. Tunable Capture Buffer Size (server default 2048, field-tuned from an original 1024)**
```javascript
// Buffer size comes from /api/audio-config — see "Dynamic Audio Configuration"
// below — not a hardcoded constant, so a TD can change it from the admin
// portal without a code redeploy.
scriptProcessor = audioContext.createScriptProcessor(audioConfig.bufferSize, 1, 1);
```
- **Impact:** Stable continuous audio capture; larger sizes trade a little latency for fewer inter-chunk seams
- **Rationale:** 512 samples too small, caused processing gaps; 2048 has tested best for audio clarity with 40+ listeners (see [Dynamic Audio Configuration](#dynamic-audio-configuration))

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
3. Connect your phone to **`EF-TOUR-GUIDE`** (5GHz — guide network)
4. Open Safari and go to `https://audio.eftours.travel/guide.html`
   - Or tap the EF logo **5 times** on the captive portal welcome screen to jump there directly
5. Tap **Start Broadcasting**
6. Speak — your voice streams to all connected tourists
7. Only one guide can broadcast at a time; a second guide sees listener count and metrics in watch mode

> ⚠️ **Must use full Safari/Chrome** — the captive portal mini-browser blocks microphone access.

### For Tourists:
1. Connect to **`EF-TOUR-001`** (2.4GHz — tourist network)
2. The captive portal pops up automatically — tap **Open Tour Audio** on the connected screen
3. If the popup doesn't appear, open any browser and go to `audio.eftours.travel`
4. Tap **Start Listening**
5. Use headphones for best experience; adjust the on-screen volume slider as needed
6. Tap the **lock icon** to lock the screen and prevent accidental taps while the phone is in your pocket — tap the screen 3 times to unlock
7. Tap the **camera icon** (top-right) to take a photo or record a video without leaving the tour audio page — see **Camera Widget** below

**Note:** No security warnings — the site uses a trusted CA-signed certificate. The screen stays on while listening (Wake Lock).

### Camera Widget

Tourists can snap photos or record short videos from the listener page itself, so they never have to leave the live audio stream to use their phone's own camera app:

- **Open:** Tap the camera icon (top-right of the listener screen)
- **Photo/Video toggle:** Switch modes with the on-screen **PHOTO** / **VIDEO** buttons (VIDEO mode only appears if the browser supports `MediaRecorder`)
- **Front/back camera flip:** Shown automatically only on devices with both a front and back camera; the front-camera preview is mirrored (selfie-style) but the saved photo/video never is
- **Zoom:** On-screen +/- buttons or pinch-to-zoom; uses the camera's native optical/hybrid zoom where supported (e.g. Android Chrome), falling back to a CSS scale elsewhere (e.g. iOS Safari)
- **Camera switching is disabled mid-recording** — stop the recording first, then flip



### Live Captions (Beta)

The guide app can generate real-time captions that display on every listener's screen:

- **Enable:** Guide taps **CC** (visible while broadcasting) to turn captions on/off
- **Desktop guides:** Uses the browser's built-in Web Speech API
- **Mobile guides:** Uses on-device Whisper (`whisper-tiny.en` via Transformers.js) running in a Web Worker — mobile browsers can't share the microphone between `getUserMedia` and the Web Speech API, so Whisper taps the existing audio stream instead. Model files are self-hosted on the router (`/models/`) so this works with no internet pass-through.
- **Listener side:** Captions appear in a bar at the bottom of the screen with scroll-back (last 8 lines) and a mute toggle
- **First use per guide device:** Whisper model download/load can take a few seconds up to ~90s depending on the device; subsequent toggles on the same page load are instant (model stays warm)

---

## Network Diagram

```
                    +------------------------------------------+
                    |   GL-MT3000 Router (192.168.8.1)         |
                    |                                          |
                    |  [WiFi Access Point]                     |
                    |  [Node.js Dual Server]                   |
                    |    - HTTP  (8888): Welcome/redirect      |
                    |    - HTTPS (3000): Audio App             |
                    |  [dnsmasq: audio.eftours.travel→router]  |
                    |  [Port Forwarding: 80→8888, 443→3000]    |
                    |    (targeted to 192.168.8.1 only)        |
                    |  [Let's Encrypt cert via ef-update.sh]   |
                    +-----+--------------------+---------------+
                          |                    |
                     WiFi |          WiFi      | Uplink (hotel/hotspot)
                          |                    |
                    +-----+--+  +---+------+   +------------------+
                    | Guide  |  | Tourist  |   | api.eftours.travel|
                    | Phone  |  | Phones   |   | (cert renewal API)|
                    | (mic)  |  | (listen) |   +------------------+
                    +--------+  +----------+
```

No Raspberry Pi. No ethernet cables. One device does everything.

---

## Troubleshooting

### Can't access audio.eftours.travel
- Check DNS entry: `nslookup audio.eftours.travel 127.0.0.1` (should return 192.168.8.1)
- Verify port forwarding: `iptables -t nat -L PREROUTING -n -v | grep REDIRECT`
- Check both servers are running: `ps | grep node`
- Test direct access: `http://192.168.8.1:8888/` (HTTP) and `https://192.168.8.1:3000/` (HTTPS)

### "Update Device" fails — "Could not reach update server"
- Confirm the router is connected to internet (Step 1 in admin portal)
- Check the cert token file exists: `cat /etc/ef-cert-token`
- Sync the clock manually: `ntpd -q -p pool.ntp.org` (SSL fails if clock is wrong)
- Test API directly: `curl -s -H "Authorization: Bearer $(cat /etc/ef-cert-token)" https://api.eftours.travel/router-cert`

### Internet not working for devices connected to the router
- Verify iptables rules are targeted (not intercepting all traffic):
  ```bash
  iptables -t nat -L PREROUTING -n -v | grep REDIRECT
  # Should show: -d 192.168.8.1 on all REDIRECT rules
  ```
- Check no wildcard DNS entries exist: `grep 'address=/#/' /etc/dnsmasq.conf` (should return nothing)
- Confirm router's uplink is connected: `ubus call repeater info 2>/dev/null`

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

### Changing the WiFi Network Name

For a fleet router, don't rename it freely — use `setup-router.sh <router-number>` (see **Router Provisioning at Scale**) so the SSID follows the fleet's conflict-free numbering convention. For a one-off, non-fleet single-router setup:

1. SSH into the router or use the admin panel at `192.168.8.1`
2. Go to Wireless settings
3. Change SSID to your preferred name (e.g., `EF-Tour-Rome`)

### Dynamic Audio Configuration

The guide's capture buffer size can be tuned in the field without redeploying code:

- **Where:** Admin portal → **Advanced Audio Settings** (collapsed panel, tap to expand)
- **Setting:** Buffer size — 1024 / 2048 / 4096 samples per chunk
  - Smaller = lower latency, more packets/sec, but a dropped packet loses less audio
  - Larger = fewer inter-chunk seams (can reduce crackle), but a dropped packet loses more audio at once
  - **2048 is the current field-tested recommendation** — clearest audio with 40+ listeners
- **How it works:** Saved to `audio-config.json` on the router (persists across reboots and "Update Device"). `guide.html` fetches it on page load and again each time broadcasting starts — the guide must restart broadcasting for a change to take effect.
- **API:** `GET /api/audio-config` (public, read by guide/listener pages) and `POST /api/admin/audio-config` (admin-only, used by the dial)

### Monitoring Connected Clients

Visit `https://audio.eftours.travel/health` to see:
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

## Router Provisioning at Scale

As the fleet grows beyond a handful of routers, two complementary approaches keep new-router setup fast and consistent:

### WiFi Naming Convention

Every router in the fleet gets a unique number, which determines its WiFi SSIDs — this prevents two routers used near each other (same hotel, same city, adjacent tour groups) from ever broadcasting the same network name. Duplicate SSIDs aren't just confusing in a WiFi picker — phones remember networks by SSID+password and will silently auto-join whichever same-named AP is strongest, which can put a traveler on the wrong tour's router.

| Router # | Tourist SSID (2.4GHz) | Guide SSID (5GHz) |
|---|---|---|
| 1 (legacy) | `EF-TOUR-001` | `EF-TOUR-GUIDE` *(unnumbered)* |
| 2 | `EF-TOUR-002` | `EF-TOUR-GUIDE-002` |
| 3 | `EF-TOUR-003` | `EF-TOUR-GUIDE-003` |
| N | `EF-TOUR-0NN` | `EF-TOUR-GUIDE-0NN` |

Router 1 keeps its original unnumbered guide SSID rather than being renamed for consistency (no benefit to re-pairing already-working guide devices). All routers **N ≥ 2** get both SSIDs numbered, zero-padded to 3 digits so names still sort correctly once the fleet passes 9 routers. The WiFi password stays identical across the whole fleet (see `secrets.txt`) — only the SSID varies.

**Before provisioning a new router:** check the Fleet Registry in `secrets.txt` for the next unused number, and add a row for it once provisioning is done.

### Option 1: Bootstrap Script (`setup-router.sh <router-number>`)

Run once on a freshly flashed GL.iNet router, after Steps 1–3 of the Setup Guide (initial WiFi config, Node.js install, and `scp -r tour-audio-server` to `/root/`) — the script needs the app files already on the router since it runs `npm install` from within that directory. Pass the router's assigned fleet number as the only argument, e.g. `./setup-router.sh 2`. It then sets the unique WiFi SSIDs above, and automates the temporary self-signed SSL bootstrap (so HTTPS can start before the real cert is installed), dependency install, auto-start service setup, DNS (including the RFC 8910 captive-portal DHCP option for Android), timezone, and port-forwarding. Keep this script in sync with the Setup Guide steps above — it's the source of truth for "what a router needs."

**Still manual after the script runs:**
- Adding the new router + its SSIDs to the Fleet Registry in `secrets.txt`
- Placing `/etc/ef-cert-token` (one-time, IT-managed secret — same token works across all routers)
- Running "Update Device" once in the admin portal to fetch the real Let's Encrypt cert (replaces the script's temporary self-signed cert)
- NTP auto-sync setup (Step 8) and DHCP/WiFi optimizations (`optimize-dhcp.sh` / `optimize-wifi.sh`) — run separately since they're independent, tour-size-dependent tunables

### Option 2: Golden Image (`sysupgrade` backup/restore)

Once one router is fully configured and verified end-to-end, take a backup and restore it onto identical hardware:

```bash
# On a known-good, fully-configured router:
ssh root@192.168.8.1 "sysupgrade -b /tmp/backup.tar.gz"
scp root@192.168.8.1:/tmp/backup.tar.gz ./golden-image-$(date +%Y%m%d).tar.gz

# On a freshly flashed router of the SAME model/firmware version:
scp golden-image-YYYYMMDD.tar.gz root@192.168.8.1:/tmp/
ssh root@192.168.8.1 "sysupgrade -r /tmp/golden-image-YYYYMMDD.tar.gz"
```

This restores UCI config (WiFi, DHCP, firewall, dnsmasq) instantly. It does **not** include `/root/tour-audio-server` unless that path is added to `sysupgrade`'s backup list (`/etc/sysupgrade.conf`) — safest to still deploy the app files fresh via `scp` after restoring, so the app code always matches this repo rather than whatever was on the router when the image was captured.

**Important:** the image also clones the source router's WiFi SSIDs. After restoring onto a new unit, re-run just the SSID step for its assigned number (`uci set wireless.<iface>.ssid=...` per the naming convention above, then `uci commit wireless && wifi reload`) — or re-run `setup-router.sh <router-number>` in full, which is idempotent and safe to repeat.

**Recommendation:** Treat the bootstrap script as the documented, versioned procedure — fix it whenever the Setup Guide changes. Cut a fresh golden image from a script-provisioned router whenever onboarding several routers at once; it turns a ~15 minute manual setup into a ~2 minute restore, with the script as the fallback/reference when an image goes stale or hardware doesn't match.

**Per-router values that stay unique regardless of method:** WiFi SSIDs (see naming convention above). The cert token, admin token, WiFi password, and app code are identical across the fleet by design.

---

## File Structure

```
tour-audio-server/
├── server.js                          # Main server (dual HTTP/HTTPS, admin API, captive portal)
├── admin-qr.js                        # CLI: prints/regenerates the admin token URL + QR image link
├── load-test.js                       # WebSocket load-testing tool (see Load Testing)
├── ef-update.sh                       # Certificate update script (run by admin portal)
├── setup-router.sh                    # Router bootstrap automation (SSID naming, SSL bootstrap, deps, DNS, port-forwarding)
├── optimize-dhcp.sh                   # Router DHCP optimization script
├── optimize-wifi.sh                   # Router WiFi optimization script
├── debug-cert.sh / diagnose-relay.sh  # Router-side diagnostic scripts (cert format / DNS+Private Relay checks)
├── package.json / package-lock.json   # Node.js dependencies
├── README.md                          # This file
├── HELP-GUIDE.md, WAKE-LOCK-GUIDE.md, IOS-SILENT-MODE-LIMITATION.md,
│   SECURITY-RELIABILITY-AUDIT.md      # Supporting guides (see repo root)
├── secrets.txt                        # Router/WiFi/admin passwords + Fleet Registry (not committed to source control)
├── audio-config.json                  # Saved dial settings (buffer size) — created on first save, persists across reboots
├── ssl/
│   ├── server.crt                     # Let's Encrypt certificate (installed via Update Device)
│   ├── server.key                     # EC private key (installed via Update Device)
│   └── backup/                        # Timestamped backups of previous certs
├── models/Xenova/                     # Self-hosted whisper-tiny.en model files for on-device captions
└── public/
    ├── welcome.html                   # Captive portal splash page (HTTP) — 5-tap logo → guide app
    ├── connected.html                 # Captive portal "you're connected" page (shown by OS probe intercept)
    ├── index.html                     # Listener interface (HTTPS) — dark blue, lock screen, wake lock, camera widget, captions
    ├── guide.html                     # Tour guide broadcast interface (HTTPS) — pink, broadcaster lock, watch mode, dials, captions
    ├── admin.html                     # Audio System Admin portal (Wi-Fi connect, cert update, Advanced Audio Settings dials)
    ├── waiting-room.html              # Waiting room for staggered connections
    ├── whisper-worker.js              # On-device speech-to-text (Whisper via Transformers.js) — mobile guide captions
    ├── transformers.min.js            # Transformers.js library (bundled, no CDN dependency)
    ├── ort-wasm.wasm / ort-wasm-simd.wasm  # ONNX Runtime WASM backends for Whisper inference
    ├── manifest.json / guide-manifest.json # PWA manifests (listener / guide "Add to Home Screen")
    ├── sw.js                          # Service worker (PWA offline shell)
    ├── icons/                         # PWA home-screen icons (listener + guide variants)
    └── EF-Logos_EF Logo Pink.png      # EF Tours logo
```

## Features

### Current Features

**Audio Streaming:**
- ✅ **Real-time audio streaming** via WebSocket (WSS)
- ✅ **Low latency** (~200-300ms end-to-end)
- ✅ **PCM Int16 encoding** for reliable real-time transmission
- ✅ **16kHz sample rate** (good voice quality, efficient bandwidth)
- ✅ **Tunable capture buffer** (1024/2048/4096 samples, field-adjustable — see [Dynamic Audio Configuration](#dynamic-audio-configuration))
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
- ✅ **CA-signed HTTPS certificate** (Let's Encrypt, no browser warnings)
- ✅ **Custom domain** (`audio.eftours.travel`) resolved locally via dnsmasq
- ✅ **Automatic cert renewal** via "Update Device" in admin portal
- ✅ **Dual-server architecture** (HTTP on 8888 redirects to HTTPS, HTTPS on 3000)
- ✅ **Targeted port forwarding** (80→8888, 443→3000, router IP only)
- ✅ **NTP auto-sync** on boot (prevents SSL failures after reboot)
- ✅ **Optimized DHCP** (100 client pool, rapid commit)
- ✅ **Optimized WiFi** (60 clients/band, WMM QoS)
- ✅ **Dual-band support** (2.4GHz + 5GHz WiFi 6)
- ✅ **Internet passthrough** — connected devices can browse normally when router has uplink

**User Experience:**
- ✅ **iOS Safari compatibility** using Web Audio API
- ✅ **Wake Lock API** prevents screen lock during broadcast (guide) and playback (listeners)
- ✅ **iOS silent mode detection** with user-friendly warnings
- ✅ **Volume control** for listeners
- ✅ **Audio visualization** with waveforms for both guide and listeners
- ✅ **Broadcast indicators** with pulsing dots showing active transmission
- ✅ **Microphone test** feature for guide
- ✅ **Audio test** feature for listeners
- ✅ **EF brand styling** with color-coded pages (dark blue for listeners, pink for guide)
- ✅ **Listener lock screen** — 3-tap unlock, animated equalizer, back-button trap, pocket protection
- ✅ **Broadcaster locking** — only one guide can broadcast at a time; second guide enters watch mode
- ✅ **Watch mode** — second guide sees live listener count and server metrics without interrupting broadcast
- ✅ **Guide easter egg** — tap the EF logo 5× on the welcome/captive portal page to navigate to the guide app
- ✅ **Captive portal mini-browser detection** — guide app detects restricted browser and shows "Open in Safari" instructions
- ✅ **Waiting room page** for staggered connections
- ✅ **Live captions (beta)** — guide-toggled real-time subtitles (Web Speech API on desktop, on-device Whisper on mobile) shown on listener screens
- ✅ **In-page camera widget** — travelers can take photos or record video without leaving the listener page; front/back camera toggle, pinch-to-zoom/native zoom, and photo/video mode toggle (see [Camera Widget](#camera-widget))

**Captive Portal:**
- ✅ **Host-based OS probe intercept** — `captive.apple.com`, `www.apple.com`, `connectivitycheck.gstatic.com`, `clients3.google.com`, `www.msftconnecttest.com` all return `302` (never `204`)
- ✅ **iCloud Private Relay suppression** — always-on `302` keeps iOS in captive-portal mode and suspends Private Relay in full Safari
- ✅ **Connected page** (`/connected.html`) — dark branded page with "Open Tour Audio" button; no auto-redirect
- ✅ **Split SSID** — tourists on `EF-TOUR-001` (2.4GHz), guides on `EF-TOUR-GUIDE` (5GHz)

**Admin:**
- ✅ **Fixed admin token** — URL never changes: `https://audio.eftours.travel/admin?token=EFAdminAudio02141`
- ✅ **8-hour session cookie** — log in once, stay logged in for the day
- ✅ **Dynamic Audio Configuration** — Advanced Audio Settings panel lets a TD tune the guide's capture buffer size (1024/2048/4096) in the field without a code redeploy; setting persists in `audio-config.json` across reboots and updates

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
| **Buffer Size** | 2048 samples (server default) | Tunable via admin dial (1024/2048/4096); 2048 currently recommended |
| **Initial Buffer** | 100ms | Prevents underruns |
| **Backpressure** | 64KB | ~2 seconds of audio |

### Known Limitations
- **iOS Silent Mode:** Audio won't play through speakers if silent switch is on (use headphones)
- **Wake Lock Support:** Requires iOS 16.4+ (older devices need manual auto-lock adjustment)
- **Certificate Renewal:** Cert expires every ~90 days; TD must run "Update Device" with internet access to renew
- **Clock Reset:** If NTP sync fails after reboot, SSL certificate validation may fail until clock syncs
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

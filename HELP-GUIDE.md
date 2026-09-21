# EF Tour Audio — Help Guide

Quick reference for connecting to the router, updating software and certificates, and getting the best audio quality.

---

## 1. Connecting to the Router

### Admin Web Panel
Open a browser on a device connected to the tour WiFi and go to:
```
http://192.168.8.1
```
Log in with the router admin password from `secrets.txt`.

### SSH (for command-line access)
```powershell
ssh root@192.168.8.1
# Password: see secrets.txt
```

### WiFi Networks
| Network | Band | Who connects |
|---|---|---|
| `EF-TOUR-001` | 2.4 GHz | Travelers |
| `EF-TOUR-GUIDE` | 5 GHz | Guides |

Password for both networks: see `secrets.txt`.

---

## 2. Updating the Software

### Deploy HTML/JS files (no restart needed)
```powershell
# All client files at once
scp public/*.html public/whisper-worker.js root@192.168.8.1:/root/tour-audio-server/public/

# Or specific files
scp public/guide.html public/index.html root@192.168.8.1:/root/tour-audio-server/public/
```
Just refresh the browser after — no server restart required.

### Deploy server.js (restart required)
```powershell
scp server.js root@192.168.8.1:/root/tour-audio-server/
ssh root@192.168.8.1 "kill $(pgrep -f server.js); cd /root/tour-audio-server && nohup node server.js > /root/server.log 2>&1 &"
```

### Check if the server is running
```powershell
ssh root@192.168.8.1 "ps | grep node"
```
You should see `/usr/bin/node /root/tour-audio-server/server.js`.

### View live server logs
```powershell
ssh root@192.168.8.1 "tail -f /root/server.log"
```

### Check system health
Visit in a browser (on the tour WiFi):
```
https://audio.eftours.travel/health
```
Or via SSH:
```bash
curl -sk https://127.0.0.1:3000/health
```

---

## 3. Updating the SSL Certificate

The SSL certificate for `audio.eftours.travel` expires every ~90 days. Renewal takes about 60 seconds and requires a temporary internet connection.

### Via the Admin Portal (recommended)
1. Connect the router to internet — use a hotel WiFi or phone hotspot via the admin portal's **"Connect to WiFi"** step
2. Open the admin portal on a device connected to the tour WiFi:
   ```
   https://audio.eftours.travel/admin?token=EFAdminAudio02141
   ```
3. Tap **Update Device Now**
4. Watch the progress steps — it fetches, validates, backs up, and installs the new cert automatically
5. The server restarts itself; travelers reconnect within a few seconds

### How to tell the cert is expiring
- The admin portal shows **days remaining** on the certificate
- Browsers will show a security warning if the cert has already expired
- Check via SSH: `openssl x509 -noout -enddate -in /root/tour-audio-server/ssl/server.crt`

### If "Update Device" fails
```bash
ssh root@192.168.8.1

# Confirm the router has internet
ping -c 3 8.8.8.8

# Sync the clock (SSL fails if clock is wrong after a reboot)
ntpd -q -p pool.ntp.org

# Check the API token is in place
ls /etc/ef-cert-token

# Retry the update manually
sh /root/tour-audio-server/ef-update.sh
```

---

## 4. Audio Quality Tips

### Guide setup
- **Use iOS Voice Isolation** — before broadcasting, open Control Center → tap the mic icon → select **Voice Isolation**. This is Apple's ML noise filter and is more powerful than any software setting in the app.
- **Hold the phone naturally** — 6–12 inches from your mouth works well. Avoid holding it right up to your face (causes breath pops) or pointing the mic directly into wind.
- **Use the sensitivity slider** — in the guide app, adjust the slider before broadcasting. Higher = picks up quieter voices; lower = rejects more background noise. Start around 6–7 and adjust to taste.
- **Wear headphones while testing** — prevents acoustic echo if you also have the listener page open on the same device.

### Closed Captions (CC)
- CC uses on-device AI (Whisper) and works offline — no internet needed.
- It runs best when the guide speaks in clear, complete sentences with brief natural pauses.
- CC adds CPU load. If audio quality drops when CC is on, try disabling it.

### Listener setup
- **Check silent mode** — on iPhone, flip the physical silent switch off (orange stripe = silent). Audio will not play through the speaker in silent mode.
- **Use headphones** — significantly improves clarity in noisy outdoor environments.
- **Tap the lock icon** — locks the screen to prevent accidental taps while the phone is in a pocket. Tap 3 times to unlock.

### Camera widget (front/back toggle)
Quick check after any deploy that touches `public/index.html`'s camera code:
1. Tap the camera icon → the flip button (top-left, circular-arrow icon) should appear on any phone with both a front and back camera. On single-camera devices it should stay hidden.
2. Tap the flip button — camera should switch between back and front, front-camera preview should be mirrored (selfie-style), and zoom should reset to 1×.
3. Take a photo with the front camera and confirm the **saved** photo is *not* mirrored (the live preview mirrors; the captured image never should).
4. Switch to Video mode, start recording, then tap the flip button — it should show a "Stop recording before switching cameras" toast instead of switching (track-swap mid-recording isn't supported), then flip normally once recording stops.
5. Deny camera permission (or test on a device that has none) — the overlay should show "Camera access denied" with the flip button hidden, not a broken/blank flip control.

---

## 5. Performance Tips

### Before the tour
- **Charge the router battery pack** fully the night before.
- **Run a quick test** — guide starts broadcasting, 2–3 people open the listener page and confirm audio plays. Check the Skip Rate in the guide app metrics (should be 0%).
- **Stagger connections** — if 40+ people are joining at once, ask them to connect in groups of 10–15 with 30 seconds between groups to avoid overwhelming the DHCP server.

### During the tour
- **Keep the router close** — within 30 meters of the furthest traveler when possible. 2.4 GHz has good range but walls and crowds reduce it.
- **Watch the guide app metrics** — all dots should be green. If Skip Rate climbs above 5%, move the router or reduce the number of connected devices.
- **One guide broadcasts at a time** — a second guide who opens guide.html automatically enters Watch Mode and can see listener count and metrics without interrupting the broadcast.

### WiFi tips
- The router's 2.4 GHz band supports up to 60 travelers. If the group is larger, the system still works but WiFi may become congested.
- 2.4 GHz penetrates crowds and walls better than 5 GHz — travelers should always connect to `EF-TOUR-001`.
- If travelers are dropping WiFi frequently, make sure the battery pack is supplying enough power (router needs ~5V/2A).

---

## 6. Quick Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Travelers can't find the WiFi | Router not booted | Wait 30–45 s after powering on |
| `audio.eftours.travel` won't load | Server not running | SSH in and restart the server (§2) |
| Security warning in browser | SSL cert expired | Run Update Device in admin portal (§3) |
| Audio cutting out | Too many devices on one band | Move some travelers to a different spot or stagger connections |
| Severe lag (3+ seconds) | Jitter buffer inflated | Guide should stop and restart broadcasting; travelers refresh the page |
| No audio on iPhone | Silent mode is on | Flip the physical silent switch on the side of the phone |
| High skip rate in metrics | Listener tab open on guide's device | Close the listener tab on the guide's phone |
| Echo / hearing own voice | Listener page open on guide's device | The app now auto-mutes it; close the tab to be safe |
| CC showing wrong words | Background noise triggering Whisper | Lower the sensitivity slider; use Voice Isolation |
| Camera flip button missing | Device only has one camera, or `enumerateDevices()` failed | Expected on single-camera devices; otherwise reopen the camera to retry detection |
| Camera flip does nothing while recording | Blocked intentionally (track-swap mid-recording isn't reliable) | Stop recording first, then flip |
| Colored bar behind home indicator in Lock Mode | `<html>` background not synced to lock/battery-save state | Fixed 2026-09 via `setRootBackground()`; if it reappears, check that every `setThemeColor()` call site also calls `setRootBackground()` |

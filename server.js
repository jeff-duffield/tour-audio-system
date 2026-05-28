const express = require('express');
const http = require('http');
const https = require('https');
const fs = require('fs');
const os = require('os');
const crypto = require('crypto');
const { exec, spawn } = require('child_process');
const WebSocket = require('ws');
const path = require('path');

const app = express();
const httpApp = express();

// SSL/TLS certificate options - using local ssl directory
const sslOptions = {
    key: fs.readFileSync(path.join(__dirname, 'ssl', 'server.key')),
    cert: fs.readFileSync(path.join(__dirname, 'ssl', 'server.crt'))
};

const server = https.createServer(sslOptions, app);
const wss = new WebSocket.Server({
    server,
    // Performance optimizations for 40+ clients
    perMessageDeflate: false,  // Disable compression for lower latency
    maxPayload: 100 * 1024,    // 100KB max message size
    clientTracking: true
});

// Redirect HTTPS root to HTTP welcome page
app.get('/', (req, res) => {
    res.redirect('http://audio.eftours.travel/');
});

// ============================================
// Admin (TD) Authentication & Routes
// Must be registered BEFORE express.static
// so /admin.html cannot be fetched anonymously
// ============================================

const ADMIN_TOKEN_PATHS = [
    '/etc/ef-admin-token',
    path.join(__dirname, 'ef-admin-token')
];
let adminToken = null;
let adminTokenPath = null;

function loadOrCreateAdminToken() {
    for (const p of ADMIN_TOKEN_PATHS) {
        try {
            if (fs.existsSync(p)) {
                const t = fs.readFileSync(p, 'utf8').trim();
                if (t) { adminToken = t; adminTokenPath = p; return; }
            }
        } catch (e) { /* try next */ }
    }
    adminToken = crypto.randomBytes(24).toString('hex');
    for (const p of ADMIN_TOKEN_PATHS) {
        try {
            fs.writeFileSync(p, adminToken + '\n', { mode: 0o600 });
            adminTokenPath = p;
            return;
        } catch (e) { /* try next */ }
    }
    console.warn('Admin token generated in memory only (could not write to disk)');
}
loadOrCreateAdminToken();

// Session store: sessionId -> expiresAt (ms)
const adminSessions = new Map();
const ADMIN_SESSION_MS = 8 * 60 * 60 * 1000; // 8 hours

function makeAdminSession() {
    const id = crypto.randomBytes(32).toString('hex');
    adminSessions.set(id, Date.now() + ADMIN_SESSION_MS);
    return id;
}

function hasValidAdminSession(req) {
    const cookie = req.headers.cookie || '';
    const m = cookie.match(/(?:^|;\s*)ef_admin_session=([a-f0-9]+)/);
    if (!m) return false;
    const expires = adminSessions.get(m[1]);
    if (!expires || expires < Date.now()) {
        adminSessions.delete(m[1]);
        return false;
    }
    return true;
}

// Purge expired sessions hourly
setInterval(() => {
    const now = Date.now();
    for (const [id, exp] of adminSessions) {
        if (exp < now) adminSessions.delete(id);
    }
}, 60 * 60 * 1000);

function adminAuth(req, res, next) {
    if (process.env.EF_ADMIN_NO_AUTH === '1') return next();
    if (hasValidAdminSession(req)) return next();

    // QR code entry: ?token=... sets a session cookie, then redirect to clean URL
    const token = req.query.token;
    if (token && adminToken && token === adminToken) {
        const sid = makeAdminSession();
        res.setHeader('Set-Cookie',
            `ef_admin_session=${sid}; HttpOnly; Path=/; Max-Age=${ADMIN_SESSION_MS / 1000}; SameSite=Lax`);
        return res.redirect(req.path);
    }

    if (req.path.startsWith('/api/')) {
        return res.status(401).json({ error: 'unauthorized' });
    }
    return res.status(401).send(`<!DOCTYPE html>
<html><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Not authorized</title>
<style>body{margin:0;font-family:-apple-system,BlinkMacSystemFont,sans-serif;background:linear-gradient(135deg,#003C64,#001a2e);color:#fff;min-height:100vh;display:flex;align-items:center;justify-content:center;padding:24px;text-align:center}
.card{max-width:360px}h1{font-size:22px;margin:0 0 12px}p{opacity:.85;line-height:1.5;font-size:15px;margin:0}</style>
</head><body><div class="card"><h1>🔒 Tour Director access only</h1><p>Scan the QR code on your EF admin card to continue.</p></div></body></html>`);
}

// JSON body parser scoped to admin API
app.use('/api/admin', express.json());

// Serve the admin page (auth gated)
app.get(['/admin', '/admin.html'], adminAuth, (req, res) => {
    res.sendFile(path.join(__dirname, 'public', 'admin.html'));
});

// Serve QR code for the admin URL — renders an img tag pointing at api.qrserver.com
// The page itself is auth-gated but the <img> src is fetched by the browser after load.
// /admin/qr  — authenticated route (same session cookie), shows QR on-screen
app.get('/admin/qr', adminAuth, (req, res) => {
    const proto = 'https';
    const host  = req.hostname;
    const adminUrl = `${proto}://${host}/admin?token=${adminToken}`;
    const qrSrc = 'https://api.qrserver.com/v1/create-qr-code/?size=300x300&data=' + encodeURIComponent(adminUrl);
    res.send(`<!DOCTYPE html>
<html><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Admin QR Code</title>
<style>
body{margin:0;font-family:-apple-system,sans-serif;background:#003C64;display:flex;align-items:center;
  justify-content:center;min-height:100vh;padding:24px;box-sizing:border-box;text-align:center}
.card{background:#fff;border-radius:16px;padding:28px 24px;max-width:400px;width:100%}
h1{color:#003C64;font-size:18px;margin:0 0 4px}
.sub{color:#666;font-size:13px;margin:0 0 16px;line-height:1.5}
img{border:2px solid #E3EBED;border-radius:8px;width:220px;height:220px}
.divider{display:flex;align-items:center;gap:10px;margin:18px 0 14px;color:#aaa;font-size:12px}
.divider::before,.divider::after{content:'';flex:1;border-top:1px solid #ddd}
.url-box{background:#F0F6FF;border:1px solid #c0d8ee;border-radius:8px;padding:10px 12px;
  text-align:left;margin-bottom:10px}
.url-label{font-size:11px;color:#888;font-weight:600;letter-spacing:.04em;text-transform:uppercase;margin-bottom:4px}
.url-text{font-family:monospace;font-size:12px;word-break:break-all;color:#003C64;line-height:1.5}
.copy-btn{width:100%;padding:10px;background:#003C64;color:#fff;border:none;border-radius:8px;
  font-size:14px;font-weight:600;cursor:pointer;margin-top:2px}
.copy-btn:active{background:#002a47}
.note{font-size:11px;color:#aaa;margin-top:14px;line-height:1.6}
#err{display:none;color:#8B2A1C;font-size:13px;margin-top:8px;background:#FFF0F0;padding:8px;border-radius:6px}
</style>
</head><body><div class="card">
<h1>TD Admin Access</h1>
<p class="sub">📱 On a phone — scan the QR code with your camera<br>💻 On a computer — copy the URL below</p>
<img src="${qrSrc}" alt="Admin QR code" onerror="this.style.display='none';document.getElementById('err').style.display='block'">
<div id="err">Could not load QR image — requires internet to render. The URL below always works.</div>
<div class="divider">or use the URL</div>
<div class="url-box">
  <div class="url-label">Admin URL</div>
  <div class="url-text" id="adminUrl">${adminUrl}</div>
</div>
<button class="copy-btn" onclick="copyUrl()">📋 Copy URL</button>
<div class="note">Paste into any browser on the tour Wi-Fi network.<br>Session expires in 8 hours.</div>
</div>
<script>
function copyUrl() {
  const url = document.getElementById('adminUrl').textContent;
  navigator.clipboard.writeText(url).then(() => {
    const btn = document.querySelector('.copy-btn');
    btn.textContent = '✅ Copied!';
    setTimeout(() => btn.textContent = '📋 Copy URL', 2000);
  }).catch(() => {
    // Fallback for older browsers / non-HTTPS
    const ta = document.createElement('textarea');
    ta.value = url; ta.style.position = 'fixed'; ta.style.opacity = '0';
    document.body.appendChild(ta); ta.select();
    document.execCommand('copy');
    document.body.removeChild(ta);
    const btn = document.querySelector('.copy-btn');
    btn.textContent = '✅ Copied!';
    setTimeout(() => btn.textContent = '📋 Copy URL', 2000);
  });
}
</script>
</body></html>`);
});

// ---- Shell helpers for admin API -----------------------------------------
function sh(cmd, timeoutMs = 3000) {
    return new Promise((resolve) => {
        exec(cmd, { timeout: timeoutMs }, (err, stdout, stderr) => {
            resolve({ err, stdout: String(stdout || ''), stderr: String(stderr || '') });
        });
    });
}

async function checkInternet() {
    // Ping a reliable anycast DNS — quick, minimal payload
    const { err } = await sh('ping -c 1 -W 2 1.1.1.1', 3500);
    return !err;
}

async function getConnectedSsid() {
    // Ask the GL.iNet repeater daemon for the live connection status first
    const { stdout } = await sh('ubus call repeater status 2>/dev/null', 2000);
    try {
        const s = JSON.parse(stdout);
        if (s && s.ssid) return s.ssid;
    } catch {}
    // Fall back to UCI config (persisted value)
    const r = await sh('uci -q get wireless.wwan.ssid', 1500);
    return r.stdout.trim() || null;
}

function getAppVersion() {
    try {
        return require('./package.json').version || '0.0.0';
    } catch { return '0.0.0'; }
}

function readJsonSafe(p) {
    try {
        return JSON.parse(fs.readFileSync(p, 'utf8'));
    } catch { return null; }
}

// ---- Phase 2: status + internet ------------------------------------------
app.get('/api/admin/status', adminAuth, async (req, res) => {
    const saved = readJsonSafe('/tmp/ef-status.json') || {};
    const [connected, ssid] = await Promise.all([checkInternet(), getConnectedSsid()]);

    // Build response, falling back to sensible defaults when fields are missing
    const currentVersion = getAppVersion();
    const cert = saved.certificate || { valid: true, expiresAt: null, daysRemaining: null };
    const app = saved.app || { currentVersion, latestVersion: null, updateAvailable: false };
    const lastUpdate = saved.lastUpdate || { timestamp: null, outcome: 'never', stepsCompleted: 0 };

    // Derive overall state
    let overall = 'ready';
    if (cert.daysRemaining === null)              overall = 'certmissing'; // never been updated
    else if (cert.daysRemaining <= 0)             overall = 'certexpired';
    else if (cert.daysRemaining <= 7)             overall = 'warning';
    else if (app.updateAvailable)                 overall = 'warning';
    else if (lastUpdate.outcome === 'failed')     overall = 'action';

    res.json({
        overall,
        certificate: cert,
        app: { ...app, currentVersion: app.currentVersion || currentVersion },
        lastUpdate,
        internet: { connected, connectedSsid: connected ? ssid : null }
    });
});

app.get('/api/admin/internet', adminAuth, async (req, res) => {
    const [connected, ssid] = await Promise.all([checkInternet(), getConnectedSsid()]);
    res.json({ connected, ssid: connected ? ssid : null });
});

// Captive portal detection — tries to fetch a known URL that returns 204.
// If we get a redirect, we're behind a portal; the redirect URL is the login page.
app.get('/api/admin/captive-check', adminAuth, async (req, res) => {
    // Use curl with --max-redirs 0 so it doesn't follow the redirect.
    // -w prints http_code and redirect_url on stdout; -o /dev/null discards body.
    const { stdout, err } = await sh(
        'curl -s --max-time 6 --max-redirs 0 ' +
        '-o /dev/null -w "%{http_code} %{redirect_url}" ' +
        'http://detectportal.firefox.com/canonical.html 2>/dev/null',
        8000
    );
    if (err) return res.json({ captive: false });
    const [code, portalUrl] = stdout.trim().split(' ');
    const httpCode = parseInt(code, 10);
    if (httpCode === 200) {
        return res.json({ captive: false });
    }
    if ((httpCode === 301 || httpCode === 302 || httpCode === 303 || httpCode === 307) && portalUrl) {
        return res.json({ captive: true, portalUrl });
    }
    // Any other response (e.g. 000 = no route, timeout) means no internet at all
    return res.json({ captive: false, noInternet: true });
});

// ---- Update endpoint (SSE streaming) -------------------------------------
// Spawns ef-update.sh, streams its tagged stdout lines as SSE events, then
// schedules a service restart after the script exits cleanly.
app.post('/api/admin/update', adminAuth, (req, res) => {
    res.writeHead(200, {
        'Content-Type':  'text/event-stream',
        'Cache-Control': 'no-cache',
        'Connection':    'keep-alive',
        'X-Accel-Buffering': 'no',  // prevent nginx from buffering SSE
    });

    const send = (type, msg) =>
        res.write(`data: ${JSON.stringify({ type, msg })}\n\n`);

    const scriptPath = path.join(__dirname, 'ef-update.sh');
    const child = spawn('/bin/sh', [scriptPath], { env: process.env });
    let buf = '';

    child.stdout.on('data', (chunk) => {
        buf += chunk.toString();
        const lines = buf.split('\n');
        buf = lines.pop();                      // keep any incomplete trailing line
        for (const line of lines) {
            const t = line.trim();
            if (!t) continue;
            const colon = t.indexOf(':');
            const type  = colon >= 0 ? t.slice(0, colon).toLowerCase() : 'info';
            const msg   = colon >= 0 ? t.slice(colon + 1) : t;
            send(type, msg);
        }
    });

    child.stderr.on('data', (chunk) => {
        // Surface any unexpected shell errors to the UI
        chunk.toString().split('\n').filter(Boolean).forEach(l => send('info', l));
    });

    child.on('close', (code) => {
        if (code !== 0) {
            send('error', `Update script exited with code ${code}`);
        }
        // Tell the client the stream is finished
        res.write('data: {"type":"end"}\n\n');
        res.end();

        // Restart the service 3 s after the response is flushed so the
        // SSE DONE event reaches the browser before the connection drops.
        if (code === 0) {
            setTimeout(() => exec('/etc/init.d/tour-audio restart'), 3000);
        }
    });

    // If the browser disconnects mid-update, kill the child to avoid orphans
    req.on('close', () => { try { child.kill(); } catch {} });
});

// ---- Phase 3: wifi scan + connect ----------------------------------------

// Parser for: iwpriv <iface> get_site_survey   (MediaTek/Ralink proprietary)
// Each data line contains a MAC address as an anchor; SSID precedes it,
// Security/RSSI/Signal% follow it.
function parseIwprivSiteSurvey(raw) {
    const networks = [];
    const macRe = /([0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5})/;
    for (const line of raw.split('\n')) {
        const macM = line.match(macRe);
        if (!macM) continue;
        const bssidIdx = line.indexOf(macM[1]);
        // Before BSSID: "0   6   My Network Name       "
        const prefixM = line.substring(0, bssidIdx).match(/^\s*\d+\s+\d+\s+(.+?)\s*$/);
        if (!prefixM) continue;
        const ssid = prefixM[1].trim();
        if (!ssid) continue; // hidden network
        // After BSSID: "WPA2/AES   -79   26   11b/g/n/ax   NONE   In ..."
        const parts = line.substring(bssidIdx + macM[1].length).trim().split(/\s+/);
        const security = parts[0] || 'UNKNOWN';
        const rssi = parseInt(parts[1], 10);      // dBm, e.g. -79
        const secured = !/^OPEN|NONE$/i.test(security);
        let strength = 'weak';
        if (!isNaN(rssi)) {
            if (rssi >= -60) strength = 'strong';
            else if (rssi >= -75) strength = 'medium';
        }
        networks.push({ ssid, signal: isNaN(rssi) ? null : rssi, strength, secured, encryption: security });
    }
    return networks;
}

// Parser for: iwinfo <iface> scan   (mac80211 / Atheros fallback)
function parseIwinfoScan(raw) {
    const networks = [];
    for (const b of raw.split(/Cell \d+ -/)) {
        const ssidM = b.match(/ESSID:\s*"([^"]*)"/);
        const sigM  = b.match(/Signal:\s*(-?\d+)\s*dBm/);
        const encM  = b.match(/Encryption:\s*([^\n]+)/);
        if (!ssidM || !ssidM[1]) continue;
        const signal = sigM ? parseInt(sigM[1], 10) : null;
        const enc = encM ? encM[1].trim() : 'unknown';
        const secured = !/^none/i.test(enc);
        let strength = 'weak';
        if (signal !== null) {
            if (signal >= -60) strength = 'strong';
            else if (signal >= -75) strength = 'medium';
        }
        networks.push({ ssid: ssidM[1], signal, strength, secured, encryption: enc });
    }
    return networks;
}

function dedupeNetworks(networks) {
    const byName = new Map();
    for (const n of networks) {
        const prev = byName.get(n.ssid);
        if (!prev || (n.signal || -999) > (prev.signal || -999)) byName.set(n.ssid, n);
    }
    return Array.from(byName.values()).sort((a, b) => (b.signal || -999) - (a.signal || -999));
}

app.get('/api/admin/wifi/scan', adminAuth, async (req, res) => {
    // Discover interfaces from /sys/class/net/ — no `iw` needed
    const { stdout: netOut } = await sh('ls /sys/class/net/ 2>/dev/null', 3000);
    const allIfaces = netOut.trim().split(/\s+/).filter(Boolean);

    // Primary AP interfaces only (not secondaries ra1/rax1, not client apcli*)
    // MediaTek/Ralink: ra0 (2.4 GHz), rax0 (5 GHz)
    // mac80211:        wlan0, wlan1, phy0-ap0
    const candidates = allIfaces.filter(i => /^(ra0|rax0|wlan\d+|phy\d+-ap0)$/.test(i));
    const ifaces = candidates.length ? candidates : ['ra0', 'rax0'];
    console.log('WiFi scan using interfaces:', ifaces.join(', '));

    const results = [];
    let anyOk = false;

    for (const iface of ifaces) {
        // Try MediaTek proprietary first (works in AP mode); fall back to iwinfo
        let r = await sh(`iwpriv ${iface} get_site_survey 2>/dev/null`, 15000);
        if (!r.err && r.stdout.includes('BSSID')) {
            const nets = parseIwprivSiteSurvey(r.stdout);
            if (nets.length) { anyOk = true; results.push(...nets); continue; }
        }
        // Fallback: iwinfo scan (Atheros/mac80211 routers)
        r = await sh(`iwinfo ${iface} scan 2>/dev/null`, 15000);
        if (!r.err && r.stdout.includes('ESSID')) {
            const nets = parseIwinfoScan(r.stdout);
            if (nets.length) { anyOk = true; results.push(...nets); }
        }
    }

    if (!anyOk) {
        return res.status(500).json({
            error: 'scan_failed',
            message: 'Could not scan for networks — enter the network name manually'
        });
    }
    res.json({ networks: dedupeNetworks(results) });
});

app.post('/api/admin/wifi/connect', adminAuth, async (req, res) => {
    const { ssid, password } = req.body || {};
    if (!ssid || typeof ssid !== 'string') {
        return res.status(400).json({ success: false, message: 'ssid required' });
    }
    // Use the GL.iNet repeater daemon (ubus 'repeater connect') — this is what
    // the router's own admin panel uses. It correctly handles apcli0 in AP+STA
    // mode, DHCP, and routing. Our previous UCI/iwpriv approach was fighting the
    // gl-repeater process that was already managing the interface.
    //
    // JSON.stringify safely handles special chars in ssid/password (quotes, etc).
    // Single-quote the JSON for the shell; escape any literal ' inside it.
    const payload = JSON.stringify({
        ssid,
        key:       password || '',
        protocol:  password ? 'psk2' : 'none',
        remember:  true,
        auto_portal: false,
    });
    const shellPayload = payload.replace(/'/g, `'\\''`);

    const { err, stdout: connectOut, stderr } = await sh(
        `ubus call repeater connect '${shellPayload}'`,
        15000
    );
    if (err) {
        return res.status(500).json({
            success: false,
            message: 'Could not start connection — repeater daemon error',
            detail: (stderr || connectOut).slice(0, 200),
        });
    }

    // Poll repeater status until connected or 30 s timeout
    let connected = false;
    const deadline = Date.now() + 30000;
    while (Date.now() < deadline) {
        await new Promise(r => setTimeout(r, 2500));
        const { stdout: statusRaw } = await sh('ubus call repeater status 2>/dev/null', 3000);
        try {
            const s = JSON.parse(statusRaw);
            // status field varies by firmware version: 1 / "connected" / "up"
            if (s && (s.status === 1 || s.status === 'connected' || s.status === 'up' || s.ip)) {
                connected = true;
                break;
            }
        } catch {}
    }

    // Confirm actual internet reachability (repeater connected ≠ hotspot has data)
    if (connected) connected = await checkInternet();

    res.json({
        success: connected,
        message: connected
            ? `Connected to ${ssid}`
            : `Joined ${ssid} but internet is not reachable — the hotspot may have no mobile data.`,
        internetReachable: connected,
    });
});

app.post('/api/admin/wifi/disconnect', adminAuth, async (req, res) => {
    const { err, stderr } = await sh('ubus call repeater disconnect \'{}\'', 8000);
    if (err) {
        return res.status(500).json({ success: false, message: 'Disconnect failed', detail: stderr.slice(0, 200) });
    }
    res.json({ success: true, message: 'Disconnected from uplink Wi-Fi' });
});

// Serve static files
app.use(express.static(path.join(__dirname, 'public')));

// ============================================
// Connectivity Check Handlers
// Respond to OS-level "am I online?" checks
// so devices don't switch away from tour WiFi
// ============================================

// Captive portal detection endpoints - redirect to HTTP welcome page
app.get('/hotspot-detect.html', (req, res) => {
    res.redirect('http://audio.eftours.travel/');
});

app.get('/generate_204', (req, res) => {
    res.redirect(302, 'http://audio.eftours.travel/');
});

app.get('/gen_204', (req, res) => {
    res.redirect(302, 'http://audio.eftours.travel/');
});

app.get('/connecttest.txt', (req, res) => {
    res.redirect('http://audio.eftours.travel/');
});

app.get('/redirect', (req, res) => {
    res.redirect('http://audio.eftours.travel/');
});

app.get('/success.txt', (req, res) => {
    res.redirect('http://audio.eftours.travel/');
});

// ============================================
// WebSocket Audio Streaming
// ============================================

const clients = new Set();
let broadcastCount = 0;
let skippedClients = 0;
let clientCountBroadcastTimeout = null;

// Extended metrics tracking
let guideConnected = false;
let broadcastStartTime = null;
let peakClients = 0;
let totalConnections = 0;
let totalBytesOut = 0;
let throughputWindowStart = Date.now();
let throughputWindowBytes = 0;
let availableIds = [];  // recycled IDs waiting to be reused (kept sorted low→high)
let maxClientId = 0;    // highest ID ever assigned
let activeBroadcaster = null;       // The one guide who can broadcast audio
const watchingGuides = new Set();   // Secondary guides in read-only watch mode

function assignClientId() {
    if (availableIds.length > 0) {
        return availableIds.shift(); // take the lowest recycled number
    }
    return ++maxClientId;
}

function releaseClientId(id) {
    if (id) {
        availableIds.push(id);
        availableIds.sort((a, b) => a - b); // keep sorted so shift() always gives lowest
    }
}

// Helper function to broadcast client count to all connected clients
// Sends role-specific messages: active broadcaster gets adjusted count,
// watching guides get watch-status, regular listeners get raw count.
// Debounced to prevent spam during rapid connections/disconnections.
function broadcastClientCount() {
    if (clientCountBroadcastTimeout) {
        clearTimeout(clientCountBroadcastTimeout);
    }

    clientCountBroadcastTimeout = setTimeout(() => {
        // True listener count excludes the active broadcaster and all watching guides
        const listenerCount = Math.max(0,
            clients.size - (activeBroadcaster ? 1 : 0) - watchingGuides.size);

        clients.forEach((client) => {
            if (client.readyState !== WebSocket.OPEN) return;
            try {
                if (client === activeBroadcaster) {
                    // Guide subtracts 1 for itself, so send listenerCount + 1
                    client.send(JSON.stringify({
                        type: 'client-count',
                        count: listenerCount + 1
                    }));
                } else if (watchingGuides.has(client)) {
                    client.send(JSON.stringify({
                        type: 'watch-status',
                        broadcasterActive: !!activeBroadcaster,
                        listenerCount
                    }));
                } else {
                    // Regular listener — send raw count (index.html doesn't display it)
                    client.send(JSON.stringify({ type: 'client-count', count: clients.size }));
                }
            } catch (error) {
                console.error('Error sending client count:', error);
            }
        });
    }, 500);
}

wss.on('connection', (ws) => {
    clients.add(ws);
    totalConnections++;
    if (clients.size > peakClients) peakClients = clients.size;

    // Assign the lowest available ID to this client and send it immediately
    const clientId = assignClientId();
    ws.clientId = clientId;
    ws.send(JSON.stringify({ type: 'client-id', id: clientId }));

    console.log(`Client #${clientId} connected. Total clients: ${clients.size}`);

    // Send updated count to all clients
    broadcastClientCount();

    ws.on('message', (data) => {
        // Ignore JSON text messages — we only handle binary PCM audio
        const isJson = (typeof data === 'string') ||
                       (data instanceof Buffer && data.length > 0 && data[0] === 0x7B);
        if (isJson) return;

        // ── Broadcaster locking ──────────────────────────────────────────────
        if (activeBroadcaster === null) {
            // No active broadcaster — this client claims the slot
            activeBroadcaster = ws;
            guideConnected = true;
            broadcastStartTime = Date.now();
            console.log(`Guide #${ws.clientId} started broadcasting`);
        } else if (activeBroadcaster !== ws) {
            // A different guide is already broadcasting — put this one in watch mode
            if (!watchingGuides.has(ws)) {
                watchingGuides.add(ws);
                const listenerCount = Math.max(0,
                    clients.size - 1 - watchingGuides.size); // -1 for active broadcaster
                try {
                    ws.send(JSON.stringify({ type: 'broadcaster_taken', listenerCount }));
                } catch (e) { /* ignore */ }
                broadcastClientCount(); // update broadcaster's count to exclude this watcher
                console.log(`Guide #${ws.clientId} rejected — broadcaster is #${activeBroadcaster.clientId}`);
            }
            return; // do NOT forward this audio
        }

        // ── Forward audio to all listeners (skip sender and watching guides) ─
        broadcastCount++;
        let skippedThisRound = 0;

        const clientArray = Array.from(clients);
        for (let i = 0; i < clientArray.length; i++) {
            const client = clientArray[i];

            if (client === ws ||
                client.readyState !== WebSocket.OPEN ||
                watchingGuides.has(client)) {
                continue;
            }

            // Backpressure handling — skip clients severely lagging (64KB ≈ 2s of audio)
            const bufferSize = client.bufferedAmount || 0;
            if (bufferSize > 65536) {
                skippedThisRound++;
                skippedClients++;
                continue;
            }

            try {
                client.send(data, { binary: true });
                totalBytesOut += data.length;
                throughputWindowBytes += data.length;
            } catch (error) {
                skippedThisRound++;
            }
        }

        if (broadcastCount % 500 === 0) {
            console.log('Broadcast stats: Total=' + broadcastCount + ', Clients=' + clients.size +
                       ', Skipped=' + skippedClients + ' (' + ((skippedClients / broadcastCount) * 100).toFixed(1) + '%)');
        }
        if (skippedThisRound > 5) {
            console.warn('Skipped ' + skippedThisRound + ' slow clients this broadcast');
        }
    });

    ws.on('close', () => {
        clients.delete(ws);
        releaseClientId(ws.clientId);
        console.log(`Client #${ws.clientId} disconnected. Total clients: ${clients.size}`);

        if (ws === activeBroadcaster) {
            // Active broadcaster left — notify all watching guides
            activeBroadcaster = null;
            guideConnected = false;
            broadcastStartTime = null;
            console.log('Active broadcaster stopped');
            watchingGuides.forEach((wg) => {
                if (wg.readyState === WebSocket.OPEN) {
                    try { wg.send(JSON.stringify({ type: 'broadcaster_ended' })); } catch (e) { /* ignore */ }
                }
            });
            watchingGuides.clear();
        } else {
            watchingGuides.delete(ws);
        }

        broadcastClientCount();
    });

    ws.on('error', (error) => {
        console.error(`WebSocket error for client #${ws.clientId}:`, error);
        clients.delete(ws);
        releaseClientId(ws.clientId);

        if (ws === activeBroadcaster) {
            activeBroadcaster = null;
            guideConnected = false;
            broadcastStartTime = null;
            watchingGuides.forEach((wg) => {
                if (wg.readyState === WebSocket.OPEN) {
                    try { wg.send(JSON.stringify({ type: 'broadcaster_ended' })); } catch (e) { /* ignore */ }
                }
            });
            watchingGuides.clear();
        } else {
            watchingGuides.delete(ws);
        }

        broadcastClientCount();
    });
});

// Health check endpoint with performance metrics
app.get('/health', (req, res) => {
    // Calculate buffer stats for all clients
    let totalBuffer = 0;
    let maxBuffer = 0;
    let slowClients = 0;

    clients.forEach((client) => {
        const bufferSize = client.bufferedAmount || 0;
        totalBuffer += bufferSize;
        maxBuffer = Math.max(maxBuffer, bufferSize);
        if (bufferSize > 65536) {  // Match the skip threshold
            slowClients++;
        }
    });

    const avgBuffer = clients.size > 0 ? Math.round(totalBuffer / clients.size) : 0;

    // CPU usage (average load over last 1 minute, as % of total CPU)
    const cpuLoad = os.loadavg()[0];
    const cpuCount = os.cpus().length;
    const cpuPercent = Math.min(100, (cpuLoad / cpuCount) * 100).toFixed(1);

    // Memory usage
    const totalMem = os.totalmem();
    const freeMem = os.freemem();
    const usedMem = totalMem - freeMem;
    const memPercent = ((usedMem / totalMem) * 100).toFixed(1);

    // Node.js process memory (heap)
    const heapMB = (process.memoryUsage().heapUsed / 1024 / 1024).toFixed(1);

    // Live throughput - calculated over the last health-check window
    const now = Date.now();
    const windowSeconds = (now - throughputWindowStart) / 1000;
    const kbPerSecond = windowSeconds > 0 ? (throughputWindowBytes / 1024 / windowSeconds).toFixed(1) : '0.0';
    // Reset the window for next call
    throughputWindowStart = now;
    throughputWindowBytes = 0;

    // Broadcast duration
    const broadcastDuration = broadcastStartTime
        ? Math.round((now - broadcastStartTime) / 1000) + 's'
        : null;

    res.json({
        status: 'ok',
        clients: clients.size,
        uptime: Math.round(process.uptime()) + 's',
        broadcast: {
            live: guideConnected,
            duration: broadcastDuration,
            peakClients: peakClients,
            totalConnections: totalConnections,
            throughput: kbPerSecond + ' KB/s',
            totalDataSent: (totalBytesOut / 1024 / 1024).toFixed(2) + ' MB'
        },
        system: {
            cpuLoad: cpuPercent + '%',
            memoryUsed: memPercent + '%',
            memoryFree: (freeMem / 1024 / 1024).toFixed(0) + ' MB',
            memoryTotal: (totalMem / 1024 / 1024).toFixed(0) + ' MB',
            nodeHeap: heapMB + ' MB'
        },
        performance: {
            totalBroadcasts: broadcastCount,
            skippedPackets: skippedClients,
            skipRate: broadcastCount > 0 ? ((skippedClients / broadcastCount) * 100).toFixed(2) + '%' : '0%',
            avgBufferSize: avgBuffer,
            maxBufferSize: maxBuffer,
            slowClients: slowClients
        }
    });
});

// ============================================
// HTTP Server (Port 80) - Welcome Page Only
// No SSL = No security warning!
// ============================================

// Redirect root to welcome page (BEFORE static middleware)
httpApp.get('/', (req, res) => {
    res.sendFile(path.join(__dirname, 'public', 'welcome.html'));
});

// Redirect any non-welcome HTTP request to HTTPS
// (e.g. someone types audio.eftours.travel/index.html without https://)
httpApp.use((req, res, next) => {
    const captivePortalPaths = ['/hotspot-detect.html', '/generate_204', '/gen_204',
                                '/connecttest.txt', '/redirect', '/success.txt',
                                '/welcome.html'];
    if (captivePortalPaths.includes(req.path)) return next();
    // Redirect everything else to HTTPS
    return res.redirect(301, 'https://' + req.hostname + req.url);
});

// Serve static files for welcome.html resources (CSS, images etc.)
httpApp.use(express.static(path.join(__dirname, 'public'), {
    index: false  // Disable automatic index.html serving
}));

// Captive portal detection endpoints - redirect to welcome page
httpApp.get('/hotspot-detect.html', (req, res) => {
    res.redirect('/welcome.html');
});

httpApp.get('/generate_204', (req, res) => {
    res.redirect(302, '/welcome.html');
});

httpApp.get('/gen_204', (req, res) => {
    res.redirect(302, '/welcome.html');
});

// Additional captive portal detection URLs
httpApp.get('/connecttest.txt', (req, res) => {
    res.redirect('/welcome.html');
});

httpApp.get('/redirect', (req, res) => {
    res.redirect('/welcome.html');
});

httpApp.get('/success.txt', (req, res) => {
    res.redirect('/welcome.html');
});

const httpServer = http.createServer(httpApp);

const HTTP_PORT = 8888;
httpServer.listen(HTTP_PORT, '0.0.0.0', () => {
    console.log(`HTTP Welcome Server running on port ${HTTP_PORT}`);
    console.log(`Welcome page: http://192.168.8.1:${HTTP_PORT}/ (or http://audio.eftours.travel/)`);
});

// ============================================
// HTTPS Server (Port 3000) - Audio App
// ============================================

const PORT = process.env.PORT || 3000;
server.listen(PORT, '0.0.0.0', () => {
    console.log(`Tour Audio Server (HTTPS) running on port ${PORT}`);
    console.log(`Guide interface: https://192.168.8.1:${PORT}/guide.html`);
    console.log(`Listener interface: https://192.168.8.1:${PORT}/ (or /index.html)`);
    console.log(`Health check: https://192.168.8.1:${PORT}/health`);
    if (adminToken) {
        console.log(`Admin URL (QR target): https://audio.eftours.travel/admin?token=${adminToken}`);
        if (adminTokenPath) console.log(`Admin token stored at: ${adminTokenPath}`);
    }
});

// ============================================
// Global Error Handlers - Prevent Crashes
// ============================================

// Handle uncaught exceptions (prevents crash)
process.on('uncaughtException', (error) => {
    console.error('UNCAUGHT EXCEPTION:', error);
    console.error('Stack:', error.stack);
    // Don't exit - let procd respawn handle actual crashes
    // This just logs the error and continues
});

// Handle unhandled promise rejections
process.on('unhandledRejection', (reason, promise) => {
    console.error('UNHANDLED REJECTION at:', promise);
    console.error('Reason:', reason);
    // Don't exit - continue running
});

// Graceful shutdown on SIGTERM (when router shuts down)
process.on('SIGTERM', () => {
    console.log('SIGTERM received - shutting down gracefully...');

    // Close WebSocket server (stops accepting new connections)
    wss.close(() => {
        console.log('WebSocket server closed');
    });

    // Close HTTPS server
    server.close(() => {
        console.log('HTTPS server closed');
        process.exit(0);
    });

    // Close HTTP server
    httpServer.close(() => {
        console.log('HTTP server closed');
    });

    // Force exit after 5 seconds if graceful shutdown fails
    setTimeout(() => {
        console.error('Forced shutdown after timeout');
        process.exit(1);
    }, 5000);
});

// Log server uptime every hour (helps with debugging)
setInterval(() => {
    const uptime = process.uptime();
    console.log(`Server uptime: ${Math.floor(uptime / 3600)}h ${Math.floor((uptime % 3600) / 60)}m`);
}, 3600000); // Every hour

const express = require('express');
const http = require('http');
const https = require('https');
const fs = require('fs');
const os = require('os');
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
    res.redirect('http://ef.tour/');
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
    res.redirect('http://ef.tour/');
});

app.get('/generate_204', (req, res) => {
    res.redirect(302, 'http://ef.tour/');
});

app.get('/gen_204', (req, res) => {
    res.redirect(302, 'http://ef.tour/');
});

app.get('/connecttest.txt', (req, res) => {
    res.redirect('http://ef.tour/');
});

app.get('/redirect', (req, res) => {
    res.redirect('http://ef.tour/');
});

app.get('/success.txt', (req, res) => {
    res.redirect('http://ef.tour/');
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

// Helper function to broadcast client count to all connected clients
// Debounced to prevent spam during rapid connections/disconnections
function broadcastClientCount() {
    // Clear any pending broadcast
    if (clientCountBroadcastTimeout) {
        clearTimeout(clientCountBroadcastTimeout);
    }

    // Debounce: wait 500ms before broadcasting count
    // This prevents spamming during rapid connects (load test ramp-up)
    clientCountBroadcastTimeout = setTimeout(() => {
        const count = clients.size;
        const countMessage = JSON.stringify({ type: 'client-count', count: count });

        clients.forEach((client) => {
            if (client.readyState === WebSocket.OPEN) {
                try {
                    client.send(countMessage);
                } catch (error) {
                    console.error('Error sending client count:', error);
                }
            }
        });
    }, 500);
}

wss.on('connection', (ws) => {
    clients.add(ws);
    totalConnections++;
    if (clients.size > peakClients) peakClients = clients.size;
    console.log('Client connected. Total clients:', clients.size);

    // Send updated count to all clients
    broadcastClientCount();

    ws.on('message', (data) => {
        // Check if it's a text message (JSON) or binary audio data
        if (typeof data === 'string' || data instanceof Buffer && data[0] === 0x7B) {
            // Might be JSON, but we only handle audio here
            // Audio data is binary, so just broadcast it
        }

        // Mark guide as live on first message and track broadcast start time
        if (!guideConnected) {
            guideConnected = true;
            broadcastStartTime = Date.now();
            console.log('Guide started broadcasting');
        }

        // Broadcast audio to all OTHER connected clients
        // Ultra-optimized for 40+ clients with aggressive backpressure handling
        broadcastCount++;
        let skippedThisRound = 0;

        // Convert Set to Array once for better iteration performance
        const clientArray = Array.from(clients);

        // Use for loop for maximum performance
        for (let i = 0; i < clientArray.length; i++) {
            const client = clientArray[i];

            // Skip the sender and closed connections
            if (client === ws || client.readyState !== WebSocket.OPEN) {
                continue;
            }

            // Backpressure handling - only skip clients that are severely lagging
            // Higher threshold (64KB) to reduce skip rate while still preventing complete buffer buildup
            const bufferSize = client.bufferedAmount || 0;
            if (bufferSize > 65536) {  // 64KB - about 2 seconds of buffered audio
                skippedThisRound++;
                skippedClients++;
                continue;
            }

            try {
                // Send without callback for maximum speed
                client.send(data, { binary: true });
                // Track bytes sent (packet size * number of listeners receiving it)
                totalBytesOut += data.length;
                throughputWindowBytes += data.length;
            } catch (error) {
                // Silently fail - don't let errors slow broadcast
                skippedThisRound++;
            }
        }

        // Reduce logging overhead - only log every 500 broadcasts
        if (broadcastCount % 500 === 0) {
            console.log('Broadcast stats: Total=' + broadcastCount + ', Clients=' + clients.size +
                       ', Skipped=' + skippedClients + ' (' + ((skippedClients / broadcastCount) * 100).toFixed(1) + '%)');
        }

        // Only warn if many clients skipped
        if (skippedThisRound > 5) {
            console.warn('Skipped ' + skippedThisRound + ' slow clients this broadcast');
        }
    });

    ws.on('close', () => {
        clients.delete(ws);
        console.log('Client disconnected. Total clients:', clients.size);

        // If the guide disconnected (they were the only sender), mark as not live
        if (clients.size === 0) {
            guideConnected = false;
            broadcastStartTime = null;
            console.log('Guide stopped broadcasting');
        }

        // Send updated count to remaining clients
        broadcastClientCount();
    });

    ws.on('error', (error) => {
        console.error('WebSocket error:', error);
        clients.delete(ws);

        // Send updated count to remaining clients
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

// Serve static files for other resources (but disable index.html serving)
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
    console.log(`Welcome page: http://192.168.8.1:${HTTP_PORT}/ (or http://ef.tour/)`);
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

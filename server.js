const express = require('express');
const http = require('http');
const https = require('https');
const fs = require('fs');
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
const wss = new WebSocket.Server({ server });

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

wss.on('connection', (ws) => {
    clients.add(ws);
    console.log('Client connected. Total clients:', clients.size);

    ws.on('message', (data) => {
        // Broadcast audio to all OTHER connected clients
        clients.forEach((client) => {
            if (client !== ws && client.readyState === WebSocket.OPEN) {
                client.send(data);
            }
        });
    });

    ws.on('close', () => {
        clients.delete(ws);
        console.log('Client disconnected. Total clients:', clients.size);
    });

    ws.on('error', (error) => {
        console.error('WebSocket error:', error);
        clients.delete(ws);
    });
});

// Health check endpoint
app.get('/health', (req, res) => {
    res.json({
        status: 'ok',
        clients: clients.size,
        uptime: process.uptime()
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

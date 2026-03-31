#!/usr/bin/env node

/**
 * Load Testing Tool for Tour Audio Server
 * 
 * Simulates multiple listener clients to test server performance under load
 * 
 * Usage:
 *   node load-test.js [options]
 * 
 * Options:
 *   --clients <number>    Number of clients to simulate (default: 20)
 *   --duration <seconds>  Test duration in seconds (default: 60)
 *   --ramp <seconds>      Ramp-up time to add all clients (default: 10)
 *   --host <hostname>     Server hostname (default: 192.168.8.1)
 *   --port <number>       Server port (default: 3000)
 *   --ssl                 Use WSS instead of WS (default: true)
 */

const WebSocket = require('ws');
const https = require('https');

// Command line arguments
const args = process.argv.slice(2);
function getArg(name, defaultValue) {
    const index = args.indexOf(name);
    return index > -1 && args[index + 1] ? args[index + 1] : defaultValue;
}

const config = {
    clients: parseInt(getArg('--clients', '20')),
    duration: parseInt(getArg('--duration', '60')),
    ramp: parseInt(getArg('--ramp', '10')),
    host: getArg('--host', '192.168.8.1'),
    port: getArg('--port', '3000'),
    ssl: !args.includes('--no-ssl')
};

// Test statistics
const stats = {
    connected: 0,
    disconnected: 0,
    errors: 0,
    messagesReceived: 0,
    bytesReceived: 0,
    startTime: null,
    clientStats: []
};

class SimulatedListener {
    constructor(id) {
        this.id = id;
        this.ws = null;
        this.connected = false;
        this.messagesReceived = 0;
        this.bytesReceived = 0;
        this.lastMessageTime = null;
        this.connectTime = null;
        this.errors = 0;
    }

    connect() {
        const protocol = config.ssl ? 'wss:' : 'ws:';
        const url = `${protocol}//${config.host}:${config.port}`;
        
        console.log(`[Client ${this.id}] Connecting to ${url}...`);
        
        // Ignore self-signed certificate errors for testing
        const wsOptions = config.ssl ? {
            rejectUnauthorized: false,
            agent: new https.Agent({ rejectUnauthorized: false })
        } : {};

        this.ws = new WebSocket(url, wsOptions);

        this.ws.on('open', () => {
            this.connected = true;
            this.connectTime = Date.now();
            stats.connected++;
            console.log(`[Client ${this.id}] ✅ Connected (Total: ${stats.connected})`);
        });

        this.ws.on('message', (data) => {
            // Check if it's a client count message or audio data
            if (typeof data === 'string') {
                try {
                    const message = JSON.parse(data);
                    if (message.type === 'client-count') {
                        // Ignore count messages for stats
                        return;
                    }
                } catch (e) {
                    // Not JSON, treat as audio
                }
            }

            // It's audio data
            this.messagesReceived++;
            this.bytesReceived += data.length;
            this.lastMessageTime = Date.now();
            
            stats.messagesReceived++;
            stats.bytesReceived += data.length;
        });

        this.ws.on('error', (error) => {
            this.errors++;
            stats.errors++;
            console.error(`[Client ${this.id}] ❌ Error:`, error.message);
        });

        this.ws.on('close', () => {
            if (this.connected) {
                stats.disconnected++;
                console.log(`[Client ${this.id}] Disconnected`);
            }
            this.connected = false;
        });
    }

    disconnect() {
        if (this.ws) {
            this.ws.close();
            this.ws = null;
        }
    }

    getStats() {
        const now = Date.now();
        const uptime = this.connectTime ? (now - this.connectTime) / 1000 : 0;
        const timeSinceLastMessage = this.lastMessageTime ? (now - this.lastMessageTime) / 1000 : null;

        return {
            id: this.id,
            connected: this.connected,
            uptime: uptime.toFixed(1),
            messagesReceived: this.messagesReceived,
            bytesReceived: this.bytesReceived,
            timeSinceLastMessage: timeSinceLastMessage ? timeSinceLastMessage.toFixed(1) : 'N/A',
            errors: this.errors
        };
    }
}

// Main test orchestrator
class LoadTest {
    constructor() {
        this.clients = [];
        this.statsInterval = null;
        this.testTimeout = null;
    }

    async start() {
        console.log('\n╔════════════════════════════════════════════════════╗');
        console.log('║     Tour Audio Server - Load Test                 ║');
        console.log('╚════════════════════════════════════════════════════╝\n');
        console.log('Configuration:');
        console.log(`  Clients:    ${config.clients}`);
        console.log(`  Duration:   ${config.duration}s`);
        console.log(`  Ramp-up:    ${config.ramp}s`);
        console.log(`  Server:     ${config.ssl ? 'wss' : 'ws'}://${config.host}:${config.port}`);
        console.log('\n' + '─'.repeat(60) + '\n');

        stats.startTime = Date.now();

        // Create clients with ramp-up
        const rampDelay = (config.ramp * 1000) / config.clients;

        for (let i = 0; i < config.clients; i++) {
            const client = new SimulatedListener(i + 1);
            this.clients.push(client);

            // Delay between each connection
            await new Promise(resolve => setTimeout(resolve, rampDelay));
            client.connect();
        }

        console.log(`\n✅ All ${config.clients} clients launched\n`);
        console.log('─'.repeat(60) + '\n');

        // Start periodic stats reporting
        this.statsInterval = setInterval(() => this.printStats(), 5000);

        // Schedule test end
        this.testTimeout = setTimeout(() => this.stop(), config.duration * 1000);
    }

    printStats() {
        const now = Date.now();
        const elapsed = ((now - stats.startTime) / 1000).toFixed(1);

        console.log(`\n[${elapsed}s] Current Statistics:`);
        console.log('─'.repeat(60));
        console.log(`  Connected:        ${stats.connected}`);
        console.log(`  Disconnected:     ${stats.disconnected}`);
        console.log(`  Errors:           ${stats.errors}`);
        console.log(`  Messages Received: ${stats.messagesReceived.toLocaleString()}`);
        console.log(`  Bytes Received:    ${(stats.bytesReceived / 1024).toFixed(2)} KB`);

        // Calculate message rate
        const msgRate = (stats.messagesReceived / parseFloat(elapsed)).toFixed(1);
        const kbRate = (stats.bytesReceived / 1024 / parseFloat(elapsed)).toFixed(2);
        console.log(`  Message Rate:      ${msgRate} msg/sec`);
        console.log(`  Data Rate:         ${kbRate} KB/sec`);

        // Find problematic clients
        const problematicClients = this.clients.filter(c => {
            const timeSince = c.lastMessageTime ? (now - c.lastMessageTime) / 1000 : 999;
            return c.connected && timeSince > 5;
        });

        if (problematicClients.length > 0) {
            console.log(`\n  ⚠️  Warning: ${problematicClients.length} clients haven't received data in 5+ seconds`);
        }
    }

    async stop() {
        console.log('\n' + '═'.repeat(60));
        console.log('  Test Complete - Disconnecting Clients...');
        console.log('═'.repeat(60) + '\n');

        clearInterval(this.statsInterval);
        clearTimeout(this.testTimeout);

        // Disconnect all clients
        this.clients.forEach(client => client.disconnect());

        // Wait a bit for clean shutdown
        await new Promise(resolve => setTimeout(resolve, 1000));

        this.printFinalReport();
    }

    printFinalReport() {
        const duration = ((Date.now() - stats.startTime) / 1000).toFixed(1);

        console.log('\n╔════════════════════════════════════════════════════╗');
        console.log('║              FINAL TEST REPORT                     ║');
        console.log('╚════════════════════════════════════════════════════╝\n');

        console.log('Overall Statistics:');
        console.log('─'.repeat(60));
        console.log(`  Test Duration:        ${duration}s`);
        console.log(`  Target Clients:       ${config.clients}`);
        console.log(`  Successfully Connected: ${stats.connected}`);
        console.log(`  Connection Failures:   ${config.clients - stats.connected}`);
        console.log(`  Total Disconnects:     ${stats.disconnected}`);
        console.log(`  Total Errors:          ${stats.errors}`);
        console.log(`  Messages Received:     ${stats.messagesReceived.toLocaleString()}`);
        console.log(`  Bytes Received:        ${(stats.bytesReceived / 1024 / 1024).toFixed(2)} MB`);

        const avgMsgRate = (stats.messagesReceived / parseFloat(duration)).toFixed(1);
        const avgKbRate = (stats.bytesReceived / 1024 / parseFloat(duration)).toFixed(2);
        console.log(`  Avg Message Rate:      ${avgMsgRate} msg/sec`);
        console.log(`  Avg Data Rate:         ${avgKbRate} KB/sec`);

        // Per-client statistics
        console.log('\nPer-Client Statistics:');
        console.log('─'.repeat(60));

        const clientStats = this.clients.map(c => c.getStats());
        const totalMessages = clientStats.reduce((sum, c) => sum + c.messagesReceived, 0);
        const avgMessages = totalMessages / this.clients.length;

        console.log(`  Avg messages per client: ${avgMessages.toFixed(0)}`);
        console.log(`  Min messages: ${Math.min(...clientStats.map(c => c.messagesReceived))}`);
        console.log(`  Max messages: ${Math.max(...clientStats.map(c => c.messagesReceived))}`);

        // Show clients with issues
        const problemClients = clientStats.filter(c => c.errors > 0 || c.messagesReceived < avgMessages * 0.5);
        if (problemClients.length > 0) {
            console.log(`\n  ⚠️  ${problemClients.length} clients had issues:`);
            problemClients.forEach(c => {
                console.log(`     Client ${c.id}: ${c.messagesReceived} msgs, ${c.errors} errors`);
            });
        }

        console.log('\n' + '═'.repeat(60));
        console.log('  Test complete! ✅');
        console.log('═'.repeat(60) + '\n');

        process.exit(0);
    }
}

// Run the test
if (require.main === module) {
    const test = new LoadTest();
    test.start().catch(error => {
        console.error('Test failed:', error);
        process.exit(1);
    });
}

module.exports = LoadTest;


# Load Testing Guide

This guide explains how to use the automated load testing tool to verify the tour audio server can handle 40+ concurrent listeners.

## Prerequisites

Install the WebSocket client library:

```bash
npm install ws
```

## Quick Start

### Basic Test (20 clients for 60 seconds)

```bash
node load-test.js
```

### Custom Test (40 clients for 120 seconds)

```bash
node load-test.js --clients 40 --duration 120
```

---

## Command Line Options

| Option | Description | Default |
|--------|-------------|---------|
| `--clients <number>` | Number of simulated listeners | 20 |
| `--duration <seconds>` | Test duration | 60 |
| `--ramp <seconds>` | Time to ramp up all clients | 10 |
| `--host <hostname>` | Server hostname/IP | 192.168.8.1 |
| `--port <number>` | Server port | 3000 |
| `--no-ssl` | Use WS instead of WSS | false (uses WSS) |

---

## Example Test Scenarios

### 1. Quick Smoke Test (10 clients, 30 seconds)

```bash
node load-test.js --clients 10 --duration 30 --ramp 5
```

**Use case:** Verify basic functionality before a tour

---

### 2. Realistic Tour Test (25 clients, 5 minutes)

```bash
node load-test.js --clients 25 --duration 300 --ramp 30
```

**Use case:** Simulate a typical 25-person tour group

---

### 3. Stress Test (50 clients, 10 minutes)

```bash
node load-test.js --clients 50 --duration 600 --ramp 20
```

**Use case:** Test maximum capacity and identify breaking point

---

### 4. Fast Ramp Test (40 clients, 2-second ramp)

```bash
node load-test.js --clients 40 --duration 120 --ramp 2
```

**Use case:** Test rapid connection scenario (everyone joins at once)

---

## Understanding the Output

### During Test

Every 5 seconds, you'll see:

```
[15.2s] Current Statistics:
------------------------------------------------------------
  Connected:        20
  Disconnected:     0
  Errors:           0
  Messages Received: 4,532
  Bytes Received:    92.41 KB
  Message Rate:      298.2 msg/sec
  Data Rate:         6.08 KB/sec
```

**Key metrics:**
- **Connected**: How many clients successfully connected
- **Messages Received**: Total audio packets received by all clients
- **Message Rate**: Packets per second (should be ~15-16 per client)
- **Data Rate**: Bandwidth usage

---

### Final Report

At the end, you'll get a detailed summary:

```
╔════════════════════════════════════════════════════╗
║              FINAL TEST REPORT                     ║
╚════════════════════════════════════════════════════╝

Overall Statistics:
------------------------------------------------------------
  Test Duration:        60.1s
  Target Clients:       20
  Successfully Connected: 20
  Connection Failures:   0
  Total Disconnects:     0
  Total Errors:          0
  Messages Received:     18,120
  Bytes Received:        0.37 MB
  Avg Message Rate:      301.5 msg/sec
  Avg Data Rate:         6.31 KB/sec

Per-Client Statistics:
------------------------------------------------------------
  Avg messages per client: 906
  Min messages: 898
  Max messages: 912
```

---

## What to Look For

### ✅ Good Signs

- **Connection success rate: 100%** (all clients connect)
- **Message rate: ~15 × number of clients** (e.g., 20 clients = ~300 msg/sec)
- **Min/Max message variance < 10%** (all clients getting similar data)
- **No errors or disconnects**
- **No "haven't received data in 5+ seconds" warnings**

### ⚠️ Warning Signs

- **Connection failures > 5%**
- **Message rate drops significantly** (indicates packet loss)
- **Large variance in messages per client** (some clients starving)
- **Warnings about clients not receiving data**
- **Increasing errors over time**

### ❌ Critical Issues

- **Connection success rate < 80%**
- **Many clients with 0 messages received**
- **Server crashes or stops responding**
- **Message rate drops to near zero**

---

## Monitoring Server Performance During Test

### Option 1: Watch Server Logs

In a separate terminal:

```bash
ssh root@192.168.8.1
logread -f | grep tour-audio
```

Look for:
- `Broadcast stats: ...` (every 100 broadcasts)
- `Skipped X slow clients` warnings
- Connection/disconnection messages

---

### Option 2: Monitor Health Endpoint

In a separate terminal:

```bash
# Check once
curl -k https://192.168.8.1:3000/health

# Check continuously (every 3 seconds)
watch -n 3 'curl -s -k https://192.168.8.1:3000/health | python -m json.tool'
```

**Important metrics:**
```json
{
  "performance": {
    "skipRate": "1.2%",        // Should be < 5%
    "slowClients": 1,          // Should be < 3
    "maxBufferSize": 8192      // Should be < 32KB
  }
}
```

---

## Troubleshooting

### Problem: "Cannot find module 'ws'"

**Solution:**
```bash
npm install ws
```

---

### Problem: All clients fail to connect

**Possible causes:**
1. Server not running
2. Wrong host/port
3. Firewall blocking connections

**Solution:**
```bash
# Check server is running
ssh root@192.168.8.1
ps | grep node

# Restart server if needed
/etc/init.d/tour-audio restart
```

---

### Problem: Clients connect but receive no messages

**Possible cause:** No tour guide is broadcasting

**Solution:** 
1. Open guide page: `https://192.168.8.1:3000/guide.html`
2. Click "Start Broadcasting"
3. Speak into microphone
4. Run load test again

---

## Best Practices

### 1. Always Test Before Tours

Run a quick test 15 minutes before the tour:

```bash
node load-test.js --clients 30 --duration 60
```

### 2. Test With Real Guide Broadcasting

The load test simulates **listeners only**. For realistic testing:

1. Have someone open guide page and broadcast
2. Run load test while guide is speaking
3. Verify all simulated clients receive audio

### 3. Gradual Scaling

Test incrementally to find your limit:

```bash
# Test 1: 20 clients
node load-test.js --clients 20 --duration 60

# Test 2: 30 clients
node load-test.js --clients 30 --duration 60

# Test 3: 40 clients
node load-test.js --clients 40 --duration 60

# Test 4: 50 clients (stress test)
node load-test.js --clients 50 --duration 60
```

---

## Performance Benchmarks

Based on GL-MT3000 router specifications:

| Clients | Expected Result |
|---------|-----------------|
| 1-20    | ✅ Perfect (no packet loss) |
| 21-30   | ✅ Excellent (< 1% skip rate) |
| 31-40   | ✅ Good (1-3% skip rate) |
| 41-50   | ⚠️  Acceptable (3-5% skip rate) |
| 51+     | ❌ Degraded (> 5% skip rate) |

**Note:** Skip rate refers to packets skipped for slow clients. Fast clients still get smooth audio.

---

## Next Steps

After successful load testing:

1. ✅ Document your maximum tested capacity
2. ✅ Train tour guides on system limits
3. ✅ Set up monitoring for production tours
4. ✅ Consider upgrade path if you regularly exceed 40 clients

---

For questions or issues, check `server.js` performance optimizations or review the `/health` endpoint metrics.


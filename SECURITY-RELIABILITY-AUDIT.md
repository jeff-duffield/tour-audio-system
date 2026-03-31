# Security & Reliability Audit
## Pre-Deployment Checklist for Tour Audio System

Run through this checklist before each tour to ensure system reliability.

---

## 🚨 CRITICAL (Must Fix Before Tour)

### ✅ 1. Service Auto-Restart on Crash
**Status:** FIXED (already configured)

**What it does:** If Node.js crashes, procd automatically restarts it within 1-2 seconds

**How to verify:**
```bash
ssh root@192.168.8.1
/etc/init.d/tour-audio status
# Should show: running

# Test auto-restart
ps | grep "node.*server.js"  # Note the PID
kill <PID>  # Kill the process
sleep 5
ps | grep "node.*server.js"  # Should show new PID
```

**If broken:** Reinstall init script from README.md Step 4

---

### ✅ 2. Global Error Handlers
**Status:** FIXED (just added)

**What it does:** Catches uncaught exceptions and unhandled promise rejections to prevent crashes

**What was added:**
- `process.on('uncaughtException')` - Catches unexpected errors
- `process.on('unhandledRejection')` - Catches async errors
- `process.on('SIGTERM')` - Graceful shutdown when router restarts
- Hourly uptime logging for debugging

**How to verify:**
```bash
# Check server.js has error handlers
ssh root@192.168.8.1
grep -A3 "uncaughtException" /root/tour-audio-server/server.js
# Should show error handler code
```

---

### ⚠️ 3. Client Isolation (Security)
**Status:** NEEDS REVIEW

**Current setting:** Client isolation is DISABLED (clients can see each other)

**Security risk:**
- Tourists' devices can communicate with each other
- Malicious device could scan/attack other devices
- **Risk level:** LOW for closed tour groups, HIGH for public groups

**Recommendation:** ENABLE isolation unless you specifically need device-to-device communication

**How to fix:**
```bash
ssh root@192.168.8.1
uci set wireless.wifi2g.isolate='1'
uci set wireless.wifi5g.isolate='1'
uci commit wireless
wifi reload
```

**Test after enabling:** Audio app should still work (it only needs router connection)

---

## ⚠️ HIGH PRIORITY (Strongly Recommended)

### ⚠️ 4. WiFi Credential Security
**Status:** NEEDS VERIFICATION

**Current SSIDs:**
- 2.4GHz: `EFTourAudio-1`
- 5GHz: `EF-TOUR-001`

**Questions to verify:**
- Are these networks password-protected?
- Is the password strong enough?
- Is the password shared securely?

**Recommendation:**
- Use WPA2/WPA3 with strong password (12+ characters)
- Don't broadcast password publicly
- Change password between tour groups if needed

**How to check:**
```bash
ssh root@192.168.8.1
uci show wireless | grep -E "encryption|key"
```

Should show:
```
wireless.wifi2g.encryption='psk2'  # Or 'sae' for WPA3
wireless.wifi2g.key='<your-password>'
```

---

### ⚠️ 5. Router Admin Interface Exposure
**Status:** NEEDS VERIFICATION

**Risk:** If router admin interface is accessible on WiFi, tourists could:
- Change router settings
- Disconnect other users
- View network traffic

**How to check:**
```bash
# Try accessing from tourist's phone connected to tour WiFi
http://192.168.8.1  # Router admin page
```

**Recommendation:** Disable HTTP admin interface or restrict to LAN only

**How to fix:**
```bash
ssh root@192.168.8.1
# Check current firewall rules
uci show uhttpd

# Option 1: Change admin interface to different port
uci set uhttpd.main.listen_http='127.0.0.1:80'
uci commit uhttpd
/etc/init.d/uhttpd restart

# Option 2: Disable HTTP admin entirely (use SSH only)
uci set uhttpd.main.listen_http=''
uci commit uhttpd
/etc/init.d/uhttpd restart
```

---

### ⚠️ 6. SSL Certificate Expiration
**Status:** CHECK EXPIRATION DATE

**Current cert:** Self-signed, valid for 365 days from creation

**How to check:**
```bash
ssh root@192.168.8.1
openssl x509 -in /root/tour-audio-server/ssl/server.crt -noout -dates
```

Should show:
```
notBefore=Mar 15 2024 ...
notAfter=Mar 15 2025 ...  # Check this date!
```

**If expired:** Regenerate following README.md instructions or setup-router.sh

---

## 📊 MEDIUM PRIORITY (Good to Have)

### 📊 7. Disk Space Monitoring
**Risk:** If router runs out of disk space, logging fails and server may crash

**How to check:**
```bash
ssh root@192.168.8.1
df -h
```

Should see:
```
/dev/root  ~128MB used of ~256MB  (50% used)  # OK
/dev/root  ~240MB used of ~256MB  (95% used)  # DANGER!
```

**If running low:**
```bash
# Clear old logs
logread -f > /dev/null &
kill $!
rm /var/log/*.log

# Check what's using space
du -h /root | sort -h
```

---

### 📊 8. Memory Usage Under Load
**Risk:** If router runs out of RAM, kernel kills processes (including Node.js)

**How to check:**
```bash
ssh root@192.168.8.1
free -m
```

Should see:
```
              total        used        free      shared  buff/cache   available
Mem:            512          180         250          10          80         300
```

**Warning signs:**
- Available < 100 MB: System under stress
- Available < 50 MB: May start killing processes

**If running low:**
- Reduce DNS cache: `uci set dhcp.@dnsmasq[0].cachesize='500'`
- Check for memory leaks: Restart server daily

---

### 📊 9. Connection Limit Testing
**Current limits:**
- DHCP: 100 clients max
- WiFi 2.4GHz: 60 clients max
- WiFi 5GHz: 60 clients max

**Have you tested:** Maximum simultaneous connections?

**Recommendation:** Test with load-test.js to find actual limit

---

### 📊 10. Battery/Power Backup
**Risk:** If router loses power mid-tour, system goes down

**Questions:**
- Is router on battery or wall power?
- Battery capacity? (estimate runtime)
- Can you hot-swap batteries?

**Recommendation for 3-hour tours:**
- 10,000 mAh battery: ~9 hours runtime (enough!)
- 5,000 mAh battery: ~4.5 hours runtime (tight)
- Wall power: Bring extension cord as backup

---

## 🔒 LOW PRIORITY (Nice to Have)

### 🔒 11. Rate Limiting
**Risk:** Malicious client could spam server with connections

**Current protection:** None

**Impact:** Low (tour groups are trusted)

**Future enhancement:** Add rate limiting to WebSocket connections

---

### 🔒 12. Logging & Monitoring
**Current logs:**
```bash
# View server logs
ssh root@192.168.8.1
logread | grep tour-audio
```

**Enhancement:** Save broadcast statistics to file for post-tour analysis

---

### 🔒 13. Backup Configuration
**Question:** Do you have router config backed up?

**How to back up:**
```bash
ssh root@192.168.8.1
# Back up entire router config
sysupgrade -b /tmp/backup.tar.gz
scp root@192.168.8.1:/tmp/backup.tar.gz ./router-backup-$(date +%Y%m%d).tar.gz
```

**Restore if needed:**
```bash
scp router-backup-YYYYMMDD.tar.gz root@192.168.8.1:/tmp/
ssh root@192.168.8.1
sysupgrade -r /tmp/router-backup-YYYYMMDD.tar.gz
```

---

## ✅ PRE-TOUR CHECKLIST

Run these checks 30 minutes before tour starts:

```bash
# 1. Check server is running
curl -k https://192.168.8.1:3000/health
# Should show: {"status":"ok","clients":0,...}

# 2. Check both WiFi bands active
ssh root@192.168.8.1 "wifi status | grep -c 'up'"
# Should show: 2 (both 2.4GHz and 5GHz)

# 3. Check disk space
ssh root@192.168.8.1 "df -h | grep '/dev/root'"
# Should be < 80% used

# 4. Check memory
ssh root@192.168.8.1 "free -m | grep 'Mem:'"
# Available should be > 100 MB

# 5. Test guide interface
# Open https://192.168.8.1:3000/guide.html
# Click "Start Broadcasting" - should work

# 6. Test listener interface
# Open https://192.168.8.1:3000/ on second device
# Should hear audio from guide

# 7. Check DHCP pool
ssh root@192.168.8.1 "cat /tmp/dhcp.leases | wc -l"
# Should show current connected devices
```

---

## 🚨 EMERGENCY PROCEDURES

### If Server Crashes Mid-Tour:

```bash
# Quick restart
ssh root@192.168.8.1
/etc/init.d/tour-audio restart

# Check if running
/etc/init.d/tour-audio status

# View error logs
logread | tail -50
```

### If WiFi Becomes Unstable:

```bash
# Restart WiFi only (doesn't affect server)
ssh root@192.168.8.1
wifi reload
```

### If Router Becomes Unresponsive:

```bash
# Hard reset: Hold reset button for 10 seconds
# Then re-run all optimizations:
# - optimize-dhcp.sh
# - optimize-wifi.sh
# - Restore router backup
```

---

## 📞 SUPPORT CONTACTS

**Router Access:**
- IP: `192.168.8.1`
- User: `root`
- Password: `EFTours2026!`

**WiFi Networks:**
- 2.4GHz: `EFTourAudio-1`
- 5GHz: `EF-TOUR-001`

**Key URLs:**
- Guide: `https://ef.tour/guide.html` or `https://192.168.8.1:3000/guide.html`
- Tourist: `https://ef.tour/` or `https://192.168.8.1:3000/`
- Health: `https://ef.tour/health` or `https://192.168.8.1:3000/health`


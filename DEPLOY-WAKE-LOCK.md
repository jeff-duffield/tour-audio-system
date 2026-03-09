# Deploy Wake Lock Feature for iPhone Auto-Lock Issue

## 🔍 The Problem

When listening to tour audio on an iPhone, the audio stops playing when the phone auto-locks. This happens because iOS suspends web pages when the screen locks to save battery.

## ✅ The Solution

Implemented the **Wake Lock API** to prevent the screen from auto-locking while audio is streaming.

**How it works:**
1. When user clicks "Start Listening", the app requests a wake lock
2. Wake lock keeps the screen on while audio is playing
3. When user clicks "Stop Listening", the wake lock is released
4. Screen can auto-lock normally when not listening

---

## 🚀 Deploy the Update

### Step 1: Deploy Updated Files

```bash
scp public/index.html root@192.168.8.1:/root/tour-audio-server/public/
scp public/tourist.html root@192.168.8.1:/root/tour-audio-server/public/
```

Password: `EFTours2026!`

### Step 2: No Server Restart Needed!

Just refresh the page in the browser - the changes are client-side only.

---

## ✅ Test It on iPhone

### Test 1: Verify Wake Lock Works

1. Open Safari on iPhone
2. Go to `https://ef.tour/` (click through security warning)
3. Click "Start Listening"
4. **Open Safari's Developer Console** (if available) or check behavior:
   - Screen should stay on even after normal auto-lock time
   - Audio should continue playing
5. Wait past your normal auto-lock time (e.g., 30 seconds, 1 minute)
6. **Expected:** Screen stays on, audio keeps playing ✅

### Test 2: Verify Wake Lock Releases

1. While listening, click "Stop Listening"
2. Wait for auto-lock time
3. **Expected:** Screen locks normally ✅

### Test 3: Check Console Logs

If you can access Safari's developer console:
1. Connect iPhone to Mac
2. Open Safari on Mac → Develop → [Your iPhone] → [ef.tour]
3. Look for console messages:
   - "Wake Lock acquired - screen will stay on" ✅
   - "Wake Lock released manually" (when stopping) ✅

---

## 📱 User Experience

### Before (Problem):
1. Start listening to audio
2. Put phone in pocket
3. Screen auto-locks after 30 seconds
4. **Audio stops** ❌
5. Have to unlock phone and restart

### After (Fixed):
1. Start listening to audio
2. Put phone in pocket (screen stays on)
3. **Audio keeps playing** ✅
4. Can listen for entire tour without interruption
5. When done, click "Stop Listening" and screen can lock normally

---

## 🔧 Technical Details

### What Changed:

**Added to both index.html and tourist.html:**

1. **Wake lock variable:**
   ```javascript
   let wakeLock = null;
   ```

2. **Request wake lock function:**
   ```javascript
   async function requestWakeLock() {
       if ('wakeLock' in navigator) {
           wakeLock = await navigator.wakeLock.request('screen');
           console.log('Wake Lock acquired - screen will stay on');
       }
   }
   ```

3. **Release wake lock function:**
   ```javascript
   async function releaseWakeLock() {
       if (wakeLock !== null) {
           await wakeLock.release();
           wakeLock = null;
       }
   }
   ```

4. **Integrated into startListening():**
   - Calls `await requestWakeLock()` after checking silent mode
   - Keeps screen on while audio is playing

5. **Integrated into stopListening():**
   - Calls `releaseWakeLock()` when stopping
   - Allows screen to lock normally when not listening

---

## 🌐 Browser Compatibility

### ✅ Supported:
- **iOS Safari 16.4+** (iPhone)
- Chrome/Edge on Android
- Chrome/Edge on desktop

### ⚠️ Fallback:
- If Wake Lock API is not supported, the app logs a message but continues to work
- Audio will still play, but screen may lock (old behavior)
- No errors or crashes

---

## 💡 Alternative Solutions (Not Implemented)

### Option 1: Tell users to change auto-lock settings
- ❌ Requires manual settings change on each device
- ❌ Users forget to change it back
- ❌ Not user-friendly for 40+ person groups

### Option 2: Use background audio API
- ❌ Requires service workers and more complex setup
- ❌ May not work reliably on iOS Safari
- ❌ Overkill for this use case

### Option 3: Keep playing silent audio
- ❌ Doesn't prevent screen lock
- ❌ Wastes battery
- ❌ Doesn't solve the problem

**Wake Lock API is the best solution!** ✅

---

## 🎯 Benefits

✅ **No user action required** - Works automatically
✅ **Battery efficient** - Only keeps screen on when needed
✅ **Reliable** - Native browser API
✅ **Simple** - Just a few lines of code
✅ **Graceful fallback** - Works on older devices too

---

## 🔍 Troubleshooting

### Screen still locks on iPhone?

**Check iOS version:**
- Wake Lock API requires iOS 16.4 or later
- Settings → General → About → Software Version
- If older, user needs to manually change auto-lock settings

**Check browser:**
- Must use Safari (not Chrome or Firefox on iOS)
- Other browsers on iOS don't support Wake Lock API yet

**Check console for errors:**
- Connect iPhone to Mac
- Safari → Develop → [iPhone] → [ef.tour]
- Look for "Wake Lock acquired" message

### Battery draining faster?

**This is expected:**
- Keeping screen on uses more battery than locking
- This is a trade-off for uninterrupted audio
- Users can manually lock screen if they want to save battery (audio will stop)

**Recommendation for users:**
- Lower screen brightness while listening
- Or use "Stop Listening" when taking breaks

---

## 📊 Summary

**Problem:** Audio stops when iPhone auto-locks

**Solution:** Wake Lock API prevents screen from locking during audio playback

**Files Updated:**
- ✅ public/index.html
- ✅ public/tourist.html

**Deploy now and test on iPhone!** 🚀


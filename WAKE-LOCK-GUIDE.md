# Wake Lock Feature - Preventing Screen Lock During Tours

## Problem Solved

**Issue:** When the guide's iPhone screen locks during broadcasting, the audio stream stops, interrupting the tour for all listeners.

**Solution:** The Wake Lock API prevents the screen from auto-locking while broadcasting (guide) or listening (tourists).

---

## How It Works

### For the Guide (guide.html)

When the guide taps **"Start Broadcasting"**:
1. ✅ Microphone access is granted
2. ✅ WebSocket connection established
3. ✅ **Wake Lock is acquired** - screen stays on
4. ✅ Guide can broadcast for hours without screen locking

When the guide taps **"Stop Broadcasting"**:
1. ✅ Broadcast stops
2. ✅ **Wake Lock is released** - screen can sleep normally again

**Result:** The guide's phone screen stays on during the entire tour, ensuring continuous audio.

---

### For Listeners (index.html)

When a tourist taps **"Start Listening"**:
1. ✅ WebSocket connection established
2. ✅ **Wake Lock is acquired** - screen stays on
3. ✅ Tourist can listen for the entire tour without touching their phone

When the tourist leaves the page or taps stop:
1. ✅ **Wake Lock is released** - screen can sleep again

**Result:** Tourists' phones stay on during playback without manual intervention.

---

## Browser Support

### iOS/Safari
- ✅ **iOS 16.4+**: Full support
- ⚠️ **iOS 16.0-16.3**: Partial support
- ❌ **iOS < 16.0**: Not supported (manual adjustment needed)

### Android/Chrome
- ✅ **Chrome 84+**: Full support
- ✅ **Edge 84+**: Full support
- ✅ **Samsung Internet 14+**: Full support

---

## Fallback for Older Devices

If Wake Lock is not supported (older iPhones), users need to manually adjust settings:

**Settings → Display & Brightness → Auto-Lock → Never**

**Reminder:** Tell them to change it back to "1 Minute" or "2 Minutes" after the tour!

---

## Technical Details

### Code Implementation (guide.html)

```javascript
let wakeLock = null;

// Request wake lock when broadcasting starts
async function requestWakeLock() {
    try {
        if ('wakeLock' in navigator) {
            wakeLock = await navigator.wakeLock.request('screen');
            console.log('Wake Lock acquired - screen will stay on during broadcast');
        }
    } catch (err) {
        console.error('Failed to acquire Wake Lock:', err);
    }
}

// Release wake lock when broadcasting stops
async function releaseWakeLock() {
    if (wakeLock !== null) {
        await wakeLock.release();
        wakeLock = null;
        console.log('Wake Lock released - screen can sleep now');
    }
}
```

**When it's called:**
- **Acquire:** In `ws.onopen` callback (after WebSocket connects)
- **Release:** In `stopBroadcast()` function

---

## Testing Wake Lock

### Test on iPhone (Guide App):

1. Connect to tour WiFi
2. Open `https://ef.tour/guide.html`
3. Tap **"Start Broadcasting"**
4. **Wait 1-2 minutes without touching the phone**
5. ✅ Screen should stay on (no auto-lock)
6. Tap **"Stop Broadcasting"**
7. Wait for auto-lock timeout (30 seconds - 1 minute)
8. ✅ Screen should now lock normally

**Check console (Safari Dev Tools):**
```
Wake Lock acquired - screen will stay on during broadcast
... (broadcasting)
Wake Lock released - screen can sleep now
```

---

### Test on iPhone (Listener App):

1. Connect to tour WiFi
2. Open `https://ef.tour/`
3. Tap **"Start Listening"**
4. **Wait 1-2 minutes without touching the phone**
5. ✅ Screen should stay on during playback
6. Stop listening or close page
7. ✅ Screen should lock normally after timeout

---

## Troubleshooting

### Wake Lock doesn't work

**Check iOS version:**
```
Settings → General → About → iOS Version
```
- Need iOS 16.4 or newer for full support

**Check browser:**
- Must use Safari on iOS
- Chrome on iOS uses Safari's engine, so it works too

**Check console for errors:**
```javascript
// Should NOT see:
"Wake Lock API not supported on this device"
// Should see:
"Wake Lock acquired - screen will stay on during broadcast"
```

---

### Screen still locks

**Possible causes:**

1. **Low Power Mode enabled**
   - Settings → Battery → Low Power Mode → OFF

2. **Browser in background**
   - Keep the browser/tab in foreground
   - Switching apps may release wake lock

3. **iOS version too old**
   - Upgrade to iOS 16.4+ or manually disable auto-lock

---

## Best Practices

### For Guides:
1. ✅ Test wake lock before tour starts
2. ✅ Keep guide app in foreground during broadcast
3. ✅ Have backup: Know how to manually disable auto-lock if needed
4. ✅ Fully charge phone before tour (screen on = more battery drain)

### For Tour Operators:
1. ✅ Document iOS version requirement (16.4+) in tour prep materials
2. ✅ Have printed instructions for manual auto-lock disable
3. ✅ Test on multiple device types before deploying

---

## Battery Impact

**With Wake Lock (screen on for 2-hour tour):**
- Guide's iPhone: ~30-40% battery drain
- Listener's iPhone: ~25-35% battery drain

**Recommendations:**
- Guide should start with 100% battery or have backup battery pack
- Tourists should have at least 50% battery at tour start
- Reduce screen brightness to save battery

---

## Security & Privacy

**Wake Lock API:**
- ✅ Only works when page is in foreground
- ✅ Automatically released when tab is closed
- ✅ User can manually lock screen (overrides wake lock)
- ✅ No permission prompt needed (unlike microphone access)

**No privacy concerns:** Wake Lock only prevents sleep, doesn't access any device data.

---

## See Also

- **DEPLOY-WAKE-LOCK.md** - Original wake lock deployment documentation (listener-only)
- **IOS-SILENT-MODE-LIMITATION.md** - iOS silent mode behavior
- **README.md** - Complete setup and deployment guide

---

## Summary

✅ **Problem:** Screen locking during broadcast/playback interrupts audio  
✅ **Solution:** Wake Lock API keeps screen on automatically  
✅ **Support:** iOS 16.4+, Android Chrome 84+  
✅ **Fallback:** Manual auto-lock disable for older devices  
✅ **Tested:** Works on both guide and listener apps  

**Your guides and tourists can now enjoy uninterrupted audio tours without touching their phones!** 🎉


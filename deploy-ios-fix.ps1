# Deploy iOS/Safari compatibility fixes

Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "  Deploying iOS/Safari Compatibility Fix" -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Fixes applied:" -ForegroundColor Green
Write-Host "  ✓ Guide: Test Microphone now uses Web Audio API (iOS compatible)"
Write-Host "  ✓ Tourist: Test Audio improved for Safari/iOS"
Write-Host "  ✓ Added explicit AudioContext.resume() for iOS"
Write-Host "  ✓ Better error handling and logging"
Write-Host ""

Write-Host "Copying files to router..." -ForegroundColor Yellow
scp public/guide.html root@192.168.8.1:/root/tour-audio-server/public/
scp public/tourist.html root@192.168.8.1:/root/tour-audio-server/public/

Write-Host ""
Write-Host "Files deployed successfully!" -ForegroundColor Green
Write-Host ""
Write-Host "No server restart needed - just refresh the pages on your iPhone!"
Write-Host ""
Write-Host "Testing on iPhone:" -ForegroundColor Cyan
Write-Host "  1. Open Safari on iPhone"
Write-Host "  2. Go to https://ef.tour/guide.html or https://ef.tour/tourist.html"
Write-Host "  3. Pull down to refresh (or close and reopen tab)"
Write-Host "  4. Try the test buttons!"
Write-Host ""
Write-Host "Important for iPhone:" -ForegroundColor Yellow
Write-Host "  📱 Make sure iPhone is NOT on silent mode (check physical switch)"
Write-Host "  🔊 Turn up device volume"
Write-Host "  ✅ Grant microphone permission when prompted"
Write-Host ""


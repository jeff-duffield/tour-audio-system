# Deploy server-side captive-portal detection fix
# (moves CNA-vs-real-Safari detection from client JS to server.js,
#  since iOS 17+ made the two indistinguishable from inside the page)
#
# Run this from your Windows computer. You'll be prompted for the
# router's root password by scp/ssh (same one used for other deploys).

$ROUTER_IP   = "192.168.8.1"
$ROUTER_PATH = "/root/tour-audio-server"

Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "  Deploying Captive Portal Detection Fix" -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Files:" -ForegroundColor Green
Write-Host "  server.js               (server-side isCaptiveRequest logic)"
Write-Host "  public/connected.html   (reads window.__EF_CAPTIVE_SERVER__)"
Write-Host "  public/welcome.html     (reads window.__EF_CAPTIVE_SERVER__)"
Write-Host ""

Write-Host "[1/2] Copying files to router..." -ForegroundColor Yellow
scp server.js "root@${ROUTER_IP}:${ROUTER_PATH}/server.js"
scp public/connected.html "root@${ROUTER_IP}:${ROUTER_PATH}/public/connected.html"
scp public/welcome.html "root@${ROUTER_IP}:${ROUTER_PATH}/public/welcome.html"

Write-Host ""
Write-Host "[2/2] Restarting tour-audio service (server.js changed)..." -ForegroundColor Yellow
ssh "root@${ROUTER_IP}" "/etc/init.d/tour-audio restart"

Write-Host ""
Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "Deployed. To watch captive-portal traffic live:" -ForegroundColor Green
Write-Host "  ssh root@${ROUTER_IP} `"logread -f | grep -E 'captive-probe|captive'`"" -ForegroundColor White
Write-Host ""
Write-Host "Then reconnect a phone to the tour Wi-Fi and watch for:" -ForegroundColor Green
Write-Host "  1. A /captive hit (RFC 8910 JSON fetch)"
Write-Host "  2. A /connected.html hit from the same IP within ~20s"
Write-Host "     -> should render with the Private Relay toggle instructions"
Write-Host "=========================================" -ForegroundColor Cyan

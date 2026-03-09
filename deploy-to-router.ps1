# PowerShell script to deploy tour-audio-server to the router
# Run this from your Windows computer

$ROUTER_IP = "192.168.8.1"
$ROUTER_USER = "root"
$ROUTER_PASSWORD = "EFTours2026!"
$ROUTER_PATH = "/root/tour-audio-server"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Tour Audio Server - Deployment Script" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Step 1: Copy files to router
Write-Host "[1/4] Copying files to router..." -ForegroundColor Yellow
Write-Host "Command: scp -r * root@${ROUTER_IP}:${ROUTER_PATH}/" -ForegroundColor Gray
Write-Host "Password: $ROUTER_PASSWORD" -ForegroundColor Gray
Write-Host ""
Write-Host "Please run this command manually:" -ForegroundColor Green
Write-Host "  scp -r * root@${ROUTER_IP}:${ROUTER_PATH}/" -ForegroundColor White
Write-Host ""

# Step 2: Generate SSL certificates (if needed)
Write-Host "[2/4] Generate SSL certificates on router (if not already done):" -ForegroundColor Yellow
Write-Host "  ssh root@${ROUTER_IP}" -ForegroundColor White
Write-Host "  cd ${ROUTER_PATH}" -ForegroundColor White
Write-Host "  mkdir -p ssl" -ForegroundColor White
Write-Host "  opkg update && opkg install openssl-util" -ForegroundColor White
Write-Host "  openssl req -x509 -newkey rsa:2048 -keyout ssl/server.key -out ssl/server.crt -days 365 -nodes -subj '/C=US/ST=State/L=City/O=EF Tours/CN=192.168.8.1'" -ForegroundColor White
Write-Host ""

# Step 3: Install dependencies
Write-Host "[3/4] Install dependencies on router:" -ForegroundColor Yellow
Write-Host "  ssh root@${ROUTER_IP}" -ForegroundColor White
Write-Host "  cd ${ROUTER_PATH}" -ForegroundColor White
Write-Host "  npm install --production" -ForegroundColor White
Write-Host ""

# Step 4: Restart server
Write-Host "[4/4] Restart the server:" -ForegroundColor Yellow
Write-Host "  ssh root@${ROUTER_IP}" -ForegroundColor White
Write-Host "  /etc/init.d/tour-audio restart" -ForegroundColor White
Write-Host ""

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "After deployment, test at:" -ForegroundColor Green
Write-Host "  Guide:   https://${ROUTER_IP}:3000/guide.html" -ForegroundColor White
Write-Host "  Tourist: https://${ROUTER_IP}:3000/tourist.html" -ForegroundColor White
Write-Host "  Health:  https://${ROUTER_IP}:3000/health" -ForegroundColor White
Write-Host "========================================" -ForegroundColor Cyan


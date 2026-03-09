# PowerShell script to generate self-signed certificate for local testing
# This creates certificates in the ssl directory

# Create ssl directory if it doesn't exist
$sslDir = Join-Path $PSScriptRoot "ssl"
if (-not (Test-Path $sslDir)) {
    New-Item -ItemType Directory -Path $sslDir | Out-Null
}

# Generate self-signed certificate
$cert = New-SelfSignedCertificate -DnsName "192.168.8.1", "localhost" -CertStoreLocation "Cert:\CurrentUser\My" -NotAfter (Get-Date).AddYears(1)

# Export certificate
$certPath = Join-Path $sslDir "server.crt"
$keyPath = Join-Path $sslDir "server.key"
$pfxPath = Join-Path $sslDir "server.pfx"

# Export to PFX (with no password for simplicity)
$certPassword = ConvertTo-SecureString -String "" -Force -AsPlainText
Export-PfxCertificate -Cert $cert -FilePath $pfxPath -Password $certPassword | Out-Null

# Convert PFX to PEM format (requires OpenSSL)
Write-Host "Certificate generated at: $pfxPath"
Write-Host ""
Write-Host "To convert to PEM format for Node.js, you need OpenSSL."
Write-Host "If you have Git for Windows installed, you can use:"
Write-Host ""
Write-Host "  & 'C:\Program Files\Git\usr\bin\openssl.exe' pkcs12 -in ssl\server.pfx -nocerts -nodes -out ssl\server.key -passin pass:"
Write-Host "  & 'C:\Program Files\Git\usr\bin\openssl.exe' pkcs12 -in ssl\server.pfx -clcerts -nokeys -out ssl\server.crt -passin pass:"
Write-Host ""
Write-Host "Or install OpenSSL from: https://slproweb.com/products/Win32OpenSSL.html"


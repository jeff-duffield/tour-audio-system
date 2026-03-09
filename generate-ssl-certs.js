const { execSync } = require('child_process');
const fs = require('fs');
const path = require('path');

// Create ssl directory if it doesn't exist
const sslDir = path.join(__dirname, 'ssl');
if (!fs.existsSync(sslDir)) {
    fs.mkdirSync(sslDir);
}

console.log('Generating self-signed SSL certificates...');
console.log('This requires OpenSSL to be installed.');
console.log('');
console.log('If you are on the router, run:');
console.log('  opkg update');
console.log('  opkg install openssl-util');
console.log('');
console.log('Then run this command:');
console.log('  openssl req -x509 -newkey rsa:2048 -keyout ssl/server.key -out ssl/server.crt -days 365 -nodes -subj "/C=US/ST=State/L=City/O=EF Tours/CN=192.168.8.1"');
console.log('');
console.log('For testing on Windows, you can use Git Bash or WSL to run the openssl command.');


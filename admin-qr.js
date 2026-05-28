#!/usr/bin/env node
/**
 * admin-qr.js — Print the TD Admin URL for generating a QR code card.
 *
 * Usage:
 *   node admin-qr.js                    (reads existing token)
 *   node admin-qr.js --rotate           (generates a fresh token, invalidating the old one)
 *   node admin-qr.js --host audio.eftours.travel     (override hostname, default: audio.eftours.travel)
 *
 * The printed URL can be pasted into any QR generator (e.g. qr.io, qrcode-monkey.com)
 * and printed onto the TD's onboarding card.
 */

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const TOKEN_PATHS = [
    '/etc/ef-admin-token',
    path.join(__dirname, 'ef-admin-token')
];

const args = process.argv.slice(2);
const rotate = args.includes('--rotate');
const hostIdx = args.indexOf('--host');
const host = hostIdx >= 0 ? args[hostIdx + 1] : 'audio.eftours.travel';

function readToken() {
    for (const p of TOKEN_PATHS) {
        try {
            if (fs.existsSync(p)) {
                const t = fs.readFileSync(p, 'utf8').trim();
                if (t) return { token: t, path: p };
            }
        } catch (e) { /* try next */ }
    }
    return null;
}

function writeToken(token) {
    for (const p of TOKEN_PATHS) {
        try {
            fs.writeFileSync(p, token + '\n', { mode: 0o600 });
            return p;
        } catch (e) { /* try next */ }
    }
    return null;
}

let existing = readToken();
let token, tokenPath;

if (rotate || !existing) {
    token = crypto.randomBytes(24).toString('hex');
    tokenPath = writeToken(token);
    if (!tokenPath) {
        console.error('ERROR: Could not write token to any of:', TOKEN_PATHS.join(', '));
        process.exit(1);
    }
    console.log(rotate ? 'Token rotated.' : 'New token generated.');
    console.log('Saved to:', tokenPath);
    console.log('RESTART the tour-audio service so the server picks up the new token:');
    console.log('    /etc/init.d/tour-audio restart');
    console.log('');
} else {
    token = existing.token;
    tokenPath = existing.path;
}

const url = `https://${host}/admin?token=${token}`;

// api.qrserver.com returns a QR code PNG directly when opened in a browser — no login needed
const qrImageUrl = 'https://api.qrserver.com/v1/create-qr-code/?size=400x400&data=' + encodeURIComponent(url);

// The admin page also exposes a /api/admin/qr endpoint that renders the QR inline
const adminQrPage = `https://${host}/admin/qr?token=${token}`;

const bar = '═'.repeat(72);
console.log(bar);
console.log(' TD ADMIN ACCESS URL');
console.log(bar);
console.log('');
console.log(' ' + url);
console.log('');
console.log(bar);
console.log(' QUICKEST WAY TO GET THE QR CODE');
console.log(bar);
console.log(' Open this link in any browser — it loads the QR image directly:');
console.log('');
console.log(' ' + qrImageUrl);
console.log('');
console.log(' Right-click the image → Save → print onto the TD onboarding card.');
console.log('');
console.log(' Alternatively, visit the admin page itself (after scanning/pasting');
console.log(' the token URL above) — it shows the QR code on screen so another');
console.log(' device can scan it from there.');
console.log('');
console.log(bar);
console.log(' SECURITY NOTES');
console.log(bar);
console.log(' • Token file:   ' + tokenPath);
console.log(' • Token length: ' + token.length + ' hex chars (' + (token.length / 2) + ' bytes)');
console.log(' • To invalidate all existing QR codes, run: node admin-qr.js --rotate');
console.log(' • Sessions last 8 hours, then the QR code must be scanned again.');
console.log('');

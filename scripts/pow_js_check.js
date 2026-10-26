// Checks the browser's proof-of-work code (static/js/pow.js) against Node's own SHA-256: node scripts/pow_js_check.js [path to pow.js]
const crypto = require('crypto');
const fs = require('fs');
global.window = {};
eval(fs.readFileSync(process.argv[2] || 'kaarigarsetu-java/src/main/webapp/static/js/pow.js', 'utf8'));
const KS = window.KS;
function hex(words) { return Array.from(words).map(w => (w >>> 0).toString(16).padStart(8, '0')).join(''); }
let bad = 0;
for (let len = 0; len < 300; len++) {
  const bytes = Uint8Array.from({ length: len }, (_, i) => (i * 7 + len) & 0x7f);
  const want = crypto.createHash('sha256').update(Buffer.from(bytes)).digest('hex');
  if (hex(KS._sha256(bytes)) !== want) { bad++; console.log('MISMATCH at length', len); }
}
console.log('sha256 lengths 0..299 checked, mismatches:', bad);
const challenge = 'v1.1999999999.16.AAAAAAAAAAAAAAAA.signaturesignature';
const t = Date.now();
KS.solvePow(challenge, 16).then(counter => {
  const digest = crypto.createHash('sha256').update(challenge + ':' + counter).digest();
  let zeros = 0; for (const b of digest) { if (b === 0) zeros += 8; else { zeros += Math.clz32(b) - 24; break; } }
  console.log('solved 16 bits: counter', counter, 'zero bits (checked with node crypto):', zeros, 'in', Date.now() - t, 'ms');
  if (zeros < 16) process.exit(1);
  if (bad) process.exit(1);
});

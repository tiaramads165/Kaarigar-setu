/*
 * Proof of work for requesting a sign-in code from a browser the server does not know yet (see AuthService.requestLoginOtp).
 * The server sends a puzzle; this finds a number whose SHA-256, together with the puzzle, starts with the required number of zero bits.
 * About 65,000 tries for the default difficulty: a fraction of a second. It is plain SHA-256 written out here because the page may be opened over
 * http on a phone, where the browser's built-in crypto API is not available.
 */
(function () {
  'use strict';
  window.KS = window.KS || {};

  var K = new Uint32Array([
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
    0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
    0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
    0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
  ]);

  /** SHA-256 of a byte array; returns the 8 state words (big-endian) of the digest. */
  function sha256(bytes) {
    var length = bytes.length;
    var total = ((length + 9 + 63) >> 6) << 6;
    var buffer = new Uint8Array(total);
    buffer.set(bytes);
    buffer[length] = 0x80;
    var bitsHigh = Math.floor(length / 0x20000000);
    var bitsLow = (length << 3) >>> 0;
    buffer[total - 8] = bitsHigh >>> 24; buffer[total - 7] = bitsHigh >>> 16; buffer[total - 6] = bitsHigh >>> 8; buffer[total - 5] = bitsHigh;
    buffer[total - 4] = bitsLow >>> 24; buffer[total - 3] = bitsLow >>> 16; buffer[total - 2] = bitsLow >>> 8; buffer[total - 1] = bitsLow;

    var h = new Uint32Array([0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19]);
    var w = new Uint32Array(64);
    for (var offset = 0; offset < total; offset += 64) {
      for (var i = 0; i < 16; i++) {
        var j = offset + i * 4;
        w[i] = (buffer[j] << 24) | (buffer[j + 1] << 16) | (buffer[j + 2] << 8) | buffer[j + 3];
      }
      for (i = 16; i < 64; i++) {
        var a15 = w[i - 15], a2 = w[i - 2];
        var s0 = ((a15 >>> 7) | (a15 << 25)) ^ ((a15 >>> 18) | (a15 << 14)) ^ (a15 >>> 3);
        var s1 = ((a2 >>> 17) | (a2 << 15)) ^ ((a2 >>> 19) | (a2 << 13)) ^ (a2 >>> 10);
        w[i] = (w[i - 16] + s0 + w[i - 7] + s1) | 0;
      }
      var A = h[0], B = h[1], C = h[2], D = h[3], E = h[4], F = h[5], G = h[6], H = h[7];
      for (i = 0; i < 64; i++) {
        var S1 = ((E >>> 6) | (E << 26)) ^ ((E >>> 11) | (E << 21)) ^ ((E >>> 25) | (E << 7));
        var ch = (E & F) ^ (~E & G);
        var t1 = (H + S1 + ch + K[i] + w[i]) | 0;
        var S0 = ((A >>> 2) | (A << 30)) ^ ((A >>> 13) | (A << 19)) ^ ((A >>> 22) | (A << 10));
        var maj = (A & B) ^ (A & C) ^ (B & C);
        var t2 = (S0 + maj) | 0;
        H = G; G = F; F = E; E = (D + t1) | 0; D = C; C = B; B = A; A = (t1 + t2) | 0;
      }
      h[0] += A; h[1] += B; h[2] += C; h[3] += D; h[4] += E; h[5] += F; h[6] += G; h[7] += H;
    }
    return h;
  }

  function leadingZeroBits(words) {
    var bits = 0;
    for (var i = 0; i < words.length; i++) {
      if (words[i] === 0) { bits += 32; continue; }
      return bits + Math.clz32(words[i]);
    }
    return bits;
  }

  function ascii(text) {
    var out = new Uint8Array(text.length);
    for (var i = 0; i < text.length; i++) out[i] = text.charCodeAt(i) & 0x7f;
    return out;
  }

  /**
   * Finds the counter for a puzzle. Works in short slices so the page stays responsive; resolves with the counter as a decimal string.
   * The text hashed is exactly: puzzle + ":" + counter, as the server checks it.
   */
  function solve(challenge, bits) {
    return new Promise(function (resolve) {
      var counter = 0;
      function slice() {
        var stop = Date.now() + 25;
        while (Date.now() < stop) {
          for (var n = 0; n < 500; n++, counter++) {
            if (leadingZeroBits(sha256(ascii(challenge + ':' + counter))) >= bits) { resolve(String(counter)); return; }
          }
        }
        setTimeout(slice, 0);
      }
      slice();
    });
  }

  window.KS.solvePow = solve;
  window.KS._sha256 = sha256;               // exposed for the tests only
  window.KS._leadingZeroBits = leadingZeroBits;
})();

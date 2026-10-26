/* Converts whatever MediaRecorder produced (webm/opus on Android, mp4/aac on iOS) into the one format the
   server accepts: 16 kHz, mono, 16-bit PCM WAV. 60 s of that is ~1.9 MB, inside the 2 MB upload limit. */
(function () {
  'use strict';
  var TARGET_RATE = 16000;

  function decode(ctx, buf) {
    return new Promise(function (resolve, reject) {
      var p = ctx.decodeAudioData(buf, resolve, reject); // callback form for older Safari
      if (p && p.then) p.then(resolve, reject);
    });
  }

  function toMono(audio) {
    var n = audio.length, ch = audio.numberOfChannels, out = new Float32Array(n);
    for (var c = 0; c < ch; c++) {
      var data = audio.getChannelData(c);
      for (var i = 0; i < n; i++) out[i] += data[i] / ch;
    }
    return out;
  }

  function resample(samples, fromRate) {
    if (fromRate === TARGET_RATE) return samples;
    var ratio = fromRate / TARGET_RATE, n = Math.floor(samples.length / ratio), out = new Float32Array(n), i;
    if (ratio > 1) {
      // Downsampling: average every source sample under each output sample (a box low-pass filter). Plain interpolation
      // would fold frequencies above 8 kHz back into the speech band as noise, which hurts recognition.
      for (i = 0; i < n; i++) {
        var from = Math.floor(i * ratio), to = Math.min(samples.length, Math.max(from + 1, Math.floor((i + 1) * ratio))), sum = 0;
        for (var k = from; k < to; k++) sum += samples[k];
        out[i] = sum / (to - from);
      }
      return out;
    }
    for (i = 0; i < n; i++) {
      var pos = i * ratio, i0 = Math.floor(pos), i1 = Math.min(i0 + 1, samples.length - 1), f = pos - i0;
      out[i] = samples[i0] * (1 - f) + samples[i1] * f;
    }
    return out;
  }

  function encode(samples) {
    var buf = new ArrayBuffer(44 + samples.length * 2), v = new DataView(buf);
    function str(o, s) { for (var i = 0; i < s.length; i++) v.setUint8(o + i, s.charCodeAt(i)); }
    str(0, 'RIFF'); v.setUint32(4, 36 + samples.length * 2, true); str(8, 'WAVE');
    str(12, 'fmt '); v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true);
    v.setUint32(24, TARGET_RATE, true); v.setUint32(28, TARGET_RATE * 2, true); v.setUint16(32, 2, true); v.setUint16(34, 16, true);
    str(36, 'data'); v.setUint32(40, samples.length * 2, true);
    for (var i = 0; i < samples.length; i++) {
      var s = Math.max(-1, Math.min(1, samples[i]));
      v.setInt16(44 + i * 2, s < 0 ? s * 0x8000 : s * 0x7fff, true);
    }
    return new Blob([buf], { type: 'audio/wav' });
  }

  window.Wav = {
    fromBlob: function (blob, maxSeconds) {
      return blob.arrayBuffer().then(function (buf) {
        var AC = window.AudioContext || window.webkitAudioContext;
        var ctx;
        try { ctx = new AC({ sampleRate: TARGET_RATE }); } catch (e) { ctx = new AC(); }   // asks the browser to resample properly while decoding
        return decode(ctx, buf).then(function (audio) {
          var samples = resample(toMono(audio), audio.sampleRate);
          samples = samples.subarray(0, maxSeconds * TARGET_RATE);
          if (ctx.close) ctx.close();
          return encode(samples);
        });
      });
    }
  };
})();

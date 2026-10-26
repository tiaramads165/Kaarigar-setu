"""Builds the Hindi speech benchmark (macOS only: uses the built-in Lekha voice). Writes refs.txt and clean*/noisy*.wav
to /tmp/ks-speech/bench. The "noisy" clips imitate a quiet, band-limited phone recording with room noise.
    python benchmarks/make_hindi_clips.py   (run from /tmp/ks-speech/bench) then   python benchmarks/stt_benchmark.py <model>
SYNTHETIC speech: it ranks models, it does not predict accuracy on real workers."""
import subprocess, wave, numpy as np, os
S = ["मैंने इलेक्ट्रीशियन का काम अपने सीनियर से सीखा है और उन्होंने दो साल तक मुझे ट्रेनिंग दी है।",
     "बिजली मीटर घर में आने वाली वोल्टेज और करंट का मापन करके कुल खर्च हुई बिजली की खपत बताता है।",
     "मैं रोज़ पेचकस, प्लायर और टेस्टर का इस्तेमाल करता हूं।",
     "घर की वायरिंग में ताँबे का तार इस्तेमाल होता है और एल्यूमिनियम का तार बाहर की लाइन में।",
     "एसी करंट अपनी दिशा बदलता रहता है और डीसी एक ही दिशा में बहता है।"]
open("refs.txt","w").write("\n".join(S))
rng = np.random.default_rng(0)
for i, t in enumerate(S):
    subprocess.run(["say","-v","Lekha","-o",f"s{i}.aiff",t],check=True)
    subprocess.run(["afconvert","-f","WAVE","-d","LEI16@16000","-c","1",f"s{i}.aiff",f"clean{i}.wav"],check=True)
    with wave.open(f"clean{i}.wav") as w: x = np.frombuffer(w.readframes(w.getnframes()),"<i2").astype(np.float32)/32768
    # "quiet phone in a noisy room": band-limit to 4 kHz (8 kHz sampling), low level, noise at ~15 dB SNR, mild clipping-free
    y = x[::2] if False else np.interp(np.arange(0,len(x),2), np.arange(len(x)), x)       # crude 8 kHz
    y = np.interp(np.arange(len(x))/2, np.arange(len(y)), y)                              # back to 16 kHz (band-limited)
    noise = rng.normal(0, 1, len(y)); noise *= np.sqrt(np.mean(y**2) / 10**(15/10)) / np.sqrt(np.mean(noise**2))
    z = (y + noise) * 0.05                                                               # very quiet recording
    with wave.open(f"noisy{i}.wav","wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000); w.writeframes((np.clip(z,-1,1)*32767).astype("<i2").tobytes())
    os.remove(f"s{i}.aiff")
print("made", len(S), "sentences x 2 conditions")

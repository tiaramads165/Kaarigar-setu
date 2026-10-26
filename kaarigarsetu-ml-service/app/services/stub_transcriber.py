"""TEST FIXTURE, not speech-to-text (STT_PROVIDER=stub): it IGNORES the audio (apart from a test marker for the spoken challenge code) and returns canned sentences in rotation, so the
automated tests can run the whole pipeline without a model. Every reply is labelled so it can never be mistaken for what a
person said. For hands-on use run with STT_PROVIDER=whisper."""
from __future__ import annotations

import itertools
import random
from itertools import cycle

# Keep every sentence under the replay check's minimum length (REPLAY_TEXT_MIN_SHINGLES = 14 word trigrams, see scripts/e2e.py): the fixture repeats them across test accounts, and a long
# repeated sentence would (correctly) be flagged as copied words. tests/test_stub_transcriber.py counts them.
_SENTENCES = cycle([
    "मैं रोज़ पेचकस, प्लायर और टेस्टर का इस्तेमाल करता हूं।",
    "मैं ताँबे का तार और एल्यूमिनियम का तार इस्तेमाल करता हूं, वायरिंग में।",
    "मैंने आईटीआई से सीखा और फिर दो साल एक सीनियर के साथ काम किया।",
    "मीटर बिजली की खपत को नापता है और उसी से बिल बनता है।",
    "एसी करंट दिशा बदलता रहता है और डीसी एक ही दिशा में बहता है।",
])


LABEL = "[STUB: your audio was NOT transcribed] "
TEXT_MARKER = b"KSTEXT:"      # a test recording may name one of these long fixed texts, so the replay check on WORDS can be exercised (see scripts/e2e.py)
LONG_TEXTS = (
    "मैं हर काम से पहले मुख्य स्विच बंद करता हूं फिर टेस्टर से तार की जांच करता हूं और उसके बाद ही काम शुरू करता हूं ताकि कोई हादसा न हो",
    "ताँबे का तार बिजली को अच्छी तरह चलाता है और जल्दी गर्म नहीं होता इसलिए घर की वायरिंग में मैं हमेशा ताँबे का तार इस्तेमाल करता हूं और अर्थिंग जरूर लगाता हूं",
    "सर्किट ब्रेकर तब ट्रिप करता है जब लोड बहुत ज्यादा हो जाता है और इस तरह वह वायरिंग को आग लगने से बचाता है इसलिए सही क्षमता का ब्रेकर लगाना बहुत जरूरी है",
)
WORDS_MARKER = b"KSWORDS:"    # ... or a long text made from the number after the marker: the same number gives the same words, a new number gives new words (so a re-run of the tests starts clean)
_SYLLABLES = [c + m for c in "कखगचजटतदनपबमरलवसह" for m in ("", "ा", "ि", "ी", "ु", "े", "ो")]
CODE_MARKER = b"KSCODE:"      # a test recording may carry the digits of the spoken challenge code after this marker (see scripts/e2e.py); real audio never does


class StubTranscriber:
    def transcribe(self, audio: bytes, filename: str, language: str | None) -> dict[str, str]:
        if not audio:
            raise ValueError("audio must not be empty")
        spoken = ""
        at = audio.find(CODE_MARKER)
        if at >= 0:                                           # the fixture "hears" the code a test put in the recording, so the challenge can be exercised without a speech model
            digits = audio[at + len(CODE_MARKER): at + len(CODE_MARKER) + 3].decode("ascii", "ignore")
            if digits.isdigit():
                spoken = " " + " ".join(digits)
        text = next(_SENTENCES)
        at = audio.find(TEXT_MARKER)
        if at >= 0 and audio[at + len(TEXT_MARKER): at + len(TEXT_MARKER) + 1].isdigit():
            text = LONG_TEXTS[int(audio[at + len(TEXT_MARKER): at + len(TEXT_MARKER) + 1]) % len(LONG_TEXTS)]
        at = audio.find(WORDS_MARKER)
        if at >= 0:
            digits = audio[at + len(WORDS_MARKER): at + len(WORDS_MARKER) + 9].split(b"\x00")[0].decode("ascii", "ignore")
            digits = "".join(itertools.takewhile(str.isdigit, digits))
            if digits:
                rng = random.Random(int(digits))
                text = " ".join("".join(rng.choice(_SYLLABLES) for _ in range(rng.randint(2, 4))) for _ in range(30))
        return {"transcript": LABEL + text + spoken, "language": language or "hi"}

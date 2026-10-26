"""The labelled test transcriber repeats a few canned sentences across test accounts. The replay check on WORDS ignores answers shorter than REPLAY_TEXT_MIN_SHINGLES (14)
word trigrams, so the canned sentences must stay shorter than that, or the end-to-end tests would flag their own fixtures as copied."""
import re

from app.services import stub_transcriber

MIN_SHINGLES = 14


def trigrams(text: str) -> int:
    words = [w for w in re.findall(r"[\wऀ-ॣ०-ॿ]+", text.lower()) if not w.isdigit()]
    return len({tuple(words[i:i + 3]) for i in range(len(words) - 2)})


def test_the_rotating_sentences_are_too_short_for_the_text_replay_check():
    sentences = [next(stub_transcriber._SENTENCES) for _ in range(5)]
    assert all(trigrams(s) < MIN_SHINGLES - 1 for s in sentences), [trigrams(s) for s in sentences]   # the [STUB...] label is an annotation: not counted


def test_the_long_fixed_texts_are_long_enough_to_be_checked():
    assert all(trigrams(t) >= MIN_SHINGLES + 4 for t in stub_transcriber.LONG_TEXTS)


def test_the_words_marker_gives_the_same_long_text_for_the_same_number_and_new_text_for_a_new_one():
    t = stub_transcriber.StubTranscriber()
    a1 = t.transcribe(b"RIFF....KSWORDS:000000042" + b"\x00" * 8, "a.wav", "hi")["transcript"]
    a2 = t.transcribe(b"xx KSWORDS:000000042", "b.wav", "hi")["transcript"]
    b = t.transcribe(b"xx KSWORDS:000000043", "b.wav", "hi")["transcript"]
    assert a1 == a2 and a1 != b
    words = lambda text: set(text.replace(stub_transcriber.LABEL, "").split())
    assert trigrams(a1) >= MIN_SHINGLES + 10 and len(words(a1) & words(b)) < 5

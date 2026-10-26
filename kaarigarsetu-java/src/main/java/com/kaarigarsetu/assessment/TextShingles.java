package com.kaarigarsetu.assessment;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The words of an answer reduced to what a replay keeps and a fresh answer does not: runs of three consecutive words. Letters are lower-cased and
 * normalised, punctuation and numbers are dropped (the spoken challenge code is a number and differs every time), and each run of three words is one
 * "shingle". The same words said again give the same shingles whatever the audio did (a changed speed, a different channel, a synthetic voice reading a
 * stolen transcript); two people answering the same question in their own words share very few.
 */
public final class TextShingles {
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{M}]+");
    /** Annotations a recogniser writes for sounds that are not words ("[Music]", "[BLANK_AUDIO]") are not part of what was said. */
    private static final Pattern ANNOTATION = Pattern.compile("\\[[^\\]]{0,80}]");
    /** An answer longer than this contributes only its first shingles: enough to recognise it, bounded in size. */
    public static final int MAX = 400;

    private TextShingles() {
    }

    public static Set<String> of(String transcript) {
        Set<String> out = new LinkedHashSet<>();
        if (transcript == null) return out;
        List<String> words = new ArrayList<>();
        Matcher matcher = WORD.matcher(ANNOTATION.matcher(Normalizer.normalize(transcript, Normalizer.Form.NFC).toLowerCase(Locale.ROOT)).replaceAll(" "));
        while (matcher.find()) {
            if (!SpokenCode.isNumberToken(matcher.group())) words.add(matcher.group());
        }
        for (int i = 0; i + 2 < words.size() && out.size() < MAX; i++) out.add(words.get(i) + " " + words.get(i + 1) + " " + words.get(i + 2));
        return out;
    }
}

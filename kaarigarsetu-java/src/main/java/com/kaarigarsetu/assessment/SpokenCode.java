package com.kaarigarsetu.assessment;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Did the worker say the code? A speech recogniser may write a spoken number as digits ("472"), as separate digits ("4 7 2"), as English words ("four seven
 * two"), as Hindi words ("चार सात दो"), as romanised Hindi ("char saat do") or in Devanagari numerals ("४७२"), so all of those are read as digits first.
 * The code counts as heard when its digits appear one after another, with at most one other word between neighbours (a recogniser may insert a filler);
 * digits scattered through an answer do not add up to a code.
 */
public final class SpokenCode {
    private static final Pattern TOKEN = Pattern.compile("\\p{Nd}+|[\\p{L}\\p{M}]+");
    private static final Map<String, Integer> WORDS = Map.ofEntries(
            Map.entry("zero", 0), Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4), Map.entry("five", 5),
            Map.entry("six", 6), Map.entry("seven", 7), Map.entry("eight", 8), Map.entry("nine", 9),
            Map.entry("शून्य", 0), Map.entry("जीरो", 0), Map.entry("ज़ीरो", 0), Map.entry("एक", 1), Map.entry("दो", 2), Map.entry("तीन", 3), Map.entry("चार", 4),
            Map.entry("पांच", 5), Map.entry("पाँच", 5), Map.entry("छह", 6), Map.entry("छः", 6), Map.entry("छे", 6), Map.entry("छ", 6), Map.entry("सात", 7),
            Map.entry("आठ", 8), Map.entry("नौ", 9), Map.entry("नो", 9),
            Map.entry("shunya", 0), Map.entry("ek", 1), Map.entry("do", 2), Map.entry("teen", 3), Map.entry("char", 4), Map.entry("chaar", 4), Map.entry("paanch", 5),
            Map.entry("panch", 5), Map.entry("chhe", 6), Map.entry("chhah", 6), Map.entry("che", 6), Map.entry("saat", 7), Map.entry("sat", 7), Map.entry("aath", 8),
            Map.entry("aat", 8), Map.entry("nau", 9));

    private SpokenCode() {
    }

    private record Digit(int value, int token) {
    }

    /** Is this word (or digit string) one a number is written with? Used to leave numbers out of the word comparison of answers. */
    public static boolean isNumberToken(String token) {
        if (token.isEmpty()) return false;
        if (Character.isDigit(token.codePointAt(0))) return true;
        return WORDS.containsKey(Normalizer.normalize(token, Normalizer.Form.NFC).toLowerCase(Locale.ROOT));
    }

    public static boolean heard(String transcript, String code) {
        if (transcript == null || code == null || code.isEmpty()) return false;
        List<Digit> digits = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(Normalizer.normalize(transcript, Normalizer.Form.NFC).toLowerCase(Locale.ROOT));
        int token = 0;
        while (matcher.find()) {
            String text = matcher.group();
            if (Character.isDigit(text.codePointAt(0))) {
                for (int i = 0; i < text.length(); ) {
                    int cp = text.codePointAt(i);
                    digits.add(new Digit(Character.digit(cp, 10), token));
                    i += Character.charCount(cp);
                }
            } else {
                Integer word = WORDS.get(text);
                if (word != null) digits.add(new Digit(word, token));
            }
            token++;
        }
        for (int start = 0; start + code.length() <= digits.size(); start++) {
            boolean match = true;
            for (int i = 0; i < code.length() && match; i++) {
                Digit current = digits.get(start + i);
                match = current.value() == code.charAt(i) - '0' && (i == 0 || current.token() - digits.get(start + i - 1).token() <= 2);
            }
            if (match) return true;
        }
        return false;
    }
}

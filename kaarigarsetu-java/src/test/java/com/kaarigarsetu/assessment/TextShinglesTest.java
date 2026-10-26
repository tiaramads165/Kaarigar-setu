package com.kaarigarsetu.assessment;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TextShinglesTest {
    @Test
    void anAnswerBecomesItsRunsOfThreeWordsWithoutCaseOrPunctuation() {
        assertEquals(Set.of("i switch off", "switch off the", "off the main", "the main supply"), TextShingles.of("I switch OFF the main, supply!"));
    }

    @Test
    void numbersAndRecogniserAnnotationsAreLeftOut() {
        Set<String> without = TextShingles.of("I always switch off the main supply first");
        assertEquals(without, TextShingles.of("[Music] I always switch off the main supply first 472"));
        assertEquals(without, TextShingles.of("I always switch off the main supply first four seven two"));
        assertEquals(without, TextShingles.of("I always switch off the main supply first चार सात दो [BLANK_AUDIO]"));
    }

    @Test
    void theSameWordsGiveTheSameSetWhateverTheAudioDid() {
        assertEquals(TextShingles.of("मैं हर काम से पहले स्विच बंद करता हूं"), TextShingles.of("मैं हर काम से पहले, स्विच बंद करता हूं।"));
    }

    @Test
    void shortOrEmptyTextHasNoShinglesAndLongTextIsBounded() {
        assertTrue(TextShingles.of(null).isEmpty());
        assertTrue(TextShingles.of("").isEmpty());
        assertTrue(TextShingles.of("two words").isEmpty());
        StringBuilder letters = new StringBuilder();
        for (int i = 0; i < 2000; i++) letters.append(wordNumber(i)).append(' ');
        assertEquals(TextShingles.MAX, TextShingles.of(letters.toString()).size());
    }

    private static String wordNumber(int n) {
        StringBuilder word = new StringBuilder("q");
        do {
            word.append((char) ('a' + n % 26));
            n /= 26;
        } while (n > 0);
        return word.toString();
    }

    @Test
    void differentAnswersToTheSameQuestionShareFewShingles() {
        Set<String> one = TextShingles.of("मैं रोज़ पेचकस प्लायर और टेस्टर का इस्तेमाल करता हूं और काम से पहले मुख्य स्विच बंद कर देता हूं");
        Set<String> two = TextShingles.of("मेरे पास पेचकस और प्लायर हैं जिनसे मैं घर की वायरिंग करता हूं और स्विच बंद रखता हूं");
        long shared = one.stream().filter(two::contains).count();
        assertTrue(shared <= 1 && shared < 0.1 * one.size() + 1, "a common phrase or two, never the answer: " + shared + " of " + one.size());
    }
}

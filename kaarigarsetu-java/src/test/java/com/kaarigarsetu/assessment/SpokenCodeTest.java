package com.kaarigarsetu.assessment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpokenCodeTest {
    @Test
    void theCodeIsHeardHoweverARecogniserWritesNumbers() {
        for (String transcript : new String[]{
                "main pehle switch band karta hoon 472",
                "I switch off the supply first, 4 7 2",
                "and then I test the wire. Four seven two.",
                "FOUR, SEVEN, TWO",
                "मैं स्विच बंद करता हूँ। चार सात दो",
                "मैं स्विच बंद करता हूँ ४७२",
                "char saat do",
                "the code is 4-7-2 thank you",
                "four, uh, seven, two",
                "४ ७ 2"}) {
            assertTrue(SpokenCode.heard(transcript, "472"), transcript);
        }
    }

    @Test
    void aWrongOrMissingOrScatteredCodeIsNotHeard() {
        for (String transcript : new String[]{"", "no number at all in this answer", "four seven three", "4 7", "four seven", "two seven four", "the number said is not that one: 4 7 5",
                "I use 4 wires and 7 clamps and then 2 screws",             // digits spread through an answer do not add up to a code
                "four then a very long stretch of other words before seven and then two"}) {
            assertFalse(SpokenCode.heard(transcript, "472"), transcript);
        }
        assertFalse(SpokenCode.heard(null, "472"));
        assertFalse(SpokenCode.heard("472", null));
        assertFalse(SpokenCode.heard("472", ""));
    }

    @Test
    void theCodeCanSitAnywhereInTheAnswerAndOtherNumbersDoNotHideIt() {
        assertTrue(SpokenCode.heard("472 is first and then the rest of my long answer", "472"));
        assertTrue(SpokenCode.heard("I have 12 years of work, and the number is 5 9 1", "591"));
        assertTrue(SpokenCode.heard("9 5 9 1", "591"), "a stray digit before it is fine");
    }

    @Test
    void numberWordsAreRecognisedAsNumbersForTheWordComparison() {
        for (String number : new String[]{"4", "४", "four", "चार", "char", "nine", "नौ"}) assertTrue(SpokenCode.isNumberToken(number), number);
        for (String word : new String[]{"wire", "तार", "", "fourth"}) assertFalse(SpokenCode.isNumberToken(word), word);
    }
}

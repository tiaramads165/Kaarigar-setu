package com.kaarigarsetu.util;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalJsonTest {
    private static String canon(String json) {
        return CanonicalJson.canonicalize(JsonParser.parseString(json));
    }

    @Test
    void sortsMembersAndRemovesWhitespace() {
        assertEquals("{\"a\":1,\"b\":[true,null,\"x\"],\"c\":{\"d\":2,\"e\":3}}", canon("{ \"c\": {\"e\":3, \"d\":2}, \"b\": [true, null, \"x\"],\n \"a\": 1 }"));
    }

    @Test
    void numbersUseTheirShortestDecimalFormSoTheTextOfTheScoreDoesNotMatter() {
        // the original defect: the same score written 81.30 by the signer and 81.3 by every ordinary JSON library
        assertEquals(canon("{\"s\":81.30}"), canon("{\"s\":81.3}"));
        assertEquals("{\"s\":81.3}", canon("{\"s\":81.30}"));
        assertEquals("[100,0,-4.5,0.002,2]", canon("[100.0, 0.00, -4.50, 2e-3, 2.0]"));
        assertEquals("{\"n\":100}", canon("{\"n\":1E+2}"));
    }

    @Test
    void numbersOutsideTheProfileAreRefusedNotGuessed() {
        assertThrows(IllegalArgumentException.class, () -> canon("[1e30]"));
        assertThrows(IllegalArgumentException.class, () -> canon("[0.0000000001]"));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.number(new BigDecimal("0.1234567890123456")));
    }

    @Test
    void stringsAreEscapedAsLittleAsPossible() {
        // RFC 8785 section 3.2.2.2: only the quote, the backslash and control characters are escaped; everything else is written as it is
        assertEquals("[\"€$<>=&'/é\"]", canon("[\"\\u20ac$<>=&'/\\u00e9\"]"));
        assertEquals("[\"a\\\"b\\\\c\\nd\\te\\u000f\\u001f\"]", canon("[\"a\\\"b\\\\c\\nd\\te\\u000f\\u001f\"]"));
    }

    @Test
    void membersAreOrderedByUtf16CodeUnitsAsInTheRfcExample() {
        // RFC 8785 section 3.2.3: carriage return, "1", U+0080, o-diaeresis, euro, emoji (a surrogate pair, D83D), Hebrew dalet with dagesh (FB33)
        String json = "{\"\\u20ac\":\"Euro\",\"\\r\":\"CR\",\"\\ufb33\":\"Dalet\",\"1\":\"One\",\"\\ud83d\\ude00\":\"Emoji\",\"\\u0080\":\"Ctl\",\"\\u00f6\":\"o\"}";
        String out = canon(json);
        int[] positions = {out.indexOf("\"\\r\""), out.indexOf("\"1\""), out.indexOf("\"\u0080\""), out.indexOf("\"\u00f6\""), out.indexOf("\"\u20ac\""),
                out.indexOf("\"\ud83d\ude00\""), out.indexOf("\"\ufb33\"")};
        for (int i = 1; i < positions.length; i++) assertTrue(positions[i - 1] >= 0 && positions[i - 1] < positions[i], "member " + i + " out of order in " + out);
    }

    @Test
    void canonicalisingTwiceChangesNothing() {
        String once = canon("{\"z\":[3,2,1],\"a\":{\"y\":1.50,\"x\":\"é\"}}");
        assertEquals(once, canon(once));
    }
}

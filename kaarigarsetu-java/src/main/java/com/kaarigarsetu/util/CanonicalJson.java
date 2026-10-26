package com.kaarigarsetu.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.util.Map;
import java.util.TreeMap;

/**
 * Canonical JSON for signing, following RFC 8785 (JSON Canonicalization Scheme): no insignificant whitespace, object members sorted by the UTF-16 code
 * units of their names, strings escaped as little as possible, numbers in their shortest decimal form.
 *
 * <p>Why it exists: a signature covers bytes, and the same data can be written as bytes in many ways ({@code 81.30} versus {@code 81.3}, key order,
 * escapes). Signing a canonical form means a verifier in ANY language can parse the document, canonicalise it again and get the identical bytes.
 * Before this, a credential could only be checked by software that kept the exact original text; an ordinary JSON library silently changed
 * {@code 81.30} to {@code 81.3} and the signature then failed.
 *
 * <p>Profile: numbers must have at most 15 significant digits and lie in [1e-6, 1e21) (where the RFC's number format is a plain decimal), which holds for
 * scores, counts and ratings; anything else is refused rather than guessed. scripts/verify_credential.py implements the same rules independently.
 */
public final class CanonicalJson {
    private static final BigDecimal UPPER = new BigDecimal("1e21");
    private static final BigDecimal LOWER = new BigDecimal("1e-6");

    private CanonicalJson() {
    }

    public static String canonicalize(JsonElement element) {
        StringBuilder out = new StringBuilder();
        write(element, out);
        return out.toString();
    }

    private static void write(JsonElement element, StringBuilder out) {
        if (element == null || element.isJsonNull()) {
            out.append("null");
        } else if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isBoolean()) out.append(primitive.getAsBoolean());
            else if (primitive.isNumber()) out.append(number(primitive.getAsBigDecimal()));
            else string(primitive.getAsString(), out);
        } else if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            out.append('[');
            for (int i = 0; i < array.size(); i++) {
                if (i > 0) out.append(',');
                write(array.get(i), out);
            }
            out.append(']');
        } else {
            JsonObject object = element.getAsJsonObject();
            Map<String, JsonElement> sorted = new TreeMap<>();              // String order is UTF-16 code unit order, as the RFC requires
            object.entrySet().forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonElement> entry : sorted.entrySet()) {
                if (!first) out.append(',');
                first = false;
                string(entry.getKey(), out);
                out.append(':');
                write(entry.getValue(), out);
            }
            out.append('}');
        }
    }

    static String number(BigDecimal value) {
        if (value.signum() == 0) return "0";
        BigDecimal plain = value.stripTrailingZeros();
        BigDecimal magnitude = plain.abs();
        if (plain.precision() > 15 || magnitude.compareTo(UPPER) >= 0 || magnitude.compareTo(LOWER) < 0) {
            throw new IllegalArgumentException("number outside the canonical profile: " + value);
        }
        return plain.toPlainString();
    }

    private static void string(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\f' -> out.append("\\f");
                case '\r' -> out.append("\\r");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }
}

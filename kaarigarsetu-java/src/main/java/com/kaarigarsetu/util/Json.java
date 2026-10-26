package com.kaarigarsetu.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One shared Gson, so serialisation rules are uniform (and no JSON is ever assembled by string concatenation).
 * Gson cannot reflect into java.time on modern JDKs, so those types are written as ISO-8601 strings.
 */
public final class Json {
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .registerTypeAdapter(LocalDateTime.class, (JsonSerializer<LocalDateTime>) (value, type, context) -> new JsonPrimitive(value.toString()))
            .registerTypeAdapter(LocalDate.class, (JsonSerializer<LocalDate>) (value, type, context) -> new JsonPrimitive(value.toString()))
            .registerTypeAdapter(Instant.class, (JsonSerializer<Instant>) (value, type, context) -> new JsonPrimitive(value.toString()))
            .create();

    private Json() {}

    public static Gson gson() {
        return GSON;
    }

    public static String toJson(Object value) {
        return GSON.toJson(value);
    }
}

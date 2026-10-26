package com.kaarigarsetu.assessment;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The trades a worker can register for (resource {@code trades.json}). A trade is added by adding one line here, one rubric file in
 * the ML service ({@code app/models/rubrics/<key>.json}) and one {@link AssessmentService} subclass; startup and tests fail if
 * any of the three is missing, so a trade can never be offered that cannot be assessed.
 */
public final class TradeCatalog {
    private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9-]{0,40}$");

    public record Trade(String key, String name, String nameHi) {}

    private final Map<String, Trade> byKey = new LinkedHashMap<>();

    public TradeCatalog(List<Trade> trades) {
        if (trades == null || trades.isEmpty()) throw new IllegalArgumentException("at least one trade is required");
        for (Trade trade : trades) {
            if (trade.key() == null || !KEY.matcher(trade.key()).matches()) throw new IllegalArgumentException("bad trade key: " + trade.key());
            if (isBlank(trade.name()) || isBlank(trade.nameHi())) throw new IllegalArgumentException("trade " + trade.key() + " needs an English and a Hindi name");
            if (byKey.put(trade.key(), trade) != null) throw new IllegalArgumentException("duplicate trade: " + trade.key());
        }
    }

    /** Loads {@code /trades.json} from the classpath. */
    public static TradeCatalog load() {
        try (InputStream in = TradeCatalog.class.getResourceAsStream("/trades.json")) {
            if (in == null) throw new IllegalStateException("trades.json is missing");
            JsonArray array = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
            java.util.ArrayList<Trade> trades = new java.util.ArrayList<>();
            for (JsonElement element : array) {
                JsonObject o = element.getAsJsonObject();
                trades.add(new Trade(required(o, "key"), required(o, "name"), required(o, "nameHi")));
            }
            return new TradeCatalog(trades);
        } catch (IOException | JsonParseException | IllegalStateException | ClassCastException failure) {
            throw new IllegalStateException("trades.json is invalid: " + failure.getMessage(), failure);
        }
    }

    private static String required(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()) throw new IllegalStateException("entry is missing '" + field + "'");
        return value.getAsString();
    }

    public List<Trade> all() {
        return List.copyOf(byKey.values());
    }

    /** Case-insensitive, whitespace-tolerant lookup; empty for anything not in the catalog. */
    public Optional<Trade> find(String key) {
        return key == null ? Optional.empty() : Optional.ofNullable(byKey.get(key.trim().toLowerCase(Locale.ROOT)));
    }

    /** Fails fast when a trade is offered to workers but no scorer class exists for it. */
    public void requireScorersFor(Collection<AssessmentService> services) {
        Set<String> scored = new HashSet<>();
        for (AssessmentService service : services) scored.add(service.supportedTrade().trim().toLowerCase(Locale.ROOT));
        for (Trade trade : byKey.values()) {
            if (!scored.contains(trade.name().toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException("Trade '" + trade.key() + "' is in trades.json but has no AssessmentService");
            }
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

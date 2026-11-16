package com.nostrcraft.nostr;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** Builder for the standard NIP-01 REQ filter fields. */
public final class NostrFilter {
    private final JsonObject json = new JsonObject();

    public NostrFilter ids(String... values) { return strings("ids", values, true); }
    public NostrFilter authors(String... values) { return strings("authors", values, true); }

    private NostrFilter strings(String key, String[] values, boolean hex) {
        if (values == null || values.length == 0) throw new IllegalArgumentException(key + " filter must not be empty");
        JsonArray array = new JsonArray();
        for (String value : values) {
            if (value == null || (hex && !Hex.isHex(value, 64)))
                throw new IllegalArgumentException(key + " values must be 64-character hexadecimal strings");
            String normalized = hex ? value.toLowerCase(java.util.Locale.ROOT) : value;
            array.add(new JsonPrimitive(normalized));
        }
        json.add(key, array);
        return this;
    }

    public NostrFilter kinds(int... values) {
        if (values == null || values.length == 0) throw new IllegalArgumentException("kinds filter must not be empty");
        JsonArray array = new JsonArray();
        for (int value : values) {
            if (value < 0 || value > 65535) throw new IllegalArgumentException("kind must be between 0 and 65535");
            array.add(new JsonPrimitive(value));
        }
        json.add("kinds", array);
        return this;
    }

    /** Adds an indexed single-letter tag filter such as e, p, a, or t. */
    public NostrFilter tag(char name, String... values) {
        if (!((name >= 'a' && name <= 'z') || (name >= 'A' && name <= 'Z')))
            throw new IllegalArgumentException("NIP-01 indexed tag names must be one ASCII letter");
        if (values == null || values.length == 0) throw new IllegalArgumentException("tag filter must not be empty");
        JsonArray array = new JsonArray();
        for (String value : values) {
            if (value == null) throw new IllegalArgumentException("tag filter values cannot be null");
            array.add(new JsonPrimitive(value));
        }
        json.add("#" + name, array);
        return this;
    }

    public NostrFilter since(long value) { if (value < 0) throw new IllegalArgumentException("since must be non-negative"); json.addProperty("since", value); return this; }
    public NostrFilter until(long value) { if (value < 0) throw new IllegalArgumentException("until must be non-negative"); json.addProperty("until", value); return this; }
    public NostrFilter limit(int value) { if (value < 0) throw new IllegalArgumentException("limit must be non-negative"); json.addProperty("limit", value); return this; }

    JsonObject toJson() { return json; }
}

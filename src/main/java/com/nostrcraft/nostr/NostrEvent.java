package com.nostrcraft.nostr;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** NIP-01 event. */
public class NostrEvent {
    public static final int KIND_METADATA = 0;
    public static final int KIND_TEXT_NOTE = 1;
    public static final int KIND_CONTACT_LIST = 3;
    public static final int KIND_APP_RECOMMENDATION = 31989;
    public static final int KIND_APP_HANDLER = 31990;

    public String id = "";
    public String pubkey = "";
    public long createdAt;
    public int kind;
    public List<List<String>> tags = new ArrayList<List<String>>();
    public String content = "";
    public String sig = "";

    /** The canonical serialization whose SHA-256 is the event id (NIP-01). */
    public String serialize() {
        StringBuilder sb = new StringBuilder("[0,");
        escape(sb, pubkey);
        sb.append(',').append(createdAt).append(',').append(kind).append(",[");
        for (int i = 0; i < tags.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('[');
            List<String> t = tags.get(i);
            for (int j = 0; j < t.size(); j++) {
                if (j > 0) sb.append(',');
                escape(sb, t.get(j));
            }
            sb.append(']');
        }
        sb.append("],");
        escape(sb, content);
        return sb.append(']').toString();
    }

    // NIP-01's listed escapes plus JSON-required escaping for remaining control bytes.
    private static void escape(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append("\\u00");
                        String hex = Integer.toHexString(c);
                        if (hex.length() < 2) sb.append('0');
                        sb.append(hex);
                    } else sb.append(c);
            }
        }
        sb.append('"');
    }

    public byte[] computeIdBytes() {
        return Schnorr.sha256(serialize().getBytes(StandardCharsets.UTF_8));
    }

    /** Build, hash and sign a new event. */
    public static NostrEvent create(NostrKeys keys, int kind, List<List<String>> tags, String content) {
        if (keys == null) throw new IllegalArgumentException("keys cannot be null");
        NostrEvent e = unsigned(keys.getPubHex(), kind, tags, content);
        byte[] idBytes = e.computeIdBytes();
        e.id = Hex.encode(idBytes);
        e.sig = Hex.encode(keys.sign(idBytes));
        return e;
    }

    /** Creates the canonical unsigned event to be signed locally or by a remote signer. */
    public static NostrEvent unsigned(String pubkey, int kind, List<List<String>> tags, String content) {
        if (kind < 0 || kind > 65535) throw new IllegalArgumentException("kind must be between 0 and 65535");
        if (!Hex.isHex(pubkey, 64)) throw new IllegalArgumentException("pubkey must be 64-character hexadecimal");
        if (content == null) throw new IllegalArgumentException("content cannot be null");
        NostrEvent e = new NostrEvent();
        e.pubkey = pubkey.toLowerCase(java.util.Locale.ROOT);
        e.createdAt = System.currentTimeMillis() / 1000L;
        e.kind = kind;
        if (tags != null) {
            for (List<String> tag : tags) {
                if (tag == null || tag.isEmpty()) throw new IllegalArgumentException("Tags must contain at least one string");
                for (String value : tag) if (value == null) throw new IllegalArgumentException("Tag values cannot be null");
            }
            e.tags = tags;
        }
        e.content = content;
        return e;
    }

    /** Fields requested by NIP-46 sign_event, excluding id and signature. */
    public JsonObject toUnsignedJson() {
        JsonObject o = new JsonObject();
        o.addProperty("kind", kind);
        o.addProperty("content", content);
        JsonArray ts = new JsonArray();
        for (List<String> tag : tags) {
            JsonArray values = new JsonArray();
            for (String value : tag) values.add(new JsonPrimitive(value));
            ts.add(values);
        }
        o.add("tags", ts);
        o.addProperty("created_at", createdAt);
        return o;
    }

    /** Checks the id hash and (optionally) the Schnorr signature. */
    public boolean verify(boolean checkSignature) {
        try {
            if (!Hex.isHex(id, 64) || !Hex.isHex(pubkey, 64) || !Hex.isHex(sig, 128)
                    || !id.equals(id.toLowerCase(java.util.Locale.ROOT))
                    || !pubkey.equals(pubkey.toLowerCase(java.util.Locale.ROOT))
                    || !sig.equals(sig.toLowerCase(java.util.Locale.ROOT))
                    || kind < 0 || kind > 65535 || createdAt < 0 || content == null || tags == null) return false;
            for (List<String> tag : tags) {
                if (tag == null || tag.isEmpty()) return false;
                for (String value : tag) if (value == null) return false;
            }
            byte[] idBytes = computeIdBytes();
            if (!Hex.encode(idBytes).equals(id.toLowerCase(java.util.Locale.ROOT))) return false;
            return !checkSignature || Schnorr.verify(idBytes, Hex.decode(pubkey), Hex.decode(sig));
        } catch (Exception e) {
            return false;
        }
    }

    public boolean hasTag(String name) {
        for (List<String> t : tags) if (!t.isEmpty() && name.equals(t.get(0))) return true;
        return false;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("pubkey", pubkey);
        o.addProperty("created_at", createdAt);
        o.addProperty("kind", kind);
        JsonArray ts = new JsonArray();
        for (List<String> t : tags) {
            JsonArray ta = new JsonArray();
            for (String s : t) ta.add(new JsonPrimitive(s));
            ts.add(ta);
        }
        o.add("tags", ts);
        o.addProperty("content", content);
        o.addProperty("sig", sig);
        return o;
    }

    /** Returns null if the JSON is not a well-formed event. */
    public static NostrEvent fromJson(JsonElement el) {
        try {
            JsonObject o = el.getAsJsonObject();
            NostrEvent e = new NostrEvent();
            if (!string(o, "id") || !string(o, "pubkey") || !integer(o, "created_at")
                    || !integer(o, "kind") || !string(o, "content") || !string(o, "sig")
                    || !o.has("tags") || !o.get("tags").isJsonArray()) return null;
            e.id = o.get("id").getAsString();
            e.pubkey = o.get("pubkey").getAsString();
            e.createdAt = o.get("created_at").getAsLong();
            long parsedKind = o.get("kind").getAsLong();
            if (parsedKind < 0 || parsedKind > 65535) return null;
            e.kind = (int) parsedKind;
            e.content = o.get("content").getAsString();
            e.sig = o.get("sig").getAsString();
            for (JsonElement te : o.getAsJsonArray("tags")) {
                List<String> tag = new ArrayList<String>();
                if (!te.isJsonArray()) return null;
                for (JsonElement s : te.getAsJsonArray()) {
                    if (!s.isJsonPrimitive() || !s.getAsJsonPrimitive().isString()) return null;
                    tag.add(s.getAsString());
                }
                if (tag.isEmpty()) return null;
                e.tags.add(tag);
            }
            if (e.kind < 0 || e.kind > 65535 || e.createdAt < 0) return null;
            return e;
        } catch (Exception ex) {
            return null;
        }
    }

    private static boolean string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString();
    }

    private static boolean integer(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return false;
        try { e.getAsBigDecimal().longValueExact(); return true; }
        catch (Exception ignored) { return false; }
    }
}

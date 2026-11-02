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

    // NIP-01 escaping: only \n \" \\ \r \t \b \f are escaped; everything else is raw UTF-8.
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
                default: sb.append(c);
            }
        }
        sb.append('"');
    }

    public byte[] computeIdBytes() {
        return Schnorr.sha256(serialize().getBytes(StandardCharsets.UTF_8));
    }

    /** Build, hash and sign a new event. */
    public static NostrEvent create(NostrKeys keys, int kind, List<List<String>> tags, String content) {
        NostrEvent e = new NostrEvent();
        e.pubkey = keys.getPubHex();
        e.createdAt = System.currentTimeMillis() / 1000L;
        e.kind = kind;
        if (tags != null) e.tags = tags;
        e.content = content;
        byte[] idBytes = e.computeIdBytes();
        e.id = Hex.encode(idBytes);
        e.sig = Hex.encode(keys.sign(idBytes));
        return e;
    }

    /** Checks the id hash and (optionally) the Schnorr signature. */
    public boolean verify(boolean checkSignature) {
        try {
            if (!Hex.isHex(id, 64) || !Hex.isHex(pubkey, 64) || !Hex.isHex(sig, 128)) return false;
            byte[] idBytes = computeIdBytes();
            if (!Hex.encode(idBytes).equals(id.toLowerCase())) return false;
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
            e.id = o.get("id").getAsString();
            e.pubkey = o.get("pubkey").getAsString();
            e.createdAt = o.get("created_at").getAsLong();
            e.kind = o.get("kind").getAsInt();
            e.content = o.get("content").getAsString();
            e.sig = o.get("sig").getAsString();
            for (JsonElement te : o.getAsJsonArray("tags")) {
                List<String> tag = new ArrayList<String>();
                for (JsonElement s : te.getAsJsonArray()) tag.add(s.isJsonPrimitive() ? s.getAsString() : "");
                e.tags.add(tag);
            }
            return e;
        } catch (Exception ex) {
            return null;
        }
    }
}

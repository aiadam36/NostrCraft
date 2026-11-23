package com.nostrcraft.nostr;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** Small, tolerant helpers for NIP-89 addressable app descriptors and recommendations. */
public final class Nip89 {
    private Nip89() {}

    public static final class Address {
        public final int kind;
        public final String pubkey;
        public final String identifier;
        public final String relay;
        Address(int kind, String pubkey, String identifier, String relay) {
            this.kind = kind; this.pubkey = pubkey; this.identifier = identifier; this.relay = relay;
        }
        public String key() { return kind + ":" + pubkey + ":" + identifier; }
    }

    public static final class Handler {
        public final NostrEvent event;
        public final String name;
        public final List<String> urls = new ArrayList<String>();
        Handler(NostrEvent event) { this.event = event; this.name = metadataName(event.content, event.pubkey); }
    }

    public static String tagValue(NostrEvent event, String name) {
        if (event == null) return null;
        for (List<String> tag : event.tags)
            if (tag.size() > 1 && name.equals(tag.get(0))) return tag.get(1);
        return null;
    }

    public static Address parseAddress(String value) {
        if (value == null) return null;
        String[] p = value.split(":", 4);
        try {
            if (p.length < 3 || Integer.parseInt(p[0]) != NostrEvent.KIND_APP_HANDLER
                    || !Hex.isHex(p[1], 64) || p[2].isEmpty()) return null;
            return new Address(Integer.parseInt(p[0]), p[1].toLowerCase(java.util.Locale.ROOT), p[2], p.length > 3 ? p[3] : null);
        } catch (Exception e) { return null; }
    }

    public static List<Address> recommendationAddresses(NostrEvent event, int kind) {
        List<Address> result = new ArrayList<Address>();
        if (event == null || event.kind != NostrEvent.KIND_APP_RECOMMENDATION) return result;
        String d = tagValue(event, "d");
        if (!Integer.toString(kind).equals(d)) return result;
        for (List<String> tag : event.tags) {
            if (tag.size() < 2 || !"a".equals(tag.get(0))) continue;
            Address address = parseAddress(tag.get(1));
            if (address != null) result.add(address);
        }
        return result;
    }

    public static Handler parseHandler(NostrEvent event) {
        if (event == null || event.kind != NostrEvent.KIND_APP_HANDLER) return null;
        boolean hasKind = false;
        for (List<String> tag : event.tags) if (tag.size() > 1 && "k".equals(tag.get(0))) {
            try { Integer.parseInt(tag.get(1)); hasKind = true; } catch (NumberFormatException ignored) { }
        }
        if (!hasKind) return null;
        Handler handler = new Handler(event);
        for (List<String> tag : event.tags) {
            if (tag.size() < 2 || !("web".equals(tag.get(0)) || "desktop".equals(tag.get(0)))) continue;
            String url = tag.get(1).replace("bech32", "");
            if (safeHttpUrl(url)) handler.urls.add(url);
        }
        return handler;
    }

    public static boolean safeHttpUrl(String value) {
        try {
            URI uri = new URI(value);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (Exception e) { return false; }
    }

    private static String metadataName(String json, String fallback) {
        try {
            com.google.gson.JsonObject o = new com.google.gson.JsonParser().parse(json).getAsJsonObject();
            for (String k : new String[] {"display_name", "name"})
                if (o.has(k) && o.get(k).isJsonPrimitive()) {
                    String s = o.get(k).getAsString().replaceAll("[\\p{Cntrl}\\u00a7]", "").trim();
                    if (!s.isEmpty()) return s.substring(0, Math.min(48, s.length()));
                }
        } catch (Exception ignored) { }
        return fallback.substring(0, Math.min(12, fallback.length()));
    }
}

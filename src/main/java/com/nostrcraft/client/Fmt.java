package com.nostrcraft.client;

/** Small text helpers for rendering untrusted Nostr content safely in Minecraft's font. */
public final class Fmt {
    private Fmt() {}

    /** Strips formatting-code injection (the section sign) and control characters; caps length. */
    public static String clean(String s, int maxLen) {
        if (s == null) return "";
        s = s.replace('\u00a7', '?').replace("\r", "");
        s = s.replaceAll("[\\p{Cntrl}&&[^\n]]", "");
        s = s.replaceAll("\n{3,}", "\n\n");
        s = s.trim();
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    public static String ago(long unixSeconds) {
        long d = System.currentTimeMillis() / 1000L - unixSeconds;
        if (d < 60) return "now";
        if (d < 3600) return (d / 60) + "m";
        if (d < 86400) return (d / 3600) + "h";
        return (d / 86400) + "d";
    }
}

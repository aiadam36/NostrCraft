package com.nostrcraft.nostr;

public final class Hex {
    private static final char[] D = "0123456789abcdef".toCharArray();

    private Hex() {}

    public static String encode(byte[] b) {
        char[] c = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            c[2 * i] = D[(b[i] >> 4) & 15];
            c[2 * i + 1] = D[b[i] & 15];
        }
        return new String(c);
    }

    public static byte[] decode(String s) {
        if (s.length() % 2 != 0) throw new IllegalArgumentException("Odd hex length");
        byte[] o = new byte[s.length() / 2];
        for (int i = 0; i < o.length; i++) {
            int hi = Character.digit(s.charAt(2 * i), 16);
            int lo = Character.digit(s.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("Invalid hex");
            o[i] = (byte) (hi << 4 | lo);
        }
        return o;
    }

    public static boolean isHex(String s, int length) {
        if (s == null || s.length() != length) return false;
        for (int i = 0; i < s.length(); i++) if (Character.digit(s.charAt(i), 16) < 0) return false;
        return true;
    }
}

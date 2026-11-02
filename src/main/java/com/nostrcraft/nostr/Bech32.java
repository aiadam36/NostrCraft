package com.nostrcraft.nostr;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Locale;

/** Bech32 (BIP-173) as used by NIP-19 (npub / nsec / note ...). */
public final class Bech32 {
    private static final String CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
    private static final int[] GEN = {0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3};

    public static final class Decoded {
        public final String hrp;
        public final byte[] data;

        Decoded(String hrp, byte[] data) {
            this.hrp = hrp;
            this.data = data;
        }
    }

    private Bech32() {}

    private static int polymod(byte[] values) {
        int chk = 1;
        for (byte v : values) {
            int b = chk >>> 25;
            chk = ((chk & 0x1ffffff) << 5) ^ (v & 0xff);
            for (int i = 0; i < 5; i++) if (((b >>> i) & 1) != 0) chk ^= GEN[i];
        }
        return chk;
    }

    private static byte[] hrpExpand(String hrp) {
        int n = hrp.length();
        byte[] out = new byte[2 * n + 1];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) (hrp.charAt(i) >> 5);
            out[i + n + 1] = (byte) (hrp.charAt(i) & 31);
        }
        return out;
    }

    private static byte[] convertBits(byte[] in, int from, int to, boolean pad) {
        int acc = 0, bits = 0, maxv = (1 << to) - 1, maxAcc = (1 << (from + to - 1)) - 1;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte b : in) {
            int v = b & 0xff;
            if ((v >> from) != 0) throw new IllegalArgumentException("Invalid data range");
            acc = ((acc << from) | v) & maxAcc;
            bits += from;
            while (bits >= to) {
                bits -= to;
                out.write((acc >> bits) & maxv);
            }
        }
        if (pad) {
            if (bits > 0) out.write((acc << (to - bits)) & maxv);
        } else if (bits >= from || ((acc << (to - bits)) & maxv) != 0) {
            throw new IllegalArgumentException("Invalid padding");
        }
        return out.toByteArray();
    }

    public static String encode(String hrp, byte[] data8) {
        byte[] d5 = convertBits(data8, 8, 5, true);
        byte[] exp = hrpExpand(hrp);
        byte[] all = new byte[exp.length + d5.length + 6];
        System.arraycopy(exp, 0, all, 0, exp.length);
        System.arraycopy(d5, 0, all, exp.length, d5.length);
        int pm = polymod(all) ^ 1;
        StringBuilder sb = new StringBuilder(hrp).append('1');
        for (byte b : d5) sb.append(CHARSET.charAt(b));
        for (int i = 0; i < 6; i++) sb.append(CHARSET.charAt((pm >>> (5 * (5 - i))) & 31));
        return sb.toString();
    }

    public static Decoded decode(String s) {
        if (!s.equals(s.toLowerCase(Locale.ROOT)) && !s.equals(s.toUpperCase(Locale.ROOT)))
            throw new IllegalArgumentException("Mixed case");
        s = s.toLowerCase(Locale.ROOT);
        int pos = s.lastIndexOf('1');
        if (pos < 1 || pos + 7 > s.length()) throw new IllegalArgumentException("Bad bech32 format");
        String hrp = s.substring(0, pos);
        byte[] vals = new byte[s.length() - pos - 1];
        for (int i = 0; i < vals.length; i++) {
            int idx = CHARSET.indexOf(s.charAt(pos + 1 + i));
            if (idx < 0) throw new IllegalArgumentException("Bad bech32 character");
            vals[i] = (byte) idx;
        }
        byte[] exp = hrpExpand(hrp);
        byte[] all = new byte[exp.length + vals.length];
        System.arraycopy(exp, 0, all, 0, exp.length);
        System.arraycopy(vals, 0, all, exp.length, vals.length);
        if (polymod(all) != 1) throw new IllegalArgumentException("Bad checksum");
        byte[] d5 = Arrays.copyOfRange(vals, 0, vals.length - 6);
        return new Decoded(hrp, convertBits(d5, 5, 8, false));
    }
}

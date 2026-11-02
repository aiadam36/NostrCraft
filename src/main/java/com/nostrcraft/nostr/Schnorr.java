package com.nostrcraft.nostr;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * BIP-340 Schnorr signatures over secp256k1 (what Nostr uses), implemented with
 * plain BigInteger so the mod needs no extra libraries.
 *
 * NOTE: not constant-time. Fine for a game client; don't reuse for anything exotic.
 */
public final class Schnorr {
    private static final BigInteger P = new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16);
    private static final BigInteger N = new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);
    private static final BigInteger[] G = {
            new BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16),
            new BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16)
    };
    private static final BigInteger THREE = BigInteger.valueOf(3);
    private static final BigInteger SEVEN = BigInteger.valueOf(7);
    private static final SecureRandom RNG = new SecureRandom();

    private Schnorr() {}

    // ---- curve arithmetic (affine; null = point at infinity) ----

    private static BigInteger[] add(BigInteger[] a, BigInteger[] b) {
        if (a == null) return b;
        if (b == null) return a;
        BigInteger lam;
        if (a[0].equals(b[0])) {
            if (!a[1].equals(b[1]) || a[1].signum() == 0) return null;
            lam = a[0].multiply(a[0]).multiply(THREE).multiply(a[1].shiftLeft(1).modInverse(P)).mod(P);
        } else {
            lam = b[1].subtract(a[1]).multiply(b[0].subtract(a[0]).modInverse(P)).mod(P);
        }
        BigInteger x = lam.multiply(lam).subtract(a[0]).subtract(b[0]).mod(P);
        BigInteger y = lam.multiply(a[0].subtract(x)).subtract(a[1]).mod(P);
        return new BigInteger[]{x, y};
    }

    private static BigInteger[] mul(BigInteger[] pt, BigInteger k) {
        BigInteger[] r = null;
        BigInteger[] addend = pt;
        for (int i = 0; i < 256; i++) {
            if (k.testBit(i)) r = add(r, addend);
            addend = add(addend, addend);
        }
        return r;
    }

    private static BigInteger[] liftX(BigInteger x) {
        if (x.signum() < 0 || x.compareTo(P) >= 0) return null;
        BigInteger c = x.pow(3).add(SEVEN).mod(P);
        BigInteger y = c.modPow(P.add(BigInteger.ONE).shiftRight(2), P);
        if (!y.multiply(y).mod(P).equals(c)) return null;
        return new BigInteger[]{x, y.testBit(0) ? P.subtract(y) : y};
    }

    // ---- helpers ----

    private static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) md.update(p);
            return md.digest();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] taggedHash(String tag, byte[]... parts) {
        byte[] t = sha256(tag.getBytes(StandardCharsets.UTF_8));
        byte[][] all = new byte[parts.length + 2][];
        all[0] = t;
        all[1] = t;
        System.arraycopy(parts, 0, all, 2, parts.length);
        return sha256(all);
    }

    private static byte[] bytes32(BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[32];
        if (raw.length > 32) System.arraycopy(raw, raw.length - 32, out, 0, 32);
        else System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }

    public static byte[] sha256(byte[] data) {
        return sha256(new byte[][]{data});
    }

    // ---- public API ----

    public static boolean isValidSecret(byte[] sk) {
        if (sk == null || sk.length != 32) return false;
        BigInteger d = new BigInteger(1, sk);
        return d.signum() > 0 && d.compareTo(N) < 0;
    }

    /** 32-byte x-only public key. */
    public static byte[] publicKey(byte[] sk) {
        if (!isValidSecret(sk)) throw new IllegalArgumentException("Invalid secret key");
        return bytes32(mul(G, new BigInteger(1, sk))[0]);
    }

    public static byte[] sign(byte[] msg32, byte[] sk) {
        byte[] aux = new byte[32];
        RNG.nextBytes(aux);
        return sign(msg32, sk, aux);
    }

    public static byte[] sign(byte[] msg, byte[] sk, byte[] aux) {
        if (!isValidSecret(sk)) throw new IllegalArgumentException("Invalid secret key");
        BigInteger d0 = new BigInteger(1, sk);
        BigInteger[] pub = mul(G, d0);
        BigInteger d = pub[1].testBit(0) ? N.subtract(d0) : d0;
        byte[] px = bytes32(pub[0]);
        byte[] t = bytes32(d);
        byte[] h = taggedHash("BIP0340/aux", aux);
        for (int i = 0; i < 32; i++) t[i] ^= h[i];
        BigInteger k0 = new BigInteger(1, taggedHash("BIP0340/nonce", t, px, msg)).mod(N);
        if (k0.signum() == 0) throw new IllegalStateException("Bad nonce");
        BigInteger[] r = mul(G, k0);
        BigInteger k = r[1].testBit(0) ? N.subtract(k0) : k0;
        byte[] rx = bytes32(r[0]);
        BigInteger e = new BigInteger(1, taggedHash("BIP0340/challenge", rx, px, msg)).mod(N);
        byte[] s = bytes32(k.add(e.multiply(d)).mod(N));
        return concat(rx, s);
    }

    public static boolean verify(byte[] msg, byte[] pubX, byte[] sig) {
        try {
            if (pubX.length != 32 || sig.length != 64) return false;
            BigInteger[] pub = liftX(new BigInteger(1, pubX));
            if (pub == null) return false;
            BigInteger r = new BigInteger(1, Arrays.copyOfRange(sig, 0, 32));
            BigInteger s = new BigInteger(1, Arrays.copyOfRange(sig, 32, 64));
            if (r.compareTo(P) >= 0 || s.compareTo(N) >= 0) return false;
            BigInteger e = new BigInteger(1, taggedHash("BIP0340/challenge", bytes32(r), pubX, msg)).mod(N);
            BigInteger[] R = add(mul(G, s), mul(pub, N.subtract(e)));
            return R != null && !R[1].testBit(0) && R[0].equals(r);
        } catch (Exception ex) {
            return false;
        }
    }
}

package com.nostrcraft.nostr;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/** NIP-44 version 2 conversation encryption (NIP-44, using secp256k1 ECDH). */
public final class Nip44 {
    private static final byte VERSION = 2;
    private static final int MAX_PLAINTEXT_BYTES = 1 << 20;
    private static final byte[] HKDF_SALT = "nip44-v2".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RNG = new SecureRandom();
    private Nip44() { }

    /** Encrypts UTF-8 text for a recipient x-only secp256k1 public key. */
    public static String encrypt(byte[] senderSecret, byte[] recipientPubX, String plaintext) {
        if (plaintext == null) throw new IllegalArgumentException("Plaintext is required");
        byte[] message = plaintext.getBytes(StandardCharsets.UTF_8);
        if (message.length < 1 || message.length > MAX_PLAINTEXT_BYTES) throw new IllegalArgumentException("NIP-44 plaintext must be 1 byte to 1 MiB");
        byte[] nonce = new byte[32];
        RNG.nextBytes(nonce);
        byte[] conversationKey = hkdfExtract(HKDF_SALT, Schnorr.sharedSecret(senderSecret, recipientPubX));
        byte[] keys = hkdfExpand(conversationKey, nonce, 76);
        byte[] padded = pad(message);
        byte[] ciphertext = chacha20(Arrays.copyOfRange(keys, 0, 32), Arrays.copyOfRange(keys, 32, 44), padded);
        byte[] mac = hmacSha256(Arrays.copyOfRange(keys, 44, 76), concat(nonce, ciphertext));
        return Base64.getEncoder().encodeToString(concat(new byte[]{VERSION}, nonce, ciphertext, mac));
    }

    /** Decrypts NIP-44 v2 content from a sender x-only secp256k1 public key. */
    public static String decrypt(byte[] recipientSecret, byte[] senderPubX, String encoded) {
        if (encoded == null) throw new IllegalArgumentException("Ciphertext is required");
        final byte[] payload;
        if (encoded.startsWith("#")) throw new IllegalArgumentException("Unsupported NIP-44 encryption version");
        if (encoded.length() < 132 || encoded.length() > 2 * (MAX_PLAINTEXT_BYTES + 128)) throw new IllegalArgumentException("Invalid NIP-44 payload size");
        try { payload = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid NIP-44 base64", e); }
        if (payload.length < 99 || payload[0] != VERSION) throw new IllegalArgumentException("Unsupported or malformed NIP-44 payload");
        byte[] nonce = Arrays.copyOfRange(payload, 1, 33);
        byte[] ciphertext = Arrays.copyOfRange(payload, 33, payload.length - 32);
        byte[] mac = Arrays.copyOfRange(payload, payload.length - 32, payload.length);
        byte[] conversationKey = hkdfExtract(HKDF_SALT, Schnorr.sharedSecret(recipientSecret, senderPubX));
        byte[] keys = hkdfExpand(conversationKey, nonce, 76);
        byte[] expected = hmacSha256(Arrays.copyOfRange(keys, 44, 76), concat(nonce, ciphertext));
        if (!MessageDigest.isEqual(mac, expected)) throw new IllegalArgumentException("NIP-44 authentication failed");
        byte[] padded = chacha20(Arrays.copyOfRange(keys, 0, 32), Arrays.copyOfRange(keys, 32, 44), ciphertext);
        byte[] message = unpad(padded);
        return new String(message, StandardCharsets.UTF_8);
    }

    private static byte[] pad(byte[] message) {
        int length = message.length;
        int paddedLength;
        if (length <= 32) paddedLength = 32;
        else {
            int nextPower = 1;
            while (nextPower < length) nextPower <<= 1;
            int chunk = Math.max(32, nextPower / 8);
            paddedLength = chunk * ((length + chunk - 1) / chunk);
        }
        int prefixLength = length >= 65536 ? 6 : 2;
        byte[] out = new byte[paddedLength + prefixLength];
        if (prefixLength == 2) {
            out[0] = (byte) (length >>> 8);
            out[1] = (byte) length;
        } else {
            out[2] = (byte) (length >>> 24);
            out[3] = (byte) (length >>> 16);
            out[4] = (byte) (length >>> 8);
            out[5] = (byte) length;
        }
        System.arraycopy(message, 0, out, prefixLength, length);
        return out;
    }

    private static byte[] unpad(byte[] padded) {
        if (padded.length < 34) throw new IllegalArgumentException("Malformed NIP-44 plaintext");
        int shortLength = ((padded[0] & 255) << 8) | (padded[1] & 255);
        int prefixLength = shortLength == 0 ? 6 : 2;
        long lengthLong = shortLength == 0
                ? (((long) (padded[2] & 255) << 24) | ((long) (padded[3] & 255) << 16) | ((long) (padded[4] & 255) << 8) | (long) (padded[5] & 255))
                : shortLength;
        if (lengthLong < 1 || lengthLong > MAX_PLAINTEXT_BYTES || (shortLength == 0 && lengthLong < 65536))
            throw new IllegalArgumentException("Malformed NIP-44 padding");
        int length = (int) lengthLong;
        if (padded.length != paddedLength(length) + prefixLength) throw new IllegalArgumentException("Malformed NIP-44 padding");
        for (int i = length + prefixLength; i < padded.length; i++) if (padded[i] != 0) throw new IllegalArgumentException("Malformed NIP-44 padding");
        return Arrays.copyOfRange(padded, prefixLength, length + prefixLength);
    }

    private static int paddedLength(int length) {
        if (length <= 32) return 32;
        int nextPower = 1;
        while (nextPower < length) nextPower <<= 1;
        int chunk = Math.max(32, nextPower / 8);
        return chunk * ((length + chunk - 1) / chunk);
    }

    private static byte[] chacha20(byte[] key, byte[] nonce, byte[] input) {
        if (input.length > (long) (0xffffffffL - 1) * 64) throw new IllegalArgumentException("NIP-44 message too long");
        byte[] out = new byte[input.length];
        int counter = 0;
        for (int offset = 0; offset < input.length; offset += 64) {
            byte[] block = chachaBlock(key, counter++, nonce);
            int count = Math.min(64, input.length - offset);
            for (int i = 0; i < count; i++) out[offset + i] = (byte) (input[offset + i] ^ block[i]);
        }
        return out;
    }

    private static byte[] chachaBlock(byte[] key, int counter, byte[] nonce) {
        int[] state = new int[16];
        byte[] sigma = "expand 32-byte k".getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < 4; i++) state[i] = littleInt(sigma, i * 4);
        for (int i = 0; i < 8; i++) state[4 + i] = littleInt(key, i * 4);
        state[12] = counter;
        for (int i = 0; i < 3; i++) state[13 + i] = littleInt(nonce, i * 4);
        int[] x = state.clone();
        for (int i = 0; i < 10; i++) {
            quarter(x, 0, 4, 8, 12); quarter(x, 1, 5, 9, 13); quarter(x, 2, 6, 10, 14); quarter(x, 3, 7, 11, 15);
            quarter(x, 0, 5, 10, 15); quarter(x, 1, 6, 11, 12); quarter(x, 2, 7, 8, 13); quarter(x, 3, 4, 9, 14);
        }
        byte[] out = new byte[64];
        for (int i = 0; i < 16; i++) putLittleInt(out, i * 4, x[i] + state[i]);
        return out;
    }

    private static void quarter(int[] x, int a, int b, int c, int d) {
        x[a] += x[b]; x[d] ^= x[a]; x[d] = Integer.rotateLeft(x[d], 16);
        x[c] += x[d]; x[b] ^= x[c]; x[b] = Integer.rotateLeft(x[b], 12);
        x[a] += x[b]; x[d] ^= x[a]; x[d] = Integer.rotateLeft(x[d], 8);
        x[c] += x[d]; x[b] ^= x[c]; x[b] = Integer.rotateLeft(x[b], 7);
    }

    private static int littleInt(byte[] b, int offset) { return (b[offset] & 255) | ((b[offset + 1] & 255) << 8) | ((b[offset + 2] & 255) << 16) | ((b[offset + 3] & 255) << 24); }
    private static void putLittleInt(byte[] b, int offset, int value) { for (int i = 0; i < 4; i++) b[offset + i] = (byte) (value >>> (8 * i)); }

    private static byte[] hkdfExtract(byte[] salt, byte[] input) { return hmacSha256(salt, input); }
    private static byte[] hkdfExpand(byte[] prk, byte[] info, int length) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] previous = new byte[0];
        for (int i = 1; out.size() < length; i++) {
            previous = hmacSha256(prk, concat(previous, info, new byte[]{(byte) i}));
            out.write(previous, 0, previous.length);
        }
        return Arrays.copyOf(out.toByteArray(), length);
    }
    private static byte[] hmacSha256(byte[] key, byte[] data) {
        try { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256")); return mac.doFinal(data); }
        catch (Exception e) { throw new IllegalStateException("HMAC-SHA256 unavailable", e); }
    }
    private static byte[] concat(byte[]... arrays) {
        int length = 0;
        for (byte[] a : arrays) length += a.length;
        byte[] out = new byte[length]; int offset = 0;
        for (byte[] a : arrays) { System.arraycopy(a, 0, out, offset, a.length); offset += a.length; }
        return out;
    }
}

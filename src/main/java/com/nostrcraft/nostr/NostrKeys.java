package com.nostrcraft.nostr;

import java.security.SecureRandom;

/** A Nostr identity: secp256k1 secret key + x-only public key. */
public final class NostrKeys {
    private final byte[] secret;
    private final byte[] pub;

    private NostrKeys(byte[] secret) {
        this.secret = secret;
        this.pub = Schnorr.publicKey(secret);
    }

    public static NostrKeys generate() {
        SecureRandom rng = new SecureRandom();
        byte[] b = new byte[32];
        do {
            rng.nextBytes(b);
        } while (!Schnorr.isValidSecret(b));
        return new NostrKeys(b.clone());
    }

    public static NostrKeys fromSecret(byte[] sk) {
        if (!Schnorr.isValidSecret(sk)) throw new IllegalArgumentException("Invalid secret key");
        return new NostrKeys(sk.clone());
    }

    /** Accepts an nsec1... string or 64 hex characters. */
    public static NostrKeys parse(String input) {
        String s = input == null ? "" : input.trim();
        if (s.startsWith("nsec1")) {
            Bech32.Decoded d = Bech32.decode(s);
            if (!"nsec".equals(d.hrp) || d.data.length != 32) throw new IllegalArgumentException("Not a valid nsec");
            return fromSecret(d.data);
        }
        if (Hex.isHex(s, 64)) return fromSecret(Hex.decode(s));
        throw new IllegalArgumentException("Expected an nsec1... key or 64 hex characters");
    }

    public String getPubHex() { return Hex.encode(pub); }
    public String getSecretHex() { return Hex.encode(secret); }
    public String getNpub() { return Bech32.encode("npub", pub); }
    public String getNsec() { return Bech32.encode("nsec", secret); }

    public byte[] sign(byte[] msg32) { return Schnorr.sign(msg32, secret); }
}

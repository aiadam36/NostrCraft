package com.nostrcraft.nostr;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Parser for public NIP-19 entities and their NIP-21 {@code nostr:} URI form. */
public final class Nip19 {
    public static final int MAX_ENTITY_LENGTH = 5000;

    public enum Type { NPUB, NOTE, NPROFILE, NEVENT, NADDR }

    /** Parsed entity. Fields not used by a type are null or empty. */
    public static final class Entity {
        public final Type type;
        /** Public key for npub/nprofile, or author for nevent/naddr, as lowercase hex. */
        public final String pubkey;
        /** Note/event id for note/nevent, as lowercase hex. */
        public final String eventId;
        /** Identifier (the {@code d} tag) for naddr. */
        public final String identifier;
        /** Kind for naddr and optionally nevent, represented unsigned. */
        public final Long kind;
        /** Optional relay hints, in encoded order. */
        public final List<String> relays;

        private Entity(Type type, String pubkey, String eventId, String identifier, Long kind, List<String> relays) {
            this.type = type;
            this.pubkey = pubkey;
            this.eventId = eventId;
            this.identifier = identifier;
            this.kind = kind;
            this.relays = Collections.unmodifiableList(new ArrayList<String>(relays));
        }
    }

    private Nip19() {}

    /**
     * Parses a bare NIP-19 value or a NIP-21 {@code nostr:} URI. Returns null for
     * unsupported or malformed input; secret-key values ({@code nsec}) are never accepted.
     */
    public static Entity parse(String input) {
        if (input == null) return null;
        String value = input.trim();
        if (value.regionMatches(true, 0, "nostr:", 0, 6)) value = value.substring(6);
        if (value.isEmpty() || value.length() > MAX_ENTITY_LENGTH) return null;
        try {
            Bech32.Decoded decoded = Bech32.decode(value);
            if ("npub".equals(decoded.hrp)) {
                requireLength(decoded.data, 32);
                return new Entity(Type.NPUB, Hex.encode(decoded.data), null, null, null, Collections.<String>emptyList());
            }
            if ("note".equals(decoded.hrp)) {
                requireLength(decoded.data, 32);
                return new Entity(Type.NOTE, null, Hex.encode(decoded.data), null, null, Collections.<String>emptyList());
            }
            if (!"nprofile".equals(decoded.hrp) && !"nevent".equals(decoded.hrp) && !"naddr".equals(decoded.hrp)) return null;
            return parseTlvs(decoded.hrp, decoded.data);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Entity parseTlvs(String hrp, byte[] data) throws CharacterCodingException {
        byte[] special = null;
        byte[] author = null;
        Long kind = null;
        List<String> relays = new ArrayList<String>();
        int offset = 0;
        while (offset < data.length) {
            if (data.length - offset < 2) throw new IllegalArgumentException("Truncated TLV header");
            int type = data[offset++] & 0xff;
            int length = data[offset++] & 0xff;
            if (length > data.length - offset) throw new IllegalArgumentException("Truncated TLV value");
            byte[] value = new byte[length];
            System.arraycopy(data, offset, value, 0, length);
            offset += length;
            switch (type) {
                case 0:
                    if (special != null) throw new IllegalArgumentException("Duplicate special field");
                    special = value;
                    break;
                case 1:
                    relays.add(decodeRelay(value));
                    break;
                case 2:
                    if ("nprofile".equals(hrp)) break;
                    if (author != null) throw new IllegalArgumentException("Duplicate author field");
                    author = value;
                    break;
                case 3:
                    if ("nprofile".equals(hrp)) break;
                    if (kind != null || value.length != 4) throw new IllegalArgumentException("Invalid kind field");
                    kind = ((long) (value[0] & 0xff) << 24)
                            | ((long) (value[1] & 0xff) << 16)
                            | ((long) (value[2] & 0xff) << 8)
                            | (long) (value[3] & 0xff);
                    break;
                default:
                    // NIP-19 requires clients to ignore TLV fields they do not recognize.
                    break;
            }
        }

        if ("nprofile".equals(hrp)) {
            requireLength(special, 32);
            return new Entity(Type.NPROFILE, Hex.encode(special), null, null, null, relays);
        }
        if ("nevent".equals(hrp)) {
            requireLength(special, 32);
            if (author != null) requireLength(author, 32);
            return new Entity(Type.NEVENT, author == null ? null : Hex.encode(author), Hex.encode(special), null, kind, relays);
        }
        if ("naddr".equals(hrp)) {
            if (special == null || author == null || kind == null) throw new IllegalArgumentException("Missing naddr field");
            requireLength(author, 32);
            return new Entity(Type.NADDR, Hex.encode(author), null, decodeUtf8(special), kind, relays);
        }
        throw new IllegalArgumentException("Unsupported entity");
    }

    private static String decodeRelay(byte[] value) throws CharacterCodingException {
        for (byte b : value) if ((b & 0xff) > 0x7f) throw new IllegalArgumentException("Relay hint must be ASCII");
        return new String(value, StandardCharsets.US_ASCII);
    }

    private static String decodeUtf8(byte[] value) throws CharacterCodingException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(value)).toString();
    }

    private static void requireLength(byte[] value, int length) {
        if (value == null || value.length != length) throw new IllegalArgumentException("Invalid field length");
    }
}

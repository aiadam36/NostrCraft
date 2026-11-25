package com.nostrcraft.nostr;

/** Account identity and event-signing abstraction for local and remote Nostr keys. */
public interface NostrSigner {
    interface Callback {
        void onSigned(NostrEvent event);
        void onError(String message);
    }

    String getPublicKey();
    void signEvent(NostrEvent unsignedEvent, Callback callback);
}

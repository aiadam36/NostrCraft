package com.nostrcraft.nostr;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Client-initiated NIP-46 (nostrconnect://) handshake and RPC transport. */
public final class Nip46Connection implements RelayConnection.Listener {
    public interface Listener {
        /** Called as soon as the URI is ready, before waiting for a signer. */
        void onUri(String uri);
        /** Called after the signer is authenticated and the account pubkey is fetched. */
        void onConnected(String userPubkey, String remoteSignerPubkey);
        void onError(String message);
        /** Called when the remote signer changes the relays used by this session. */
        default void onRelaysChanged(List<String> relays) { }
    }

    public interface RpcListener {
        void onResult(String result);
        void onError(String message);
    }

    private static final int KIND_NIP46 = 24133;
    private static final long CONNECT_TIMEOUT_SECONDS = 180;
    private static final long REQUEST_TIMEOUT_SECONDS = 45;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final JsonParser JSON = new JsonParser();

    private static final class Pending {
        final String method;
        final ScheduledFuture<?> timeout;
        final RpcListener callback;
        Pending(String method, ScheduledFuture<?> timeout, RpcListener callback) {
            this.method = method; this.timeout = timeout; this.callback = callback;
        }
    }

    private final Listener listener;
    private final NostrKeys clientKeys = NostrKeys.generate();
    private final String connectSecret = randomHex(16);
    private final String subscriptionId = "nc46-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private final Map<String, RelayConnection> relays = new ConcurrentHashMap<String, RelayConnection>();
    private final Map<String, Pending> pending = new ConcurrentHashMap<String, Pending>();
    private final AtomicBoolean uriNotified = new AtomicBoolean();
    private volatile List<String> relayUrls = Collections.emptyList();
    private volatile String remoteSignerPubkey;
    private volatile String userPubkey;
    private volatile boolean connected;
    private volatile boolean closed;
    private volatile ScheduledFuture<?> connectTimeout;
    private volatile String connectionUri;

    public Nip46Connection(Listener listener) {
        if (listener == null) throw new IllegalArgumentException("Listener is required");
        this.listener = listener;
    }

    /** Starts listening and returns the URI; present it to the user after onUri confirms a relay is listening. */
    public synchronized String start(List<String> relayUrls, String requestedPermissions) {
        if (closed || !relays.isEmpty()) throw new IllegalStateException("NIP-46 connection has already been started");
        this.relayUrls = validateRelays(relayUrls);
        if (requestedPermissions == null || requestedPermissions.trim().isEmpty())
            throw new IllegalArgumentException("Requested permissions are required");
        StringBuilder uri = new StringBuilder("nostrconnect://").append(clientKeys.getPubHex()).append('?');
        boolean first = true;
        for (String relay : this.relayUrls) {
            if (!first) uri.append('&');
            first = false;
            uri.append("relay=").append(encode(relay));
        }
        uri.append("&secret=").append(encode(connectSecret));
        uri.append("&perms=").append(encode(requestedPermissions.trim()));
        uri.append("&name=").append(encode("NostrCraft"));
        connectionUri = uri.toString();
        connectTimeout = RelayConnection.GROUP.schedule(new Runnable() {
            @Override public void run() { fail("Timed out waiting for a remote signer"); }
        }, CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        for (String relayUrl : this.relayUrls) openRelay(relayUrl);
        return connectionUri;
    }

    public boolean isConnected() { return connected; }
    public String getUserPubkey() { return userPubkey; }
    public String getRemoteSignerPubkey() { return remoteSignerPubkey; }
    public List<String> getRelayUrls() { return relayUrls; }

    @Override public void onOpen(RelayConnection relay) {
        if (closed) { relay.close(); return; }
        JsonObject filter = new JsonObject();
        JsonArray kinds = new JsonArray(); kinds.add(new JsonPrimitive(KIND_NIP46)); filter.add("kinds", kinds);
        JsonArray recipients = new JsonArray(); recipients.add(new JsonPrimitive(clientKeys.getPubHex())); filter.add("#p", recipients);
        filter.addProperty("since", System.currentTimeMillis() / 1000L - 60);
        JsonArray req = new JsonArray(); req.add(new JsonPrimitive("REQ")); req.add(new JsonPrimitive(subscriptionId)); req.add(filter);
        relay.send(req.toString());
        if (uriNotified.compareAndSet(false, true)) listener.onUri(connectionUri);
        // The signer relays the initial connect response; this client does not send a connect RPC.
        if (remoteSignerPubkey != null) sendRequest("switch_relays", Collections.<String>emptyList());
    }

    @Override public void onMessage(RelayConnection relay, String text) {
        try {
            JsonArray frame = JSON.parse(text).getAsJsonArray();
            if (frame.size() < 3 || !"EVENT".equals(frame.get(0).getAsString())
                    || !subscriptionId.equals(frame.get(1).getAsString())) return;
            NostrEvent event = NostrEvent.fromJson(frame.get(2));
            if (event == null || event.kind != KIND_NIP46 || !event.verify(true)
                    || !hasRecipient(event, clientKeys.getPubHex())) return;
            handleResponse(event);
        } catch (Exception ignored) { /* Ignore malformed or unrelated relay messages. */ }
    }

    @Override public void onClose(RelayConnection relay) {
        // RelayConnection owns reconnects; keep the subscription state and pending RPCs alive.
    }

    private void handleResponse(NostrEvent event) {
        final JsonObject response;
        try {
            String clear = Nip44.decrypt(clientKeys.secretBytes(), Hex.decode(event.pubkey), event.content);
            response = JSON.parse(clear).getAsJsonObject();
        } catch (Exception ignored) { return; }
        if (!response.has("id") || !response.get("id").isJsonPrimitive()
                || !response.has("result") && !response.has("error")) return;
        String id = response.get("id").getAsString();

        // nostrconnect is signer-initiated: accept only the response carrying our one-time secret.
        if (remoteSignerPubkey == null) {
            if (response.has("error") || !response.has("result")
                    || !connectSecret.equals(response.get("result").getAsString())) return;
            remoteSignerPubkey = event.pubkey;
            if (connectTimeout != null) connectTimeout.cancel(false);
            sendRequest("switch_relays", Collections.<String>emptyList());
            sendRequest("get_public_key", Collections.<String>emptyList());
            return;
        }
        if (!remoteSignerPubkey.equals(event.pubkey)) return;
        Pending request = pending.remove(id);
        if (request == null) return;
        request.timeout.cancel(false);
        if (response.has("error")) {
            if (request.callback != null) request.callback.onError(response.get("error").getAsString());
            if ("get_public_key".equals(request.method)) fail("Remote signer rejected get_public_key: " + response.get("error").getAsString());
            return;
        }
        JsonElement result = response.get("result");
        if ("get_public_key".equals(request.method)) {
            if (result == null || !result.isJsonPrimitive() || !result.getAsJsonPrimitive().isString()
                    || !Hex.isHex(result.getAsString(), 64)
                    || !Schnorr.isValidPublicKey(Hex.decode(result.getAsString()))) {
                fail("Remote signer returned an invalid user public key"); return;
            }
            userPubkey = result.getAsString().toLowerCase(java.util.Locale.ROOT);
            connected = true;
            listener.onConnected(userPubkey, remoteSignerPubkey);
            if (request.callback != null) request.callback.onResult(userPubkey);
        } else if ("switch_relays".equals(request.method)) {
            JsonElement relayResult = result;
            if (relayResult != null && relayResult.isJsonPrimitive() && relayResult.getAsJsonPrimitive().isString()) {
                String jsonResult = relayResult.getAsString();
                if ("null".equals(jsonResult)) relayResult = null;
                else {
                    try { relayResult = JSON.parse(jsonResult); }
                    catch (Exception ignored) { return; }
                }
            }
            if (relayResult == null || relayResult.isJsonNull() || !relayResult.isJsonArray()) return;
            List<String> next = new ArrayList<String>();
            for (JsonElement value : relayResult.getAsJsonArray()) {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return;
                next.add(value.getAsString());
            }
            if (!next.isEmpty()) switchRelays(next);
        }
        if (request.callback != null && !"get_public_key".equals(request.method)
                && !"switch_relays".equals(request.method)) {
            if (result == null || !result.isJsonPrimitive() || !result.getAsJsonPrimitive().isString()) {
                request.callback.onError("Remote signer returned a malformed result");
            } else {
                try { request.callback.onResult(result.getAsString()); }
                catch (RuntimeException e) { request.callback.onError("Could not process remote signer result"); }
            }
        }
    }

    private String sendRequest(final String method, List<String> params) {
        return sendRequest(method, params, null);
    }

    private String sendRequest(final String method, List<String> params, final RpcListener callback) {
        final String signer = remoteSignerPubkey;
        if (closed || signer == null) return null;
        final String id = UUID.randomUUID().toString();
        JsonObject rpc = new JsonObject(); rpc.addProperty("id", id); rpc.addProperty("method", method);
        JsonArray args = new JsonArray(); for (String param : params) args.add(new JsonPrimitive(param)); rpc.add("params", args);
        String encrypted = Nip44.encrypt(clientKeys.secretBytes(), Hex.decode(signer), rpc.toString());
        List<List<String>> tags = Collections.singletonList(Arrays.asList("p", signer));
        NostrEvent request = NostrEvent.create(clientKeys, KIND_NIP46, tags, encrypted);
        JsonArray frame = new JsonArray(); frame.add(new JsonPrimitive("EVENT")); frame.add(request.toJson());
        final ScheduledFuture<?> timeout = RelayConnection.GROUP.schedule(new Runnable() {
            @Override public void run() {
                Pending removed = pending.remove(id);
                if (removed != null) {
                    if (removed.callback != null) removed.callback.onError("Timed out waiting for the remote signer");
                    if ("get_public_key".equals(method)) fail("Timed out waiting for the remote signer");
                }
            }
        }, REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        pending.put(id, new Pending(method, timeout, callback));
        for (RelayConnection relay : relays.values()) if (relay.isOpen()) relay.send(frame.toString());
        return id;
    }

    /** Requests a NIP-46 sign_event operation for the supplied unsigned event. */
    public void signEvent(NostrEvent unsignedEvent, final RpcListener callback) {
        if (unsignedEvent == null || callback == null) throw new IllegalArgumentException("Event and callback are required");
        if (!connected || remoteSignerPubkey == null) { callback.onError("Remote signer is not connected"); return; }
        JsonObject fields = unsignedEvent.toUnsignedJson();
        sendRequest("sign_event", Collections.singletonList(fields.toString()), new RpcListener() {
            @Override public void onResult(String result) { callback.onResult(result); }
            @Override public void onError(String message) { callback.onError(message); }
        });
    }

    /** Sends the courtesy logout RPC, then destroys the temporary client keypair. */
    public void logoutAndClose() {
        if (!connected || closed) { close(); return; }
        final ScheduledFuture<?> fallback = RelayConnection.GROUP.schedule(new Runnable() {
            @Override public void run() { close(); }
        }, 2, TimeUnit.SECONDS);
        sendRequest("logout", Collections.<String>emptyList(), new RpcListener() {
            private void finish() {
                fallback.cancel(false);
                close();
            }
            @Override public void onResult(String result) { finish(); }
            @Override public void onError(String message) { finish(); }
        });
    }

    private synchronized void switchRelays(List<String> requested) {
        if (closed) return;
        final List<String> validated;
        try { validated = validateRelays(requested); }
        catch (IllegalArgumentException e) { return; }
        if (validated.equals(relayUrls)) return;
        List<RelayConnection> old = new ArrayList<RelayConnection>(relays.values());
        relays.clear(); relayUrls = validated;
        for (RelayConnection relay : old) relay.close();
        for (String relayUrl : validated) openRelay(relayUrl);
        listener.onRelaysChanged(relayUrls);
    }

    private void openRelay(String url) {
        try {
            RelayConnection connection = new RelayConnection(url, this);
            relays.put(url, connection);
            connection.connect();
        } catch (Exception e) { fail("Invalid NIP-46 relay URL"); }
    }

    private static List<String> validateRelays(List<String> urls) {
        if (urls == null || urls.isEmpty() || urls.size() > 5) throw new IllegalArgumentException("Provide 1 to 5 NIP-46 relays");
        List<String> safe = new ArrayList<String>();
        for (String value : urls) {
            if (value == null) throw new IllegalArgumentException("Invalid NIP-46 relay URL");
            String url = value.trim();
            java.net.URI parsed;
            try { parsed = java.net.URI.create(url); }
            catch (Exception e) { throw new IllegalArgumentException("Invalid NIP-46 relay URL"); }
            if (!"wss".equalsIgnoreCase(parsed.getScheme()) || parsed.getHost() == null || parsed.getUserInfo() != null
                    || parsed.getFragment() != null || parsed.getQuery() != null)
                throw new IllegalArgumentException("NIP-46 relays must be secure wss:// URLs");
            if (!safe.contains(url)) safe.add(url);
        }
        if (safe.isEmpty()) throw new IllegalArgumentException("Provide at least one NIP-46 relay");
        return Collections.unmodifiableList(safe);
    }

    private static boolean hasRecipient(NostrEvent event, String clientPubkey) {
        for (List<String> tag : event.tags)
            if (tag.size() >= 2 && "p".equals(tag.get(0)) && clientPubkey.equalsIgnoreCase(tag.get(1))) return true;
        return false;
    }

    private void fail(String message) {
        if (closed || connected) return;
        listener.onError(message);
        close();
    }

    /** Disconnects and best-effort erases the temporary client keypair. */
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (connectTimeout != null) connectTimeout.cancel(false);
        for (Pending request : pending.values()) request.timeout.cancel(false);
        pending.clear();
        for (RelayConnection relay : relays.values()) relay.close();
        relays.clear();
        clientKeys.destroy();
        remoteSignerPubkey = null; userPubkey = null; connected = false;
    }

    private static String randomHex(int bytes) { byte[] value = new byte[bytes]; RANDOM.nextBytes(value); return Hex.encode(value); }
    private static String encode(String value) {
        try { return URLEncoder.encode(value, "UTF-8"); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}

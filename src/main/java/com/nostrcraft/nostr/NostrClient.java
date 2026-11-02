package com.nostrcraft.nostr;

import com.google.gson.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Everything Nostr, with no Minecraft dependency: relays, subscriptions, the feed cache,
 * profile names, login state and publishing.
 *
 * Implements: NIP-01 (events, REQ/EVENT/CLOSE, OK/EOSE/NOTICE/CLOSED), NIP-19 (npub/nsec).
 * Runs entirely on the CLIENT: your secret key never touches the server.
 */
public class NostrClient implements RelayConnection.Listener {
    private static final NostrClient INSTANCE = new NostrClient();

    public static NostrClient get() { return INSTANCE; }

    public static final int MAX_FEED = 200;
    /** Hide notes that are replies (have an "e" tag) so the global feed reads better. */
    public static volatile boolean HIDE_REPLIES = true;
    /** Verify Schnorr signatures on incoming events (ids are always checked). */
    public static volatile boolean VERIFY_SIGNATURES = true;

    private static final String FEED_SUB = "nc-feed";
    private static final String[] DEFAULT_RELAYS = {
            "wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net"
    };

    private static final class Profile {
        final long ts;
        final String name;
        Profile(long ts, String name) { this.ts = ts; this.name = name; }
    }

    private final JsonParser parser = new JsonParser();
    private File configFile;
    private volatile NostrKeys keys;

    private final List<String> relayUrls = new CopyOnWriteArrayList<String>();
    private final Map<String, RelayConnection> relays = new ConcurrentHashMap<String, RelayConnection>();

    private final List<NostrEvent> feed = new ArrayList<NostrEvent>(); // guarded by 'feed'
    private final Set<String> seen = new HashSet<String>();            // guarded by 'feed'
    private final Map<String, Profile> profiles = new ConcurrentHashMap<String, Profile>();
    private final Map<String, String> shortNames = new ConcurrentHashMap<String, String>();
    private final Set<String> metaKnown = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final Queue<String> metaQueue = new ConcurrentLinkedQueue<String>();
    private final AtomicInteger metaCounter = new AtomicInteger();
    private final AtomicBoolean started = new AtomicBoolean();
    private ScheduledFuture<?> metaTask;

    private volatile int feedVersion;
    private volatile String status = "Idle";

    private NostrClient() {}

    // ------------------------------------------------------------ lifecycle

    public void init(File configDir) {
        configFile = new File(configDir, "nostrcraft.json");
        load();
    }

    public void ensureStarted() {
        if (!started.compareAndSet(false, true)) return;
        status = "Connecting...";
        for (String url : relayUrls) connectRelay(url);
        metaTask = RelayConnection.GROUP.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try { flushMeta(); } catch (Throwable ignored) { }
            }
        }, 2, 2, TimeUnit.SECONDS);
    }

    public void stop() {
        if (!started.compareAndSet(true, false)) return;
        if (metaTask != null) metaTask.cancel(false);
        for (RelayConnection rc : relays.values()) rc.close();
        relays.clear();
        status = "Disconnected";
    }

    private void connectRelay(String url) {
        try {
            RelayConnection rc = new RelayConnection(url, this);
            relays.put(url, rc);
            rc.connect();
        } catch (Exception e) {
            status = "Bad relay URL: " + url;
        }
    }

    // ------------------------------------------------------------ account (client-side only)

    public boolean isLoggedIn() { return keys != null; }
    public String getNpub() { return keys == null ? null : keys.getNpub(); }
    public String getNsec() { return keys == null ? null : keys.getNsec(); }

    public void login(String input) {
        NostrKeys k = NostrKeys.parse(input); // throws IllegalArgumentException on bad input
        keys = k;
        queueMeta(k.getPubHex());
        save();
        status = "Logged in as " + shortNpub(k.getPubHex());
    }

    public void generateKey() {
        keys = NostrKeys.generate();
        queueMeta(keys.getPubHex());
        save();
        status = "New key generated: " + shortNpub(keys.getPubHex());
    }

    public void logout() {
        keys = null;
        save();
        status = "Logged out";
    }

    // ------------------------------------------------------------ publishing

    /** Publishes a kind-1 text note to every open relay. */
    public void publish(String content) {
        NostrKeys k = keys;
        if (k == null) throw new IllegalStateException("Not logged in - open the login screen first");
        content = content == null ? "" : content.trim();
        if (content.isEmpty()) throw new IllegalStateException("Note is empty");
        List<RelayConnection> open = openRelays();
        if (open.isEmpty()) throw new IllegalStateException("No relay connected yet");

        NostrEvent ev = NostrEvent.create(k, NostrEvent.KIND_TEXT_NOTE, new ArrayList<List<String>>(), content);
        JsonArray msg = new JsonArray();
        msg.add(new JsonPrimitive("EVENT"));
        msg.add(ev.toJson());
        String text = msg.toString();
        for (RelayConnection rc : open) rc.send(text);
        addToFeed(ev); // show immediately; the relay echo is de-duplicated by id
        status = "Published to " + open.size() + " relay(s)";
    }

    // ------------------------------------------------------------ relays

    public List<String> getRelays() { return new ArrayList<String>(relayUrls); }

    public boolean isRelayOpen(String url) {
        RelayConnection rc = relays.get(url);
        return rc != null && rc.isOpen();
    }

    public void addRelay(String url) {
        url = url.trim();
        if (!url.matches("^wss?://[^\\s/]+(/\\S*)?$")) throw new IllegalArgumentException("Relay must look like wss://relay.example.com");
        if (relayUrls.contains(url)) return;
        relayUrls.add(url);
        save();
        if (started.get()) connectRelay(url);
    }

    public void removeRelay(String url) {
        relayUrls.remove(url.trim());
        RelayConnection rc = relays.remove(url.trim());
        if (rc != null) rc.close();
        save();
    }

    private List<RelayConnection> openRelays() {
        List<RelayConnection> out = new ArrayList<RelayConnection>();
        for (RelayConnection rc : relays.values()) if (rc.isOpen()) out.add(rc);
        return out;
    }

    // ------------------------------------------------------------ reading state

    public int getFeedVersion() { return feedVersion; }
    public String getStatus() { return status; }
    public void setStatus(String s) { status = s; }

    /** Newest first. Returns a snapshot. */
    public List<NostrEvent> getFeed() {
        synchronized (feed) { return new ArrayList<NostrEvent>(feed); }
    }

    public String displayName(String pubkey) {
        Profile p = profiles.get(pubkey);
        if (p != null) return p.name;
        queueMeta(pubkey);
        return shortNpub(pubkey);
    }

    public String shortNpub(String pubkey) {
        String s = shortNames.get(pubkey);
        if (s == null) {
            try {
                String n = Bech32.encode("npub", Hex.decode(pubkey));
                s = n.substring(0, 10) + "..." + n.substring(n.length() - 4);
            } catch (Exception e) {
                s = "unknown";
            }
            shortNames.put(pubkey, s);
        }
        return s;
    }

    // ------------------------------------------------------------ relay callbacks

    @Override
    public void onOpen(RelayConnection c) {
        status = "Connected: " + host(c.url);
        // NIP-01 REQ: latest 80 text notes from this relay
        c.send("[\"REQ\",\"" + FEED_SUB + "\",{\"kinds\":[1],\"limit\":80}]");
    }

    @Override
    public void onClose(RelayConnection c) {
        status = "Lost connection: " + host(c.url);
    }

    @Override
    public void onMessage(RelayConnection c, String text) {
        JsonArray a = parser.parse(text).getAsJsonArray();
        String type = a.get(0).getAsString();
        if ("EVENT".equals(type)) {
            if (a.size() >= 3) handleEvent(a.get(2));
        } else if ("OK".equals(type)) {
            boolean ok = a.get(2).getAsBoolean();
            String why = a.size() > 3 ? a.get(3).getAsString() : "";
            status = ok ? "\u00a7a" + host(c.url) + " accepted your note"
                        : "\u00a7c" + host(c.url) + " rejected: " + why;
        } else if ("EOSE".equals(type)) {
            String sub = a.get(1).getAsString();
            if (sub.startsWith("nc-meta")) c.send("[\"CLOSE\",\"" + sub + "\"]");
        } else if ("NOTICE".equals(type)) {
            status = "\u00a7e" + host(c.url) + ": " + a.get(1).getAsString();
        } else if ("CLOSED".equals(type)) {
            status = "\u00a7e" + host(c.url) + " closed sub: " + (a.size() > 2 ? a.get(2).getAsString() : "");
        }
    }

    private void handleEvent(JsonElement el) {
        NostrEvent e = NostrEvent.fromJson(el);
        if (e == null || (e.kind != NostrEvent.KIND_TEXT_NOTE && e.kind != NostrEvent.KIND_METADATA)) return;
        if (!e.verify(VERIFY_SIGNATURES)) return;

        if (e.kind == NostrEvent.KIND_METADATA) {
            handleProfile(e);
            return;
        }
        if (HIDE_REPLIES && e.hasTag("e")) return;
        if (e.createdAt > System.currentTimeMillis() / 1000L + 600) return; // ignore far-future spam
        if (e.content.trim().isEmpty()) return;
        addToFeed(e);
        queueMeta(e.pubkey);
    }

    private void handleProfile(NostrEvent e) {
        Profile old = profiles.get(e.pubkey);
        if (old != null && old.ts >= e.createdAt) return;
        try {
            JsonObject o = parser.parse(e.content).getAsJsonObject();
            String name = str(o, "display_name");
            if (name.isEmpty()) name = str(o, "name");
            name = name.replaceAll("[\\p{Cntrl}\u00a7]", "").trim();
            if (name.length() > 32) name = name.substring(0, 32);
            if (name.isEmpty()) return;
            profiles.put(e.pubkey, new Profile(e.createdAt, name));
            feedVersion++;
        } catch (Exception ignored) { }
    }

    private static String str(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsString() : "";
    }

    private void addToFeed(NostrEvent ev) {
        synchronized (feed) {
            if (!seen.add(ev.id)) return;
            int i = 0;
            while (i < feed.size() && feed.get(i).createdAt >= ev.createdAt) i++;
            feed.add(i, ev);
            while (feed.size() > MAX_FEED) seen.remove(feed.remove(feed.size() - 1).id);
        }
        feedVersion++;
    }

    // ------------------------------------------------------------ profile (kind 0) batching

    private void queueMeta(String pubkey) {
        if (metaKnown.add(pubkey)) metaQueue.add(pubkey);
    }

    private void flushMeta() {
        if (metaQueue.isEmpty()) return;
        List<RelayConnection> open = openRelays();
        if (open.isEmpty()) return;
        JsonArray authors = new JsonArray();
        String pk;
        while (authors.size() < 100 && (pk = metaQueue.poll()) != null) authors.add(new JsonPrimitive(pk));
        String sub = "nc-meta-" + metaCounter.incrementAndGet();
        String req = "[\"REQ\",\"" + sub + "\",{\"kinds\":[0],\"authors\":" + authors + "}]";
        for (RelayConnection rc : open) rc.send(req);
    }

    // ------------------------------------------------------------ config

    private static String host(String url) {
        try { return java.net.URI.create(url).getHost(); } catch (Exception e) { return url; }
    }

    private void load() {
        relayUrls.clear();
        try {
            if (configFile.exists()) {
                String json = new String(Files.readAllBytes(configFile.toPath()), StandardCharsets.UTF_8);
                JsonObject o = parser.parse(json).getAsJsonObject();
                if (o.has("secretKey") && !o.get("secretKey").isJsonNull())
                    keys = NostrKeys.fromSecret(Hex.decode(o.get("secretKey").getAsString()));
                if (o.has("relays"))
                    for (JsonElement e : o.getAsJsonArray("relays")) relayUrls.add(e.getAsString());
            }
        } catch (Exception e) {
            status = "Config error: " + e.getMessage();
        }
        if (keys != null) queueMeta(keys.getPubHex());
        if (relayUrls.isEmpty()) Collections.addAll(relayUrls, DEFAULT_RELAYS);
    }

    private synchronized void save() {
        if (configFile == null) return;
        try {
            JsonObject o = new JsonObject();
            o.add("secretKey", keys == null ? JsonNull.INSTANCE : new JsonPrimitive(keys.getSecretHex()));
            JsonArray rs = new JsonArray();
            for (String r : relayUrls) rs.add(new JsonPrimitive(r));
            o.add("relays", rs);
            configFile.getParentFile().mkdirs();
            Files.write(configFile.toPath(), new GsonBuilder().setPrettyPrinting().create().toJson(o).getBytes(StandardCharsets.UTF_8));
            // best effort: owner-only access (the file holds your secret key in plain text!)
            configFile.setReadable(false, false);
            configFile.setReadable(true, true);
            configFile.setWritable(false, false);
            configFile.setWritable(true, true);
        } catch (Exception e) {
            status = "Could not save config: " + e.getMessage();
        }
    }
}

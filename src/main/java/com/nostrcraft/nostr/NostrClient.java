package com.nostrcraft.nostr;

import com.google.gson.*;

import java.io.File;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
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
 * Implements: NIP-01 (events and relay messages), NIP-19 (npub/nsec), NIP-50 search with NIP-01 fallback.
 * Runs entirely on the CLIENT: your secret key never touches the server.
 */
public class NostrClient implements RelayConnection.Listener {
    public interface Nip05ResolverListener {
        void onResolved(String pubkey);
        void onError(String message);
    }

    public interface SubscriptionListener {
        void onEvent(NostrEvent event, String relayUrl);
        default void onEose(String relayUrl) { }
        default void onClosed(String relayUrl, String message) { }
    }
    private static final NostrClient INSTANCE = new NostrClient();
    private static final ExecutorService NIP11_EXECUTOR = Executors.newFixedThreadPool(2, new ThreadFactory() {
        private final AtomicInteger number = new AtomicInteger();
        @Override public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "NostrCraft-NIP11-" + number.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    });
    private static final ExecutorService NIP05_EXECUTOR = Executors.newFixedThreadPool(2, new ThreadFactory() {
        private final AtomicInteger number = new AtomicInteger();
        @Override public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "NostrCraft-NIP05-" + number.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    });

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
        final String nip05;
        final String website;
        final String banner;
        final Boolean bot;
        final Integer birthdayYear;
        final Integer birthdayMonth;
        final Integer birthdayDay;
        volatile boolean nip05Verified;
        Profile(long ts, String name, String nip05, String website, String banner, Boolean bot,
                Integer birthdayYear, Integer birthdayMonth, Integer birthdayDay) {
            this.ts = ts;
            this.name = name;
            this.nip05 = nip05;
            this.website = website;
            this.banner = banner;
            this.bot = bot;
            this.birthdayYear = birthdayYear;
            this.birthdayMonth = birthdayMonth;
            this.birthdayDay = birthdayDay;
        }
    }

    private static final class SearchSpec {
        final String text;
        final String author;
        final String nip05;
        final Integer kind;
        final String tag;
        final Long since;
        final Long until;
        SearchSpec(String text, String author, String nip05, Integer kind, String tag, Long since, Long until) {
            this.text = text; this.author = author; this.nip05 = nip05;
            this.kind = kind; this.tag = tag; this.since = since; this.until = until;
        }
        SearchSpec withAuthor(String pubkey) { return new SearchSpec(text, pubkey, null, kind, tag, since, until); }
    }

    private final JsonParser parser = new JsonParser();
    private File configFile;
    private volatile NostrKeys keys;
    private volatile long followListCreatedAt;

    private final List<String> relayUrls = new CopyOnWriteArrayList<String>();
    private final Map<String, RelayConnection> relays = new ConcurrentHashMap<String, RelayConnection>();
    private final Map<String, Boolean> searchSupport = new ConcurrentHashMap<String, Boolean>();
    private final Map<String, String> customSubscriptions = new ConcurrentHashMap<String, String>();
    private final Map<String, SubscriptionListener> subscriptionListeners = new ConcurrentHashMap<String, SubscriptionListener>();

    private final List<NostrEvent> feed = new ArrayList<NostrEvent>(); // guarded by 'feed'
    private final Set<String> seen = new HashSet<String>();            // guarded by 'feed'
    private final List<NostrEvent> searchResults = new ArrayList<NostrEvent>(); // guarded by 'searchResults'
    private final Set<String> searchSeen = new HashSet<String>();
    private volatile String searchQuery = "";
    private volatile SearchSpec searchSpec = new SearchSpec("", null, null, null, null, null, null);
    private volatile boolean searchFollowingOnly;
    private volatile String searchSubscription = "";
    private final Map<String, Profile> profiles = new ConcurrentHashMap<String, Profile>();
    private final Map<String, String> shortNames = new ConcurrentHashMap<String, String>();
    private final Set<String> following = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final Set<String> metaKnown = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final Queue<String> metaQueue = new ConcurrentLinkedQueue<String>();
    private final AtomicInteger metaCounter = new AtomicInteger();
    private final AtomicInteger searchCounter = new AtomicInteger();
    private final AtomicInteger entityRequestCounter = new AtomicInteger();
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
        following.clear();
        followListCreatedAt = 0;
        queueMeta(k.getPubHex());
        save();
        requestFollowList();
        status = "Logged in as " + shortNpub(k.getPubHex());
    }

    public void generateKey() {
        keys = NostrKeys.generate();
        following.clear();
        followListCreatedAt = 0;
        queueMeta(keys.getPubHex());
        save();
        requestFollowList();
        status = "New key generated: " + shortNpub(keys.getPubHex());
    }

    public void logout() {
        keys = null;
        following.clear();
        followListCreatedAt = 0;
        feedVersion++;
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
        publishEvent(ev);
    }

    /** Publishes a previously signed, valid event to every open relay. */
    public void publishEvent(NostrEvent ev) {
        if (ev == null || !ev.verify(true)) throw new IllegalArgumentException("Event id or signature is invalid");
        List<RelayConnection> open = openRelays();
        if (open.isEmpty()) throw new IllegalStateException("No relay connected yet");
        JsonArray msg = new JsonArray();
        msg.add(new JsonPrimitive("EVENT"));
        msg.add(ev.toJson());
        String text = msg.toString();
        for (RelayConnection rc : open) rc.send(text);
        if (ev.kind == NostrEvent.KIND_TEXT_NOTE) addToFeed(ev); // relay echoes are de-duplicated by id
        status = "Published to " + open.size() + " relay(s)";
    }

    /** Opens or replaces a NIP-01 subscription on every currently connected relay. */
    public int subscribe(String subscriptionId, NostrFilter... filters) {
        return subscribe(subscriptionId, null, filters);
    }

    public int subscribe(String subscriptionId, SubscriptionListener listener, NostrFilter... filters) {
        if (subscriptionId == null || subscriptionId.isEmpty() || subscriptionId.length() > 64)
            throw new IllegalArgumentException("Subscription id must contain 1 to 64 characters");
        if (filters == null || filters.length == 0) throw new IllegalArgumentException("At least one filter is required");
        JsonArray req = new JsonArray();
        req.add(new JsonPrimitive("REQ"));
        req.add(new JsonPrimitive(subscriptionId));
        for (NostrFilter filter : filters) {
            if (filter == null) throw new IllegalArgumentException("Filter cannot be null");
            req.add(filter.toJson());
        }
        customSubscriptions.put(subscriptionId, req.toString());
        if (listener == null) subscriptionListeners.remove(subscriptionId);
        else subscriptionListeners.put(subscriptionId, listener);
        String message = req.toString();
        List<RelayConnection> open = openRelays();
        for (RelayConnection relay : open) relay.send(message);
        return open.size();
    }

    /** Requests the entity represented by a parsed NIP-19 reference. Relay hints are preferred when connected. */
    public String requestEntity(Nip19.Entity entity, SubscriptionListener listener) {
        if (entity == null || listener == null) throw new IllegalArgumentException("Entity and listener are required");
        ensureStarted();
        String subscription = "nc-entity-" + entityRequestCounter.incrementAndGet();
        NostrFilter[] filters;
        switch (entity.type) {
            case NPUB:
            case NPROFILE:
                filters = new NostrFilter[] {
                        new NostrFilter().kinds(NostrEvent.KIND_METADATA).authors(entity.pubkey).limit(1),
                        new NostrFilter().kinds(NostrEvent.KIND_TEXT_NOTE).authors(entity.pubkey).limit(20)
                };
                break;
            case NOTE:
            case NEVENT:
                filters = new NostrFilter[] { new NostrFilter().ids(entity.eventId).limit(1) };
                break;
            case NADDR:
                if (entity.kind == null || entity.kind.longValue() > 65535L)
                    throw new IllegalArgumentException("This address uses an unsupported event kind");
                filters = new NostrFilter[] { new NostrFilter().kinds(entity.kind.intValue())
                        .authors(entity.pubkey).tag('d', entity.identifier).limit(1) };
                break;
            default:
                throw new IllegalArgumentException("Unsupported Nostr reference");
        }

        JsonArray req = new JsonArray();
        req.add(new JsonPrimitive("REQ"));
        req.add(new JsonPrimitive(subscription));
        for (NostrFilter filter : filters) req.add(filter.toJson());
        String message = req.toString();
        customSubscriptions.put(subscription, message);
        subscriptionListeners.put(subscription, listener);

        List<RelayConnection> open = openRelays();
        List<RelayConnection> hinted = new ArrayList<RelayConnection>();
        for (RelayConnection relay : open) if (entity.relays.contains(relay.url)) hinted.add(relay);
        List<RelayConnection> targets = hinted.isEmpty() ? open : hinted;
        for (RelayConnection relay : targets) relay.send(message);
        return subscription;
    }

    /** Closes a NIP-01 subscription on every currently connected relay. */
    public void closeSubscription(String subscriptionId) {
        if (subscriptionId == null || subscriptionId.isEmpty() || subscriptionId.length() > 64)
            throw new IllegalArgumentException("Subscription id must contain 1 to 64 characters");
        JsonArray close = new JsonArray();
        close.add(new JsonPrimitive("CLOSE"));
        close.add(new JsonPrimitive(subscriptionId));
        String message = close.toString();
        for (RelayConnection relay : openRelays()) relay.send(message);
        customSubscriptions.remove(subscriptionId);
        subscriptionListeners.remove(subscriptionId);
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
    public boolean follows(String pubkey) { return following.contains(pubkey); }
    public int getFollowingCount() { return following.size(); }

    /** Newest first. Returns a snapshot. */
    public List<NostrEvent> getFeed() {
        synchronized (feed) { return new ArrayList<NostrEvent>(feed); }
    }

    public boolean isSearching() { return !searchQuery.isEmpty(); }
    public String getSearchQuery() { return searchQuery; }
    public List<NostrEvent> getSearchResults() {
        synchronized (searchResults) { return new ArrayList<NostrEvent>(searchResults); }
    }

    /** Starts/replaces a search, using NIP-50 where advertised and recent-event matching elsewhere. */
    public void search(String query, boolean followingOnly) {
        String clean = query == null ? "" : query.trim();
        if (clean.length() > 128) throw new IllegalArgumentException("Search is limited to 128 characters");
        SearchSpec spec = parseSearchSpec(clean);
        closeSearch();
        searchQuery = clean;
        searchSpec = spec;
        searchFollowingOnly = followingOnly;
        searchSubscription = clean.isEmpty() ? "" : "nc-search-" + searchCounter.incrementAndGet();
        synchronized (searchResults) { searchResults.clear(); searchSeen.clear(); }
        feedVersion++;
        if (clean.isEmpty()) return;
        if (followingOnly && following.isEmpty() && spec.author == null && spec.nip05 == null) {
            status = "No followed profiles to search";
            return;
        }
        if (spec.nip05 != null) {
            final String activeSubscription = searchSubscription;
            status = "Resolving NIP-05: " + spec.nip05;
            resolveNip05(spec.nip05, new Nip05ResolverListener() {
                @Override public void onResolved(String pubkey) {
                    if (!activeSubscription.equals(searchSubscription)) return;
                    searchSpec = searchSpec.withAuthor(pubkey);
                    startSearch(activeSubscription);
                }
                @Override public void onError(String message) {
                    if (activeSubscription.equals(searchSubscription)) status = "NIP-05 lookup failed: " + message;
                }
            });
            return;
        }
        startSearch(searchSubscription);
    }

    private void startSearch(String subscription) {
        if (!subscription.equals(searchSubscription)) return;
        for (RelayConnection rc : openRelays()) discoverSearchSupport(rc, subscription);
        status = "Searching relays for: " + searchQuery;
    }

    private static SearchSpec parseSearchSpec(String query) {
        StringBuilder text = new StringBuilder();
        String author = null, nip05 = null, tag = null;
        Integer kind = null;
        Long since = null, until = null;
        for (String token : query.split("\\s+")) {
            if (token.startsWith("kind:")) {
                if (kind != null) throw new IllegalArgumentException("Use only one kind: filter");
                try { kind = Integer.valueOf(token.substring(5)); }
                catch (Exception e) { throw new IllegalArgumentException("kind: must be between 0 and 65535"); }
                if (kind.intValue() < 0 || kind.intValue() > 65535) throw new IllegalArgumentException("kind: must be between 0 and 65535");
            } else if (token.startsWith("from:")) {
                if (author != null || nip05 != null) throw new IllegalArgumentException("Use only one from: filter");
                String value = token.substring(5);
                Nip19.Entity entity = Nip19.parse(value);
                if (entity != null && (entity.type == Nip19.Type.NPUB || entity.type == Nip19.Type.NPROFILE)) author = entity.pubkey;
                else if (Hex.isHex(value, 64)) author = value.toLowerCase(Locale.ROOT);
                else if (isValidNip05(value)) nip05 = value;
                else throw new IllegalArgumentException("from: must be a pubkey, npub, or NIP-05 identifier");
            } else if (token.startsWith("since:")) {
                if (since != null) throw new IllegalArgumentException("Use only one since: filter");
                since = parseTime(token.substring(6));
            } else if (token.startsWith("until:")) {
                if (until != null) throw new IllegalArgumentException("Use only one until: filter");
                until = parseTime(token.substring(6));
            } else if (token.startsWith("#")) {
                if (tag != null) throw new IllegalArgumentException("Use only one #tag filter");
                tag = token.substring(1);
                if (tag.isEmpty()) throw new IllegalArgumentException("Tag filter cannot be empty");
            } else {
                if (text.length() > 0) text.append(' ');
                text.append(token);
            }
        }
        if (since != null && until != null && since.longValue() > until.longValue())
            throw new IllegalArgumentException("since: must be earlier than until:");
        return new SearchSpec(text.toString(), author, nip05, kind, tag, since, until);
    }

    private static Long parseTime(String value) {
        try {
            if (value.matches("[0-9]+")) return Long.valueOf(value);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("([1-9][0-9]*)([mhdw])").matcher(value);
            if (!m.matches()) throw new NumberFormatException();
            long amount = Long.parseLong(m.group(1));
            long unit = "m".equals(m.group(2)) ? 60L : "h".equals(m.group(2)) ? 3600L
                    : "d".equals(m.group(2)) ? 86400L : 604800L;
            long seconds = Math.multiplyExact(amount, unit);
            return Long.valueOf(Math.max(0L, System.currentTimeMillis() / 1000L - seconds));
        } catch (Exception e) {
            throw new IllegalArgumentException("Time filters use Unix seconds or durations like 2h, 7d, or 1w");
        }
    }

    private void closeSearch() {
        String old = searchSubscription;
        if (old.isEmpty()) return;
        JsonArray close = new JsonArray();
        close.add(new JsonPrimitive("CLOSE")); close.add(new JsonPrimitive(old));
        for (RelayConnection rc : openRelays()) rc.send(close.toString());
    }

    private void discoverSearchSupport(final RelayConnection c, final String subscription) {
        Boolean supported = searchSupport.get(c.url);
        if (supported != null) {
            if (subscription.equals(searchSubscription)) sendSearch(c, supported.booleanValue());
            return;
        }
        NIP11_EXECUTOR.execute(new Runnable() {
            @Override public void run() {
                boolean supported = fetchNip50Support(c.url);
                searchSupport.put(c.url, Boolean.valueOf(supported));
                if (subscription.equals(searchSubscription) && !searchQuery.isEmpty() && c.isOpen())
                    sendSearch(c, supported);
            }
        });
    }

    private boolean fetchNip50Support(String relayUrl) {
        HttpURLConnection connection = null;
        try {
            URI relay = URI.create(relayUrl);
            String scheme = "wss".equalsIgnoreCase(relay.getScheme()) ? "https" : "http";
            URL infoUrl = new URI(scheme, null, relay.getHost(), relay.getPort(), relay.getPath(), null, null).toURL();
            connection = (HttpURLConnection) infoUrl.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/nostr+json");
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(4000);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return false;
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null && body.length() < 512 * 1024) body.append(line);
            reader.close();
            JsonObject info = parser.parse(body.toString()).getAsJsonObject();
            JsonElement nips = info.get("supported_nips");
            if (nips == null || !nips.isJsonArray()) return false;
            for (JsonElement nip : nips.getAsJsonArray()) if (nip.getAsInt() == 50) return true;
        } catch (Exception ignored) {
            // If NIP-11 is unavailable, use the standard-filter fallback.
        } finally {
            if (connection != null) connection.disconnect();
        }
        return false;
    }

    private void sendSearch(RelayConnection c, boolean supportsNip50) {
        SearchSpec spec = searchSpec;
        if (searchQuery.isEmpty()) return;
        JsonObject filter = new JsonObject();
        JsonArray kinds = new JsonArray(); kinds.add(new JsonPrimitive(spec.kind == null ? 1 : spec.kind.intValue()));
        filter.add("kinds", kinds);
        if (supportsNip50 && !spec.text.isEmpty()) filter.addProperty("search", spec.text);
        filter.addProperty("limit", 200);
        if (spec.author != null) {
            JsonArray authors = new JsonArray(); authors.add(new JsonPrimitive(spec.author));
            filter.add("authors", authors);
        }
        if (spec.tag != null) {
            JsonArray tags = new JsonArray(); tags.add(new JsonPrimitive(spec.tag));
            filter.add("#t", tags);
        }
        if (spec.since != null) filter.addProperty("since", spec.since.longValue());
        if (spec.until != null) filter.addProperty("until", spec.until.longValue());
        if (searchFollowingOnly) {
            JsonArray authors = new JsonArray();
            synchronized (following) {
                for (String pubkey : following)
                    if (spec.author == null || spec.author.equals(pubkey)) authors.add(new JsonPrimitive(pubkey));
            }
            if (authors.size() == 0) {
                status = "No followed profiles to search";
                return;
            }
            filter.add("authors", authors);
        }
        JsonArray req = new JsonArray();
        req.add(new JsonPrimitive("REQ")); req.add(new JsonPrimitive(searchSubscription)); req.add(filter);
        c.send(req.toString());
    }

    public String displayName(String pubkey) {
        Profile p = profiles.get(pubkey);
        if (p != null) return p.name;
        queueMeta(pubkey);
        return shortNpub(pubkey);
    }

    /** Returns a verified NIP-05 identifier for UI display, or null until verification succeeds. */
    public String verifiedNip05(String pubkey) {
        Profile profile = profiles.get(pubkey);
        return profile != null && profile.nip05Verified ? profile.nip05 : null;
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
        for (String req : customSubscriptions.values()) c.send(req);
        if (isSearching()) discoverSearchSupport(c, searchSubscription);
        requestFollowList(c);
    }

    private void requestFollowList() {
        for (RelayConnection rc : openRelays()) requestFollowList(rc);
    }

    private void requestFollowList(RelayConnection c) {
        NostrKeys account = keys;
        if (account != null) c.send("[\"REQ\",\"nc-follow-list\",{\"kinds\":[3],\"authors\":[\"" + account.getPubHex() + "\"],\"limit\":1}]");
    }

    @Override
    public void onClose(RelayConnection c) {
        status = "Lost connection: " + host(c.url);
    }

    @Override
    public void onMessage(RelayConnection c, String text) {
        JsonArray a;
        try {
            JsonElement parsed = parser.parse(text);
            if (!parsed.isJsonArray()) return;
            a = parsed.getAsJsonArray();
            if (a.size() == 0 || !a.get(0).isJsonPrimitive()) return;
        } catch (Exception ignored) { return; }
        try {
        String type = a.get(0).getAsString();
        if ("EVENT".equals(type)) {
            if (a.size() >= 3) handleEvent(a.get(2), a.get(1).getAsString(), c);
        } else if ("OK".equals(type)) {
            boolean ok = a.get(2).getAsBoolean();
            String why = a.size() > 3 ? a.get(3).getAsString() : "";
            status = ok ? "\u00a7a" + host(c.url) + " accepted your note"
                        : "\u00a7c" + host(c.url) + " rejected: " + why;
        } else if ("EOSE".equals(type)) {
            String sub = a.get(1).getAsString();
            SubscriptionListener listener = subscriptionListeners.get(sub);
            if (listener != null) try { listener.onEose(c.url); } catch (Throwable ignored) { }
            if (sub.startsWith("nc-meta") || "nc-follow-list".equals(sub))
                c.send("[\"CLOSE\",\"" + sub + "\"]");
        } else if ("NOTICE".equals(type)) {
            status = "\u00a7e" + host(c.url) + ": " + a.get(1).getAsString();
        } else if ("CLOSED".equals(type)) {
            String sub = a.size() > 1 ? a.get(1).getAsString() : "";
            SubscriptionListener listener = subscriptionListeners.get(sub);
            if (listener != null) try { listener.onClosed(c.url, a.size() > 2 ? a.get(2).getAsString() : ""); } catch (Throwable ignored) { }
            Boolean supported = searchSupport.get(c.url);
            if (sub.equals(searchSubscription) && Boolean.TRUE.equals(supported)) {
                searchSupport.put(c.url, Boolean.FALSE);
                sendSearch(c, false);
                status = "\u00a7e" + host(c.url) + " rejected NIP-50; trying recent notes locally";
            } else {
                status = sub.startsWith("nc-search-")
                        ? "\u00a7e" + host(c.url) + " could not complete the search request"
                        : "\u00a7e" + host(c.url) + " closed sub: " + (a.size() > 2 ? a.get(2).getAsString() : "");
            }
        }
        } catch (Exception ignored) { /* Ignore malformed relay frames. */ }
    }

    private void handleEvent(JsonElement el, String subscription, RelayConnection relay) {
        if (subscription.startsWith("nc-search-") && !subscription.equals(searchSubscription)) return;
        NostrEvent e = NostrEvent.fromJson(el);
        if (e == null) return;
        if (!e.verify(VERIFY_SIGNATURES)) return;
        SubscriptionListener listener = subscriptionListeners.get(subscription);
        if (listener != null) try { listener.onEvent(e, relay.url); } catch (Throwable ignored) { }
        if (subscription.startsWith("nc-entity-")) {
            if (e.kind == NostrEvent.KIND_METADATA) handleProfile(e);
            return;
        }
        boolean searchResult = !searchSubscription.isEmpty() && searchSubscription.equals(subscription);

        if (e.kind == NostrEvent.KIND_METADATA) {
            handleProfile(e);
            if (searchResult && searchSpec.kind != null && searchSpec.kind.intValue() == NostrEvent.KIND_METADATA) {
                Boolean supportsNip50 = searchSupport.get(relay.url);
                if (supportsNip50 == null || supportsNip50.booleanValue() || matchesSearch(e.content, searchSpec)) addToSearch(e);
            }
            return;
        }
        if (e.kind == NostrEvent.KIND_CONTACT_LIST) {
            if (searchResult) { addToSearch(e); return; }
            NostrKeys account = keys;
            if (account == null || !account.getPubHex().equals(e.pubkey)) return;
            handleFollowList(e);
            return;
        }
        if (searchResult) {
            SearchSpec spec = searchSpec;
            if (e.kind == NostrEvent.KIND_TEXT_NOTE && (HIDE_REPLIES && e.hasTag("e")
                    || e.createdAt > System.currentTimeMillis() / 1000L + 600 || e.content.trim().isEmpty())) return;
            Boolean supportsNip50 = searchSupport.get(relay.url);
            if (supportsNip50 != null && !supportsNip50.booleanValue() && !matchesSearch(e.content, spec)) return;
            addToSearch(e);
            return;
        }
        if (e.kind != NostrEvent.KIND_TEXT_NOTE) return;
        if (HIDE_REPLIES && e.hasTag("e")) return;
        if (e.createdAt > System.currentTimeMillis() / 1000L + 600) return; // ignore far-future spam
        if (e.content.trim().isEmpty()) return;
        addToFeed(e);
        queueMeta(e.pubkey);
    }

    private static boolean matchesSearch(String content, SearchSpec spec) {
        if (spec.text.isEmpty()) return true;
        String text = content.toLowerCase(Locale.ROOT);
        String[] terms = spec.text.toLowerCase(Locale.ROOT).trim().split("\\s+");
        for (String term : terms) if (!term.isEmpty() && !text.contains(term)) return false;
        return true;
    }

    private void addToSearch(NostrEvent ev) {
        synchronized (searchResults) {
            if (!searchSeen.add(ev.id)) return;
            int i = 0;
            while (i < searchResults.size() && searchResults.get(i).createdAt >= ev.createdAt) i++;
            searchResults.add(i, ev);
            while (searchResults.size() > 200) searchSeen.remove(searchResults.remove(searchResults.size() - 1).id);
        }
        feedVersion++;
    }

    private void handleFollowList(NostrEvent e) {
        Set<String> next = new HashSet<String>();
        synchronized (following) {
            if (e.createdAt < followListCreatedAt) return;
            for (List<String> tag : e.tags) {
                if (tag.size() >= 2 && "p".equals(tag.get(0)) && Hex.isHex(tag.get(1), 64)) next.add(tag.get(1).toLowerCase());
            }
            following.clear();
            following.addAll(next);
            followListCreatedAt = e.createdAt;
        }
        if (!next.isEmpty()) {
            JsonArray authors = new JsonArray();
            for (String pubkey : next) authors.add(new JsonPrimitive(pubkey));
            String req = "[\"REQ\",\"nc-follow-feed\",{\"kinds\":[1],\"authors\":" + authors + ",\"limit\":200}]";
            for (RelayConnection rc : openRelays()) rc.send(req);
        }
        if (isSearching() && searchFollowingOnly) {
            synchronized (searchResults) { searchResults.clear(); searchSeen.clear(); }
            for (RelayConnection rc : openRelays()) discoverSearchSupport(rc, searchSubscription);
        }
        feedVersion++;
        status = "Following " + next.size() + " profile(s)";
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
            if (name.isEmpty()) name = shortNpub(e.pubkey);
            String nip05 = str(o, "nip05").trim();
            String website = str(o, "website").trim();
            String banner = str(o, "banner").trim();
            Boolean bot = booleanValue(o, "bot");
            JsonObject birthday = objectValue(o, "birthday");
            Integer birthdayYear = birthday == null ? null : integerValue(birthday, "year");
            Integer birthdayMonth = birthday == null ? null : integerValue(birthday, "month");
            Integer birthdayDay = birthday == null ? null : integerValue(birthday, "day");
            Profile profile = new Profile(e.createdAt, name, nip05, website, banner, bot,
                    birthdayYear, birthdayMonth, birthdayDay);
            profiles.put(e.pubkey, profile);
            if (isValidNip05(nip05)) verifyNip05(e.pubkey, profile);
            feedVersion++;
        } catch (Exception ignored) { }
    }

    private static Boolean booleanValue(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                ? value.getAsBoolean() : null;
    }

    private static JsonObject objectValue(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static Integer integerValue(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return null;
        try {
            long number = value.getAsBigDecimal().longValueExact();
            if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) return null;
            return (int) number;
        } catch (ArithmeticException ignored) {
            return null;
        }
    }

    private static boolean isValidNip05(String identifier) {
        if (identifier == null || identifier.length() > 320) return false;
        int at = identifier.indexOf('@');
        if (at < 0 || at != identifier.lastIndexOf('@')) return false;
        String local = identifier.substring(0, at);
        String domain = identifier.substring(at + 1);
        return local.matches("[a-z0-9._-]+") && !local.startsWith(".") && !local.endsWith(".")
                && domain.length() <= 253 && domain.matches("(?i)[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?")
                && !domain.contains("..") && domain.indexOf('.') > 0;
    }

    /** Resolves a NIP-05 identifier and confirms it against the author's kind-0 profile event. */
    public void resolveNip05(final String identifier, final Nip05ResolverListener listener) {
        if (listener == null) throw new IllegalArgumentException("Listener is required");
        if (!isValidNip05(identifier)) {
            listener.onError("Invalid NIP-05 identifier");
            return;
        }
        NIP05_EXECUTOR.execute(new Runnable() {
            @Override public void run() {
                final String pubkey;
                try { pubkey = lookupNip05(identifier); }
                catch (Exception e) { listener.onError("Could not read the domain mapping"); return; }
                if (pubkey == null) { listener.onError("The name is not mapped to a public key"); return; }

                ensureStarted();
                final String subscription = "nc-nip05-" + entityRequestCounter.incrementAndGet();
                final AtomicBoolean finished = new AtomicBoolean();
                final String expected = normalizeNip05(identifier);
                subscribe(subscription, new SubscriptionListener() {
                    @Override public void onEvent(NostrEvent event, String relayUrl) {
                        if (event.kind != NostrEvent.KIND_METADATA || !pubkey.equals(event.pubkey)) return;
                        try {
                            String actual = str(parser.parse(event.content).getAsJsonObject(), "nip05").trim();
                            if (!expected.equals(normalizeNip05(actual))) return;
                            if (finished.compareAndSet(false, true)) {
                                closeSubscription(subscription);
                                listener.onResolved(pubkey);
                            }
                        } catch (Exception ignored) { }
                    }
                }, new NostrFilter().kinds(NostrEvent.KIND_METADATA).authors(pubkey).limit(1));
                RelayConnection.GROUP.schedule(new Runnable() {
                    @Override public void run() {
                        if (finished.compareAndSet(false, true)) {
                            closeSubscription(subscription);
                            listener.onError("No matching profile was found on connected relays");
                        }
                    }
                }, 12, TimeUnit.SECONDS);
            }
        });
    }

    private String lookupNip05(String identifier) throws Exception {
        int at = identifier.indexOf('@');
        String local = identifier.substring(0, at);
        String domain = identifier.substring(at + 1);
        HttpURLConnection connection = null;
        try {
            String endpoint = "https://" + domain + "/.well-known/nostr.json?name="
                    + java.net.URLEncoder.encode(local, "UTF-8");
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(6000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null && body.length() < 65536) body.append(line);
            reader.close();
            JsonObject root = parser.parse(body.toString()).getAsJsonObject();
            JsonElement names = root.get("names");
            if (names == null || !names.isJsonObject()) return null;
            JsonElement key = names.getAsJsonObject().get(local);
            if (key == null || !key.isJsonPrimitive() || !key.getAsJsonPrimitive().isString()) return null;
            String pubkey = key.getAsString();
            return Hex.isHex(pubkey, 64) && pubkey.equals(pubkey.toLowerCase(Locale.ROOT)) ? pubkey : null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String normalizeNip05(String identifier) {
        int at = identifier.indexOf('@');
        return at < 0 ? identifier : identifier.substring(0, at) + "@" + identifier.substring(at + 1).toLowerCase(Locale.ROOT);
    }

    private void verifyNip05(final String pubkey, final Profile profile) {
        NIP05_EXECUTOR.execute(new Runnable() {
            @Override public void run() {
                int at = profile.nip05.indexOf('@');
                String local = profile.nip05.substring(0, at);
                String domain = profile.nip05.substring(at + 1);
                HttpURLConnection connection = null;
                try {
                    String url = "https://" + domain + "/.well-known/nostr.json?name="
                            + java.net.URLEncoder.encode(local, "UTF-8");
                    connection = (HttpURLConnection) new URL(url).openConnection();
                    connection.setConnectTimeout(5000);
                    connection.setReadTimeout(5000);
                    connection.setInstanceFollowRedirects(false);
                    connection.setRequestProperty("Accept", "application/json");
                    int code = connection.getResponseCode();
                    if (code != HttpURLConnection.HTTP_OK) return;
                    BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder body = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null && body.length() < 65536) body.append(line);
                    reader.close();
                    JsonObject root = parser.parse(body.toString()).getAsJsonObject();
                    JsonElement namesElement = root.get("names");
                    if (namesElement == null || !namesElement.isJsonObject()) return;
                    JsonElement mappedKey = namesElement.getAsJsonObject().get(local);
                    if (mappedKey == null || !mappedKey.isJsonPrimitive() || !mappedKey.getAsJsonPrimitive().isString()) return;
                    if (!pubkey.equals(mappedKey.getAsString())) return;
                    if (profiles.get(pubkey) == profile) {
                        profile.nip05Verified = true;
                        feedVersion++;
                    }
                } catch (Exception ignored) {
                    // Unreachable, invalid, or malformed NIP-05 endpoints remain unverified.
                } finally {
                    if (connection != null) connection.disconnect();
                }
            }
        });
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

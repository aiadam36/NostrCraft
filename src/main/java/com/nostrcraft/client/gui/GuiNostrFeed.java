package com.nostrcraft.client.gui;

import com.nostrcraft.client.Fmt;
import com.nostrcraft.nostr.NostrClient;
import com.nostrcraft.nostr.NostrEvent;
import com.nostrcraft.nostr.Nip19;
import com.nostrcraft.nostr.Bech32;
import com.nostrcraft.nostr.Hex;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The Nostr browser: scrollable global/following feed + a box to publish a note. */
public class GuiNostrFeed extends GuiScreen {
    private static final int BTN_POST = 0, BTN_ACCOUNT = 1, BTN_FEED = 2, BTN_SEARCH = 3;
    private static final Pattern NOSTR_REFERENCE = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_])(?:nostr:)?(?:npub|note|nprofile|nevent|naddr)1[023456789acdefghjklmnpqrstuvwxyz]{6,4994}");

    private static final class LinkSpan {
        final int start, end;
        final Nip19.Entity entity;
        LinkSpan(int start, int end, Nip19.Entity entity) { this.start = start; this.end = end; this.entity = entity; }
    }

    private static final class FeedLine {
        final String text;
        final List<LinkSpan> links;
        FeedLine(String text, List<LinkSpan> links) { this.text = text; this.links = links; }
    }

    private static final class LinkHitbox {
        final int x, y, width, height;
        final Nip19.Entity entity;
        LinkHitbox(int x, int y, int width, int height, Nip19.Entity entity) {
            this.x = x; this.y = y; this.width = width; this.height = height; this.entity = entity;
        }
        boolean contains(int mouseX, int mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }
    }

    private final NostrClient nc = NostrClient.get();
    private GuiTextField compose;
    private GuiTextField searchBox;
    private GuiButton accountButton;
    private GuiButton feedButton;
    private GuiButton searchButton;
    private boolean followingFeed;
    private final List<FeedLine> lines = new ArrayList<FeedLine>();
    private final List<LinkHitbox> linkHitboxes = new ArrayList<LinkHitbox>();
    private int scroll;
    private int builtVersion = -1, builtWidth = -1;
    private long builtAt;
    private int searchActionToken;
    private int px, pw, feedTop, feedBottom;

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        nc.ensureStarted();
        pw = Math.min(width - 20, 380);
        px = (width - pw) / 2;
        feedTop = 58;
        feedBottom = height - 52;
        buttonList.clear();
        accountButton = addButton(new GuiButton(BTN_ACCOUNT, px + pw - 90, 8, 90, 20, accountLabel()));
        feedButton = addButton(new GuiButton(BTN_FEED, px + 86, 8, 90, 20, feedLabel()));
        searchButton = addButton(new GuiButton(BTN_SEARCH, px + pw - 56, 33, 56, 20, searchButtonLabel()));
        String oldSearch = searchBox == null ? nc.getSearchQuery() : searchBox.getText();
        searchBox = new GuiTextField(11, fontRenderer, px, 33, pw - 62, 20);
        searchBox.setMaxStringLength(128);
        searchBox.setText(oldSearch);
        addButton(new GuiButton(BTN_POST, px + pw - 60, height - 43, 60, 20, "Post"));
        String old = compose == null ? "" : compose.getText();
        compose = new GuiTextField(10, fontRenderer, px, height - 43, pw - 66, 20);
        compose.setMaxStringLength(500);
        compose.setText(old);
        compose.setFocused(true);
        builtVersion = -1;
    }

    private String accountLabel() {
        return nc.isLoggedIn() ? "Account" : "Login";
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        searchActionToken++;
    }

    @Override
    public boolean doesGuiPauseGame() { return false; }

    @Override
    public void updateScreen() { compose.updateCursorCounter(); }

    @Override
    protected void actionPerformed(GuiButton b) throws IOException {
        if (b.id == BTN_POST) post();
        else if (b.id == BTN_ACCOUNT) mc.displayGuiScreen(new GuiNostrLogin(this));
        else if (b.id == BTN_FEED) { followingFeed = !followingFeed; scroll = 0; builtVersion = -1; }
        else if (b.id == BTN_SEARCH) {
            if (nc.isSearching()) {
                nc.search("", followingFeed);
                searchBox.setText("");
                scroll = 0;
                builtVersion = -1;
            } else runSearch();
        }
    }

    private void post() {
        String text = compose.getText().trim();
        if (text.isEmpty()) return;
        try {
            nc.publish(text);
            compose.setText("");
            scroll = 0;
        } catch (IllegalStateException e) {
            nc.setStatus("\u00a7c" + e.getMessage());
        }
    }

    private void runSearch() {
        try {
            String query = searchBox.getText().trim();
            final int actionToken = ++searchActionToken;
            if (query.matches("(?i)(?:nostr:)?nsec1[0-9a-z]+")) {
                nc.setStatus("\u00a7cPrivate keys cannot be searched");
                return;
            }
            Nip19.Entity reference = Nip19.parse(query);
            if (reference != null) {
                nc.search("", followingFeed);
                mc.displayGuiScreen(new GuiNostrEntity(this, reference));
                return;
            }
            if (query.indexOf('@') > 0 && query.indexOf(' ') < 0) {
                nc.search("", followingFeed);
                nc.setStatus("Resolving NIP-05: " + query);
                nc.resolveNip05(query, new NostrClient.Nip05ResolverListener() {
                    @Override public void onResolved(final String pubkey) {
                        mc.addScheduledTask(new Runnable() {
                            @Override public void run() {
                                if (mc.currentScreen != GuiNostrFeed.this || actionToken != searchActionToken) return;
                                Nip19.Entity profile = Nip19.parse(Bech32.encode("npub", Hex.decode(pubkey)));
                                if (profile != null) mc.displayGuiScreen(new GuiNostrEntity(GuiNostrFeed.this, profile));
                            }
                        });
                    }
                    @Override public void onError(final String message) {
                        mc.addScheduledTask(new Runnable() {
                            @Override public void run() {
                                if (mc.currentScreen == GuiNostrFeed.this && actionToken == searchActionToken)
                                    nc.setStatus("NIP-05 lookup failed: " + message);
                            }
                        });
                    }
                });
                return;
            }
            if (nc.isSearching() && query.equals(nc.getSearchQuery())) {
                nc.search("", followingFeed);
            } else {
                nc.search(query, followingFeed);
            }
            scroll = 0;
            builtVersion = -1;
        } catch (IllegalArgumentException e) {
            nc.setStatus("\u00a7c" + e.getMessage());
        }
    }

    @Override
    protected void keyTyped(char c, int key) throws IOException {
        if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
            if (searchBox.isFocused()) runSearch();
            else post();
            return;
        }
        if (searchBox.textboxKeyTyped(c, key)) return;
        if (compose.textboxKeyTyped(c, key)) return;
        super.keyTyped(c, key); // ESC closes
    }

    @Override
    protected void mouseClicked(int mx, int my, int btn) throws IOException {
        super.mouseClicked(mx, my, btn);
        if (btn == 0) {
            for (LinkHitbox hitbox : linkHitboxes) if (hitbox.contains(mx, my)) {
                mc.displayGuiScreen(new GuiNostrEntity(this, hitbox.entity));
                return;
            }
        }
        searchBox.mouseClicked(mx, my, btn);
        compose.mouseClicked(mx, my, btn);
        searchBox.setFocused(mx >= px && mx <= px + pw - 62 && my >= 33 && my <= 53);
        compose.setFocused(mx >= px && mx <= px + pw - 66 && my >= height - 43 && my <= height - 23);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int d = Mouse.getEventDWheel();
        if (d != 0) scroll = Math.max(0, scroll - Integer.signum(d) * 3);
    }

    private void rebuildLines() {
        lines.clear();
        List<NostrEvent> feed = nc.isSearching() ? nc.getSearchResults() : nc.getFeed();
        if (!nc.isSearching() && followingFeed) {
            List<NostrEvent> filtered = new ArrayList<NostrEvent>();
            for (NostrEvent e : feed) if (nc.follows(e.pubkey)) filtered.add(e);
            feed = filtered;
        }
        if (feed.isEmpty()) addLine(nc.isSearching()
                ? "\u00a77Searching recent relay history..."
                : "\u00a77Waiting for notes from relays...");
        for (NostrEvent e : feed) {
            String verified = nc.verifiedNip05(e.pubkey) == null ? "" : " \u00a7a[NIP-05]";
            addLine("\u00a7b" + nc.displayName(e.pubkey) + verified + " \u00a78- \u00a77" + Fmt.ago(e.createdAt));
            addWrappedContent(Fmt.clean(e.content, 600), 12);
            addLine("");
        }
        builtVersion = nc.getFeedVersion();
        builtWidth = width;
        builtAt = System.currentTimeMillis();
    }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        drawDefaultBackground();
        drawRect(px - 6, 4, px + pw + 6, height - 4, 0xC0150B22);

        if (builtVersion != nc.getFeedVersion() || builtWidth != width || System.currentTimeMillis() - builtAt > 15000)
            rebuildLines();
        accountButton.displayString = accountLabel();
        feedButton.displayString = feedLabel();
        searchButton.displayString = searchButtonLabel();

        drawString(fontRenderer, "\u00a7d\u00a7lNostrCraft", px, 9, 0xFFFFFF);
        String who = nc.isLoggedIn() ? "\u00a77" + shorten(nc.getNpub()) : "\u00a78not logged in";
        drawString(fontRenderer, who, px, 20, 0xFFFFFF);
        searchBox.drawTextBox();
        if (searchBox.getText().isEmpty() && !searchBox.isFocused())
            drawString(fontRenderer, "\u00a78Text, npub, name@domain, or filters", px + 4, 39, 0xFFFFFF);

        int visible = (feedBottom - feedTop) / 10;
        int max = Math.max(0, lines.size() - visible);
        if (scroll > max) scroll = max;
        linkHitboxes.clear();
        for (int i = 0; i < visible && scroll + i < lines.size(); i++)
            drawFeedLine(lines.get(scroll + i), px, feedTop + i * 10, mx, my);
        if (max > 0) { // scrollbar
            int h = feedBottom - feedTop;
            int barH = Math.max(12, h * visible / lines.size());
            int barY = feedTop + (h - barH) * scroll / max;
            drawRect(px + pw + 1, feedTop, px + pw + 3, feedBottom, 0x40FFFFFF);
            drawRect(px + pw + 1, barY, px + pw + 3, barY + barH, 0xC0B084F0);
        }

        compose.drawTextBox();
        if (compose.getText().isEmpty() && !compose.isFocused())
            drawString(fontRenderer, "\u00a78What's on your mind?", px + 4, height - 37, 0xFFFFFF);
        drawString(fontRenderer, nc.getStatus(), px, height - 17, 0xAAAAAA);
        super.drawScreen(mx, my, pt);
    }

    private void addLine(String text) {
        lines.add(new FeedLine(text, new ArrayList<LinkSpan>()));
    }

    private void addWrappedContent(String content, int maxLines) {
        StringBuilder rendered = new StringBuilder();
        List<LinkSpan> spans = new ArrayList<LinkSpan>();
        Matcher matcher = NOSTR_REFERENCE.matcher(content);
        int copiedThrough = 0;
        while (matcher.find()) {
            Nip19.Entity entity = Nip19.parse(matcher.group());
            if (entity == null) continue;
            rendered.append(content, copiedThrough, matcher.start());
            int start = rendered.length();
            String label = referenceLabel(entity);
            rendered.append(label);
            spans.add(new LinkSpan(start, rendered.length(), entity));
            copiedThrough = matcher.end();
        }
        rendered.append(content, copiedThrough, content.length());

        String[] paragraphs = rendered.toString().split("\\n", -1);
        int added = 0;
        boolean truncated = false;
        for (int paragraphIndex = 0; paragraphIndex < paragraphs.length && !truncated; paragraphIndex++) {
            String paragraph = paragraphs[paragraphIndex];
            List<?> wrapped = fontRenderer.listFormattedStringToWidth(paragraph, pw - 8);
            int searchFrom = 0;
            for (Object item : wrapped) {
                if (added >= maxLines) { truncated = true; break; }
                String line = (String) item;
                int lineStart = paragraph.indexOf(line, searchFrom);
                if (lineStart < 0) lineStart = searchFrom;
                int lineEnd = lineStart + line.length();
                int globalStart = 0;
                for (int i = 0; i < paragraphIndex; i++) globalStart += paragraphs[i].length() + 1;
                List<LinkSpan> lineLinks = new ArrayList<LinkSpan>();
                for (LinkSpan span : spans) {
                    int start = Math.max(span.start, globalStart + lineStart);
                    int end = Math.min(span.end, globalStart + lineEnd);
                    if (start < end) lineLinks.add(new LinkSpan(start - (globalStart + lineStart), end - (globalStart + lineStart), span.entity));
                }
                lines.add(new FeedLine(line, lineLinks));
                added++;
                searchFrom = lineEnd;
            }
            if (paragraphIndex < paragraphs.length - 1 && !truncated && added < maxLines) {
                addLine("");
                added++;
            }
        }
        if (truncated) addLine("\u00a78...");
    }

    private String referenceLabel(Nip19.Entity entity) {
        switch (entity.type) {
            case NPUB:
            case NPROFILE:
                return "[profile: " + Fmt.clean(nc.displayName(entity.pubkey), 32) + "]";
            case NOTE:
                return "[note " + abbreviate(entity.eventId) + "]";
            case NEVENT:
                return "[event " + abbreviate(entity.eventId) + "]";
            case NADDR:
                return "[address: " + Fmt.clean(entity.identifier, 24) + "]";
            default:
                return "[Nostr reference]";
        }
    }

    private static String abbreviate(String hex) {
        return hex.substring(0, 8) + "..." + hex.substring(hex.length() - 6);
    }

    private void drawFeedLine(FeedLine line, int x, int y, int mouseX, int mouseY) {
        if (line.links.isEmpty()) {
            drawString(fontRenderer, line.text, x, y, 0xFFFFFF);
            return;
        }
        int position = 0;
        for (LinkSpan link : line.links) {
            if (link.start > position) {
                String before = line.text.substring(position, link.start);
                drawString(fontRenderer, before, x, y, 0xFFFFFF);
                x += fontRenderer.getStringWidth(before);
            }
            String label = line.text.substring(link.start, link.end);
            int linkWidth = fontRenderer.getStringWidth(label);
            boolean hovered = mouseX >= x && mouseX < x + linkWidth && mouseY >= y && mouseY < y + 9;
            drawString(fontRenderer, (hovered ? "\u00a7e" : "\u00a7b") + label, x, y, 0xFFFFFF);
            linkHitboxes.add(new LinkHitbox(x, y, linkWidth, 9, link.entity));
            x += fontRenderer.getStringWidth(label);
            position = link.end;
        }
        if (position < line.text.length())
            drawString(fontRenderer, line.text.substring(position), x, y, 0xFFFFFF);
    }

    private static String shorten(String s) {
        return s.length() > 20 ? s.substring(0, 12) + "..." + s.substring(s.length() - 6) : s;
    }

    private String feedLabel() {
        return followingFeed ? "Following (" + nc.getFollowingCount() + ")" : "Global";
    }

    private String searchButtonLabel() { return nc.isSearching() ? "Clear" : "Search"; }
}

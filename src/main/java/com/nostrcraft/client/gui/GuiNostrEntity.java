package com.nostrcraft.client.gui;

import com.nostrcraft.client.Fmt;
import com.nostrcraft.nostr.Bech32;
import com.nostrcraft.nostr.Hex;
import com.nostrcraft.nostr.Nip19;
import com.nostrcraft.nostr.NostrClient;
import com.nostrcraft.nostr.NostrEvent;
import com.nostrcraft.nostr.Nip89;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** In-game detail view opened from an NIP-19 reference in the feed. */
public class GuiNostrEntity extends GuiScreen {
    private static final int BTN_BACK = 0;
    private static final int BTN_DISCOVER = 1;

    private final GuiScreen parent;
    private final Nip19.Entity entity;
    private final NostrClient nc = NostrClient.get();
    private final List<NostrEvent> received = Collections.synchronizedList(new ArrayList<NostrEvent>());
    private volatile boolean loaded;
    private String subscription;
    private String error;
    private final List<Nip89.Handler> handlers = new ArrayList<Nip89.Handler>();
    private boolean discovering;

    public GuiNostrEntity(GuiScreen parent, Nip19.Entity entity) {
        this.parent = parent;
        this.entity = entity;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        addButton(new GuiButton(BTN_BACK, width / 2 - 60, height - 30, 120, 20, "Back"));
        addButton(new GuiButton(BTN_DISCOVER, width / 2 - 160, height - 30, 95, 20, "Find an app"));
        if (subscription != null) nc.closeSubscription(subscription);
        loaded = false;
        error = null;
        try {
            subscription = nc.requestEntity(entity, new NostrClient.SubscriptionListener() {
                @Override public void onEvent(NostrEvent event, String relayUrl) {
                    synchronized (received) {
                        for (NostrEvent old : received) if (old.id.equals(event.id)) return;
                        received.add(event);
                    }
                }
                @Override public void onEose(String relayUrl) { loaded = true; }
            });
        } catch (IllegalArgumentException e) {
            error = e.getMessage();
            loaded = true;
        }
    }

    @Override
    public void onGuiClosed() {
        if (subscription != null) {
            nc.closeSubscription(subscription);
            subscription = null;
        }
    }

    @Override public boolean doesGuiPauseGame() { return false; }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == BTN_BACK) mc.displayGuiScreen(parent);
        else if (button.id == BTN_DISCOVER) {
            NostrEvent target = currentTarget();
            if (target != null) discover(target.kind);
        }
        else if (button.id >= 100 && button.id < 100 + handlers.size()) openHandler(button.id - 100);
    }

    private void discover(final int kind) {
        if (discovering) return;
        discovering = true;
        nc.discoverHandlers(kind, new NostrClient.Nip89Listener() {
            @Override public void onHandlers(final List<Nip89.Handler> found) {
                mc.addScheduledTask(new Runnable() {
                    @Override public void run() {
                        if (mc.currentScreen != GuiNostrEntity.this) return;
                        handlers.clear(); handlers.addAll(found); discovering = false;
                        buttonList.removeIf(b -> b.id >= 100);
                        int y = height - 30;
                        for (int i = 0; i < handlers.size() && i < 3; i++)
                            addButton(new GuiButton(100 + i, width / 2 - 55 + (i * 2), y - 24 * (i + 1), 110, 20,
                                    handlers.get(i).name.length() > 16 ? handlers.get(i).name.substring(0, 16) : handlers.get(i).name));
                    }
                });
            }
        });
    }

    private NostrEvent currentTarget() {
        for (NostrEvent event : snapshot()) if (matchesTarget(event)) return event;
        return null;
    }

    private void openHandler(int index) {
        if (index < 0 || index >= handlers.size()) return;
        Nip89.Handler handler = handlers.get(index);
        if (handler.urls.isEmpty()) { error = "Handler has no supported web URL"; return; }
        String value = handler.urls.get(0);
        if (value.contains("bech32")) {
            if (entity.eventId == null) { error = "This handler needs an event reference"; return; }
            value = value.replace("bech32", Bech32.encode("note", Hex.decode(entity.eventId)));
        }
        if (!Nip89.safeHttpUrl(value)) { error = "Handler URL is invalid"; return; }
        try {
            if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().browse(new java.net.URI(value));
            else error = "Desktop link opening is unavailable";
        } catch (Exception e) { error = "Could not open handler URL"; }
    }

    @Override
    protected void keyTyped(char c, int key) throws IOException {
        if (key == Keyboard.KEY_ESCAPE) { mc.displayGuiScreen(parent); return; }
        super.keyTyped(c, key);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        int left = 20;
        int right = width - 20;
        drawRect(left - 6, 8, right + 6, height - 34, 0xC0150B22);

        if (entity.type == Nip19.Type.NPUB || entity.type == Nip19.Type.NPROFILE) drawProfile(left, right);
        else drawEvent(left, right);
        if (error != null) drawWrapped("\u00a7c" + Fmt.clean(error, 160), left, height - 55, right - left);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawProfile(int left, int right) {
        String name = nc.displayName(entity.pubkey);
        drawString(fontRenderer, "\u00a7d\u00a7lProfile", left, 16, 0xFFFFFF);
        drawString(fontRenderer, "\u00a7b" + Fmt.clean(name, 48), left, 32, 0xFFFFFF);
        String npub;
        try { npub = Bech32.encode("npub", Hex.decode(entity.pubkey)); }
        catch (Exception e) { npub = entity.pubkey; }
        drawString(fontRenderer, "\u00a77" + Fmt.clean(npub, 96), left, 44, 0xFFFFFF);
        ProfileBannerCache.Banner banner = ProfileBannerCache.get(nc.profileBanner(entity.pubkey));
        int detailsY = 56;
        if (banner != null) {
            float scale = Math.min((right - left) / (float) banner.width, 88f / banner.height);
            int bannerWidth = Math.max(1, Math.round(banner.width * scale));
            int bannerHeight = Math.max(1, Math.round(banner.height * scale));
            mc.getTextureManager().bindTexture(banner.texture);
            drawModalRectWithCustomSizedTexture(left, detailsY, 0, 0, bannerWidth, bannerHeight, banner.width, banner.height);
            detailsY += bannerHeight + 8;
        }
        String nip05 = nc.verifiedNip05(entity.pubkey);
        int y = detailsY;
        if (nip05 != null) {
            drawString(fontRenderer, "\u00a7aVerified NIP-05: " + Fmt.clean(nip05, 80), left, y, 0xFFFFFF);
            y += 12;
        }
        String website = nc.profileWebsite(entity.pubkey);
        if (website != null) {
            drawString(fontRenderer, "\u00a7fWebsite: " + Fmt.clean(website, 100), left, y, 0xFFFFFF);
            y += 12;
        }
        String birthday = nc.profileBirthday(entity.pubkey);
        if (birthday != null) {
            drawString(fontRenderer, "\u00a7fBirthday: " + Fmt.clean(birthday, 16), left, y, 0xFFFFFF);
            y += 12;
        }
        if (Boolean.TRUE.equals(nc.profileIsBot(entity.pubkey))) {
            drawString(fontRenderer, "\u00a7eAutomated account", left, y, 0xFFFFFF);
            y += 12;
        }
        drawString(fontRenderer, "\u00a7fRecent notes", left, y, 0xFFFFFF);
        y += 12;
        List<NostrEvent> notes = snapshot();
        Collections.sort(notes, new Comparator<NostrEvent>() {
            @Override public int compare(NostrEvent a, NostrEvent b) { return Long.compare(b.createdAt, a.createdAt); }
        });
        int shown = 0;
        for (NostrEvent note : notes) {
            if (note.kind != NostrEvent.KIND_TEXT_NOTE) continue;
            if (shown++ >= 12 || y >= height - 44) break;
            drawString(fontRenderer, "\u00a78" + Fmt.ago(note.createdAt), left, y, 0xFFFFFF);
            y += 10;
            y = drawWrapped(Fmt.clean(note.content, 600), left, y, right - left);
            y += 5;
        }
        if (shown == 0 && y < height - 44)
            drawString(fontRenderer, loaded ? "\u00a77No recent notes found" : "\u00a77Loading profile and notes...", left, y, 0xFFFFFF);
    }

    private void drawEvent(int left, int right) {
        drawString(fontRenderer, "\u00a7d\u00a7l" + (entity.type == Nip19.Type.NADDR ? "Addressable event" : "Nostr event"), left, 16, 0xFFFFFF);
        List<NostrEvent> events = snapshot();
        NostrEvent target = null;
        for (NostrEvent event : events) if (matchesTarget(event)) { target = event; break; }
        if (target == null) {
            drawString(fontRenderer, loaded ? "\u00a77Event not found on available relays" : "\u00a77Loading event from relays...", left, 38, 0xFFFFFF);
            return;
        }
        drawString(fontRenderer, "\u00a7b" + Fmt.clean(nc.displayName(target.pubkey), 48), left, 34, 0xFFFFFF);
        drawString(fontRenderer, "\u00a78Kind " + target.kind + " - " + Fmt.ago(target.createdAt), left, 46, 0xFFFFFF);
        drawString(fontRenderer, "\u00a78" + target.id.substring(0, 16) + "...", left, 58, 0xFFFFFF);
        drawWrapped(Fmt.clean(target.content, 4000), left, 76, right - left);
        if (target.kind != NostrEvent.KIND_TEXT_NOTE && !target.hasTag("alt"))
            drawString(fontRenderer, "\u00a77Unsupported event kind; use Find an app to discover a handler", left, height - 60, 0xFFFFFF);
        if (discovering) drawString(fontRenderer, "\u00a77Finding recommended apps...", left, height - 48, 0xFFFFFF);
    }

    private boolean matchesTarget(NostrEvent event) {
        if (entity.type == Nip19.Type.NOTE || entity.type == Nip19.Type.NEVENT)
            return entity.eventId.equals(event.id);
        if (entity.type != Nip19.Type.NADDR || entity.kind == null || entity.kind.longValue() != event.kind
                || !entity.pubkey.equals(event.pubkey)) return false;
        for (List<String> tag : event.tags)
            if (tag.size() > 1 && "d".equals(tag.get(0)) && entity.identifier.equals(tag.get(1))) return true;
        return false;
    }

    private List<NostrEvent> snapshot() {
        synchronized (received) { return new ArrayList<NostrEvent>(received); }
    }

    private int drawWrapped(String text, int x, int y, int maxWidth) {
        for (Object line : fontRenderer.listFormattedStringToWidth(text, maxWidth)) {
            if (y >= height - 44) break;
            drawString(fontRenderer, (String) line, x, y, 0xFFFFFF);
            y += 10;
        }
        return y;
    }
}

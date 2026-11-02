package com.nostrcraft.client.gui;

import com.nostrcraft.client.Fmt;
import com.nostrcraft.nostr.NostrClient;
import com.nostrcraft.nostr.NostrEvent;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** The Nostr browser: scrollable global feed + a box to publish a note. */
public class GuiNostrFeed extends GuiScreen {
    private static final int BTN_POST = 0, BTN_ACCOUNT = 1;

    private final NostrClient nc = NostrClient.get();
    private GuiTextField compose;
    private GuiButton accountButton;
    private final List<String> lines = new ArrayList<String>();
    private int scroll;
    private int builtVersion = -1, builtWidth = -1;
    private long builtAt;
    private int px, pw, feedTop, feedBottom;

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        nc.ensureStarted();
        pw = Math.min(width - 20, 380);
        px = (width - pw) / 2;
        feedTop = 34;
        feedBottom = height - 52;
        buttonList.clear();
        accountButton = addButton(new GuiButton(BTN_ACCOUNT, px + pw - 90, 8, 90, 20, accountLabel()));
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
    }

    @Override
    public boolean doesGuiPauseGame() { return false; }

    @Override
    public void updateScreen() { compose.updateCursorCounter(); }

    @Override
    protected void actionPerformed(GuiButton b) throws IOException {
        if (b.id == BTN_POST) post();
        else if (b.id == BTN_ACCOUNT) mc.displayGuiScreen(new GuiNostrLogin(this));
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

    @Override
    protected void keyTyped(char c, int key) throws IOException {
        if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
            post();
            return;
        }
        if (compose.textboxKeyTyped(c, key)) return;
        super.keyTyped(c, key); // ESC closes
    }

    @Override
    protected void mouseClicked(int mx, int my, int btn) throws IOException {
        super.mouseClicked(mx, my, btn);
        compose.mouseClicked(mx, my, btn);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int d = Mouse.getEventDWheel();
        if (d != 0) scroll = Math.max(0, scroll - Integer.signum(d) * 3);
    }

    private void rebuildLines() {
        lines.clear();
        List<NostrEvent> feed = nc.getFeed();
        if (feed.isEmpty()) lines.add("\u00a77Waiting for notes from relays...");
        for (NostrEvent e : feed) {
            lines.add("\u00a7b" + nc.displayName(e.pubkey) + " \u00a78- \u00a77" + Fmt.ago(e.createdAt));
            int n = 0;
            for (Object l : fontRenderer.listFormattedStringToWidth(Fmt.clean(e.content, 600), pw - 8)) {
                if (n++ >= 12) { lines.add("\u00a78..."); break; }
                lines.add((String) l);
            }
            lines.add("");
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

        drawString(fontRenderer, "\u00a7d\u00a7lNostrCraft", px, 9, 0xFFFFFF);
        String who = nc.isLoggedIn() ? "\u00a77" + shorten(nc.getNpub()) : "\u00a78not logged in";
        drawString(fontRenderer, who, px, 20, 0xFFFFFF);

        int visible = (feedBottom - feedTop) / 10;
        int max = Math.max(0, lines.size() - visible);
        if (scroll > max) scroll = max;
        for (int i = 0; i < visible && scroll + i < lines.size(); i++)
            drawString(fontRenderer, lines.get(scroll + i), px, feedTop + i * 10, 0xFFFFFF);
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

    private static String shorten(String s) {
        return s.length() > 20 ? s.substring(0, 12) + "..." + s.substring(s.length() - 6) : s;
    }
}

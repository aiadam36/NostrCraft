package com.nostrcraft.client.gui;

import com.nostrcraft.nostr.NostrClient;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import org.lwjgl.input.Keyboard;

import java.io.IOException;

/** Log in with an nsec / hex key, generate a fresh identity, or log out. */
public class GuiNostrLogin extends GuiScreen {
    private static final int BTN_LOGIN = 0, BTN_GEN = 1, BTN_LOGOUT = 2, BTN_BACK = 3;

    private final GuiScreen parent;
    private final NostrClient nc = NostrClient.get();
    private GuiTextField input;
    private String message = "";

    public GuiNostrLogin(GuiScreen parent) { this.parent = parent; }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        int w = Math.min(width - 40, 300);
        int x = (width - w) / 2;
        int y = height / 2 - 30;
        String old = input == null ? "" : input.getText();
        input = new GuiTextField(10, fontRenderer, x, y, w, 20);
        input.setMaxStringLength(128);
        input.setText(old);
        input.setFocused(true);
        buttonList.clear();
        addButton(new GuiButton(BTN_LOGIN, width / 2 - 100, y + 28, 200, 20, "Login with key"));
        addButton(new GuiButton(BTN_GEN, width / 2 - 100, y + 52, 200, 20, "Generate new identity"));
        GuiButton logout = addButton(new GuiButton(BTN_LOGOUT, width / 2 - 100, y + 76, 200, 20, "Logout"));
        logout.enabled = nc.isLoggedIn();
        addButton(new GuiButton(BTN_BACK, width / 2 - 100, y + 104, 200, 20, "Back"));
    }

    @Override
    public void onGuiClosed() { Keyboard.enableRepeatEvents(false); }

    @Override
    public void updateScreen() { input.updateCursorCounter(); }

    @Override
    public boolean doesGuiPauseGame() { return false; }

    @Override
    protected void actionPerformed(GuiButton b) throws IOException {
        switch (b.id) {
            case BTN_LOGIN: login(); break;
            case BTN_GEN:
                nc.generateKey();
                message = "\u00a7aNew identity created. Back up your key with /nostr export";
                break;
            case BTN_LOGOUT:
                nc.logout();
                message = "\u00a77Logged out";
                break;
            case BTN_BACK: mc.displayGuiScreen(parent); break;
        }
        if (b.id != BTN_BACK) initGuiKeepMessage();
    }

    private void initGuiKeepMessage() {
        String m = message;
        initGui();
        message = m;
    }

    private void login() {
        try {
            nc.login(input.getText());
            input.setText("");
            message = "\u00a7aLogged in!";
        } catch (IllegalArgumentException e) {
            message = "\u00a7c" + e.getMessage();
        }
    }

    @Override
    protected void keyTyped(char c, int key) throws IOException {
        if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) { login(); initGuiKeepMessage(); return; }
        if (input.textboxKeyTyped(c, key)) return;
        if (key == Keyboard.KEY_ESCAPE) { mc.displayGuiScreen(parent); return; }
        super.keyTyped(c, key);
    }

    @Override
    protected void mouseClicked(int mx, int my, int btn) throws IOException {
        super.mouseClicked(mx, my, btn);
        input.mouseClicked(mx, my, btn);
    }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        drawDefaultBackground();
        int y = height / 2 - 30;
        drawCenteredString(fontRenderer, "\u00a7d\u00a7lNostrCraft \u00a7r- Account", width / 2, y - 48, 0xFFFFFF);
        String line = nc.isLoggedIn() ? "\u00a77Logged in as \u00a7f" + nc.getNpub() : "\u00a77Not logged in";
        drawCenteredString(fontRenderer, line, width / 2, y - 32, 0xFFFFFF);
        drawCenteredString(fontRenderer, "\u00a78Paste your nsec1... (or 64-char hex) key below", width / 2, y - 14, 0xFFFFFF);
        input.drawTextBox();
        drawCenteredString(fontRenderer, message, width / 2, y + 130, 0xFFFFFF);
        drawCenteredString(fontRenderer, "\u00a78Keys stay on your computer (config/nostrcraft.json), never sent to servers", width / 2, height - 14, 0xFFFFFF);
        super.drawScreen(mx, my, pt);
    }
}

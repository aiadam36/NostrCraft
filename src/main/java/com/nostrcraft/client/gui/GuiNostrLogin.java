package com.nostrcraft.client.gui;

import com.nostrcraft.nostr.NostrClient;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Local-key and NIP-46 account connection screen. */
public class GuiNostrLogin extends GuiScreen {
    private static final int BTN_LOGIN = 0, BTN_GEN = 1, BTN_REMOTE = 2, BTN_COPY = 3;
    private static final int BTN_DISCONNECT = 4, BTN_LOGOUT = 5, BTN_CLIENT_TAG = 6, BTN_BACK = 7;
    private static final String REMOTE_PERMISSIONS = "sign_event:0,sign_event:1,sign_event:3";

    private final GuiScreen parent;
    private final NostrClient nc = NostrClient.get();
    private GuiTextField input;
    private GuiTextField relayInput;
    private GuiTextField uriInput;
    private String message = "";

    public GuiNostrLogin(GuiScreen parent) { this.parent = parent; }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        int w = Math.min(width - 32, 320);
        int x = (width - w) / 2;
        int half = (w - 4) / 2;
        int y = Math.max(8, (height - 222) / 2);
        String oldKey = input == null ? "" : input.getText();
        String oldRelays = relayInput == null ? "wss://relay.damus.io" : relayInput.getText();
        input = new GuiTextField(10, fontRenderer, x, y + 34, w, 18);
        input.setMaxStringLength(128);
        input.setText(oldKey);
        input.setFocused(true);
        relayInput = new GuiTextField(11, fontRenderer, x, y + 91, w, 18);
        relayInput.setMaxStringLength(512);
        relayInput.setText(oldRelays);
        relayInput.setFocused(false);
        uriInput = new GuiTextField(12, fontRenderer, x, y + 132, w, 18);
        uriInput.setMaxStringLength(2048);
        uriInput.setText(nc.getRemoteConnectionUri() == null ? "" : nc.getRemoteConnectionUri());
        uriInput.setFocused(false);
        buttonList.clear();
        addButton(new GuiButton(BTN_LOGIN, x, y + 55, half, 20, "Login with key"));
        addButton(new GuiButton(BTN_GEN, x + half + 4, y + 55, half, 20, "Generate identity"));
        GuiButton remote = addButton(new GuiButton(BTN_REMOTE, x, y + 111, half, 20,
                nc.isRemoteSignerConnecting() ? "Waiting for signer..." : "Connect signer"));
        remote.enabled = !nc.isRemoteSignerConnecting();
        GuiButton copy = addButton(new GuiButton(BTN_COPY, x + half + 4, y + 111, half, 20, "Copy signer URI"));
        copy.enabled = nc.getRemoteConnectionUri() != null;
        GuiButton disconnect = addButton(new GuiButton(BTN_DISCONNECT, x, y + 153, half, 20, "Disconnect signer"));
        disconnect.enabled = nc.isRemoteSignerConnecting() || nc.isRemoteSignerConnected();
        GuiButton logout = addButton(new GuiButton(BTN_LOGOUT, x + half + 4, y + 153, half, 20, "Logout"));
        logout.enabled = nc.isLoggedIn();
        addButton(new GuiButton(BTN_CLIENT_TAG, x, y + 179, half, 20,
                "NIP-89 tag: " + (nc.isClientTagEnabled() ? "ON" : "OFF")));
        addButton(new GuiButton(BTN_BACK, x + half + 4, y + 179, half, 20, "Back"));
    }

    @Override public void onGuiClosed() { Keyboard.enableRepeatEvents(false); }
    @Override public void updateScreen() {
        input.updateCursorCounter();
        relayInput.updateCursorCounter();
        uriInput.updateCursorCounter();
        String uri = nc.getRemoteConnectionUri();
        if (uri != null && !uri.equals(uriInput.getText())) uriInput.setText(uri);
        for (GuiButton button : buttonList) {
            if (button.id == BTN_COPY) button.enabled = uri != null;
            else if (button.id == BTN_DISCONNECT)
                button.enabled = nc.isRemoteSignerConnecting() || nc.isRemoteSignerConnected();
            else if (button.id == BTN_REMOTE) {
                button.enabled = !nc.isRemoteSignerConnecting();
                button.displayString = nc.isRemoteSignerConnecting() ? "Waiting for signer..." : "Connect signer";
            }
        }
        if (nc.isRemoteSignerConnected() && !nc.isLoggedIn()) message = "\u00a7eSigner connected";
    }
    @Override public boolean doesGuiPauseGame() { return false; }

    @Override
    protected void actionPerformed(GuiButton b) throws IOException {
        switch (b.id) {
            case BTN_LOGIN: login(); break;
            case BTN_GEN:
                nc.generateKey();
                message = "\u00a7aNew identity created. Back up with /nostr export";
                break;
            case BTN_REMOTE: connectRemote(); break;
            case BTN_COPY:
                String uri = nc.getRemoteConnectionUri();
                if (uri != null) {
                    setClipboardString(uri);
                    message = "\u00a7aURI copied. Paste it into your signer app.";
                }
                break;
            case BTN_DISCONNECT:
                nc.disconnectRemoteSigner();
                message = "\u00a77Remote signer disconnected";
                break;
            case BTN_LOGOUT:
                nc.logout();
                message = "\u00a77Logged out";
                break;
            case BTN_CLIENT_TAG:
                nc.setClientTagEnabled(!nc.isClientTagEnabled());
                message = "\u00a77NIP-89 client tag " + (nc.isClientTagEnabled() ? "enabled" : "disabled");
                break;
            case BTN_BACK: mc.displayGuiScreen(parent); return;
        }
        String currentMessage = message;
        initGui();
        message = currentMessage;
    }

    private void connectRemote() {
        try {
            List<String> relays = new ArrayList<String>();
            for (String value : relayInput.getText().split(","))
                if (!value.trim().isEmpty()) relays.add(value.trim());
            if (relays.isEmpty()) throw new IllegalArgumentException("Enter at least one wss:// signer relay");
            message = "\u00a7eConnecting to signer relays...";
            nc.connectRemoteSigner(relays, REMOTE_PERMISSIONS, new NostrClient.RemoteLoginListener() {
                @Override public void onUri(String connectionUri) {
                    mc.addScheduledTask(new Runnable() {
                        @Override public void run() { message = "\u00a7eURI ready. Copy it and paste it into your signer app."; }
                    });
                }
                @Override public void onConnected(String userPubkey, String remoteSignerPubkey) {
                    mc.addScheduledTask(new Runnable() {
                        @Override public void run() {
                            message = "\u00a7aConnected as " + nc.getNpub();
                            initGui();
                        }
                    });
                }
                @Override public void onError(final String error) {
                    mc.addScheduledTask(new Runnable() {
                        @Override public void run() {
                            message = "\u00a7cSigner connection failed: " + error;
                            initGui();
                        }
                    });
                }
            });
            String uri = nc.getRemoteConnectionUri();
            if (uri != null) uriInput.setText(uri);
        } catch (IllegalArgumentException | IllegalStateException e) {
            message = "\u00a7c" + e.getMessage();
        }
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
        if (key == Keyboard.KEY_ESCAPE) { mc.displayGuiScreen(parent); return; }
        if (input.textboxKeyTyped(c, key) || relayInput.textboxKeyTyped(c, key)) return;
        super.keyTyped(c, key);
    }

    @Override
    protected void mouseClicked(int mx, int my, int btn) throws IOException {
        super.mouseClicked(mx, my, btn);
        input.mouseClicked(mx, my, btn);
        relayInput.mouseClicked(mx, my, btn);
        uriInput.mouseClicked(mx, my, btn);
    }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        drawDefaultBackground();
        int w = Math.min(width - 32, 320);
        int x = (width - w) / 2;
        int y = Math.max(8, (height - 222) / 2);
        String account = nc.isLoggedIn() ? "\u00a77Account: \u00a7f" + nc.getNpub()
                : "\u00a77Not logged in";
        drawCenteredString(fontRenderer, "\u00a7d\u00a7lNostrCraft \u00a7r- Account", width / 2, y, 0xFFFFFF);
        drawCenteredString(fontRenderer, account, width / 2, y + 14, 0xFFFFFF);
        fontRenderer.drawString("Local key (nsec or hex)", x, y + 24, 0x888888);
        input.drawTextBox();
        fontRenderer.drawString("Signer relay(s), comma separated (wss://)", x, y + 81, 0x888888);
        relayInput.drawTextBox();
        fontRenderer.drawString("Connection URI (copy and paste into signer app)", x, y + 122, 0x888888);
        uriInput.drawTextBox();
        drawCenteredString(fontRenderer, message, width / 2, y + 204, 0xFFFFFF);
        super.drawScreen(mx, my, pt);
    }
}

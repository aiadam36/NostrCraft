package com.nostrcraft.client;

import com.nostrcraft.block.BlockNostrScreen;
import com.nostrcraft.block.TileNostrScreen;
import com.nostrcraft.nostr.NostrClient;
import com.nostrcraft.nostr.NostrEvent;
import net.minecraft.block.BlockHorizontal;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.util.EnumFacing;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/** Draws a rotating ticker of the latest notes on the front face of the Nostr Screen block. */
public class TESRNostrScreen extends TileEntitySpecialRenderer<TileNostrScreen> {
    private static final int TEXT_W = 92;       // fit inside the inset display area
    private static final int MAX_LINES = 10;    // keep glyphs within the inset display height
    private static final int NOTE_LINES = 4;
    private static final float S = 1f / 128f;   // 1 block = 128 font pixels
    private static final float PAD = 18 * S;

    private final List<String> lines = new ArrayList<String>();
    private int cachedVersion = -1;
    private int cachedStatus = 0;
    private long cachedSlot = -1;

    @Override
    public void render(TileNostrScreen te, double x, double y, double z, float partialTicks, int destroyStage, float alpha) {
        World world = te.getWorld();
        if (world == null) return;
        IBlockState state = world.getBlockState(te.getPos());
        if (!(state.getBlock() instanceof BlockNostrScreen)) return;
        if (x * x + y * y + z * z > 20 * 20) return; // don't bother with far-away screens

        NostrClient nc = NostrClient.get();
        nc.ensureStarted();

        long slot = world.getTotalWorldTime() / 200L; // advance the ticker every 10 s
        if (nc.getFeedVersion() != cachedVersion || nc.getStatus().hashCode() != cachedStatus || slot != cachedSlot) {
            rebuild(nc, (int) slot);
            cachedVersion = nc.getFeedVersion();
            cachedStatus = nc.getStatus().hashCode();
            cachedSlot = slot;
        }

        EnumFacing facing = state.getValue(BlockHorizontal.FACING);
        FontRenderer fr = getFontRenderer();

        GlStateManager.pushMatrix();
        GlStateManager.translate((float) x + 0.5f, (float) y + 0.5f, (float) z + 0.5f);
        GlStateManager.rotate(-facing.getHorizontalAngle(), 0f, 1f, 0f); // local +Z = the front face
        GlStateManager.translate(0f, 0f, 0.5f + 0.003f);
        GlStateManager.translate(-0.5f + PAD, 0.5f - PAD, 0f);
        GlStateManager.scale(S, -S, S);
        GlStateManager.disableLighting();
        GlStateManager.color(1f, 1f, 1f, 1f);
        for (int i = 0; i < lines.size(); i++) fr.drawString(lines.get(i), 0, i * 9, 0xFFFFFF);
        GlStateManager.enableLighting();
        GlStateManager.popMatrix();
    }

    private void rebuild(NostrClient nc, int slot) {
        FontRenderer fr = getFontRenderer();
        lines.clear();
        lines.add("\u00a7d\u00a7lNostrCraft");
        List<NostrEvent> feed = nc.getFeed();
        if (feed.isEmpty()) {
            for (Object l : fr.listFormattedStringToWidth("\u00a77" + Fmt.clean(nc.getStatus(), 120), TEXT_W)) lines.add((String) l);
            return;
        }
        int n = feed.size();
        for (int i = 0; i < n && lines.size() < MAX_LINES; i++) {
            NostrEvent e = feed.get(Math.floorMod(slot + i, n));
            lines.add("\u00a7b" + fr.trimStringToWidth(nc.displayName(e.pubkey), TEXT_W));
            int shown = 0;
            for (Object l : fr.listFormattedStringToWidth(Fmt.clean(e.content, 160), TEXT_W)) {
                if (shown++ >= NOTE_LINES || lines.size() >= MAX_LINES) break;
                lines.add((String) l);
            }
        }
    }
}

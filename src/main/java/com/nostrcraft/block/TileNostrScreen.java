package com.nostrcraft.block;

import net.minecraft.tileentity.TileEntity;

/**
 * Marker tile entity so the block can have a TileEntitySpecialRenderer.
 * It holds no data: the feed lives client-side in NostrClient.
 * Idea for later: store a per-screen filter (relay, hashtag, author) here and sync it.
 */
public class TileNostrScreen extends TileEntity {
}

package com.nostrcraft.client;

import com.nostrcraft.ModBlocks;
import com.nostrcraft.NostrCraft;
import com.nostrcraft.block.TileNostrScreen;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

/** Client-only registrations (item model + TESR). */
@Mod.EventBusSubscriber(value = Side.CLIENT, modid = NostrCraft.MODID)
public class ClientEvents {
    @SubscribeEvent
    public static void onModels(ModelRegistryEvent e) {
        ModelLoader.setCustomModelResourceLocation(ModBlocks.NOSTR_SCREEN_ITEM, 0,
                new ModelResourceLocation(ModBlocks.NOSTR_SCREEN.getRegistryName(), "inventory"));
        ClientRegistry.bindTileEntitySpecialRenderer(TileNostrScreen.class, new TESRNostrScreen());
    }
}

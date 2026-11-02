package com.nostrcraft;

import com.nostrcraft.block.BlockNostrScreen;
import com.nostrcraft.block.TileNostrScreen;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.registry.GameRegistry;

@Mod.EventBusSubscriber(modid = NostrCraft.MODID)
public class ModBlocks {
    public static final BlockNostrScreen NOSTR_SCREEN = new BlockNostrScreen();
    public static final Item NOSTR_SCREEN_ITEM =
            new ItemBlock(NOSTR_SCREEN).setRegistryName(NOSTR_SCREEN.getRegistryName());

    @SubscribeEvent
    public static void registerBlocks(RegistryEvent.Register<Block> e) {
        e.getRegistry().register(NOSTR_SCREEN);
        GameRegistry.registerTileEntity(TileNostrScreen.class, new ResourceLocation(NostrCraft.MODID, "nostr_screen"));
    }

    @SubscribeEvent
    public static void registerItems(RegistryEvent.Register<Item> e) {
        e.getRegistry().register(NOSTR_SCREEN_ITEM);
    }
}

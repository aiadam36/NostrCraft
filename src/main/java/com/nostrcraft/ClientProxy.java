package com.nostrcraft;

import com.nostrcraft.client.NostrCommand;
import com.nostrcraft.client.gui.GuiNostrFeed;
import com.nostrcraft.nostr.NostrClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import org.lwjgl.input.Keyboard;

public class ClientProxy extends CommonProxy {
    private static KeyBinding openKey;

    @Override
    public void preInit(FMLPreInitializationEvent e) {
        super.preInit(e);
        NostrClient.get().init(e.getModConfigurationDirectory());
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override
    public void init(FMLInitializationEvent e) {
        ClientCommandHandler.instance.registerCommand(new NostrCommand());
        openKey = new KeyBinding("key.nostrcraft.open", Keyboard.KEY_N, "key.categories.nostrcraft");
        ClientRegistry.registerKeyBinding(openKey);
    }

    @Override
    public void openFeedGui() {
        Minecraft.getMinecraft().displayGuiScreen(new GuiNostrFeed());
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        Minecraft mc = Minecraft.getMinecraft();
        if (e.phase == TickEvent.Phase.END && openKey.isPressed() && mc.currentScreen == null && mc.world != null) {
            openFeedGui();
        }
    }

    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent e) {
        NostrClient.get().stop(); // reconnects automatically next time a screen/GUI needs it
    }
}

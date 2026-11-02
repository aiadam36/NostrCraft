package com.nostrcraft;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

@Mod(modid = NostrCraft.MODID, name = NostrCraft.NAME, version = NostrCraft.VERSION, acceptedMinecraftVersions = "[1.12.2]")
public class NostrCraft {
    public static final String MODID = "nostrcraft";
    public static final String NAME = "NostrCraft";
    public static final String VERSION = "0.1.0";

    @SidedProxy(clientSide = "com.nostrcraft.ClientProxy", serverSide = "com.nostrcraft.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent e) { proxy.preInit(e); }

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) { proxy.init(e); }
}

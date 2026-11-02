package com.nostrcraft;

import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

/** Server-safe proxy. Anything that touches the Minecraft client lives in ClientProxy. */
public class CommonProxy {
    public void preInit(FMLPreInitializationEvent e) { }
    public void init(FMLInitializationEvent e) { }
    public void openFeedGui() { }
}

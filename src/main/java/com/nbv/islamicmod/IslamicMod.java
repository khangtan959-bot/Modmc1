package com.nbv.islamicmod;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.server.ServerStartingEvent;
import org.slf4j.Logger;

// ID của Mod, phải khớp với file mods.toml
@Mod(IslamicMod.MODID)
public class IslamicMod {
    public static final String MODID = "islamicmod";
    public static final Logger LOGGER = LogUtils.getLogger();

    public IslamicMod() {
        // Đăng ký Event Bus
        MinecraftForge.EVENT_BUS.register(this);
        // Đăng ký file xử lý sự kiện game (Luật chơi)
        MinecraftForge.EVENT_BUS.register(new ModEvents());
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("Islamic Mod đã khởi động - Bismillah!");
    }

    // Đăng ký lệnh /zakat
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        ZakatCommand.register(event.getDispatcher());
    }
}
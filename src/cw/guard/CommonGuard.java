package cw.guard;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * 两端都跑的事件桥：命令注册 + 「服务端维度 id 表」修复的调度。
 *
 * <p>注意 {@link ServerStartedEvent} 在**单人存档的集成服务端**上同样会触发，
 * 所以单人也会执行维度 id 补齐 —— 单人同样会撞 {@code Missing Dimension}：
 * 集成服务端一样是「IP 在 ServerAboutToStart 定稿、AE2 在 createLevels 末尾才建关卡」，
 * 一旦有包引用那个维度，本地连接会被直接断开。
 */
@Mod.EventBusSubscriber(modid = CwPortalGuard.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CommonGuard {

    private CommonGuard() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        // 客户端侧（含单人）由 cw.guard.client.ClientGuard 注册完整命令树（多两个调试子命令），
        // 这里只在专用服务端注册精简版，避免引用任何 net.minecraft.client.* 类。
        if (FMLEnvironment.dist != Dist.DEDICATED_SERVER) {
            return;
        }
        try {
            CwGuardCommands.registerServer(event);
        } catch (Throwable t) {
            CwPortalGuard.LOGGER.warn("[cw_portal_guard] failed to register /cwguard: {}", t.toString());
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        DimensionRecordGuard.arm();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        DimensionRecordGuard.tick();
    }
}

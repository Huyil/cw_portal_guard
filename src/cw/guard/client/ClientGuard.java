package cw.guard.client;

import cw.guard.CwGuardConfig;
import cw.guard.CwGuardCommands;
import cw.guard.mixin.LevelRendererAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.Map;

/**
 * 客户端修复层（只在 CLIENT 物理端加载，专用服务端不加载这个类）。
 *
 * <p>修 Immersive Portals 3.0.7 的「首次跨维度门户渲染」崩溃：
 * 客户端第一次“看见”一扇通往未加载维度的门时，IP 在渲染帧内临时创建该维度的
 * ClientLevel + LevelRenderer；其 {@code LevelRenderer.setLevel() -> allChanged()} 被 IP 的
 * {@code MixinLevelRenderer.onReloadStarted} 取消（"world renderer reloading cancelled during
 * portal rendering"），新渲染器的 {@code chunkRenderDispatcher} 永远是 null，随后
 * {@code onSetupTerrainBegin} 里 {@code chunkRenderDispatcher.setCamera(...)} 空指针崩溃。
 *
 * <p>本层在「渲染开始之前」把门的目的地维度提前建好：{@code RenderTickEvent.START}
 * 位于 runAllTasks（收包）之后、renderLevel 之前，此时 {@code WorldRenderInfo.isRendering()}
 * 仍为 false，所以 allChanged() 不会被 IP 取消；另外在 tick 里自愈半初始化的渲染器。
 */
@Mod.EventBusSubscriber(
    modid = cw.guard.CwPortalGuard.MODID,
    value = Dist.CLIENT,
    bus = Mod.EventBusSubscriber.Bus.FORGE
)
public final class ClientGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger("cw_portal_guard");

    private static int ticks;
    private static boolean announced;

    private ClientGuard() {
    }

    /** 单人/客户端侧注册完整命令树（多出 dims / repro 两个调试子命令）。 */
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        try {
            CwClientCommands.register(event);
        } catch (Throwable t) {
            LOGGER.warn("[cw_portal_guard] failed to register client /cwguard: {}", t.toString());
        }
    }

    /** 关键：每帧渲染前（收包之后、renderLevel 之前）做预热，此时不在门户渲染中。 */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        // 世界渲染之前先把 GL 状态复位（防 HUD 代码泄漏状态污染区块渲染）
        if (CwGuardConfig.glStateReset()) {
            GlStateSanitizer.beforeWorldRender();
        }
        safeWork("render");
    }

    /** 备份路径：客户端 tick 结束时也检查一次。 */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        CwRepro.tick();
        ticks++;
        if (ticks % 1200 == 0) {
            try {
                LOGGER.info(
                    "[cw_portal_guard] alive: worlds={} portals={}",
                    ClientWorldLoader.worldRendererMap.size(),
                    countPortals()
                );
            } catch (Throwable ignored) {
            }
        }
        safeWork("tick");
    }

    private static void safeWork(String where) {
        try {
            work(where);
        } catch (Throwable t) {
            // 任何异常都不能影响客户端
        }
    }

    private static void work(String where) {
        Minecraft mc = Minecraft.m_91087_();
        if (mc == null) {
            return;
        }
        ClientLevel level = mc.f_91073_;
        if (level == null || mc.f_91074_ == null || mc.f_91074_.f_108617_ == null) {
            return;
        }
        if (mc.f_91074_.m_9236_() != level) {
            return;
        }
        if (!announced) {
            announced = true;
            LOGGER.info("[cw_portal_guard] active (hooked at {})", where);
        }

        if (CwGuardConfig.tickPrewarm()) {
            prewarmPortalDestinations(level);
        }
        if (CwGuardConfig.rendererHeal()) {
            healBrokenRenderers();
        }
    }

    /** 把当前客户端已加载的门的「目的地维度」提前建出来（渲染开始前 / tick 内，非门户渲染帧）。 */
    private static void prewarmPortalDestinations(ClientLevel level) {
        for (Entity entity : level.m_104735_()) {
            if (!(entity instanceof Portal portal)) {
                continue;
            }
            ResourceKey<Level> dest = portal.getDestDim();
            if (dest != null && dest != level.m_46472_()) {
                prewarmDim(dest, "portal-tick");
            }
        }
    }

    /** 修复「有 level 但 chunkRenderDispatcher 为 null」的半初始化渲染器（IP 取消 allChanged 的后遗症）。 */
    private static void healBrokenRenderers() {
        for (Map.Entry<ResourceKey<Level>, LevelRenderer> entry : ClientWorldLoader.worldRendererMap.entrySet()) {
            LevelRenderer renderer = entry.getValue();
            if (renderer == null) {
                continue;
            }
            if (((LevelRendererAccessor) renderer).cw$dispatcher() != null) {
                continue;
            }
            try {
                renderer.m_109701_(ClientWorldLoader.getWorld(entry.getKey()));
                LOGGER.info("[cw_portal_guard] healed renderer {}", entry.getKey().m_135782_());
            } catch (Throwable t) {
                LOGGER.warn("[cw_portal_guard] heal failed {}: {}", entry.getKey().m_135782_(), t.toString());
            }
        }
    }

    /** 供 mixin 调用：把某个目的地维度提前建出来（非渲染帧内）。 */
    public static void prewarmDim(ResourceKey<Level> dest, String source) {
        if (dest == null) {
            return;
        }
        try {
            Minecraft mc = Minecraft.m_91087_();
            if (mc == null || mc.f_91073_ == null || mc.f_91074_ == null || mc.f_91074_.f_108617_ == null) {
                return;
            }
            if (dest == mc.f_91073_.m_46472_()) {
                return;
            }
            if (ClientWorldLoader.worldRendererMap.containsKey(dest)) {
                return;
            }
            if (!ClientWorldLoader.getServerDimensions().contains(dest)) {
                LOGGER.debug("[cw_portal_guard] skip unknown dim {}", dest.m_135782_());
                return;
            }
            ClientWorldLoader.getWorld(dest);
            LOGGER.info("[cw_portal_guard] prewarmed client dimension {} (via {})", dest.m_135782_(), source);
        } catch (Throwable t) {
            LOGGER.warn("[cw_portal_guard] prewarm failed {} ({}): {}", dest.m_135782_(), source, t.toString());
        }
    }

    private static int countPortals() {
        Minecraft mc = Minecraft.m_91087_();
        if (mc == null || mc.f_91073_ == null) {
            return 0;
        }
        int n = 0;
        for (Entity e : mc.f_91073_.m_104735_()) {
            if (e instanceof Portal) {
                n++;
            }
        }
        return n;
    }
}

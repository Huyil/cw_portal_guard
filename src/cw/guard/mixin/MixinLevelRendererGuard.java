package cw.guard.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

import java.util.HashSet;
import java.util.Set;

/**
 * 兜底：Immersive Portals 的 MixinLevelRenderer.onSetupTerrainBegin 在
 * "正在渲染门后内容 && 该维度 != 玩家维度" 时会无条件执行
 *   this.chunkRenderDispatcher.setCamera(camera.getPosition());
 * 而它的 onReloadStarted 会取消 allChanged()，导致新渲染器的 chunkRenderDispatcher 为 null。
 *
 * 这里在 setupRender HEAD（priority 高于 IP 的 1000，先执行）把整帧的地形构建跳过，
 * 而不是让 IP 的那句空指针把客户端带走；下一帧由 CwPortalGuard 的预热/自愈补 setLevel()。
 */
@Mixin(value = LevelRenderer.class, priority = 3000)
public abstract class MixinLevelRendererGuard {

    private static final Logger CW_LOGGER = LoggerFactory.getLogger("cw_portal_guard");
    private static final Set<String> CW_LOGGED = new HashSet<>();

    @Shadow
    private ChunkRenderDispatcher f_109436_;

    @Shadow
    private ClientLevel f_109465_;

    @Inject(method = "m_194338_", at = @At("HEAD"), cancellable = true)
    private void cw$skipTerrainSetupWhenDispatcherMissing(
        Camera camera, Frustum frustum, boolean hasForcedFrustum, boolean spectator, CallbackInfo ci
    ) {
        if (f_109436_ != null || !cw.guard.CwGuardConfig.renderGuard()) {
            return;
        }
        if (!WorldRenderInfo.isRendering()) {
            return;
        }
        ci.cancel();
        String key = f_109465_ == null ? "null" : String.valueOf(f_109465_.m_46472_());
        if (CW_LOGGED.size() < 32 && CW_LOGGED.add(key)) {
            CW_LOGGER.info("[cw_portal_guard] guard skipped terrain setup for uninitialized renderer (dim={})", key);
        }
    }
}

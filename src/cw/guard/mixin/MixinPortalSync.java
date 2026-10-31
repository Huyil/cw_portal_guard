package cw.guard.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.portal.Portal;

/**
 * 关键钩子：服务端把门的目的地改成新维度时，客户端通过 Portal 数据同步
 * (Portal.RemoteCallables.acceptPortalDataSync -> Portal.acceptDataSync) 更新 dimensionTo。
 * 在 acceptDataSync 返回处立刻把新目的地维度的 ClientLevel 建出来——此刻只是封包处理，
 * 不在渲染/门户渲染中，所以 IP 的 onReloadStarted 不会取消 allChanged()，渲染器完整初始化。
 */
@Mixin(Portal.class)
public abstract class MixinPortalSync {

    @Inject(method = "acceptDataSync", at = @At("RETURN"))
    private void cw$prewarmAfterDataSync(Vec3 pos, CompoundTag customData, CallbackInfo ci) {
        if (!cw.guard.CwGuardConfig.portalSyncPrewarm()) {
            return;
        }
        try {
            Portal self = (Portal) (Object) this;
            ResourceKey<Level> dest = self.getDestDim();
            if (dest != null) {
                cw.guard.client.ClientGuard.prewarmDim(dest, "portal-sync");
            }
        } catch (Throwable ignored) {
        }
    }
}

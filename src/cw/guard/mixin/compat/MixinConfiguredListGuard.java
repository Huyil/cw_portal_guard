package cw.guard.mixin.compat;

import com.mrcrayfish.configured.client.screen.ListMenuScreen;
import net.minecraft.client.gui.GuiGraphics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Last-resort guard for Configured's list screens.
 *
 * <p>{@code ListMenuScreen#init()} builds the entries (which may run arbitrary
 * config providers) BEFORE it creates the entry list and the search box. If any
 * provider throws, the screen stays half-initialized and the next
 * {@code tick()} / {@code render()} dereferences those null fields, turning a
 * caught error into a crash. Here the field reads are made null-safe, so a
 * broken provider costs an empty screen instead of the game.
 *
 * <p>The fields are resolved reflectively because their types
 * ({@code ListMenuScreen.FocusedEditBox} / {@code EntryList}) are protected
 * nested classes and cannot be referenced from another package. If resolution
 * ever fails (e.g. Configured renames them), the guard simply stays inactive.
 *
 * <p>This is not the actual JEI fix - that is {@link MixinConfiguredConfigMap},
 * which prevents the throwing entry from being listed at all.
 */
@Mixin(value = ListMenuScreen.class, remap = false)
public abstract class MixinConfiguredListGuard {

    private static final Logger CW_LOGGER = LoggerFactory.getLogger("cw_portal_guard");
    private static final AtomicBoolean CW_LOGGED = new AtomicBoolean();

    private static volatile boolean cwResolved;
    private static Field cwSearchField;
    private static Field cwListField;

    private static void cw$resolve() {
        if (cwResolved) {
            return;
        }
        synchronized (MixinConfiguredListGuard.class) {
            if (cwResolved) {
                return;
            }
            cwSearchField = cw$find("searchTextField");
            cwListField = cw$find("list");
            cwResolved = true;
        }
    }

    private static Field cw$find(String name) {
        try {
            Field field = ListMenuScreen.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** True only when we could resolve the field and its current value is null. */
    private boolean cw$isNull(Field field) {
        if (field == null) {
            return false;
        }
        try {
            return field.get(this) == null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void cw$warnOnce(String where) {
        if (CW_LOGGED.compareAndSet(false, true)) {
            CW_LOGGER.warn("[cw_portal_guard] Configured screen was left half-initialized; skipping {} to avoid a crash", where);
        }
    }

    @Inject(method = "m_86600_", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void cw$guardTick(CallbackInfo ci) {
        if (!cw.guard.CwGuardConfig.configuredListGuard()) {
            return;
        }
        cw$resolve();
        if (cw$isNull(cwSearchField)) {
            cw$warnOnce("tick");
            ci.cancel();
        }
    }

    @Inject(method = "m_88315_", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void cw$guardRender(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (!cw.guard.CwGuardConfig.configuredListGuard()) {
            return;
        }
        cw$resolve();
        if (cw$isNull(cwSearchField) || cw$isNull(cwListField)) {
            cw$warnOnce("render");
            ci.cancel();
        }
    }
}

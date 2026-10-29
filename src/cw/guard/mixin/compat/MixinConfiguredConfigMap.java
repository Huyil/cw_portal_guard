package cw.guard.mixin.compat;

import com.mrcrayfish.configured.api.ConfigType;
import com.mrcrayfish.configured.api.IModConfig;
import com.mrcrayfish.configured.api.ModContext;
import com.mrcrayfish.configured.client.ClientHandler;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Drop config entries that cannot produce a file name BEFORE Configured builds
 * its selection screen.
 *
 * <p>Background (Configured 2.2.3 + JEI 15.74): JEI replaced its config system
 * with mezz_config and the deprecated {@code IJeiConfigFile#getPath()} now
 * throws {@code IllegalStateException("Config schema is inactive: jei")} for
 * schemas that have no local file. The JEI server config ({@code jei-server.ini})
 * is such a schema on a client connected to a remote server: it is synced from
 * the server, so it has no path on this side.
 *
 * <p>Configured calls {@code getFileName()} while building its entry list, inside
 * {@code Screen#init()}. The exception aborts init half-way (the search box is
 * created after the entries), and the next tick NPEs on the null search box.
 * Filtering those entries out here (one choke point used by both the JEI gear
 * button and the mod list) keeps the screen usable and leaves real client
 * configs untouched.
 *
 * <p>Only applied when Configured and JEI are both installed, see
 * {@link cw.guard.compat.ConfiguredJeiMixinPlugin}.
 */
@Mixin(value = ClientHandler.class, remap = false)
public abstract class MixinConfiguredConfigMap {

    private static final Logger CW_LOGGER = LoggerFactory.getLogger("cw_portal_guard");
    private static final Set<String> CW_REPORTED = new HashSet<>();

    @Inject(method = "createConfigMap", at = @At("RETURN"), cancellable = true, remap = false)
    private static void cw$dropUnavailableConfigs(
        ModContext context,
        CallbackInfoReturnable<Map<ConfigType, Set<IModConfig>>> cir
    ) {
        if (!cw$compatActive()) {
            return;
        }
        Map<ConfigType, Set<IModConfig>> configs = cir.getReturnValue();
        if (configs == null || configs.isEmpty()) {
            return;
        }

        Map<ConfigType, Set<IModConfig>> kept = new HashMap<>();
        int dropped = 0;
        for (Map.Entry<ConfigType, Set<IModConfig>> entry : configs.entrySet()) {
            Set<IModConfig> usable = new LinkedHashSet<>();
            for (IModConfig config : entry.getValue()) {
                if (cw$isUsable(config)) {
                    usable.add(config);
                } else {
                    dropped++;
                    cw$report(config);
                }
            }
            if (!usable.isEmpty()) {
                kept.put(entry.getKey(), usable);
            }
        }

        if (dropped > 0) {
            cir.setReturnValue(kept);
        }
    }

    /** The fix only means anything when both mods are actually loaded. */
    private static boolean cw$compatActive() {
        try {
            ModList mods = ModList.get();
            return mods != null && mods.isLoaded("configured") && mods.isLoaded("jei");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Mirrors what {@code ModConfigSelectionScreen#createLabelFromModConfig} does:
     * a translation key short-circuits the file name lookup, so only probe when
     * there is no key (that is exactly the path that throws for JEI).
     */
    private static boolean cw$isUsable(IModConfig config) {
        try {
            if (config.getTranslationKey() != null) {
                return true;
            }
            config.getFileName();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void cw$report(IModConfig config) {
        try {
            String modId = String.valueOf(config.getModId());
            String type = String.valueOf(config.getType());
            String key = modId + "/" + type;
            if (CW_REPORTED.size() < 16 && CW_REPORTED.add(key)) {
                CW_LOGGER.info("[cw_portal_guard] hid unavailable config entry from {} ({})", modId, type);
            }
        } catch (Throwable ignored) {
        }
    }
}

package cw.guard.compat;

import net.minecraftforge.fml.ModList;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Gates the Configured + JEI compatibility mixins at runtime.
 *
 * <p>The JEI config-menu crash only exists when BOTH mods are installed:
 * Configured supplies the config screen and JEI's deprecated config API throws
 * for schemas without a local file. If either mod is missing, this plugin makes
 * Mixin skip the compat mixins entirely, so the game starts normally instead of
 * failing on missing targets or references.
 *
 * <p>Probing is retried until it succeeds once: {@code ModList} may not be ready
 * when Mixin first asks, so a negative result is never cached.
 */
public class ConfiguredJeiMixinPlugin implements IMixinConfigPlugin {

    private static final String[] REQUIRED_MODS = {"configured", "jei"};
    private static volatile boolean confirmed;
    private static volatile boolean announced;

    @Override
    public void onLoad(String mixinPackage) {
        // nothing to do
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (confirmed) {
            return true;
        }
        ModList mods = loadedMods();
        if (mods == null) {
            // ModList is not available yet. Do NOT cache a "no" here: target
            // selection happens early, so a premature false would disable the
            // fix for the whole session. Missing target classes are already
            // tolerated by "required": false.
            return true;
        }
        for (String modId : REQUIRED_MODS) {
            if (!mods.isLoaded(modId)) {
                return false;
            }
        }
        confirmed = true;
        if (!announced) {
            announced = true;
            log("[cw_portal_guard] Configured + JEI compat enabled (JEI config menu guard)");
        }
        return true;
    }

    private static ModList loadedMods() {
        try {
            return ModList.get();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void log(String message) {
        try {
            Logger logger = LoggerFactory.getLogger("cw_portal_guard");
            logger.info(message);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        // nothing to do
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        // nothing to do
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        // nothing to do
    }
}

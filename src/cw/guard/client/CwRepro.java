package cw.guard.client;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.ClientWorldLoader;

import java.lang.reflect.Method;

/**
 * Test helper (client only): after a portal has been retargeted and the client has
 * created the destination world (Immersive Portals does that at packet time, see
 * {@code Portal$RemoteCallables.acceptPortalDataSync}), drop that client world
 * again after a few ticks.
 *
 * <p>Why: the original crash only happens when the render pass is the first to
 * need the destination world - {@code ClientWorldLoader.getWorld()} then creates
 * it during portal rendering, {@code setLevel -> allChanged()} is cancelled by
 * IP's {@code onReloadStarted}, and the fresh renderer keeps a null
 * {@code chunkRenderDispatcher}. By disposing the world right before a frame we
 * force exactly that path, so the guard layers can be tested deterministically
 * without a second player or the Cube Warper machine.
 *
 * <p>Debug only - never touches a world the player is currently in.
 */
public final class CwRepro {

    private static final Logger LOGGER = LoggerFactory.getLogger("cw_portal_guard");

    private static ResourceKey<Level> pending;
    private static int remainingTicks;
    private static Method disposeMethod;
    private static boolean lookedUp;

    private CwRepro() {
    }

    public static synchronized void scheduleDispose(ResourceKey<Level> dimension, int delayTicks) {
        pending = dimension;
        remainingTicks = Math.max(1, delayTicks);
    }

    /** Called from the client tick (render thread). */
    public static synchronized void tick() {
        ResourceKey<Level> dimension = pending;
        if (dimension == null) {
            return;
        }
        if (--remainingTicks > 0) {
            return;
        }
        pending = null;
        dispose(dimension);
    }

    private static void dispose(ResourceKey<Level> dimension) {
        try {
            Method method = method();
            if (method == null) {
                LOGGER.warn("[cwguard] ClientWorldLoader.disposeDimensionDynamically not found - repro cannot force the render path");
                return;
            }
            method.invoke(null, dimension);
            LOGGER.info("[cwguard] disposed client world {} - the next portal render must create it", dimension.m_135782_());
        } catch (Throwable t) {
            LOGGER.warn("[cwguard] dispose failed for {}: {}", dimension.m_135782_(), t.toString());
        }
    }

    private static Method method() {
        if (lookedUp) {
            return disposeMethod;
        }
        lookedUp = true;
        try {
            Method method = ClientWorldLoader.class.getDeclaredMethod("disposeDimensionDynamically", ResourceKey.class);
            method.setAccessible(true);
            disposeMethod = method;
        } catch (Throwable t) {
            LOGGER.warn("[cwguard] cannot resolve disposeDimensionDynamically: {}", t.toString());
        }
        return disposeMethod;
    }
}

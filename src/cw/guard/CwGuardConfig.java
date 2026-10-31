package cw.guard;

import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Switchboard for every guard layer (client render layers, Configured/JEI fix, and
 * the server-side dimension-id-table repair).
 *
 * <p>File: {@code config/cw_portal_guard.properties}. Missing file is created with
 * all switches enabled. Values are picked up live (the file's mtime is polled at
 * most once per second), so a layer can be disabled without restarting the client.
 * The dimension-id switch is read when a server starts, so it applies to the next
 * world/server start.
 *
 * <p>Purpose: find out which layer actually prevents a given crash. Run with only
 * one switch enabled, reproduce, and check the log for that layer's message.
 */
public final class CwGuardConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("cw_portal_guard");
    private static final String FILE_NAME = "cw_portal_guard.properties";

    private static final String DEFAULTS = """
        # cw_portal_guard switches (true = enabled, false = disabled)
        # Saved changes apply on the next event (no restart required for the render layers;
        # dimension_id_guard is read when a server/world starts).
        # To find out which layer matters: keep only one "true", set the rest to
        # "false", restart the game and reproduce the crash.
        #
        # === client side ===
        # portal_sync_prewarm : create the portal destination dimension when the
        #                       destination is synced (packet handling, not rendering)
        # tick_prewarm        : per-frame prewarm of every loaded portal's destination
        # renderer_heal       : re-init renderers that have a level but no
        #                       chunkRenderDispatcher
        # render_guard        : last resort - skip one frame of terrain setup instead
        #                       of letting the NPE crash the client
        # configured_jei_filter : hide Configured entries whose file name lookup throws
        #                       (JEI synced server config without a local file)
        # configured_list_guard : null-safe tick/render for half-initialized Configured
        #                       list screens
        # === both sides (dedicated server / single-player integrated server) ===
        # dimension_id_guard  : re-run Immersive Portals' dimension-id record completion
        #                       at ServerStartedEvent so dimensions created during
        #                       startup (e.g. ae2:spatial_storage) get an id and stop
        #                       kicking players with "Missing Dimension <id>"
        portal_sync_prewarm=true
        tick_prewarm=true
        renderer_heal=true
        render_guard=true
        configured_jei_filter=true
        configured_list_guard=true
        dimension_id_guard=true
        # gl_state_reset      : force a known-good GL state before every world render
        #                       (neutralises HUD code that leaks colorMask/depthMask/stencil)
        gl_state_reset=true
        """;

    private static volatile Snapshot snapshot;

    private CwGuardConfig() {
    }

    public static boolean portalSyncPrewarm() {
        return snapshot().portalSyncPrewarm;
    }

    public static boolean tickPrewarm() {
        return snapshot().tickPrewarm;
    }

    public static boolean rendererHeal() {
        return snapshot().rendererHeal;
    }

    public static boolean renderGuard() {
        return snapshot().renderGuard;
    }

    public static boolean configuredJeiFilter() {
        return snapshot().configuredJeiFilter;
    }

    public static boolean configuredListGuard() {
        return snapshot().configuredListGuard;
    }

    public static boolean dimensionIdGuard() {
        return snapshot().dimensionIdGuard;
    }

    public static boolean glStateReset() {
        return snapshot().glStateReset;
    }

    public static void logState() {
        Snapshot s = snapshot();
        LOGGER.info(
            "[cw_portal_guard] toggles ({}): syncPreload={} tickPreload={} heal={} renderGuard={} configuredJeiFilter={} configuredListGuard={} dimensionIdGuard={} glStateReset={}",
            s.path, s.portalSyncPrewarm, s.tickPrewarm, s.rendererHeal, s.renderGuard,
            s.configuredJeiFilter, s.configuredListGuard, s.dimensionIdGuard, s.glStateReset
        );
    }

    private static Snapshot snapshot() {
        Snapshot current = snapshot;
        long now = System.currentTimeMillis();
        if (current != null && now < current.validUntil) {
            return current;
        }
        try {
            Path path = path();
            if (path == null) {
                return current != null ? current : Snapshot.DEFAULT;
            }
            long mtime = Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : 0L;
            if (current != null && current.path.equals(path) && current.mtime == mtime) {
                Snapshot refreshed = current.validUntil(now + 1000L);
                snapshot = refreshed;
                return refreshed;
            }
            Snapshot loaded = load(path, mtime, now);
            snapshot = loaded;
            return loaded;
        } catch (Throwable t) {
            return current != null ? current : Snapshot.DEFAULT;
        }
    }

    private static Path path() {
        try {
            return FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Snapshot load(Path path, long mtime, long now) throws IOException {
        if (!Files.exists(path)) {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, DEFAULTS, StandardCharsets.UTF_8);
            mtime = Files.getLastModifiedTime(path).toMillis();
        }
        Map<String, String> values = new HashMap<>();
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        for (String line : lines) {
            String text = line.trim();
            if (text.isEmpty() || text.startsWith("#") || text.startsWith("!")) {
                continue;
            }
            int split = text.indexOf('=');
            if (split <= 0) {
                continue;
            }
            values.put(text.substring(0, split).trim().toLowerCase(Locale.ROOT), text.substring(split + 1).trim());
        }
        return new Snapshot(
            path,
            mtime,
            now + 1000L,
            flag(values, "portal_sync_prewarm"),
            flag(values, "tick_prewarm"),
            flag(values, "renderer_heal"),
            flag(values, "render_guard"),
            flag(values, "configured_jei_filter"),
            flag(values, "configured_list_guard"),
            flag(values, "dimension_id_guard"),
            flag(values, "gl_state_reset")
        );
    }

    private static boolean flag(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isEmpty()) {
            return true;
        }
        return Boolean.parseBoolean(value);
    }

    private record Snapshot(
        Path path,
        long mtime,
        long validUntil,
        boolean portalSyncPrewarm,
        boolean tickPrewarm,
        boolean rendererHeal,
        boolean renderGuard,
        boolean configuredJeiFilter,
        boolean configuredListGuard,
        boolean dimensionIdGuard,
        boolean glStateReset
    ) {
        static final Snapshot DEFAULT = new Snapshot(
            Path.of(""), 0L, 0L, true, true, true, true, true, true, true, true
        );

        Snapshot validUntil(long next) {
            return new Snapshot(
                path, mtime, next, portalSyncPrewarm, tickPrewarm, rendererHeal,
                renderGuard, configuredJeiFilter, configuredListGuard, dimensionIdGuard, glStateReset
            );
        }
    }
}

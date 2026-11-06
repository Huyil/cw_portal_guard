package cw.guard;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 本地化文本入口。
 *
 * <p>文本来自 {@code assets/cw_portal_guard/lang/<lang>.json}。聊天栏走 Minecraft 的
 * {@code Component.translatable}，由客户端语言设置决定；日志直接读取同一份 lang 文件并在
 * {@link #LANG_FALLBACK} 中查找——专用服务端没有客户端语言表，{@code translatable} 在服务端
 * 只能返回 key 本身，因此日志需要独立解析。
 *
 * <p>语言选择：{@code log_language} 配置项；留空时依次尝试 {@code zh_cn}、{@code en_us}。
 */
public final class Messages {

    private static final Logger LOGGER = CwPortalGuard.LOGGER;
    private static final String PREFIX = "cw_portal_guard.";

    private static final Map<String, Map<String, String>> CACHE = new HashMap<>();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {
    }.getType();
    private static final Gson GSON = new Gson();

    private Messages() {
    }

    /** 聊天栏 / 命令回显：交给客户端语言表解析。 */
    public static MutableComponent component(String key, Object... args) {
        try {
            return Component.m_237110_(PREFIX + key, args);
        } catch (Throwable t) {
            return Component.m_237113_(resolve(key, args));
        }
    }

    /** 纯文本：按日志语言解析。 */
    public static String text(String key, Object... args) {
        return resolve(key, args);
    }

    public static void info(String key, Object... args) {
        log(key, Level.INFO, args);
    }

    public static void warn(String key, Object... args) {
        log(key, Level.WARN, args);
    }

    public static void error(String key, Object... args) {
        log(key, Level.ERROR, args);
    }

    public static void debug(String key, Object... args) {
        log(key, Level.DEBUG, args);
    }

    private static void log(String key, Level level, Object[] args) {
        if (!CwGuardConfig.logMessages()) {
            return;
        }
        try {
            String msg = "[cw_portal_guard] " + resolve(key, args);
            switch (level) {
                case WARN -> LOGGER.warn(msg);
                case ERROR -> LOGGER.error(msg);
                case DEBUG -> LOGGER.debug(msg);
                default -> LOGGER.info(msg);
            }
        } catch (Throwable t) {
            LOGGER.warn("[cw_portal_guard] {} (unresolved message)", key);
        }
    }

    /** 从 lang 文件找到模板并格式化；缺失时回落到 key。 */
    private static String resolve(String key, Object... args) {
        String template = lookup(PREFIX + key);
        if (template == null) {
            return PREFIX + key;
        }
        if (args == null || args.length == 0) {
            return template;
        }
        try {
            return String.format(Locale.ROOT, template, args);
        } catch (Throwable t) {
            return template;
        }
    }

    private static String lookup(String fullKey) {
        for (String lang : languages()) {
            String v = table(lang).get(fullKey);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    /**
     * 语言优先级：配置项 {@code log_language} → 进程语言环境 → {@code en_us}。
     *
     * <p>专用服务端通常运行在英文环境，此时日志回落到 {@code en_us}；
     * 需要中文日志的服主在配置里填 {@code log_language=zh_cn} 即可。
     */
    private static String[] languages() {
        String configured = CwGuardConfig.logLanguage();
        if (configured != null && !configured.isBlank()) {
            return new String[]{configured.trim().toLowerCase(Locale.ROOT)};
        }
        String env = Locale.getDefault().getLanguage().toLowerCase(Locale.ROOT);
        if (env.startsWith("zh")) {
            return new String[]{"zh_cn", "en_us"};
        }
        return new String[]{"en_us", "zh_cn"};
    }

    private static Map<String, String> table(String lang) {
        Map<String, String> cached = CACHE.get(lang);
        if (cached != null) {
            return cached;
        }
        Map<String, String> loaded = load(lang);
        CACHE.put(lang, loaded);
        return loaded;
    }

    private static Map<String, String> load(String lang) {
        String path = "/assets/cw_portal_guard/lang/" + lang + ".json";
        try (InputStream in = Messages.class.getResourceAsStream(path)) {
            if (in == null) {
                return Map.of();
            }
            Map<String, String> parsed = GSON.fromJson(
                new InputStreamReader(in, StandardCharsets.UTF_8), MAP_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (Throwable t) {
            return Map.of();
        }
    }

    private enum Level {
        INFO, WARN, ERROR, DEBUG
    }
}

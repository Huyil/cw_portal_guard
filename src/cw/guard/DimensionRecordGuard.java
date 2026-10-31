package cw.guard;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 服务端修复层：补齐 Immersive Portals 的维度 id 表（{@code imm_ptl_dim_reg.dat}）。
 *
 * <h2>为什么需要它</h2>
 * 用 {@code javap -p -c} 核对 IP 3.0.7 字节码后的确定性结论：
 * <ol>
 *   <li>{@code DimensionIdManagement.onServerStarted()} 在 <b>ServerAboutToStart</b> 阶段执行：
 *       读 {@code world/data/imm_ptl_dim_reg.dat} → {@code completeServerIdRecord()} → 回写；</li>
 *   <li>{@code completeServerIdRecord()} 只遍历 {@code server.getAllLevels()}，
 *       而此刻 {@code MinecraftServer} 还没进 {@code createLevels}；</li>
 *   <li>AE2 的 {@code ae2:spatial_storage} 由
 *       {@code appeng.mixins.spatial.MinecraftServerMixin#injectSpatialLevel}
 *       在 {@code createLevels} 末尾才 {@code put} 进 levelMap。</li>
 * </ol>
 * ⇒ id 表永远缺这个维度，任何引用它的包（{@code ClientboundPlayerPositionPacket}、
 * {@code imm_ptl:rd}）在 {@code DimId.writeWorldId → DimensionIdRecord.getIntId}
 * 处抛 {@code RuntimeException: Missing Dimension <id>}，玩家被踢下线。
 * <b>重启不会自愈</b>，因为每次启动都是同一个时序。
 *
 * <h2>怎么修</h2>
 * IP 自己提供了正好需要的方法（3.0.7 实测存在，public static）：
 * <pre>
 * DimensionIdManagement.updateAndSaveServerDimIdRecord();
 *   = completeServerIdRecord()                 // 按当前 getAllLevels() 补 id（max+1）
 *   + recordToTag(serverRecord, dim -> true)   // predicate 恒 true，不丢条目
 *   + NbtIo.writeCompressed(...)               // 立刻落盘
 * </pre>
 * 在 {@code ServerStartedEvent}（所有关卡都已创建、玩家还没进来）调一次即可，
 * id 由 IP 自己分配，服务端与客户端天然一致（客户端登录时从服务端拿这张表）。
 *
 * <p>为兼容「关卡懒创建」的模组，之后每 {@value #RETRY_TICKS} tick 复查一次，
 * 直到表大小连续 {@value #STABLE_ATTEMPTS} 次不变。全程反射调用：
 * 没装 IP / IP 改了方法名时只打日志，不影响启动。
 */
public final class DimensionRecordGuard {

    /** IP 内部类名（反射，避免硬依赖）。 */
    private static final String MGMT = "qouteall.q_misc_util.dimension.DimensionIdManagement";
    private static final String RECORD = "qouteall.q_misc_util.dimension.DimensionIdRecord";

    private static final int RETRY_TICKS = 20;
    private static final int STABLE_ATTEMPTS = 3;
    private static final int MAX_ATTEMPTS = 60;

    private static int tickCounter;
    private static int attempts;
    private static int stable;
    private static boolean finished;
    private static boolean disabledLogged;
    private static String lastResult = "尚未运行";

    private DimensionRecordGuard() {
    }

    /** {@code ServerStartedEvent}：武装（重置状态并立刻跑一次）。 */
    public static void arm() {
        tickCounter = 0;
        attempts = 0;
        stable = 0;
        finished = false;
        disabledLogged = false;
        lastResult = "尚未运行";
        if (!CwGuardConfig.dimensionIdGuard()) {
            finished = true;
            lastResult = "已通过 dimension_id_guard=false 关闭";
            if (!disabledLogged) {
                disabledLogged = true;
                CwPortalGuard.LOGGER.info("[cw_portal_guard] dimension id guard disabled by config");
            }
            return;
        }
        attempt("ServerStartedEvent");
    }

    /** {@code ServerTickEvent.END}：定期复查，直到表稳定。 */
    public static void tick() {
        if (finished) {
            return;
        }
        if (++tickCounter % RETRY_TICKS != 0) {
            return;
        }
        attempt("tick+" + tickCounter);
    }

    private static void attempt(String when) {
        if (finished) {
            return;
        }
        attempts++;
        try {
            Class<?> mgmt = Class.forName(MGMT);
            Method save = mgmt.getDeclaredMethod("updateAndSaveServerDimIdRecord");
            save.setAccessible(true);

            Map<String, Integer> before = snapshot();
            save.invoke(null);
            Map<String, Integer> now = snapshot();

            if (now == null) {
                CwPortalGuard.LOGGER.warn("[cw_portal_guard] {}: IP 维度表不可读（serverRecord=null），稍后重试", when);
                return;
            }
            if (before == null) {
                CwPortalGuard.LOGGER.info("[cw_portal_guard] {}: IP 维度表此前不存在，已初始化 {} 条", when, now.size());
                stable = 0;
            } else {
                int added = logDiff(before, now);
                if (added > 0) {
                    stable = 0;
                    CwPortalGuard.LOGGER.info(
                        "[cw_portal_guard] {}: 维度表 {} -> {} 条（新增 {}）", when, before.size(), now.size(), added);
                } else {
                    stable++;
                    CwPortalGuard.LOGGER.info(
                        "[cw_portal_guard] {}: 维度表 {} 条，无变化（第 {} 次稳定）", when, now.size(), stable);
                }
            }

            if (stable >= STABLE_ATTEMPTS) {
                finished = true;
                lastResult = now.size() + " 条（" + attempts + " 次尝试），" + spatialInfo(now);
                CwPortalGuard.LOGGER.info("[cw_portal_guard] 维度 id 表修复完成：{}", lastResult);
            } else if (attempts >= MAX_ATTEMPTS) {
                finished = true;
                lastResult = "达到最大尝试次数 " + MAX_ATTEMPTS + "，表停在 " + now.size() + " 条";
                CwPortalGuard.LOGGER.warn("[cw_portal_guard] {}", lastResult);
            }
        } catch (ClassNotFoundException e) {
            finished = true;
            lastResult = "未安装 immersive_portals，无需处理";
            CwPortalGuard.LOGGER.info("[cw_portal_guard] {}", lastResult);
        } catch (Throwable t) {
            CwPortalGuard.LOGGER.error("[cw_portal_guard] 维度 id 表第 {} 次尝试失败（{}）", attempts, when, t);
            if (attempts >= MAX_ATTEMPTS) {
                finished = true;
                lastResult = "尝试失败：" + t;
            }
        }
    }

    /** 反射读 {@code DimensionIdRecord.serverRecord.idMap} 的快照（key 保留 ResourceKey.toString()，便于打印）。 */
    private static Map<String, Integer> snapshot() throws Exception {
        Class<?> recordClass = Class.forName(RECORD);
        Field serverRecordField = recordClass.getField("serverRecord");
        Object record = serverRecordField.get(null);
        if (record == null) {
            return null;
        }
        Field idMapField = recordClass.getDeclaredField("idMap");
        idMapField.setAccessible(true);
        Object map = idMapField.get(record);
        if (!(map instanceof Map)) {
            return null;
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) map).entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Number) {
                out.put(String.valueOf(entry.getKey()), ((Number) value).intValue());
            }
        }
        return out;
    }

    private static int logDiff(Map<String, Integer> before, Map<String, Integer> after) {
        int added = 0;
        for (Map.Entry<String, Integer> entry : after.entrySet()) {
            if (!before.containsKey(entry.getKey())) {
                added++;
                CwPortalGuard.LOGGER.info(
                    "[cw_portal_guard] + 补上维度 id: {} -> {}", pretty(entry.getKey()), entry.getValue());
            }
        }
        return added;
    }

    private static String spatialInfo(Map<String, Integer> table) {
        for (Map.Entry<String, Integer> entry : table.entrySet()) {
            if (entry.getKey().contains("spatial_storage")) {
                return "ae2:spatial_storage -> " + entry.getValue() + "（已收录）";
            }
        }
        return "未包含 ae2:spatial_storage（该维度本局可能未创建，属正常）";
    }

    /** ResourceKey.toString() 形如 "ResourceKey[minecraft:dimension / ae2:spatial_storage]"。 */
    static String pretty(String key) {
        int slash = key.indexOf("/ ");
        if (slash > 0) {
            String tail = key.substring(slash + 2).trim();
            if (tail.endsWith("]")) {
                tail = tail.substring(0, tail.length() - 1);
            }
            return tail;
        }
        return key;
    }

    /** 给 {@code /cwguard idtable} 用：当前服务端所有维度里，还没进 id 表的（0 = 健康）。 */
    public static List<String> missingDimensions() {
        List<String> missing = new ArrayList<>();
        try {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) {
                return missing;
            }
            Set<ResourceKey<Level>> levels = server.m_129784_();
            Class<?> recordClass = Class.forName(RECORD);
            Object record = recordClass.getField("serverRecord").get(null);
            if (record == null) {
                missing.add("<serverRecord 为 null>");
                return missing;
            }
            Method getIntId = recordClass.getMethod("getIntId", ResourceKey.class);
            for (ResourceKey<Level> key : levels) {
                try {
                    getIntId.invoke(record, key);
                } catch (InvocationTargetException e) {
                    missing.add(key.m_135782_().toString());
                }
            }
        } catch (Throwable t) {
            missing.add("<检查失败: " + t + ">");
        }
        return missing;
    }

    /** 给命令用的可读状态。 */
    public static String lastResult() {
        return lastResult + (finished ? "" : "（仍在复查）");
    }
}

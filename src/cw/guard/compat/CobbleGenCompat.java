package cw.guard.compat;

import com.simibubi.create.content.kinetics.drill.CobbleGenLevel;
import io.github.null2264.cobblegen.CobbleGen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.fluids.FluidInteractionRegistry;
import net.minecraftforge.fluids.FluidInteractionRegistry.HasFluidInteraction;
import net.minecraftforge.fluids.FluidInteractionRegistry.InteractionInformation;
import net.minecraftforge.fluids.FluidType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CobbleGen ↔ Create 兼容层（服务端 + 单人集成服务端）。
 *
 * <h2>问题</h2>
 * Create 的钻头刷石机优化 {@code CobbleGenOptimisation#determineOutput} 用 Forge 的
 * {@code FluidInteractionRegistry} 模拟"这里会长出什么"，熔岩+水永远算出 {@code minecraft:cobblestone}。
 * 而 CobbleGen（null2264）按维度把<b>真实</b>产物换成砂岩 / 焦炉砖 / 黑石 …（它 mixin 的是
 * 原版流体事件，不经过 Forge 注册表）。于是 {@code DrillBlockEntity#optimiseCobbleGen} 的
 * {@code currentOutput.equals(stateToBreak)} 不成立 → 静默退化（掉落物变物品实体、不进漏斗）。
 *
 * <h2>做法（不改 Create、不改 CobbleGen、无 mixin）</h2>
 * 往 Forge 注册表<b>熔岩交互列表的最前面</b>插一条我们自己的交互：
 * <ul>
 *   <li><b>predicate</b>：把 CobbleGen 的 {@code FLUID_INTERACTION.interact(...)} 跑在 Create 的
 *       {@code CobbleGenLevel} 仿真层上（{@code setBlock} 被拦成记录、不写世界），
 *       若 CobbleGen 在此处确实会放置方块，则命中，产物通过 ThreadLocal 交给 interaction；</li>
 *   <li><b>interaction</b>：把产物 {@code setBlockAndUpdate} 到 Create 传进来的仿真层/真实世界上。</li>
 * </ul>
 * Create 的模拟循环是"取第一个 predicate 命中的条目"，我们的条目排最前：
 * <ul>
 *   <li>CobbleGen 覆盖了该维度 → 模拟拿到真实产物（与被钻方块一致 → 优化恢复）；</li>
 *   <li>CobbleGen 没覆盖（predicate 不命中）→ 完全落回 Forge 默认 → 行为与原来一致；</li>
 *   <li>真实世界里熔岩流动也会先问我们的条目：命中的话放置的正是 CobbleGen 本来要放的方块
 *       （用的是它自己的判定代码），结果一致；不命中则走原版逻辑。</li>
 * </ul>
 *
 * <p>本类<b>只在 CobbleGen 存在时</b>才会被加载（调用方 {@code CommonGuard} 先查 ModList），
 * 因此对 CobbleGen 类的直接引用不会在缺 mod 时触发 NoClassDefFoundError。
 *
 * <p>开关：{@code cobblegen_compat}（默认开）。所有路径 try/catch，异常一律退回默认行为。
 */
public final class CobbleGenCompat {

    private static final Logger LOG = LoggerFactory.getLogger("cw_portal_guard");

    /** predicate 算出的产物，交给紧随其后执行的 interaction（同一服务器线程）。 */
    private static final ThreadLocal<BlockState> PENDING = new ThreadLocal<>();

    /** 每个 "维度|原方块 -> 新方块" 只打一次日志。 */
    private static final Map<String, Boolean> LOGGED = new ConcurrentHashMap<>();

    private static volatile boolean installed;

    /** 我们自己插入的那条交互；卸载时按引用移除，绝不误删别人的条目。 */
    private static volatile InteractionInformation ownEntry;

    private CobbleGenCompat() {
    }

    /** 幂等安装：把我们的交互插到熔岩交互列表头部。 */
    public static void install() {
        try {
            if (installed && isStillInstalled()) {
                return;
            }
            InteractionInformation entry = new InteractionInformation(CobbleGenCompat::predict, CobbleGenCompat::place);

            Field field = FluidInteractionRegistry.class.getDeclaredField("INTERACTIONS");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<FluidType, List<InteractionInformation>> interactions =
                (Map<FluidType, List<InteractionInformation>>) field.get(null);
            if (interactions == null) {
                LOG.warn("[cw_portal_guard] cobblegen compat: FluidInteractionRegistry.INTERACTIONS is null, skip");
                return;
            }

            FluidType lavaType = ForgeMod.LAVA_TYPE.get();
            List<InteractionInformation> list = interactions.get(lavaType);
            if (list == null) {
                List<InteractionInformation> fresh = new ArrayList<>();
                fresh.add(entry);
                interactions.put(lavaType, fresh);
            } else if (list instanceof ArrayList) {
                list.add(0, entry);
            } else {
                List<InteractionInformation> copy = new ArrayList<>(list);
                copy.add(0, entry);
                interactions.put(lavaType, copy);
            }
            ownEntry = entry;
            installed = true;
            LOG.info("[cw_portal_guard] cobblegen compat installed: own interaction inserted at lava list head");
        } catch (Throwable t) {
            LOG.warn("[cw_portal_guard] cobblegen compat install failed: {}", t.toString());
        }
    }

    /** 把我们的条目从列表里摘掉（按引用），返回是否真的摘掉了。 */
    public static boolean uninstall() {
        try {
            if (!isStillInstalled()) {
                installed = false;
                ownEntry = null;
                return false;
            }
            List<InteractionInformation> list = lavaInteractions();
            if (list == null) {
                return false;
            }
            boolean removed = list.remove(ownEntry);
            installed = false;
            ownEntry = null;
            if (removed) {
                LOG.info("[cw_portal_guard] cobblegen compat uninstalled");
            }
            return removed;
        } catch (Throwable t) {
            LOG.warn("[cw_portal_guard] cobblegen compat uninstall failed: {}", t.toString());
            return false;
        }
    }

    /** 运行时检查：我们那条是否还在注册表里（可能被别的 mod 重建过列表）。 */
    private static boolean isStillInstalled() {
        try {
            InteractionInformation entry = ownEntry;
            if (entry == null) {
                return false;
            }
            List<InteractionInformation> list = lavaInteractions();
            return list != null && list.contains(entry);
        } catch (Throwable t) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<InteractionInformation> lavaInteractions() throws Exception {
        Field field = FluidInteractionRegistry.class.getDeclaredField("INTERACTIONS");
        field.setAccessible(true);
        Map<FluidType, List<InteractionInformation>> interactions =
            (Map<FluidType, List<InteractionInformation>>) field.get(null);
        if (interactions == null) {
            return null;
        }
        return interactions.get(ForgeMod.LAVA_TYPE.get());
    }

    // ---------------------------------------------------------------- predicate

    /**
     * Create 的模拟循环（以及原版熔岩流动）会以每个水平方向调用本方法。
     * 命中条件：CobbleGen 在当前维度/群系确实会把这里生成为<b>别的</b>方块。
     */
    private static boolean predict(LevelAccessor level, BlockPos currentPos, BlockPos relativePos, FluidState fluidState) {
        try {
            PENDING.set(null);
            if (!(level instanceof ServerLevel serverLevel)) {
                return false;
            }
            BlockState result = query(serverLevel, currentPos, fluidState);
            if (result == null) {
                return false;
            }
            PENDING.set(result);
            return true;
        } catch (Throwable t) {
            PENDING.set(null);
            LOG.debug("[cw_portal_guard] cobblegen compat predict failed: {}", t.toString());
            return false;
        }
    }

    /** interaction：把 CobbleGen 的产物放到目标位置（真实世界或 Create 的仿真层）。 */
    private static void place(LevelAccessor level, BlockPos currentPos, BlockPos relativePos, FluidState fluidState) {
        BlockState pending = PENDING.get();
        PENDING.set(null);
        if (pending != null) {
            level.m_7731_(currentPos, pending, 3);   // setBlock(pos, state, UPDATE_ALL)
        }
    }

    // ---------------------------------------------------------------- query

    /**
     * 问 CobbleGen：流动进来的流体（{@code fluidState}）在这个位置会生成什么？
     *
     * <p>实现就是把 {@code CobbleGen.FLUID_INTERACTION.interact(...)} 跑在 Create 的
     * {@link CobbleGenLevel} 仿真层上（对 Create 而言零成本复用；对原版而言是只读预测），
     * {@code setBlock} 被 {@code blocksAdded} 记录，真实世界不受影响。
     *
     * @return CobbleGen 会放置的方块；未覆盖 / 任何异常返回 {@code null}
     */
    private static BlockState query(ServerLevel level, BlockPos pos, FluidState fluidState) {
        CobbleGenLevel simulation = new CobbleGenLevel(level);
        try {
            BlockState incoming = fluidState.m_76188_();   // createLegacyBlock：流动熔岩 -> 熔岩方块
            if (CobbleGen.FLUID_INTERACTION == null) {
                return null;
            }
            boolean handled = CobbleGen.FLUID_INTERACTION.interact(simulation, pos, incoming);
            if (!handled) {
                return null;
            }
            BlockState result = simulation.blocksAdded.get(pos);   // CobbleGenLevel.setBlock 记录时已 immutable
            if (result == null || result.m_60795_()) {
                return null;
            }
            logOnce(level, result);
            return result;
        } catch (Throwable t) {
            LOG.debug("[cw_portal_guard] cobblegen compat query failed: {}", t.toString());
            return null;
        } finally {
            simulation.clear();
        }
    }

    private static void logOnce(ServerLevel level, BlockState replaced) {
        try {
            String dim = String.valueOf(level.m_46472_().m_135782_());
            String block = String.valueOf(
                net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(replaced.m_60734_()));
            String key = dim + "|" + block;
            if (LOGGED.putIfAbsent(key, Boolean.TRUE) == null) {
                LOG.info("[cw_portal_guard] cobblegen compat active: {} -> {}", dim, block);
            }
        } catch (Throwable ignored) {
            // 日志失败不影响功能
        }
    }
}

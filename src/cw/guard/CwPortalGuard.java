package cw.guard;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * cw_portal_guard —— CubeWarper 的 Immersive Portals 补丁（**双端同一个 jar**）。
 *
 * <p>两端修的是两件不同的事，按物理端自动分流：
 *
 * <table border="1">
 *   <tr><th>侧</th><th>修复</th><th>实现类</th></tr>
 *   <tr><td>服务端（含单人存档的集成服务端）</td>
 *       <td>IP 维度 id 表缺条目导致的 {@code Missing Dimension <id>} 踢人/断连
 *           （典型：{@code ae2:spatial_storage}）</td>
 *       <td>{@link DimensionRecordGuard}（纯反射，无 mixin）</td></tr>
 *   <tr><td>客户端</td>
 *       <td>首次看穿通往未加载维度的门户时 {@code ChunkRenderDispatcher == null} 崩溃；
 *           Configured + JEI 配置菜单崩溃</td>
 *       <td>{@code cw.guard.client.ClientGuard} + {@code cw.guard.mixin.*}（仅 client 段）</td></tr>
 * </table>
 *
 * <p>分流靠三件事保证，服务端永远不会去加载 {@code net.minecraft.client.*}
 * （专用服务端 jar 里根本没有这些类）：
 * <ol>
 *   <li>客户端事件处理类用 {@code @EventBusSubscriber(value = Dist.CLIENT)}，
 *       Forge 在专用服务端直接跳过、不加载；</li>
 *   <li>mixin 配置把渲染相关 mixin 全放在 {@code "client"} 段；</li>
 *   <li>命令树里客户端专属子命令（dims / repro）由客户端类单独构建，
 *       服务端只注册 status / idtable。</li>
 * </ol>
 *
 * <p>mods.toml 里已去掉 {@code clientSideOnly}，两端都装同一个 jar 即可；
 * 客户端装是为了单人和联机渲染修复，服务端装是为了维度 id 表修复。
 */
@Mod(CwPortalGuard.MODID)
public class CwPortalGuard {

    public static final String MODID = "cw_portal_guard";
    public static final String VERSION = "1.3.6";

    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    public CwPortalGuard() {
        boolean client = FMLEnvironment.dist == Dist.CLIENT;
        LOGGER.info("[cw_portal_guard] loaded (v{}) side={}", VERSION, FMLEnvironment.dist);
        LOGGER.info(
            "[cw_portal_guard] active fixes on this side: {}",
            client
                ? "dimension-id-table (server+client) + portal render guard + configured/jei menu guard"
                : "dimension-id-table (server)"
        );
        CwGuardConfig.logState();
    }
}

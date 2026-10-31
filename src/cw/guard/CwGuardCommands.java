package cw.guard;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;

import java.util.List;

/**
 * {@code /cwguard} 的公共部分（两端可用）：
 *
 * <pre>
 * /cwguard status    当前开关 + 维度 id 表修复状态
 * /cwguard idtable   IP 维度 id 表体检：列出「服务端已加载但表里没有 id」的维度（0 个 = 健康）
 * </pre>
 *
 * <p>客户端专属子命令（{@code dims} / {@code repro}）在 {@code cw.guard.client.CwClientCommands}
 * 里单独注册，这里不引用任何客户端类——专用服务端 jar 里没有 {@code net.minecraft.client.*}。
 */
public final class CwGuardCommands {

    private CwGuardCommands() {
    }

    /** 专用服务端注册的命令树。 */
    public static void registerServer(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.m_82127_("cwguard")
            .then(Commands.m_82127_("status").executes(CwGuardCommands::status))
            .then(Commands.m_82127_("idtable").executes(CwGuardCommands::idtable)));
    }

    public static int status(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        CwGuardConfig.logState();
        String text = "[cwguard] syncPreload=" + CwGuardConfig.portalSyncPrewarm()
            + " tickPreload=" + CwGuardConfig.tickPrewarm()
            + " heal=" + CwGuardConfig.rendererHeal()
            + " renderGuard=" + CwGuardConfig.renderGuard()
            + " configuredFilter=" + CwGuardConfig.configuredJeiFilter()
            + " configuredGuard=" + CwGuardConfig.configuredListGuard()
            + " dimensionIdGuard=" + CwGuardConfig.dimensionIdGuard();
        source.m_288197_(() -> Component.m_237113_(text), false);
        source.m_288197_(() -> Component.m_237113_(
            "[cwguard] dimension id table: " + DimensionRecordGuard.lastResult()), false);
        return 1;
    }

    public static int idtable(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<String> missing = DimensionRecordGuard.missingDimensions();
        if (missing.isEmpty()) {
            source.m_288197_(() -> Component.m_237113_(
                "[cwguard] dimension id table OK - every loaded dimension has an id. "
                    + DimensionRecordGuard.lastResult()), false);
            return 1;
        }
        source.m_81352_(Component.m_237113_(
            "[cwguard] " + missing.size() + " dimension(s) without an id in Immersive Portals' record "
                + "(players entering these WILL be kicked): " + String.join(", ", missing)
                + " - run /cwguard status, and check that dimension_id_guard is enabled"));
        return 0;
    }
}

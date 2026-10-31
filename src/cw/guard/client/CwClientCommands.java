package cw.guard.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import cw.guard.CwGuardCommands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.RegisterCommandsEvent;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 客户端侧完整 {@code /cwguard} 命令树（只在 CLIENT 端注册）。
 *
 * <pre>
 * /cwguard status                       当前开关 + 维度 id 表修复状态
 * /cwguard idtable                      IP 维度 id 表体检（0 个缺失 = 健康）
 * /cwguard dims                         列出客户端已加载的维度
 * /cwguard repro auto [radius]          把最近的门改到「本次会话还没加载」的维度
 * /cwguard repro &lt;dimension&gt; [radius]  同上，指定维度
 * </pre>
 *
 * <p>只有「本次会话还没加载过」的维度才能触发原始崩溃：崩溃发生在渲染帧内懒创建
 * 目的地维度的路径上。改到已加载维度不会触发（这也是为什么它看起来"偶发"）。
 *
 * <p>{@code repro} 走的是 IP 正常的同步路径
 * （{@code Portal#reloadAndSyncToClient} → 客户端 {@code acceptDataSync}），
 * 与「服务端改门的目的地」完全同一条代码路径。
 */
public final class CwClientCommands {

    private CwClientCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.m_82127_("cwguard")
            .then(Commands.m_82127_("status")
                .executes(CwGuardCommands::status))
            .then(Commands.m_82127_("idtable")
                .executes(CwGuardCommands::idtable))
            .then(Commands.m_82127_("dims")
                .executes(CwClientCommands::dims))
            .then(Commands.m_82127_("repro")
                .requires(source -> source.m_6761_(2))
                .then(Commands.m_82127_("auto")
                    .executes(context -> reproAuto(context, 48.0D))
                    .then(Commands.m_82129_("radius", DoubleArgumentType.doubleArg(1.0D, 256.0D))
                        .executes(context -> reproAuto(context, DoubleArgumentType.getDouble(context, "radius")))))
                .then(Commands.m_82129_("dimension", ResourceLocationArgument.m_106984_())
                    .executes(context -> reproDim(context, 48.0D))
                    .then(Commands.m_82129_("radius", DoubleArgumentType.doubleArg(1.0D, 256.0D))
                        .executes(context -> reproDim(context, DoubleArgumentType.getDouble(context, "radius")))))));
    }

    private static int dims(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Set<ResourceKey<Level>> loaded = clientWorlds();
        source.m_288197_(() -> Component.m_237113_(
            "[cwguard] client worlds (" + loaded.size() + "): " + summarize(loaded)
        ), false);
        return 1;
    }

    private static int reproAuto(CommandContext<CommandSourceStack> context, double radius) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player;
        try {
            player = source.m_81375_();
        } catch (Exception e) {
            source.m_81352_(Component.m_237113_("[cwguard] must be run by a player"));
            return 0;
        }

        ServerLevel level = source.m_81372_();
        Set<ResourceKey<Level>> loaded = clientWorlds();
        List<ResourceKey<Level>> candidates = new ArrayList<>();
        for (ResourceKey<Level> key : source.m_81377_().m_129784_()) {
            if (key.equals(level.m_46472_()) || loaded.contains(key)) {
                continue;
            }
            if (key.m_135782_().toString().startsWith("ae2:spatial")) {
                continue; // AE2's spatial storage dimension is not a fair test target
            }
            candidates.add(key);
        }
        if (candidates.isEmpty()) {
            source.m_81352_(Component.m_237113_(
                "[cwguard] every dimension is already loaded this session - restart the game and retry"
            ));
            return 0;
        }
        candidates.sort(Comparator.comparing(key -> key.m_135782_().toString()));
        ResourceKey<Level> chosen = candidates.stream()
            .filter(key -> key.m_135782_().toString().startsWith("hexahedron:"))
            .findFirst()
            .orElse(candidates.get(0));
        return retarget(source, level, player, chosen, radius, false);
    }

    private static int reproDim(CommandContext<CommandSourceStack> context, double radius) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player;
        try {
            player = source.m_81375_();
        } catch (Exception e) {
            source.m_81352_(Component.m_237113_("[cwguard] must be run by a player"));
            return 0;
        }

        ResourceLocation id = ResourceLocationArgument.m_107011_(context, "dimension");
        ResourceKey<Level> dimension = ResourceKey.m_135785_(Registries.f_256858_, id);
        ServerLevel target = source.m_81377_().m_129880_(dimension);
        if (target == null) {
            source.m_81352_(Component.m_237113_("[cwguard] unknown dimension: " + id));
            return 0;
        }
        boolean loaded = clientWorlds().contains(dimension);
        return retarget(source, source.m_81372_(), player, dimension, radius, loaded);
    }

    private static int retarget(
        CommandSourceStack source,
        ServerLevel level,
        ServerPlayer player,
        ResourceKey<Level> dimension,
        double radius,
        boolean alreadyLoaded
    ) {
        Portal portal = level
            .m_45976_(Portal.class, player.m_20191_().m_82400_(radius))
            .stream()
            .min(Comparator.comparingDouble(candidate -> candidate.m_20280_(player)))
            .orElse(null);
        if (portal == null) {
            source.m_81352_(Component.m_237113_(
                "[cwguard] no portal within " + (int) radius + " blocks - stand next to one first"
            ));
            return 0;
        }

        double distance = Math.sqrt(portal.m_20280_(player));
        try {
            portal.setDestinationDimension(dimension);
            portal.reloadAndSyncToClient();
        } catch (Throwable t) {
            source.m_81352_(Component.m_237113_("[cwguard] repro failed: " + t));
            return 0;
        }

        String name = dimension.m_135782_().toString();
        source.m_288197_(() -> Component.m_237113_(
            "[cwguard] portal " + portal.m_20148_() + " -> " + name
                + " (distance " + String.format("%.1f", distance) + ")"
                + (alreadyLoaded ? " [WARNING] this dimension is already loaded, the crash will NOT trigger" : "")
        ), true);
        if (!alreadyLoaded) {
            // IP creates the world at packet time; drop it again a few ticks later so
            // the render pass has to create it (that is the path that used to crash).
            CwRepro.scheduleDispose(dimension, 10);
            source.m_288197_(() -> Component.m_237113_(
                "[cwguard] " + name + " was created by the portal sync; it will be disposed again in ~0.5s"
                    + " - keep looking at the portal so the render pass has to recreate it"
            ), false);
        }
        return 1;
    }

    private static Set<ResourceKey<Level>> clientWorlds() {
        try {
            return new LinkedHashSet<>(ClientWorldLoader.worldRendererMap.keySet());
        } catch (Throwable ignored) {
            return Set.of();
        }
    }

    private static String summarize(Set<ResourceKey<Level>> keys) {
        List<String> names = keys.stream()
            .map(key -> key.m_135782_().toString())
            .sorted()
            .toList();
        if (names.size() <= 10) {
            return String.join(", ", names);
        }
        return String.join(", ", names.subList(0, 10)) + " ... (+" + (names.size() - 10) + ")";
    }
}

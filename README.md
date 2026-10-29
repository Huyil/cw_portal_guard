# cw_portal_guard

**Client-side Forge 1.20.1 patch for Immersive Portals 3.0.7** — fixes a reproducible client crash
(`ChunkRenderDispatcher` is null) the first time the player looks through a portal into a
dimension that has not been loaded yet.

*EN*: Immersive Portals creates a secondary `ClientWorld` + `LevelRenderer` lazily **inside the
render pass**. Creating it calls `LevelRenderer.setLevel()` → `allChanged()`, but IP's own
`MixinLevelRenderer.onReloadStarted` cancels `allChanged()` while a portal is being rendered —
and `chunkRenderDispatcher` is only created inside `allChanged()`. The renderer therefore stays
half-initialized and the next `onSetupTerrainBegin` dereferences a null field and crashes.
This mod creates destination dimensions **outside** the render pass (portal data-sync hook +
pre-render prewarm), heals any half-initialized renderer, and as a last resort skips one frame's
terrain setup instead of crashing.

---

## 1. 崩溃现象

```
java.lang.NullPointerException: Cannot invoke
  "net.minecraft.client.renderer.chunk.ChunkRenderDispatcher.m_112693_(net.minecraft.world.phys.Vec3)"
because "this.f_109436_" is null
  at LevelRenderer.handler$com000$immersive_portals$onSetupTerrainBegin(LevelRenderer.java:8349)
  at LevelRenderer.m_194338_(LevelRenderer.java)
  at MyGameRenderer.lambda$switchAndRenderTheWorld$1(MyGameRenderer.java:203)
  ...
Level dimension: hexahedron:<某维度>
Client Chunks (ImmPtl) 0
```

同帧日志：

```
world renderer reloading cancelled during portal rendering
Client World Created hexahedron:<某维度>
```

复现条件：**某扇无缝传送门的目的地被改成"本次会话还没加载过"的维度，而玩家正看着这扇门**
（例如启动 Cube Warper 的传送立方体时人站在立方体外）。同一会话里该维度被成功加载过一次后，
就不再复现 —— 所以它看起来"偶发"，其实只崩第一次。

## 2. 根因

1. `ClientWorldLoader.createSecondaryClientWorld()` 在**渲染帧内**被调用（从
   `MyGameRenderer.switchAndRenderTheWorld` → `getWorldRenderer` → `getWorld` 进来）。
2. 它 `new LevelRenderer(...)` 后调 `worldRenderer.setLevel(newWorld)`；
   `setLevel()` 对非 null level 只做一件事 —— `allChanged()`。
3. **`chunkRenderDispatcher` / `ViewArea` 正是在 `allChanged()` 里创建的**；
   而 IP 的 `MixinLevelRenderer.onReloadStarted` 在 `WorldRenderInfo.isRendering()` 时
   直接 `ci.cancel()` 掉 `allChanged()`。
4. 于是新渲染器 `level != null` 但 `chunkRenderDispatcher == null`；同一帧继续渲染，
   `onSetupTerrainBegin` 在「渲染门后内容 && 该维度 != 玩家维度」时无条件执行
   `chunkRenderDispatcher.setCamera(...)` → NPE → 客户端崩溃。

## 3. 修复

| 组件 | 时机 | 作用 |
|---|---|---|
| `MixinPortalSync` | 门的**目的地数据同步返回处**（收包阶段，非渲染帧） | 目的地一改就把该维度的 `ClientLevel` 建出来；此时 `allChanged()` 不会被 IP 取消 |
| `CwPortalGuard` | `RenderTickEvent.START` / `ClientTickEvent.END` | 预热所有已加载门的目的地维度；并自愈「有 level 但 `chunkRenderDispatcher == null`」的渲染器 |
| `MixinLevelRendererGuard`（priority 3000） | `setupRender` HEAD | 兜底：万一还有漏网，跳过该帧地形构建而不是崩溃（下一帧已自愈） |
| `LevelRendererAccessor` | — | `@Accessor("f_109436_")` 读取 `chunkRenderDispatcher` |

## 4. 构建

不联网、不需要 Gradle，纯 `javac`/`jar`：

```bash
bash build.sh            # 产物 dist/cw_portal_guard-1.0.0.jar
```

原理：直接用客户端实例里现成的 **production(SRG) jar** 当类路径，所以源码里直接写 SRG 名
（`m_194338_` 之类）——不需要 refmap，也不怕 ForgeGradle 的 reobf 改坏。
可用环境变量 `JDK=...` 覆盖 JDK 路径。

## 5. 安装 / 卸载

- 安装：把 `dist/cw_portal_guard-*.jar` 丢进 `.minecraft/versions/<版本>/mods/`。
- 卸载：删掉该 jar。纯客户端 mod，不写存档、不影响服务端。
- 依赖：Forge 47.x + 1.20.1 + Immersive Portals 3.x（`mods.toml` 里已声明）。

## 6. 验证

```
[cw_portal_guard] loaded (v1.0.3)
[cw_portal_guard] active (hooked at render)
[cw_portal_guard] prewarmed client dimension hexahedron:<dim> (via portal-sync)
[cw_portal_guard] alive: worlds=3 portals=8
```

观察点：`world renderer reloading cancelled` 不应再出现，`crash-reports/` 不再新增。
完整前后对照见 [docs/evidence.md](docs/evidence.md)。

## 7. 版本与踩坑

- `v1.0.0`/`v1.0.1`：无效。坑：
  1. mixin 只写进 jar manifest 的 `MixinConfigs`，**Forge 1.20.1 不会注册**，必须写
     `mods.toml` 的 `[[mixins]] config="cw_portal_guard.mixins.json"`；
  2. 日志用 `System.out` 不会进 `logs/latest.log`（log4j 只写自己的事件），必须用 slf4j；
  3. jar 根目录要有 `pack.mcmeta`（`pack_format: 15`），否则启动会弹
     「未能加载有效的ResourcePackInfo」警告页。
- `v1.0.3`：`MixinPortalSync` 在收包阶段建维度后，`reloading cancelled` 归零、不再崩溃。
- `v1.1.0`：新增 Configured + JEI 配置菜单崩溃修复（见 §8），按 mod 存在性门控，缺 mod 不启用也不报错。

## 8. JEI 配置菜单崩溃（Configured 2.2.3 + JEI 15.74）

打开 JEI 配置菜单时客户端 NPE 崩溃（crash report 是 `Ticking screen` +
`ListMenuScreen.m_86600_`），真正的原因在日志前一行：`IllegalStateException: Config schema is inactive: jei`。

链路：JEI 齿轮 → Configured 的 `ModConfigSelectionScreen` → `init()` 里先 `constructEntries()` →
为每个配置取文件名 → `JeiConfig.getFileName()` → JEI 15.60+ 的 deprecated
`IJeiConfigFile#getPath()` 对「没有本地文件」的 schema 直接抛异常（本次是远程服务器同步来的
`jei-server.ini`）→ `init()` 中断，`searchTextField` 没建出来 → 下一 tick NPE。

修复（v1.1.0 起内置，**只有 Configured 和 JEI 同时安装才启用**）：

| 组件 | 作用 |
|---|---|
| `ConfiguredJeiMixinPlugin` | mixin config plugin：`configured` 与 `jei` 都在才应用兼容 mixin；确认缺任一个就跳过（不报错）。`ModList` 还没就绪时不缓存“否”（避免过早判定把修复整个禁掉），目标类缺失由 `required: false` 兜底 |
| `MixinConfiguredConfigMap` | 在 `ClientHandler.createConfigMap` 返回处过滤掉「取文件名会抛异常」的配置项（JEI 齿轮与 mod 列表两条入口共用这个汇聚点），从根上避免 `init()` 中断 |
| `MixinConfiguredListGuard` | 技术兜底：屏幕若仍处于半初始化（`searchTextField`/`list` 为 null），tick/render 跳过而不是 NPE；字段用反射读取（内部类为 protected，跨包不能直接引用） |

效果：单人 / 自建 LAN 不受影响（服务端配置有本地文件，条目照常显示）；远程服只少了那一条
「无本地文件」的服务端配置，客户端配置全部正常。

日志：

```
[cw_portal_guard] Configured + JEI compat enabled (JEI config menu guard)
[cw_portal_guard] hid unavailable config entry from jei (CLIENT)
```

### 已知限制

- 过滤发生在 Configured 侧，JEI 自身的 deprecated API 行为未改；JEI 若移除
  `IJeiConfigFile` / `IJeiConfigManager`（已标记 forRemoval），Configured 的 JEI 集成本身会失效，
  与本补丁无关。
- 兼容 mixin 的编译依赖：`configured-forge-*.jar`、`asm`+`asm-tree`、`fmlcore`（`build.sh` 已自动探测）。

## 9. 上游修复建议（Immersive Portals）

`iPortalTeam/ImmersivePortalsMod`（1.20.1 分支）`MixinLevelRenderer.onSetupTerrainBegin`：

```java
if (WorldRenderInfo.isRendering()) {
    if (level.dimension() != RenderStates.originalPlayerDimension && chunkRenderDispatcher != null) {
        chunkRenderDispatcher.setCamera(camera.getPosition());
    }
}
```

更彻底：`onReloadStarted` 在 `chunkRenderDispatcher == null`（新建渲染器从未初始化）时
不要取消 `allChanged()`；或把 `createSecondaryClientWorld` 推迟到渲染帧之外
（例如在 portal 数据同步时创建）。

## License

All Rights Reserved（与 `mods.toml` 一致）。

# cw_portal_guard

**Cube Warper 的 Immersive Portals 3.0.7 补丁 —— 一个 jar，两端各修各的。**

| 侧 | 修的问题 | 实现 | 需要 mixin |
|---|---|---|---|
| **服务端**（专用服务端 / 单人存档的集成服务端） | IP 维度 id 表缺条目 → `Missing Dimension <id>` → 玩家一登录就被踢（典型：`ae2:spatial_storage`） | `cw.guard.DimensionRecordGuard`（纯反射，调 IP 自己的 API） | ❌ 不需要 |
| **客户端** | 首次看穿通往「本次会话还没加载过」的维度时 `ChunkRenderDispatcher == null` 崩溃 | `cw.guard.client.ClientGuard` + `cw.guard.mixin.*` | ✅（仅 client 段） |
| **客户端** | Configured 2.2.3 + JEI 15.74 配置菜单崩溃 | `cw.guard.compat.*` + `mixin.compat.*` | ✅（仅 client 段，且两个 mod 都在才启用） |
| **客户端** | Botania 进度饼 HUD 泄漏 GL 状态 → 区块整片黑（准星指着祭坛时） | `cw.guard.client.GlStateSanitizer` | ❌ 不需要 |

同一个 jar 两端都能装：`mods.toml` 里 **没有** `clientSideOnly`，所有客户端逻辑都放在
`cw.guard.client.*` 与被 `@EventBusSubscriber(value = Dist.CLIENT)` 标记的类里，
渲染 mixin 全在 mixin 配置的 `"client"` 段 —— 专用服务端（jar 里根本没有
`net.minecraft.client.*`）不会加载它们。

**为什么要合并成一个 jar**：单人也需要两边的修复 —— 单人世界同样会撞
`Missing Dimension`（集成服务端一样是「IP 在 ServerAboutToStart 定稿、AE2 在
createLevels 末尾才建关卡」），也同样会撞客户端渲染崩溃。

---

## 1. 服务端修复：Missing Dimension 踢人

### 1.1 现象

```
io.netty.handler.codec.EncoderException: java.lang.RuntimeException: Missing Dimension ae2:spatial_storage
  at qouteall.q_misc_util.dimension.DimensionIdRecord.getIntId(DimensionIdRecord.java:50)
  at qouteall.q_misc_util.dimension.DimId.writeWorldId(DimId.java:25)
  ... ClientboundPlayerPositionPacket.handler$blb000$immersive_portals$onWrite
  ... ClientboundCustomPayloadPacket ... (imm_ptl:rd 包装)
```

玩家存档在 / 被传送到该维度后，一登录就循环被踢。**重启服务器不会自愈**
（这是最容易误判的地方）。

### 1.2 根因（`javap -p -c` 核对 IP 3.0.7 字节码，确定性的）

1. `DimensionIdManagement.onServerStarted()` 在 **ServerAboutToStart** 阶段执行：
   读 `world/data/imm_ptl_dim_reg.dat` → `completeServerIdRecord()` → 回写；
2. `completeServerIdRecord()` 只遍历 `server.getAllLevels()`（`MinecraftServer.m_129784_()`），
   而此刻 `MinecraftServer` **还没进 `createLevels`**；
3. AE2 的 `ae2:spatial_storage` 由 `appeng.mixins.spatial.MinecraftServerMixin#injectSpatialLevel`
   在 `createLevels` 末尾才 `put` 进 levelMap。

⇒ id 表永远缺它；任何引用该维度的包在编码期抛异常 → 连接被断。
**推论：同类风险不止 AE2** —— 任何用 mixin 在 `createLevels` 里插关卡的模组都会中招，
而本修复对它们一并生效。

### 1.3 修法

IP 自己提供了正好需要的方法（3.0.7 实测，`public static`）：

```java
DimensionIdManagement.updateAndSaveServerDimIdRecord();
//   = completeServerIdRecord()                 // 遍历当前 getAllLevels()，缺的补 max+1
//   + recordToTag(serverRecord, dim -> true)   // predicate 恒 true，不丢条目
//   + NbtIo.writeCompressed(...)               // 立刻落盘
```

在 `ServerStartedEvent`（所有关卡已创建、玩家还没进来）调一次即可；
id 由 IP 自己分配，服务端/客户端天然一致（客户端登录时从服务端拿这张表）。
之后每 20 tick 复查一次，表大小连续 3 次不变即收工（兼容「关卡懒创建」的模组）。

全程反射：没装 IP 或 IP 改了方法名时只打一条日志，不影响启动。

### 1.4 运维命令

```
/cwguard status    当前开关 + 维度 id 表修复状态
/cwguard idtable   IP 维度 id 表体检：列出「已加载但没有 id」的维度（0 个 = 健康）
                   ← 这就是踢人事故的复发警报，出现非 0 立刻查
```

（客户端/单人额外有 `dims`、`repro` 两个调试子命令，见 §3.5。）

---

## 2. 客户端修复 A：首次跨维度门户渲染崩溃

### 2.1 现象

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

同帧日志：`world renderer reloading cancelled during portal rendering` + `Client World Created ...`。
复现条件：**某扇无缝传送门的目的地被改成"本次会话还没加载过"的维度，而玩家正看着这扇门**。
同一会话里该维度被加载过一次后就不再复现 —— 所以看起来"偶发"，其实只崩第一次。

### 2.2 根因

1. `ClientWorldLoader.createSecondaryClientWorld()` 在**渲染帧内**被调用；
2. 它 `new LevelRenderer(...)` 后调 `setLevel(newWorld)`，`setLevel()` 对非 null level 只做 `allChanged()`；
3. `chunkRenderDispatcher` / `ViewArea` 正是在 `allChanged()` 里创建的，而 IP 的
   `MixinLevelRenderer.onReloadStarted` 在 `WorldRenderInfo.isRendering()` 时直接 `ci.cancel()`；
4. 新渲染器 `level != null` 但 `chunkRenderDispatcher == null`，同帧 `onSetupTerrainBegin`
   无条件 `chunkRenderDispatcher.setCamera(...)` → NPE。

### 2.3 修复（分层，可用开关逐层验证）

| 组件 | 时机 | 作用 |
|---|---|---|
| `MixinPortalSync` | 门的目的地数据同步返回处（收包阶段，非渲染帧） | 目的地一改就把该维度的 `ClientLevel` 建出来；此时 `allChanged()` 不会被 IP 取消 |
| `ClientGuard` | `RenderTickEvent.START` / `ClientTickEvent.END` | 预热所有已加载门的目的地维度；自愈「有 level 但 `chunkRenderDispatcher == null`」的渲染器 |
| `MixinLevelRendererGuard`（priority 3000） | `setupRender` HEAD | 兜底：万一还有漏网，跳过该帧地形构建而不是崩溃 |
| `LevelRendererAccessor` | — | `@Accessor("f_109436_")` 读取 `chunkRenderDispatcher` |

---

## 3. 构建 / 安装 / 调试

### 3.1 构建

不联网、不需要 Gradle，纯 `javac`/`jar`：

```bash
bash build.sh      # 产物 dist/cw_portal_guard-<version>.jar
```

直接用客户端实例里现成的 **production(SRG) jar** 当类路径，所以源码里直接写 SRG 名
（`m_194338_` 之类）—— 不需要 refmap，也不怕 ForgeGradle 的 reobf 改坏。
`net.minecraftforge.api.distmarker.Dist` 由 `net.minecraftforge:mergetool:<ver>:api`
提供（运行时它在 version json 的 libraries 里，编译期 `build.sh` 已自动探测）。

### 3.2 安装

- **服务端**：`mods/cw_portal_guard-<ver>.jar`，重启。
- **客户端**：同一个 jar 放进 `mods/`（不要两个都放，重复 modId 会启动失败）。
- 卸载：删 jar；服务端已写进 `imm_ptl_dim_reg.dat` 的条目会保留（无害）。
- 依赖：Forge 47.x + 1.20.1 + Immersive Portals 3.x；Configured/JEI 是可选的
  （缺任一个，兼容 mixin 自动不启用）。

### 3.3 开关

`config/cw_portal_guard.properties`（不存在会自动生成，默认全开）。
改动最多 1 秒生效（渲染层立即可测；`dimension_id_guard` 在下一次世界/服务器启动时读取）。

```
portal_sync_prewarm / tick_prewarm / renderer_heal / render_guard   # 客户端渲染层
configured_jei_filter / configured_list_guard                       # 客户端 JEI 菜单层
dimension_id_guard                                                  # 两端：维度 id 表（服务端也读）
```

启动日志会打印当前状态（含 `dimensionIdGuard=`）。

### 3.4 两端分别看到什么

```
# 专用服务端
[cw_portal_guard] loaded (v1.3.0) side=DEDICATED_SERVER
[cw_portal_guard] active fixes on this side: dimension-id-table (server)
[cw_portal_guard] ServerStartedEvent: 维度表 90 -> 91 条（新增 1）
[cw_portal_guard] + 补上维度 id: ae2:spatial_storage -> 89
[cw_portal_guard] 维度 id 表修复完成：91 条（4 次尝试），ae2:spatial_storage -> 89（已收录）

# 客户端（单人/联机都会看到）
[cw_portal_guard] loaded (v1.3.0) side=CLIENT
[cw_portal_guard] active fixes on this side: dimension-id-table (server+client) + portal render guard + configured/jei menu guard
[cw_portal_guard] active (hooked at render)
[cw_portal_guard] prewarmed client dimension hexahedron:<dim> (via portal-sync)
```

### 3.5 `/cwguard` 命令

客户端/单人（完整树）：`status`、`idtable`、`dims`、`repro <dimension|auto> [radius]`。
专用服务端：`status`、`idtable`（`repro`/`dims` 依赖客户端世界表，故不注册）。

`/cwguard repro` 走 IP 正常的同步路径
（`Portal#reloadAndSyncToClient` → 客户端 `acceptDataSync`），与「服务端改门的目的地」
完全同一条代码路径。前提：单人世界、开启作弊（或 op 权限 2）、站在门前、目标维度
本次会话还没加载过；且不能是 global portal。备用（不依赖本 mod）：

```
execute as @e[type=immersive_portals:portal,limit=1,sort=nearest] at @s run portal set_portal_destination <dim> ~ ~ ~
```

判定哪一层真正起作用：每轮都要**重启客户端**（同一会话里维度加载过一次就不再触发首帧路径）。

| 轮次 | 只开 | 预期 |
|---|---|---|
| 0 | 全关 | 崩溃（基线） |
| 1 | `portal_sync_prewarm` | 不崩，日志 `prewarmed ... (via portal-sync)` |
| 2 | `tick_prewarm` | 不崩则日志 `(via portal-tick)` |
| 3 | `renderer_heal` | 大概率仍崩（自愈发生在崩溃之后） |
| 4 | `render_guard` | 不崩，日志 `guard skipped terrain setup` |
| 5 | 全开 | 不崩，且不应出现 `guard skipped` |

---

## 4. 客户端修复 B：JEI 配置菜单崩溃（Configured 2.2.3 + JEI 15.74）

打开 JEI 配置菜单时客户端 NPE 崩溃（crash report 是 `Ticking screen` +
`ListMenuScreen.m_86600_`）。真正的原因在日志前一行：`IllegalStateException: Config schema is inactive: jei`。

链路：JEI 齿轮 → Configured 的 `ModConfigSelectionScreen` → `init()` 里先 `constructEntries()`
→ 为每个配置取文件名 → `JeiConfig.getFileName()` → JEI 15.60+ 的 deprecated
`IJeiConfigFile#getPath()` 对「没有本地文件」的 schema 直接抛异常（本次是远程服务器同步来的
`jei-server.ini`）→ `init()` 中断，`searchTextField` 没建出来 → 下一 tick NPE。

| 组件 | 作用 |
|---|---|
| `ConfiguredJeiMixinPlugin` | mixin config plugin：`configured` 与 `jei` 都在才应用兼容 mixin；缺任一个就跳过（不报错）。`ModList` 未就绪时不缓存「否」，目标类缺失由 `required: false` 兜底 |
| `MixinConfiguredConfigMap` | 在 `ClientHandler.createConfigMap` 返回处过滤「取文件名会抛异常」的配置项（JEI 齿轮与 mod 列表两条入口共用这个汇聚点） |
| `MixinConfiguredListGuard` | 技术兜底：屏幕半初始化时 tick/render 跳过而不是 NPE（字段用反射读取） |

---

## 5. 客户端修复 C：Botania「进度饼」HUD 导致区块整片不渲染（黑屏、不崩）

### 5.1 现象

准星指着**正在合成**的 Botania 符文祭坛时，**自己这个世界的区块整片不画**（看起来黑屏）：
门对面的维度、实体、TESR（祭坛漂浮的物品）、粒子、HUD 全部正常；准星一移开**立刻**恢复。
不崩溃，FPS 正常，Embeddium 的 `Geometry Pool` 不为 0（说明几何已上传，是**绘制时被丢弃**）。

### 5.2 根因

`vazkii.botania.client.core.helper.RenderHelper#renderProgressPie`（准星指着祭坛时才画的那个进度饼）
在 HUD 阶段做了这些事（javap 反编译实证）：

```
RenderSystem.clear(256, true);                     // glClear(GL_DEPTH_BUFFER_BIT)
GL11.glEnable(2960);                               // 裸 GL：GL_STENCIL_TEST
RenderSystem.colorMask(false,false,false,false);
RenderSystem.depthMask(false);
RenderSystem.stencilFunc(512 /*GL_NEVER*/, 1, 255);
RenderSystem.stencilOp(7681, 7680, 7680);
RenderSystem.stencilMask(255);
... 用 stencil 把图标裁成进度饼 ...
GL11.glDisable(2960);
```

它用 stencil 缓冲做图标裁剪，并且**直接调 LWJGL**（绕过 `GlStateManager` 的状态缓存），
退出时也没有把 `depthFunc` / stencil 的 func·op·mask / 着色器颜色恢复成中性值。
结果是每帧往下一帧的世界渲染里塞一次脏状态：区块几何被整批丢弃，而走别的渲染路径的东西
（门后维度、实体、粒子、HUD）不受影响 —— 这就是"只有世界变黑"的原因。

### 5.3 修复

`cw.guard.client.GlStateSanitizer`，在**每帧世界渲染开始之前**（`RenderTickEvent.START`，
早于 `LevelRenderer.renderLevel`）强制复位：

| 复位项 | 值 |
|---|---|
| stencil test | 关（1.20.1 的 `GlStateManager` 没有 stencil-test 缓存方法，用裸 GL） |
| `colorMask` / `depthMask` | 全 true（"先设反再设正"强制真的下发，绕过缓存吞调用） |
| `depthFunc` | `GL_LEQUAL` |
| depth test / cull / blend | 开 / 开 / 关 |
| `shaderColor` | 1,1,1,1 |

开关：`gl_state_reset`（默认 true）。开销每帧十几次 GL 调用，可忽略。
只有检测到脏状态时才会打一条日志（同一签名只打一次，不刷屏）：

```
[cw_portal_guard] dirty GL state before world render (stencilTest=... depthFunc=... ...) - sanitizing
```

> 注意：这个坑不只祭坛。**任何用同一个进度饼的 Botania 方块**（魔力池充电、泰拉钢平台等）
> 在被注视时都会触发同一发污染，所以这层保护是通用的，不是祭坛专用。

### 5.4 为什么是"每帧无条件抹"而不是"只修那一位"

2026-10-31 用 `glGet*` 全量探测（含 stencil 的 func/ref/valueMask/writeMask/fail/zfail/zpass）得到的实测值：

```
stencilTest=false depthTest=false blend=false cull=true colorMask=true depthMask=true depthFunc=515
stencilFunc=514(GL_EQUAL) ref=1 valueMask=255 writeMask=0 fail=ZERO zfail=KEEP zpass=KEEP
```

- `depthFunc=515(GL_LEQUAL)`、`colorMask/depthMask=true`、`blend=false`、`cull=true` —— 全是中性值；
- `stencilFunc=GL_EQUAL(514)/ref=1/valueMask=255/writeMask=0/stencilOp(GL_REPLACE,KEEP,KEEP)`
  与 `renderProgressPie` 结尾设置的值**逐项吻合** —— 即这些残值就是 Botania 自己留下的
  （`stencilTest=false`，所以单独存在时是惰性的）；
- Botania 的 Hud 把 `shaderColor` 设成 `(1,1,1,1)`（反编译确认），也不脏。

⇒ 用采样日志**抓不到**"让区块消失的那一位"：污染只存在于被污染的下一帧，而采样必然漏帧。
因此"只修那一位"无法安全成立，**保留全量复位**才是可靠形态（这 8 项都是原版每个 pass 自己会设的值，覆盖它们零风险）。

### 5.5 实测（2026-10-31）

在**把其它临时改动全部还原**（ImmediatelyFast 通用批处理=开、IP `enableClippingMechanism`=开、
`portal_sync_prewarm` 等四个渲染层=关）的情况下，1.3.2 依然正常：祭坛合成 + 准星指着它不再黑屏，
准星移开/移回都正常。⇒ 说明这层修复与 IF 批处理、IP 裁剪、门户预热都无关，**独立生效**。

---

## 6. 验证记录

### 5.1 服务端（2026-10-31，真实测试服，A/B）

故意把 `imm_ptl_dim_reg.dat` 换回「缺条目」的 90 条版本作为基线，然后只装本 mod 启动：

```
11:49:28  loaded (v1.3.0) side=DEDICATED_SERVER
11:49:28  active fixes on this side: dimension-id-table (server)
11:49:28  toggles (...): ... dimensionIdGuard=true
11:49:57  ServerStartedEvent: 维度表 90 -> 91 条（新增 1）
11:49:57  + 补上维度 id: ae2:spatial_storage -> 89
11:50:08  维度 id 表修复完成：91 条（4 次尝试），ae2:spatial_storage -> 89（已收录）
grep -c "Missing Dimension"  ->  0
/cwguard idtable             ->  dimension id table OK - every loaded dimension has an id
```

端到端（同一测试服，真实客户端）：把玩家 `tp` 进 `ae2:spatial_storage` 停留 5 秒再传回，
全程不掉线；`grep -c "lost connection"` = 0。

### 5.2 客户端

客户端逻辑与 1.2.0 **逐方法一致**（`ClientGuard` 的方法集与旧 `CwPortalGuard` 完全相同，
只是换了包名并把注册方式从「构造函数里手动注册」改成
`@EventBusSubscriber(value = Dist.CLIENT)`），mixin 配置与 1.2.0 字节级相同。
另做了静态校验：jar 内所有类的常量池引用在客户端 classpath（MC srg jar + Forge + IP +
Configured）上 100% 可解析。

⚠️ **仍需一次真实客户端启动确认**（本次没有 GUI 环境）：进游戏后应看到
`[cw_portal_guard] active (hooked at render)`，且首次看穿新维度门户不崩。

---

## 7. 已知限制 / 风险

- 服务端修复靠反射调用 IP 的 `updateAndSaveServerDimIdRecord()`（3.0.7 实测存在）。
  IP 大版本升级若改名，只会打一条 error 日志并停手，不影响启动 —— 此时用
  `/cwguard idtable` 能立刻看出来。
- `ServerStartedEvent` 之前的一两秒内若恰好有玩家登录，他拿到的可能仍是旧表；
  重连即恢复。运维上不会踩到（重启通常先等 `Done (` 再放人）。
- 客户端 `repro` 只在单人/集成服务端有意义；联机时改门目的地请用服务端命令。
- 本 mod 只保证「缺 id 的维度不再踢人」，不改变 AE2 空间存储本身的玩法。

## License

**MIT** —— 见 [LICENSE](LICENSE)（与 `mods.toml` 的 `license="MIT"` 一致）。

明确一句，省得别人猜：

- **可以随意把本 mod 放进任何整合包**（包括商业整合包），不需要单独授权、不需要事先询问；
- **可以装在任意服务器上使用**（公共服、商业服、私人服都行）；
- 也可以修改、二次分发，只需保留版权声明与许可声明。

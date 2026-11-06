# cw_portal_guard

[English](README.md) | **简体中文**

> Cube Warper 的运行时修复集合，**一个 jar 覆盖服务端与客户端**。
> Forge 1.20.1 / Immersive Portals 3.0.7。

| | 修复 | 生效侧 |
|:--|:--|:--|
| 1 | IP 维度 id 表缺条目导致的 `Missing Dimension <id>` 踢人 | 服务端 + 单人 |
| 2 | 首次看穿未加载维度门户时的 `ChunkRenderDispatcher` 空指针崩溃 | 客户端 |
| 3 | Configured + JEI 配置菜单崩溃 | 客户端 |
| 4 | Botania 进度饼 HUD 导致区块整片不渲染 | 客户端 |
| 5 | Create × CobbleGen：刷石机优化在自定义维度静默失效 | 服务端 + 单人 |

技术细节、根因与验证数据见 **[docs/TECHNICAL.md](docs/TECHNICAL.zh-CN.md)**。

---

## 安装

把 [Releases](https://github.com/Huyil/cw_portal_guard/releases) 里的
`cw_portal_guard-<version>.jar` 放进 `mods/`：

- **服务端**：修复 1 与 5；
- **客户端**：修复 2、3、4，以及单人存档内的 1 与 5；
- 同一个 jar 两端共用，服务端与客户端加载各自对应的修复。

<details>
<summary>依赖</summary>

- 必需：Forge 47.x、Minecraft 1.20.1、Immersive Portals 3.x
- 按需自动启用：Create 6.x + CobbleGen 5.x（修复 5）、Configured + JEI（修复 3）
- 未安装对应模组时相关功能自动跳过

</details>

## 配置

首次启动生成 `config/cw_portal_guard.properties`，默认全部启用。

| 开关 | 默认 | 读取时机 | 作用 |
|:--|:--|:--|:--|
| `portal_sync_prewarm` | `true` | 每帧 | 修复 2：门目的地同步时预建维度 |
| `tick_prewarm` | `true` | 每帧 | 修复 2：逐帧预热 |
| `renderer_heal` | `true` | 每帧 | 修复 2：自愈半初始化渲染器 |
| `render_guard` | `true` | 每帧 | 修复 2：兜底跳过地形构建 |
| `configured_jei_filter` | `true` | 每帧 | 修复 3：过滤异常配置项 |
| `configured_list_guard` | `true` | 每帧 | 修复 3：半初始化屏幕判空 |
| `gl_state_reset` | `true` | 每帧 | 修复 4：世界渲染前复位 GL 状态 |
| `dimension_id_guard` | `true` | 世界启动 | 修复 1：补齐 IP 维度 id 表 |
| `cobblegen_compat` | `true` | 世界启动 | 修复 5：对齐 Create 与 CobbleGen 的产物 |

> 标记「世界启动」的两项在 `ServerStartedEvent` 读取一次，改动后需重进世界或重启服务器。
> 其余项在对应事件里实时读取。

## 命令

`/cwguard` 需要**权限等级 2**（服务器 OP；单人存档开启作弊即可）。

| 命令 | 可用端 | 说明 |
|:--|:--|:--|
| `/cwguard status` | 两端 | 当前开关状态与维度 id 表修复结果 |
| `/cwguard idtable` | 两端 | 列出「已加载但缺少 id」的维度，`0` 个为健康 |
| `/cwguard dims` | 客户端 | 列出客户端已加载维度 |
| `/cwguard repro <dimension\|auto> [radius]` | 客户端（单人） | 把最近的门改到指定维度以复现修复 2 |

`/cwguard idtable` 可当作修复 1 的复发检查：输出非 0 时表示该维度会触发踢人。

## 构建

```bash
bash build.sh      # 产物 dist/cw_portal_guard-<version>.jar
```

构建过程使用 `javac` / `jar`，离线执行。类路径取自本地客户端实例的 SRG jar，
可用环境变量覆盖：

```bash
MC=/path/to/.minecraft MODS=/path/to/instance/mods JDK=/path/to/jdk17 bash build.sh
```

## 验证

启动日志示例：

```
[cw_portal_guard] loaded (v1.4.0) side=DEDICATED_SERVER
[cw_portal_guard] ServerStartedEvent: 维度表 90 -> 91 条（新增 1）
[cw_portal_guard] + 补上维度 id: ae2:spatial_storage -> 89
[cw_portal_guard] cobblegen compat installed: own interaction inserted at lava list head
```

各修复的 A/B 实测数据见 **[docs/TECHNICAL.md](docs/TECHNICAL.zh-CN.md)**。

## License

[MIT](LICENSE) — 可自由用于任何整合包与任意服务器，包括商业用途；
修改与二次分发只需保留版权与许可声明。

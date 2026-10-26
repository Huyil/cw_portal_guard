# 证据（2026-10-26 实测）

## 修复前（v1.0.0/1.0.1 未生效的那两版，以及原版）

崩溃：`crash-2026-10-26_22.36.14-client.txt` / `crash-2026-10-26_23.47.00-client.txt`

```
java.lang.NullPointerException: Cannot invoke "net.minecraft.client.renderer.chunk.ChunkRenderDispatcher.m_112693_(net.minecraft.world.phys.Vec3)"
because "this.f_109436_" is null
  at net.minecraft.client.renderer.LevelRenderer.handler$com000$immersive_portals$onSetupTerrainBegin(LevelRenderer.java:8349)
  at net.minecraft.client.renderer.LevelRenderer.m_194338_(LevelRenderer.java)
  at qouteall.imm_ptl.core.render.MyGameRenderer.lambda$switchAndRenderTheWorld$1(MyGameRenderer.java:203)
  ...
Level dimension: hexahedron:magnetic_ceiling
Client Chunks (ImmPtl) 0
```

客户端日志（同一帧）：

```
[22:36:12] world renderer reloading cancelled during portal rendering
[22:36:12] Client World Created hexahedron:magnetic_ceiling
[22:36:12] Created lightmap texture for hexahedron:magnetic_ceiling
[22:36:12] Unreported exception thrown!
```

## 修复后（v1.0.3）

```
[23:53:03] [cw_portal_guard] loaded (v1.0.3)
[23:54:22] [cw_portal_guard] active (hooked at render)
[23:55:17] [cw_portal_guard] prewarmed client dimension hexahedron:hexahedron_graveyard (via portal-sync)
[23:55:32] [cw_portal_guard] alive: worlds=3 portals=8
```

- `world renderer reloading cancelled` 出现次数：**0**
- 该会话 `crash-reports/` 新增：**0**

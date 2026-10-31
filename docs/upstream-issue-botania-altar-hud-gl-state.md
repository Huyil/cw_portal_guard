# Upstream issue draft — Botania 1.20.1-456

**Title:** `RenderHelper#renderProgressPie` leaves non-neutral stencil state behind (breaks chunk rendering when other mods use the stencil buffer)

**Labels:** `bug`, `client`, `1.20.x`

---

## Environment

| | |
|---|---|
| Minecraft | 1.20.1 |
| Forge | 47.4.23 |
| Botania | `Botania-1.20.1-456-FORGE` |
| Other relevant mods | Immersive Portals 3.0.7, Embeddium 0.3.31 (Sodium port), ImmediatelyFast 1.5.5 |
| Side | client (reproduces in single-player, no server needed) |

## Steps to reproduce

1. Place a **Runic Altar** and put the ingredients in so it enters the crafting state
   (the HUD progress pie must be visible).
2. Stand in front of it and move the **crosshair onto the altar** so Botania renders the altar HUD.
3. Look at the world / move the cursor — with Immersive Portals + Embeddium installed, the
   **chunk layer of the player's own dimension stops being rendered** (the screen goes black),
   while entities, block entities, particles and the HUD keep rendering normally.
4. Move the crosshair off the altar → rendering recovers **immediately** (same frame).

Not a crash. FPS unaffected. Embeddium's geometry pool is **not** empty (geometry is uploaded,
it is simply not drawn), so this is a state/draw issue, not a chunk-build issue.

## Root cause

`vazkii/botania/client/core/helper/RenderHelper.java`, `renderProgressPie(...)` uses the stencil
buffer to clip the item icon into a pie, and does not restore the state on exit.

Disassembly of `Botania-1.20.1-456-FORGE.jar` (`javap -p -c`), `renderProgressPie`:

```
 19: sipush 256                      RenderSystem.clear(GL_DEPTH_BUFFER_BIT, true)
 26: sipush 2960                     GL11.glEnable(GL_STENCIL_TEST)          <- raw GL, bypasses GlStateManager
 36: iconst_0 x4                     RenderSystem.colorMask(false,false,false,false)
 40: iconst_0                        RenderSystem.depthMask(false)
 43: sipush 512, 1, 255              RenderSystem.stencilFunc(GL_NEVER, 1, 255)
 53: sipush 7681, 7680, 7680         RenderSystem.stencilOp(GL_REPLACE, GL_KEEP, GL_KEEP)
 65: sipush 255                      RenderSystem.stencilMask(255)
 ..:                                 renderItem(stack, ...)   // writes stencil=1 over the icon
150: iconst_1 x4                     RenderSystem.colorMask(true,true,true,true)   // restored
157: iconst_1                        RenderSystem.depthMask(true)                 // restored
161: iconst_0                        RenderSystem.stencilMask(0)                  // <-- NOT restored
165: sipush 514, 1, 255              RenderSystem.stencilFunc(GL_EQUAL, 1, 255)   // <-- NOT restored
 ..:                                 draw the pie as a TRIANGLE_FAN
364:                                 RenderSystem.disableBlend()
370: sipush 2960                     GL11.glDisable(GL_STENCIL_TEST)              // ends disabled
```

So after the method returns, the GL state still holds:

```
GL_STENCIL_FUNC       = GL_EQUAL (0x0202 / 514)
GL_STENCIL_REF        = 1
GL_STENCIL_VALUE_MASK = 255
GL_STENCIL_WRITEMASK  = 0
GL_STENCIL_FAIL       = GL_REPLACE
GL_STENCIL_PASS_DEPTH_FAIL/PASS = GL_KEEP
```

Verified on a live client by querying the raw GL state at the start of the next world frame
(custom diagnostic mod):

```
stencilTest=false depthFunc=515 colorMask=true depthMask=true
stencilFunc=514 ref=1 valueMask=255 writeMask=0 fail=7681(REPLACE) zfail=7680 zpass=7680
```

`stencilFunc=GL_EQUAL/ref=1/writeMask=0/op=REPLACE` is **exactly** what `renderProgressPie`
sets and never resets.

### Why it matters

The stencil test itself is left *disabled*, so on its own the leftovers are inert. But any mod
that later enables the stencil test and only sets *some* of the sub-state (or that assumes the
neutral `GL_ALWAYS / ref 0 / mask 0xFF / KEEP` defaults) inherits Botania's leftovers:

* in our pack, Immersive Portals uses the stencil buffer for portal clipping
  (`enableClippingMechanism`), and in combination with Embeddium the chunk render pass
  silently stops drawing for the following frames — visible as "the world goes black while the
  player looks at a crafting altar";
* the state also survives into the **next frame**, because nothing else resets it: `GlStateManager`
  in 1.20.1 has no `_enableStencilTest/_disableStencilTest`, and vanilla never touches
  `stencilFunc/stencilOp/stencilMask`.

## Suggested fix

Restore the neutral state before returning (ideally in a `finally`, so it also happens if the
item render throws):

```java
// at the end of renderProgressPie(...)
RenderSystem.stencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
RenderSystem.stencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
RenderSystem.stencilMask(0xFF);
// colorMask/depthMask are already restored earlier; keep them inside the same try/finally
```

Notes / alternatives:

* 1.20.1's `GlStateManager` has no stencil-test cache, so `GL11.glEnable/glDisable` is the only
  option there — but please still neutralise func/op/mask (`GL_ALWAYS / 0 / 0xFF / KEEP`), since
  those *do* go through `RenderSystem`.
* On 1.20.2+/1.21 where `GlStateManager._enableStencilTest()` exists, prefer it so the cached
  state stays in sync.
* The same helper is used by other Botania blocks (mana pool charge animation, terrestrial
  agglomeration plate, …), so a fix here covers all of them.

## Workaround used by the reporting modpack

We ship a small client mod that force-resets this state at the start of every world frame
(`cw_portal_guard` → `GlStateSanitizer`: stencil off + `GL_ALWAYS/0/0xFF/KEEP`, colorMask/depthMask
all on, `depthFunc=GL_LEQUAL`, depthTest/cull on, blend off). It fixes the symptom, but the correct
place is the end of `renderProgressPie`.

## Additional data

* Not related to ImmediatelyFast (reproduces with universal batching off), nor to ModernUI
  (reproduces with it removed), nor to `Immersive Portals → enableClippingMechanism=false`
  (reproduces with it off).
* Removing Botania's HUD (by not looking at the altar) is the only other way to avoid it, which
  matches the root cause being in the HUD draw path.

package cw.guard.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GL 状态看门狗（客户端）。
 *
 * <p>背景：Botania 的「进度饼」HUD（{@code RenderHelper.renderProgressPie}，准星指着正在合成的
 * 符文祭坛时才画、移开准星立刻恢复）在 HUD 阶段做了这些事：
 * <pre>
 *   RenderSystem.clear(256, true);                 // glClear(GL_DEPTH_BUFFER_BIT)
 *   GL11.glEnable(2960);                           // 裸 GL：GL_STENCIL_TEST
 *   RenderSystem.colorMask(false,false,false,false);
 *   RenderSystem.depthMask(false);
 *   RenderSystem.stencilFunc(512 /*GL_NEVER*&#47;, 1, 255);
 *   RenderSystem.stencilOp(7681, 7680, 7680);
 *   RenderSystem.stencilMask(255);
 *   ... 用 stencil 把图标裁成进度饼 ...
 *   GL11.glDisable(2960);
 * </pre>
 *
 * <p>实测症状：准星指着祭坛时，**自己这个世界的区块整片不画**（变黑），而门对面维度、实体、
 * 粒子、HUD 都正常；Embeddium 的 Geometry Pool 不为 0（说明几何已上传，只是绘制时被丢弃）；
 * 准星一移开立刻恢复 —— 典型的「HUD 每帧污染一次 GL 状态」。
 *
 * <p>本类在**每帧世界渲染开始之前**（{@code RenderTickEvent.START}，早于
 * {@code LevelRenderer.renderLevel}）把常用 GL 状态强制复位一次。注意 1.20.1 的
 * {@code GlStateManager} 并没有 stencil-test 的缓存方法（只有 _stencilFunc/_stencilOp/_stencilMask），
 * 所以 stencil 开关必须用裸 GL 关；其余状态用 GlStateManager 的"先设反再设正"写法，
 * 保证真的下发一次 GL 调用而不是被缓存吞掉。
 *
 * <p>开销：每帧十几次 GL 调用，可忽略。异常只会在"发现脏状态"时每秒打一条日志。
 */
@OnlyIn(Dist.CLIENT)
public final class GlStateSanitizer {

    private static final Logger LOGGER = LoggerFactory.getLogger("cw_portal_guard");

    private static final int GL_DEPTH_TEST = 2929;
    private static final int GL_STENCIL_TEST = 2960;
    private static final int GL_BLEND = 3042;
    private static final int GL_CULL_FACE = 2884;
    private static final int GL_COLOR_WRITEMASK = 2931;
    private static final int GL_DEPTH_WRITEMASK = 2930;
    private static final int GL_LEQUAL = 515;
    private static final int GL_DEPTH_FUNC = 2932;
    // 注意顺序（GL 规范）：
    //   FUNC=2962, VALUE_MASK=2963, FAIL=2964, ZFAIL=2965, ZPASS=2966, REF=2967, WRITEMASK=2968
    private static final int GL_STENCIL_FUNC = 2962;
    private static final int GL_STENCIL_VALUE_MASK = 2963;
    private static final int GL_STENCIL_FAIL = 2964;
    private static final int GL_STENCIL_PASS_DEPTH_FAIL = 2965;
    private static final int GL_STENCIL_PASS_DEPTH_PASS = 2966;
    private static final int GL_STENCIL_REF = 2967;
    private static final int GL_STENCIL_WRITEMASK = 2968;
    private static final int GL_ALWAYS = 519;
    private static final int GL_KEEP = 7680;

    private static final int INSPECT_EVERY = 5;    // glGet* 是同步查询，抽样（1/5 帧）

    private static int frames;
    private static String lastSignature = "";

    private GlStateSanitizer() {
    }

    /** 每帧在世界渲染前调用一次。 */
    public static void beforeWorldRender() {
        frames++;
        if (frames % INSPECT_EVERY == 0) {
            try {
                inspectAndReport();
            } catch (Throwable ignored) {
                // 日志都不许影响客户端
            }
        }
        try {
            apply();
        } catch (Throwable ignored) {
            // 复位失败也不能影响客户端
        }
    }

    /** 只在发现异常时打日志（每秒最多一次），用于定位到底哪一位被弄脏。 */
    private static void inspectAndReport() {
        boolean stencilOn = GL11.glIsEnabled(GL_STENCIL_TEST);
        boolean depthTest = GL11.glIsEnabled(GL_DEPTH_TEST);
        boolean blend = GL11.glIsEnabled(GL_BLEND);
        boolean cull = GL11.glIsEnabled(GL_CULL_FACE);
        boolean colorMask = GL11.glGetBoolean(GL_COLOR_WRITEMASK);
        boolean depthMask = GL11.glGetBoolean(GL_DEPTH_WRITEMASK);
        int depthFunc = GL11.glGetInteger(GL_DEPTH_FUNC);
        int stencilFunc = GL11.glGetInteger(GL_STENCIL_FUNC);
        int stencilRef = GL11.glGetInteger(GL_STENCIL_REF);
        int stencilValueMask = GL11.glGetInteger(GL_STENCIL_VALUE_MASK);
        int stencilWriteMask = GL11.glGetInteger(GL_STENCIL_WRITEMASK);
        int stencilFail = GL11.glGetInteger(GL_STENCIL_FAIL);
        int stencilPassDepthFail = GL11.glGetInteger(GL_STENCIL_PASS_DEPTH_FAIL);
        int stencilPassDepthPass = GL11.glGetInteger(GL_STENCIL_PASS_DEPTH_PASS);

        // depthTest 在帧首本来就可能是关的（GUI 阶段会关），不算异常。
        // 会让区块整片消失的是：stencil 被打开、颜色/深度写入被关、depthFunc 被改坏、
        // 或者 stencil 的 func/op/mask 被留在非中性值（后续有代码重新打开 stencil 就会中招）。
        // 注意：stencil test 关闭时，func/ref/mask/op 都是惰性的。
        // Botania 的 renderProgressPie 收尾会留下 stencilFunc=GL_EQUAL(514)/ref=1/writeMask=0/
        // stencilOp(GL_REPLACE,KEEP,KEEP)（实测值，与字节码完全一致，见 docs/upstream-issue-*.md），
        // 只要 test 是关的就不会影响任何绘制，所以不能当异常报（否则每次看祭坛都刷一条假警报）。
        boolean stencilDirty = stencilOn && (stencilFunc != GL_ALWAYS || stencilRef != 0
            || stencilValueMask != 255 || stencilWriteMask != 255
            || stencilFail != GL_KEEP || stencilPassDepthFail != GL_KEEP || stencilPassDepthPass != GL_KEEP);
        boolean suspicious = stencilOn || !colorMask || !depthMask || depthFunc != GL_LEQUAL || stencilDirty;
        if (!suspicious) {
            lastSignature = "";
            return;
        }
        String signature = stencilOn + "/" + depthTest + "/" + blend + "/" + cull + "/" + colorMask + "/"
            + depthMask + "/" + depthFunc + "/" + stencilFunc + "," + stencilRef + "," + stencilValueMask
            + "," + stencilWriteMask + "/" + stencilFail + "," + stencilPassDepthFail + "," + stencilPassDepthPass;
        if (signature.equals(lastSignature)) {
            return;   // 状态没变就不重复刷日志
        }
        lastSignature = signature;
        LOGGER.warn(
            "[cw_portal_guard] dirty GL state before world render (stencilTest={} depthTest={} blend={} cull={} colorMask={} depthMask={} depthFunc={} | stencilFunc={} ref={} valueMask={} writeMask={} | fail={} zfail={} zpass={}) - sanitizing",
            stencilOn, depthTest, blend, cull, colorMask, depthMask, depthFunc,
            stencilFunc, stencilRef, stencilValueMask, stencilWriteMask,
            stencilFail, stencilPassDepthFail, stencilPassDepthPass
        );
    }

    private static void apply() {
        RenderSystem.assertOnRenderThread();

        // 1) 颜色/深度写入：先设反再设正，强制真的下发 glColorMask / glDepthMask
        GlStateManager._colorMask(false, false, false, false);
        GlStateManager._colorMask(true, true, true, true);
        GlStateManager._depthMask(false);
        GlStateManager._depthMask(true);

        // 2) stencil：1.20.1 的 GlStateManager 没有 stencil-test 缓存
        //    （只有 _stencilFunc/_stencilOp/_stencilMask/_clearStencil），所以用裸 GL 关掉
        GL11.glDisable(GL_STENCIL_TEST);
        RenderSystem.stencilFunc(GL_ALWAYS, 0, 255);
        RenderSystem.stencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
        RenderSystem.stencilMask(255);

        // 3) 世界渲染依赖的基础状态
        GlStateManager._enableDepthTest();
        GlStateManager._depthFunc(GL_LEQUAL);
        GlStateManager._enableCull();
        GlStateManager._disableBlend();

        // 4) 全局着色器颜色乘子（HUD 代码常在这里留残值，会让后续绘制整体变黑）
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }
}

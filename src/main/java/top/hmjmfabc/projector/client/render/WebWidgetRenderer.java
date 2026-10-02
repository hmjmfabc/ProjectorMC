package top.hmjmfabc.projector.client.render;

import net.minecraft.resources.ResourceLocation;
import top.hmjmfabc.projector.client.font.FontManager;
import top.hmjmfabc.projector.client.font.TtfFont;
import top.hmjmfabc.projector.client.web.WebControls;
import top.hmjmfabc.projector.client.web.WebOverlayRenderer;
import top.hmjmfabc.projector.client.web.WebSessions;
import top.hmjmfabc.projector.common.Plane;
import top.hmjmfabc.projector.common.widget.Fonts;
import top.hmjmfabc.projector.common.widget.WebWidget;

/**
 * 网页控件的绘制（27.2，第 11 种控件）。
 *
 * <p>画法与<b>视频控件同源</b>：一块贴纹理的四边形（{@code RenderType.text}，
 * v=0 在图像顶部）+ 一个底色块。纹理来自 {@code WebSessions.textureFor(w)} ——
 * 它是「网页当前的画面」（由可选前置模组 MCEF 上传到 GL 纹理，再套一层
 * {@code AbstractTexture} 适配器注册进来）。</p>
 *
 * <p>纹理还没就绪时画<b>灰蓝占位块</b>（与视频「还没加载」同一个颜色
 * {@code 0x66808A96}）并在中间写一行状态，免得「什么都没画」被误判成渲染失败。</p>
 *
 * <p>控件底部那条按钮栏（⟳ ← → ⌂）由 {@link WebOverlayRenderer} 画，
 * 只在 {@link WebControls#visible(WebWidget)} 为真时出现（5 秒无操作自动隐藏）。</p>
 *
 * <p><b>不在这里做任何后端判断</b>：能拿到纹理就贴、拿不到就占位 + 状态文字，
 * 后端是否可用由 {@code WebSessions} 自己说清楚（它给的 status 就是给玩家看的）。</p>
 */
public final class WebWidgetRenderer {
    private WebWidgetRenderer() {
    }

    /** 「还没加载」的占位色（与视频控件那一档逐字相同）。 */
    private static final int PLACEHOLDER = 0x66808A96;

    /** 画一个网页控件（含画面与浮层）。 */
    public static void draw(QuadCollector collector, Plane plane, PlaneRenderContext ctx, WebWidget w) {
        if (w == null) {
            return;
        }
        if (!(Math.abs(w.w) > 1.0e-4 && Math.abs(w.h) > 1.0e-4)) {
            return;
        }
        drawBase(collector, ctx, w);
        // 浮层单独一层（WebOverlayRenderer 内部再按 0.015 往上错开），
        // 与视频控件「画面照旧、浮层叠加」是同一结构：下面的提前 return 不会吃掉按钮栏。
        if (WebControls.visible(w)) {
            WebOverlayRenderer.draw(collector, ctx, w);
        }
        // 页面光标（27.2）：与按钮栏**各自判显隐**（它的窗口是「最近 5 秒转发过输入」），
        // 所以不能塞进上面那个 if —— 关掉按钮栏也照样能点页面，光标得跟着出来。
        WebOverlayRenderer.drawCursor(collector, ctx, w);
    }

    /** 供浮层复用的「默认字体」（占位状态文字用的就是它；取不到就返回 null，调用方跳过绘制）。 */
    public static top.hmjmfabc.projector.client.font.TtfFont hintFont() {
        try {
            return top.hmjmfabc.projector.client.font.FontManager.get(Fonts.MINECRAFT_AE);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 网页画面本身（纹理 / 占位 + 状态文字）。 */
    private static void drawBase(QuadCollector collector, PlaneRenderContext ctx, WebWidget w) {
        // ① 控件底色：默认透明（不挡平面本身），玩家自己设了颜色才填
        if (w.background != 0) {
            solidRect(collector, ctx, w, w.x, w.y, w.x + w.w, w.y + w.h,
                    QuadCollector.withAlpha(w.background, w.alpha));
        }

        ResourceLocation texture = null;
        try {
            texture = WebSessions.textureFor(w);
        } catch (Throwable t) {
            // 网页会话出任何问题都不该让整帧崩掉，也不该静默：只报一次
            reportOnce(w, "会话查询失败：" + t);
            texture = null;
        }

        if (texture != null && QuadCollector.textureReady(texture)) {
            drawWebQuad(collector, ctx, w, texture);
            report(w, "就绪");
            return;
        }
        // 拿不到画面时才去问状态：status() 内部要过一次反射（可用性判定），
        // 顺利的那种情况（有画面）没必要每帧都问。
        String status = statusOf(w);
        // ② 还没画面：灰蓝占位 + 一行状态（未安装模组 / 初始化中 / 无帧超时…）
        solidRect(collector, ctx, w, w.x, w.y, w.x + w.w, w.y + w.h,
                QuadCollector.withAlpha(PLACEHOLDER, w.alpha));
        drawStatusLine(collector, ctx, w, status);
        report(w, status);
    }

    /** 状态那一行（拿不到就不画）：整段包 try/catch，状态字串不值得让整帧崩掉。 */
    private static String statusOf(WebWidget w) {
        try {
            String s = WebSessions.status(w);
            return s == null || s.isBlank() ? "网页尚未就绪" : s;
        } catch (Throwable t) {
            reportOnce(w, "状态查询失败：" + t);
            return "网页状态未知";
        }
    }

    /**
     * 把网页画面贴到控件方框里。
     *
     * <p>四个角与其它控件一样是 左下→右下→右上→左上；UV 取
     * {@link WebWidget#texU0()}/{@link WebWidget#texV0()}/{@link WebWidget#texU1()}/{@link WebWidget#texV1()}
     * —— <b>不要在这里写字面量</b>：这四个数同时决定「画面朝向」与「点击落在哪」
     * （{@code WebInput.pageX/pageY} 由同一组常量算出来），是本项目唯一一处可以
     * 一句话调转网页 180° 的开关。</p>
     *
     * <p><b>2026-10-02 真机结论（rc-139 改回）</b>：pre-133~138 期间这里传的是
     * {@code (1,1,0,0)}（两轴都翻），玩家报告<b>墙面上网页整个倒过来</b> ——
     * 同一面墙上的文字控件是正的，说明那段时间的「翻」是多余的。
     * 现在保持 {@code (0,0,1,1)}，与图片/视频控件逐字一致。</p>
     *
     * <p>⚠ <b>只翻一个轴会变成左右镜像</b>（那是 180° 再翻一个轴的结果）；
     * 而「红点落点」这类自检<b>验不出朝向</b> —— 渲染与点击一起翻时红点照样落在点上，
     * 必须靠「同一面墙上网页里的字和文字控件的字哪一头朝上」来判断。</p>
     */
    private static void drawWebQuad(QuadCollector collector, PlaneRenderContext ctx,
                                    WebWidget w, ResourceLocation texture) {
        collector.setRenderType(QuadCollector.imageType(texture));
        int tint = QuadCollector.withAlpha(0xFFFFFFFF, w.alpha);
        double[] p0 = TextRenderer.rot(w.x, w.y, w.rot, 0, 0);
        double[] p1 = TextRenderer.rot(w.x, w.y, w.rot, w.w, 0);
        double[] p2 = TextRenderer.rot(w.x, w.y, w.rot, w.w, w.h);
        double[] p3 = TextRenderer.rot(w.x, w.y, w.rot, 0, w.h);
        collector.canvasQuad(ctx.axisX(), ctx.axisY(), ctx.normal(), ctx.origin(),
                p0[0], p0[1], p1[0], p1[1], p2[0], p2[1], p3[0], p3[1],
                ctx.depth(), WebWidget.texU0(), WebWidget.texV0(), WebWidget.texU1(), WebWidget.texV1(), tint);
    }

    /**
     * 占位块中间那一行状态文字。
     *
     * <p>字体必须用内置的 Minecraft AE：状态里会出现中文（「未安装」「加载中」…），
     * 而 Caviar Dreams 不含任何中文字形，用它只会得到一排方框。</p>
     */
    private static void drawStatusLine(QuadCollector collector, PlaneRenderContext ctx,
                                       WebWidget w, String status) {
        TtfFont font = FontManager.get(Fonts.MINECRAFT_AE);
        if (font == null) {
            font = FontManager.get(null);
        }
        if (font == null) {
            return;
        }
        // 字号随控件大小走，但不能小到看不清
        double size = Math.max(3.0, Math.min(w.h * 0.16, w.w * 0.10));
        double lineH = size * 1.35;
        double top = w.y + Math.max(0, (w.h - lineH) * 0.5);
        TextRenderer.drawInBox(collector, ctx, font, "&7" + status, size, w.w, 1.15, 1, 1,
                w.x, top, w.w, lineH, w.rot, w.alpha, ctx.depth(), w.x, w.y);
    }

    private static void solidRect(QuadCollector collector, PlaneRenderContext ctx, WebWidget w,
                                  double x0, double y0, double x1, double y1, int argb) {
        if ((argb >>> 24) == 0 || Math.abs(x1 - x0) < 1.0e-4 || Math.abs(y1 - y0) < 1.0e-4) {
            return;
        }
        double[] p0 = TextRenderer.rot(w.x, w.y, w.rot, x0 - w.x, y0 - w.y);
        double[] p1 = TextRenderer.rot(w.x, w.y, w.rot, x1 - w.x, y0 - w.y);
        double[] p2 = TextRenderer.rot(w.x, w.y, w.rot, x1 - w.x, y1 - w.y);
        double[] p3 = TextRenderer.rot(w.x, w.y, w.rot, x0 - w.x, y1 - w.y);
        collector.setRenderType(QuadCollector.imageType(WidgetRenderer.WHITE));
        collector.canvasQuad(ctx.axisX(), ctx.axisY(), ctx.normal(), ctx.origin(),
                p0[0], p0[1], p1[0], p1[1], p2[0], p2[1], p3[0], p3[1],
                ctx.depth(), 0f, 0f, 1f, 1f, argb);
    }

    // ------------------------------------------------------------------
    // 诊断：每个控件只在状态变化时打一行（跟着「登记」走，不跟「每帧」走）
    // ------------------------------------------------------------------

    private static final java.util.Map<java.util.UUID, String> REPORT = new java.util.HashMap<>();

    private static void report(WebWidget w, String state) {
        if (w == null || w.id == null) {
            return;
        }
        String line = String.format(java.util.Locale.ROOT,
                "网页控件: 地址=%s 框=%.1fx%.1f 单位(%.2fx%.2f 格) 密度=%.1f 状态=%s",
                w.displayUrl(), w.w, w.h, w.w / 16, w.h / 16, w.pixelPerUnit, state);
        if (!line.equals(REPORT.put(w.id, line))) {
            top.hmjmfabc.projector.Projector.LOGGER.info("[Projector][网页] {}", line);
        }
    }

    /** 异常类只报一次（每个控件一次），避免同一个坏会话每帧刷屏。 */
    private static final java.util.Set<java.util.UUID> ERROR_LOGGED =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private static void reportOnce(WebWidget w, String message) {
        if (w == null || w.id == null || !ERROR_LOGGED.add(w.id)) {
            return;
        }
        top.hmjmfabc.projector.Projector.LOGGER.warn(
                "[Projector][网页] 控件 {} {}", w.id.toString().substring(0, 8), message);
    }
}

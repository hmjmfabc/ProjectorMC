package top.hmjmfabc.projector.client.web;

import top.hmjmfabc.projector.client.font.FontManager;
import top.hmjmfabc.projector.client.font.TtfFont;
import top.hmjmfabc.projector.client.render.PlaneRenderContext;
import top.hmjmfabc.projector.client.render.QuadCollector;
import top.hmjmfabc.projector.client.render.TextRenderer;
import top.hmjmfabc.projector.client.render.WidgetRenderer;
import top.hmjmfabc.projector.common.widget.WebWidget;

/**
 * 网页控件的世界内按钮栏（⟳ 刷新 / ← 后退 / → 前进 / ⌂ 主页）。
 *
 * <p><b>视觉语言与音乐控件、视频控件的浮层逐字一致</b>：白色细空心圆环 +
 * 白色图形，圆环线宽 {@code Math.max(0.22, radius * 0.09)}，同一控件内每层沿外法线
 * 偏 {@link #LAYER}（0.015）格避免共面 Z-fighting。</p>
 *
 * <p>几何<b>只</b>取自 {@link WebWidget#buttonBox(int)} / {@link WebWidget#barHeight()}，
 * 与世界里点击判定（{@code WebWidget.hitButton}）共用同一份来源 ——
 * 否则就会出现本项目的经典 bug「看得见点不着」。</p>
 *
 * <p><b>为什么不直接调 {@code MusicWidgetRenderer} 的那几个原语？</b>
 * 它们的参数类型写死成 {@code MusicWidget}（用 {@code w.x/w.y/w.rot} 当旋转轴心）。
 * 音乐控件那套已经实测通过，不去动它；这里放一份**通用版**：入参是显式的
 * 「画布变换（轴心 + 旋转角）+ 画布坐标」，与控件类型无关。</p>
 */
public final class WebOverlayRenderer {
    private WebOverlayRenderer() {
    }

    /** 同控件内的层间距（方块）。⚠ 必须 ≥0.015，否则共面层会互相闪烁（老规矩）。 */
    private static final double LAYER = 0.015;

    /** 白色（与音乐控件同色）。 */
    private static final int WHITE = 0xFFFFFFFF;

    /**
     * 一块画布的变换：旋转轴心 {@code (ax, ay)}（画布坐标）+ 旋转角（度，逆时针）。
     *
     * <p>本项目所有控件都是「绕锚点（左下角）旋转」的，所以原语拿到的是画布坐标，
     * 先减轴心再旋转。参数类型里<b>没有</b>控件，因此对任何控件类型都能用。</p>
     */
    public record Frame(double ax, double ay, double rot) {
        /** 从任意控件取它的画布变换（锚点 + 旋转角）。 */
        public static Frame of(WebWidget w) {
            return new Frame(w.x, w.y, w.rot);
        }
    }

    /** 画整条按钮栏（调用方已确认此刻该显示它）。 */
    public static void draw(QuadCollector collector, PlaneRenderContext ctx, WebWidget w) {
        if (w == null || w.w <= 1 || w.h <= 1) {
            return;
        }
        if (!w.showControls) {
            return;
        }
        // 【rc-139 朝向契约】按钮栏的几何全部在**控件局部坐标**里（{@code buttonBox} 的
        // y = pad ⇒ 贴控件底边，x 从左到右），而网页画面现在也是「左上角=页面左上角」，
        // 所以按钮栏正好压在网页的**底部**、左右顺序也与网页一致 ——
        // 网页整体调转 180° 时**它不需要跟着翻**（翻了反而会跑到网页顶上去）。
        // 点击判定 {@code WebWidget.hitButton} 用的也是同一份局部几何，两边不会分叉。
        Frame f = Frame.of(w);
        PlaneRenderContext ringCtx = layer(ctx, 1);
        PlaneRenderContext glyphCtx = layer(ctx, 2);
        int white = QuadCollector.withAlpha(WHITE, w.alpha);
        // ★【玩家 2026-10-02 要求】**图形用黑色、圈仍白色**：
        //   网页大多是白底，白色图形糊在白底上根本看不见；圈是细空心环，
        //   白圈在浅底上仍然认得出（而且与音乐/视频控件的观感一致）。
        int glyphColor = QuadCollector.withAlpha(0xFF000000, w.alpha);

        for (int i = 0; i < WebWidget.BUTTON_COUNT; i++) {
            double[] b = canvasBox(w, i);
            double size = Math.min(b[2] - b[0], b[3] - b[1]);
            double cx = (b[0] + b[2]) / 2.0;
            double cy = (b[1] + b[3]) / 2.0;
            double radius = size / 2.0;
            // 【用户要求】圈要细：与音乐/视频控件的播放键同一配方（逐字一致）
            double ringWidth = Math.max(0.22, radius * 0.09);
            ring(collector, ringCtx, f, cx, cy, radius, ringWidth, white);
            glyph(collector, glyphCtx, f, i, cx, cy, size, glyphColor);
        }
    }

    /** 第 {@code i} 个按钮的画布方框 {@code {x0,y0,x1,y1}}（局部几何 + 锚点）。 */
    private static double[] canvasBox(WebWidget w, int i) {
        double[] b = w.buttonBox(i);
        return new double[]{w.x + b[0], w.y + b[1], w.x + b[0] + b[2], w.y + b[1] + b[3]};
    }

    // ------------------------------------------------------------------
    // 页面光标（27.2）：世界里没有鼠标指针，用一个小圆环告诉玩家「页面认为鼠标在哪」
    // ------------------------------------------------------------------

    /**
     * 画「页面光标」——最近一次转发给页面的悬停位置（控件局部坐标）。
     *
     * <p><b>与按钮栏不是同一件事，所以不共用显隐判据</b>：按钮栏要
     * {@code showControls} + 「我点过它」+ 5 秒窗口；而页面光标只要「最近 5 秒里
     * 转发过悬停/点击」就画（{@code WebInput.cursor} 自己判窗口，
     * 关掉按钮栏也照样能点页面，光标得跟着出来）。</p>
     *
     * <p>外观与音乐/视频控件同一套语言：白色细空心圆环，环宽
     * {@code max(0.22, r*0.09)}（<b>环宽公式没动</b>），再往上一层
     * （{@link #LAYER} = 0.015 格）避免共面闪烁。
     * 半径取控件高度的 <b>2%</b>（{@code WebInput.cursorRadius}；用户 2026-10-02 口径：
     * 上一版的 4% 太大、挡住内容）；点中的一瞬间会收缩到
     * {@code WebInput.CLICK_SHRINK}（「点到了」的反馈）。</p>
     */
    public static void drawCursor(QuadCollector collector, PlaneRenderContext ctx, WebWidget w) {
        if (w == null || w.w <= 1 || w.h <= 1) {
            return;
        }
        WebInput.CursorAt c = WebInput.cursor(w);
        if (c == null) {
            return;
        }
        Frame f = Frame.of(w);
        double r = WebInput.cursorRadius(w) * c.scale();
        if (!(r > 0)) {
            return;
        }
        // 光标是本控件内的第 3 层：按钮栏占 1（圆环）与 2（图形），别再撞层
        ring(collector, layer(ctx, 3), f, w.x + c.localX(), w.y + c.localY(),
                r, Math.max(0.22, r * 0.09), QuadCollector.withAlpha(WHITE, w.alpha));
        drawHint(collector, ctx, f, w);
    }

    /**
     * <b>世界内的一行操作提示</b>（玩家 2026-10-02 要求：「请在合适的位置提醒玩家按 I 输入」）。
     *
     * <p>位置：<b>控件正下方紧挨着</b>（光标环出现时才有 —— 也就是玩家正在瞄它/刚点过它的时候，
     * 不打扰平时看画面）。文案：<code>左键操作 · 按 I 打字</code>（两份语言都有）。</p>
     *
     * <p>画在光标同一层（第 3 层）之上再抬一档，字大小取控件高度的 5%（下限 4、上限 9 画布单位），
     * 这样大控件上不至于糊成一片、小控件上也看得见。</p>
     */
    private static void drawHint(QuadCollector collector, PlaneRenderContext ctx, Frame f, WebWidget w) {
        TtfFont font = top.hmjmfabc.projector.client.render.WebWidgetRenderer.hintFont();
        if (font == null) {
            return;
        }
        String text = plainLang("projector.msg.web_world_hint", "\u5de6\u952e\u64cd\u4f5c");
        if (text.isEmpty()) {
            return;
        }
        // ★【玩家 2026-10-02 两条要求】
        //   ① **不许盖住网页内容** ⇒ 画在控件**外面**（下沿之下，与上一版同一个位置）；
        //   ② 这行字要**跟着网页的朝向走**（网页倒着的时候它也跟着倒）。
        // ⚠【rc-139 改回 rot+0】网页画面已经翻正（见 WebWidget.texU0() 的实测记录），
        //   所以这行提示也必须回到正常朝向 —— 否则玩家会看到「网页是正的、提示是倒的」，
        //   正是这两种东西必须同源的原因（玩家原话：提示信息也要跟着转）。
        // 旋转用「方框中心」当轴（不是控件锚点）—— 否则 180° 会把整行甩到控件另一侧去。
        double size = Math.max(3.0, Math.min(w.h * 0.10, w.w * 0.06));
        double lineH = size * 1.6;
        double pad = size * 0.9;
        double boxW = w.w;
        double boxY = w.y - pad - lineH;
        int color = QuadCollector.withAlpha(0xFFFFFFFF, w.alpha);
        TextRenderer.drawInBox(collector, ctx, font, text, size, -1, 1.0, 0, 1,
                w.x, boxY, boxW, lineH,
                w.rot, w.alpha, ctx.depth(),
                w.x + boxW / 2.0, boxY + lineH / 2.0);
    }

    /** 语言文件里的句子（取不到就用兜底）。 */
    private static String plainLang(String key, String fallback) {
        try {
            return net.minecraft.network.chat.Component.translatable(key).getString();
        } catch (Throwable t) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------
    // 四个图形
    // ------------------------------------------------------------------

    /**
     * 第 {@code i} 个按钮里的图形。
     *
     * <p>图形全部用「胶囊 / 圆角三角 / 圆环」拼出来（引擎没有现成的图标 API），
     * 与播放键的三角、暂停的竖杠是同一套观感。</p>
     */
    private static void glyph(QuadCollector collector, PlaneRenderContext ctx, Frame f,
                              int index, double cx, double cy, double s, int argb) {
        switch (index) {
            case WebWidget.BTN_BACK -> arrow(collector, ctx, f, cx, cy, s, argb, -1);
            case WebWidget.BTN_FORWARD -> arrow(collector, ctx, f, cx, cy, s, argb, 1);
            case WebWidget.BTN_REFRESH -> refresh(collector, ctx, f, cx, cy, s, argb);
            case WebWidget.BTN_HOME -> home(collector, ctx, f, cx, cy, s, argb);
            default -> {
            }
        }
    }

    /** ← / →：一根圆头横杠 + 一个圆角三角头（{@code dir = -1} 指左，{@code 1} 指右）。 */
    private static void arrow(QuadCollector collector, PlaneRenderContext ctx, Frame f,
                              double cx, double cy, double s, int argb, int dir) {
        double shaft = s * 0.34;                 // 横杠半长
        double half = s * 0.055;                 // 横杠半高
        double tailX = cx - dir * shaft;
        double headX = cx + dir * shaft * 0.55;
        roundedRect(collector, ctx, f, Math.min(tailX, headX), cy - half,
                Math.max(tailX, headX), cy + half, half, argb);
        // 三角头：底边贴着横杠，尖端朝 dir
        double tipX = cx + dir * s * 0.34;
        double baseX = cx + dir * s * 0.02;
        double wing = s * 0.21;
        double[][] tri = {
                {baseX, cy - wing},
                {baseX, cy + wing},
                {tipX, cy},
        };
        roundPolygon(collector, ctx, f, tri, s * 0.045, argb);
    }

    /**
     * ⟳：一段圆弧 + 一个切向的箭头。
     *
     * <p>圆弧走 260°，缺口留在右上，箭头画在圆弧的起点 —— 一眼能认出是「刷新」。</p>
     */
    private static void refresh(QuadCollector collector, PlaneRenderContext ctx, Frame f,
                                double cx, double cy, double s, int argb) {
        double r = s * 0.28;
        double thickness = Math.max(0.18, s * 0.075);
        double a0 = Math.toRadians(-50);
        double sweep = Math.toRadians(260);
        arc(collector, ctx, f, cx, cy, r, thickness, a0, sweep, argb);

        // 箭头：沿圆弧在起点处的切线方向（逆时针），底边垂直于半径
        double px = cx + Math.cos(a0) * r;
        double py = cy + Math.sin(a0) * r;
        double tx = -Math.sin(a0);
        double ty = Math.cos(a0);
        double rx = Math.cos(a0);
        double ry = Math.sin(a0);
        double len = s * 0.30;
        double wing = s * 0.17;
        double[][] head = {
                {px + rx * wing, py + ry * wing},
                {px - rx * wing, py - ry * wing},
                {px + tx * len, py + ty * len},
        };
        roundPolygon(collector, ctx, f, head, s * 0.03, argb);
    }

    /** ⌂：圆角三角屋顶 + 方房子主体。 */
    private static void home(QuadCollector collector, PlaneRenderContext ctx, Frame f,
                             double cx, double cy, double s, int argb) {
        double[][] roof = {
                {cx - s * 0.33, cy + s * 0.03},
                {cx + s * 0.33, cy + s * 0.03},
                {cx, cy + s * 0.30},
        };
        roundPolygon(collector, ctx, f, roof, s * 0.045, argb);
        double bx = s * 0.21;
        roundedRect(collector, ctx, f, cx - bx, cy - s * 0.26, cx + bx, cy + s * 0.06,
                s * 0.035, argb);
    }

    // ------------------------------------------------------------------
    // 通用原语（音乐控件那些原语的「不绑类型」版本）
    // ------------------------------------------------------------------

    /** 同一控件内的第 n 层：沿外法线错开 n × {@link #LAYER} 方块，避免共面闪烁。 */
    private static PlaneRenderContext layer(PlaneRenderContext ctx, int n) {
        if (n == 0) {
            return ctx;
        }
        return new PlaneRenderContext(ctx.axisX(), ctx.axisY(), ctx.normal(), ctx.origin(),
                ctx.depth() + n * LAYER);
    }

    /** 画布坐标 → 世界（先绕轴心逆旋转，再由 {@code canvasQuad} 做唯一的单位换算）。 */
    private static double[] pt(Frame f, double x, double y) {
        return TextRenderer.rot(f.ax(), f.ay(), f.rot(), x - f.ax(), y - f.ay());
    }

    /** 轴对齐矩形（随控件旋转）。 */
    static void rect(QuadCollector collector, PlaneRenderContext ctx, Frame f,
                     double x0, double y0, double x1, double y1, int argb) {
        if ((argb >>> 24) == 0 || Math.abs(x1 - x0) < 1.0e-4 || Math.abs(y1 - y0) < 1.0e-4) {
            return;
        }
        quad(collector, ctx, f, x0, y0, x1, y0, x1, y1, x0, y1, argb);
    }

    /** 三角形（第三、四点重合的退化四边形）。 */
    static void triangle(QuadCollector collector, PlaneRenderContext ctx, Frame f,
                         double ax, double ay, double bx, double by, double cx, double cy, int argb) {
        if ((argb >>> 24) == 0) {
            return;
        }
        double[] pa = pt(f, ax, ay);
        double[] pb = pt(f, bx, by);
        double[] pc = pt(f, cx, cy);
        collector.setRenderType(QuadCollector.imageType(WidgetRenderer.WHITE));
        collector.canvasQuad(ctx.axisX(), ctx.axisY(), ctx.normal(), ctx.origin(),
                pa[0], pa[1], pb[0], pb[1], pc[0], pc[1], pc[0], pc[1],
                ctx.depth(), 0f, 0f, 1f, 1f, argb);
    }

    /** 任意四边形（按 a→b→c→d 顺序）。 */
    static void quad(QuadCollector collector, PlaneRenderContext ctx, Frame f,
                     double ax, double ay, double bx, double by, double cx, double cy,
                     double dx, double dy, int argb) {
        if ((argb >>> 24) == 0) {
            return;
        }
        double[] pa = pt(f, ax, ay);
        double[] pb = pt(f, bx, by);
        double[] pc = pt(f, cx, cy);
        double[] pd = pt(f, dx, dy);
        collector.setRenderType(QuadCollector.imageType(WidgetRenderer.WHITE));
        collector.canvasQuad(ctx.axisX(), ctx.axisY(), ctx.normal(), ctx.origin(),
                pa[0], pa[1], pb[0], pb[1], pc[0], pc[1], pd[0], pd[1],
                ctx.depth(), 0f, 0f, 1f, 1f, argb);
    }

    /** 整圈空心圆环（白色细圈）。 */
    static void ring(QuadCollector collector, PlaneRenderContext ctx, Frame f,
                     double cx, double cy, double r, double thickness, int argb) {
        arc(collector, ctx, f, cx, cy, r, thickness, 0, Math.PI * 2, argb);
    }

    /** 一段圆环（{@code a0} 起，逆时针扫 {@code sweep}）。段数与圆环一致，接口才看得出来是同一个圈。 */
    static void arc(QuadCollector collector, PlaneRenderContext ctx, Frame f,
                    double cx, double cy, double r, double thickness,
                    double a0, double sweep, int argb) {
        if ((argb >>> 24) == 0 || r <= 0.1) {
            return;
        }
        int segments = Math.max(4, (int) Math.ceil(28 * Math.abs(sweep) / (Math.PI * 2)));
        double inner = Math.max(0.0, r - thickness);
        for (int i = 0; i < segments; i++) {
            double s0 = a0 + sweep * i / segments;
            double s1 = a0 + sweep * (i + 1) / segments;
            quad(collector, ctx, f,
                    cx + Math.cos(s0) * inner, cy + Math.sin(s0) * inner,
                    cx + Math.cos(s0) * r, cy + Math.sin(s0) * r,
                    cx + Math.cos(s1) * r, cy + Math.sin(s1) * r,
                    cx + Math.cos(s1) * inner, cy + Math.sin(s1) * inner, argb);
        }
    }

    /** 圆角矩形（实心）。 */
    static void roundedRect(QuadCollector collector, PlaneRenderContext ctx, Frame f,
                            double x0, double y0, double x1, double y1, double r, int argb) {
        if (r <= 0.4) {
            rect(collector, ctx, f, x0, y0, x1, y1, argb);
            return;
        }
        double rr = Math.min(r, Math.min(Math.abs(x1 - x0), Math.abs(y1 - y0)) * 0.5);
        rect(collector, ctx, f, x0 + rr, y0, x1 - rr, y1, argb);
        rect(collector, ctx, f, x0, y0 + rr, x0 + rr, y1 - rr, argb);
        rect(collector, ctx, f, x1 - rr, y0 + rr, x1, y1 - rr, argb);
        // 四个角：扇形三角（与音乐控件的圆角矩形同一做法）
        double[][] corners = {
                {x0 + rr, y0 + rr, Math.PI},            // 左下
                {x1 - rr, y0 + rr, Math.PI * 1.5},      // 右下
                {x1 - rr, y1 - rr, 0},                  // 右上
                {x0 + rr, y1 - rr, Math.PI * 0.5},      // 左上
        };
        for (double[] c : corners) {
            for (int i = 0; i < 8; i++) {
                double t0 = c[2] + Math.PI * 0.5 * i / 8;
                double t1 = c[2] + Math.PI * 0.5 * (i + 1) / 8;
                triangle(collector, ctx, f, c[0], c[1],
                        c[0] + Math.cos(t0) * rr, c[1] + Math.sin(t0) * rr,
                        c[0] + Math.cos(t1) * rr, c[1] + Math.sin(t1) * rr, argb);
            }
        }
    }

    /**
     * 圆角多边形（凸多边形）——与 {@code MusicWidgetRenderer.roundPolygon} 同一算法：
     * 每条边按半径内缩 + 每个角用圆弧过渡，最后以质心为扇心三角化。
     */
    static void roundPolygon(QuadCollector collector, PlaneRenderContext ctx, Frame f,
                             double[][] pts, double radius, int argb) {
        int n = pts.length;
        if (n < 3) {
            return;
        }
        int arcSteps = 5;
        java.util.List<double[]> path = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            double[] prev = pts[(i - 1 + n) % n];
            double[] cur = pts[i];
            double[] next = pts[(i + 1) % n];
            double ux = prev[0] - cur[0];
            double uy = prev[1] - cur[1];
            double vx = next[0] - cur[0];
            double vy = next[1] - cur[1];
            double lu = Math.max(1e-6, Math.hypot(ux, uy));
            double lv = Math.max(1e-6, Math.hypot(vx, vy));
            ux /= lu;
            uy /= lu;
            vx /= lv;
            vy /= lv;
            double cos = Math.max(-1.0, Math.min(1.0, ux * vx + uy * vy));
            double half = Math.acos(cos) / 2.0;
            double r = Math.min(radius, Math.min(lu, lv) * 0.5);
            if (r <= 0.01 || Math.sin(half) < 1e-4) {
                path.add(new double[]{cur[0], cur[1]});
                continue;
            }
            double dist = r / Math.sin(half);
            double bx = ux + vx;
            double by = uy + vy;
            double lb = Math.max(1e-6, Math.hypot(bx, by));
            double ccx = cur[0] + bx / lb * dist;
            double ccy = cur[1] + by / lb * dist;
            double a1 = Math.atan2(uy, ux);
            double a2 = Math.atan2(vy, vx);
            double delta = a2 - a1;
            while (delta > Math.PI) {
                delta -= Math.PI * 2;
            }
            while (delta < -Math.PI) {
                delta += Math.PI * 2;
            }
            for (int k = 0; k <= arcSteps; k++) {
                double a = a1 + delta * k / (double) arcSteps;
                path.add(new double[]{ccx + Math.cos(a) * r, ccy + Math.sin(a) * r});
            }
        }
        double gx = 0;
        double gy = 0;
        for (double[] p : path) {
            gx += p[0];
            gy += p[1];
        }
        gx /= path.size();
        gy /= path.size();
        for (int i = 0; i < path.size(); i++) {
            double[] a = path.get(i);
            double[] b = path.get((i + 1) % path.size());
            triangle(collector, ctx, f, gx, gy, a[0], a[1], b[0], b[1], argb);
        }
    }
}

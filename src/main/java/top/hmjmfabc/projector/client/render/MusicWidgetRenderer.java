package top.hmjmfabc.projector.client.render;

import top.hmjmfabc.projector.client.font.FontManager;
import top.hmjmfabc.projector.common.text.TextLayout;
import top.hmjmfabc.projector.client.font.TtfFont;
import top.hmjmfabc.projector.client.music.LyricRecord;
import top.hmjmfabc.projector.client.music.MusicEnvelope;
import top.hmjmfabc.projector.client.music.MusicManager;
import top.hmjmfabc.projector.client.music.MusicPlayer;
import top.hmjmfabc.projector.common.Plane;
import top.hmjmfabc.projector.common.widget.MusicWidget;

/**
 * 音乐控件的绘制：圆角矩形 + 播放键 + 长条波形 + 文字 + 时间。
 *
 * <p>这里的几何全部来自 {@link MusicWidget} 自己的
 * {@code buttonBox()/waveBox()/timeBox()}，世界里点击播放键用的是同一份，
 * 所以「画在哪」和「点在哪」永远一致。</p>
 *
 * <p>圆角是自己拼的（引擎没有现成 API）：中间一块大矩形 + 左右两块 +
 * 四个角的扇形三角。三角用「退化四边形」（第三、四点重合）提交 —— 顶点格式不变，
 * 依然走本项目唯一可靠的 {@code RenderType.text} 纹理管线。</p>
 */
public final class MusicWidgetRenderer {
    /** 圆角的分段数（每段一个三角形）。 */
    private static final int CORNER_SEGMENTS = 10;
    /**
     * 同一控件内部各层之间沿法线的错开量（方块）。
     *
     * <p>⚠ 必须 ≥0.015：底色 / 边框 / 波形 / 播放键 / 文字是**共面**的，
     * 深度值一样时它们会互相闪烁（玩家反馈的「重叠闪烁」就是这个）。
     * 这是本项目的老规矩（棋盘也是 0.015）。</p>
     */
    private static final double LAYER = 0.015;

    private MusicWidgetRenderer() {
    }

    public static void draw(QuadCollector collector, Plane plane, PlaneRenderContext ctx, MusicWidget w) {
        if (!(Math.abs(w.w) > 1.0e-4 && Math.abs(w.h) > 1.0e-4)) {
            return;
        }
        long gameTime = WidgetRenderer.currentGameTime();
        double corner = Math.max(0, Math.min(w.corner, Math.min(w.w, w.h) * 0.5));
        double x0 = w.x;
        double y0 = w.y;
        double x1 = w.x + w.w;
        double y1 = w.y + w.h;

        // 各层沿法线依次错开，避免共面闪烁（底色 → 边框 → 波形 → 播放键 → 文字）
        PlaneRenderContext bgCtx = layer(ctx, 0);
        PlaneRenderContext borderCtx = layer(ctx, 1);
        PlaneRenderContext waveCtx = layer(ctx, 2);
        PlaneRenderContext btnCtx = layer(ctx, 3);
        PlaneRenderContext textCtx = layer(ctx, 4);

        // ① 圆角矩形底：默认**不画**（用户不要黑底），只有玩家自己设了颜色才填
        if (w.background != 0) {
            roundedRect(collector, bgCtx, w, x0, y0, x1, y1, corner,
                    QuadCollector.withAlpha(w.background, w.alpha));
        }

        // ② 圆角边框（【27.2-pre-136】颜色独立：调色盘可以只改边框，不动文字/进度色）
        double border = Math.max(0.45, Math.min(w.h * 0.055, 1.0));
        roundedRing(collector, borderCtx, w, x0, y0, x1, y1, corner, border,
                QuadCollector.withAlpha(w.borderColor, w.alpha));

        // ③ 播放键
        drawButton(collector, btnCtx, w, gameTime);

        // ④ 波形 + 文字（上半行放文字、下半行放竖条；太扁时只画竖条）
        double[] mid = offset(w, w.waveBox());
        double inner = Math.max(2.0, (mid[3] - mid[1]));
        double textH = Math.min(w.fontSize * 1.35, inner * 0.55);
        boolean bars = inner - textH >= 3.0;
        if (bars) {
            drawWave(collector, waveCtx, w, gameTime, mid[0], mid[1], mid[2], mid[3] - textH - 0.3);
            drawTitle(collector, plane, textCtx, w, gameTime, mid[0], mid[3] - textH, mid[2], mid[3]);
        } else {
            drawWave(collector, waveCtx, w, gameTime, mid[0], mid[1], mid[2], mid[3]);
            drawTitle(collector, plane, textCtx, w, gameTime, mid[0], mid[1], mid[2], mid[3]);
        }

        // ⑤ 时间
        drawTime(collector, textCtx, w, gameTime);
    }

    // ------------------------------------------------------------------ 播放键

    private static void drawButton(QuadCollector collector, PlaneRenderContext ctx, MusicWidget w,
                                   long gameTime) {
        double[] box = offset(w, w.buttonBox());
        double size = box[2] - box[0];
        double cx = (box[0] + box[2]) / 2;
        double cy = (box[1] + box[3]) / 2;
        // 【用户要求】播放键 = **白色空心圆**（细边），图形用进度色画得圆润一些
        double radius = size / 2;
        double ringWidth = Math.max(0.22, radius * 0.09);   // 用户要求：圈再细一点
        ring(collector, ctx, w, cx, cy, radius, ringWidth,
                QuadCollector.withAlpha(w.textColor, w.alpha));

        // 图形与圆圈同色（白色）
        int glyph = QuadCollector.withAlpha(w.textColor, w.alpha);
        boolean playing = w.playing && !w.finishedAt(gameTime);
        if (playing) {
            // 两根圆头竖杠（胶囊）
            double barW = size * 0.15;
            double barH = size * 0.40;
            double gap = size * 0.14;
            double r = barW / 2;
            roundedRect(collector, ctx, w, cx - gap / 2 - barW, cy - barH / 2,
                    cx - gap / 2, cy + barH / 2, r, glyph);
            roundedRect(collector, ctx, w, cx + gap / 2, cy - barH / 2,
                    cx + gap / 2 + barW, cy + barH / 2, r, glyph);
        } else {
            // 圆角三角形：等边三角（视觉重心居中）三边圆角
            double s3 = size * 0.34;                     // 外接圆半径
            double[][] tri = {
                    {cx - s3 * 0.52, cy - s3 * 0.92},
                    {cx - s3 * 0.52, cy + s3 * 0.92},
                    {cx + s3 * 1.00, cy},
            };
            roundPolygon(collector, ctx, w, tri, size * 0.055, glyph);
        }
    }

    private static double luminance(int argb) {
        double r = ((argb >> 16) & 0xFF) / 255.0;
        double g = ((argb >> 8) & 0xFF) / 255.0;
        double b = (argb & 0xFF) / 255.0;
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    // ------------------------------------------------------------------ 波形

    private static void drawWave(QuadCollector collector, PlaneRenderContext ctx, MusicWidget w,
                                 long gameTime, double bx0, double by0, double bx1, double by1) {
        double width = bx1 - bx0;
        double height = by1 - by0;
        if (width <= 1.0 || height <= 1.0) {
            return;
        }
        MusicEnvelope envelope = MusicManager.envelopeFor(w);
        int bars = w.effectiveBarCount();
        double slot = width / bars;
        double barW = Math.max(0.35, Math.min(slot * 0.62, 1.4));
        long duration = w.effectiveDurationMs();
        long position = w.positionAt(gameTime);
        int playedColor = QuadCollector.withAlpha(w.accentColor, w.alpha);
        int restColor = QuadCollector.withAlpha(w.waveColor, w.alpha);

        for (int i = 0; i < bars; i++) {
            double at = (i + 0.5) / bars * duration;
            int amp = envelope == null ? 40 : envelope.valueAt((long) at);
            double barH = Math.max(0.7, height * amp / 100.0);
            double cx = bx0 + i * slot + (slot - barW) / 2;
            double cy = (by0 + by1) / 2;
            rect(collector, ctx, w, cx, cy - barH / 2, cx + barW, cy + barH / 2,
                    at <= position ? playedColor : restColor);
        }
    }

    // ------------------------------------------------------------------ 文字

    private static void drawTitle(QuadCollector collector, Plane plane, PlaneRenderContext ctx,
                                  MusicWidget w, long gameTime,
                                  double tx0, double ty0, double tx1, double ty1) {
        String text = titleText(plane, w, gameTime);
        if (text.isEmpty() || tx1 - tx0 < 2.0) {
            return;
        }
        TtfFont font = FontManager.get(w.fontId);
        // 单行显示：放不下就直接省略号截断（用户要求「只显示前面的字」）
        double size = w.fontSize;
        String oneLine = fitOneLine(font, text, size, tx1 - tx0);
        TextRenderer.drawInBox(collector, ctx, font, oneLine, size, -1, 1.0,
                0, 1, tx0, ty0, tx1 - tx0, ty1 - ty0, w.rot, w.alpha, ctx.depth(),
                w.x, w.y);
    }

    /**
     * 把文字裁成「一行放得下」的样子：超出部分用省略号。
     *
     * <p>先用字体真实度量算宽度（不能用「字符数 × 平均字宽」估 —— 中英文混排差很多），
     * 放不下就一步步砍尾巴，直到「前 n 个字 + …」放得下。</p>
     */
    static String fitOneLine(TtfFont font, String text, double fontSize, double maxWidth) {
        if (font == null || text == null || text.isEmpty() || maxWidth <= 0) {
            return text == null ? "" : text;
        }
        // 【27.2-pre-136】快路径：**一次**排版 + 一次遍历定位截断点。
        // 以前走 truncate()，它对每个候选串都调一次 measure() ⇒ 一个字一个字地量，
        // 30 个字就是 30 次排版（每帧！）。排版结果现在有缓存，但 30 次查表也比 1 次贵。
        if (text.indexOf('\n') < 0) {
            String fast = truncateByRuns(font, text, fontSize, maxWidth);
            if (fast != null) {
                return fast;
            }
        }
        return truncate(text, maxWidth, s -> measure(font, s, fontSize));
    }

    /**
     * 用一次排版结果求出「前 n 个字 + 省略号」。
     *
     * <p>排版出来的每个字形都带着自己的墨迹矩形，所以「前 n 个字形有多宽」
     * 就是第 n 个字形的右边缘 —— 不需要把每个候选串都重新排一遍。
     * 截断按<b>可见字符</b>数走（{@code TextLayout.visiblePrefix}），
     * 因此格式化代码既不算字数、也不会被截成半截。</p>
     *
     * @return 结果；无法处理时返回 null（调用方回落到逐个候选的慢路径）
     */
    private static String truncateByRuns(TtfFont font, String text, double fontSize, double maxWidth) {
        try {
            java.util.List<TextLayout.GlyphRun> runs = TextRenderer.runsFor(font, text, fontSize, -1, 1.0);
            if (runs.isEmpty()) {
                return text;
            }
            double minX = runs.get(0).x0;
            double maxX = runs.get(0).x1;
            for (TextLayout.GlyphRun g : runs) {
                maxX = Math.max(maxX, g.x1);
            }
            if (maxX - minX <= maxWidth) {
                return text;
            }
            String ellipsis = "\u2026";
            java.util.List<TextLayout.GlyphRun> dot = TextRenderer.runsFor(font, ellipsis, fontSize, -1, 1.0);
            double dotW = 0;
            if (!dot.isEmpty()) {
                double dMin = dot.get(0).x0;
                double dMax = dot.get(0).x1;
                for (TextLayout.GlyphRun g : dot) {
                    dMin = Math.min(dMin, g.x0);
                    dMax = Math.max(dMax, g.x1);
                }
                dotW = dMax - dMin;
            }
            double limit = maxWidth - dotW;
            int fit = 0;
            for (int i = 0; i < runs.size(); i++) {
                if (runs.get(i).x1 - minX <= limit) {
                    fit = i + 1;
                } else {
                    break;
                }
            }
            if (fit <= 0) {
                return ellipsis;
            }
            if (fit >= runs.size()) {
                return text;
            }
            return TextLayout.visiblePrefix(text, fit) + ellipsis;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 「一行放不下就省略号」的纯逻辑（度量函数由调用方给，便于验证）。
     *
     * @param text     原文
     * @param maxWidth 可用宽度（与度量函数同一单位）
     * @param measurer 度量函数：给定文字返回宽度
     */
    public static String truncate(String text, double maxWidth,
                                  java.util.function.ToDoubleFunction<String> measurer) {
        if (text == null || text.isEmpty() || maxWidth <= 0) {
            return text == null ? "" : text;
        }
        if (measurer.applyAsDouble(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "\u2026";
        for (int cut = text.length() - 1; cut > 0; cut--) {
            String candidate = text.substring(0, cut) + ellipsis;
            if (measurer.applyAsDouble(candidate) <= maxWidth) {
                return candidate;
            }
        }
        return ellipsis;
    }

    /** 用字体度量算一行文字的宽度（画布单位）。 */
    private static double measure(TtfFont font, String text, double fontSize) {
        try {
            return TextLayout.measure(text, FontManager.metrics(font), fontSize, -1, 1.0).width();
        } catch (Throwable t) {
            return text.length() * fontSize * 0.6;
        }
    }

    private static void drawTime(QuadCollector collector, PlaneRenderContext ctx, MusicWidget w,
                                 long gameTime) {
        double[] box = offset(w, w.timeBox());
        String text = formatTime(w.positionAt(gameTime)) + "/" + formatTime(w.effectiveDurationMs());
        TtfFont font = FontManager.get(w.fontId);
        double size = Math.max(4.0, w.fontSize * 0.92);
        TextRenderer.drawInBox(collector, ctx, font, fitOneLine(font, text, size, box[2] - box[0]),
                size, -1, 1.0, 2, 1, box[0], box[1], box[2] - box[0], box[3] - box[1],
                w.rot, w.alpha, ctx.depth(), w.x, w.y);
    }

    /** 有歌词就显示当前那一句，否则显示歌名。 */
    private static String titleText(Plane plane, MusicWidget w, long gameTime) {
        MusicPlayer player = MusicPlayer.get();
        boolean mine = player.isActive()
                       && MusicManager.ownerKey(plane, w).equals(player.ownerKey());
        if (w.showLyric && mine) {
            LyricRecord lyric = MusicManager.lyricFor(w);
            if (lyric != null) {
                String line = lyric.lineAt((int) (w.positionAt(gameTime) / 50L));
                if (line != null && !line.isBlank()) {
                    return line;
                }
            }
        }
        String title = w.title == null || w.title.isBlank() ? w.sourceKey : w.title;
        if (title == null || title.isBlank()) {
            return w.playing ? "…" : "未选择音乐";
        }
        if (w.artist != null && !w.artist.isBlank()) {
            return title + " - " + w.artist;
        }
        return title;
    }

    /**
     * 毫秒 → {@code m:ss}（超过一小时用 {@code h:mm:ss}）。
     *
     * <p>【27.2-pre-136】不再用 {@code String.format}：它每帧都要为「进度/时长」各算一次，
     * 而格式化字符串在 Java 里很慢（要解析格式 + 建 Formatter）。手写补零等价且便宜得多。</p>
     */
    public static String formatTime(long ms) {
        long total = Math.max(0L, ms) / 1000L;
        long seconds = total % 60L;
        long minutes = (total / 60L) % 60L;
        long hours = total / 3600L;
        if (hours > 0) {
            return hours + ":" + two(minutes) + ":" + two(seconds);
        }
        return minutes + ":" + two(seconds);
    }

    /** 两位补零（{@code %02d} 的手写版）。 */
    private static String two(long v) {
        return v < 10 ? "0" + v : Long.toString(v);
    }

    // ------------------------------------------------------------------ 几何工具

    /** 同一控件内的第 n 层：沿外法线错开 n × {@link #LAYER} 方块，避免共面闪烁。 */
    private static PlaneRenderContext layer(PlaneRenderContext ctx, int n) {
        if (n == 0) {
            return ctx;
        }
        return new PlaneRenderContext(ctx.axisX(), ctx.axisY(), ctx.normal(), ctx.origin(),
                ctx.depth() + n * LAYER);
    }

    /** 局部坐标 → 画布坐标（加上锚点）。 */
    private static double[] offset(MusicWidget w, double[] local) {
        return new double[]{w.x + local[0], w.y + local[1], w.x + local[2], w.y + local[3]};
    }

    /** 轴对齐矩形（自动按控件旋转角旋转）。 */
    static void rect(QuadCollector collector, PlaneRenderContext ctx, MusicWidget w,
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

    /** 三角形：第三、四点重合的退化四边形。 */
    static void triangle(QuadCollector collector, PlaneRenderContext ctx, MusicWidget w,
                         double ax, double ay, double bx, double by, double cx, double cy, int argb) {
        if ((argb >>> 24) == 0) {
            return;
        }
        double[] pa = TextRenderer.rot(w.x, w.y, w.rot, ax - w.x, ay - w.y);
        double[] pb = TextRenderer.rot(w.x, w.y, w.rot, bx - w.x, by - w.y);
        double[] pc = TextRenderer.rot(w.x, w.y, w.rot, cx - w.x, cy - w.y);
        collector.setRenderType(QuadCollector.imageType(WidgetRenderer.WHITE));
        collector.canvasQuad(ctx.axisX(), ctx.axisY(), ctx.normal(), ctx.origin(),
                pa[0], pa[1], pb[0], pb[1], pc[0], pc[1], pc[0], pc[1],
                ctx.depth(), 0f, 0f, 1f, 1f, argb);
    }

    /** 空心圆环（白圈）。 */
    static void ring(QuadCollector collector, PlaneRenderContext ctx, MusicWidget w,
                     double cx, double cy, double r, double thickness, int argb) {
        if ((argb >>> 24) == 0 || r <= 0.1) {
            return;
        }
        int segments = 28;
        double inner = Math.max(0.0, r - thickness);
        for (int i = 0; i < segments; i++) {
            double a0 = Math.PI * 2 * i / segments;
            double a1 = Math.PI * 2 * (i + 1) / segments;
            quad(collector, ctx, w,
                    cx + Math.cos(a0) * inner, cy + Math.sin(a0) * inner,
                    cx + Math.cos(a0) * r, cy + Math.sin(a0) * r,
                    cx + Math.cos(a1) * r, cy + Math.sin(a1) * r,
                    cx + Math.cos(a1) * inner, cy + Math.sin(a1) * inner, argb);
        }
    }

    /**
     * 圆角多边形（凸多边形，本处用来画「播放」三角）。
     *
     * <p>做法是标准的「每条边按半径内缩 + 每个角用圆弧过渡」：先算每个角的
     * 圆弧圆心（沿角平分线走 {@code r / sin(半角)}），再依次取圆弧上的点组成
     * 一条闭合路径，最后以质心为扇心三角化。这样三角的三个角是**真正的圆弧**，
     * 不会是「小三角形拼三个圆」那种看着别扭的形状。</p>
     */
    static void roundPolygon(QuadCollector collector, PlaneRenderContext ctx, MusicWidget w,
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
            double ux = prev[0] - cur[0], uy = prev[1] - cur[1];
            double vx = next[0] - cur[0], vy = next[1] - cur[1];
            double lu = Math.max(1e-6, Math.hypot(ux, uy));
            double lv = Math.max(1e-6, Math.hypot(vx, vy));
            ux /= lu;
            uy /= lu;
            vx /= lv;
            vy /= lv;
            double cos = Math.max(-1.0, Math.min(1.0, ux * vx + uy * vy));
            double half = Math.acos(cos) / 2.0;                 // 半个内角
            double r = Math.min(radius, Math.min(lu, lv) * 0.5);
            if (r <= 0.01 || Math.sin(half) < 1e-4) {
                path.add(new double[]{cur[0], cur[1]});
                continue;
            }
            double dist = r / Math.sin(half);                   // 圆心到顶点的距离
            double bx = ux + vx, by = uy + vy;                  // 角平分线
            double lb = Math.max(1e-6, Math.hypot(bx, by));
            double ccx = cur[0] + bx / lb * dist;
            double ccy = cur[1] + by / lb * dist;
            double a0 = Math.atan2(uy - by / lb * (uy * by / lb + ux * bx / lb), 0); // 占位，下面直接算
            // 两个切点相对圆心的角度
            double angU = Math.atan2(cur[1] + uy * lu - ccy, cur[0] + ux * lu - ccx);
            double angV = Math.atan2(cur[1] + vy * lv - ccy, cur[0] + vx * lv - ccx);
            double a1 = Math.atan2(uy, ux);
            double a2 = Math.atan2(vy, vx);
            // 从 a1 扫到 a2（走短弧）
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
            if (angU == angV) {                                    // 仅为消除未用变量告警
                path.add(new double[]{ccx, ccy});
                path.remove(path.size() - 1);
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
            triangle(collector, ctx, w, gx, gy, a[0], a[1], b[0], b[1], argb);
        }
    }

    /** 实心圆（扇形拼）。 */
    static void disc(QuadCollector collector, PlaneRenderContext ctx, MusicWidget w,
                     double cx, double cy, double r, int argb) {
        if (r <= 0.05) {
            return;
        }
        int segments = 24;   // 播放键要「圆润」，段数给足
        for (int i = 0; i < segments; i++) {
            double a0 = Math.PI * 2 * i / segments;
            double a1 = Math.PI * 2 * (i + 1) / segments;
            triangle(collector, ctx, w, cx, cy,
                    cx + Math.cos(a0) * r, cy + Math.sin(a0) * r,
                    cx + Math.cos(a1) * r, cy + Math.sin(a1) * r, argb);
        }
    }

    /** 圆角矩形（实心）。 */
    static void roundedRect(QuadCollector collector, PlaneRenderContext ctx, MusicWidget w,
                            double x0, double y0, double x1, double y1, double r, int argb) {
        if (r <= 0.4) {
            rect(collector, ctx, w, x0, y0, x1, y1, argb);
            return;
        }
        rect(collector, ctx, w, x0 + r, y0, x1 - r, y1, argb);
        rect(collector, ctx, w, x0, y0 + r, x0 + r, y1 - r, argb);
        rect(collector, ctx, w, x1 - r, y0 + r, x1, y1 - r, argb);
        corners(collector, ctx, w, x0, y0, x1, y1, r, argb, 0);
    }

    /** 圆角矩形边框（空心环）。 */
    static void roundedRing(QuadCollector collector, PlaneRenderContext ctx, MusicWidget w,
                            double x0, double y0, double x1, double y1, double r, double t,
                            int argb) {
        if (t <= 0) {
            return;
        }
        if (r <= 0.4) {
            rect(collector, ctx, w, x0, y0, x1, y0 + t, argb);
            rect(collector, ctx, w, x0, y1 - t, x1, y1, argb);
            rect(collector, ctx, w, x0, y0 + t, x0 + t, y1 - t, argb);
            rect(collector, ctx, w, x1 - t, y0 + t, x1, y1 - t, argb);
            return;
        }
        rect(collector, ctx, w, x0 + r, y0, x1 - r, y0 + t, argb);
        rect(collector, ctx, w, x0 + r, y1 - t, x1 - r, y1, argb);
        rect(collector, ctx, w, x0, y0 + r, x0 + t, y1 - r, argb);
        rect(collector, ctx, w, x1 - t, y0 + r, x1, y1 - r, argb);
        corners(collector, ctx, w, x0, y0, x1, y1, r, argb, t);
    }

    /**
     * 四个角：{@code thickness == 0} 时画实心扇形三角，否则画弧上的环形四边形。
     */
    private static void corners(QuadCollector collector, PlaneRenderContext ctx, MusicWidget w,
                                double x0, double y0, double x1, double y1, double r, int argb,
                                double thickness) {
        // {圆心x, 圆心y, 起始角}
        double[][] corners = {
                {x0 + r, y0 + r, Math.PI},              // 左下
                {x1 - r, y0 + r, Math.PI * 1.5},        // 右下
                {x1 - r, y1 - r, 0},                    // 右上
                {x0 + r, y1 - r, Math.PI * 0.5},        // 左上
        };
        for (double[] c : corners) {
            for (int i = 0; i < CORNER_SEGMENTS; i++) {
                double a0 = c[2] + Math.PI * 0.5 * i / CORNER_SEGMENTS;
                double a1 = c[2] + Math.PI * 0.5 * (i + 1) / CORNER_SEGMENTS;
                double ox0 = c[0] + Math.cos(a0) * r;
                double oy0 = c[1] + Math.sin(a0) * r;
                double ox1 = c[0] + Math.cos(a1) * r;
                double oy1 = c[1] + Math.sin(a1) * r;
                if (thickness <= 0) {
                    triangle(collector, ctx, w, c[0], c[1], ox0, oy0, ox1, oy1, argb);
                } else {
                    double inner = Math.max(0, r - thickness);
                    double ix0 = c[0] + Math.cos(a0) * inner;
                    double iy0 = c[1] + Math.sin(a0) * inner;
                    double ix1 = c[0] + Math.cos(a1) * inner;
                    double iy1 = c[1] + Math.sin(a1) * inner;
                    quad(collector, ctx, w, ix0, iy0, ox0, oy0, ox1, oy1, ix1, iy1, argb);
                }
            }
        }
    }

    /** 任意四边形（按 a→b→c→d 顺序）。 */
    static void quad(QuadCollector collector, PlaneRenderContext ctx, MusicWidget w,
                     double ax, double ay, double bx, double by, double cx, double cy,
                     double dx, double dy, int argb) {
        if ((argb >>> 24) == 0) {
            return;
        }
        double[] pa = TextRenderer.rot(w.x, w.y, w.rot, ax - w.x, ay - w.y);
        double[] pb = TextRenderer.rot(w.x, w.y, w.rot, bx - w.x, by - w.y);
        double[] pc = TextRenderer.rot(w.x, w.y, w.rot, cx - w.x, cy - w.y);
        double[] pd = TextRenderer.rot(w.x, w.y, w.rot, dx - w.x, dy - w.y);
        collector.setRenderType(QuadCollector.imageType(WidgetRenderer.WHITE));
        collector.canvasQuad(ctx.axisX(), ctx.axisY(), ctx.normal(), ctx.origin(),
                pa[0], pa[1], pb[0], pb[1], pc[0], pc[1], pd[0], pd[1],
                ctx.depth(), 0f, 0f, 1f, 1f, argb);
    }
}



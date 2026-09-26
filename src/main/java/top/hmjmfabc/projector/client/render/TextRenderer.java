package top.hmjmfabc.projector.client.render;

import top.hmjmfabc.projector.ProjectorConfig;
import top.hmjmfabc.projector.client.font.FontManager;
import top.hmjmfabc.projector.client.font.GlyphAtlas;
import top.hmjmfabc.projector.client.font.TtfFont;
import top.hmjmfabc.projector.common.text.TextLayout;

import java.util.List;

/**
 * 文本渲染器：把一段带 {@code &} 格式化代码的文本画到平面上的任意位置，
 * 支持任意字体、任意字号、任意旋转，以及粗体/斜体/下划线/删除线。
 *
 * <p>排版完全由 {@link TextLayout} 用真实字体度量完成，因此缩放是「无极」的：
 * 字号可以取任意浮点值。字形位图固定以 96px 光栅化后由 GPU 缩放，
 * 所以一个字形无论被放大到几格，显存里都只存一份。</p>
 */
public final class TextRenderer {

    /** 缺字提示只记一次，避免刷屏。 */
    private static boolean glyphMissLogged;

    private TextRenderer() {
    }

    /**
     * 把文本绘制在「控件方框」内：尺寸、对齐、旋转全部以方框为准。
     *
     * <p><b>这是唯一的绘制入口，也是「方框与文字永远对齐」的保证。</b>
     * 排版只做一次（{@link TextLayout#layoutRuns}），得到一组字形墨迹矩形与
     * 它们的包围盒；方框尺寸用的就是同一份结果的包围盒，
     * 因此：</p>
     * <ul>
     *   <li>{@code align=0/1/2} 分别是「让墨迹左边缘 / 中线 / 右边缘贴住方框」；</li>
     *   <li>{@code vAlign=0/1} 分别是「让墨迹下边缘贴住方框底边 / 在方框内垂直居中」；</li>
     *   <li>不再有「基线 + descent」这类间接换算，也就不会再出现
     *       「文字整体偏出方框」的情况。</li>
     * </ul>
     *
     * @param boxX,boxY 方框左下角（画布坐标，未旋转）
     * @param boxW,boxH 方框宽高（画布坐标）
     * @param align    0=左 1=中 2=右（相对方框）
     * @param vAlign   0=贴底 1=垂直居中（相对方框）
     */
    public static void drawInBox(QuadCollector collector, PlaneRenderContext ctx, TtfFont font,
                                 String text, double fontSize, double wrapWidth, double lineSpacing,
                                 int align, int vAlign,
                                 double boxX, double boxY, double boxW, double boxH,
                                 double rotDeg, float alpha, double depth) {
        // 默认旋转轴 = 方框左下角（与 SelectionRenderer 画控件框用的是同一个点）
        drawInBox(collector, ctx, font, text, fontSize, wrapWidth, lineSpacing, align, vAlign,
                boxX, boxY, boxW, boxH, rotDeg, alpha, depth, boxX, boxY);
    }

    /**
     * 与上一个重载相同，但<b>显式指定旋转轴</b>。
     *
     * <p>【为什么要这个重载】旋转轴以前只能等于方框左下角，而 {@code pivotX/pivotY}
     * 是<b>静态字段</b>。一个控件里要画多段文字（例如时钟的「标题 / 时间 / 午别」
     * 三条带子、天气的「图标 + 文字」）时，每段都有自己的方框，
     * 于是每段都会把旋转轴改成自己的方框角——控件一旦旋转，
     * 几段文字就会绕着不同的轴各转各的，直接散架。</p>
     *
     * <p>把轴显式传进来（通常是<b>控件自身的左下角</b>），整个控件就只有一根轴，
     * 与 {@code WidgetRenderer} 里图片/方框用的轴完全一致。</p>
     */
    public static void drawInBox(QuadCollector collector, PlaneRenderContext ctx, TtfFont font,
                                 String text, double fontSize, double wrapWidth, double lineSpacing,
                                 int align, int vAlign,
                                 double boxX, double boxY, double boxW, double boxH,
                                 double rotDeg, float alpha, double depth,
                                 double pivotX0, double pivotY0) {
        if (font == null || text == null || text.isEmpty() || fontSize <= 0.05) {
            return;
        }
        GlyphAtlas atlas = FontManager.atlas();
        if (atlas.pageCount() <= 0) {
            return;
        }
        List<TextLayout.GlyphRun> runs = TextLayout.layoutRuns(text, FontManager.metrics(font),
                fontSize, wrapWidth, lineSpacing, 0, 0);
        if (runs.isEmpty()) {
            return;
        }
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (TextLayout.GlyphRun g : runs) {
            minX = Math.min(minX, g.x0);
            maxX = Math.max(maxX, g.x1);
            minY = Math.min(minY, g.y0);
            maxY = Math.max(maxY, g.y1);
        }
        double dx = switch (align) {
            case 1 -> boxX + boxW / 2.0 - (minX + maxX) / 2.0;
            case 2 -> boxX + boxW - maxX;
            default -> boxX - minX;
        };
        double dy = vAlign == 1
                ? boxY + (boxH - (maxY - minY)) / 2.0 - minY
                : boxY - minY;

        pivotX = pivotX0;
        pivotY = pivotY0;
        // 先设一次默认页：即使后面一个字形都没画，也不会留下 null 渲染类型。
        collector.setRenderType(QuadCollector.fontType(atlas.locationOf(0)));

        GlyphAtlas.Slot probe = sampleSlot(atlas);
        for (TextLayout.GlyphRun g : runs) {
            int color = QuadCollector.withAlpha(g.argb, alpha);
            double x0 = g.x0 + dx, x1 = g.x1 + dx;
            double y0 = g.y0 + dy, y1 = g.y1 + dy;
            int styleBits = g.style;
            if (g.codePoint < 0 || probe == null) {
                // 缺字占位（或图集里连一个可用字形都没有）：实心小红块
                solid(collector, ctx, rotDeg, depth, x0, y0, x1, y1, color, atlas);
                continue;
            }
            GlyphAtlas.Slot slot = font.slotOf(g.codePoint, atlas);
            if (slot == null || slot.w() <= 0 || slot.h() <= 0) {
                solid(collector, ctx, rotDeg, depth, x0, y0, x1, y1, 0x55FF5555, atlas);
                continue;
            }
            float ps = ProjectorConfig.INSTANCE.atlasPageSize.get();
            float u0 = slot.x() / ps;
            float v0 = slot.y() / ps;
            float u1 = (slot.x() + slot.w()) / ps;
            float v1 = (slot.y() + slot.h()) / ps;
            // 按字形所在页切换纹理：图集多页时这一行是正确性的关键。
            collector.setRenderType(QuadCollector.fontType(atlas.locationOf(slot.page())));
            if ((styleBits & TextLayout.STYLE_BOLD) != 0) {
                double off = Math.max(0.06, (x1 - x0) * 0.06);
                quad(collector, ctx, rotDeg, depth, x0 + off, y0, x1 + off, y1,
                        u0, v0, u1, v1, color, styleBits);
            }
            quad(collector, ctx, rotDeg, depth, x0, y0, x1, y1, u0, v0, u1, v1, color, styleBits);
            if ((styleBits & TextLayout.STYLE_UNDERLINE) != 0) {
                double t = Math.max(0.3, (y1 - y0) * 0.07);
                solid(collector, ctx, rotDeg, depth, x0, y0 - t * 1.8, x1, y0 - t * 0.8, color, atlas);
            }
            if ((styleBits & TextLayout.STYLE_STRIKETHROUGH) != 0) {
                double t = Math.max(0.3, (y1 - y0) * 0.07);
                double mid = y0 + (y1 - y0) * 0.42;
                solid(collector, ctx, rotDeg, depth, x0, mid, x1, mid + t, color, atlas);
            }
        }
    }

    /**
     * 输出一个可见字形。
     *
     * <p>旋转统一绕「方框左下角」进行（{@code boxX,boxY} 由 drawInBox 通过
     * {@link #rotPivot} 传入），这样字形与控件方框的旋转轴完全相同。</p>
     */
    private static void quad(QuadCollector collector, PlaneRenderContext ctx, double rotDeg, double depth,
                             double x0, double y0, double x1, double y1,
                             float u0, float v0, float u1, float v1, int argb, int styleBits) {
        double shear = (styleBits & TextLayout.STYLE_ITALIC) != 0 ? 0.21 : 0.0;
        double dxTop = (y1 - y0) * shear;
        double[] p0 = rotPivot(rotDeg, x0, y0);
        double[] p1 = rotPivot(rotDeg, x1, y0);
        double[] p2 = rotPivot(rotDeg, x1 + dxTop, y1);
        double[] p3 = rotPivot(rotDeg, x0 + dxTop, y1);
        collector.canvasQuad(ctx.axisX(), ctx.axisY(), ctx.normal(), ctx.origin(),
                p0[0], p0[1], p1[0], p1[1], p2[0], p2[1], p3[0], p3[1],
                depth, u0, v0, u1, v1, argb);
    }

    /** 画一个实心矩形（借用字形图集里某个不透明字形的一小块作为 UV）。 */
    private static void solid(QuadCollector collector, PlaneRenderContext ctx, double rotDeg, double depth,
                              double x0, double y0, double x1, double y1, int argb, GlyphAtlas atlas) {
        GlyphAtlas.Slot probe = sampleSlot(atlas);
        if (probe == null || x1 - x0 <= 1.0e-4 || y1 - y0 <= 1.0e-4) return;
        float ps = ProjectorConfig.INSTANCE.atlasPageSize.get();
        float cu = (probe.x() + probe.w() * 0.5f) / ps;
        float cv = (probe.y() + probe.h() * 0.5f) / ps;
        collector.setRenderType(QuadCollector.fontType(atlas.locationOf(probe.page())));
        double[] p0 = rotPivot(rotDeg, x0, y0);
        double[] p1 = rotPivot(rotDeg, x1, y0);
        double[] p2 = rotPivot(rotDeg, x1, y1);
        double[] p3 = rotPivot(rotDeg, x0, y1);
        collector.canvasQuad(ctx.axisX(), ctx.axisY(), ctx.normal(), ctx.origin(),
                p0[0], p0[1], p1[0], p1[1], p2[0], p2[1], p3[0], p3[1],
                depth, cu, cv, cu, cv, argb);
    }

    /** 取一个「实心」采样点：优先 'm'，退回 'M'。 */
    private static GlyphAtlas.Slot sampleSlot(GlyphAtlas atlas) {
        TtfFont font = FontManager.get(null);
        if (font == null) return null;
        GlyphAtlas.Slot s = font.slotOf('m', atlas);
        if (s != null && s.w() > 1 && s.h() > 1) return s;
        return font.slotOf('M', atlas);
    }

    /** 本帧正在绘制的控件方框左下角：字形与方框共用同一个旋转轴。 */
    private static double pivotX, pivotY;

    /**
     * 绕当前方框左下角逆时针旋转。
     *
     * <p><b>注意 {@link #rot} 的参数是「相对轴心的偏移」，不是绝对坐标。</b>
     * 这里传进来的是画布绝对坐标，必须先减去轴心再旋转——
     * 少了这一步就会变成 {@code (轴心 + 坐标)}，也就是把整段文字平移了
     * 「方框左下角」那么多：方框在画布中央时，文字正好被推到右上角。</p>
     */
    private static double[] rotPivot(double rotDeg, double x, double y) {
        return rot(pivotX, pivotY, rotDeg, x - pivotX, y - pivotY);
    }

    /** 绕 (wx,wy) 逆时针旋转。 */
    public static double[] rot(double wx, double wy, double rotDeg, double x, double y) {
        if (rotDeg == 0) {
            return new double[]{wx + x, wy + y};
        }
        double theta = Math.toRadians(rotDeg);
        double cos = Math.cos(theta), sin = Math.sin(theta);
        return new double[]{wx + x * cos - y * sin, wy + x * sin + y * cos};
    }

    private static float atlasPageSize() {
        return ProjectorConfig.INSTANCE.atlasPageSize.get();
    }
}

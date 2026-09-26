package top.hmjmfabc.projector.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import top.hmjmfabc.projector.client.SelectionState;
import top.hmjmfabc.projector.common.BlockFace;
import top.hmjmfabc.projector.common.Plane;
import top.hmjmfabc.projector.common.PlaneBlock;
import top.hmjmfabc.projector.common.PlaneCanvas;
import top.hmjmfabc.projector.common.widget.Widget;

/**
 * 圈选高亮渲染。
 *
 * <p>用「加法混合的四边形描边」而不是线条来实现，原因有三：</p>
 * <ol>
 *   <li>线条的顶点格式与原版 {@code rendertype_lines} 强绑定，跨版本/跨驱动容易出问题；</li>
 *   <li>四边形的宽度、发光强度可以自由控制，视觉上更接近光灵箭的高光边框；</li>
 *   <li>可以与控件渲染共用同一套管线，减少状态切换。</li>
 * </ol>
 */
public final class SelectionRenderer {

    /** 描边宽度（画布单位，16 = 一格）。 */
    private static final double OUTLINE_WIDTH = 0.85;
    /** 外侧光晕宽度。 */
    private static final double GLOW_WIDTH = 2.2;
    /**
     * 描边沿外法线的偏移（正数 = 朝玩家一侧）。
     *
     * <p>必须为正：描边要压在方块表面与控件内容之上，
     * 不能陷进方块里（那会被不透明方块面按深度剔除）。</p>
     */
    private static final double DEPTH = 0.02;

    private final QuadCollector collector = new QuadCollector();

    public void render(PoseStack pose, net.minecraft.client.Camera camera, float partialTick) {
        Plane plane = SelectionState.plane();
        if (plane == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        // 与 WorldPlaneRenderer 同理：必须使用相机相对坐标
        Vec3 camPos = camera == null ? Vec3.ZERO : camera.getPosition();

        long time = System.currentTimeMillis();
        float pulse = 0.55f + 0.45f * (float) Math.sin(time / 380.0);
        int glowColor = withAlpha(0x33BBEEFF, 0.30f + 0.30f * pulse);
        int lineColor = withAlpha(0xCCE8FBFF, 0.70f + 0.30f * pulse);
        int widgetColor = withAlpha(0xFFFFD54A, 0.80f + 0.20f * pulse);

        QuadCollector.beginFrame();
        collector.clear();

        PlaneCanvas canvas = plane.canvas();
        Vec3 origin = canvas.originWorld();
        // 与 WorldPlaneRenderer 一致：使用绝对世界坐标，由 flush 应用 pose 矩阵
        double[] axisX = axis(BlockFace.right(plane.face));
        double[] axisY = axis(BlockFace.up(plane.face));
        double offU = plane.canvasOffsetX / PlaneCanvas.UNITS_PER_BLOCK;
        double offV = plane.canvasOffsetY / PlaneCanvas.UNITS_PER_BLOCK;
        // 与 WorldPlaneRenderer 一致：相机相对坐标（相机平移由 ModelViewMat 承担）
        double[] originArr = {
                origin.x - axisX[0] * offU - axisY[0] * offV - camPos.x,
                origin.y - axisX[1] * offU - axisY[1] * offV - camPos.y,
                origin.z - axisX[2] * offU - axisY[2] * offV - camPos.z};
        // 显式取真实外法线：axisX × axisY 在 EAST/WEST/DOWN 上是朝内的
        double[] normal = axis(BlockFace.normal(plane.face));
        // 描边的基准面同样要补上「面平面相对方块最小角的偏移」
        double base = plane.anchorSurface + DEPTH;

        // 1) 整个平面的外框
        // 用 debugQuads 而不是 lightning()：
        //  lightning() 是 COLOR_DEPTH_WRITE + WEATHER_TARGET，在 Fabulous 画质下会被
        //  输出到天气缓冲，而且写深度会把它后面的粒子/天气/半透明内容剔掉，
        //  观感上就像「一圈会遮挡东西的光柱」。debugQuads 不写深度、走半透明混合，
        //  更像设计稿里要求的「光灵箭高光边框」。
        collector.setRenderType(RenderType.debugQuads());
        // 外框范围取「方块矩形的实际包围盒」，保证与内容严格重合
        // （之前直接用 plane.width/height，任何一点点坐标不一致都会表现为「高光比内容大一圈」）
        double ox0 = Double.MAX_VALUE, oy0 = Double.MAX_VALUE;
        double ox1 = -Double.MAX_VALUE, oy1 = -Double.MAX_VALUE;
        for (PlaneBlock b : plane.blocks.values()) {
            ox0 = Math.min(ox0, b.x0);
            oy0 = Math.min(oy0, b.y0);
            ox1 = Math.max(ox1, b.x1);
            oy1 = Math.max(oy1, b.y1);
        }
        if (ox0 > ox1 || oy0 > oy1) {
            ox0 = 0; oy0 = 0; ox1 = plane.width; oy1 = plane.height;
        }
        // 线宽按平面尺寸缩放：小平面用细线，免得「一圈光晕」把平面本身糊掉
        double span = Math.min(ox1 - ox0, oy1 - oy0);
        double glowW = Math.max(0.4, Math.min(GLOW_WIDTH, span * 0.03));
        double lineW = Math.max(0.2, Math.min(OUTLINE_WIDTH, span * 0.012));

        addGlowBorder(originArr, axisX, axisY, normal, base, ox0, oy0, ox1, oy1, glowColor, glowW);
        addOutline(originArr, axisX, axisY, normal, base, ox0, oy0, ox1, oy1, lineColor, lineW);

        // 2) 每个方块面的分格线（浅色，帮助看清平面的实际范围）。
        //    只在方块数不多时画：像 16x16 的地板有 256 个面，
        //    全部描边会糊成一片、看着像一圈「光柱」，反而看不清平面边界。
        final int maxGridCells = 64;
        if (plane.blocks.size() <= maxGridCells) {
            int gridColor = withAlpha(0xFF8CE9FF, 0.14f);
            for (PlaneBlock b : plane.blocks.values()) {
                addOutline(originArr, axisX, axisY, normal, base, b.x0, b.y0, b.x1, b.y1, gridColor, 0.3);
            }
        }

        // 3) 控件边框
        for (Widget w : plane.widgets) {
            double[] box = widgetAabb(w);
            addOutlineRotated(originArr, axisX, axisY, normal, base, w, box, widgetColor, 0.6);
        }

        collector.flush(pose);
    }

    private static double[] axis(net.minecraft.core.Direction d) {
        return new double[]{d.getStepX(), d.getStepY(), d.getStepZ()};
    }

    /** 控件的旋转后包围盒（画布坐标）。 */
    public static double[] widgetAabb(Widget w) {
        double[][ ] corners = {
                TextRenderer.rot(w.x, w.y, w.rot, 0, 0),
                TextRenderer.rot(w.x, w.y, w.rot, w.w, 0),
                TextRenderer.rot(w.x, w.y, w.rot, w.w, w.h),
                TextRenderer.rot(w.x, w.y, w.rot, 0, w.h)};
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (double[] c : corners) {
            minX = Math.min(minX, c[0]);
            minY = Math.min(minY, c[1]);
            maxX = Math.max(maxX, c[0]);
            maxY = Math.max(maxY, c[1]);
        }
        return new double[]{minX, minY, maxX, maxY};
    }

    /** 画一个外发光边框。 */
    private void addGlowBorder(double[] o, double[] ax, double[] ay, double[] normal, double base,
                               double x0, double y0, double x1, double y1,
                               int color, double width) {
        addOutline(o, ax, ay, normal, base, x0, y0, x1, y1, color, width);
    }

    /** 画一个矩形描边（四条带状四边形）。 */
    private void addOutline(double[] o, double[] ax, double[] ay, double[] normal, double base,
                            double x0, double y0, double x1, double y1,
                            int color, double width) {
        double w = Math.max(0.05, width);
        // 下
        quad(o, ax, ay, normal, base, x0, y0 - w, x1, y0, color);
        // 上
        quad(o, ax, ay, normal, base, x0, y1, x1, y1 + w, color);
        // 左
        quad(o, ax, ay, normal, base, x0 - w, y0, x0, y1, color);
        // 右
        quad(o, ax, ay, normal, base, x1, y0, x1 + w, y1, color);
    }

    /** 画一个随控件旋转的描边。 */
    private void addOutlineRotated(double[] o, double[] ax, double[] ay, double[] normal, double base,
                                   Widget w, double[] box, int color, double width) {
        double minX = box[0], minY = box[1], maxX = box[2], maxY = box[3];
        double lw = Math.max(0.05, width);
        double cx = w.x, cy = w.y;
        double lx0 = minX - cx, ly0 = minY - cy;
        double lx1 = maxX - cx, ly1 = maxY - cy;
        double[][] edges = {
                {lx0, ly0 - lw, lx1, ly0},
                {lx0, ly1, lx1, ly1 + lw},
                {lx0 - lw, ly0, lx0, ly1},
                {lx1, ly0, lx1 + lw, ly1}};
        for (double[] e : edges) {
            double[] p0 = TextRenderer.rot(cx, cy, w.rot, e[0], e[1]);
            double[] p1 = TextRenderer.rot(cx, cy, w.rot, e[2], e[1]);
            double[] p2 = TextRenderer.rot(cx, cy, w.rot, e[2], e[3]);
            double[] p3 = TextRenderer.rot(cx, cy, w.rot, e[0], e[3]);
            collector.canvasQuad(ax, ay, normal, o, p0[0], p0[1], p1[0], p1[1], p2[0], p2[1], p3[0], p3[1],
                    base, 0, 0, 0, 0, color);
        }
    }

    private void quad(double[] o, double[] ax, double[] ay, double[] normal, double base,
                      double x0, double y0, double x1, double y1, int color) {
        collector.canvasQuad(ax, ay, normal, o, x0, y0, x1, y0, x1, y1, x0, y1,
                base, 0, 0, 0, 0, color);
    }

    private static int withAlpha(int argb, float alpha) {
        int a = (int) (((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, alpha)));
        return (a << 24) | (argb & 0xFFFFFF);
    }
}

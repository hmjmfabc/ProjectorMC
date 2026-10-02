package top.hmjmfabc.projector.client.render;

import top.hmjmfabc.projector.common.widget.MusicWidget;
import top.hmjmfabc.projector.common.widget.VideoWidget;

/**
 * 视频控件的「世界内小播放键 + 进度条」（hotfix-98）。
 *
 * <p>几何<b>全部</b>取自 {@link VideoWidget} 的 {@code controlBox() / progressBox()}，
 * 与点击判定共用同一份来源 —— 否则就会出现「看得见点不着」。</p>
 *
 * <p>层次的错开与音乐控件同规矩：同一控件内每层沿外法线偏 {@link #LAYER} 格，
 * 免得共面 Z-fighting（本项目的复发榜第一）。</p>
 */
public final class VideoOverlayRenderer {
    private VideoOverlayRenderer() {
    }

    /** 同控件内的层间距（方块）。 */
    private static final double LAYER = 0.015;

    /** 白色（与音乐控件同色）。 */
    private static final int WHITE = 0xFFFFFFFF;

    /** 画整个浮层（调用方已确认该控件此刻要显示控件）。 */
    public static void draw(QuadCollector collector, PlaneRenderContext ctx, VideoWidget w) {
        if (w.w <= 1 || w.h <= 1) {
            return;
        }
        // 临时锚点：音乐控件的绘制函数只读这三个字段 ⇒ 形状/配色与音乐**逐像素一样**
        MusicWidget a = new MusicWidget();
        a.x = w.x;
        a.y = w.y;
        a.rot = w.rot;

        long now = WidgetRenderer.clientTimeMs();
        boolean playing = !w.paused;

        // ---- 进度条：与音乐控件的条同一套观感（白色胶囊 + 圆点）----
        // ⚠ 必须转成**画布坐标**：音乐那套绘制函数内部会做 x - w.x 再旋转
        //   （音乐自己也是用 offset(w, w.xxxBox()) 传进去的）。少了这一步，
        //   播放键与进度条会被画到**画布原点**（锚点方块那个角），看着就像「不显示了」。
        double[] box = offset(w, w.progressBox());
        double barH = Math.max(0.6, box[3] - box[1]);
        double midY = (box[1] + box[3]) / 2.0;
        double x0 = box[0];
        double x1 = box[2];
        MusicWidgetRenderer.roundedRect(collector, layer(ctx, 1), a,
                x0, midY - barH / 2, x1, midY + barH / 2, barH / 2, 0x8CFFFFFF);
        // 【27.1.3】直播 / HLS 这类**无限流**没有「总时长」，进度条只画底条；
        // 普通在线视频就算选了流式播放也是有时长的，照常画进度。
        final boolean live = top.hmjmfabc.projector.client.media.net.OnlineVideos.live(w);
        // 时长不可靠（外部解码器还没报出真时长）时不画已播放段，免得进度条乱跳
        if (!live && w.durationKnown()) {
            double f = Math.max(0.0, Math.min(1.0, w.progressFraction(now)));
            double playedX = x0 + (x1 - x0) * f;
            if (playedX > x0 + 0.05) {
                MusicWidgetRenderer.roundedRect(collector, layer(ctx, 2), a,
                        x0, midY - barH / 2, playedX, midY + barH / 2, barH / 2, WHITE);
            }
            MusicWidgetRenderer.disc(collector, layer(ctx, 3), a, playedX, midY, barH * 0.95, WHITE);
        }

        // ---- 播放键：**与音乐控件逐字相同**的配方（白色细圆环 + 白色图形）----
        double[] b = offset(w, w.controlBox());
        double size = b[2] - b[0];
        double cx = (b[0] + b[2]) / 2.0;
        double cy = (b[1] + b[3]) / 2.0;
        double radius = size / 2.0;
        double ringWidth = Math.max(0.22, radius * 0.09);
        reportGeometry(w, b, box);
        MusicWidgetRenderer.ring(collector, layer(ctx, 2), a, cx, cy, radius, ringWidth, WHITE);

        if (playing) {
            double barW = size * 0.15;
            double barH2 = size * 0.40;
            double gap = size * 0.14;
            double r = barW / 2;
            MusicWidgetRenderer.roundedRect(collector, layer(ctx, 3), a,
                    cx - gap / 2 - barW, cy - barH2 / 2, cx - gap / 2, cy + barH2 / 2, r, WHITE);
            MusicWidgetRenderer.roundedRect(collector, layer(ctx, 3), a,
                    cx + gap / 2, cy - barH2 / 2, cx + gap / 2 + barW, cy + barH2 / 2, r, WHITE);
        } else {
            double s3 = size * 0.34;
            double[][] tri = {
                    {cx - s3 * 0.52, cy - s3 * 0.92},
                    {cx - s3 * 0.52, cy + s3 * 0.92},
                    {cx + s3 * 1.00, cy},
            };
            MusicWidgetRenderer.roundPolygon(collector, layer(ctx, 3), a, tri, size * 0.055, WHITE);
        }
    }

    /** 同一控件内的第 n 层：沿外法线错开，避免共面闪烁。 */
    private static PlaneRenderContext layer(PlaneRenderContext ctx, int n) {
        if (n == 0) {
            return ctx;
        }
        return new PlaneRenderContext(ctx.axisX(), ctx.axisY(), ctx.normal(), ctx.origin(),
                ctx.depth() + n * LAYER);
    }

    /**
     * 控件局部坐标 → 画布坐标（加上锚点）。
     *
     * <p>{@code VideoWidget.controlBox()/progressBox()} 返回的是**相对锚点**的局部坐标，
     * 而 {@code MusicWidgetRenderer} 的绘制函数要的是**画布坐标**
     * （它们内部按 {@code x - w.x} 取局部量再旋转）—— 两边差一个锚点偏移，
     * 少了这个换算就会画到画布原点去。</p>
     */
    private static double[] offset(VideoWidget w, double[] local) {
        return new double[]{w.x + local[0], w.y + local[1], w.x + local[2], w.y + local[3]};
    }

    /** 每个控件上一次打「浮层几何」的时间（同一控件 2 秒最多一行）。 */
    private static final java.util.Map<java.util.UUID, Long> LAST_GEOMETRY_MS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 诊断：把浮层的画布几何打一行日志。
     *
     * <p>「点了没出现」这种问题必须能一眼分清：是点击没叫出来（没有这一行）、
     * 还是画出来了但位置不对（这一行里的坐标和控件位置对不上 —— 上一版就是把
     * 局部坐标当画布坐标用，整块浮层被画到画布原点去了）。</p>
     */
    private static void reportGeometry(VideoWidget w, double[] b, double[] box) {
        if (w == null || w.id == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = LAST_GEOMETRY_MS.get(w.id);
        if (last != null && now - last < 2000L) {
            return;
        }
        LAST_GEOMETRY_MS.put(w.id, now);
        top.hmjmfabc.projector.Projector.LOGGER.info(
                "[Projector][视频] 浮层几何: 控件={} 控件x/y={},{} 播放键画布=({},{})-({},{})"
                        + " 进度条画布=({},{})-({},{}) 时长已知={}",
                w.id.toString().substring(0, 8), w.x, w.y,
                b[0], b[1], b[2], b[3], box[0], box[1], box[2], box[3], w.durationKnown());
    }
}

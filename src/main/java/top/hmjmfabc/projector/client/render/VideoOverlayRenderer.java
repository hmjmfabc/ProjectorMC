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

    /**
     * 绘制代理（只借 MusicWidget 那套几何原语）。
     *
     * <p>【27.2.4-200】静态复用：渲染是单线程的，每帧只覆写 x/y/w/h/rot 四个字段。
     * 以前每帧 `new MusicWidget()`，而 {@code Widget} 的字段初始化里有
     * {@code UUID.randomUUID()}（SecureRandom）与一个 ArrayList，等于每帧制造垃圾。</p>
     */
    private static final MusicWidget PROXY = new MusicWidget();
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
        // 临时代理控件：只用来借音乐控件那套绘制原语（形状/配色与音乐**逐像素一样**）。
        // 【27.2.1-hotfix-144 定稿】音乐那套原语的入参是**控件局部坐标**（相对左下角），
        // 所以这里全程用局部坐标：画布位置/旋转由原语内部按代理控件的 x/y/rot 处理。
        // ⚠ 这里**不许**再出现 on-canvas 的加法（offset/`w.x + …`）——
        //   那会让每个原语额外平移一次（本轮偏移 bug 的根因）。
        // 【27.2.4-200 修】渲染线程、每个可见视频控件每帧一次：以前每次都 new 一个 MusicWidget，
        // 而 Widget 的字段初始化含 UUID.randomUUID()（SecureRandom）+ ArrayList ⇒ 每帧纯垃圾。
        // 改成静态复用的代理对象（渲染是单线程的，每帧覆写四个字段即可）。
        MusicWidget a = PROXY;
        a.x = w.x;
        a.y = w.y;
        a.w = w.w;
        a.h = w.h;
        a.rot = w.rot;

        long now = WidgetRenderer.clientTimeMs();
        boolean playing = !w.paused;

        // ---- 进度条：与音乐控件的条同一套观感（白色胶囊 + 圆点）----
        // 局部坐标（相对左下角）—— 与 VideoWidget.progressBox() 的约定一致
        double[] box = w.progressBox();
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
        double[] b = w.controlBox();
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

    // ⚠【27.2.1-hotfix-144】这里以前有一个 `offset(w, local)`（局部 → 画布）。
    //   它是本轮「播放键/进度条整体偏移」的根源：音乐那套原语的入参本来就是**局部坐标**，
    //   再加一次锚点偏移就等于平移两遍。**这个换算已经删掉，不要再加回来** ——
    //   本文件全程用局部坐标，画布位置由原语内部的 (x - 中心) 处理。

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

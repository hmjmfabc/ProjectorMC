package top.hmjmfabc.projector.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import top.hmjmfabc.projector.client.font.FontManager;
import top.hmjmfabc.projector.client.font.TtfFont;
import top.hmjmfabc.projector.client.media.MediaCache;
import top.hmjmfabc.projector.common.Plane;
import top.hmjmfabc.projector.common.game.GameRules;
import top.hmjmfabc.projector.common.widget.ClockWidget;
import top.hmjmfabc.projector.common.widget.Fonts;
import top.hmjmfabc.projector.common.widget.ImageWidget;
import top.hmjmfabc.projector.common.widget.ProgressWidget;
import top.hmjmfabc.projector.common.widget.TextWidget;
import top.hmjmfabc.projector.common.widget.VideoWidget;
import top.hmjmfabc.projector.common.widget.WeatherWidget;
import top.hmjmfabc.projector.common.widget.Widget;

import java.util.Map;

/**
 * 控件渲染器：把一个 {@link Widget} 画到平面上。
 *
 * <p>所有内容都是「每一帧重新构建四边形」的即时（immediate）渲染，
 * 好处是数据一变立刻生效、不需要维护 GPU 缓存与失效逻辑；
 * 代价是每帧的顶点提交量。为了控制开销，平面在远处会被整体裁剪，
 * 且每帧四边形总数有上限（见 {@link QuadCollector#MAX_QUADS}）。</p>
 */
public final class WidgetRenderer {

    /** 天气图标纹理。 */
    public static final ResourceLocation ICON_CLEAR = top.hmjmfabc.projector.Projector.id("textures/widget/weather_clear.png");
    public static final ResourceLocation ICON_RAIN = top.hmjmfabc.projector.Projector.id("textures/widget/weather_rain.png");
    public static final ResourceLocation ICON_THUNDER = top.hmjmfabc.projector.Projector.id("textures/widget/weather_thunder.png");
    public static final ResourceLocation ICON_SNOW = top.hmjmfabc.projector.Projector.id("textures/widget/weather_snow.png");
    public static final ResourceLocation ICON_END = top.hmjmfabc.projector.Projector.id("textures/widget/weather_end.png");
    public static final ResourceLocation ICON_NETHER = top.hmjmfabc.projector.Projector.id("textures/widget/weather_nether.png");

    private WidgetRenderer() {
    }

    /** 绘制单个控件。 */
    public static void draw(QuadCollector collector, Plane plane, PlaneRenderContext ctx, Widget w) {
        drawAnimated(collector, plane, ctx, w, top.hmjmfabc.projector.common.sequence.SequenceAnim.State.NORMAL);
    }

    /**
     * 【⑩】带流程动画状态地绘制一个控件。
     *
     * <p>动画的四个量分别是：画布内偏移 {@code dx/dy}、沿外法线的抬升 {@code dDepth}
     * （「下落」动画就是靠它把控件先放到平面前方 10 格）、以控件中心为基准的缩放
     * {@code scale}、以及透明度/旋转。</p>
     *
     * <p><b>实现方式：临时改写控件字段、画完立刻还原（try/finally）。</b>
     * 之所以不去克隆一个控件：所有绘制分支都直接读 {@code w.x/w.y/w.w/w.h/w.rot/w.alpha}，
     * 而克隆一份 Widget 需要按类型复制全部字段——任何一处漏掉都会变成
     * 「动画时某个控件类型表现和别的不一样」这种极难查的 bug。
     * 临时改写只影响本帧的这一次绘制，且渲染是单线程的。</p>
     */
    public static void drawAnimated(QuadCollector collector, Plane plane, PlaneRenderContext ctx,
                                    Widget w, top.hmjmfabc.projector.common.sequence.SequenceAnim.State st) {
        if (w == null) return;
        if (st == null) st = top.hmjmfabc.projector.common.sequence.SequenceAnim.State.NORMAL;
        if (!st.visible()) return;
        if (st.isNormal()) {
            drawRaw(collector, plane, ctx, w);
            return;
        }
        // ---- 保存原值 ----
        final double ox = w.x, oy = w.y, ow = w.w, oh = w.h, orot = w.rot;
        final float oalpha = w.alpha;
        try {
            // 缩放以「控件中心」为基准：绕左下角放大会让控件整体往右上跑，很别扭
            double sx = st.scale();
            if (sx != 1.0) {
                double nw = ow * sx, nh = oh * sx;
                w.x = ox + (ow - nw) * 0.5;
                w.y = oy + (oh - nh) * 0.5;
                w.w = nw;
                w.h = nh;
            }
            w.x += st.dx();
            w.y += st.dy();
            w.rot += st.rotDeg();
            w.alpha = (float) (oalpha * st.alpha());
            PlaneRenderContext use = st.dDepth() == 0 ? ctx
                    : new PlaneRenderContext(ctx.axisX(), ctx.axisY(), ctx.normal(), ctx.origin(),
                    ctx.depth() + st.dDepth());
            drawRaw(collector, plane, use, w);
        } catch (Throwable t) {
            // 动画求值/绘制出任何问题都不该让整帧崩掉，也不该把控件字段留在脏状态
            if (!animErrorLogged) {
                animErrorLogged = true;
                top.hmjmfabc.projector.Projector.LOGGER.error(
                        "[Projector] 流程动画绘制失败（该控件本帧已跳过，后续同类错误不再重复记录）", t);
            }
        } finally {
            w.x = ox;
            w.y = oy;
            w.w = ow;
            w.h = oh;
            w.rot = orot;
            w.alpha = oalpha;
        }
    }

    private static boolean animErrorLogged;

    /** 真正的分类型绘制（不含动画）。 */
    private static void drawRaw(QuadCollector collector, Plane plane, PlaneRenderContext ctx, Widget w) {
        switch (w.kind()) {
            case Widget.KIND_TEXT -> drawText(collector, ctx, (TextWidget) w);
            case Widget.KIND_IMAGE -> drawImage(collector, ctx, (ImageWidget) w);
            case Widget.KIND_VIDEO -> drawVideo(collector, ctx, (VideoWidget) w);
            case Widget.KIND_CLOCK -> drawClock(collector, ctx, (ClockWidget) w);
            case Widget.KIND_WEATHER -> drawWeather(collector, ctx, (WeatherWidget) w);
            case Widget.KIND_PROGRESS -> drawProgress(collector, ctx, (ProgressWidget) w, plane);
            case Widget.KIND_TIMER -> drawTimer(collector, ctx, (top.hmjmfabc.projector.common.widget.TimerWidget) w);
            case Widget.KIND_LEADERBOARD -> drawLeaderboard(collector, ctx,
                    (top.hmjmfabc.projector.common.widget.LeaderboardWidget) w);
            case Widget.KIND_CHESS -> drawChess(collector, ctx,
                    (top.hmjmfabc.projector.common.widget.ChessWidget) w);
            case Widget.KIND_MUSIC -> MusicWidgetRenderer.draw(collector, plane, ctx,
                    (top.hmjmfabc.projector.common.widget.MusicWidget) w);
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------
    // 文本
    // ------------------------------------------------------------------

    private static void drawText(QuadCollector collector, PlaneRenderContext ctx, TextWidget w) {
        TtfFont font = FontManager.get(w.fontId);
        TextRenderer.drawInBox(collector, ctx, font, w.text, w.fontSize, w.wrapWidth, w.lineSpacing,
                w.align, 1, w.x, w.y, w.w, w.h, w.rot, w.alpha, ctx.depth());
        if (w.background != 0) {
            solidRect(collector, ctx, w, w.x, w.y, w.x + w.w, w.y + w.h, w.background);
        }
    }

    /** 控件尺寸退化（宽或高为 0）时不提交四边形：否则顶点全部重合，什么也画不出来。 */
    private static boolean degenerate(Widget w) {
        return !(Math.abs(w.w) > 1.0e-4 && Math.abs(w.h) > 1.0e-4);
    }

    // ------------------------------------------------------------------
    // 图片
    // ------------------------------------------------------------------

    private static void drawImage(QuadCollector collector, PlaneRenderContext ctx, ImageWidget w) {
        if (degenerate(w)) return;
        if (w.background != 0) {
            solidRect(collector, ctx, w, w.x, w.y, w.x + w.w, w.y + w.h, w.background);
        }
        if (w.mediaId == null || w.mediaId.isEmpty()) {
            placeholder(collector, ctx, w, 0x663377CC);
            return;
        }
        MediaCache.Entry entry = MediaCache.image(w.mediaId);
        if (entry == null || !QuadCollector.textureReady(entry.location)) {
            // 素材还没下载/解码完，或纹理已被释放：画占位色块，
            // 免得「什么都没画」被误判成渲染失败。
            // 【⑫】正在下载时顺带把「加载中…（已收/总量，xx%）」写在控件中间。
            loadingPlaceholder(collector, ctx, w, w.mediaId, 0, 0x66CCAA33);
            reportImage(w, entry == null ? "媒体未就绪（MediaCache 里没有）" : "纹理未注册", 0, 0);
            return;
        }
        reportImage(w, "就绪", entry.width, entry.height);
        collector.setRenderType(QuadCollector.imageType(entry.location));
        int tint = QuadCollector.withAlpha(w.tint, w.alpha);
        double[] p0 = TextRenderer.rot(w.x, w.y, w.rot, 0, 0);
        double[] p1 = TextRenderer.rot(w.x, w.y, w.rot, w.w, 0);
        double[] p2 = TextRenderer.rot(w.x, w.y, w.rot, w.w, w.h);
        double[] p3 = TextRenderer.rot(w.x, w.y, w.rot, 0, w.h);
        collector.canvasQuad(ctx.axisX(), ctx.axisY(), ctx.normal(), ctx.origin(),
                p0[0], p0[1], p1[0], p1[1], p2[0], p2[1], p3[0], p3[1],
                ctx.depth(), (float) w.u0, (float) w.v0, (float) w.u1, (float) w.v1, tint);
    }

    /** 每个图片控件只在上报内容变化时打一条日志，便于定位「图片看不见」。 */
    private static final java.util.Map<java.util.UUID, String> IMAGE_REPORT = new java.util.HashMap<>();

    private static void reportImage(ImageWidget w, String state, int srcW, int srcH) {
        String line = String.format(java.util.Locale.ROOT,
                "素材=%s(%d×%d) 框=%.1fx%.1f 单位(%.2fx%.2f 格) 状态=%s",
                w.mediaName == null || w.mediaName.isEmpty() ? w.mediaId : w.mediaName,
                srcW, srcH, w.w, w.h, w.w / 16, w.h / 16, state);
        if (!line.equals(IMAGE_REPORT.put(w.id, line))) {
            top.hmjmfabc.projector.Projector.LOGGER.info("[Projector] 图片控件: {}", line);
        }
    }

    // ------------------------------------------------------------------
    // 视频
    // ------------------------------------------------------------------

    private static void drawVideo(QuadCollector collector, PlaneRenderContext ctx, VideoWidget w) {
        if (degenerate(w)) return;
        if (w.mediaId == null || w.mediaId.isEmpty()) {
            placeholder(collector, ctx, w, 0x663377CC);
            return;
        }
        // 【27.1.2】装了 WaterMedia 且这个控件该用它时，画面由它解码（MP4 等就靠这条）
        if (top.hmjmfabc.projector.client.media.wm.WaterMediaVideos.wants(w)) {
            net.minecraft.resources.ResourceLocation wm =
                    top.hmjmfabc.projector.client.media.wm.WaterMediaVideos.textureFor(w);
            if (wm != null && QuadCollector.textureReady(wm)) {
                drawVideoQuad(collector, ctx, w, wm);
                reportWaterMedia(w);
                return;
            }
            // WaterMedia 那边还没就绪（连源 / 建解码器）：用另一种颜色的占位，别和「内置加载中」混淆
            loadingPlaceholder(collector, ctx, w, w.mediaId, 0, 0x6644CC99);
            // 注：占位色偏绿，和内置后端的橙色占位区分开（一眼看出在用哪个后端）
            return;
        }
        long now = clientTimeMs();
        int frame = w.currentFrame(now);
        reportVideo(w, frame);
        MediaCache.Frame f = MediaCache.videoFrame(w.mediaId, frame);
        if (f == null || !QuadCollector.textureReady(f.location)) {
            // 【⑫】整段视频的下载进度用 frame=0 的会话；帧下载用当前帧号
            loadingPlaceholder(collector, ctx, w, w.mediaId, frame, 0x66CCAA33);
            return;
        }
        drawVideoQuad(collector, ctx, w, f.location);
    }

    /** 把一帧画到控件方框里（内置后端与 WaterMedia 后端共用同一份几何）。 */
    private static void drawVideoQuad(QuadCollector collector, PlaneRenderContext ctx,
                                      VideoWidget w, net.minecraft.resources.ResourceLocation texture) {
        collector.setRenderType(QuadCollector.imageType(texture));
        int tint = QuadCollector.withAlpha(w.tint, w.alpha);
        double[] p0 = TextRenderer.rot(w.x, w.y, w.rot, 0, 0);
        double[] p1 = TextRenderer.rot(w.x, w.y, w.rot, w.w, 0);
        double[] p2 = TextRenderer.rot(w.x, w.y, w.rot, w.w, w.h);
        double[] p3 = TextRenderer.rot(w.x, w.y, w.rot, 0, w.h);
        collector.canvasQuad(ctx.axisX(), ctx.axisY(), ctx.normal(), ctx.origin(),
                p0[0], p0[1], p1[0], p1[1], p2[0], p2[1], p3[0], p3[1],
                ctx.depth(), 0f, 0f, 1f, 1f, tint);
    }

    /** 【27.1.2】每隔一段时间报一次「这个视频正在由 WaterMedia 播」。 */
    private static final java.util.Map<java.util.UUID, Long> WM_REPORT_MS = new java.util.HashMap<>();

    private static void reportWaterMedia(VideoWidget w) {
        long now = System.currentTimeMillis();
        Long last = WM_REPORT_MS.get(w.id);
        if (last != null && now - last < 30_000L) {
            return;
        }
        WM_REPORT_MS.put(w.id, now);
        top.hmjmfabc.projector.Projector.LOGGER.info("[Projector][视频] WaterMedia 播放中: 素材={}（{}）｜{}",
                w.mediaName, w.mediaId.substring(0, Math.min(8, w.mediaId.length())),
                top.hmjmfabc.projector.client.media.wm.WaterMediaVideos.report());
    }

    /** 【rc-86】把「这个视频控件一帧有多大、放多大、多少帧率」写进日志（只在该变了才写）。 */
    private static final java.util.Map<java.util.UUID, String> VIDEO_REPORT = new java.util.HashMap<>();
    /** 【rc-88】上一行「视频控件」日志的时刻：以前帧号一变就打印 ⇒ 10 行/秒、几分钟几 MB。 */
    private static long lastVideoReportMs;

    private static void reportVideo(VideoWidget w, int frame) {
        try {
            // 【rc-88】限流：内容变化 + 至少 2 秒一行。真出问题时日志要能一眼看穿，
            // 每秒十行的「帧=132/241」会把别的线索全冲掉。
            if (System.currentTimeMillis() - lastVideoReportMs < 2000L) return;
            int[] sz = MediaCache.decodedSize(w.mediaId);
            String line = String.format(java.util.Locale.ROOT,
                    "素材=%s 框=%.1fx%.1f 单位(%.2fx%.2f 格) 解码=%s 帧=%d/%d fps=%.1f 循环=%s",
                    w.mediaName == null || w.mediaName.isEmpty() ? w.mediaId : w.mediaName,
                    w.w, w.h, w.w / 16, w.h / 16,
                    sz == null ? "未就绪" : (sz[0] + "x" + sz[1]),
                    frame, Math.max(1, w.frameCount), w.fps, w.loop);
            if (!line.equals(VIDEO_REPORT.put(w.id, line))) {
                lastVideoReportMs = System.currentTimeMillis();
                top.hmjmfabc.projector.Projector.LOGGER.info("[Projector] 视频控件: {}", line);
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // 时钟
    // ------------------------------------------------------------------

    /**
     * 时钟：按「标题 / 时钟本体 / 午别」三条带子垂直排列（用户 ⑦）。
     *
     * <p>三段共用<b>同一个旋转轴</b>（控件左下角）——否则控件一旋转，
     * 三条带子会因为各自以自己方框的角为轴而散架。</p>
     */
    private static void drawClock(QuadCollector collector, PlaneRenderContext ctx, ClockWidget w) {
        if (degenerate(w)) return;
        if (w.background != 0) {
            solidRect(collector, ctx, w, w.x, w.y, w.x + w.w, w.y + w.h, w.background);
        }
        long dayTime = currentDayTime();
        String[] lines = w.clockLines(dayTime);

        double lineH = Math.max(1.0, w.fontSize) * 1.25;
        double titleH = w.hasTitle() ? Math.max(1.0, w.titleSize) * 1.25 : 0;
        double periodH = w.showPeriod ? Math.max(1.0, w.periodSize) * 1.25 : 0;
        double total = titleH + lineH * lines.length + periodH;
        double y = w.y + Math.max(0, (w.h - total) * 0.5);

        // 【关键】画布 y 轴是**向上**的！所以「先画的在下面、后画的在上面」。
        // 原先按「标题→时钟→午别」的顺序画，结果标题被放到了最底下、
        // 午别跑到了最上面 —— 与用户要求（标题在最上、午别在最下）刚好相反。
        // 正确顺序：先画最底下的午别，再画时钟，最后画最上面的标题。
        if (periodH > 0) {
            TextRenderer.drawInBox(collector, ctx, FontManager.get(w.periodFontId),
                    colorPrefix(w.periodColor) + w.periodLabel(dayTime),
                    Math.max(1.0, w.periodSize), -1, 1.0, 1, 1,
                    w.x, y, w.w, periodH, w.rot, w.alpha, ctx.depth(), w.x, w.y);
            y += periodH;
        }
        TtfFont clockFont = FontManager.get(w.fontId);
        // 画布 y 轴向上：**最后画的在最上面**。clockLines() 约定「索引 0 = 最上面一行」，
        // 所以这里必须**倒着画**，否则堆叠样式的钟会变成「分钟在上、小时在下」。
        for (int i = lines.length - 1; i >= 0; i--) {
            TextRenderer.drawInBox(collector, ctx, clockFont, colorPrefix(w.color) + lines[i],
                    Math.max(1.0, w.fontSize), -1, 1.0, 1, 1,
                    w.x, y, w.w, lineH, w.rot, w.alpha, ctx.depth(), w.x, w.y);
            y += lineH;
        }
        if (titleH > 0) {
            TextRenderer.drawInBox(collector, ctx, FontManager.get(w.titleFontId),
                    colorPrefix(w.titleColor) + w.title,
                    Math.max(1.0, w.titleSize), -1, 1.0, 1, 1,
                    w.x, y, w.w, titleH, w.rot, w.alpha, ctx.depth(), w.x, w.y);
        }
    }

    // ------------------------------------------------------------------
    // 天气
    // ------------------------------------------------------------------

    private static void drawWeather(QuadCollector collector, PlaneRenderContext ctx, WeatherWidget w) {
        if (degenerate(w)) return;
        if (w.background != 0) {
            solidRect(collector, ctx, w, w.x, w.y, w.x + w.w, w.y + w.h, w.background);
        }
        WeatherState state = currentWeather();
        double iconSize = Math.min(w.h, Math.max(1, w.h)) * clamp(w.iconScale, 0.2, 1.0);
        double textX = w.x;
        boolean iconOk = w.showIcon && QuadCollector.textureReady(state.icon);
        if (iconOk) {
            collector.setRenderType(QuadCollector.imageType(state.icon));
            double[] p0 = TextRenderer.rot(w.x, w.y, w.rot, 0, (w.h - iconSize) / 2);
            double[] p1 = TextRenderer.rot(w.x, w.y, w.rot, iconSize, (w.h - iconSize) / 2);
            double[] p2 = TextRenderer.rot(w.x, w.y, w.rot, iconSize, (w.h + iconSize) / 2);
            double[] p3 = TextRenderer.rot(w.x, w.y, w.rot, 0, (w.h + iconSize) / 2);
            collector.canvasQuad(ctx.axisX(), ctx.axisY(), ctx.normal(), ctx.origin(),
                    p0[0], p0[1], p1[0], p1[1], p2[0], p2[1], p3[0], p3[1],
                    ctx.depth(), 0f, 0f, 1f, 1f, QuadCollector.withAlpha(0xFFFFFFFF, w.alpha));
            textX = w.x + iconSize * 1.15;
        }
        if (w.showText) {
            String label = colorPrefix(w.color) + state.label;
            TtfFont font = FontManager.get(w.fontId);
            // 【⑨.1 关联修正】文字的旋转轴必须与图标/控件方框一致（控件左下角），
            // 以前这里把轴设成了 (textX, w.y)——图标用 w.x、文字用 textX，
            // 控件一旦旋转，图标与文字就会各转各的。
            TextRenderer.drawInBox(collector, ctx, font, label, w.fontSize, -1, 1.15, 0, 1,
                    textX, w.y, Math.max(1, w.x + w.w - textX), w.h, w.rot, w.alpha, ctx.depth(),
                    w.x, w.y);
        }
    }

    /** 当前天气状态。 */
    public record WeatherState(ResourceLocation icon, String label, int argb) {
    }

    public static WeatherState currentWeather() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return new WeatherState(ICON_CLEAR, "\u6674\u5929", 0xFFFFFFFF);
        }
        ResourceKey<Level> dim = level.dimension();
        if (dim.equals(Level.END)) {
            return new WeatherState(ICON_END, "\u672b\u5730", 0xFFC08CFF);
        }
        if (dim.equals(Level.NETHER)) {
            return new WeatherState(ICON_NETHER, "\u4e0b\u754c", 0xFFFF8C5A);
        }
        if (level.isThundering()) {
            return new WeatherState(ICON_THUNDER, "\u96f7\u96e8", 0xFF8C8CFF);
        }
        if (level.isRaining()) {
            // 分辨雪：检查玩家所在生物群系是否有降水且寒冷
            if (isSnowing(level)) {
                return new WeatherState(ICON_SNOW, "\u4e0b\u96ea", 0xFFE8F4FF);
            }
            return new WeatherState(ICON_RAIN, "\u4e0b\u96e8", 0xFF7FB4FF);
        }
        return new WeatherState(ICON_CLEAR, "\u6674\u5929", 0xFFFFE066);
    }

    private static boolean isSnowing(ClientLevel level) {
        var player = Minecraft.getInstance().player;
        if (player == null) return false;
        var biome = level.getBiome(player.blockPosition());
        return biome.value().coldEnoughToSnow(player.blockPosition());
    }

    // ------------------------------------------------------------------
    // 百分比进度
    // ------------------------------------------------------------------

    private static void drawProgress(QuadCollector collector, PlaneRenderContext ctx, ProgressWidget w, Plane plane) {
        if (degenerate(w)) return;
        if (w.background != 0) {
            solidRect(collector, ctx, w, w.x, w.y, w.x + w.w, w.y + w.h, w.background);
        }
        long gameTime = currentGameTime();
        int percent = w.percent(gameTime);
        // 字体固定为 Caviar Dreams（设计要求）
        TtfFont font = FontManager.get(Fonts.CAVIAR_DREAMS);

        double titleSize = Math.max(1.0, w.titleSize);
        double valueSize = Math.max(1.0, w.valueSize);
        double valueLine = valueSize * 1.25;
        double titleLine = titleSize * 1.25;
        double total = valueLine + titleLine;
        double top = w.y + Math.max(0, (w.h - total) * 0.5);

        // 下方大字：xx%  —— 数字使用统一宽度（等宽数字观感），"%"略小
        String valueText = (percent < 0 ? 0 : percent) + (w.showPercentSign ? "%" : "");
        TextRenderer.drawInBox(collector, ctx, font, colorPrefix(w.valueColor) + valueText,
                valueSize, -1, 1.0, 1, 1,
                w.x, top, w.w, valueLine, w.rot, w.alpha, ctx.depth());

        // 上方小字：已完成
        TextRenderer.drawInBox(collector, ctx, font, w.title,
                titleSize, -1, 1.0, 1, 1,
                w.x, top + valueLine, w.w, titleLine, w.rot, w.alpha, ctx.depth());
    }

    // ------------------------------------------------------------------
    // ⑧ 计时器
    // ------------------------------------------------------------------

    private static void drawTimer(QuadCollector collector, PlaneRenderContext ctx,
                                  top.hmjmfabc.projector.common.widget.TimerWidget w) {
        if (degenerate(w)) return;
        if (w.background != 0) {
            solidRect(collector, ctx, w, w.x, w.y, w.x + w.w, w.y + w.h, w.background);
        }
        long gameTime = currentGameTime();
        TtfFont font = FontManager.get(w.fontId);
        String text = w.text(gameTime);
        float alpha = w.alpha * w.fancyAlphaFactor(gameTime);

        // 花哨倒计时最后 10 秒：用刚实现的 &z 彩色渐变渲染，天然就是「炫彩」
        String styled = w.madnessRainbow(gameTime)
                ? "&z" + text
                : colorPrefix(w.fancyColor(gameTime)) + text;

        TextRenderer.drawInBox(collector, ctx, font, styled, Math.max(1.0, w.fontSize), -1, 1.15, 1, 1,
                w.x, w.y, w.w, w.h, w.rot, alpha, ctx.depth(), w.x, w.y);
    }

    // ------------------------------------------------------------------
    // ③ 排行榜
    // ------------------------------------------------------------------

    private static void drawLeaderboard(QuadCollector collector, PlaneRenderContext ctx,
                                        top.hmjmfabc.projector.common.widget.LeaderboardWidget w) {
        if (degenerate(w)) return;
        if (w.background != 0) {
            solidRect(collector, ctx, w, w.x, w.y, w.x + w.w, w.y + w.h, w.background);
        }
        java.util.List<top.hmjmfabc.projector.client.LeaderboardSource.Row> rows =
                top.hmjmfabc.projector.client.LeaderboardSource.rows(
                        w.objective, w.descending, w.safeMaxRows());

        double titleH = w.showTitle && w.title != null && !w.title.isEmpty()
                ? Math.max(1.0, w.titleSize) * 1.25 : 0;
        double rowH = Math.max(1.0, w.rowSize) * 1.25 * Math.max(0.4, w.lineSpacing);
        double total = titleH + rowH * rows.size();
        // 画布 y 轴向上：bottom 是最底下那一行。标题必须在**最高**的 y 上。
        double bottom = w.y + Math.max(0, (w.h - total) * 0.5);
        // 第 1 名要在**最上面**（紧贴标题），所以从最高一行往下画
        double y = bottom + Math.max(0, rows.size() - 1) * rowH;
        if (titleH > 0) {
            TextRenderer.drawInBox(collector, ctx, FontManager.get(w.titleFontId),
                    colorPrefix(w.titleColor) + w.title, Math.max(1.0, w.titleSize), -1, 1.0, 1, 1,
                    w.x, bottom + rowH * rows.size(), w.w, titleH, w.rot, w.alpha, ctx.depth(), w.x, w.y);
        }

        // 序号 / 玩家名 / 分数三列的宽度：分数右对齐、序号左对齐、名字居中占剩余空间
        double indexW = w.showIndex ? Math.max(1.0, w.rowSize) * 2.0 : 0;
        double scoreW = w.showScore ? Math.max(1.0, w.rowSize) * 3.2 : 0;
        double nameW = Math.max(1.0, w.w - indexW - scoreW);
        TtfFont indexFont = FontManager.get(w.indexFontId);
        TtfFont nameFont = FontManager.get(w.nameFontId);
        TtfFont scoreFont = FontManager.get(w.scoreFontId);

        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            if (w.showIndex) {
                TextRenderer.drawInBox(collector, ctx, indexFont,
                        colorPrefix(w.indexColor) + (i + 1), Math.max(1.0, w.rowSize), -1, 1.0, 0, 1,
                        w.x, y, indexW, rowH, w.rot, w.alpha, ctx.depth(), w.x, w.y);
            }
            TextRenderer.drawInBox(collector, ctx, nameFont,
                    colorPrefix(w.nameColor) + row.name(), Math.max(1.0, w.rowSize), -1, 1.0, 0, 1,
                    w.x + indexW, y, nameW, rowH, w.rot, w.alpha, ctx.depth(), w.x, w.y);
            if (w.showScore) {
                TextRenderer.drawInBox(collector, ctx, scoreFont,
                        colorPrefix(w.scoreColor) + row.score(), Math.max(1.0, w.rowSize), -1, 1.0, 2, 1,
                        w.x + indexW + nameW, y, scoreW, rowH, w.rot, w.alpha, ctx.depth(), w.x, w.y);
            }
            y -= rowH;   // 往下走一行（画布 y 轴向上，减小 y 就是往下）
        }

        // 计分板项没设置 / 找不到时给一句可见提示，免得玩家面对空白控件不知道哪里错了
        if (rows.isEmpty()) {
            String hint = (w.objective == null || w.objective.isEmpty())
                    ? "\uff08\u672a\u8bbe\u7f6e\u8ba1\u5206\u677f\u9879\uff09"
                    : "\uff08\u627e\u4e0d\u5230 " + w.objective + "\uff09";
            TextRenderer.drawInBox(collector, ctx, nameFont, "&7" + hint,
                    Math.max(1.0, w.rowSize), -1, 1.0, 1, 1,
                    w.x, w.y, w.w, w.h, w.rot, w.alpha, ctx.depth(), w.x, w.y);
        }
    }

    // ------------------------------------------------------------------
    // 【⑪】棋类游戏
    // ------------------------------------------------------------------

    /** 造一个只有 depth 不同的渲染上下文（用于错开共面图层，消除 Z-fighting）。 */
    private static PlaneRenderContext depthShift(PlaneRenderContext ctx, double depth) {
        return new PlaneRenderContext(ctx.axisX(), ctx.axisY(), ctx.normal(), ctx.origin(), depth);
    }

    /**
     * 棋盘各图层沿法线的错开量（方块）。
     *
     * <p><b>为什么必须这么大：</b>深度缓冲的分辨率随距离平方衰减——
     * {@code Δz ≈ z² / (near × 2^24)}（near ≈ 0.05、24 位深度）。
     * 取 0.0009 时，20 格处 Δz≈0.0005 勉强够、50 格处 Δz≈0.003 就已经不够了，
     * 于是「分割线依旧乱闪」。现在每层 0.015 方块（1.5 厘米）：
     * 64 格处 Δz≈0.005，仍然有 3 倍余量，而 1.5 厘米贴面偏移肉眼完全看不出来。</p>
     */
    private static final double CHESS_LAYER = 0.015;

    /** 棋盘的诊断只对「棋种+列数+行数+字体」变化时打一次，不刷屏。 */
    private static final java.util.Map<java.util.UUID, String> CHESS_REPORT = new java.util.HashMap<>();
    /** 棋盘诊断的限流时间戳（毫秒）：斗蛐蛐自动对打时「棋子=」每手都变。 */
    private static final java.util.Map<java.util.UUID, Long> CHESS_REPORT_MS = new java.util.HashMap<>();

    public static void drawChess(QuadCollector collector, PlaneRenderContext ctx,
                                 top.hmjmfabc.projector.common.widget.ChessWidget w) {
        if (degenerate(w)) return;
        w.ensureBoard();
        var g = w.game;
        if (g == null) return;
        GameRules rules = g.rules();
        int cw = rules.width(), ch = rules.height();
        if (cw <= 0 || ch <= 0) return;

        // 四层沿法线错开（见 CHESS_LAYER 的说明）
        final double dBase = ctx.depth();
        final double dGrid = dBase + CHESS_LAYER;
        final double dMark = dBase + CHESS_LAYER * 2;
        final double dPiece = dBase + CHESS_LAYER * 3;
        PlaneRenderContext ctxGrid = depthShift(ctx, dGrid);
        PlaneRenderContext ctxMark = depthShift(ctx, dMark);

        if (w.background != 0) {
            solidRect(collector, ctx, w, w.x, w.y, w.x + w.w, w.y + w.h, w.background);
        }
        // 棋盘底色（压在所有格线之下）
        solidRect(collector, ctx, w, w.x, w.y, w.x + w.w, w.y + w.h, w.boardColor);

        // ---- 几何布局：交叉点式 vs 格心式 ----
        // 【唯一来源】几何与「点位中心」都由 ChessWidget 提供，渲染与命中检测共用，
        // 避免两边各算一套导致「看着点在子上、实际点到隔壁」。
        var geo = w.geometry();
        final boolean intersections = geo.intersections();
        double spacing = geo.spacing();
        double ox = geo.ox();
        double oy = geo.oy();
        double lineT = Math.max(1.1, Math.min(spacing * 0.09, 3.0));

        // ---- 格线 ----
        if (w.showGrid) {
            if (intersections) {
                // 竖线：cw 条，各贯穿整盘
                for (int c = 0; c < cw; c++) {
                    double lx = ox + c * spacing - lineT * 0.5;
                    solidRect(collector, ctxGrid, w, lx, oy - lineT * 0.5,
                            lx + lineT, oy + (ch - 1) * spacing + lineT * 0.5, w.lineColor);
                }
                for (int r = 0; r < ch; r++) {
                    double ly = oy + r * spacing - lineT * 0.5;
                    solidRect(collector, ctxGrid, w, ox - lineT * 0.5, ly,
                            ox + (cw - 1) * spacing + lineT * 0.5, ly + lineT, w.lineColor);
                }
                // 中国象棋额外画九宫斜线（不画的话一眼认不出是象棋盘）。
                // solidRect 只能画轴对齐矩形，所以斜线用「阶梯法」：沿对角线摆一串小方块。
                if (g.kind == top.hmjmfabc.projector.common.game.GameKind.XIANGQI) {
                    for (int side = 0; side < 2; side++) {
                        int r0 = side == 0 ? 0 : 9;
                        int r1 = side == 0 ? 2 : 7;
                        for (int k = 0; k <= 24; k++) {
                            double t = k / 24.0;
                            double dx = ox + (3 + 2 * t) * spacing;
                            double dy = oy + (r0 + (r1 - r0) * t) * spacing;
                            solidRect(collector, ctxGrid, w,
                                    dx - lineT * 0.5, dy - lineT * 0.5,
                                    dx + lineT * 0.5, dy + lineT * 0.5, w.lineColor);
                            dx = ox + (5 - 2 * t) * spacing;
                            solidRect(collector, ctxGrid, w,
                                    dx - lineT * 0.5, dy - lineT * 0.5,
                                    dx + lineT * 0.5, dy + lineT * 0.5, w.lineColor);
                        }
                    }
                }
            } else {
                // 格心式：把所有格子的四条边画成 (cw+1) + (ch+1) 条线
                for (int c = 0; c <= cw; c++) {
                    double lx = ox + c * spacing - lineT * 0.5;
                    solidRect(collector, ctxGrid, w, lx, oy - lineT * 0.5,
                            lx + lineT, oy + ch * spacing + lineT * 0.5, w.lineColor);
                }
                for (int r = 0; r <= ch; r++) {
                    double ly = oy + r * spacing - lineT * 0.5;
                    solidRect(collector, ctxGrid, w, ox - lineT * 0.5, ly,
                            ox + cw * spacing + lineT * 0.5, ly + lineT, w.lineColor);
                }
            }
        }

        // ---- 最后一手高亮（中心由 centerOf 给出，与命中检测同源）----
        if (w.highlightLast && g.lastTo >= 0 && g.lastTo < cw * ch) {
            int lx = g.lastTo % cw, ly = g.lastTo / cw;
            double half = spacing * (intersections ? 0.42 : 0.5);
            double[] c = w.centerOf(lx, ly);
            solidRect(collector, ctxMark, w, c[0] - half, c[1] - half,
                    c[0] + half, c[1] + half, w.highlightColor);
        }
        // 世界内点选时选中的棋子
        if (w.selCol >= 0 && w.selCol < cw && w.selRow >= 0 && w.selRow < ch) {
            double half = spacing * (intersections ? 0.46 : 0.5);
            double[] c = w.centerOf(w.selCol, w.selRow);
            solidRect(collector, ctxMark, w, c[0] - half, c[1] - half,
                    c[0] + half, c[1] + half, 0x90FFD479);
        }

        // ---- 【rc-84】合法落点「着重显示」----
        // 玩家原话：「五子棋第一步那几个特殊的点应该着重显示，要不然只提示不合法」。
        // 这条同样治象棋：选中一个子就把它能走到的点画出来，玩家不必靠「点了才知道」。
        // 高亮的点与点击判定同源（都来自规则层的 legalMoves），不存在第二套真相。
        try {
            for (top.hmjmfabc.projector.common.game.Move hm : w.hintMoves()) {
                double[] c = w.centerOf(hm.tx(), hm.ty());
                boolean capture = g.at(hm.tx(), hm.ty()) != 0;
                if (w.selCol < 0 && g.moveCount == 0) {
                    // 第一手：整块点亮 + 外圈，务必一眼看到（空盘时只有「天元」一个点）
                    double big = spacing * 0.42;
                    solidRect(collector, ctxMark, w, c[0] - big, c[1] - big,
                            c[0] + big, c[1] + big, 0xCC66FFB0);
                    double mid = spacing * 0.24;
                    solidRect(collector, ctxMark, w, c[0] - mid, c[1] - mid,
                            c[0] + mid, c[1] + mid, 0xFFFFFFFF);
                } else {
                    // 可走到的点：小方块；吃子目标画大一点、换暖色，和「走过去」区分开
                    double r = spacing * (capture ? 0.34 : 0.17);
                    solidRect(collector, ctxMark, w, c[0] - r, c[1] - r,
                            c[0] + r, c[1] + r, capture ? 0xB0FF8080 : 0xA0FFD479);
                }
            }
        } catch (Throwable t) {
            // 高亮只是辅助，任何异常都不该让棋盘画不出来
        }

        // ---- 棋子 ----
        TtfFont font = FontManager.get(w.fontId);
        if (font == null) font = FontManager.get(Fonts.MINECRAFT_AE);
        double box = intersections ? spacing : spacing;
        double fs = box * (intersections ? 0.92 : 0.78);
        if (w.fontSize > 0 && Math.abs(w.fontSize - 12.0) > 0.01) {
            fs = Math.min(w.fontSize, box * 1.2);
        }
        fs = Math.max(1.0, fs);
        int pieces = 0;
        for (int row = 0; row < ch; row++) {
            for (int col = 0; col < cw; col++) {
                int p = g.at(col, row);
                if (p == 0) continue;
                pieces++;
                String glyph = rules.glyph(p);
                if (glyph == null || glyph.isEmpty()) continue;
                // 棋子的绘制方框左下角 = 中心 - 半个 box（两种布局统一算法）
                double[] ctr = w.centerOf(col, row);
                double bx = ctr[0] - box * 0.5;
                double by = ctr[1] - box * 0.5;
                TextRenderer.drawInBox(collector, ctx, font,
                        colorPrefix(w.sideColor(Integer.signum(p))) + glyph,
                        fs, -1, 1.0, 1, 1,
                        bx, by, box, box,
                        w.rot, w.alpha, dPiece, w.x, w.y);
            }
        }

        // 一次性诊断：棋类控件以前难查（画了 N 个四边形却看不出内容），
        // 所以把「到底算了什么」落一行日志。只在关键参数变化时打印。
        String report = String.format(java.util.Locale.ROOT,
                "%s %dx%d %s 间距=%.2f 框=%.1fx%.1f 字号=%.1f 棋子=%d 字体=%s",
                top.hmjmfabc.projector.common.game.GameKind.name(g.kind), cw, ch,
                intersections ? "交叉点式" : "格心式",
                spacing, w.w, w.h, fs, pieces,
                font == null ? "null!!" : FontManager.displayName(w.fontId));
        if (!report.equals(CHESS_REPORT.put(w.id, report))) {
            // 关键异常（一个子都没画出来 / 字体没找到 / 间距太小）永远立刻打；
            // 其余情况限流：斗蛐蛐自动对打时「棋子=」每手都变，不限流会一直刷日志。
            long now = System.currentTimeMillis();
            boolean urgent = pieces == 0 || font == null || spacing < 6.0;
            if (urgent || now - CHESS_REPORT_MS.getOrDefault(w.id, 0L) >= 400L) {
                CHESS_REPORT_MS.put(w.id, now);
                top.hmjmfabc.projector.Projector.LOGGER.info("[Projector] 棋盘控件: {}", report);
                if (spacing < 6.0) {
                    top.hmjmfabc.projector.Projector.LOGGER.warn(
                            "[Projector] 棋盘点位间距只有 {} 画布单位（约 {} 格方块），会看不清；"
                                    + "请在编辑界面按「切换棋种」重新撑框，或把控件框拉大",
                            String.format(java.util.Locale.ROOT, "%.2f", spacing),
                            String.format(java.util.Locale.ROOT, "%.2f", spacing / 16.0));
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 生成一个把颜色设为指定 ARGB 的格式化代码前缀。 */
    public static String colorPrefix(int argb) {
        return "&#" + String.format(java.util.Locale.ROOT, "%06X", argb & 0xFFFFFF);
    }

    /** 把 ARGB 近似映射到 16 色代码（保留给以后可能用到的场景）。 */
    public static int nearestLegacyCode(int argb) {
        int best = 15;
        long bestDist = Long.MAX_VALUE;
        for (int i = 0; i < top.hmjmfabc.projector.common.text.FormatCodes.COLORS.length; i++) {
            int c = top.hmjmfabc.projector.common.text.FormatCodes.COLORS[i];
            long dr = ((c >> 16) & 0xFF) - ((argb >> 16) & 0xFF);
            long dg = ((c >> 8) & 0xFF) - ((argb >> 8) & 0xFF);
            long db = (c & 0xFF) - (argb & 0xFF);
            long d = dr * dr + dg * dg + db * db;
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    private static void placeholder(QuadCollector collector, PlaneRenderContext ctx, Widget w, int argb) {
        solidRect(collector, ctx, w, w.x, w.y, w.x + w.w, w.y + w.h, QuadCollector.withAlpha(argb, w.alpha));
    }

    // ------------------------------------------------------------------
    // 【⑫】下载中：控件中间显示「加载中…／（已收/总量，xx%）」
    // ------------------------------------------------------------------

    /**
     * 正在从服务端下载时，在占位色块上叠两行字。
     *
     * <p>字体<b>必须用内置的 Minecraft AE</b>：本方法要显示中文「加载中…」，
     * 而另一个内置字体 Caviar Dreams 不含任何中文字形，用它只会得到一排方框
     * （见 AGENTS.md §3.2 的平台性限制）。</p>
     */
    private static void loadingPlaceholder(QuadCollector collector, PlaneRenderContext ctx, Widget w,
                                           @Nullable String hash, int frame, int bg) {
        placeholder(collector, ctx, w, bg);
        if (hash == null || hash.isEmpty()) return;
        long[] p;
        try {
            p = MediaCache.transferProgress(hash, frame);
        } catch (Throwable t) {
            return;
        }
        if (p == null) return;
        long got = Math.max(0, p[0]);
        long total = Math.max(0, p[1]);
        int pct = total <= 0 ? 0 : (int) Math.min(100L, got * 100L / total);
        String line2 = "(" + humanBytes(got) + "/" + humanBytes(total) + ", " + pct + "%)";

        TtfFont font = FontManager.get(Fonts.MINECRAFT_AE);
        if (font == null) font = FontManager.get(null);
        if (font == null) return;
        // 字号随控件大小走，但不能小到看不清
        double size = Math.max(4.0, Math.min(w.h * 0.20, w.w * 0.13));
        double lineH = size * 1.35;
        double top = w.y + Math.max(0, (w.h - lineH * 2) * 0.5);
        TextRenderer.drawInBox(collector, ctx, font, "&f\u52a0\u8f7d\u4e2d\u2026", size, -1, 1.15, 1, 1,
                w.x, top, w.w, lineH, w.rot, w.alpha, ctx.depth(), w.x, w.y);
        TextRenderer.drawInBox(collector, ctx, font, "&7" + line2, size * 0.8, -1, 1.15, 1, 1,
                w.x, top + lineH, w.w, lineH, w.rot, w.alpha, ctx.depth(), w.x, w.y);
    }

    /** 人类可读的字节数（B / KB / MB）。 */
    public static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024L * 1024L) {
            return String.format(java.util.Locale.ROOT, "%.1fKB", bytes / 1024.0);
        }
        return String.format(java.util.Locale.ROOT, "%.2fMB", bytes / 1048576.0);
    }

    private static void solidRect(QuadCollector collector, PlaneRenderContext ctx, Widget w,
                                  double x0, double y0, double x1, double y1, int argb) {
        if (argb >>> 24 == 0) return;
        if (Math.abs(x1 - x0) < 1.0e-4 || Math.abs(y1 - y0) < 1.0e-4) return;
        double[] p0 = TextRenderer.rot(w.x, w.y, w.rot, x0 - w.x, y0 - w.y);
        double[] p1 = TextRenderer.rot(w.x, w.y, w.rot, x1 - w.x, y0 - w.y);
        double[] p2 = TextRenderer.rot(w.x, w.y, w.rot, x1 - w.x, y1 - w.y);
        double[] p3 = TextRenderer.rot(w.x, w.y, w.rot, x0 - w.x, y1 - w.y);
        collector.setRenderType(QuadCollector.imageType(WHITE));
        collector.canvasQuad(ctx.axisX(), ctx.axisY(), ctx.normal(), ctx.origin(),
                p0[0], p0[1], p1[0], p1[1], p2[0], p2[1], p3[0], p3[1],
                ctx.depth(), 0f, 0f, 1f, 1f, argb);
    }

    /** 纯白纹理（由 {@code ProjectorClient} 在启动时生成并注册）。 */
    public static ResourceLocation WHITE = top.hmjmfabc.projector.Projector.id("dynamic/white");

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    /** 当前游戏刻（客户端）。 */
    public static long currentGameTime() {
        ClientLevel level = Minecraft.getInstance().level;
        return level == null ? 0L : level.getGameTime();
    }

    /** 当前世界时间（用于夜晚/白天判断与时钟）。 */
    public static long currentDayTime() {
        ClientLevel level = Minecraft.getInstance().level;
        return level == null ? 0L : level.getDayTime();
    }

    /** 当前真实时间（毫秒），用于视频本地播放。 */
    public static long clientTimeMs() {
        return System.currentTimeMillis();
    }

    /** 调试信息入口（F3 之类场景可用）。 */
    public static int cachedImageCount() {
        return MediaCache.cachedImages();
    }
}

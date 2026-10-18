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
            // 缩放以「控件中心」为基准：绕左下角放大会让控件整体往右上跑，很别扭。
            // 横纵可以不一样：「擦除展开」把宽度从 0 拉开、「翻页展开」把高度从 0 拉开。
            double sx = st.effScaleX();
            double sy = st.effScaleY();
            if (sx != 1.0 || sy != 1.0) {
                double nw = ow * sx, nh = oh * sy;
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
            // 【27.2】网页控件：贴网页画面（纹理来自会话层）+ 底部按钮栏，
            // 画法与视频分支同源（同一份 canvasQuad / 同一个 RenderType.text）。
            case Widget.KIND_WEB -> WebWidgetRenderer.draw(collector, plane, ctx,
                    (top.hmjmfabc.projector.common.widget.WebWidget) w);
            // 【27.2.2】展示框：轮廓 + 物品贴图（右键放入 / 左键取出）
            case Widget.KIND_ITEM -> ItemWidgetRenderer.draw(collector, ctx,
                    (top.hmjmfabc.projector.common.widget.ItemWidget) w);
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------
    // 文本
    // ------------------------------------------------------------------

    private static void drawText(QuadCollector collector, PlaneRenderContext ctx, TextWidget w) {
        // 【27.2-pre-136 修复】底色必须**先画**：以前它排在文字后面，两者又在同一深度，
        // 于是「设了底色的文本控件」会被底色整个盖住 —— 玩家只会看到一块纯色。
        if (w.background != 0) {
            solidRect(collector, ctx, w, 0, 0, w.w, w.h, w.background);
        }
        TtfFont font = FontManager.get(w.fontId);
        // 【27.2-pre-136】「打字」动画：只画出「已经打出来的那部分」。
        // 时钟与流程动画用的是同一个 gameTime，所以编辑器里也能看到它在打。
        String shown = typedPrefix(w);
        TextRenderer.drawInBox(collector, ctx, font, shown, w.fontSize, w.wrapWidth, w.lineSpacing,
                w.align, 1, w.x, w.y, w.w, w.h, w.rot, w.alpha, ctx.depth(), w.rotX(), w.rotY());
    }

    /**
     * 打字动画当前应当画出的文本（未开启时原样返回）。
     *
     * <p>截断走 {@code TextLayout.visiblePrefix}：格式化代码不算字数、也不会被截成半截。</p>
     */
    private static String typedPrefix(TextWidget w) {
        if (!w.typewriter || w.text == null || w.text.isEmpty()) {
            return w.text;
        }
        int n = w.typedChars(currentGameTime());
        if (n == Integer.MAX_VALUE) return w.text;
        return top.hmjmfabc.projector.common.text.TextLayout.visiblePrefix(w.text, n);
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
            solidRect(collector, ctx, w, 0, 0, w.w, w.h, w.background);
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
        double[] p0 = w.rotPt(0, 0);
        double[] p1 = w.rotPt(w.w, 0);
        double[] p2 = w.rotPt(w.w, w.h);
        double[] p3 = w.rotPt(0, w.h);
        collector.canvasQuad(ctx.axisX(), ctx.axisY(), ctx.normal(), ctx.origin(),
                p0[0], p0[1], p1[0], p1[1], p2[0], p2[1], p3[0], p3[1],
                ctx.depth(), (float) w.u0, (float) w.v0, (float) w.u1, (float) w.v1, tint);
    }

    /**
     * 每个图片控件只在上报内容变化时打一条日志，便于定位「图片看不见」。
     *
     * <p>【27.2-pre-136】以前这里是「每帧先 {@code String.format} 一整行、再和上次比」——
     * 字符串是造出来了才发现没变。现在先比一个<b>轻量签名</b>（没有格式化），
     * 只有真的变了才拼日志串。图片控件多的时候这是纯白省的每帧开销。</p>
     */
    private static final java.util.Map<java.util.UUID, ImageSig> IMAGE_REPORT = new java.util.HashMap<>();

    /** 图片上报的「内容签名」：全是基本量，构造它不涉及格式化。 */
    private record ImageSig(String media, int srcW, int srcH, double boxW, double boxH, String state) {
    }

    private static void reportImage(ImageWidget w, String state, int srcW, int srcH) {
        ImageSig sig = new ImageSig(
                w.mediaName == null || w.mediaName.isEmpty() ? w.mediaId : w.mediaName,
                srcW, srcH, w.w, w.h, state);
        ImageSig old = IMAGE_REPORT.put(w.id, sig);
        if (sig.equals(old)) {
            return;
        }
        String line = String.format(java.util.Locale.ROOT,
                "素材=%s(%d×%d) 框=%.1fx%.1f 单位(%.2fx%.2f 格) 状态=%s",
                sig.media(), srcW, srcH, w.w, w.h, w.w / 16, w.h / 16, state);
        top.hmjmfabc.projector.Projector.LOGGER.info("[Projector] 图片控件: {}", line);
    }

    // ------------------------------------------------------------------
    // 视频
    // ------------------------------------------------------------------

    private static void drawVideo(QuadCollector collector, PlaneRenderContext ctx, VideoWidget w) {
        if (degenerate(w)) return;
        // 【hotfix-98】画面（含各种占位）照旧，之后统一叠加「世界内小播放键 + 进度条」。
        // 浮层单独一层，与画面内容互不干扰，也不影响下面这些提前 return。
        drawVideoBase(collector, ctx, w);
        if (top.hmjmfabc.projector.client.media.VideoControls.visible(w)) {
            VideoOverlayRenderer.draw(collector, ctx, w);
        }
    }

    /** 视频画面本身（占位 / WaterMedia / 内置三条路）。 */
    private static void drawVideoBase(QuadCollector collector, PlaneRenderContext ctx, VideoWidget w) {
        // 【27.1.3】素材身份只从 OnlineVideos 一个入口问（在线源 = 本地缓存身份）
        final String mediaId = top.hmjmfabc.projector.client.media.net.OnlineVideos.mediaId(w);
        if (mediaId.isEmpty()) {
            placeholder(collector, ctx, w, 0x663377CC);
            return;
        }
        final boolean online = top.hmjmfabc.projector.client.media.net.OnlineVideos.online(w);
        final boolean localReady = online
                ? top.hmjmfabc.projector.client.media.net.OnlineVideoCache.ready(w.sourceUrl)
                : MediaCache.isLocallyAvailable(mediaId);
        // 【27.1.3】按需加载：**点过播放才下载**（用户要求）。
        // 【27.2.1-hotfix-144 按用户反馈修正】这道门**只挡「要联网下载」那一步**：
        //   在线源（B 站等）现在**自动解析**，不必等玩家点一下 —— 解析只是几次 HTTP 请求，
        //   不占磁盘也不烧流量；玩家报的「B 站视频不会自己解析，必须手动点一下编辑」就是这条门
        //   把解析也一起挡住了（点播放/进编辑器都会调 VideoControls.request ⇒ 手动点一下才通）。
        //   **本地**素材（要整段下载/上传的那类）仍然守「点过播放才下载」。
        if (!localReady && !online
                && !top.hmjmfabc.projector.client.media.VideoControls.requested(w)) {
            // 灰蓝占位 = 「还没加载」（与紫色「下载中」、橙色「内置加载中」、绿色「外部解码器」区分开）
            loadingPlaceholder(collector, ctx, w, mediaId, 0, 0x66808A96);
            reportNotLoaded(w);
            return;
        }
        // 在线源：交给后台解析 + 下载；没下完就画「下载中」占位（紫色，和另外两种区分开）
        if (online) {
            top.hmjmfabc.projector.client.media.net.OnlineVideos.tick(w);
            if (!top.hmjmfabc.projector.client.media.net.OnlineVideos.ready(w)) {
                loadingPlaceholder(collector, ctx, w, mediaId, 0, 0x668833FF);
                reportOnline(w);
                return;
            }
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
            loadingPlaceholder(collector, ctx, w, mediaId, 0, 0x6644CC99);
            // 注：占位色偏绿，和内置后端的橙色占位区分开（一眼看出在用哪个后端）
            return;
        }
        // 【27.2.4-201】内置帧序列后端（MJPEG / ZIP）已随 Build 112 的内置解码器一起下线。
        // 走到这里说明：这份素材连外部解码器都不认（旧存档里的 .mjpg / .zip 控件）。
        // 以前这里会退回逐帧解码 —— 那条路已经没有实现，放行只会得到「永远加载中」。
        // 现在明确画「格式不支持」占位，并只提示一次（不刷日志、也不偷偷下载几十 MB）。
        unsupportedVideo(collector, ctx, w, mediaId);
    }

    /** 「放不了」占位（一次性日志）—— 原因分两种，日志与玩家提示要分开说。 */
    private static void unsupportedVideo(QuadCollector collector, PlaneRenderContext ctx,
                                         VideoWidget w, String mediaId) {
        placeholder(collector, ctx, w, 0x66CC3366);
        String name = top.hmjmfabc.projector.client.media.net.OnlineVideos.mediaName(w);
        String shown = name == null || name.isEmpty() ? "(未命名)" : name;
        // ① 帧序列（mjpg/zip）早就没有解码器了；② 其它容器放不了 = 没装/装坏了视频解码器。
        boolean legacyFrames = shown.toLowerCase(java.util.Locale.ROOT).matches(
                ".*\\.(mjpg|mjpeg|zip|avi)$");
        if (w.id != null && UNSUPPORTED_LOGGED.add(w.id)) {
            if (legacyFrames) {
                top.hmjmfabc.projector.Projector.LOGGER.warn(
                        "[Projector][视频] 这份素材是帧序列（MJPEG / ZIP），自 Build 112 起不再支持，"
                                + "已画占位块：{}（素材={}）。请转成 MP4 / WebM / MKV 后重新添加。",
                        shown, mediaId == null || mediaId.isEmpty() ? "无" : mediaId);
            } else {
                top.hmjmfabc.projector.Projector.LOGGER.warn(
                        "[Projector][视频] 这份视频放不出来（没有可用的视频解码器），已画占位块：{}"
                                + "（素材={}）。请确认已安装视频解码前置模组、且文件没有损坏。",
                        shown, mediaId == null || mediaId.isEmpty() ? "无" : mediaId);
            }
        }
    }

    /** 已经提示过「格式不受支持」的控件（每个控件一次）。 */
    private static final java.util.Set<java.util.UUID> UNSUPPORTED_LOGGED =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    /** 【27.1.3】「还没加载」只提示一次（每个控件一次），别每帧刷屏。 */
    private static final java.util.Set<java.util.UUID> NOT_LOADED_LOGGED =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private static void reportNotLoaded(VideoWidget w) {
        if (w == null || w.id == null || !NOT_LOADED_LOGGED.add(w.id)) {
            return;
        }
        top.hmjmfabc.projector.Projector.LOGGER.info(
                "[Projector][视频] 按需加载：控件 {} 还没被点过，先不下载（点世界里的视频左下角，"
                        + "或进编辑器点「播放中/已暂停」即可开始）{}",
                w.id.toString().substring(0, 8),
                w.isOnline() ? "（在线源 " + w.sourceUrl + "）" : "");
    }

    /** 【27.1.3】在线视频每 3 秒最多报一行状态（下载进度、失败原因）。 */
    private static final java.util.Map<java.util.UUID, Long> ONLINE_REPORT_MS = new java.util.HashMap<>();

    private static void reportOnline(VideoWidget w) {
        long now = System.currentTimeMillis();
        Long last = ONLINE_REPORT_MS.get(w.id);
        if (last != null && now - last < 3000L) {
            return;
        }
        ONLINE_REPORT_MS.put(w.id, now);
        top.hmjmfabc.projector.Projector.LOGGER.info("[Projector][在线视频] 控件 {} 状态：{}（链接 {}）",
                w.id.toString().substring(0, 8),
                top.hmjmfabc.projector.client.media.net.OnlineVideos.status(w),
                w.sourceUrl);
    }

    /** 把一帧画到控件方框里（内置后端与 WaterMedia 后端共用同一份几何）。 */
    private static void drawVideoQuad(QuadCollector collector, PlaneRenderContext ctx,
                                      VideoWidget w, net.minecraft.resources.ResourceLocation texture) {
        collector.setRenderType(QuadCollector.imageType(texture));
        int tint = QuadCollector.withAlpha(w.tint, w.alpha);
        double[] p0 = w.rotPt(0, 0);
        double[] p1 = w.rotPt(w.w, 0);
        double[] p2 = w.rotPt(w.w, w.h);
        double[] p3 = w.rotPt(0, w.h);
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
        String name = top.hmjmfabc.projector.client.media.net.OnlineVideos.mediaName(w);
        String id = top.hmjmfabc.projector.client.media.net.OnlineVideos.mediaId(w);
        top.hmjmfabc.projector.Projector.LOGGER.info("[Projector][视频] WaterMedia 播放中: 素材={}（{}）｜{}",
                name, id.substring(0, Math.min(8, id.length())),
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
            String reportId = top.hmjmfabc.projector.client.media.net.OnlineVideos.mediaId(w);
            String reportName = top.hmjmfabc.projector.client.media.net.OnlineVideos.mediaName(w);
            int[] sz = MediaCache.decodedSize(reportId);
            String line = String.format(java.util.Locale.ROOT,
                    "素材=%s 框=%.1fx%.1f 单位(%.2fx%.2f 格) 解码=%s 帧=%d/%d fps=%.1f 循环=%s",
                    reportName == null || reportName.isEmpty() ? reportId : reportName,
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
            solidRect(collector, ctx, w, 0, 0, w.w, w.h, w.background);
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
                    w.x, y, w.w, periodH, w.rot, w.alpha, ctx.depth(), w.rotX(), w.rotY());
            y += periodH;
        }
        TtfFont clockFont = FontManager.get(w.fontId);
        // 画布 y 轴向上：**最后画的在最上面**。clockLines() 约定「索引 0 = 最上面一行」，
        // 所以这里必须**倒着画**，否则堆叠样式的钟会变成「分钟在上、小时在下」。
        for (int i = lines.length - 1; i >= 0; i--) {
            TextRenderer.drawInBox(collector, ctx, clockFont, colorPrefix(w.color) + lines[i],
                    Math.max(1.0, w.fontSize), -1, 1.0, 1, 1,
                    w.x, y, w.w, lineH, w.rot, w.alpha, ctx.depth(), w.rotX(), w.rotY());
            y += lineH;
        }
        if (titleH > 0) {
            TextRenderer.drawInBox(collector, ctx, FontManager.get(w.titleFontId),
                    colorPrefix(w.titleColor) + w.title,
                    Math.max(1.0, w.titleSize), -1, 1.0, 1, 1,
                    w.x, y, w.w, titleH, w.rot, w.alpha, ctx.depth(), w.rotX(), w.rotY());
        }
    }

    // ------------------------------------------------------------------
    // 天气
    // ------------------------------------------------------------------

    private static void drawWeather(QuadCollector collector, PlaneRenderContext ctx, WeatherWidget w) {
        if (degenerate(w)) return;
        if (w.background != 0) {
            solidRect(collector, ctx, w, 0, 0, w.w, w.h, w.background);
        }
        WeatherState state = currentWeather();
        double iconSize = Math.min(w.h, Math.max(1, w.h)) * clamp(w.iconScale, 0.2, 1.0);
        double textX = w.x;
        boolean iconOk = w.showIcon && QuadCollector.textureReady(state.icon);
        if (iconOk) {
            collector.setRenderType(QuadCollector.imageType(state.icon));
            double[] p0 = w.rotPt(0, (w.h - iconSize) / 2);
            double[] p1 = w.rotPt(iconSize, (w.h - iconSize) / 2);
            double[] p2 = w.rotPt(iconSize, (w.h + iconSize) / 2);
            double[] p3 = w.rotPt(0, (w.h + iconSize) / 2);
            collector.canvasQuad(ctx.axisX(), ctx.axisY(), ctx.normal(), ctx.origin(),
                    p0[0], p0[1], p1[0], p1[1], p2[0], p2[1], p3[0], p3[1],
                    ctx.depth(), 0f, 0f, 1f, 1f, QuadCollector.withAlpha(0xFFFFFFFF, w.alpha));
            textX = w.x + iconSize * 1.15;
        }
        if (w.showText) {
            String label = colorPrefix(w.color) + state.label;
            TtfFont font = FontManager.get(w.fontId);
            // 【⑨.1 关联修正 + 27.2.4-200】旋转轴必须是**控件中心**：
            // 图标/方框/选中框都绕中心转，文字若用别的轴（角、(textX,w.y)），控件一转就各转各的。
            TextRenderer.drawInBox(collector, ctx, font, label, w.fontSize, -1, 1.15, 0, 1,
                    textX, w.y, Math.max(1, w.x + w.w - textX), w.h, w.rot, w.alpha, ctx.depth(),
                    w.rotX(), w.rotY());
        }
    }

    /** 当前天气状态。 */
    public record WeatherState(ResourceLocation icon, String label, int argb) {
    }

    /** 天气状态的缓存时刻（游戏 tick）。同一 tick 里所有天气控件共用一份。 */
    private static long weatherTick = Long.MIN_VALUE;
    private static WeatherState weatherCached;

    /**
     * 当前天气状态。
     *
     * <p>【27.2-pre-136】按 <b>tick</b> 缓存：天气一个 tick 才可能变一次，
     * 而渲染一秒要问 60~120 次；下雪判断里还有一次生物群系查询
     * （{@code level.getBiome(...)}）。以前是每帧每控件都走一遍完整判断。</p>
     */
    public static WeatherState currentWeather() {
        long now = currentGameTime();
        if (weatherCached != null && now == weatherTick) {
            return weatherCached;
        }
        WeatherState made = computeWeather();
        weatherTick = now;
        weatherCached = made;
        return made;
    }

    private static WeatherState computeWeather() {
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
            solidRect(collector, ctx, w, 0, 0, w.w, w.h, w.background);
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
                w.x, top, w.w, valueLine, w.rot, w.alpha, ctx.depth(), w.rotX(), w.rotY());

        // 上方小字：已完成
        TextRenderer.drawInBox(collector, ctx, font, w.title,
                titleSize, -1, 1.0, 1, 1,
                w.x, top + valueLine, w.w, titleLine, w.rot, w.alpha, ctx.depth(), w.rotX(), w.rotY());
    }

    // ------------------------------------------------------------------
    // ⑧ 计时器
    // ------------------------------------------------------------------

    private static void drawTimer(QuadCollector collector, PlaneRenderContext ctx,
                                  top.hmjmfabc.projector.common.widget.TimerWidget w) {
        if (degenerate(w)) return;
        if (w.background != 0) {
            solidRect(collector, ctx, w, 0, 0, w.w, w.h, w.background);
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
                w.x, w.y, w.w, w.h, w.rot, alpha, ctx.depth(), w.rotX(), w.rotY());
    }

    // ------------------------------------------------------------------
    // ③ 排行榜
    // ------------------------------------------------------------------

    private static void drawLeaderboard(QuadCollector collector, PlaneRenderContext ctx,
                                        top.hmjmfabc.projector.common.widget.LeaderboardWidget w) {
        if (degenerate(w)) return;
        if (w.background != 0) {
            solidRect(collector, ctx, w, 0, 0, w.w, w.h, w.background);
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
                    w.x, bottom + rowH * rows.size(), w.w, titleH, w.rot, w.alpha, ctx.depth(), w.rotX(), w.rotY());
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
                        w.x, y, indexW, rowH, w.rot, w.alpha, ctx.depth(), w.rotX(), w.rotY());
            }
            TextRenderer.drawInBox(collector, ctx, nameFont,
                    colorPrefix(w.nameColor) + row.name(), Math.max(1.0, w.rowSize), -1, 1.0, 0, 1,
                    w.x + indexW, y, nameW, rowH, w.rot, w.alpha, ctx.depth(), w.rotX(), w.rotY());
            if (w.showScore) {
                TextRenderer.drawInBox(collector, ctx, scoreFont,
                        colorPrefix(w.scoreColor) + row.score(), Math.max(1.0, w.rowSize), -1, 1.0, 2, 1,
                        w.x + indexW + nameW, y, scoreW, rowH, w.rot, w.alpha, ctx.depth(), w.rotX(), w.rotY());
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
                    w.x, w.y, w.w, w.h, w.rot, w.alpha, ctx.depth(), w.rotX(), w.rotY());
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

    /**
     * 棋盘的诊断只对「棋种+列数+行数+字体」变化时打一次，不刷屏。
     *
     * <p>【27.2-pre-136】改存<b>签名</b>而不是拼好的字符串：以前每帧都要先
     * {@code String.format} 一整行再和上次比 —— 字符串造出来才发现没变。
     * 现在只有签名真的变了才拼那一行。</p>
     */
    private static final java.util.Map<java.util.UUID, ChessSig> CHESS_REPORT = new java.util.HashMap<>();

    /** 棋盘上报的轻量签名（构造它不做任何格式化）。 */
    /**
     * 【27.2.1-snapshot-150】把一个**局部坐标矩形**（相对左下角）换算成画布包围盒。
     * <p>专门用来在日志里对比「格线 / 棋子 / 高亮」三者是否落在同一块地方 ——
     * 它们走的是不同的绘制原语（solidRect 收局部、drawInBox 收画布），
     * 只要有一个喂错坐标系，这里三条包围盒就会明显不一样。</p>
     */
    private static double[] canvasBox(Widget w, double x0, double y0, double x1, double y1) {
        double[] a = w.rotPt(x0, y0);
        double[] b = w.rotPt(x1, y1);
        double[] c = w.rotPt(x1, y0);
        double[] d = w.rotPt(x0, y1);
        return new double[]{
                Math.min(Math.min(a[0], b[0]), Math.min(c[0], d[0])),
                Math.min(Math.min(a[1], b[1]), Math.min(c[1], d[1])),
                Math.max(Math.max(a[0], b[0]), Math.max(c[0], d[0])),
                Math.max(Math.max(a[1], b[1]), Math.max(c[1], d[1]))};
    }

    /**
     * 诊断用：把首格的「渲染坐标 → 画布坐标 → 反算格号」走一整圈，看是否回到 (0,0)。
     * <p>它同时验两件事：渲染坐标与判定坐标是否同源；以及坐标系有没有混用。</p>
     */
    private static String roundTrip(top.hmjmfabc.projector.common.widget.ChessWidget w) {
        try {
            double[] local = w.localCenterOf(0, 0);          // 局部（渲染实际用的）
            double[] canvas = w.rotPt(local[0], local[1]);
            int[] back = w.cellAt(canvas[0], canvas[1]);
            return "局部" + String.format(java.util.Locale.ROOT, "(%.1f,%.1f)", local[0], local[1])
                    + "→画布" + String.format(java.util.Locale.ROOT, "(%.1f,%.1f)", canvas[0], canvas[1])
                    + "→格号" + java.util.Arrays.toString(back)
                    + (back != null && back[0] == 0 && back[1] == 0 ? " ✅" : " ❌");
        } catch (Throwable t) {
            return "异常:" + t;
        }
    }

    /** 已经是**画布坐标**的两点 → 画布包围盒（不许再走 rotPt）。 */
    private static double[] canvasBoxAlready(Widget w, double x0, double y0, double x1, double y1) {
        return new double[]{Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1)};
    }

    private static String boxText(double[] b) {
        return String.format(java.util.Locale.ROOT, "(%.0f,%.0f)-(%.0f,%.0f)",
                b[0], b[1], b[2], b[3]);
    }

    /** 物理不变量：{@code inner} 是否整个落在 {@code outer} 包围盒内（留 0.6 画布单位容差）。 */
    private static boolean boxContains(double[] outer, double[] inner) {
        return inner[0] >= outer[0] - 0.6 && inner[1] >= outer[1] - 0.6
                && inner[2] <= outer[2] + 0.6 && inner[3] <= outer[3] + 0.6;
    }

    private record ChessSig(int kind, int cols, int rows, boolean intersections, double spacing,
                            double w, double h, double fs, int pieces, String font,
                            int x, int y, int rot) {
    }

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

        // 五层沿法线错开（见 CHESS_LAYER 的说明）
        // ⚠ 层序（都由 CHESS_LAYER 错开，共面会互相闪烁/被盖掉）：
        //   dBase   = 控件底色（background）
        //   dBoard  = 棋盘底色（玩家设了 boardColor 才有；必须在格线**之下**）
        //   dGrid   = 格线
        //   dMark   = 最后一手高亮 / 选中 / 合法落点
        //   dPiece  = 棋子
        //   dBorder = 棋盘外框（**最前**）
        // ⚠ 深度方向是「origin + normal × depth」，而 normal 朝玩家 ⇒ **depth 越大越靠前**。
        //   所以要把「必须看得见」的东西放在**最大**的 depth 上。
        //   上一版我把边框放在格线之后（+0.015 < 格线 +0.030）⇒ 白边被格线整圈压住，
        //   玩家报「盘子修好了、白色那圈还是看不见」。
        // 【152】同时修掉「棋盘底色被放到最前层」——那会让底色盖住格线与棋子
        //   （默认透明所以一直没暴露）。现在底色独占一层、夹在控件底色与格线之间。
        final double dBase = ctx.depth();
        final double dBoard = dBase + CHESS_LAYER;
        final double dGrid = dBase + CHESS_LAYER * 2;
        final double dMark = dBase + CHESS_LAYER * 3;
        final double dPiece = dBase + CHESS_LAYER * 4;
        final double dBorder = dBase + CHESS_LAYER * 5;      // 最前：边框永远看得见
        PlaneRenderContext ctxBorder = depthShift(ctx, dBorder);
        PlaneRenderContext ctxBoard = depthShift(ctx, dBoard);
        PlaneRenderContext ctxGrid = depthShift(ctx, dGrid);
        PlaneRenderContext ctxMark = depthShift(ctx, dMark);

        if (w.background != 0) {
            solidRect(collector, ctx, w, 0, 0, w.w, w.h, w.background);
        }
        // 棋盘底色（压在所有格线之下）。【27.2-pre-136】默认是**全透明**：
        // 用户要求「黑色底改成透明底」，所以这里只在玩家真的设了颜色时才填。
        if ((w.boardColor >>> 24) != 0) {
            solidRect(collector, ctxBoard, w, 0, 0, w.w, w.h, w.boardColor);
        }
        // 【27.2-pre-136】棋盘外框（可调色；与音乐控件的白框同一套设计语言）。
        // 画在**自己那一层**（ctxBorder）：以前和底色同层（dBase），会被格线/棋子盖掉。
        if (w.showBorder && (w.borderColor >>> 24) != 0) {
            // ⚠ 这四条用 solidRect 画（收**局部**坐标）⇒ 与棋盘底一样、**不要**加左下角。
            //   历史上这里被我漏改过一版：格线加了、边框没加 ⇒ 玩家看到「棋盘修好了，
            //   但白色那圈边框还在别处」。
            // 线宽：原来上限 1.6 画布单位（=10 分之一个方块），在墙上很容易看漏 ⇒ 提到 2.6
            double bt = Math.max(0.6, Math.min(Math.min(w.w, w.h) * 0.03, 2.6));
            int bc = QuadCollector.withAlpha(w.borderColor, w.alpha);
            solidRect(collector, ctxBorder, w, 0, 0, w.w, bt, bc);
            solidRect(collector, ctxBorder, w, 0, w.h - bt, w.w, w.h, bc);
            solidRect(collector, ctxBorder, w, 0, bt, bt, w.h - bt, bc);
            solidRect(collector, ctxBorder, w, w.w - bt, bt, w.w, w.h - bt, bc);
        }

        // ---- 几何布局：交叉点式 vs 格心式 ----
        // 【唯一来源】几何与「点位中心」都由 ChessWidget 提供，渲染与命中检测共用，
        // 避免两边各算一套导致「看着点在子上、实际点到隔壁」。
        var geo = w.geometry();
        final boolean intersections = geo.intersections();
        double spacing = geo.spacing();
        // ⚠ geo 给的是**局部**坐标（相对左下角），而 solidRect 收的**也是**局部坐标
        //   （见 solidRect 的坐标系契约）⇒ 这里**直接原样往下传**，不许再加 w.x/w.y。
        //   （踩过两次：一次完全不加 ⇒ 格线跑到平面角落；一次加过头 ⇒ 边框被平移两次。
        //     现在两边约定同一套，唯一的换算点在 solidRect 里。）
        double ox = geo.ox();
        double oy = geo.oy();
        // 高亮/选中/提示要走 solidRect ⇒ 需要**局部**坐标，用 localCenterOf（见下）
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

        // ---- 最后一手高亮 ----
        // ⚠ 下面的高亮全部用 {@code localCenterOf}（**局部**坐标）：solidRect 收的是局部坐标。
        //   以前用的是 centerOf（**画布**坐标）⇒ 又被减一次左下角、又绕中心转一次，
        //   玩家看到的就是「棋子在一处、格子在一处、高亮/判定又在另一处」。
        //   棋子的那条链（TextRenderer.drawInBox）要的才是画布坐标，两者不能混。
        if (w.highlightLast && g.lastTo >= 0 && g.lastTo < cw * ch) {
            int lx = g.lastTo % cw, ly = g.lastTo / cw;
            double half = spacing * (intersections ? 0.42 : 0.5);
            double[] c = w.localCenterOf(lx, ly);
            solidRect(collector, ctxMark, w, c[0] - half, c[1] - half,
                    c[0] + half, c[1] + half, w.highlightColor);
        }
        // 世界内点选时选中的棋子
        if (w.selCol >= 0 && w.selCol < cw && w.selRow >= 0 && w.selRow < ch) {
            double half = spacing * (intersections ? 0.46 : 0.5);
            double[] c = w.localCenterOf(w.selCol, w.selRow);
            // 白色（与格线/边框同一套）；比「最后一手」更亮一点以示区别
            solidRect(collector, ctxMark, w, c[0] - half, c[1] - half,
                    c[0] + half, c[1] + half, 0x99FFFFFF);
        }

        // ---- 【rc-84】合法落点「着重显示」----
        // 玩家原话：「五子棋第一步那几个特殊的点应该着重显示，要不然只提示不合法」。
        // 这条同样治象棋：选中一个子就把它能走到的点画出来，玩家不必靠「点了才知道」。
        // 高亮的点与点击判定同源（都来自规则层的 legalMoves），不存在第二套真相。
        try {
            for (top.hmjmfabc.projector.common.game.Move hm : w.hintMoves()) {
                double[] c = w.localCenterOf(hm.tx(), hm.ty());   // 局部坐标（solidRect）
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
                        w.rot, w.alpha, dPiece, w.rotX(), w.rotY());
            }
        }

        // 一次性诊断：棋类控件以前难查（画了 N 个四边形却看不出内容），
        // 所以把「到底算了什么」落一行日志。只在关键参数变化时打印。
        // 【27.2-pre-136】先比轻量签名，只有真的变了才拼日志串
        ChessSig sig = new ChessSig(g.kind, cw, ch, intersections, spacing, w.w, w.h, fs,
                pieces, font == null ? "" : FontManager.displayName(w.fontId),
                // 【27.2.1-snapshot-150】把位置与角度也算进签名：挪动/旋转控件时重打一行，
                // 排查「棋子/格子/判定区对不上」时能立刻看到三个坐标系的实际取值。
                (int) w.x, (int) w.y, (int) w.rot);
        String report = null;
        if (!sig.equals(CHESS_REPORT.put(w.id, sig))) {
            // ⚠ 诊断自己也要守坐标系契约：canvasBox 收**局部**、centerOf 给**画布**。
            //   （历史上这里混过一次，日志里的「包围盒对不上」是诊断自己造的假象，白查两轮。）
            double[] boxGrid = canvasBox(w, geo.ox(), geo.oy(),
                    geo.ox() + (cw - 1) * spacing, geo.oy() + (ch - 1) * spacing);
            double[] boxPiece = canvasBox(w,
                    w.localCenterOf(0, ch - 1)[0], w.localCenterOf(0, ch - 1)[1],
                    w.localCenterOf(cw - 1, 0)[0], w.localCenterOf(cw - 1, 0)[1]);
            // 高亮：有最后一手就报那一格，否则退回棋盘对角线
            int mk = (w.highlightLast && g.lastTo >= 0 && g.lastTo < cw * ch) ? g.lastTo : -1;
            double[] hc = mk >= 0 ? w.localCenterOf(mk % cw, mk / cw) : w.localCenterOf(cw - 1, 0);
            double[] boxMark = canvasBox(w, hc[0] - spacing * 0.5, hc[1] - spacing * 0.5,
                    hc[0] + spacing * 0.5, hc[1] + spacing * 0.5);
            // 控件自身（= 棋盘底 / 外框所在）的包围盒，用来一眼看出「边框有没有落在棋盘上」
            double[] boxSelf = w.rotatedAabb();
            report = String.format(java.util.Locale.ROOT,
                    "%s %dx%d %s 间距=%.2f 框=%.1fx%.1f 字号=%.1f 棋子=%d 字体=%s 边框=%s"
                            + "｜坐标系: 控件x/y=%.1f,%.1f rot=%.0f° 几何原点(局部)=%.1f,%.1f"
                            + " 首格中心(画布)=%.1f,%.1f 首格反算=%s"
                            + "｜包围盒(画布): 外框/底盘=%s 格线=%s 棋子=%s 高亮=%s"
                            + " 格线在框内=%s 棋子在框内=%s",
                    top.hmjmfabc.projector.common.game.GameKind.name(g.kind), cw, ch,
                    intersections ? "交叉点式" : "格心式",
                    spacing, w.w, w.h, fs, pieces,
                    font == null ? "null!!" : FontManager.displayName(w.fontId),
                    w.showBorder ? (Integer.toHexString(w.borderColor)) : "关",
                    w.x, w.y, w.rot, geo.ox(), geo.oy(),
                    w.centerOf(0, 0)[0], w.centerOf(0, 0)[1],
                    // ⚠ centerOf 给**画布**值、cellAt 收**画布**值 ⇒ 直接喂
                    roundTrip(w),
                    boxText(boxSelf), boxText(boxGrid), boxText(boxPiece), boxText(boxMark),
                    // 物理不变量：格线与棋子必须落在**控件自身**的包围盒里。
                    // 一旦 solidRect 的坐标系约定再被改错，格线/棋子会整体平移，这里立刻变 ❌。
                    boxContains(boxSelf, boxGrid) ? "✅" : "❌",
                    boxContains(boxSelf, boxPiece) ? "✅" : "❌");
        }
        if (report != null) {
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

    /**
     * 生成一个把颜色设为指定 ARGB 的格式化代码前缀。
     *
     * <p>【27.2-pre-136】**必须记忆化**：它每帧会被调用几十次（一个棋盘 32 个棋子、
     * 排行榜每行三次），而 {@code String.format} 是 Java 里最慢的常用方法之一。
     * 更要紧的是：返回同一个字符串实例之后，「文字排版缓存」的键才会命中 ——
     * 以前每帧都造一个新字符串，等于每帧每段文字都强制重新排版。</p>
     */
    public static String colorPrefix(int argb) {
        int slot = (argb * 0x9E3779B1) >>> 26;
        for (int i = 0; i < COLOR_CACHE_SIZE; i++) {
            int idx = (slot + i) & (COLOR_CACHE_SIZE - 1);
            String v = COLOR_CACHE_VAL[idx];
            if (v == null) {
                break;
            }
            if (COLOR_CACHE_KEY[idx] == argb) {
                return v;
            }
        }
        String made = "&#" + String.format(java.util.Locale.ROOT, "%06X", argb & 0xFFFFFF);
        for (int i = 0; i < COLOR_CACHE_SIZE; i++) {
            int idx = (slot + i) & (COLOR_CACHE_SIZE - 1);
            String v = COLOR_CACHE_VAL[idx];
            if (v == null || v.equals(made)) {
                COLOR_CACHE_KEY[idx] = argb;
                COLOR_CACHE_VAL[idx] = made;
                break;
            }
        }
        return made;
    }

    /** colorPrefix 的开放寻址小缓存（64 项；同时用到的颜色不可能这么多）。 */
    private static final int COLOR_CACHE_SIZE = 64;
    private static final int[] COLOR_CACHE_KEY = new int[COLOR_CACHE_SIZE];
    private static final String[] COLOR_CACHE_VAL = new String[COLOR_CACHE_SIZE];

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
        solidRect(collector, ctx, w, 0, 0, w.w, w.h, QuadCollector.withAlpha(argb, w.alpha));
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
                w.x, top, w.w, lineH, w.rot, w.alpha, ctx.depth(), w.rotX(), w.rotY());
        TextRenderer.drawInBox(collector, ctx, font, "&7" + line2, size * 0.8, -1, 1.15, 1, 1,
                w.x, top + lineH, w.w, lineH, w.rot, w.alpha, ctx.depth(), w.rotX(), w.rotY());
    }

    /** 人类可读的字节数（B / KB / MB）。 */
    public static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024L * 1024L) {
            return String.format(java.util.Locale.ROOT, "%.1fKB", bytes / 1024.0);
        }
        return String.format(java.util.Locale.ROOT, "%.2fMB", bytes / 1048576.0);
    }

    /**
     * 实心矩形（控件底色 / 棋盘底 / 棋盘边框 / 占位块 / 格线 / 高亮全靠它）。
     *
     * <p><b>★ 坐标系契约（唯一真源，改了这里就是全局改约定）：入参是「控件局部坐标」
     * —— 相对控件左下角，范围 {@code 0..w / 0..h}，未旋转。</b>
     * 内部用 {@link Widget#rotPt(double, double)} 一步换算到画布（绕局部中心旋转 + 加左下角），
     * 而 {@code collector.canvasQuad} 收的是画布坐标 ⇒ 整条链闭合。</p>
     *
     * <p>【27.2.2-snapshot-152 事故】上一版这里的实现是
     * {@code w.rotPt(x0 - w.x, y0 - w.y)}（＝收**画布**坐标），
     * 而同一轮把**所有调用方**都改成了传局部坐标（{@code 0,0,w.w,w.h}）——
     * 两边各自「看起来都对」，合起来就是：矩形被整体平移 {@code -R(x,y)}，
     * 画到**画布原点所在的平面角落**去了。<br>
     * 表现：棋盘格线（传画布）、棋子（drawInBox 传画布）全都在正确位置，
     * 只有**棋盘外框那一圈白边**不见了 —— 因为它被画到了平面角落里，
     * 玩家连报两轮「棋盘边框还是没修」。同理，所有控件的「底色」与
     * 「加载中占位块」也一起被画到角落去了（底色默认透明所以没被发现）。</p>
     *
     * <p><b>别再改回画布坐标。</b>本文件里 {@code solidRect} 的调用方**全部**传局部：
     * 底色/占位块 {@code 0,0,w.w,w.h}、棋盘底与边框 {@code 0..w}、
     * 格线与高亮走 {@link ChessWidget#geometry()}/{@code localCenterOf} 的局部几何。
     * 需要画「画布坐标」的东西请走 {@code TextRenderer.drawInBox} 或
     * {@code collector.canvasQuad}，不要从这里绕。</p>
     */
    private static void solidRect(QuadCollector collector, PlaneRenderContext ctx, Widget w,
                                  double x0, double y0, double x1, double y1, int argb) {
        if (argb >>> 24 == 0) return;
        if (Math.abs(x1 - x0) < 1.0e-4 || Math.abs(y1 - y0) < 1.0e-4) return;
        double[] p0 = w.rotPt(x0, y0);
        double[] p1 = w.rotPt(x1, y0);
        double[] p2 = w.rotPt(x1, y1);
        double[] p3 = w.rotPt(x0, y1);
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

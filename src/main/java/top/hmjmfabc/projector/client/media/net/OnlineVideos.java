package top.hmjmfabc.projector.client.media.net;

import top.hmjmfabc.projector.common.widget.VideoWidget;

/**
 * 在线视频与播放管线之间的<b>唯一入口</b>（27.1.3）。
 *
 * <p>视频控件有两种素材来源：上传到服务器的素材（{@code mediaId} + {@code mediaName}）
 * 与<b>在线源</b>（{@code sourceUrl}，客户端各自解析下载）。渲染器与两个播放后端
 * 都从这里问「这个控件现在该用哪个 id / 文件名」，<b>不要</b>各自去读
 * {@code w.mediaId} —— 一份判定写两遍，就一定会有一处忘改（本项目复发过多次）。</p>
 */
public final class OnlineVideos {
    private OnlineVideos() {
    }

    /** 控件是不是在线源。 */
    public static boolean online(VideoWidget w) {
        return w != null && w.isOnline();
    }

    /**
     * 这个控件此刻用的素材 id。
     *
     * <p>在线源返回的是**本地缓存身份**（{@code OnlineVideoCache.idFor(url)}，
     * 一个 SHA-1 十六进制串）：这样 {@code MediaCache} / {@code LocalMedia} /
     * WaterMedia 那条路都把它当成普通本地素材，播放代码一行不用改。</p>
     */
    public static String mediaId(VideoWidget w) {
        if (w == null) {
            return "";
        }
        if (w.isOnline()) {
            return OnlineVideoCache.idFor(w.sourceUrl);
        }
        return w.mediaId == null ? "" : w.mediaId;
    }

    /** 这个控件此刻用的文件名（扩展名决定交给哪条解码路）。 */
    public static String mediaName(VideoWidget w) {
        if (w == null) {
            return "";
        }
        if (w.isOnline()) {
            return OnlineVideoCache.nameFor(w.sourceUrl);
        }
        return w.mediaName == null ? "" : w.mediaName;
    }

    /** 每帧调一次：保证在线源正在后台解析/下载（内含节流与去重）。 */
    public static void tick(VideoWidget w) {
        if (online(w)) {
            // 【27.1.3】流式（默认）时**不下载**，只解析出直链交给解码器；
            // 玩家在编辑器里关掉流式（或环境拉不了流）才走老办法：整段下载到本地再播。
            OnlineVideoCache.request(w.sourceUrl, !willStream(w));
        }
    }

    /**
     * 【27.1.3】这个控件打算流式播吗：玩家没关开关 **且** 有能拉流的解码器。
     *
     * <p>环境拉不了流（没装额外解码器）时**自动退回下载**——否则视频永远放不出来。
     * 这也是「默认流式」不会把没装解码器的玩家坑死的原因。</p>
     */
    public static boolean willStream(VideoWidget w) {
        return online(w) && w.streamOnline && canStream() && !streamFailed(w);
    }

    /**
     * 【27.1.3】这一端试过流式、但解码器一直没起来 ⇒ 这个控件改走「先下载」。
     *
     * <p>流式要解码器直接拉远程地址；有的环境/格式就是拉不动。以前会一直卡在占位上，
     * 现在由 {@code WaterMediaVideos} 在超时后叫一次这里，退回老办法（下载后再播）。</p>
     */
    private static final java.util.Set<java.util.UUID> STREAM_FAILED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static void noteStreamFailed(VideoWidget w) {
        if (w != null && w.id != null && STREAM_FAILED.add(w.id)) {
            top.hmjmfabc.projector.Projector.LOGGER.warn("[Projector][在线视频] 控件 {} 流式播放超时，改为先下载再播",
                    w.id.toString().substring(0, 8));
            OnlineVideoCache.forceDownload(w.sourceUrl);
        }
    }

    public static boolean streamFailed(VideoWidget w) {
        return w != null && w.id != null && STREAM_FAILED.contains(w.id);
    }

    /** 现在是不是「流式播放」（直播/HLS，或玩家选了流式）。 */
    public static boolean streaming(VideoWidget w) {
        return online(w) && OnlineVideoCache.streaming(w.sourceUrl);
    }

    /** 是不是**天生**只能流式的（直播/HLS）：进度条不画「已播放段」就看它。 */
    public static boolean live(VideoWidget w) {
        return online(w) && OnlineVideoCache.infinite(w.sourceUrl);
    }

    /** 流地址（不是流或还没解析出来就是空串）。 */
    public static String streamUrl(VideoWidget w) {
        return online(w) ? OnlineVideoCache.streamUrl(w.sourceUrl) : "";
    }

    /**
     * 素材可以播了吗。
     *
     * <p>本地素材永远算就绪；在线源要等下完；**流**则是「地址到手 + 有解码器能拉流」。</p>
     */
    public static boolean ready(VideoWidget w) {
        if (!online(w)) {
            return true;
        }
        if (OnlineVideoCache.ready(w.sourceUrl)) {
            return true;
        }
        return streaming(w) && canStream();
    }

    /** 流式播放要外部解码器（我们自己的后端只吃本地 MJPEG/ZIP）。 */
    private static boolean canStream() {
        try {
            return top.hmjmfabc.projector.client.media.wm.WaterMediaBridge.available();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 状态说明（占位提示与日志共用）。 */
    public static String status(VideoWidget w) {
        if (!online(w)) {
            return "";
        }
        String s = OnlineVideoCache.describe(w.sourceUrl);
        if (streaming(w) && !canStream()) {
            s += "（当前环境放不了流，需要额外解码器支持）";
        } else if (streamFailed(w)) {
            s += "（流式起不来，已改为先下载）";
        }
        return s;
    }
}

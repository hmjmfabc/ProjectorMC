package top.hmjmfabc.projector.common.widget;

import net.minecraft.nbt.CompoundTag;

/**
 * 视频控件。
 *
 * <p>内置后端只认 MJPEG / ZIP 帧序列（Android 上无法可靠调用外部解码程序）；
 * 装了 WaterMedia 模组时常见格式（MP4/WebM/MKV…）交给它播放，见 27.1.2。
 * 因此采用两种「逐帧静态图」格式：</p>
 * <ul>
 *   <li><b>MJPEG (.mjpg/.mjpeg/.avi-MJPEG)</b>：把每帧 JPEG 直接切成独立图片解码；</li>
 *   <li><b>PNG/JPEG 帧序列 (.zip 或文件夹)</b>：按文件名排序逐帧播放。</li>
 * </ul>
 *
 * <p>{@code startTimeMs} 为「世界时间锚点」，让同一存档内所有客户端播放进度一致；
 * 为 0 时表示使用各自本地时间。</p>
 */
public class VideoWidget extends Widget {

    public String mediaId = "";
    public String mediaName = "";
    public int srcW, srcH;
    public int frameCount;
    public double fps = 10;
    public boolean loop = true;
    /** 世界时间锚点（毫秒）；0 = 用本地时间。 */
    public long startTimeMs;
    public boolean paused;
    /** 暂停时显示的帧。 */
    public int pausedFrame;
    public int tint = 0xFFFFFFFF;

    /**
     * 【hotfix-101】媒体真实时长（毫秒，0 = 还不知道）。
     *
     * <p>外部解码器（WaterMedia）播放的视频没有帧表 ⇒ {@code frameCount} 是 1，
     * 用 fps 算出来的「时长」只有 100 毫秒 —— 进度条于是每 0.1 秒绕一圈 = 玩家看到的
     * 「进度条乱跳」，跳进度也是错的。解码器一报出真时长就记在这里并跟随控件同步。</p>
     */
    public long mediaDurationMs;

    /** 【hotfix-101】真实播放位置（毫秒）。运行时由解码器更新，不进 NBT。 */
    public transient long mediaPositionMs;

    /**
     * 【hotfix-101】暂停时冻结的位置（毫秒）。
     *
     * <p>以前只有一个 {@code pausedFrame}（帧号），而外部解码器的视频根本没有帧号
     * ⇒ 暂停位置只能记成 0，继续播放就从头开始。现在以毫秒为准，
     * 帧号由它换算出来（内置后端仍用帧号渲染）。</p>
     */
    public long pausedMs;

    /**
     * 播放后端（27.1.2）：
     * <ul>
     *   <li>{@link #BACKEND_AUTO}（默认）：MP4 / WebM / MKV 等常见格式交给 WaterMedia（装了才生效），
     *       MJPEG / ZIP 用本项目自带后端；</li>
     *   <li>{@link #BACKEND_BUILTIN}：强制内置后端（只看 MJPEG / ZIP 帧序列）；</li>
     *   <li>{@link #BACKEND_WATERMEDIA}：强制 WaterMedia（装没装都能设，没装就等于自动）。</li>
     * </ul>
     */
    public int backend = BACKEND_AUTO;

    public static final int BACKEND_AUTO = 0;
    public static final int BACKEND_BUILTIN = 1;
    public static final int BACKEND_WATERMEDIA = 2;

    public boolean builtinForced() {
        return backend == BACKEND_BUILTIN;
    }

    public boolean waterMediaForced() {
        return backend == BACKEND_WATERMEDIA;
    }

    public String backendName() {
        return switch (backend) {
            case BACKEND_BUILTIN -> "内置";
            case BACKEND_WATERMEDIA -> "WaterMedia";
            default -> "自动";
        };
    }

    @Override
    public int kind() {
        return KIND_VIDEO;
    }

    @Override
    public String label() {
        String n = mediaName;
        if (n == null || n.isEmpty()) n = "\u89c6\u9891";
        return n + " (" + frameCount + "f, " + String.format(java.util.Locale.ROOT, "%.1f", fps) + "fps)";
    }

    @Override
    protected void saveExtra(CompoundTag t) {
        t.putString("media", mediaId);
        t.putString("mediaName", mediaName);
        t.putInt("srcW", srcW);
        t.putInt("srcH", srcH);
        t.putInt("frames", frameCount);
        t.putDouble("fps", fps);
        t.putBoolean("loop", loop);
        t.putLong("start", startTimeMs);
        t.putBoolean("paused", paused);
        t.putInt("pausedFrame", pausedFrame);
        t.putLong("pausedMs", pausedMs);
        t.putLong("mediaDurationMs", mediaDurationMs);
        t.putInt("tint", tint);
        t.putInt("backend", backend);
    }

    @Override
    public void loadExtra(CompoundTag t) {
        mediaId = t.getString("media");
        mediaName = t.getString("mediaName");
        srcW = t.getInt("srcW");
        srcH = t.getInt("srcH");
        frameCount = t.getInt("frames");
        fps = t.contains("fps") && t.getDouble("fps") > 0.01 ? t.getDouble("fps") : 10;
        loop = !t.contains("loop") || t.getBoolean("loop");
        startTimeMs = t.getLong("start");
        paused = t.getBoolean("paused");
        pausedFrame = t.getInt("pausedFrame");
        pausedMs = t.getLong("pausedMs");
        mediaDurationMs = t.getLong("mediaDurationMs");
        if (pausedMs <= 0 && pausedFrame > 0 && fps > 0.01) {
            // 旧存档只有帧号：换算成毫秒
            pausedMs = (long) (pausedFrame * 1000.0 / fps);
        }
        tint = t.contains("tint") ? t.getInt("tint") : 0xFFFFFFFF;
        backend = t.contains("backend") ? t.getInt("backend") : BACKEND_AUTO;
    }

    // ------------------------------------------------------------------
    // 【hotfix-98】播放位置 / 暂停 / 世界内小播放键的几何
    //
    // 坐标约定（与渲染一致）：局部 (0,0) = 控件左下角，局部 y 向上（画布 y 轴向上），
    // 局部坐标 → 画布坐标要再过一次 rot(x, y, rot, …)。
    // 绘制与点击**都**从这里取几何，避免「画在一个地方、点在另一个地方」。
    // ------------------------------------------------------------------

    /** 控件自身时间轴的总时长（毫秒）。知道真实时长时以它为准。 */
    public long durationMs() {
        if (mediaDurationMs > 0) {
            return mediaDurationMs;
        }
        if (fps <= 0.01) {
            return 1L;
        }
        return Math.max(1L, (long) (Math.max(1, frameCount) / fps * 1000.0));
    }

    /** 时长是否可靠（不可靠时进度条不画，免得乱跳）。 */
    public boolean durationKnown() {
        return mediaDurationMs > 0 || frameCount > 1;
    }

    /**
     * 当前播放到第几毫秒（0 ~ 总时长）。
     *
     * <p>与 {@link #currentFrame(long)} 同一套时间语义，只是单位换成毫秒：
     * 暂停时取冻结的那一帧；否则以 {@code startTimeMs} 为锚点算过去多久
     * （为 0 表示老存档/新建，用本地时间对时长取模）。</p>
     */
    public long positionMs(long nowMs) {
        long d = durationMs();
        if (paused) {
            return Math.max(0L, Math.min(d, pausedMs));
        }
        // 外部解码器在播：用它自报的位置最准（进度条不会跟画面错位）
        if (mediaDurationMs > 0 && mediaPositionMs > 0) {
            return Math.max(0L, Math.min(mediaDurationMs, mediaPositionMs));
        }
        long elapsed = startTimeMs > 0 ? Math.max(0L, nowMs - startTimeMs) : nowMs;
        if (loop) {
            return Math.floorMod(elapsed, d);
        }
        return Math.max(0L, Math.min(d, elapsed));
    }

    /** 播放进度 0~1（进度条用）。 */
    public double progressFraction(long nowMs) {
        return (double) positionMs(nowMs) / Math.max(1L, durationMs());
    }

    /**
     * 跳到指定进度（不改播放/暂停状态）。
     *
     * <p>跳转后必定把 {@code startTimeMs} 锚到「现在 − 目标位置」——
     * 即使原来是 0（本地时间）也要锚，否则算出来的是全局时间，跳了等于没跳。</p>
     */
    public void seekToFraction(double fraction, long nowMs) {
        double f = Math.max(0.0, Math.min(1.0, fraction));
        seekToMs((long) (f * durationMs()), nowMs);
    }

    /**
     * 跳到指定毫秒（不改播放/暂停状态）。
     *
     * <p>外部解码器的视频由客户端按**真实时长**算好毫秒再发过来，
     * 服务端不需要知道时长也能跳对（以前发比例，而服务端手里的时长是错的）。</p>
     */
    public void seekToMs(long ms, long nowMs) {
        long d = durationMs();
        long pos = Math.max(0L, ms);
        if (durationKnown()) {
            pos = Math.min(d, pos);
        }
        if (paused) {
            pausedMs = pos;
            pausedFrame = frameAt(pos);
        } else {
            startTimeMs = nowMs - pos;
            mediaPositionMs = pos;      // 外部解码器下一帧会对上
            pausedMs = pos;
        }
    }

    /**
     * 暂停 / 继续 —— <b>唯一的入口</b>。
     *
     * <p>以前没有任何地方记录「暂停时停在哪一帧」（{@code pausedFrame} 永远是 0），
     * 于是点暂停画面直接跳回第一帧，点继续又从全局时间接着算 ——
     * 玩家看到的就是「暂停/启动按钮不好使」。现在：</p>
     * <ul>
     *   <li>暂停：把**当前这一帧**冻进 {@code pausedFrame}；</li>
     *   <li>继续：把时间锚点挪到「等于这一帧」的位置，从暂停处接着放；</li>
     *   <li>不循环且已经放到结尾时再点继续：从头重放（播放器的常规手感）。</li>
     * </ul>
     */
    public void setPausedAt(boolean value, long nowMs) {
        if (value == paused) {
            return;
        }
        if (value) {
            // 冻结「此刻的位置」：内置后端按帧号，外部解码器按它自报的毫秒
            pausedMs = positionMs(nowMs);
            pausedFrame = currentFrame(nowMs);
            paused = true;
            return;
        }
        long d = durationMs();
        long pos = Math.max(0L, pausedMs);
        if (!loop && durationKnown() && pos >= d - 250L) {
            pausedFrame = 0;
            pausedMs = 0L;
            pos = 0L;
        }
        startTimeMs = nowMs - pos;
        mediaPositionMs = pos;
        paused = false;
    }

    /** 世界内点播放键：翻转播放 / 暂停。 */
    public void toggleAt(long nowMs) {
        setPausedAt(!paused, nowMs);
    }

    /** 位置（毫秒）→ 帧号。 */
    private int frameAt(long posMs) {
        int total = Math.max(1, frameCount);
        if (fps <= 0.01) {
            return 0;
        }
        int f = (int) (posMs / 1000.0 * fps);
        return Math.max(0, Math.min(total - 1, f));
    }

    /** 内边距（画布单位）。 */
    public double controlPadding() {
        return Math.max(0.4, Math.min(w, h) * 0.05);
    }

    /** 小播放键的边长。 */
    public double controlSize() {
        return Math.max(2.5, Math.min(Math.min(w, h) * 0.30, 14.0));
    }

    /** 进度条高度。 */
    public double progressHeight() {
        return Math.max(0.8, controlSize() * 0.18);
    }

    /** 播放键方框 {@code {x0,y0,x1,y1}}（左下角，相对锚点、未旋转）。 */
    public double[] controlBox() {
        double s = controlSize();
        double p = controlPadding();
        return new double[]{p, p, p + s, p + s};
    }

    /**
     * 「点一下叫出控件」的热区：左下角一块（比按钮大，手指才点得到）。
     *
     * <p>控件藏起来时只有这一块响应点击 —— 点控件别处什么都不会发生。</p>
     */
    public double[] controlHotZone() {
        double s = controlSize();
        double side = Math.min(Math.min(w, h), Math.max(s * 1.6, Math.min(w, h) * 0.42));
        return new double[]{0, 0, Math.max(side, s), Math.max(side, s)};
    }

    /** 进度条方框（底部，紧挨播放键右侧）。 */
    public double[] progressBox() {
        double s = controlSize();
        double p = controlPadding();
        double barH = progressHeight();
        double x0 = p + s + p * 0.9;
        double x1 = w - p;
        if (x1 <= x0 + 1.5) {
            x1 = x0 + 1.5;                 // 控件太窄也要留一点可点区
        }
        double y0 = p + (s - barH) / 2.0;
        return new double[]{x0, y0, x1, y0 + barH};
    }

    /** 点在小播放键上吗（触摸设备放宽 0.5 单位）。 */
    public boolean hitControl(double canvasX, double canvasY) {
        double[] local = toLocal(canvasX, canvasY);
        double[] b = controlBox();
        return local[0] >= b[0] - 0.5 && local[0] <= b[2] + 0.5
                && local[1] >= b[1] - 0.5 && local[1] <= b[3] + 0.5;
    }

    /** 点在左下角热区里吗（用来「叫出」控件）。 */
    public boolean hitHotZone(double canvasX, double canvasY) {
        double[] local = toLocal(canvasX, canvasY);
        double[] z = controlHotZone();
        return local[0] >= z[0] - 0.5 && local[0] <= z[2] + 0.5
                && local[1] >= z[1] - 0.5 && local[1] <= z[3] + 0.5;
    }

    /**
     * 点在进度条上的比例 0~1；不在条上返回 -1。
     *
     * <p>纵向放宽到「播放键那一行」，因为条本身只有一两个单位高，手指点不准。</p>
     */
    public double seekFractionAt(double canvasX, double canvasY) {
        double[] local = toLocal(canvasX, canvasY);
        double[] box = progressBox();
        double s = controlSize();
        if (local[1] < controlPadding() - s * 0.6 || local[1] > controlPadding() + s * 1.6) {
            return -1.0;
        }
        if (local[0] < box[0] || local[0] > box[2]) {
            return -1.0;
        }
        return Math.max(0.0, Math.min(1.0, (local[0] - box[0]) / Math.max(0.001, box[2] - box[0])));
    }

    /** 计算当前应该显示第几帧。 */
    public int currentFrame(long nowMs) {
        int total = Math.max(1, frameCount);
        if (paused) {
            return Math.floorMod(pausedFrame, total);
        }
        if (fps <= 0.01) return 0;
        long elapsed = startTimeMs > 0 ? Math.max(0, nowMs - startTimeMs) : (nowMs % (long) (total / Math.max(0.01, fps) * 1000L + 1));
        int f = (int) (elapsed / 1000.0 * fps);
        if (loop) {
            return Math.floorMod(f, total);
        }
        return Math.min(total - 1, Math.max(0, f));
    }
}

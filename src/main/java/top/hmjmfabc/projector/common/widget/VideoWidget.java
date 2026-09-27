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
        tint = t.contains("tint") ? t.getInt("tint") : 0xFFFFFFFF;
        backend = t.contains("backend") ? t.getInt("backend") : BACKEND_AUTO;
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

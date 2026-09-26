package top.hmjmfabc.projector.common.widget;

import net.minecraft.nbt.CompoundTag;

/**
 * 视频控件。
 *
 * <p>模组自身不内置任何视频编解码器（Android 上无法可靠调用 ffmpeg），
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

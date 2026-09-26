package top.hmjmfabc.projector.common.platform;

import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/**
 * 媒体元数据探测的公共门面。
 *
 * <p>真正的探测实现（stb_image / 帧表扫描）在客户端；服务端与界面代码
 * 通过本类访问，避免把客户端类拉到公共路径上。</p>
 */
public final class MediaMeta {

    private static Prober prober;

    /** 探测器接口。 */
    public interface Prober {
        MediaMeta probe(@Nullable Path path);
    }

    private final int width;
    private final int height;
    private final int frames;
    private final double fps;

    public MediaMeta(int width, int height, int frames, double fps) {
        this.width = width;
        this.height = height;
        this.frames = frames;
        this.fps = fps;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int frames() {
        return frames;
    }

    public double fps() {
        return fps;
    }

    public static void setProber(Prober p) {
        prober = p;
    }

    public static MediaMeta probe(@Nullable Path path, boolean video) {
        if (prober != null && path != null) {
            MediaMeta m = prober.probe(path);
            if (m != null) return m;
        }
        return new MediaMeta(0, 0, 1, 10);
    }

    public static MediaMeta empty() {
        return new MediaMeta(0, 0, 1, 10);
    }
}

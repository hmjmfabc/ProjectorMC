package top.hmjmfabc.projector.server;

import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * 服务端输入校验工具。
 *
 * <p>网络包里的任何字符串最终都会被用来拼文件路径、当数组长度或作为渲染坐标。
 * 客户端是不可信的，因此这些东西必须在服务端统一过滤：
 * 否则就会出现「路径穿越写文件」「用超大数组长度打爆内存」
 * 「把 NaN 塞进顶点坐标」这类问题。</p>
 */
public final class Sanitize {

    /** 合法的媒体哈希：40 位小写十六进制（SHA-1）。 */
    private static final Pattern SHA1 = Pattern.compile("^[0-9a-fA-F]{40}$");
    /** 自定义字体 ID 白名单。 */
    private static final Pattern FONT_ID = Pattern.compile("^[A-Za-z0-9_\\-.:]{1,64}$");
    /** 字体文件名白名单。 */
    private static final Pattern FILE_NAME = Pattern.compile("^[A-Za-z0-9_\\-]{1,64}\\.(ttf|otf|TTF|OTF)$");

    private Sanitize() {
    }

    /** 是否是合法的媒体哈希。 */
    public static boolean isHash(@Nullable String hash) {
        return hash != null && SHA1.matcher(hash).matches();
    }

    /** 归一化并转小写；非法返回 null。 */
    @Nullable
    /**
     * 【27.2】这个文件名是不是音频（按扩展名）。
     *
     * <p>用途：音频与视频走**同一档上传配额** —— 一首无损 FLAC 十几 MB，
     * 按图片那 4 MB 的档会直接传不上去。客户端在发送前、服务端在收首片时都要用同一条判据，
     * 所以放在这个两边都会加载的类里（{@code MusicTrack.AUDIO_EXT} 在 client 侧，
     * 专用服务端不能引用它）。</p>
     */
    public static boolean isAudioName(@Nullable String name) {
        if (name == null) {
            return false;
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return false;
        }
        String ext = name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        return switch (ext) {
            case "mp3", "flac", "wav", "m4a", "aac" -> true;
            default -> false;
        };
    }

    public static String hash(@Nullable String hash) {
        if (!isHash(hash)) return null;
        return hash.toLowerCase(java.util.Locale.ROOT);
    }

    public static boolean isFontId(@Nullable String id) {
        return id != null && FONT_ID.matcher(id).matches();
    }

    /** 校验并返回安全的字体文件名；非法返回 null。 */
    @Nullable
    public static String fontFileName(@Nullable String name) {
        if (name == null) return null;
        String base = name.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) base = base.substring(slash + 1);
        if (!FILE_NAME.matcher(base).matches()) return null;
        return base;
    }

    /**
     * 把 {@code child} 安全地解析到 {@code base} 之下。
     * 任何试图跳出目录的输入都返回 null。
     */
    @Nullable
    public static Path resolveInside(Path base, @Nullable String fileName) {
        if (fileName == null || fileName.isEmpty()) return null;
        if (fileName.indexOf('/') >= 0 || fileName.indexOf('\\') >= 0) return null;
        if (fileName.equals(".") || fileName.equals("..")) return null;
        Path resolved = base.resolve(fileName).normalize();
        if (!resolved.startsWith(base.normalize())) return null;
        return resolved;
    }

    /** 数值必须有限。 */
    public static boolean finite(double v) {
        return Double.isFinite(v);
    }

    /** 把数值夹到 [min, max]；NaN/Infinity 返回 fallback。 */
    public static double clamp(double v, double min, double max, double fallback) {
        if (!Double.isFinite(v)) return fallback;
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    public static float clamp(float v, float min, float max, float fallback) {
        if (!Float.isFinite(v)) return fallback;
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }
}

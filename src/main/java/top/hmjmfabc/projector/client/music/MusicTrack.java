package top.hmjmfabc.projector.client.music;

import top.hmjmfabc.projector.client.media.LocalMedia;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 一首歌的「来源描述」：本地文件 / 网易云歌曲 / 任意直链。
 *
 * <p>这个对象会**存进控件的 NBT**，所以只放少量文本字段；真正拉流的地址在
 * {@link #playUrl()} 里现算（网易云的直链是有时效的，不能存进存档）。</p>
 *
 * @param kind       来源类型
 * @param key        本地 = 相对/绝对路径；网易云 = 歌曲 ID；直链 = 完整 URL
 * @param title      标题
 * @param artist     歌手（本地文件为空）
 * @param durationMs 时长（毫秒，0 = 未知）
 */
public record MusicTrack(Kind kind, String key, String title, String artist, long durationMs) {

    public enum Kind {
        /** 本地音频文件（放在 {@code .minecraft/projector/musics} 或媒体目录里）。 */
        LOCAL,
        /** 网易云音乐（key = 歌曲 ID）。 */
        NETEASE,
        /** 任意 http(s) 直链。 */
        URL
    }

    /** 网易云「外链」地址：会 302 跳到真正的音频文件上（必须允许跟随重定向）。 */
    public static final String NETEASE_HOST = "music.163.com";

    /**
     * 可以播放的本地扩展名。
     *
     * <p>{@code m4a/aac} 刻意不在列表里：AAC 解码库与内置 JCodec 的包名冲突，
     * 装上去会让游戏在启动阶段崩（详见 {@code AudioDecoder} 与 NOTICE）。</p>
     */
    public static final List<String> AUDIO_EXT = List.of("mp3", "flac", "wav");

    public static MusicTrack local(String key, String title, long durationMs) {
        return new MusicTrack(Kind.LOCAL, key, title, "", durationMs);
    }

    public static MusicTrack netease(long songId, String title, String artist, long durationMs) {
        return new MusicTrack(Kind.NETEASE, Long.toString(songId), title, artist, durationMs);
    }

    public static MusicTrack direct(String url, String title, long durationMs) {
        return new MusicTrack(Kind.URL, url, title, "", durationMs);
    }

    public boolean isEmpty() {
        return key == null || key.isBlank();
    }

    public String displayName() {
        String t = title == null || title.isBlank() ? key : title;
        if (kind == Kind.NETEASE && artist != null && !artist.isBlank()) {
            return t + " - " + artist;
        }
        return t == null ? "" : t;
    }

    /** 拉流用的地址：本地是 {@code file:}，其余是 http(s)。 */
    public String playUrl() {
        if (kind == Kind.NETEASE) {
            return "https://" + NETEASE_HOST + "/song/media/outer/url?id="
                   + URLEncoder.encode(key, StandardCharsets.UTF_8) + ".mp3";
        }
        return key;
    }

    /** 诊断用的一行字（不含时效性的直链参数）。 */
    public String describeSource() {
        return switch (kind) {
            case LOCAL -> "本地文件 " + key;
            case NETEASE -> "网易云歌曲 " + key;
            case URL -> "直链 " + key;
        };
    }

    /** 打开音频字节流。 */
    public InputStream openStream() throws IOException {
        if (kind == Kind.LOCAL) {
            Path path = resolveLocal(key);
            if (path == null) {
                throw new IOException("本地音频文件不存在：" + key);
            }
            return Files.newInputStream(path);
        }
        return new ChunkedAudioStream(start -> rangedRequest(playUrl(), start));
    }

    /** 带 Range 的请求：断了能接着传（网易云与大部分直链都支持）。 */
    static HttpRequest rangedRequest(String url, long start) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Range", "bytes=" + start + "-")
                .header("User-Agent", NetEaseApi.USER_AGENT)
                .header("Accept", "*/*")
                .GET();
        if (url.contains(NETEASE_HOST)) {
            builder.header("Referer", "http://" + NETEASE_HOST + "/");
            String cookie = top.hmjmfabc.projector.ProjectorConfig.INSTANCE.musicNeteaseCookie.get();
            if (cookie != null && !cookie.isBlank()) {
                builder.header("Cookie", cookie.trim());
            }
        }
        return builder.build();
    }

    /**
     * 把控件里存的路径解析成真实文件。
     *
     * <p>依次尝试：绝对路径 → 音乐目录 → 媒体目录 → 旧媒体目录。</p>
     */
    public static Path resolveLocal(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        try {
            Path direct = Path.of(key);
            if (direct.isAbsolute() && Files.isRegularFile(direct)) {
                return direct;
            }
        } catch (Exception ignored) {
            // 不是合法路径就当相对路径处理
        }
        try {
            for (Path dir : searchDirs()) {
                Path candidate = dir.resolve(key).normalize();
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        } catch (Throwable t) {
            // 客户端还没起来时（例如无头环境/极早期调用）拿不到游戏目录，
            // 这里只当作「找不到文件」，不要把异常抛给调用方
            top.hmjmfabc.projector.Projector.LOGGER.debug(
                    "[Projector][音乐] 解析本地路径失败 {}：{}", key, t.toString());
        }
        return null;
    }

    /** 找本地音乐时依次看的目录。 */
    public static List<Path> searchDirs() {
        return List.of(LocalMedia.musicDir(), LocalMedia.mediaDir(), LocalMedia.legacyMediaDir());
    }

    /** 路径 → 相对音乐目录的键（能相对就相对，玩家换设备后仍然能用）。 */
    public static String keyFor(Path file) {
        Path music = LocalMedia.musicDir().toAbsolutePath().normalize();
        Path abs = file.toAbsolutePath().normalize();
        if (abs.startsWith(music)) {
            return music.relativize(abs).toString().replace('\\', '/');
        }
        return abs.toString();
    }

    /**
     * 从控件读出一首歌（**唯一**的「控件 → MusicTrack」入口）。
     *
     * <p>放在 client 侧是有意为之：{@code MusicWidget} 属于 {@code common/}，
     * 专用服务端也会加载它，所以它不能引用本类（dist 隔离，见 AGENTS §5.5 第 67 条）。</p>
     */
    public static MusicTrack of(top.hmjmfabc.projector.common.widget.MusicWidget w) {
        if (w == null) {
            return local("", "", 0L);
        }
        Kind kind;
        try {
            kind = Kind.valueOf(w.sourceKind == null ? "LOCAL"
                    : w.sourceKind.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            kind = Kind.LOCAL;
        }
        return new MusicTrack(kind, w.sourceKey == null ? "" : w.sourceKey,
                w.title == null ? "" : w.title, w.artist == null ? "" : w.artist, w.durationMs);
    }

    /** 把一首歌写进控件（唯一的「MusicTrack → 控件」入口）。 */
    public static void applyTo(top.hmjmfabc.projector.common.widget.MusicWidget w, MusicTrack t) {
        if (w == null || t == null) {
            return;
        }
        w.setTrackInfo(t.kind().name(), t.key(), t.title(), t.artist(), t.durationMs());
    }

    public static boolean isAudioFile(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        return AUDIO_EXT.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}

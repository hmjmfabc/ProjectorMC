package top.hmjmfabc.projector.client.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import top.hmjmfabc.projector.Projector;
import top.hmjmfabc.projector.ProjectorConfig;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网易云音乐：搜索、歌曲信息、歌词。
 *
 * <p>接口地址与请求头照搬 <b>Net Music Mod（网络音乐机）</b> 的 {@code api/WebApi} +
 * {@code api/NetEaseMusic}（MIT 许可，见 NOTICE）。与参考实现相比，本项目**只保留
 * 免加密的三个接口**：</p>
 * <ul>
 *   <li>搜索 {@code /api/search/get/web}（旧版明文接口，不需要 weapi 加密）</li>
 *   <li>歌曲详情 {@code /api/song/detail/}</li>
 *   <li>歌词 {@code /api/song/lyric/}</li>
 * </ul>
 * <p>播放地址用官方外链 {@code /song/media/outer/url?id=<id>.mp3}（302 跳转，
 * 由 {@link MusicHttp#CLIENT} 自动跟随）。参考实现里的 weapi 加密（AES+RSA）
 * 只用在高码率/VIP 直链接口上，本项目暂不需要，因此没有移植
 * {@code EncryptUtils} —— 需要高码率时再补。</p>
 *
 * <p>所有网络调用都在**工作线程**执行，绝不在渲染/主线程里调用。</p>
 */
public final class NetEaseApi {
    private NetEaseApi() {
    }

    public static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 6.1; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/81.0.4044.138 Safari/537.36";

    private static final String HOST = "http://music.163.com";
    /** 「分享链接 → 歌曲 ID」：支持 /song?id=xxx 与 outer/url?id=xxx.mp3 两种形式。 */
    private static final Pattern ID_IN_URL = Pattern.compile("[?&]id=(\\d+)");

    /** 搜索结果。 */
    public record SearchHit(long id, String title, String artist, long durationMs, int fee) {
        public MusicTrack toTrack() {
            return MusicTrack.netease(id, title, artist, durationMs);
        }
    }

    /** 歌曲详情。 */
    public record SongDetail(long id, String title, String artist, long durationMs, int fee) {
    }

    private static HttpRequest.Builder base(String url) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(MusicHttp.API_TIMEOUT)
                .header("Referer", "http://music.163.com/")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", USER_AGENT);
        String cookie = ProjectorConfig.INSTANCE.musicNeteaseCookie.get();
        if (cookie != null && !cookie.isBlank()) {
            b.header("Cookie", cookie.trim());
        }
        return b;
    }

    private static String get(String url) throws IOException {
        long t0 = System.currentTimeMillis();
        HttpResponse<String> response = MusicHttp.send(base(url).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("网易云返回 HTTP " + response.statusCode() + "：" + url);
        }
        Projector.LOGGER.debug("[Projector][音乐] 网易云接口 {}ms：{}",
                System.currentTimeMillis() - t0, url);
        return response.body();
    }

    // ------------------------------------------------------------ 搜索

    /**
     * 搜索接口的 URL（纯函数，便于验证）。
     *
     * <p>参考实现用的是 {@code UrlEscapers.urlPathSegmentEscaper()}，它的安全字符集里
     * **包含 {@code &} 与 {@code =}**，而这两个字符又被直接拼进 query —— 玩家在关键字里
     * 打一个 {@code &type=1000} 就能改写请求参数。这里改用
     * {@code URLEncoder}（表单编码：{@code &} → {@code %26}、空格 → {@code +}），堵掉这个口子。</p>
     */
    public static String searchUrl(String keywords, int limit) {
        return "https://music.163.com/api/search/get/web?s="
               + URLEncoder.encode(keywords == null ? "" : keywords, StandardCharsets.UTF_8)
               + "&type=1&limit=" + Math.max(1, Math.min(30, limit));
    }

    /** 关键字搜索。调用方必须在工作线程执行。 */
    public static List<SearchHit> search(String keywords) throws IOException {
        String url = searchUrl(keywords, ProjectorConfig.INSTANCE.musicSearchLimit.get());
        JsonObject root = JsonParser.parseString(get(url)).getAsJsonObject();
        JsonObject result = root.has("result") && root.get("result").isJsonObject()
                ? root.getAsJsonObject("result") : null;
        if (result == null || !result.has("songs")) {
            return List.of();
        }
        JsonArray songs = result.getAsJsonArray("songs");
        List<SearchHit> hits = new ArrayList<>(songs.size());
        for (JsonElement element : songs) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject song = element.getAsJsonObject();
            long id = asLong(song, "id", 0L);
            if (id <= 0) {
                continue;
            }
            hits.add(new SearchHit(id, asString(song, "name"), artists(song, "artists"),
                    asLong(song, "duration", 0L), (int) asLong(song, "fee", 0L)));
        }
        return hits;
    }

    // ------------------------------------------------------------ 歌曲详情

    /** 按 ID 取歌曲信息（标题/歌手/时长）。 */
    public static SongDetail detail(long songId) throws IOException {
        String url = detailUrl(songId);
        JsonObject root = JsonParser.parseString(get(url)).getAsJsonObject();
        JsonArray songs = root.has("songs") && root.get("songs").isJsonArray()
                ? root.getAsJsonArray("songs") : new JsonArray();
        if (songs.isEmpty()) {
            throw new IOException("网易云查不到这首歌：" + songId);
        }
        JsonObject song = songs.get(0).getAsJsonObject();
        // 详情接口的时长字段叫 dt（毫秒），搜索接口叫 duration
        long duration = asLong(song, "dt", asLong(song, "duration", 0L));
        return new SongDetail(songId, asString(song, "name"), artists(song, "ar"),
                duration, (int) asLong(song, "fee", 0L));
    }

    // ------------------------------------------------------------ 歌词

    /** 取歌词（LRC 原文 + 翻译），失败返回 null。 */
    public static LyricRecord lyric(long songId, String title) {
        try {
            String url = lyricUrl(songId);
            JsonObject root = JsonParser.parseString(get(url)).getAsJsonObject();
            String original = nested(root, "lrc", "lyric");
            String translated = nested(root, "tlyric", "lyric");
            return LyricRecord.parse(original, translated, title);
        } catch (Exception e) {
            Projector.LOGGER.debug("[Projector][音乐] 取歌词失败 {}：{}", songId, e.toString());
            return null;
        }
    }

    // ------------------------------------------------------------ 工具

    /** 歌词接口的 URL（纯函数）。 */
    public static String lyricUrl(long songId) {
        return HOST + "/api/song/lyric/?id=" + songId + "&lv=-1&kv=-1&tv=-1";
    }

    /** 歌曲详情接口的 URL（纯函数）。 */
    public static String detailUrl(long songId) {
        return HOST + "/api/song/detail/?id=" + songId + "&ids=%5B" + songId + "%5D";
    }

    /** 从「歌曲 ID / 分享链接 / 外链地址」里解析出歌曲 ID；不是这些形态就返回 -1。 */
    public static long parseSongId(String text) {
        if (text == null) {
            return -1L;
        }
        String t = text.trim();
        if (t.isEmpty()) {
            return -1L;
        }
        if (t.matches("\\d{1,12}")) {
            return Long.parseLong(t);
        }
        Matcher m = ID_IN_URL.matcher(t);
        if (m.find()) {
            try {
                return Long.parseLong(m.group(1));
            } catch (NumberFormatException e) {
                return -1L;
            }
        }
        return -1L;
    }

    private static String nested(JsonObject root, String object, String field) {
        if (root.has(object) && root.get(object).isJsonObject()) {
            return asString(root.getAsJsonObject(object), field);
        }
        return "";
    }

    /** 把 artists / ar 数组拼成「A, B」（最多 3 位）。 */
    private static String artists(JsonObject song, String field) {
        if (!song.has(field) || !song.get(field).isJsonArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        JsonArray array = song.getAsJsonArray(field);
        int shown = 0;
        for (JsonElement element : array) {
            if (shown >= 3) {
                sb.append("...");
                break;
            }
            if (element.isJsonObject()) {
                String name = asString(element.getAsJsonObject(), "name");
                if (!name.isEmpty()) {
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append(name);
                    shown++;
                }
            }
        }
        return sb.toString();
    }

    private static String asString(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    private static long asLong(JsonObject o, String key, long fallback) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsLong() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }
}

package top.hmjmfabc.projector.client.media.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import top.hmjmfabc.projector.common.OnlineVideoLink;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 在线视频链接 → 可播放直链（27.1.3）。
 *
 * <p>解析流程（接口面照着工作区里 MIT 的 {@code Bilibili-Media-Mod-2-neoforge} 自行实现）：</p>
 * <ol>
 *   <li>{@code b23.tv} 短链 → 跟着 302 跳到 {@code bilibili.com/video/BV…}；</li>
 *   <li>投稿视频：{@code x/web-interface/view}（BV 用 bvid、av 用 aid）拿 {@code cid}
 *       → {@code x/player/playurl?...&platform=html5} 拿 {@code durl[0].url}；</li>
 *   <li>番剧：{@code ss} 先查 {@code pgc/view/web/season} 拿第一集 {@code ep_id}
 *       → {@code pgc/player/web/playurl} 拿直链；</li>
 *   <li>直播：{@code room_init} 换真实房间号 → {@code xlive/web-room/v1/playUrl/playUrl} 拿流地址；</li>
 *   <li>yhdm.one：{@code _get_plays/<id>/<vid>} 拿 {@code video_plays[0].play_data}；</li>
 *   <li>其它 http(s)：原样当直链用。</li>
 * </ol>
 *
 * <p><b>B 站直链必须带 {@code Referer}</b>（否则 CDN 返 403），所以解析结果里连带把 Referer
 * 一起带出来 —— 下载时要用同一份头。</p>
 *
 * <p>这是<b>阻塞</b>调用，只能在后台线程跑（见 {@code OnlineVideoCache}）。</p>
 */
public final class OnlineVideoResolver {
    private OnlineVideoResolver() {
    }

    /**
     * 解析结果。
     *
     * @param url      可播放直链
     * @param referer  下载/播放时要带的 Referer（没有则空串）
     * @param ext      直链的扩展名（小写、不含点；猜不到用 "mp4"）
     * @param live     是不是直播流（直播是无限流，下载策略不同）
     * @param note     日志用的说明（哪一步拿到的）
     */
    public record Resolved(String url, String referer, String ext, boolean live, String note) {
    }

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private static final Duration API_TIMEOUT = Duration.ofSeconds(20);

    /** 解析一条链接；无法解析时抛 {@link IOException}（消息会进日志）。 */
    public static Resolved resolve(String raw) throws IOException {
        OnlineVideoLink.Target t = OnlineVideoLink.parse(raw);
        if (!t.resolvable()) {
            throw new IOException("不是能用的在线视频链接（只支持 http/https）");
        }
        try {
            return switch (t.kind()) {
                case DIRECT -> new Resolved(t.url(), OnlineVideoLink.refererFor(t),
                        t.ext().isEmpty() ? "" : t.ext(), false, "直链");
                case BILIBILI_VIDEO -> bilibiliVideo(t);
                case BILIBILI_BANGUMI -> bangumi(t);
                case BILIBILI_LIVE -> live(t);
                case YHDM -> yhdm(t);
                default -> throw new IOException("不支持的链接种类: " + t.kind());
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("解析被中断", e);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("解析失败: " + e, e);
        }
    }

    // ------------------------------------------------------------------ 各类链接

    private static Resolved bilibiliVideo(OnlineVideoLink.Target t) throws Exception {
        OnlineVideoLink.Target real = t;
        String id = t.id();
        if (id.startsWith("b23:")) {
            String longUrl = expand(t.url());
            real = OnlineVideoLink.parse(longUrl);
            if (!real.resolvable() || real.kind() != OnlineVideoLink.Kind.BILIBILI_VIDEO) {
                // 短链可能跳到番剧/直播
                return resolve(longUrl);
            }
            id = real.id();
        }
        String viewBody = get(OnlineVideoLink.viewApi(real), OnlineVideoLink.BILI_WEB);
        JsonObject viewRoot = json(viewBody);
        requireOk(viewRoot, "取视频信息");
        JsonObject data = obj(viewRoot, "data");
        long cid = cidOf(data, real.page());
        if (cid <= 0) {
            throw new IOException("拿不到 cid（视频可能已删除或需要登录）");
        }
        String playBody = get(OnlineVideoLink.playApi(real, cid), OnlineVideoLink.BILI_WEB);
        JsonObject playRoot = json(playBody);
        requireOk(playRoot, "取播放地址");
        String url = directOf(playRoot);
        if (url == null || url.isEmpty()) {
            throw new IOException("接口没给出直链（可能是会员/付费视频，或需要 cookie）");
        }
        return new Resolved(url, OnlineVideoLink.BILI_WEB, extOr(url, real.ext(), "mp4"), false,
                "投稿视频 " + id + (real.page() > 1 ? " 第" + real.page() + "话" : ""));
    }

    private static Resolved bangumi(OnlineVideoLink.Target t) throws Exception {
        OnlineVideoLink.Target real = t;
        if (t.id().startsWith("ss")) {
            String body = get(OnlineVideoLink.seasonApi(t), OnlineVideoLink.BILI_WEB);
            String ep = firstEpId(json(body));
            if (ep == null) {
                throw new IOException("这个番剧没有可播放的剧集");
            }
            real = new OnlineVideoLink.Target(OnlineVideoLink.Kind.BILIBILI_BANGUMI, t.url(),
                    ep, 1, t.ext());
        }
        String body = get(OnlineVideoLink.bangumiApi(real), OnlineVideoLink.BILI_WEB);
        JsonObject root = json(body);
        requireOk(root, "取番剧地址");
        String url = directOf(root);
        if (url == null || url.isEmpty()) {
            throw new IOException("番剧接口没给出直链（多半是会员专享，需要在配置里填 cookie）");
        }
        return new Resolved(url, OnlineVideoLink.BILI_WEB, extOr(url, real.ext(), "mp4"), false,
                "番剧 " + real.id());
    }

    private static Resolved live(OnlineVideoLink.Target t) throws Exception {
        String initBody = get(OnlineVideoLink.liveInitApi(t), OnlineVideoLink.BILI_LIVE);
        JsonObject initRoot = json(initBody);
        requireOk(initRoot, "取直播间信息");
        long roomId = longOf(obj(initRoot, "data"), "room_id", 0L);
        OnlineVideoLink.Target real = roomId > 0
                ? new OnlineVideoLink.Target(OnlineVideoLink.Kind.BILIBILI_LIVE, t.url(),
                        Long.toString(roomId), 1, "flv")
                : t;
        String body = get(OnlineVideoLink.livePlayApi(real), OnlineVideoLink.BILI_LIVE);
        JsonObject root = json(body);
        requireOk(root, "取直播流地址");
        String url = directOf(root);
        if (url == null || url.isEmpty()) {
            throw new IOException("直播接口没给出流地址（房间可能没开播）");
        }
        return new Resolved(url, OnlineVideoLink.BILI_LIVE, extOr(url, "flv", "flv"), true,
                "直播 " + real.id());
    }

    private static Resolved yhdm(OnlineVideoLink.Target t) throws Exception {
        String body = get(OnlineVideoLink.yhdmApi(t), "https://yhdm.one/");
        JsonArray plays = arr(json(body), "video_plays");
        if (plays == null || plays.isEmpty()) {
            throw new IOException("这个页面没有可播放的线路");
        }
        JsonObject first = plays.get(0).getAsJsonObject();
        String url = str(first, "play_data");
        if (url == null || url.isEmpty()) {
            throw new IOException("线路里没有播放地址");
        }
        return new Resolved(url, "https://yhdm.one/", extOr(url, "m3u8", "m3u8"), false,
                "yhdm " + t.id());
    }

    /** 短链展开：跟着重定向拿最终地址。 */
    public static String expand(String shortUrl) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(shortUrl))
                .timeout(API_TIMEOUT)
                .header("User-Agent", OnlineVideoLink.UA_MOBILE)
                .GET()
                .build();
        HttpResponse<Void> resp = CLIENT.send(req, HttpResponse.BodyHandlers.discarding());
        String finalUrl = resp.uri().toString();
        if (resp.statusCode() < 200 || resp.statusCode() >= 400 || finalUrl.equals(shortUrl)) {
            throw new IOException("短链展开失败（HTTP " + resp.statusCode() + "）");
        }
        return finalUrl;
    }

    // ------------------------------------------------------------------ HTTP 细节

    private static String get(String url, String referer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(API_TIMEOUT)
                .header("User-Agent", OnlineVideoLink.UA)
                .header("Accept", "application/json, text/plain, */*")
                .GET();
        if (referer != null && !referer.isEmpty()) {
            b.header("Referer", referer);
            b.header("Origin", referer.endsWith("/") ? referer.substring(0, referer.length() - 1) : referer);
        }
        String cookie = cookie();
        if (!cookie.isEmpty()) {
            b.header("Cookie", cookie);
        }
        HttpResponse<String> resp = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("HTTP " + resp.statusCode() + "（" + shortUrl(url) + "）");
        }
        return resp.body();
    }

    /** 配置里的 cookie（游客态就是空串）。 */
    private static String cookie() {
        try {
            String c = top.hmjmfabc.projector.ProjectorConfig.INSTANCE.onlineBilibiliCookie.get();
            return c == null ? "" : c.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    // ------------------------------------------------------------------ JSON 取值

    /**
     * B 站接口的 {@code code != 0} 一律当成失败，并把**它自己的 message** 带出来。
     *
     * <p>联网冒烟测试（`tmp/v31`）确认过正常路径的响应形状；这段是为了让失败也**可诊断**：
     * 会员专享（-403/-10403）、视频失效（-404）、需要登录这些在游戏里表现为
     * 「填了链接什么都没发生」，所以错误消息里要写清是哪一条，并指出该改哪个配置项。</p>
     */
    private static void requireOk(JsonObject root, String what) throws IOException {
        if (root == null) {
            throw new IOException(what + "失败：返回的不是 JSON");
        }
        long code = longOf(root, "code", 0L);
        if (code == 0L) {
            return;
        }
        String msg = str(root, "message");
        String hint = switch ((int) code) {
            case -403, -10403 -> "（会员/地区限制：把配置里的 online.bilibiliCookie 填上再试）";
            case -404 -> "（视频不存在或已被删除）";
            case -400 -> "（参数被接口拒绝，可能是分页/ID 写错）";
            case -412 -> "（请求被风控拦了，稍后再试）";
            default -> "";
        };
        throw new IOException(what + "失败：接口返回 code=" + code
                + (msg == null ? "" : " " + msg) + hint);
    }

    private static JsonObject json(String body) {
        try {
            JsonElement e = JsonParser.parseString(body);
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static JsonObject obj(JsonObject root, String key) {
        if (root == null) {
            return null;
        }
        JsonElement e = root.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static JsonArray arr(JsonObject root, String key) {
        if (root == null) {
            return null;
        }
        JsonElement e = root.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    private static String str(JsonObject o, String key) {
        if (o == null) {
            return null;
        }
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static long longOf(JsonObject o, String key, long def) {
        try {
            JsonElement e = o == null ? null : o.get(key);
            return e != null && e.isJsonPrimitive() ? e.getAsLong() : def;
        } catch (Throwable t) {
            return def;
        }
    }

    /** 从 view 接口的 data 里按分页取 cid。 */
    private static long cidOf(JsonObject data, int page) {
        if (data == null) {
            return 0L;
        }
        JsonArray pages = arr(data, "pages");
        if (pages != null && pages.size() > 0) {
            int idx = Math.max(0, Math.min(pages.size() - 1, page - 1));
            long cid = longOf(pages.get(idx).getAsJsonObject(), "cid", 0L);
            if (cid > 0) {
                return cid;
            }
        }
        return longOf(data, "cid", 0L);
    }

    /** ss → 第一集 ep_id。 */
    private static String firstEpId(JsonObject root) {
        JsonObject result = obj(root, "result");
        JsonObject r = result != null ? result : obj(root, "data");
        JsonArray eps = r == null ? null : arr(r, "episodes");
        if (eps != null && eps.size() > 0) {
            long id = longOf(eps.get(0).getAsJsonObject(), "ep_id", 0L);
            if (id > 0) {
                return "ep" + id;
            }
        }
        return null;
    }

    /**
     * 从任意一层里挖出可播直链。
     *
     * <p>覆盖三条已知形状：普通点的 {@code data.durl[0].url} / 番剧的 {@code result.durl[0].url}、
     * DASH 的 {@code dash.video[0].baseUrl}（没有音轨，兜底用）、
     * 直播的 {@code data.playurl_info...codec[0].base_url}。</p>
     */
    private static String directOf(JsonObject root) {
        if (root == null) {
            return null;
        }
        for (String key : new String[]{"data", "result"}) {
            JsonObject box = obj(root, key);
            if (box == null) {
                continue;
            }
            JsonArray durl = arr(box, "durl");
            if (durl != null && durl.size() > 0) {
                String url = str(durl.get(0).getAsJsonObject(), "url");
                if (url != null && !url.isEmpty()) {
                    return url;
                }
            }
            JsonObject dash = obj(box, "dash");
            if (dash != null) {
                JsonArray vids = arr(dash, "video");
                if (vids != null && vids.size() > 0) {
                    String url = str(vids.get(0).getAsJsonObject(), "baseUrl");
                    if (url == null || url.isEmpty()) {
                        url = str(vids.get(0).getAsJsonObject(), "base_url");
                    }
                    if (url != null && !url.isEmpty()) {
                        return url;
                    }
                }
            }
            String live = liveUrl(box);
            if (live != null) {
                return live;
            }
        }
        return null;
    }

    /** 直播接口的嵌套结构：playurl_info.playurl.stream[i].format[j].codec[k].base_url。 */
    private static String liveUrl(JsonObject box) {
        try {
            JsonObject info = obj(box, "playurl_info");
            JsonObject playurl = info == null ? null : obj(info, "playurl");
            JsonArray streams = playurl == null ? null : arr(playurl, "stream");
            if (streams == null) {
                return null;
            }
            for (JsonElement se : streams) {
                JsonArray formats = arr(se.getAsJsonObject(), "format");
                if (formats == null) {
                    continue;
                }
                for (JsonElement fe : formats) {
                    JsonArray codecs = arr(fe.getAsJsonObject(), "codec");
                    if (codecs == null) {
                        continue;
                    }
                    for (JsonElement ce : codecs) {
                        String url = str(ce.getAsJsonObject(), "base_url");
                        if (url != null && !url.isEmpty()) {
                            return url;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // 结构变了就当没有
        }
        return null;
    }

    private static String extOr(String url, String fallback, String def) {
        String ext = OnlineVideoLink.extOf(url);
        if (!ext.isEmpty()) {
            return ext;
        }
        if (fallback != null && !fallback.isEmpty()) {
            return fallback;
        }
        return def;
    }

    /** 日志里只打短形式，别把几百字符的 CDN 链接刷进日志。 */
    private static String shortUrl(String url) {
        int q = url.indexOf('?');
        String s = q > 0 ? url.substring(0, q) : url;
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }
}

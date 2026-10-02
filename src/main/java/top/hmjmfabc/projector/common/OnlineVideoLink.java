package top.hmjmfabc.projector.common;

import java.util.Locale;

/**
 * 在线视频链接的识别（27.1.3）。
 *
 * <p>纯逻辑：只做<b>字符串判断</b>与<b>API 地址拼接</b>，不联网、不碰 MC —— 这样能被无头测试
 * 直接跑（「BV 号少一位」「短链没认出」这类错肉眼看不出来）。真正的 HTTP 请求在
 * {@code client/media/net/OnlineVideoResolver} 里做。</p>
 *
 * <p>识别的链接种类：</p>
 * <ul>
 *   <li><b>B 站投稿视频</b>：{@code bilibili.com/video/BV…}、{@code av…}、{@code b23.tv/…} 短链
 *       （支持 {@code ?p=2} 分页）；</li>
 *   <li><b>B 站番剧</b>：{@code bangumi/play/ep…}、{@code ss…}；</li>
 *   <li><b>B 站直播</b>：{@code live.bilibili.com/<房间号>}；</li>
 *   <li><b>yhdm.one</b> 动漫站：{@code yhdm.one/vod-play/<id>/<vid>.html}；</li>
 *   <li><b>任意直链</b>：其它 http(s) 地址（mp4/webm/mkv/flv/mov/ts/mjpg/zip…）。</li>
 * </ul>
 *
 * <p>解析思路参照工作区里 <b>MIT</b> 许可的 {@code Bilibili-Media-Mod-2-neoforge}
 * （它给 WaterMedia 打 Mixin 加同样的三个接口），本项目按同样的接口<b>自行实现</b>：
 * 不打包它的代码、不做 mixin、也绝不 import/链接/打包 WaterMedia。</p>
 */
public final class OnlineVideoLink {
    private OnlineVideoLink() {
    }

    /** 链接种类。 */
    public enum Kind {
        /** 空链接（= 用本地/服务器素材，不是在线视频）。 */
        NONE,
        /** 任意 http(s) 直链。 */
        DIRECT,
        /** B 站投稿视频（BV/av/b23.tv）。 */
        BILIBILI_VIDEO,
        /** B 站番剧（ep/ss）。 */
        BILIBILI_BANGUMI,
        /** B 站直播。 */
        BILIBILI_LIVE,
        /** yhdm.one 动漫站。 */
        YHDM,
        /** 填了但不是能用的链接（非 http/https、太长、含空白）。 */
        BAD
    }

    /**
     * 识别结果。
     *
     * @param kind 种类
     * @param url  规范化后的链接（用于日志与同源判断）
     * @param id   关键标识：BV 号 / {@code av<数字>} / {@code ep<数字>} / {@code ss<数字>} /
     *             房间号 / {@code <id>/<vid>}（yhdm）；直链时为空串
     * @param page 分页（{@code ?p=N}，从 1 开始；不认识时 1）
     * @param ext  从链接里猜到的扩展名（小写、不含点；猜不到为空串）
     */
    public record Target(Kind kind, String url, String id, int page, String ext) {
        /** 是不是「认得、可以去解析」的链接。 */
        public boolean resolvable() {
            return kind != Kind.NONE && kind != Kind.BAD;
        }

        /** 是不是 B 站的东西（要带 Referer 才拉得动直链）。 */
        public boolean bilibili() {
            return kind == Kind.BILIBILI_VIDEO || kind == Kind.BILIBILI_BANGUMI
                    || kind == Kind.BILIBILI_LIVE;
        }

        public static Target none() {
            return new Target(Kind.NONE, "", "", 1, "");
        }

        public static Target bad(String url) {
            return new Target(Kind.BAD, url == null ? "" : url, "", 1, "");
        }
    }

    /** 链接长度上限（防止有人往 NBT 里塞一兆）。 */
    public static final int MAX_LENGTH = 512;

    /** 请求头用的浏览器 UA（B 站接口对默认的 Java UA 不友好）。 */
    public static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            + " (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** 短链展开时用的手机 UA（b23.tv 按 UA 决定跳到哪个页面）。 */
    public static final String UA_MOBILE = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_2 like Mac OS X)"
            + " AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";

    public static final String BILI_WEB = "https://www.bilibili.com/";
    public static final String BILI_LIVE = "https://live.bilibili.com/";

    /** 认得的视频扩展名（猜文件名用；顺带能挡住 "http://x.com/page.html" 这类明显不是视频的直链）。 */
    private static final String[] VIDEO_EXTS = {
            "mp4", "m4v", "webm", "mkv", "mov", "avi", "flv", "ts", "m2ts", "mpg", "mpeg",
            "3gp", "wmv", "mjpg", "mjpeg", "m3u8", "zip", "mpd"};

    /**
     * 这个字符串能不能当在线视频链接用。
     *
     * <p>只允许 {@code http/https}：其它协议（{@code file:}、{@code jar:}…）一律拒绝 ——
     * 链接会被同步到所有客户端，不能变成「读别人本机文件」的口子。</p>
     */
    public static boolean acceptable(String raw) {
        if (raw == null) {
            return false;
        }
        String s = raw.trim();
        if (s.isEmpty() || s.length() > MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == ' ') {
                return false;
            }
        }
        String lower = s.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /** 规范化：去空白；没写协议的 {@code www.bilibili.com/...} 补 https。 */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        String lower = s.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://") && !s.isEmpty()) {
            s = "https://" + s;
        }
        return s;
    }

    /** 识别一条链接。 */
    public static Target parse(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) {
            return Target.none();
        }
        if (!acceptable(s)) {
            return Target.bad(s);
        }
        s = normalize(s);
        String lower = s.toLowerCase(Locale.ROOT);

        // ① B 站短链：要先去展开（展开在 Resolver 里做），这里认出来就行
        if (lower.contains("b23.tv/")) {
            String id = s.substring(lower.indexOf("b23.tv/") + "b23.tv/".length());
            int q = id.indexOf('?');
            if (q >= 0) {
                id = id.substring(0, q);
            }
            return new Target(Kind.BILIBILI_VIDEO, s, "b23:" + id, 1, "");
        }
        // ② 直播（必须先于番剧/视频判断：live.bilibili.com 也可能带 BV 字样）
        String room = firstGroup(s, "live\\.bilibili\\.com/(?:blanc/|h5/)?(\\d+)");
        if (room != null) {
            return new Target(Kind.BILIBILI_LIVE, s, room, 1, "flv");
        }
        // ③ 番剧 ep/ss
        String ep = firstGroup(s, "/ep(\\d+)");
        if (ep != null) {
            return new Target(Kind.BILIBILI_BANGUMI, s, "ep" + ep, 1, "mp4");
        }
        String ss = firstGroup(s, "/ss(\\d+)");
        if (ss != null) {
            return new Target(Kind.BILIBILI_BANGUMI, s, "ss" + ss, 1, "mp4");
        }
        // ④ 投稿视频 BV / av
        String bv = bvidOf(s);
        if (bv != null) {
            return new Target(Kind.BILIBILI_VIDEO, s, bv, pageOf(s), "mp4");
        }
        String av = firstGroup(s, "(?:^|[^0-9a-zA-Z])av(\\d{1,12})");
        if (av != null && lower.contains("bilibili.com")) {
            return new Target(Kind.BILIBILI_VIDEO, s, "av" + av, pageOf(s), "mp4");
        }
        // ⑤ yhdm.one
        String[] yhdm = twoGroups(s, "yhdm\\.one/vod-play/([^/]+)/([^.]+)\\.html");
        if (yhdm != null) {
            return new Target(Kind.YHDM, s, yhdm[0] + "/" + yhdm[1], 1, "m3u8");
        }
        // ⑥ 其余一律按直链处理（扩展名猜得到就用，猜不到留给下载时看 Content-Type）
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return Target.bad(s);
        }
        return new Target(Kind.DIRECT, s, "", 1, extOf(s));
    }

    /** 从链接里取 BV 号（取不到返回 null）。 */
    public static String bvidOf(String url) {
        return firstGroup(url, "(BV[0-9A-Za-z]{8,})");
    }

    /** 从链接里取 {@code ?p=N} 分页（默认 1）。 */
    public static int pageOf(String url) {
        String p = firstGroup(url, "[?&]p=(\\d{1,3})");
        if (p == null) {
            return 1;
        }
        try {
            int n = Integer.parseInt(p);
            return n < 1 ? 1 : n;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** 从链接路径里猜扩展名（小写、不含点；猜不到返回空串）。 */
    public static String extOf(String url) {
        if (url == null) {
            return "";
        }
        String path = url;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        int slash = path.lastIndexOf('/');
        if (slash >= 0) {
            path = path.substring(slash + 1);
        }
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) {
            return "";
        }
        String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (String known : VIDEO_EXTS) {
            if (known.equals(ext)) {
                return ext;
            }
        }
        return "";
    }

    /** 扩展名是不是内置解码器能直接放的（MJPEG 单文件 / ZIP 帧序列）。 */
    public static boolean nativeExt(String ext) {
        return "mjpg".equals(ext) || "mjpeg".equals(ext) || "zip".equals(ext);
    }

    // ------------------------------------------------------------------
    // 接口地址（纯拼接；都取自参考实现的接口面）
    // ------------------------------------------------------------------

    /** 投稿视频信息（拿 cid）：BV 走 bvid，av 走 avid。 */
    public static String viewApi(Target t) {
        String id = t.id();
        if (id.startsWith("av")) {
            return "https://api.bilibili.com/x/web-interface/view?aid=" + id.substring(2);
        }
        return "https://api.bilibili.com/x/web-interface/view?bvid=" + id;
    }

    /** 投稿视频取直链（platform=html5 拿到的是普通 durl，不需要登录）。 */
    public static String playApi(Target t, long cid) {
        String id = t.id();
        String key = id.startsWith("av") ? "avid=" + id.substring(2) : "bvid=" + id;
        return "https://api.bilibili.com/x/player/playurl?" + key + "&cid=" + cid
                + "&qn=116&type=&otype=json&platform=html5&high_quality=1";
    }

    /** 番剧取直链（ep 直接用 ep_id；ss 需要先查 season 拿 ep）。 */
    public static String bangumiApi(Target t) {
        String id = t.id();
        String key = id.startsWith("ep") ? "ep_id=" + id.substring(2) : "season_id=" + id.substring(2);
        return "https://api.bilibili.com/pgc/player/web/playurl?" + key
                + "&qn=116&otype=json&platform=html5&high_quality=1";
    }

    /** 番剧季度信息（ss → 第一集 ep_id）。 */
    public static String seasonApi(Target t) {
        return "https://api.bilibili.com/pgc/view/web/season?season_id=" + t.id().substring(2);
    }

    /** 直播真实房间号（短号要换一次）。 */
    public static String liveInitApi(Target t) {
        return "https://api.live.bilibili.com/room/v1/Room/room_init?id=" + t.id();
    }

    /** 直播取流地址。 */
    public static String livePlayApi(Target t) {
        return "https://api.live.bilibili.com/xlive/web-room/v1/playUrl/playUrl?cid=" + t.id()
                + "&platform=h5&qn=10000&https_url_req=1";
    }

    /** yhdm 播放地址接口。 */
    public static String yhdmApi(Target t) {
        return "https://yhdm.one/_get_plays/" + t.id();
    }

    /** 这条链接该带哪个 Referer（B 站的直链不带 Referer 会被 403）。 */
    public static String refererFor(Target t) {
        if (t.kind() == Kind.BILIBILI_LIVE) {
            return BILI_LIVE;
        }
        if (t.bilibili()) {
            return BILI_WEB;
        }
        if (t.kind() == Kind.YHDM) {
            return "https://yhdm.one/";
        }
        if (t.kind() == Kind.DIRECT) {
            // 直链：按「自己的站点根」当 Referer（防盗链的站点多半认这个）
            try {
                java.net.URI u = java.net.URI.create(t.url());
                if (u.getHost() != null) {
                    return u.getScheme() + "://" + u.getHost() + "/";
                }
            } catch (Throwable ignored) {
                // 拼不出来就不带 Referer
            }
        }
        return "";
    }

    /** 下载时给文件起的名字（只是给玩家看的，真实落盘名是哈希）。 */
    public static String displayName(Target t, String ext) {
        String e = ext == null || ext.isEmpty() ? (t.ext().isEmpty() ? "mp4" : t.ext()) : ext;
        String base = switch (t.kind()) {
            case BILIBILI_VIDEO -> "bilibili-" + t.id() + (t.page() > 1 ? "-p" + t.page() : "");
            case BILIBILI_BANGUMI -> "bilibili-" + t.id();
            case BILIBILI_LIVE -> "bilibili-live-" + t.id();
            case YHDM -> "yhdm-" + t.id().replace('/', '-');
            case DIRECT -> "online-" + Integer.toHexString(t.url().hashCode());
            default -> "online";
        };
        return base + "." + e;
    }

    // ------------------------------------------------------------------
    // 内部：正则取组
    // ------------------------------------------------------------------

    private static String firstGroup(String s, String regex) {
        String[] g = twoGroups(s, regex);
        return g == null ? null : g[0];
    }

    private static String[] twoGroups(String s, String regex) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(s);
            if (!m.find()) {
                return null;
            }
            int n = m.groupCount();
            String[] out = new String[Math.max(1, n)];
            out[0] = m.group(1);
            if (n >= 2) {
                out[1] = m.group(2);
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }
}

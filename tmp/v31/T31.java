import top.hmjmfabc.projector.client.media.net.OnlineVideoResolver;
import top.hmjmfabc.projector.common.OnlineVideoLink;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 【27.1.3】在线视频解析的**联网冒烟测试**（T31）。
 *
 * <p>与其它套件不同：这一套**需要联网**，所以它**不在 `run-all.sh` 里**
 * （离线交付前的回归不该依赖外网）。要手动跑：</p>
 *
 * <pre>bash tmp/run-verify.sh tmp/v31 T31</pre>
 *
 * <p>它验证的是无头环境唯一能验证、也最容易出错的那一段：
 * B 站接口的形状（`view` → `cid`、`playurl` → `durl[0].url`）、
 * 直链要不要 Referer、以及拿到的地址**真的能取到数据**。
 * 解析逻辑本身（链接识别）由 T30 离线钉住。</p>
 *
 * <p>网络不通时打印 {@code == NETWORK UNAVAILABLE ==} 并以 0 退出（不算失败）。</p>
 */
public class T31 {
    private static int passed;
    private static final List<String> failed = new ArrayList<>();

    /** 用来测「普通投稿视频」的两个公开老视频（av/BV 两种写法指向同一个投稿）。 */
    private static final String[] VIDEOS = {
            "https://www.bilibili.com/video/av170001",
            "https://www.bilibili.com/video/BV17x411w7KC",
    };

    /** 公开的小样本直链（w3schools 的样例视频）。 */
    private static final String DIRECT_SAMPLE = "https://www.w3schools.com/html/mov_bbb.mp4";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public static void main(String[] args) {
        if (!online()) {
            System.out.println("== NETWORK UNAVAILABLE ==（跳过联网冒烟测试）");
            return;
        }

        directLink();
        bilibiliVideo();
        shortLink();
        bangumi();
        live();
        report();
    }

    /**
     * 番剧（ep / ss）：**现场从接口里取一个真实剧集号**，免得写死的测试数据过期。
     *
     * <p>取法：{@code pgc/web/timeline}（今日番剧表）里既有 {@code episode_id} 也有
     * {@code season_id}，正好把 ep 与 ss 两条路都覆盖。</p>
     */
    private static void bangumi() {
        String body;
        try {
            body = getWithHeaders("https://api.bilibili.com/pgc/web/timeline?types=1&before=0&after=0",
                    "https://www.bilibili.com/");
        } catch (Throwable t) {
            check("番剧列表可访问：" + shortMsg(t), false);
            return;
        }
        String ep = firstNumber(body, "\"episode_id\":(\\d+)");
        String ss = firstNumber(body, "\"season_id\":(\\d+)");
        if (ep == null || ss == null) {
            check("番剧列表里能取到 ep_id/season_id", false);
            return;
        }
        System.out.println("      （今日番剧：ep" + ep + " / ss" + ss + "）");

        try {
            OnlineVideoResolver.Resolved r =
                    OnlineVideoResolver.resolve("https://www.bilibili.com/bangumi/play/ep" + ep);
            check("番剧 ep 解析成功（" + host(r.url()) + "）", !r.url().isEmpty());
            int code = rangeGet(r.url(), r.referer());
            check("番剧直链能取到数据（HTTP " + code + "）", code == 200 || code == 206);
        } catch (Throwable t) {
            check("番剧 ep 解析：" + shortMsg(t), false);
        }
        try {
            OnlineVideoResolver.Resolved r =
                    OnlineVideoResolver.resolve("https://www.bilibili.com/bangumi/play/ss" + ss);
            check("番剧 ss 解析成功（先查季再取第一集，" + host(r.url()) + "）", !r.url().isEmpty());
        } catch (Throwable t) {
            check("番剧 ss 解析：" + shortMsg(t), false);
        }
    }

    // ------------------------------------------------------------------ 各项

    /** 任意直链：能解析、能按我们的头取到数据。 */
    private static void directLink() {
        try {
            OnlineVideoResolver.Resolved r = OnlineVideoResolver.resolve(DIRECT_SAMPLE);
            check("直链解析成功（扩展名 " + r.ext() + "）",
                    r.url().equals(DIRECT_SAMPLE) && "mp4".equals(r.ext()));
            int code = rangeGet(r.url(), r.referer());
            check("直链能取到数据（HTTP " + code + "）", code == 200 || code == 206);
        } catch (Throwable t) {
            check("直链解析：" + shortMsg(t), false);
        }
    }

    /** B 站投稿视频：view → cid → playurl → durl，并确认这个地址带 Referer 能取到数据。 */
    private static void bilibiliVideo() {
        boolean any = false;
        for (String url : VIDEOS) {
            try {
                OnlineVideoResolver.Resolved r = OnlineVideoResolver.resolve(url);
                boolean looksLikeCdn = r.url().startsWith("http")
                        && (r.url().contains("bilivideo") || r.url().contains("akamaized")
                        || r.url().contains("bilibili") || r.url().contains("upos"));
                check("投稿视频解析成功 " + tail(url) + " -> " + host(r.url()) + "（" + r.note() + "）",
                        looksLikeCdn && !r.url().isEmpty());
                int code = rangeGet(r.url(), r.referer());
                check("直链带 Referer 能取到数据（HTTP " + code + "）", code == 200 || code == 206);
                int noRef = rangeGet(r.url(), "");
                System.out.println("      （不带 Referer 时 HTTP " + noRef + "）");
                any = true;
            } catch (Throwable t) {
                System.out.println("      " + tail(url) + " 解析失败：" + shortMsg(t));
            }
        }
        check("至少有一个投稿视频解析成功（接口形状没变）", any);
    }

    /** b23.tv 短链：跟 302 展开。 */
    private static void shortLink() {
        try {
            String expanded = OnlineVideoResolver.expand("https://b23.tv/BV17x411w7KC");
            check("短链展开成功 -> " + tail(expanded), expanded.contains("bilibili.com"));
        } catch (Throwable t) {
            check("短链展开：" + shortMsg(t), false);
        }
    }

    /** 直播：room_init → playUrl。房间没开播也算通过（能拿到接口响应即可）。 */
    private static void live() {
        try {
            OnlineVideoResolver.Resolved r = OnlineVideoResolver.resolve("https://live.bilibili.com/1");
            check("直播解析成功（流地址 " + host(r.url()) + "）", r.live() && !r.url().isEmpty());
        } catch (Throwable t) {
            // 房间 1 可能没开播 / 需要登录：把原因打出来，但不当成硬失败
            System.out.println("      （直播间 1 未解析： " + shortMsg(t) + "）");
            check("直播接口至少返回了可解释的错误（不是结构崩了）",
                    !shortMsg(t).contains("ClassCast")
                            && !shortMsg(t).contains("NullPointer"));
        }
    }

    // ------------------------------------------------------------------ 工具

    /** 探一次头：拿 0~1 字节就够证明这个地址可用。 */
    private static int rangeGet(String url, String referer) {        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", OnlineVideoLink.UA)
                    .header("Range", "bytes=0-1023")
                    .GET();
            if (referer != null && !referer.isEmpty()) {
                b.header("Referer", referer);
            }
            return CLIENT.send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Throwable t) {
            System.out.println("      （取数据异常：" + shortMsg(t) + "）");
            return -1;
        }
    }

    /** 带 B 站那套头的 GET（测试自己取数据用）。 */
    private static String getWithHeaders(String url, String referer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", OnlineVideoLink.UA)
                .GET();
        if (referer != null && !referer.isEmpty()) {
            b.header("Referer", referer);
        }
        return CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    /** 从 JSON 文本里抠第一个匹配的数字（测试不值得为它引 JSON 库）。 */
    private static String firstNumber(String body, String regex) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(body);
            return m.find() ? m.group(1) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean online() {        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.bilibili.com/x/web-interface/view?bvid=BV17x411w7KC"))
                    .timeout(Duration.ofSeconds(12))
                    .header("User-Agent", OnlineVideoLink.UA)
                    .GET()
                    .build();
            HttpResponse<String> r = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            return r.statusCode() >= 200 && r.statusCode() < 500;
        } catch (Throwable t) {
            System.out.println("      （联网检查失败：" + shortMsg(t) + "）");
            return false;
        }
    }

    private static String host(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Throwable t) {
            return url.length() > 40 ? url.substring(0, 40) : url;
        }
    }

    private static String tail(String url) {
        return url.length() > 46 ? "…" + url.substring(url.length() - 46) : url;
    }

    private static String shortMsg(Throwable t) {
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  \u2705 " + name);
        } else {
            failed.add(name);
            System.out.println("  \u274c " + name);
        }
    }

    private static void report() {
        System.out.println();
        System.out.println("== " + (failed.isEmpty() ? "ALL PASS ==" : "FAILED ==") + "  "
                + passed + " passed, " + failed.size() + " failed");
        if (!failed.isEmpty()) {
            System.exit(1);
        }
    }
}

import top.hmjmfabc.projector.common.OnlineVideoLink;
import top.hmjmfabc.projector.common.OnlineVideoLink.Kind;
import top.hmjmfabc.projector.common.OnlineVideoLink.Target;

import java.util.ArrayList;
import java.util.List;

/**
 * 【27.1.3】在线视频链接识别（T30）。
 *
 * <p>只测**纯逻辑**：链接认不认得出、BV/av/ep/ss/房间号取得对不对、分页取没取到、
 * API 地址拼得对不对、非法协议有没有被挡掉。真正的联网解析没法在无头环境跑
 * （要访问 bilibili.com），所以这里钉的是「解析之前那一步」——
 * 而这一步的错误（BV 少一位、短链没认出、file:// 混进来）在游戏里表现为
 * 「填了链接却什么都不发生」，肉眼根本看不出来。</p>
 */
public class T30 {
    private static int passed;
    private static final List<String> failed = new ArrayList<>();

    public static void main(String[] args) {
        basics();
        bilibiliVideo();
        bangumi();
        live();
        yhdmAndDirect();
        rejects();
        apiUrls();
        names();

        System.out.println();
        for (String f : failed) {
            System.out.println("  \u274c " + f);
        }
        System.out.println("== " + (failed.isEmpty() ? "ALL PASS ==" : "FAILED ==") + "  "
                + passed + " passed, " + failed.size() + " failed");
        if (!failed.isEmpty()) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ 基础

    private static void basics() {
        check("空链接 = 没有在线源", OnlineVideoLink.parse("").kind() == Kind.NONE
                && OnlineVideoLink.parse(null).kind() == Kind.NONE);
        check("没写协议也会补 https", "https://www.bilibili.com/video/BV1xx411c7mD"
                .equals(OnlineVideoLink.normalize("www.bilibili.com/video/BV1xx411c7mD")));
        check("只认 http/https", OnlineVideoLink.acceptable("http://a.com/x.mp4")
                && OnlineVideoLink.acceptable("https://a.com/x.mp4")
                && !OnlineVideoLink.acceptable("file:///etc/passwd")
                && !OnlineVideoLink.acceptable("jar:file:/x.jar!/a"));
        check("超长链接被拒（防 NBT 塞垃圾）",
                !OnlineVideoLink.acceptable("https://a.com/" + "x".repeat(600)));
        check("含空白的链接被拒", !OnlineVideoLink.acceptable("https://a.com/a b.mp4"));
    }

    // ------------------------------------------------------------------ B 站投稿视频

    private static void bilibiliVideo() {
        Target bv = OnlineVideoLink.parse("https://www.bilibili.com/video/BV1xx411c7mD");
        check("认得 BV 号", bv.kind() == Kind.BILIBILI_VIDEO && "BV1xx411c7mD".equals(bv.id())
                && bv.page() == 1);
        Target p3 = OnlineVideoLink.parse("https://www.bilibili.com/video/BV1xx411c7mD?p=3");
        check("认得 ?p=3 分页", p3.page() == 3 && "BV1xx411c7mD".equals(p3.id()));
        Target av = OnlineVideoLink.parse("https://www.bilibili.com/video/av12345");
        check("认得 av 号", av.kind() == Kind.BILIBILI_VIDEO && "av12345".equals(av.id()));
        Target short1 = OnlineVideoLink.parse("https://b23.tv/abcd1234");
        check("认得 b23.tv 短链（待展开）", short1.kind() == Kind.BILIBILI_VIDEO
                && short1.id().startsWith("b23:"));
        Target params = OnlineVideoLink.parse(
                "https://www.bilibili.com/video/BV1xx411c7mD/?spm_id_from=333.999&vd_source=abc");
        check("带一堆参数也认得出来", params.kind() == Kind.BILIBILI_VIDEO
                && "BV1xx411c7mD".equals(params.id()));
    }

    // ------------------------------------------------------------------ 番剧

    private static void bangumi() {
        Target ep = OnlineVideoLink.parse("https://www.bilibili.com/bangumi/play/ep123456");
        check("认得番剧 ep", ep.kind() == Kind.BILIBILI_BANGUMI && "ep123456".equals(ep.id()));
        Target ss = OnlineVideoLink.parse("https://www.bilibili.com/bangumi/play/ss6789");
        check("认得番剧 ss", ss.kind() == Kind.BILIBILI_BANGUMI && "ss6789".equals(ss.id()));
    }

    // ------------------------------------------------------------------ 直播

    private static void live() {
        Target room = OnlineVideoLink.parse("https://live.bilibili.com/12345");
        check("认得直播间", room.kind() == Kind.BILIBILI_LIVE && "12345".equals(room.id()));
        Target withQuery = OnlineVideoLink.parse("https://live.bilibili.com/22637261?broadcast_type=0");
        check("直播间带参数也认得", withQuery.kind() == Kind.BILIBILI_LIVE
                && "22637261".equals(withQuery.id()));
    }

    // ------------------------------------------------------------------ yhdm 与直链

    private static void yhdmAndDirect() {
        Target y = OnlineVideoLink.parse("https://yhdm.one/vod-play/kZpXbDf/1.html");
        check("认得 yhdm 播放页", y.kind() == Kind.YHDM && "kZpXbDf/1".equals(y.id()));
        Target mp4 = OnlineVideoLink.parse("https://cdn.example.com/a/b/clip.mp4?token=xyz");
        check("直链：认出 mp4（参数不影响）", mp4.kind() == Kind.DIRECT && "mp4".equals(mp4.ext()));
        Target mjpg = OnlineVideoLink.parse("https://a.com/x.mjpeg");
        check("直链：mjpg/mjpeg 是内置后端能直接放的",
                "mjpeg".equals(mjpg.ext()) && OnlineVideoLink.nativeExt("mjpeg")
                        && OnlineVideoLink.nativeExt("zip") && !OnlineVideoLink.nativeExt("mp4"));
        Target noExt = OnlineVideoLink.parse("https://a.com/watch?v=1");
        check("直链：猜不到扩展名就留空（下载时看 Content-Type）", noExt.ext().isEmpty());
        check("扩展名只从路径取（不会把 ?a=1 当成扩展名）",
                OnlineVideoLink.extOf("https://a.com/v?x=1.mp4").isEmpty());
    }

    // ------------------------------------------------------------------ 拒绝

    private static void rejects() {
        Target bad = OnlineVideoLink.parse("file:///etc/passwd");
        check("file:// 一律拒绝", bad.kind() == Kind.BAD && !bad.resolvable());
        Target bad2 = OnlineVideoLink.parse("https://a.com/" + "y".repeat(600));
        check("超长链接拒绝", bad2.kind() == Kind.BAD);
        check("拒绝的链接不会被当成可解析目标",
                !OnlineVideoLink.parse("").resolvable() && OnlineVideoLink.parse("").kind() == Kind.NONE);
    }

    // ------------------------------------------------------------------ 接口地址

    private static void apiUrls() {
        Target bv = OnlineVideoLink.parse("https://www.bilibili.com/video/BV1xx411c7mD");
        check("view 接口：BV 走 bvid", OnlineVideoLink.viewApi(bv).contains("bvid=BV1xx411c7mD"));
        Target av = OnlineVideoLink.parse("https://www.bilibili.com/video/av12345");
        check("view 接口：av 走 aid", OnlineVideoLink.viewApi(av).contains("aid=12345"));
        check("playurl 接口：带 cid 与 html5（免登录的普通流）",
                OnlineVideoLink.playApi(bv, 998877L).contains("cid=998877")
                        && OnlineVideoLink.playApi(bv, 998877L).contains("platform=html5"));
        Target ep = OnlineVideoLink.parse("https://www.bilibili.com/bangumi/play/ep123456");
        check("番剧接口：ep_id", OnlineVideoLink.bangumiApi(ep).contains("ep_id=123456"));
        Target ss = OnlineVideoLink.parse("https://www.bilibili.com/bangumi/play/ss6789");
        check("番剧接口：ss 先查 season", OnlineVideoLink.seasonApi(ss).contains("season_id=6789")
                && OnlineVideoLink.bangumiApi(ss).contains("season_id=6789"));
        Target room = OnlineVideoLink.parse("https://live.bilibili.com/12345");
        check("直播接口：先 room_init 再 playUrl",
                OnlineVideoLink.liveInitApi(room).contains("id=12345")
                        && OnlineVideoLink.livePlayApi(room).contains("cid=12345"));
        check("B 站一定要带 Referer（不带会被 CDN 403）",
                OnlineVideoLink.BILI_WEB.equals(OnlineVideoLink.refererFor(bv))
                        && OnlineVideoLink.refererFor(room).startsWith("https://live.bilibili.com"));
        Target direct = OnlineVideoLink.parse("https://cdn.example.com/a/clip.mp4");
        check("直链的 Referer = 自己的站点根（防盗链站点多半认这个）",
                "https://cdn.example.com/".equals(OnlineVideoLink.refererFor(direct)));
    }

    // ------------------------------------------------------------------ 文件名

    private static void names() {
        Target p3 = OnlineVideoLink.parse("https://www.bilibili.com/video/BV1xx411c7mD?p=3");
        check("投稿视频的文件名带分页", "bilibili-BV1xx411c7mD-p3.mp4"
                .equals(OnlineVideoLink.displayName(p3, "mp4")));
        Target room = OnlineVideoLink.parse("https://live.bilibili.com/12345");
        check("直播的文件名带房间号", "bilibili-live-12345.flv"
                .equals(OnlineVideoLink.displayName(room, "flv")));
        Target y = OnlineVideoLink.parse("https://yhdm.one/vod-play/kZpXbDf/1.html");
        check("yhdm 的文件名把斜杠换掉（要能当文件名）",
                !OnlineVideoLink.displayName(y, "m3u8").contains("/")
                        || OnlineVideoLink.displayName(y, "m3u8").startsWith("yhdm-kZpXbDf-1"));
    }

    // ------------------------------------------------------------------ 断言

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  \u2705 " + name);
        } else {
            failed.add(name);
        }
    }
}

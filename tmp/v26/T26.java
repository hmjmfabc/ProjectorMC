package top.hmjmfabc.projector.client.media;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Random;

/**
 * T26 —— 【rc-88】「一画视频就每隔 2 秒卡 1 秒」的回归。
 *
 * <p>查明的机制（有实测数字，不是推测）：
 * <ol>
 *   <li>播放时每一帧都要问一次「本地有没有整段视频」，链路是
 *       {@code MediaCache.videoFrame} → {@code LocalMedia.hasWhole} → {@code byHash}
 *       → {@code rescanIfStale}（TTL <b>2000 ms</b>）。</li>
 *   <li>{@code rescan()} 会给素材目录里<b>每个文件重算 SHA-1</b>，而玩家目录里那个
 *       685 MB 的 mp4 单独实测就要 <b>1013 ms</b>（Termux 实测 FUSE 读 + SHA-1）。</li>
 *   <li>{@code pruneDigestCache()} 用 {@code lastIndexOf('|')} 解析键
 *       {@code 路径|大小|修改时间}（<b>两个</b>竖线）⇒ 取出的是 {@code 路径|大小}，
 *       永远匹配不上 ⇒ <b>每次扫描都把摘要缓存清空</b>，于是每 2 秒重算一遍。</li>
 * </ol>
 * 暂停播放时 {@code videoFrame} 直接命中帧缓存、走不到这条链，所以「暂停就不卡」；
 * 日志里停顿间隔实测 1.86~2.13 秒、每次 857~1010 ms，与 TTL 和 mp4 的哈希耗时完全吻合。</p>
 *
 * <p>本测试断言的是可验证的部分（不需要 MC/native）：
 * 摘要缓存必须活过第二次扫描、大文件的摘要在后台补算、渲染路径不许碰文件系统、
 * 异步扫描不许在调用线程上跑。</p>
 */
public final class T26 {

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        Path root = Path.of("tmp/v26/root");
        deleteTree(root);
        Path media = root.resolve("media");
        Files.createDirectories(media);
        Files.createDirectories(root.resolve("cache"));

        Path small = media.resolve("small.mjpg");
        Path big = media.resolve("big.mp4");
        // 注意：小文件必须**真的**小于下面设定的阈值，否则「内联算摘要」那条路根本走不到
        writeRandom(small, 512 * 1024);
        writeRandom(big, 4 * 1024 * 1024);
        String bigSha1 = sha1Of(big);

        LocalMedia.ROOT_FOR_TEST = root;
        // 测试里把「内联算摘要」的上限压到 1 MB，好让 4 MB 的文件走「后台补算」那条路
        LocalMedia.setInlineHashMaxForTest(1024 * 1024);

        // ---- ① 第一次扫描：小文件内联算，大文件不在扫描里现算 ----
        long d0 = LocalMedia.digestsComputed();
        LocalMedia.rescan();
        long afterFirst = LocalMedia.digestsComputed() - d0;
        // 用「扫描自己记的账」（在 rescan() 内部结算，不受后台线程影响）来断言，
        // 否则后台线程可能已经算完大文件、把全局计数从 1 变成 2，测试就变成看运气。
        check("第一次扫描只为「小文件」内联算摘要（大文件交给后台）",
                LocalMedia.lastScanInlineDigests() == 1,
                "扫描内联算了 " + LocalMedia.lastScanInlineDigests() + " 个摘要（期望 1：small.mjpg 512KB）");
        check("（参考）扫描期间全局摘要计数没有暴涨", afterFirst <= 2, "实得 " + afterFirst);
        check("摘要缓存里已经有条目", LocalMedia.digestCacheSize() >= 1,
                "实得 " + LocalMedia.digestCacheSize());

        // ---- ② 大文件的摘要由后台线程补算，补算完必须能按 SHA-1 查到 ----
        //（先等后台算完，再断言「重扫不重算」——否则全局计数器会被后台线程的 +1 干扰）
        String smallSha1 = sha1Of(small);
        boolean found = false;
        for (int i = 0; i < 100 && !found; i++) {
            found = LocalMedia.byHash(bigSha1) != null && LocalMedia.byHash(smallSha1) != null;
            if (!found) Thread.sleep(100);
        }
        check("大文件（4 MB > 阈值）的摘要在后台补算完成，byHash 能查到",
                found, "10 秒内没算出来 ⇒ 大文件会永远查不到自己的哈希（又会被重下发）");
        check("两个文件各算了一次摘要（小文件内联 + 大文件后台）",
                LocalMedia.digestsComputed() - d0 == 2,
                "实得 " + (LocalMedia.digestsComputed() - d0) + " 次（期望恰好 2）");

        // ---- ③ 【本轮的核心 bug】摘要都已就绪后，再扫描不许重算任何摘要 ----
        int cacheBefore = LocalMedia.digestCacheSize();
        long d1 = LocalMedia.digestsComputed();
        LocalMedia.rescan();
        long again = LocalMedia.digestsComputed() - d1;
        check("第二次扫描**一次摘要都不重算**（摘要缓存必须活过重扫）",
                again == 0, "实得重算 " + again + " 次 ⇒ pruneDigestCache 又把缓存清空了");
        check("重扫后摘要缓存条目数不变（不是被清空）",
                LocalMedia.digestCacheSize() >= cacheBefore,
                "扫描前 " + cacheBefore + " → 扫描后 " + LocalMedia.digestCacheSize());
        check("重扫后 still 能按哈希查到（含大文件）",
                LocalMedia.byHash(bigSha1) != null, "");

        // ---- ④ hasWholeCached：渲染路径专用，绝不触发扫描、绝不碰文件系统 ----
        LocalMedia.rescan();                       // 让 lastScan 变新、索引非空
        Thread.sleep(50);
        long d2 = LocalMedia.digestsComputed();
        boolean whole = LocalMedia.hasWholeCached(bigSha1);
        check("hasWholeCached 对「本地确实有的整段文件」返回 true", whole, "");
        check("hasWholeCached 不做任何 SHA-1",
                LocalMedia.digestsComputed() == d2, "");
        check("hasWholeCached 不会安排扫描（渲染线程不许碰文件系统）",
                !LocalMedia.scanPending(), "它安排了后台扫描 ⇒ 渲染线程仍可能被 IO 拖住");
        check("hasWholeCached 对不存在的哈希返回 false",
                !LocalMedia.hasWholeCached("0000000000000000000000000000000000000000"), "");

        // ---- ⑤ 索引过期时，扫描不许在调用线程上跑（**确定性**：不靠 TTL 计时） ----
        Files.write(media.resolve("newer.mjpg"), new byte[64 * 1024]);   // 新文件 ⇒ 后台扫描必然要算摘要
        LocalMedia.markScanStaleForTest();                               // 强制「索引已过期」
        long d3 = LocalMedia.digestsComputed();
        long t0 = System.nanoTime();
        LocalMedia.rescanAsyncIfStale();
        long tookMs = (System.nanoTime() - t0) / 1_000_000L;
        check("rescanAsyncIfStale 立刻返回（不占用调用线程）",
                tookMs < 100, "实得 " + tookMs + " ms");
        check("过期的扫描**没有**在调用线程上算摘要",
                LocalMedia.digestsComputed() == d3, "调用线程算了摘要 ⇒ 又把 IO 放回渲染线程了");
        check("扫描被交给后台（要么还在排队，要么已经由后台做完）",
                LocalMedia.scanPending() || LocalMedia.digestsComputed() > d3,
                "scanPending=false 且摘要计数没变 ⇒ 扫描没被安排");
        boolean done = false;
        for (int i = 0; i < 60 && !done; i++) {
            done = !LocalMedia.scanPending();
            if (!done) Thread.sleep(100);
        }
        check("后台扫描会自己结束（不会永远 pending）", done, "");
        check("后台扫描确实算出了新文件的摘要",
                LocalMedia.digestsComputed() > d3, "实得 " + (LocalMedia.digestsComputed() - d3) + " 次");

        // ---- ⑥ 源码级不变量：渲染路径不许出现同步扫描与旧入口 ----
        String mc = read("src/main/java/top/hmjmfabc/projector/client/media/MediaCache.java");
        String videoFrame = slice(mc, "public static Frame videoFrame(", "static int pickSlot(");
        check("能切出 videoFrame 源码", videoFrame.length() > 200, "长度 " + videoFrame.length());
        // 【rc-88 收尾】渲染线程现在**一个 LocalMedia 入口都不碰**：只往 WHOLE_CANDIDATE 打标记，
        // 真正的判断（要读文件系统，还得认旧命名 <hash>_f0.bin）搬到了工作线程的 escalateToWholeIfNeeded。
        check("videoFrame 只打「候选」标记（WHOLE_CANDIDATE）",
                videoFrame.contains("WHOLE_CANDIDATE.add(hash)"), "");
        check("videoFrame 不碰任何 LocalMedia 入口",
                !videoFrame.contains("LocalMedia."), "渲染线程又开始查本地文件了 ⇒ 老问题会复发");
        check("videoFrame 里不许出现扫描入口",
                !videoFrame.contains("rescanIfStale") && !videoFrame.contains("rescanAsyncIfStale"), "");
        String esc = slice(mc, "static void escalateToWholeIfNeeded(", "private static void loadFrameFromServer(");
        check("升级判断（工作线程）里才查 LocalMedia.hasWhole",
                esc.contains("LocalMedia.hasWhole(hash)"), "");
        check("升级判断挂在 requestLoad 的 worker 上",
                mc.contains("if (video) escalateToWholeIfNeeded(hash);"), "");

        String lm = read("src/main/java/top/hmjmfabc/projector/client/media/LocalMedia.java");
        check("pruneDigestCache 用 indexOf('|') 解析键（不是 lastIndexOf）",
                lm.contains("int i = k.indexOf('|');"), "键是 路径|大小|修改时间，用 lastIndexOf 会留下「路径|大小」");
        check("pruneDigestCache 里没有 lastIndexOf('|')",
                !slice(lm, "private static void pruneDigestCache(", "private static String hex(")
                        .contains("lastIndexOf('|')"), "");
        check("byHash 走的是异步扫描入口",
                slice(lm, "public static MediaFile byHash(", "public static boolean hasWhole(")
                        .contains("rescanAsyncIfStale()"), "");
        check("rescan 里有大文件延迟补算（digestLater）", lm.contains("digestLater(p, mf)"), "");
        check("rescan 有慢扫描告警（>=150ms 必须留痕）", lm.contains("SLOW_SCAN_LOG_MS"), "");

        String wr = read("src/main/java/top/hmjmfabc/projector/client/render/WidgetRenderer.java");
        check("渲染器不直接碰 LocalMedia 的扫描/文件索引",
                !wr.contains("LocalMedia.rescan") && !wr.contains("LocalMedia.byHash"), "");
        check("「视频控件」日志已限流（不然 10 行/秒把日志冲爆）",
                wr.contains("lastVideoReportMs"), "");

        LocalMedia.ROOT_FOR_TEST = null;
        deleteTree(root);

        System.out.println();
        System.out.println("== " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " ==  " + pass + " passed");
        if (fail > 0) System.exit(1);
    }

    // ------------------------------------------------------------------

    private static void writeRandom(Path p, int bytes) throws Exception {
        Random r = new Random(12345);
        byte[] buf = new byte[bytes];
        r.nextBytes(buf);
        Files.write(p, buf);
    }

    private static String sha1Of(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] d = md.digest(Files.readAllBytes(p));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var s = Files.walk(root)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        }
    }

    private static String slice(String s, String from, String to) {
        if (s == null) return "";
        int a = s.indexOf(from);
        if (a < 0) return "";
        int b = s.indexOf(to, a);
        return b < 0 ? s.substring(a) : s.substring(a, b);
    }

    private static String read(String rel) {
        try {
            // 去注释后再断言「某段代码里有没有某个调用」——否则注释里提到的旧写法
            //（例如「这里以前调的是 hasWhole」）会把断言弄成假红/假绿。
            return new String(Files.readAllBytes(Path.of(rel)), java.nio.charset.StandardCharsets.UTF_8)
                    .replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
        } catch (Exception e) {
            return null;
        }
    }

    private static void check(String what, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  ✅ " + what);
        } else {
            fail++;
            System.out.println("  ❌ " + what + (detail.isEmpty() ? "" : "  ← " + detail));
        }
    }
}

package top.hmjmfabc.projector.client.media;

import top.hmjmfabc.projector.network.Payloads;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * T21 ——【rc-83】「延迟特别大，以至于无法圈选平面和破坏方块」。
 *
 * <p>玩家实测：服务端在 28 秒里把一张图片分片下发了 <b>297 片 / 58 MB</b>，
 * 另一个会话 <b>505 片 / 98.6 MB</b>（都恰好是 200 KB 的整数倍 ⇒ 永远没收到最后一片），
 * 24 MB 的视频被整段下发 3 次。批量流量把链路灌满 ⇒ 保活包与操作包被饿死 ⇒
 * 圈选平面、破坏方块全部点不动，最后 {@code Connection reset}。</p>
 *
 * <p>两个独立根因：</p>
 * <ol>
 *   <li><b>在途标志被当成分片级的东西</b>：{@code requestFromServer} 的 {@code "dl"}
 *       标志在**每收到一个分片**时就被移除，而 {@code requestLoad} 的 {@code ""}
 *       在工作线程交出网络请求后立刻移除 ⇒ 纹理还没就绪的渲染线程每一帧都能重新发一次
 *       {@code offset = 0} 的请求，同一段 200 KB 被反复下发，重组缓冲永远填不满。</li>
 *   <li><b>帧号语义混淆</b>：协议里 {@code frame == 0} 被当成「整段文件」，
 *       而视频播放循环必然反复回到第 0 帧 ⇒ 每循环一圈就重发一次整段文件。</li>
 * </ol>
 *
 * <p>本测试<b>真的驱动一遍下载状态机</b>（发送口 {@code MediaCache.SENDER} 可替换），
 * 断言的是行为不变量：</p>
 * <ol>
 *   <li>同一份媒体在途时，重复请求必须被抑制（不发送）；</li>
 *   <li>续传的偏移必须<b>严格递增、绝不回到 0</b>（这是本次风暴的判据）；</li>
 *   <li>服务端下发的总字节必须正好等于文件大小（不许多发一个字节）；</li>
 *   <li>收齐后必须释放「在途」记录与传输登记（否则下一份媒体永远排在它后面）；</li>
 *   <li>整段下载用帧号 {@link MediaCache#WHOLE}（-1），第 0 帧是合法的单帧请求；</li>
 *   <li>限速是纯函数且默认生效（2 MB/s）。</li>
 * </ol>
 */
public final class T21 {

    private static int pass;
    private static int fail;

    /** 假的媒体哈希（40 位十六进制，形状与真实 SHA-1 一致）。 */
    private static final String HASH = "0123456789abcdef0123456789abcdef01234567";
    /** 模拟文件：3 个整片 + 1 个半片。 */
    private static final int TOTAL = 200 * 1024 * 3 + 100 * 1024;
    private static final int CHUNK = 200 * 1024;

    /** 客户端实际发出的请求（由测试替身收集）。 */
    private static final List<Payloads.MediaRequest> SENT =
            Collections.synchronizedList(new ArrayList<>());
    /** 假服务端实际下发的字节总数（用来抓「重复下发同一段」）。 */
    private static final List<Integer> SENT_BYTES = new CopyOnWriteArrayList<>();

    public static void main(String[] args) throws Exception {
        // 替换发送口：无头环境没有 Minecraft 实例，也不该真发包
        MediaCache.SENDER = SENT::add;

        check("限速是纯函数：2 MB/s 下 200 KB 一片约 97 ms",
                MediaCache.paceDelayMs(CHUNK, 2 * 1024 * 1024) == 97L,
                "实算=" + MediaCache.paceDelayMs(CHUNK, 2 * 1024 * 1024));
        check("限速 0 = 不限速（延迟必须为 0）",
                MediaCache.paceDelayMs(CHUNK, 0L) == 0L, "");
        check("整段文件的帧号是 -1（与第 0 帧区分开）", MediaCache.WHOLE == -1, "");

        // ---- ① 同一份媒体：重复请求必须被抑制 ----
        MediaCache.requestFromServer("测试", HASH, 0);
        check("首次请求必须立刻发出 1 个包", SENT.size() == 1, "实发=" + SENT.size());
        check("首片请求的偏移是 0", SENT.get(0).offset() == 0, "offset=" + SENT.get(0).offset());
        check("首片请求的长度是 200 KB", SENT.get(0).length() == CHUNK,
                "length=" + SENT.get(0).length());

        for (int i = 0; i < 50; i++) {
            MediaCache.requestFromServer("测试（重复）", HASH, 0);
        }
        check("在途期间重复请求被全部抑制（50 次重复只发 0 个包）",
                SENT.size() == 1, "实发=" + SENT.size() + "（必须恒为 1）");
        check("在途记录只有 1 条", MediaCache.downloadingCount() == 1,
                "实有=" + MediaCache.downloadingCount());

        // ---- ② 驱动整份下载：偏移必须严格递增、绝不回到 0 ----
        List<Integer> offsets = new ArrayList<>();
        offsets.add(0);
        int issued = 1;
        int guard = 0;
        while (issued < 64 && guard++ < 400) {
            if (SENT.size() <= issued - 1) {
                Thread.sleep(20L);
                continue;
            }
            Payloads.MediaRequest req = SENT.get(issued - 1);
            int off = req.offset();
            if (issued > 1) {
                offsets.add(off);
                if (off == 0) {
                    check("续传偏移不许回到 0（第 " + issued + " 个请求）", false,
                            "又从头开始了 —— 这正是服务端反复下发同一份内容的根因");
                    break;
                }
                if (off <= offsets.get(offsets.size() - 2)) {
                    check("续传偏移必须严格递增", false,
                            offsets.get(offsets.size() - 2) + " -> " + off);
                    break;
                }
            }
            // 假服务端：按请求的偏移/长度回一片，最后一片带 last
            int len = Math.min(Math.min(req.length() <= 0 ? CHUNK : req.length(), CHUNK), TOTAL - off);
            if (len <= 0) break;
            boolean last = off + len >= TOTAL;
            SENT_BYTES.add(len);
            MediaCache.onMediaChunk(HASH, 0, off, TOTAL, false, new byte[len], last, false);
            issued++;
            // 等客户端把这一片处理完（分片处理走媒体池；续传可能受限速延后）
            long deadline = System.currentTimeMillis() + 3000L;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(15L);
                if (MediaCache.downloadingCount() == 0) break;
                if (SENT.size() >= issued + 1) break;
                if (SENT.size() == issued) {
                    // 已经收齐：不再有新请求，等状态清空
                    if (last) continue;
                }
            }
        }

        check("请求偏移序列完全正确（严格递增、0 只出现一次）",
                offsets.toString().equals("[0, 204800, 409600, 614400]"),
                "偏移序列=" + offsets + "（每一次都必须从上一片的末尾继续）");
        long sum = 0L;
        for (int b : SENT_BYTES) sum += b;
        check("服务端下发的总字节正好等于文件大小（不多发一分）",
                sum == TOTAL, "总和=" + sum + " 期望=" + TOTAL);
        check("收齐后在途记录已释放", MediaCache.downloadingCount() == 0,
                "仍有 " + MediaCache.downloadingCount() + " 条在途");
        check("收齐后传输登记已结算（开始与结束一一对应）",
                TransferLog.activeCount() == 0, "仍有 " + TransferLog.activeCount() + " 条登记");

        // ---- ③ 整段视频：进度显示与帧号 ----
        final String VHASH = "f".repeat(40);
        SENT.clear();
        SENT_BYTES.clear();
        MediaCache.requestFromServer("测试（整段）", VHASH, MediaCache.WHOLE);
        check("整段请求只发一次", SENT.size() == 1, "实发=" + SENT.size());
        check("整段请求的帧号是 -1", SENT.get(0).frame() == MediaCache.WHOLE,
                "frame=" + SENT.get(0).frame());
        MediaCache.onMediaChunk(VHASH, MediaCache.WHOLE, 0, 4 * 1024 * 1024, true,
                new byte[CHUNK], false, false);
        Thread.sleep(200L);
        long[] prog = MediaCache.transferProgress(VHASH, 1234);
        check("播放帧变化时进度会回退到「整段下载」那条记录",
                prog != null && prog[1] == 4L * 1024 * 1024,
                prog == null ? "拿不到进度" : ("total=" + prog[1]));

        sourceChecks();

        System.out.println();
        System.out.println("== " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " ==  " + pass + " passed");
        if (fail > 0) System.exit(1);
    }

    /** 结构不变量（源码级，去注释后匹配）：协议判据与「不许多发」的闸门。 */
    private static void sourceChecks() {
        String server = read("src/main/java/top/hmjmfabc/projector/network/ServerNetHandler.java");
        String client = read("src/main/java/top/hmjmfabc/projector/client/media/MediaCache.java");

        check("服务端：整段文件按 frame < 0 判定（不再用 frame == 0）",
                server != null && server.contains("if (payload.frame() < 0) {"),
                "视频整段与第 0 帧仍然混淆 ⇒ 播放循环每圈重发一次整段文件");
        check("服务端：视频整段分支用 readRange 定位读（不再每个分片整读整个文件）",
                server != null && server.contains("store.readRange(server, payload.hash(), absOffset, want)"),
                "每个 200 KB 分片整读 24 MB 文件，主线程会被拖死");
        check("服务端：视频整段分支不再调用 store.read(...)",
                server != null && !server.contains("store.read(server, payload.hash())"),
                "");
        check("服务端：offset=0 的重复起始请求有闸门",
                server != null && server.contains("restartStorm(player, payload.hash())")
                        && server.contains("MAX_WHOLE_RESTARTS"),
                "客户端出现重试风暴时服务端必须主动拒绝");

        check("客户端：在途下载表是唯一闸门（重复请求只计数不发送）",
                client != null && client.contains("DOWNLOADS.get(k)")
                        && client.contains("live.suppressed++"),
                "");
        check("客户端：续传时不解除在途标志（没有按分片删闸门的代码）",
                client != null && !client.contains("loading.remove(\"dl\")"),
                "按分片解除闸门 = 渲染线程每帧都能重发起下载");
        check("客户端：收齐/失败/超时才移除在途记录",
                client != null && client.contains("DOWNLOADS.remove(k)")
                        && client.contains("DOWNLOAD_STALE_MS"),
                "");
        check("客户端：整段下载期间暂停逐帧请求",
                client != null && client.contains("if (isDownloading(hash, WHOLE)) return null;"),
                "否则整段与逐帧两股流量同时灌同一条链路");
        check("客户端：渲染取图时在途直接返回（不再每帧重发起）",
                client != null && client.contains("if (isDownloading(hash, 0)) return null;"),
                "");
        check("客户端：整段文件落盘到 hash.bin（不是 hash_f-1.bin）",
                client != null && client.contains("video && frame >= 0"), "");
    }

    private static String read(String rel) {
        try {
            String s = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Path.of(rel)), java.nio.charset.StandardCharsets.UTF_8);
            // 去掉注释：静态检查必须看代码，不能被说明性注释误判（T19 踩过）
            return s.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
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
            System.out.println("  ❌ " + what + (detail.isEmpty() ? "" : "  —— " + detail));
        }
    }
}

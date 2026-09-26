/**
 * 【T16】媒体传输日志（服务端 + 客户端）的行为不变量。
 *
 * <p>玩家要求：<b>每一次由 Projector 导致的媒体上传/下载都要详细记录</b>。
 * 日志内容本身没法在无头环境里断言，但「什么时候打、打几次」是可以钉住的，
 * 而且这正是最容易被写坏的地方（一逐分片打就刷爆日志 —— AGENTS.md §5.5 第 29 条）。</p>
 *
 * <p>本套件断言：</p>
 * <ol>
 *   <li>服务端：同一玩家 + 同一哈希的一次连续下发<b>只登记一份</b>（不会每分片打一行）；</li>
 *   <li>服务端：不同哈希 / 不同玩家各自独立；结算（空闲、登出、关服）后清空；</li>
 *   <li>服务端：上传累计只统计成功落盘的字节（当日上传累计要能对上）；</li>
 *   <li>客户端：同一哈希 + 同一帧只登记一份，不同帧互不干扰；完成/失败后必须清掉，
 *       否则「开始」那行下次不会再打（这也是失败重试时最容易漏的地方）。</li>
 * </ol>
 *
 * <p>注意：这里刻意用 {@code null} 玩家构造服务端会话 —— TransferLog 对身份缺省是
 * 容错的（打 {@code -}），所以这套逻辑可以脱离 Minecraft 服务器跑。</p>
 */
public class T16 {
    static int fails = 0;

    static void chk(String what, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + what + "   " + detail);
        if (!ok) fails++;
    }

    static final String HASH_A = "0123456789abcdef0123456789abcdef01234567";
    static final String HASH_B = "fedcba9876543210fedcba9876543210fedcba98";

    public static void main(String[] a) {
        serverSide();
        clientSide();

        System.out.println(fails == 0 ? "\n== ALL PASS ==" : "\n== " + fails + " FAILED ==");
        if (fails > 0) System.exit(1);
    }

    // ------------------------------------------------------------------

    static void serverSide() {
        var log = top.hmjmfabc.projector.server.TransferLog.class;

        // 1) 同一份媒体的 5 个分片 -> 只登记一份
        for (int i = 0; i < 5; i++) {
            top.hmjmfabc.projector.server.TransferLog.download(null, "图片", HASH_A, "海报.png",
                    500_000, false, 0, 100_000);
        }
        chk("服务端：同一玩家+同一哈希 5 个分片只登记一份", activeCount(log) == 1,
                "会话数=" + activeCount(log));

        // 2) 换一份媒体 -> 多一份；换帧不算新会话（视频逐帧要合并成一条）
        top.hmjmfabc.projector.server.TransferLog.download(null, "视频", HASH_B, "片子.mjpg",
                8_000_000, true, 3, 200_000);
        top.hmjmfabc.projector.server.TransferLog.download(null, "视频", HASH_B, "片子.mjpg",
                8_000_000, true, 4, 200_000);
        chk("服务端：换一份媒体各算一份、逐帧合并", activeCount(log) == 2,
                "会话数=" + activeCount(log));

        // 3) 结算后清空（空闲/登出/关服三条路径都调 settle）
        top.hmjmfabc.projector.server.TransferLog.flushAll();
        chk("服务端：flushAll 后清空", activeCount(log) == 0, "会话数=" + activeCount(log));

        // 4) 登出结算只清自己那份
        top.hmjmfabc.projector.server.TransferLog.download(null, "图片", HASH_A, "海报.png",
                1000, false, 0, 1000);
        top.hmjmfabc.projector.server.TransferLog.flush(null);
        chk("服务端：flush(玩家) 之后清空", activeCount(log) == 0, "会话数=" + activeCount(log));

        // 5) 上传累计只算成功的
        long before = top.hmjmfabc.projector.server.TransferLog.todayUploadBytes();
        top.hmjmfabc.projector.server.TransferLog.uploadDone(null, HASH_A, "海报.png", 4096, 1, 12, true, 0);
        top.hmjmfabc.projector.server.TransferLog.uploadDone(null, HASH_B, "片子.mjpg", 8192, 2, 30, false, 0);
        top.hmjmfabc.projector.server.TransferLog.uploadFail(null, "超过配额", HASH_A, "大海报.png",
                999_999, 0);
        long after = top.hmjmfabc.projector.server.TransferLog.todayUploadBytes();
        chk("服务端：当日上传累计=成功字节之和（失败不计）", after - before == 4096 + 8192,
                "增量=" + (after - before));
    }

    static void clientSide() {
        var log = top.hmjmfabc.projector.client.media.TransferLog.class;

        // 1) 同一份媒体重复「开始」只登记一份
        top.hmjmfabc.projector.client.media.TransferLog.downloadStart("按需渲染", HASH_A, -1, false);
        top.hmjmfabc.projector.client.media.TransferLog.downloadStart("按需渲染", HASH_A, -1, false);
        top.hmjmfabc.projector.client.media.TransferLog.downloadStart("后台预缓存（缓存档位）", HASH_A, -1, false);
        chk("客户端：同一哈希+同一帧只登记一份", activeCount(log) == 1, "会话数=" + activeCount(log));

        // 2) 分片累加不新增会话
        for (int i = 0; i < 10; i++) {
            top.hmjmfabc.projector.client.media.TransferLog.downloadChunk(HASH_A, -1, 20_000);
        }
        chk("客户端：10 个分片不会新增会话", activeCount(log) == 1, "会话数=" + activeCount(log));

        // 3) 不同帧各算一份（视频逐帧）
        top.hmjmfabc.projector.client.media.TransferLog.downloadStart("按需渲染（视频逐帧）", HASH_B, 5, true);
        top.hmjmfabc.projector.client.media.TransferLog.downloadStart("按需渲染（视频逐帧）", HASH_B, 6, true);
        chk("客户端：不同帧各自登记", activeCount(log) == 3, "会话数=" + activeCount(log));

        // 4) 完成/失败都要把会话清掉，否则下次那份媒体不会再打「开始」
        top.hmjmfabc.projector.client.media.TransferLog.downloadDone(HASH_A, -1, 200_000, null);
        top.hmjmfabc.projector.client.media.TransferLog.downloadFail(HASH_B, 5, "服务端没有这份媒体");
        chk("客户端：完成/失败各清掉一份", activeCount(log) == 1, "会话数=" + activeCount(log));
        top.hmjmfabc.projector.client.media.TransferLog.downloadStart("按需渲染", HASH_A, -1, false);
        chk("客户端：清掉之后还能重新登记（重试/二次播放）", activeCount(log) == 2,
                "会话数=" + activeCount(log));

        // 5) 退出服务器时全部清空
        top.hmjmfabc.projector.client.media.TransferLog.reset();
        chk("客户端：reset 后清空", activeCount(log) == 0, "会话数=" + activeCount(log));
    }

    /** 反射读 activeCount（两个类各有一个，避免直接依赖实现细节的可见性）。 */
    static int activeCount(Class<?> cls) {
        try {
            var m = cls.getMethod("activeCount");
            return (int) m.invoke(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

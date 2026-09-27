package top.hmjmfabc.projector.client.media;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Random;

/**
 * T27 —— 【27.1.1-snapshot-89】内存占用优化的回归。
 *
 * <p>本轮针对三处「白吃内存」的地方：
 * <ol>
 *   <li><b>整段视频下载把整份文件缓在堆里</b>：{@code new byte[total]}（64 MB 的视频就是 64 MB 堆，
 *       校验哈希时还要再引用一次），并且因此有一条「&gt;64MB 不能下载」的硬上限。
 *       现在整段文件**边收边写盘**（{@code <hash>.part} → 校验 → 改名），堆峰值只有一个分片。</li>
 *   <li><b>图片原始字节常驻堆</b>：{@code Entry.raw} 存着整个文件的 byte[]，而全项目没有一处读它
 *       ⇒ 每张图片白占最多 16 MB 且永不释放。现在只留纹理与字节数。</li>
 *   <li><b>视频帧槽位随视频数线性增长</b>：以前只有「每个视频 12 个槽位」这一条，
 *       同时画 N 个视频就是 N 倍占用。现在叠加「总内存预算 / 活跃视频数」的纯逻辑分摊。</li>
 * </ol>
 *
 * <p>断言的都是可测量的量：在途下载占用的堆字节、落盘文件的内容、纯逻辑分摊结果、
 * 内存报表能否给出数字（NativeImage/GL 在这台 aarch64 上无法无头分配，故不测真实纹理）。</p>
 */
public final class T27 {

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("projector-t27");
        Files.createDirectories(root.resolve("cache"));
        LocalMedia.ROOT_FOR_TEST = root;

        // ---- ① 造一份 8 MB 的「整段视频」；哈希就是它自己的 SHA-1（这样校验能过） ----
        int total = 8 * 1024 * 1024;
        byte[] whole = new byte[total];
        new Random(20260926).nextBytes(whole);
        String hash = sha1(whole);
        check("测试哈希是合法 SHA-1（40 位十六进制）",
                top.hmjmfabc.projector.server.Sanitize.isHash(hash), hash);

        MediaCache.SENDER = req -> { };      // 续传请求不需要真的发出去

        // ---- ② 分片喂进去（模拟服务端下发）：堆占用必须恒为 0 ----
        // 真实协议是「请求一片 → 服务端回一片 → 再要下一片」，所以这里一片一片喂，
        // 每片之间让出 CPU 给媒体工作线程（否则尾片可能先被处理，读到带空洞的文件）。
        final int chunk = 200 * 1024;
        long maxBuffered = 0;
        for (int off = 0; off < total; off += chunk) {
            int len = Math.min(chunk, total - off);
            byte[] part = java.util.Arrays.copyOfRange(whole, off, off + len);
            boolean last = off + len >= total;
            MediaCache.onMediaChunk(hash, MediaCache.WHOLE, off, total, true, part, last, false);
            maxBuffered = Math.max(maxBuffered, MediaCache.bufferedDownloadBytesForTest());
            Thread.sleep(5L);
        }
        check("整段下载全程：堆里的下载缓冲恒为 0（旧实现这里会是 8 MB）",
                maxBuffered == 0, "实得最大 " + maxBuffered + " 字节");

        // ---- ③ 收齐后必须落盘到 cache/<hash>.bin，且内容逐字节一致 ----
        Path cached = LocalMedia.cacheDir().resolve(hash + ".bin");
        boolean ok = false;
        for (int i = 0; i < 100 && !ok; i++) {
            ok = Files.isRegularFile(cached) && Files.size(cached) == total;
            if (!ok) Thread.sleep(100L);
        }
        check("整段文件落盘为 cache/<hash>.bin", ok,
                ok ? "" : "没落盘（10 秒内没出现）");
        if (ok) {
            byte[] back = Files.readAllBytes(cached);
            check("落盘内容与收到的分片逐字节一致",
                    java.util.Arrays.equals(back, whole), "大小=" + back.length);
            check("校验通过：落盘文件的 SHA-1 等于哈希",
                    hash.equalsIgnoreCase(LocalMedia.sha1File(cached)), "");
            check("没有残留 .part 文件",
                    !Files.exists(LocalMedia.cacheDir().resolve(hash + ".part")), "");
            check("下载登记已结算（不再有在途）",
                    !MediaCache.downloadActiveForTest(hash, MediaCache.WHOLE), "");
        }

        // ---- ④ 放宽后的上限：整段文件按「磁盘保护上限」而不是旧的 64 MB 内存上限 ----
        String bigHash = sha1("declared-70MB".getBytes());
        MediaCache.onMediaChunk(bigHash, MediaCache.WHOLE, 0, 70 * 1024 * 1024, true,
                new byte[1024], false, false);
        boolean active = false;
        for (int i = 0; i < 40 && !active; i++) {
            active = MediaCache.downloadActiveForTest(bigHash, MediaCache.WHOLE);
            if (!active) Thread.sleep(50L);
        }
        check("整段文件不再被 64MB 上限拒收（改由磁盘上限保护）",
                active, "被拒了 ⇒ 还是旧的内存上限");
        String hugeHash = sha1("declared-600MB".getBytes());
        MediaCache.onMediaChunk(hugeHash, MediaCache.WHOLE, 0, 600 * 1024 * 1024, true,
                new byte[1024], false, false);
        boolean neverActive = true;
        for (int i = 0; i < 20 && neverActive; i++) {
            Thread.sleep(50L);
            neverActive = !MediaCache.downloadActiveForTest(hugeHash, MediaCache.WHOLE);
        }
        check("超过磁盘保护上限（512MB）仍然拒收", neverActive, "居然被接受了");

        // ---- ⑤ 帧槽位分摊：纯逻辑（不依赖 GL） ----
        long slot = 548L * 308 * 4;                     // 一个 548x308 的槽位 ≈ 0.64 MB
        check("单视频、预算充裕：仍用配置里的 12 个槽位",
                MediaCache.slotsAllowedForMedia(slot, 1, 64L * 1024 * 1024, 12) == 12, "");
        int share16 = MediaCache.slotsAllowedForMedia(slot, 16, 64L * 1024 * 1024, 12);
        check("同时 16 个视频：按预算分摊后不再是 12 个槽位（" + share16 + " 个）",
                share16 < 12 && share16 >= MediaCache.MIN_SLOTS_PER_MEDIA, "实得 " + share16);
        int big4k = MediaCache.slotsAllowedForMedia(1920L * 1080 * 4, 16, 64L * 1024 * 1024, 12);
        check("4K 帧 + 8 个视频：落到下限（至少还能播）",
                big4k == MediaCache.MIN_SLOTS_PER_MEDIA, "实得 " + big4k);
        check("预算为 0 / 槽位未知时不乱缩（回退配置值）",
                MediaCache.slotsAllowedForMedia(0, 3, 64L * 1024 * 1024, 12) == 12
                        && MediaCache.slotsAllowedForMedia(slot, 3, 0, 12) == 12, "");
        check("预算越大、分到的槽位越多（单调性）",
                MediaCache.slotsAllowedForMedia(slot, 4, 128L * 1024 * 1024, 48)
                        >= MediaCache.slotsAllowedForMedia(slot, 4, 32L * 1024 * 1024, 48), "");
        check("任何情况下都不会低于下限 4 个槽位",
                MediaCache.slotsAllowedForMedia(4096L * 4096 * 4, 64, 8L * 1024 * 1024, 12)
                        >= MediaCache.MIN_SLOTS_PER_MEDIA, "");

        // ---- ⑥ 内存报表：必须给出「图片/槽位/下载缓冲/预算」四项数字 ----
        String rep = MediaCache.memoryReport();
        check("内存报表含四项拆分与预算", rep.contains("图片=") && rep.contains("视频槽=")
                && rep.contains("下载缓冲=") && rep.contains("预算"), rep);
        check("空载时槽位数为 0 个", rep.contains("视频槽=0个"), rep);

        // ---- ⑦ 源码级不变量：不再保留原始字节 / 有流式写盘 / 上限常量已换 ----
        String mc = read("src/main/java/top/hmjmfabc/projector/client/media/MediaCache.java");
        check("能读到 MediaCache 源码", mc != null, "");
        check("Entry 不再保留原始文件字节（没有 byte[] raw 字段）",
                mc != null && !mc.contains("public final byte[] raw;"), "");
        check("上传图片不再把原始字节存进表里",
                mc != null && !mc.contains("uploadImage(hash, sized, data)"), "");
        check("存在整段流式写盘（handleWholeToDisk + .part）",
                mc != null && mc.contains("handleWholeToDisk(") && mc.contains("\".part\""), "");
        check("整段文件的磁盘保护上限是 512MB（不再是 64MB 内存上限）",
                mc != null && mc.contains("DISK_DOWNLOAD_MAX = 512L * 1024 * 1024"), "");
        check("落盘前要求「真的收齐」（防止 last 提前到达读到带空洞的文件）",
                mc != null && mc.contains("dl.receivedSum < total"), "");
        check("有跨媒体的槽位总预算", mc != null && mc.contains("slotBudgetBytes()"), "");
        String cfg = read("src/main/java/top/hmjmfabc/projector/ProjectorConfig.java");
        check("新增配置项 video.maxFrameMemoryMb（默认 64MB，8~1024）",
                cfg != null && cfg.contains("\"videoMaxFrameMemoryMb\", 64, 8, 1024"), "");

        System.out.println();
        System.out.println("== " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " ==  " + pass + " passed");
        LocalMedia.ROOT_FOR_TEST = null;
        if (fail > 0) System.exit(1);
    }

    private static String sha1(byte[] d) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest(d)) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String read(String rel) {
        try {
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

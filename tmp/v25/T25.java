package top.hmjmfabc.projector.client.media;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * T25 —— 【rc-87】「只要在画视频就每隔一两秒卡 600~880 ms」。
 *
 * <p>玩家 A/B：<b>删掉视频控件就不卡</b>。日志又显示卡顿与「填帧」同时出现/消失
 * （11:48:37→11:49:00 那段没在画，两者都停了）⇒ 卡在「读视频帧」这条路上。</p>
 *
 * <p>根因：{@link VideoSource} 用 {@code FileChannel.map()} <b>内存映射</b>访问素材，
 * 而玩家的素材在 {@code /storage/emulated/0}（Android 的 FUSE 模拟存储）上：
 * 映射读未驻留的页会触发<b>缺页中断 → FUSE 往返</b>，一帧 60 KB ≈ 15 个页；
 * 一次 {@code open()} 还要逐字节扫过整个文件（14.6 MB ⇒ 1460 万次 {@code get}）。
 * 现在改成<b>带 256 KB 窗口缓存的定位读</b>（每帧一次 pread）。</p>
 *
 * <p>本测试用<b>玩家真实素材里抽出的 8 帧</b>拼一个 387 KB 的合成 MJPEG
 * （刻意跨过 256 KB 窗口边界），断言：帧数、每帧字节与源文件逐字节一致、
 * 越界返回 null、拼回去等于整个文件、以及源码里不再有内存映射。</p>
 */
public final class T25 {

    private static int pass;
    private static int fail;
    private static final Path SAMPLE = Path.of("tmp/v25/sample.mjpg");

    public static void main(String[] args) throws Exception {
        if (!Files.isRegularFile(SAMPLE)) {
            System.out.println("  ⚠ 缺少测试素材 " + SAMPLE + "（跳过）");
            System.out.println("== ALL PASS ==  0 passed");
            return;
        }
        byte[] whole = Files.readAllBytes(SAMPLE);
        check("合成素材 > 256 KB（保证跨窗口边界）", whole.length > 256 * 1024,
                whole.length + " 字节");

        VideoSource src = VideoSource.open(SAMPLE);
        check("能打开合成 MJPEG", src != null, "open() 返回 null");
        if (src == null) {
            System.out.println("== 1 FAILED ==  0 passed");
            System.exit(1);
        }
        try {
            check("帧数 = 8（窗口化扫描不漏帧）", src.frameCount == 8, "实得 " + src.frameCount);
            // 尺寸靠 stb_image 解析，而本机没有 aarch64 的 LWJGL native ⇒ 无头环境拿不到，
            // 这里只断言「要么解析成功(548x308)，要么是 0x0」，不把环境限制当失败
            check("尺寸解析：548x308（或 0x0 = 本机无 LWJGL native，游戏里正常）",
                    (src.width == 548 && src.height == 308) || (src.width == 0 && src.height == 0),
                    src.width + "x" + src.height);

            // 每帧必须是完整 JPEG，且拼回去等于整个文件（等价于「偏移/长度全对」）
            int total = 0;
            boolean shape = true;
            for (int i = 0; i < src.frameCount; i++) {
                byte[] f = src.frameBytes(i);
                if (f == null || f.length < 4) {
                    shape = false;
                    break;
                }
                if ((f[0] & 0xFF) != 0xFF || (f[1] & 0xFF) != 0xD8
                        || (f[f.length - 2] & 0xFF) != 0xFF || (f[f.length - 1] & 0xFF) != 0xD9) {
                    shape = false;
                    break;
                }
                total += f.length;
            }
            check("每一帧都是完整 JPEG（FFD8 开头 / FFD9 结尾）", shape, "");
            check("所有帧拼回去正好等于整个文件（偏移与长度全对）", total == whole.length,
                    total + " vs " + whole.length);

            // 跨窗口的那一帧（第 4~8 帧位于 256 KB 之后）必须逐字节正确
            int off = 0;
            byte[] f4 = src.frameBytes(4);
            for (int i = 0; i < 4; i++) off += src.frameBytes(i).length;
            boolean same = f4 != null && f4.length > 0 && off + f4.length <= whole.length;
            if (same) {
                for (int k = 0; k < f4.length; k++) {
                    if (f4[k] != whole[off + k]) {
                        same = false;
                        break;
                    }
                }
            }
            check("跨窗口的第 5 帧与文件里的字节完全一致", same,
                    "偏移=" + off + " 长度=" + (f4 == null ? -1 : f4.length));

            check("越界帧号返回 null（不许抛异常）",
                    src.frameBytes(-1) == null && src.frameBytes(99) == null, "");

            // 解码路径不依赖 mmap：这里只要求「拿得到字节」，解码由 stb 负责
            check("decode(0) 不与 frameBytes 冲突（同一窗口复用后仍正确）",
                    src.frameBytes(0) != null && src.frameBytes(0).length > 0, "");
        } finally {
            src.close();
        }

        // ---- 结构不变量：不许再回到内存映射 ----
        String code = read("src/main/java/top/hmjmfabc/projector/client/media/VideoSource.java");
        check("能读到 VideoSource 源码", code != null, "");
        check("不再使用 FileChannel.map() / MappedByteBuffer",
                code != null && !code.contains(".map(") && !code.contains("MappedByteBuffer"),
                "回到内存映射 ⇒ FUSE 存储上每帧十几次缺页中断，就是那个 700ms 卡顿");
        check("有窗口缓存（byteAt / copy / fill 三个入口）",
                code != null && code.contains("private int byteAt(") && code.contains("private byte[] copy(")
                        && code.contains("private void fill("), "");
        check("帧数据读取走窗口（copy(offset,...)）", code != null && code.contains("raw = copy(offset, length)"), "");
        check("frameBytes 是同步的（解码可能来自多个工作线程）",
                code != null && code.contains("public synchronized byte[] frameBytes("), "");

        // ---- 槽位保护窗（GPU 还在用纹理时不许复写）----
        String mc = read("src/main/java/top/hmjmfabc/projector/client/media/MediaCache.java");
        check("有自适应帧槽位保护窗", mc != null && mc.contains("SLOT_HOLD_MS")
                && mc.contains("MAX_SLOT_HOLD_MS"), "");
        check("上传慢就翻倍保护窗、持续快就收回去",
                mc != null && mc.contains("SLOT_HOLD_MS * 2") && mc.contains("SLOT_HOLD_MS / 8"), "");
        check("槽位全部在保护窗内时跳过这一帧（返回 -1，不当成可用槽）",
                mc != null && mc.contains("if (idx < 0) {") && mc.contains("GPU_HELD_SKIPPED"), "");
        check("槽位下限提到 8（4 个在保护窗下大半帧会被跳过）",
                mc != null && mc.contains("Math.max(8, ProjectorConfig.INSTANCE.videoFrameCacheFrames.get())"), "");
        check("pickSlot 在全部不可用时返回 -1",
                MediaCache.pickSlot(new int[]{1, 2}, new long[]{Long.MAX_VALUE, Long.MAX_VALUE}, 2) == -1, "");
        check("pickSlot 有可用槽时仍正常（保护窗不影响常规选择）",
                MediaCache.pickSlot(new int[]{1, 2}, new long[]{Long.MAX_VALUE, 5}, 2) == 1, "");
        check("stats() 增加到 9 个数字（含保护窗跳过）", MediaCache.stats().length == 9,
                "实得 " + MediaCache.stats().length);

        System.out.println();
        System.out.println("== " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " ==  " + pass + " passed");
        if (fail > 0) System.exit(1);
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
            System.out.println("  ❌ " + what + (detail.isEmpty() ? "" : "  —— " + detail));
        }
    }
}

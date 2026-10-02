package top.hmjmfabc.projector.client.media;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 【27.1.3】客户端磁盘缓存清理的验证（T32）——重点是**「先清视频」这条顺序**。
 *
 * <p>顺序只有在**超上限**时才看得出来（默认上限 2 GB，测试造不出那么大的文件），
 * 所以这里用 {@code CacheCleaner.CAP_FOR_TEST} 把上限压到几 MB，
 * 再摆一份「1 个大视频帧 + 1 张大图」：**必须删视频、留图片**才算过
 * （如果实现改成先删图片，这条就会红）。</p>
 *
 * <p>另外验证：按天数过期时视频先走；{@code .part} 半成品十分钟内不许删、超时当垃圾；
 * 没超上限时什么都不删。</p>
 */
public class T32 {
    private static int passed;
    private static final List<String> failed = new ArrayList<>();

    public static void main(String[] args) {
        Path root;
        try {
            root = Files.createTempDirectory("projector-t32-");
        } catch (Exception e) {
            System.out.println("== FAILED ==  无法建临时目录：" + e);
            System.exit(1);
            return;
        }
        LocalMedia.ROOT_FOR_TEST = root;
        try {
            Path cache = Files.createDirectories(root.resolve("cache"));

            // ---- ① 超上限：视频先走、图片留下 ----
            Path bigFrame = write(cache.resolve("aaaa1111_f0.bin"), 3 * 1024 * 1024);
            Path bigImage = write(cache.resolve("bbbb2222.bin"), 1 * 1024 * 1024);
            Path freshFrame = write(cache.resolve("cccc3333_f0.bin"), 256 * 1024);
            //  上限 2 MB：总 4.25 MB 超标；删掉 3 MB 那个视频帧就够了 ⇒ 图片必须还在
            CacheCleaner.CAP_FOR_TEST = 2L * 1024 * 1024;
            CacheCleaner.sweep();
            check("超上限时**先删视频**（3MB 的视频帧被删）", !Files.exists(bigFrame));
            check("超上限时**图片留下**（1MB 的图片还在）", Files.exists(bigImage));
            check("没超上限的其它文件不动（256KB 的视频帧还在）", Files.exists(freshFrame));

            // ---- ② 按天数过期：视频先走，图片随后 ----
            CacheCleaner.CAP_FOR_TEST = 0L;                     // 0 = 不限，只走天数这条路
            Path oldVideo = write(cache.resolve("dddd4444_f3.bin"), 64 * 1024);
            Path oldImage = write(cache.resolve("eeee5555.bin"), 64 * 1024);
            FileTime old = FileTime.fromMillis(System.currentTimeMillis() - 40L * 86_400_000L);
            Files.setLastModifiedTime(oldVideo, old);
            Files.setLastModifiedTime(oldImage, old);
            CacheCleaner.sweep();
            check("超过保留天数的视频被清掉", !Files.exists(oldVideo));
            check("超过保留天数的图片也被清掉（清完视频才轮到它）", !Files.exists(oldImage));
            check("新文件仍不动", Files.exists(freshFrame));

            // ---- ③ 半成品：十分钟内别碰，过期的当垃圾 ----
            Path freshPart = write(cache.resolve("11112222.part"), 4096);
            Path stalePart = write(cache.resolve("33334444.part"), 4096);
            Files.setLastModifiedTime(stalePart,
                    FileTime.fromMillis(System.currentTimeMillis() - 3600_000L));
            CacheCleaner.sweep();
            check("在途的半成品（刚建的 .part）不删", Files.exists(freshPart));
            check("过期的半成品（1 小时前）清掉", !Files.exists(stalePart));

            // ---- ④ 上限为 0 = 不限：什么都不删 ----
            CacheCleaner.CAP_FOR_TEST = 0L;
            Path keep = write(cache.resolve("ffff6666_f0.bin"), 128 * 1024);
            CacheCleaner.sweep();
            check("上限=0 时不按大小清理", Files.exists(keep));
        } catch (Throwable t) {
            check("测试自身没崩（" + t + "）", false);
        } finally {
            CacheCleaner.CAP_FOR_TEST = null;
            LocalMedia.ROOT_FOR_TEST = null;
            deleteTree(root);
        }

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

    // ------------------------------------------------------------------ 工具

    private static Path write(Path p, int bytes) throws Exception {
        Files.write(p, new byte[bytes]);
        return p;
    }

    private static void deleteTree(Path root) {
        try (var s = Files.walk(root)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // 清不掉就算了
                }
            });
        } catch (Exception ignored) {
            // 同上
        }
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  \u2705 " + name);
        } else {
            failed.add(name);
        }
    }
}

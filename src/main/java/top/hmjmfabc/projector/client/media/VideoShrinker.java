package top.hmjmfabc.projector.client.media;

import org.jetbrains.annotations.Nullable;
import top.hmjmfabc.projector.Projector;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 【②】把「超过自动压缩阈值」的视频压到目标大小。
 *
 * <p>用户要求：视频超过 32 MB 时上传前自动压画质到 32 MB（除非服务端启用了
 * 原画上传且客户端勾选了它）。</p>
 *
 * <h2>为什么是「抽帧」而不是重新编码</h2>
 * <p>本模组的视频只有两种形态：<b>MJPEG 流</b>（一串首尾相接的 JPEG）和
 * <b>ZIP 帧序列</b>（一堆编号 JPEG 打包）。两者都是<b>独立的完整帧</b>，
 * 所以「压画质」最划算的做法是<b>按帧抽稀</b>：保留每第 N 帧、丢掉中间的帧。</p>
 * <ul>
 *   <li>不需要解码／编码，CPU 几乎为零，Android 上也能秒完成；</li>
 *   <li>留下来的每一帧都是<b>原始画质</b>，不像重新编码那样二次损失；</li>
 *   <li>副作用只是帧率降低（视频变「跳」一点），这是省流量的合理代价。</li>
 * </ul>
 * <p>如果抽到只剩很少的帧还是超标（例如每一帧都巨大），就继续加大抽稀步长，
 * 最多试 {@link #MAX_ATTEMPTS} 轮；实在压不下去就返回失败，让调用方给出提示。</p>
 */
public final class VideoShrinker {

    /** 最多尝试几轮抽稀（步长递增）。 */
    private static final int MAX_ATTEMPTS = 5;

    private VideoShrinker() {
    }

    /** 压缩结果。 */
    public record Result(boolean ok, Path output, long size, int step, String message) {
    }

    /**
     * 把 {@code src} 抽帧压到不超过 {@code targetBytes}，写到 {@code dst}。
     *
     * @param targetBytes 目标字节数（0 或负数 = 不限制，直接复制）
     */
    public static Result shrink(Path src, Path dst, long targetBytes) {
        try {
            long size = Files.size(src);
            if (size <= 0) {
                return new Result(false, null, 0, 1, "\u6e90\u6587\u4ef6\u4e3a\u7a7a");
            }
            if (targetBytes <= 0 || size <= targetBytes) {
                Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return new Result(true, dst, Files.size(dst), 1, "\u65e0\u9700\u538b\u7f29");
            }
            String lower = src.getFileName().toString().toLowerCase(Locale.ROOT);
            boolean zip = lower.endsWith(".zip");

            // 首轮步长按体积比估算，再逐轮加倍
            int step = (int) Math.max(2L, Math.ceil(size / (double) targetBytes));
            long best = Long.MAX_VALUE;
            Path bestFile = null;
            int bestStep = step;
            for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                long written = zip ? shrinkZip(src, dst, step) : shrinkMjpeg(src, dst, step);
                if (written > 0 && written < best) {
                    best = written;
                    bestFile = dst;
                    bestStep = step;
                }
                if (written > 0 && written <= targetBytes) {
                    return new Result(true, dst, written, step,
                            "\u5df2\u81ea\u52a8\u538b\u7f29\u5230 " + mb(written)
                                    + " MB\uff08\u4fdd\u7559\u6bcf\u7b2c " + step + " \u5e27\uff09");
                }
                step *= 2;
            }
            if (bestFile != null) {
                // 压不到目标也至少把最小的一版留下，让调用方决定是否接受
                return new Result(best <= targetBytes, bestFile, best, bestStep,
                        "\u538b\u7f29\u540e\u4ecd\u4e3a " + mb(best) + " MB\uff08\u76ee\u6807 "
                                + mb(targetBytes) + " MB\uff09");
            }
            return new Result(false, null, 0, 1, "\u538b\u7f29\u5931\u8d25\uff08\u65e0\u6cd5\u8bc6\u522b\u7684\u89c6\u9891\u683c\u5f0f\uff09");
        } catch (Throwable t) {
            Projector.LOGGER.warn("[Projector] 视频压缩失败：{}", t.toString());
            return new Result(false, null, 0, 1, "\u538b\u7f29\u5931\u8d25\uff1a" + t.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // MJPEG：按帧表复制字节区间
    // ------------------------------------------------------------------

    /** 返回写出的字节数；失败返回 -1。 */
    private static long shrinkMjpeg(Path src, Path dst, int step) throws IOException {
        VideoSource vs = VideoSource.open(src);
        if (vs == null) return -1;
        long[] table;
        try {
            table = vs.exportFrameTable();
        } finally {
            vs.close();
        }
        if (table == null || table.length < 2) return -1;
        Path tmp = dst.resolveSibling(dst.getFileName() + ".part");
        long written = 0;
        try (RandomAccessFile in = new RandomAccessFile(src.toFile(), "r");
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(tmp), 256 * 1024)) {
            byte[] buf = new byte[256 * 1024];
            for (int i = 0; i + 1 < table.length; i += 2 * step) {
                long off = table[i];
                long len = table[i + 1];
                if (off < 0 || len <= 0) continue;
                in.seek(off);
                long remain = len;
                while (remain > 0) {
                    int n = (int) Math.min(buf.length, remain);
                    int read = in.read(buf, 0, n);
                    if (read <= 0) break;
                    out.write(buf, 0, read);
                    remain -= read;
                    written += read;
                }
            }
        }
        if (written <= 0) {
            Files.deleteIfExists(tmp);
            return -1;
        }
        Files.move(tmp, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return written;
    }

    // ------------------------------------------------------------------
    // ZIP 帧序列：保留每第 N 个条目，用 STORED 重新打包
    // ------------------------------------------------------------------

    private static long shrinkZip(Path src, Path dst, int step) throws IOException {
        List<ZipEntry> entries = new ArrayList<>();
        try (ZipFile zf = new ZipFile(src.toFile())) {
            var en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String n = e.getName().toLowerCase(Locale.ROOT);
                if (!n.endsWith(".jpg") && !n.endsWith(".jpeg") && !n.endsWith(".png")) continue;
                entries.add(e);
            }
            if (entries.isEmpty()) return -1;
            // 按条目名排序，保证抽帧顺序与播放顺序一致
            entries.sort(Comparator.comparing(ZipEntry::getName));
            Path tmp = dst.resolveSibling(dst.getFileName() + ".part");
            long written = 0;
            try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(
                    Files.newOutputStream(tmp), 256 * 1024))) {
                byte[] buf = new byte[256 * 1024];
                for (int i = 0; i < entries.size(); i += step) {
                    ZipEntry e = entries.get(i);
                    ZipEntry out = new ZipEntry(e.getName());
                    out.setMethod(ZipEntry.STORED);
                    out.setSize(e.getSize());
                    out.setCompressedSize(e.getSize());
                    out.setCrc(e.getCrc());
                    zos.putNextEntry(out);
                    try (InputStream in = zf.getInputStream(e)) {
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            zos.write(buf, 0, n);
                            written += n;
                        }
                    }
                    zos.closeEntry();
                }
            }
            if (written <= 0) {
                Files.deleteIfExists(tmp);
                return -1;
            }
            Files.move(tmp, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return written;
        }
    }

    private static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / 1048576.0);
    }

    /**
     * 目标压缩文件路径。
     *
     * <p><b>刻意放到 cache 目录而不是素材目录旁边：</b>素材目录是玩家自己管理的
     * （「我放了哪些图片/视频」），在里面凭空多出 {@code xxx.compressed.mjpg}
     * 既让人困惑，又会被下次的媒体列表扫出来。cache 目录本来就是我们自己的
     * 临时区（还有一个 {@code .nomedia} 让文件管理器跳过它）。</p>
     */
    @Nullable
    public static Path compressedPath(Path src) {
        try {
            String name = src.getFileName().toString();
            int dot = name.lastIndexOf('.');
            String stem = dot > 0 ? name.substring(0, dot) : name;
            String ext = dot > 0 ? name.substring(dot) : ".mjpg";
            Path dir = LocalMedia.cacheDir();
            Files.createDirectories(dir);
            return dir.resolve(stem + ".compressed" + ext);
        } catch (Throwable t) {
            // 实在建不出目录就退回原目录旁边，总比直接失败好
            String name = src.getFileName().toString();
            int dot = name.lastIndexOf('.');
            String stem = dot > 0 ? name.substring(0, dot) : name;
            String ext = dot > 0 ? name.substring(dot) : ".mjpg";
            return src.resolveSibling(stem + ".compressed" + ext);
        }
    }
}

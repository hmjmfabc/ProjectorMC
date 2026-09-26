package top.hmjmfabc.projector.client.media;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 视频源：对 MJPEG 与 ZIP 帧序列提供统一接口。
 *
 * <p>访问方式是<b>带窗口缓存的定位读</b>：一次读 {@link #WINDOW} 字节并复用该缓冲，
 * 堆占用恒定为 256 KB，顺序扫描与逐帧取数都走它。</p>
 */
public final class VideoSource implements AutoCloseable {

    /** 视频类型。 */
    public enum Kind {
        /** 逐帧 JPEG（MJPEG）。 */
        MJPEG,
        /** ZIP 内的图片序列。 */
        ZIP
    }

    public final Kind kind;
    public final int frameCount;
    public final int width;
    public final int height;
    /** 每帧 [offset, length]；ZIP 情况下为 [数据偏移, 压缩长度]，method 列表另存。 */
    private final List<long[]> ranges = new ArrayList<>();
    private final List<Integer> methods = new ArrayList<>();
    /** 读窗口大小：顺序扫描与逐帧取数都走它，堆占用恒定 256 KB。 */
    private static final int WINDOW = 256 * 1024;

    private final FileChannel channel;
    private final long fileSize;
    /** 窗口缓存（堆内，可复用）。decode 可能被多个工作线程调用，读入口统一同步。 */
    private final ByteBuffer window = ByteBuffer.allocate(WINDOW);
    private long windowStart = -1L;
    private int windowLen;

    private VideoSource(Kind kind, FileChannel channel, int fileSize) throws IOException {
        this.kind = kind;
        this.channel = channel;
        this.fileSize = fileSize;
        int[] wh = {0, 0};
        int frames = kind == Kind.MJPEG ? scanMjpeg(wh) : scanZip(wh);
        this.frameCount = Math.max(1, frames);
        this.width = wh[0];
        this.height = wh[1];
    }

    // ------------------------------------------------------------------
    // 窗口化随机访问：唯一的读文件入口
    // ------------------------------------------------------------------

    /** 取 {@code pos} 处的一个字节；必要时换窗。文件尾返回 -1。 */
    private int byteAt(long pos) throws IOException {
        if (pos < 0 || pos >= fileSize) return -1;
        if (windowStart < 0 || pos < windowStart || pos >= windowStart + windowLen) {
            fill(pos);
            if (windowLen <= 0) return -1;
        }
        return window.get((int) (pos - windowStart)) & 0xFF;
    }

    /** 从 {@code pos} 起读 {@code n} 字节到新数组（跨窗口也正确）。 */
    private byte[] copy(long pos, int n) throws IOException {
        byte[] out = new byte[Math.max(0, n)];
        int done = 0;
        long p = pos;
        while (done < out.length) {
            if (windowStart < 0 || p < windowStart || p >= windowStart + windowLen) {
                fill(p);
                if (windowLen <= 0) break;
            }
            int off = (int) (p - windowStart);
            int take = Math.min(out.length - done, windowLen - off);
            window.position(off);
            window.get(out, done, take);
            done += take;
            p += take;
        }
        return out;
    }

    /** 以 {@code pos} 为起点填窗口（一次 pread；顺序扫描时命中率很高）。 */
    private void fill(long pos) throws IOException {
        window.clear();
        windowStart = pos;
        windowLen = 0;
        long p = pos;
        while (window.hasRemaining()) {
            int n = channel.read(window, p);
            if (n < 0) break;
            windowLen += n;
            p += n;
            if (windowLen >= WINDOW) break;
        }
    }

    /**
     * 打开视频文件。返回 null 表示无法识别（例如真正的 mp4/h264）。
     *
     * <p>【rc-87 关键修复】<b>不再使用 {@code FileChannel.map()} 内存映射。</b>
     * 玩家的素材放在 {@code /storage/emulated/0}（Android 的 FUSE 模拟存储）上，
     * 而内存映射读一个还没驻留的页会触发<b>缺页中断 → FUSE 往返</b>：
     * 一帧 60 KB ≈ 15 个页 ⇒ 每帧十几次往返；再加上一次 {@code open()} 要逐字节扫过
     * 整个 14.6 MB 文件。表现就是「只要在画视频就隔一两秒卡一下」（玩家实测 600~880 ms），
     * 而**删掉视频控件就不卡**（A/B 已确认），且与上传带宽无关（一帧只有 0.64 MB）。
     * 现在改成 {@code FileChannel.read(ByteBuffer, position)} 定位读：
     * 每帧一次 pread 就够，扫描也用顺序读（一次 64 KB）。</p>
     */
    @Nullable
    public static VideoSource open(Path path) {
        try {
            FileChannel ch = FileChannel.open(path, StandardOpenOption.READ);
            long size = ch.size();
            if (size <= 4 || size > Integer.MAX_VALUE) {
                ch.close();
                return null;
            }
            Kind kind = detect(ch, size);
            if (kind == null) {
                ch.close();
                return null;
            }
            VideoSource src = new VideoSource(kind, ch, (int) size);
            if (src.frameCount <= 1 && src.width <= 0) {
                src.close();
                return null;
            }
            return src;
        } catch (IOException ex) {
            return null;
        }
    }

    private static Kind detect(FileChannel ch, long size) throws IOException {
        int n = (int) Math.min(size, 4096);
        ByteBuffer head = ByteBuffer.allocate(n);
        readFully(ch, head, 0);
        byte[] b = head.array();
        for (int i = 0; i + 3 < n; i++) {
            int b0 = b[i] & 0xFF;
            int b1 = b[i + 1] & 0xFF;
            if (b0 == 0xFF && b1 == 0xD8) {
                return Kind.MJPEG;
            }
            if (b0 == 0x50 && b1 == 0x4B && (b[i + 2] & 0xFF) == 0x03 && (b[i + 3] & 0xFF) == 0x04) {
                return Kind.ZIP;
            }
        }
        return null;
    }

    /** 从 {@code position} 处读满 {@code buf}（FileChannel 的 read 可能短读）。 */
    private static void readFully(FileChannel ch, ByteBuffer buf, long position) throws IOException {
        long pos = position;
        while (buf.hasRemaining()) {
            int n = ch.read(buf, pos);
            if (n < 0) throw new IOException("文件提前结束");
            pos += n;
        }
        buf.flip();
    }

    private int scanMjpeg(int[] wh) throws IOException {
        long n = fileSize;
        int count = 0;
        // 【rc-87】必须从 0 开始扫：以前从 1 开始，于是**第一帧永远被丢掉**
        //（玩家素材的第一帧紧跟在文件开头；被 T25 的「拼回去等于整个文件」断言抓到）。
        long i = 0;
        boolean sizeRead = false;
        while (i + 1 < n) {
            if (byteAt(i) == 0xFF && byteAt(i + 1) == 0xD8) {
                long start = i;
                long j = i + 2;
                long end = -1;
                while (j + 1 < n) {
                    if (byteAt(j) == 0xFF && byteAt(j + 1) == 0xD9) {
                        end = j + 2;
                        break;
                    }
                    j++;
                }
                if (end < 0) break;
                ranges.add(new long[]{start, end - start});
                count++;
                if (!sizeRead) {
                    int[] s = imageSizeAt((int) start, (int) (end - start));
                    if (s != null) {
                        wh[0] = s[0];
                        wh[1] = s[1];
                        sizeRead = true;
                    }
                }
                i = end;
            } else {
                i++;
            }
        }
        return count;
    }

    private int scanZip(int[] wh) throws IOException {
        long n = fileSize;
        long i = 0;
        boolean sizeRead = false;
        List<String> names = new ArrayList<>();
        List<long[]> raw = new ArrayList<>();
        List<Integer> meth = new ArrayList<>();
        while (i + 30 < n) {
            if (byteAt(i) != 0x50 || byteAt(i + 1) != 0x4B
                    || byteAt(i + 2) != 0x03 || byteAt(i + 3) != 0x04) {
                i++;
                continue;
            }
            int method = byteAt(i + 8) | (byteAt(i + 9) << 8);
            long compSize = byteAt(i + 18) | ((long) byteAt(i + 19) << 8)
                    | ((long) byteAt(i + 20) << 16) | ((long) byteAt(i + 21) << 24);
            int nameLen = byteAt(i + 26) | (byteAt(i + 27) << 8);
            int extraLen = byteAt(i + 28) | (byteAt(i + 29) << 8);
            if (nameLen <= 0 || i + 30 + nameLen > n) break;
            byte[] nb = copy(i + 30, nameLen);
            String name = new String(nb, java.nio.charset.StandardCharsets.UTF_8);
            long dataStart = i + 30 + nameLen + extraLen;
            if (compSize <= 0 || dataStart + compSize > n) {
                i = dataStart;
                continue;
            }
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".bmp")) {
                names.add(name);
                raw.add(new long[]{dataStart, compSize});
                meth.add(method);
                if (!sizeRead) {
                    byte[] frame = extract((int) dataStart, (int) compSize, method);
                    if (frame != null) {
                        int[] s = ImageCodec.size(frame, 0, frame.length);
                        if (s != null) {
                            wh[0] = s[0];
                            wh[1] = s[1];
                            sizeRead = true;
                        }
                    }
                }
            }
            i = (int) (dataStart + compSize);
        }
        Integer[] order = new Integer[names.size()];
        for (int k = 0; k < order.length; k++) order[k] = k;
        Arrays.sort(order, (a, b) -> naturalCompare(names.get(a), names.get(b)));
        for (int k : order) {
            ranges.add(raw.get(k));
            methods.add(meth.get(k));
        }
        return ranges.size();
    }

    @Nullable
    private int[] imageSizeAt(int offset, int length) {
        try {
            byte[] frame = copy(offset, length);
            return ImageCodec.size(frame, 0, frame.length);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 取出某一帧的原始图片字节（MJPEG 直接切片，ZIP 需要解压）。 */
    @Nullable
    public synchronized byte[] frameBytes(int frame) {
        if (frame < 0 || frame >= ranges.size()) return null;
        long[] r = ranges.get(frame);
        int method = kind == Kind.ZIP ? methods.get(frame) : 0;
        return extract((int) r[0], (int) r[1], method);
    }

    private byte[] extract(int offset, int length, int method) {
        byte[] raw;
        try {
            raw = copy(offset, length);
        } catch (IOException ex) {
            return null;
        }
        if (method == 0) {
            return raw;
        }
        try (java.util.zip.InflaterInputStream in = new java.util.zip.InflaterInputStream(
                new java.io.ByteArrayInputStream(raw))) {
            return in.readAllBytes();
        } catch (Exception ex) {
            return null;
        }
    }

    /** 解压/解码出某一帧的 RGBA 像素。 */
    @Nullable
    public ImageCodec.Decoded decode(int frame) {
        byte[] bytes = frameBytes(frame);
        if (bytes == null) return null;
        return ImageCodec.decode(bytes, 0, bytes.length);
    }

    @Override
    public void close() {
        try {
            channel.close();
        } catch (IOException ignored) {
        }
    }

    /** 导出帧表：[偏移, 长度] 依次排列，用于上传给服务端做随机访问。 */
    public long[] exportFrameTable() {
        long[] out = new long[ranges.size() * 2];
        for (int i = 0; i < ranges.size(); i++) {
            out[i * 2] = ranges.get(i)[0];
            out[i * 2 + 1] = ranges.get(i)[1];
        }
        return out;
    }

    /** 自然序比较：frame2 排在 frame10 之前。 */
    public static int naturalCompare(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (si < a.length() && Character.isDigit(a.charAt(si))) si++;
                while (sj < b.length() && Character.isDigit(b.charAt(sj))) sj++;
                long va = Long.parseLong(a.substring(i, si));
                long vb = Long.parseLong(b.substring(j, sj));
                if (va != vb) return Long.compare(va, vb);
                i = si;
                j = sj;
            } else {
                if (ca != cb) return Character.compare(ca, cb);
                i++;
                j++;
            }
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }
}

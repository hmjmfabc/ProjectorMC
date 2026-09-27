package top.hmjmfabc.projector.client.media;

import org.jetbrains.annotations.Nullable;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 「只看文件头」的视频尺寸探测（27.1.2）。
 *
 * <p>为什么需要它：交给外部解码器播放的格式（MP4 / MKV 等）我们<b>不解码</b>，
 * 但也得知道画面多大 —— 控件要按真实宽高比摆放，不然竖屏视频会被拉成方的。
 * 这里只读容器头部的几个字节，<b>不碰任何像素</b>，几十微秒就返回。</p>
 *
 * <p>支持两类容器（覆盖玩家实际会遇到的绝大部分文件）：</p>
 * <ul>
 *   <li><b>ISO-BMFF</b>（mp4 / m4v / mov / 3gp）：走 box 树找 {@code moov → trak → tkhd}，
 *       宽高是末尾的两个 16.16 定点数。{@code moov} 在文件尾（手机录制常见）也能找到 ——
 *       我们是按 box 大小跳着走的，不是只读开头一小段。</li>
 *   <li><b>EBML</b>（mkv / webm）：按元素 ID 与变长长度走到 {@code Tracks → TrackEntry → Video}
 *       里的 {@code PixelWidth(0xB0)} / {@code PixelHeight(0xBA)}。</li>
 * </ul>
 *
 * <p>认不出来就返回 {@code null}（调用方按 1:1 处理），<b>绝不抛异常</b> ——
 * 这只是个「顺便读到的提示」，不值得为它中断上传。</p>
 */
public final class VideoProbe {
    private VideoProbe() {
    }

    /** 单次探测最多读多少字节（防止对超大文件乱读）。 */
    private static final int MAX_HEAD_BYTES = 4 * 1024 * 1024;

    /** 探测尺寸。 */
    @Nullable
    public static int[] size(@Nullable Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long length = raf.length();
            if (length < 16) {
                return null;
            }
            byte[] head = new byte[(int) Math.min(length, 64L)];
            raf.seek(0L);
            raf.readFully(head);
            if (isIsoBmff(head)) {
                int[] sz = isoSizes(raf, length);
                if (sz != null) {
                    return sz;
                }
            }
            if (isEbml(head)) {
                int[] sz = ebmlSizes(raf, length);
                if (sz != null) {
                    return sz;
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 名称看起来是不是这两类容器（省得对图片也去读头）。 */
    public static boolean looksLikeContainer(@Nullable String fileName) {
        if (fileName == null) {
            return false;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        String ext = fileName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        return switch (ext) {
            case "mp4", "m4v", "mov", "3gp", "3gpp", "mkv", "webm" -> true;
            default -> false;
        };
    }

    // ------------------------------------------------------------ ISO-BMFF（mp4 / mov）

    private static boolean isIsoBmff(byte[] head) {
        return head.length >= 8 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p';
    }

    /** 走 box 树找 moov → trak → tkhd。 */
    @Nullable
    private static int[] isoSizes(RandomAccessFile raf, long length) {
        try {
            long moov = findBox(raf, 0L, length, "moov");
            if (moov < 0) {
                return null;
            }
            long end = Math.min(length, moov + boxSize(raf, moov, length));
            long p = moov + 8;
            while (p + 8 <= end) {
                long size = boxSize(raf, p, end);
                if ("trak".equals(boxType(raf, p))) {
                    long tkhd = findBox(raf, p + 8, Math.min(end, p + size), "tkhd");
                    if (tkhd >= 0) {
                        int[] sz = tkhdSize(raf, tkhd);
                        if (sz != null) {
                            return sz;      // 音轨的 tkhd 宽高是 0，tkhdSize 会返回 null，继续找
                        }
                    }
                }
                if (size <= 0L) {
                    break;
                }
                p += size;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 在 [from,to) 里按 box 顺序找 type。返回 box 起始偏移（-1 = 没找到）。 */
    private static long findBox(RandomAccessFile raf, long from, long to, String type) {
        try {
            long p = from;
            while (p + 8 <= to) {
                long size = boxSize(raf, p, to);
                if (type.equals(boxType(raf, p))) {
                    return p;
                }
                if (size <= 0L) {
                    return -1L;
                }
                p += size;
            }
        } catch (Throwable ignored) {
            // 结构看不懂就当作没找到
        }
        return -1L;
    }

    /** box 的 4 字节类型。 */
    private static String boxType(RandomAccessFile raf, long boxStart) {
        try {
            byte[] t = new byte[4];
            raf.seek(boxStart + 4);
            if (raf.read(t, 0, 4) < 4) {
                return "";
            }
            return new String(t, java.nio.charset.StandardCharsets.ISO_8859_1);
        } catch (Throwable t) {
            return "";
        }
    }

    private static long boxSize(RandomAccessFile raf, long boxStart, long to) {
        try {
            byte[] hdr = new byte[16];
            raf.seek(boxStart);
            if (raf.read(hdr, 0, 8) < 8) {
                return to - boxStart;
            }
            long size = u32(hdr, 0);
            if (size == 1L) {
                if (raf.read(hdr, 0, 8) < 8) {
                    return to - boxStart;
                }
                size = u64(hdr, 0);
            }
            if (size <= 0L) {
                return to - boxStart;
            }
            return size;
        } catch (Throwable t) {
            return to - boxStart;
        }
    }

    /** tkhd 末尾两个 16.16 定点数就是宽高。 */
    @Nullable
    private static int[] tkhdSize(RandomAccessFile raf, long tkhdStart) {
        try {
            byte[] head = new byte[8];
            raf.seek(tkhdStart);
            if (raf.read(head, 0, 8) < 8) {
                return null;
            }
            long size = u32(head, 0);
            if (size == 1L) {
                return null;                    // 64 位长度的 tkhd 没见过，放弃
            }
            byte[] body = new byte[(int) Math.min(size, 512L)];
            raf.seek(tkhdStart);
            int got = raf.read(body, 0, body.length);
            if (got < 84) {
                return null;
            }
            // 宽高就是 tkhd 末尾的两个 16.16 定点数（version 0/1 都一样在末尾）
            int w = (int) (u32(body, got - 8) >>> 16);
            int h = (int) (u32(body, got - 4) >>> 16);
            if (w <= 0 || h <= 0 || w > 32768 || h > 32768) {
                return null;
            }
            return new int[]{w, h};
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------ EBML（mkv / webm）

    private static boolean isEbml(byte[] head) {
        return head.length >= 4 && (head[0] & 0xFF) == 0x1A && (head[1] & 0xFF) == 0x45
                && (head[2] & 0xFF) == 0xDF && (head[3] & 0xFF) == 0xA3;
    }

    /**
     * 在头部窗口里找 TrackEntry → Video 的 PixelWidth / PixelHeight。
     *
     * <p>刻意做成「扫元素 ID + 长度合理性检查」而不是完整解析整棵树：
     * 我们只要两个数，且拿到后立刻做范围校验（16~32768）。</p>
     */
    @Nullable
    private static int[] ebmlSizes(RandomAccessFile raf, long length) {
        try {
            int window = (int) Math.min(length, MAX_HEAD_BYTES);
            byte[] buf = new byte[window];
            raf.seek(0L);
            int got = raf.read(buf, 0, window);
            int w = 0;
            int h = 0;
            for (int i = 4; i + 2 < got; i++) {
                int id = buf[i] & 0xFF;
                if (id != 0xB0 && id != 0xBA) {
                    continue;
                }
                long[] vint = readVint(buf, got, i + 1);
                if (vint == null || vint[1] < 1 || vint[1] > 8) {
                    continue;
                }
                int at = (int) vint[0];
                long value = 0L;
                boolean ok = true;
                for (int k = 0; k < vint[1]; k++) {
                    if (at + k >= got) {
                        ok = false;
                        break;
                    }
                    value = (value << 8) | (buf[at + k] & 0xFFL);
                }
                if (!ok || value < 16L || value > 32768L) {
                    continue;
                }
                if (id == 0xB0 && w == 0) {
                    w = (int) value;
                } else if (id == 0xBA && h == 0) {
                    h = (int) value;
                }
                if (w > 0 && h > 0) {
                    return new int[]{w, h};
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 读 EBML 变长整数：返回 {值起始偏移, 长度字节数}。 */
    @Nullable
    private static long[] readVint(byte[] buf, int end, int at) {
        if (at >= end) {
            return null;
        }
        int first = buf[at] & 0xFF;
        int len = 0;
        for (int bit = 7; bit >= 0; bit--) {
            if ((first & (1 << bit)) != 0) {
                len = 8 - bit;
                break;
            }
        }
        if (len < 1 || len > 8 || at + len > end) {
            return null;
        }
        long value = first & ((1 << (8 - len)) - 1);
        for (int k = 1; k < len; k++) {
            value = (value << 8) | (buf[at + k] & 0xFFL);
        }
        return new long[]{at + len, value};
    }

    // ------------------------------------------------------------ 小工具

    private static long u32(byte[] b, int at) {
        return ((b[at] & 0xFFL) << 24) | ((b[at + 1] & 0xFFL) << 16)
                | ((b[at + 2] & 0xFFL) << 8) | (b[at + 3] & 0xFFL);
    }

    private static long u64(byte[] b, int at) {
        long v = 0L;
        for (int k = 0; k < 8; k++) {
            v = (v << 8) | (b[at + k] & 0xFFL);
        }
        return v;
    }
}

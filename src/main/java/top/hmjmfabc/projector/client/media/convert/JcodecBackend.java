package top.hmjmfabc.projector.client.media.convert;

import org.jetbrains.annotations.Nullable;
import org.jcodec.api.FrameGrab;
import org.jcodec.api.PictureWithMetadata;
import org.jcodec.common.io.FileChannelWrapper;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.DemuxerTrack;
import org.jcodec.common.DemuxerTrackMeta;
import org.jcodec.common.model.Picture;
import org.lwjgl.stb.STBImageWrite;
import org.lwjgl.stb.STBIWriteCallbackI;
import org.lwjgl.system.MemoryUtil;
import top.hmjmfabc.projector.Projector;
import top.hmjmfabc.projector.ProjectorConfig;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * <b>纯 Java</b> 视频解码后端（不依赖任何外部程序）。
 *
 * <p>这是 Android 上唯一可行的方案：游戏跑在启动器的应用沙箱里
 * （实测 uid 与 Termux 不同，且 {@code /data/data/com.termux} 是 {@code drwx------}），
 * 既读不到沙箱外的外部程序（共享存储又是 noexec），共享存储又挂载为 {@code noexec}，
 * 打包 {@code .so} 也不符合本项目「模组本体不含任何 .so」的约束。</p>
 *
 * <p>两条流水线拼起来：</p>
 * <ol>
 *   <li><b>解码</b>：JCodec（纯 Java，无 AWT / ImageIO / JNI，
 *       由 {@code jarJar} 打在 {@code META-INF/jarjar/} 里）负责 MP4/MOV 解封装
 *       与 H.264 解码，逐帧给出 YUV 平面；</li>
 *   <li><b>编码</b>：我们把它转成 RGB，再用 <b>Minecraft 自带的
 *       {@code stbi_write_jpg}</b>（LWJGL stb，游戏本来就打包了它的 native）
 *       编成 JPEG。</li>
 * </ol>
 *
 * <p><b>内存策略</b>：逐帧处理、逐帧写出，任何时刻只有「一帧 RGB + 一张 JPEG」
 * 在内存里（512×512 时约 1 MB），因此转长视频也不会把内存顶上去。</p>
 */
public final class JcodecBackend {

    private JcodecBackend() {
    }

    /** 色彩空间/尺寸只报一次。 */
    private static volatile boolean reportedColorSpace;

    /** JCodec 是否可用（依赖缺失时优雅降级，不影响模组其它功能）。 */
    public static boolean available() {
        try {
            Class.forName("org.jcodec.api.FrameGrab");
            Class.forName("org.lwjgl.stb.STBImageWrite");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 执行转换（阻塞，请在后台线程调用）。输出写到 {@code out}。
     *
     * @return 成功写出的帧数；失败抛异常
     */
    public static int convert(Path source, Path out, VideoConverter.Target target,
                              VideoConverter.Options opts, VideoConverter.Progress cb) throws Exception {
        FileChannelWrapper channel = null;
        FrameGrab grab = null;
        try {
            channel = NIOUtils.readableChannel(source.toFile());
            grab = FrameGrab.createFrameGrab(channel);

            // 源帧率：容器元数据不一定可靠，拿不到就按 25 fps 估
            double srcFps = 25.0;
            int totalFrames = 0;
            double durationSec = 0;
            try {
                DemuxerTrack track = grab.getVideoTrack();
                if (track != null && track.getMeta() != null) {
                    DemuxerTrackMeta meta = track.getMeta();
                    totalFrames = meta.getTotalFrames();
                    durationSec = meta.getTotalDuration();
                    if (totalFrames > 0 && durationSec > 0.01) {
                        srcFps = totalFrames / durationSec;
                    }
                }
            } catch (Throwable ignored) {
                // 元数据缺失：用默认帧率
            }
            if (!(srcFps > 0.5) || !Double.isFinite(srcFps)) srcFps = 25.0;

            int targetFps = Math.max(1, opts.fps);
            int step = Math.max(1, (int) Math.round(srcFps / targetFps));
            int maxSide = Math.max(64, opts.maxSide);
            int stbQuality = stbQuality(opts.quality);
            long maxBytes = ProjectorConfig.INSTANCE.maxVideoBytes.get();

            Projector.LOGGER.info(
                    "[Projector] 纯 Java 转换开始：源 {}x{} 帧率≈{} 总帧≈{} 时长≈{}s -> 每 {} 帧取 1 帧",
                    grab.getMediaInfo().getDim().getWidth(), grab.getMediaInfo().getDim().getHeight(),
                    String.format(Locale.ROOT, "%.2f", srcFps), totalFrames,
                    String.format(Locale.ROOT, "%.1f", durationSec), step);

            // 输出容器
            try (OutputStream raw = new BufferedOutputStream(Files.newOutputStream(out), 1 << 16)) {
                ZipOutputStream zip = target == VideoConverter.Target.ZIP ? new ZipOutputStream(raw) : null;
                long written = 0;
                int frameIndex = 0;
                int readIndex = 0;
                long t0 = System.currentTimeMillis();

                while (true) {
                    if (cb.cancelled()) {
                        if (zip != null) zip.close();
                        throw new IOException("已取消");
                    }
                    // 【性能】时长上限：超过就停。纯 Java 解码速度是硬瓶颈
                    //（1080p 约 1~3 fps），这是唯一能真正缩短总耗时的办法。
                    if (opts.maxSeconds > 0 && srcFps > 0
                            && readIndex / srcFps > opts.maxSeconds) {
                        Projector.LOGGER.info("[Projector] 已达时长上限 {} 秒，停止解码",
                                opts.maxSeconds);
                        break;
                    }
                    PictureWithMetadata pwm = grab.getNativeFrameWithMetadata();
                    if (pwm == null || pwm.getPicture() == null) break;
                    int i = readIndex++;
                    if (i % step != 0) continue;

                    byte[] jpeg = encodeJpeg(pwm.getPicture(), maxSide, stbQuality);
                    if (jpeg == null) continue;
                    if (written + jpeg.length > maxBytes) {
                        Projector.LOGGER.info("[Projector] 已达单个媒体上限 {} 字节，停止转换", maxBytes);
                        break;
                    }
                    if (zip != null) {
                        writeStored(zip, String.format(Locale.ROOT, "frame_%05d.jpg", frameIndex), jpeg);
                    } else {
                        raw.write(jpeg);
                    }
                    written += jpeg.length;
                    frameIndex++;

                    // 进度：优先用元数据算百分比，没有就只报已处理帧数
                    double frac = totalFrames > 0
                            ? Math.min(1.0, (double) i / Math.max(1, totalFrames)) : -1;
                    double sec = srcFps > 0 ? i / srcFps : 0;
                    double elapsed = (System.currentTimeMillis() - t0) / 1000.0;
                    // 预估剩余：纯 Java 解码速度稳定，按「已解码帧数 / 已用时间」外推很准
                    String eta = "";
                    if (i > 0 && elapsed > 1.0 && totalFrames > 0) {
                        double decodeFps = i / elapsed;
                        int remaining = totalFrames - i;
                        if (opts.maxSeconds > 0 && srcFps > 0) {
                            remaining = Math.min(remaining,
                                    (int) (opts.maxSeconds * srcFps) - i);
                        }
                        if (remaining > 0 && decodeFps > 0.01) {
                            eta = String.format(Locale.ROOT, "，剩余约 %.0f 秒",
                                    remaining / decodeFps);
                        }
                    }
                    cb.update(frac, String.format(Locale.ROOT,
                            "已编码 %d 帧（源 %.0f 秒）用时 %.0f 秒%s%s",
                            frameIndex, sec, elapsed,
                            frac >= 0 ? String.format(Locale.ROOT, "，进度 %.0f%%", frac * 100) : "",
                            eta));
                }
                if (zip != null) {
                    zip.finish();
                    zip.flush();
                }
                raw.flush();
                if (frameIndex == 0) {
                    throw new IOException("没有解出任何帧（可能是 JCodec 不支持的编码，例如 H.265/AV1）");
                }
                return frameIndex;
            }
        } finally {
            // 释放原生映射与解码器
            try {
                if (channel != null) NIOUtils.closeQuietly(channel);
            } catch (Throwable ignored) {
                // 关闭失败无所谓
            }
        }
    }

    // ------------------------------------------------------------------
    // 帧 -> JPEG
    // ------------------------------------------------------------------

    /**
     * 把一帧 YUV 画面转成 RGB，缩放到 {@code maxSide} 以内，再编成 JPEG。
     *
     * @return JPEG 字节；失败返回 null
     */
    @Nullable
    private static byte[] encodeJpeg(Picture pic, int maxSide, int stbQuality) {
        try {
            // 【重要】必须用「裁剪后」的尺寸：H.264 按 16 像素对齐，
            // 例如 1080p 解出来是 1920x1088，最后 8 行是填充数据（不是画面），
            // 直接用完整高度会把这几行杂色也画进去。
            int w = pic.getCroppedWidth() > 0 ? pic.getCroppedWidth() : pic.getWidth();
            int h = pic.getCroppedHeight() > 0 ? pic.getCroppedHeight() : pic.getHeight();
            if (w <= 0 || h <= 0) return null;
            ColorSpace cs = pic.getColor();
            if (!reportedColorSpace) {
                reportedColorSpace = true;
                Projector.LOGGER.info("[Projector] 视频帧色彩空间={} 完整={}x{} 裁剪后={}x{}",
                        cs, pic.getWidth(), pic.getHeight(), w, h);
            }
            byte[] yPlane = pic.getPlaneData(0);
            byte[] uPlane = pic.getPlaneData(1);
            byte[] vPlane = pic.getPlaneData(2);
            if (yPlane == null) return null;
            // 无彩色分量（灰度）：当成 U=V=128
            boolean mono = uPlane == null || vPlane == null;

            // 缩放到 maxSide 以内（等比）
            double scale = Math.min(1.0, (double) maxSide / Math.max(w, h));
            int dw = Math.max(2, (int) Math.round(w * scale));
            int dh = Math.max(2, (int) Math.round(h * scale));
            dw &= ~1;
            dh &= ~1;

            // 色彩范围：YUV420J 是 JPEG 全范围，YUV420 是 BT.601 有限范围
            boolean fullRange = cs != ColorSpace.YUV420;
            // 【关键】色度平面的尺寸/步长必须问 Picture 自己。
            // 之前按 w/2、h/2 硬猜，一旦实际排布不是正好一半
            //（对齐、填充、不同的 4:2:0 变体），整片画面就会串色——
            // 表现就是「炫彩色」。Picture.getPlaneWidth/Height 是权威值。
            int cw = Math.max(1, pic.getPlaneWidth(1));
            int ch = Math.max(1, pic.getPlaneHeight(1));

            ByteBuffer rgb = MemoryUtil.memAlloc(dw * dh * 3);
            try {
                // 【性能】预计算「目标像素 -> 源像素」索引，避免在逐像素循环里做除法。
                // 1080p 一帧有 200 万像素，两次整数除法是这套循环里最贵的东西。
                int[] sxMap = new int[dw];
                for (int dx = 0; dx < dw; dx++) sxMap[dx] = Math.min(w - 1, dx * w / dw);
                int[] syMap = new int[dh];
                for (int dy = 0; dy < dh; dy++) syMap[dy] = Math.min(h - 1, dy * h / dh);

                // 【性能】颜色换算查表。注意 Y 与色度必须各用各的表：
                //   R = Y + 1.402*(V-128)
                //   G = Y - 0.344136*(U-128) - 0.714136*(V-128)
                //   B = Y + 1.772*(U-128)
                // 之前错误地把「亮度」也折进了以色度为下标的表里（用同一个 i 推 yy 和 cc），
                // 等于用色度字节冒充亮度 —— 画面就会变成炫彩色。
                int[] tY = new int[256];    // 亮度：范围映射
                int[] tRV = new int[256];   // V 对 R 的贡献（已含 -128 偏移）
                int[] tGU = new int[256];   // U 对 G 的贡献
                int[] tGV = new int[256];   // V 对 G 的贡献
                int[] tBU = new int[256];   // U 对 B 的贡献
                for (int i = 0; i < 256; i++) {
                    tY[i] = fullRange ? i : clamp255((i - 16) * 255 / 219);
                    int c = fullRange ? i - 128 : clamp255((i - 128) * 255 / 224 + 128) - 128;
                    tRV[i] = (91881 * c) >> 16;
                    tGU[i] = (22554 * c) >> 16;
                    tGV[i] = (46802 * c) >> 16;
                    tBU[i] = (116130 * c) >> 16;
                }

                int o = 0;
                for (int dy = 0; dy < dh; dy++) {
                    int sy = syMap[dy];
                    int yRow = sy * w;
                    int cRow = (sy >> 1) * cw;
                    for (int dx = 0; dx < dw; dx++) {
                        int sx = sxMap[dx];
                        int Y = tY[yPlane[yRow + sx] & 0xFF];
                        if (mono) {
                            rgb.put(o, (byte) Y);
                            rgb.put(o + 1, (byte) Y);
                            rgb.put(o + 2, (byte) Y);
                        } else {
                            int ci = cRow + (sx >> 1);
                            int u = uPlane[ci] & 0xFF;
                            int v = vPlane[ci] & 0xFF;
                            rgb.put(o, (byte) clamp255(Y + tRV[v]));
                            rgb.put(o + 1, (byte) clamp255(Y - tGU[u] - tGV[v]));
                            rgb.put(o + 2, (byte) clamp255(Y + tBU[u]));
                        }
                        o += 3;
                    }
                }
                return writeJpeg(rgb, dw, dh, stbQuality);
            } finally {
                MemoryUtil.memFree(rgb);
            }
        } catch (Throwable t) {
            Projector.LOGGER.warn("[Projector] 单帧编码失败：{}", t.toString());
            return null;
        }
    }

    /** 用 Minecraft 自带的 stb_image_write 把 RGB 缓冲编成 JPEG。 */
    private static byte[] writeJpeg(ByteBuffer rgb, int w, int h, int quality) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
        STBIWriteCallbackI sink = (context, data, size) -> {
            ByteBuffer src = MemoryUtil.memByteBuffer(data, size);
            byte[] tmp = new byte[size];
            src.get(tmp);
            out.write(tmp, 0, size);
        };
        int ok = STBImageWrite.stbi_write_jpg_to_func(sink, 0L, w, h, 3, rgb, quality);
        if (ok == 0) return null;
        return out.toByteArray();
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    /**
     * 把界面上的「ffmpeg 风格画质 1~31（越小越好）」换算成 stb 的 1~100（越大越好）。
     */
    private static int stbQuality(int uiQuality) {
        int q = 100 - (Math.max(1, Math.min(31, uiQuality)) - 1) * 3;
        return Math.max(25, Math.min(95, q));
    }

    /** 以 STORED 方式写入 zip 条目（JPEG 已压缩，无需再 deflate）。 */
    private static void writeStored(ZipOutputStream zip, String name, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setMethod(ZipEntry.STORED);
        e.setSize(data.length);
        e.setCompressedSize(data.length);
        CRC32 crc = new CRC32();
        crc.update(data);
        e.setCrc(crc.getValue());
        zip.putNextEntry(e);
        zip.write(data);
        zip.closeEntry();
    }
}

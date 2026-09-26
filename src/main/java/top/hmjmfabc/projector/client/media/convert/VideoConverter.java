package top.hmjmfabc.projector.client.media.convert;

import org.jetbrains.annotations.Nullable;
import top.hmjmfabc.projector.Projector;
import top.hmjmfabc.projector.ProjectorConfig;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 把常见视频格式（MP4 / MKV / MOV / WEBM …）转换成模组支持的两种格式之一。
 *
 * <p><b>两种目标格式：</b></p>
 * <ul>
 *   <li>{@link Target#MJPEG} —— {@code .mjpg}，一串首尾相连的 JPEG（MJPEG）。
 *       模组直接按 JPEG 的 SOI/EOI 标记切片定位每一帧，读取最快（零解码开销）。</li>
 *   <li>{@link Target#ZIP} —— {@code .zip}，一个存着 JPEG 帧序列的压缩包。
 *       帧数据以 STORED（不压缩）方式写入：JPEG 本身已经压过了，再 deflate 一遍
 *       只会白白吃 CPU，而模组的读取端本来就支持 STORED。</li>
 * </ul>
 *
 * <p><b>内存策略：</b>整个转换过程是流式的——ffmpeg 把 MJPEG 写到标准输出，
 * 我们边读边按帧切开直接写进目标文件，任何时刻内存里只有一帧（几百 KB）。
 * 因此转一个几百 MB 的长视频也不会把内存顶上去。</p>
 *
 * <p>整个过程跑在调用方的后台线程上（见 {@code ConvertScreen}），
 * 支持进度回调与取消。</p>
 */
public final class VideoConverter {

    /** 目标格式。 */
    public enum Target {
        /** 单文件 MJPEG（.mjpg）。 */
        MJPEG("mjpg", "MJPEG 单文件 (.mjpg)"),
        /** ZIP 帧序列（.zip）。 */
        ZIP("zip", "ZIP 帧序列 (.zip)");

        public final String ext;
        public final String label;

        Target(String ext, String label) {
            this.ext = ext;
            this.label = label;
        }
    }

    /** 转换参数。 */
    public static final class Options {
        public Target target = Target.MJPEG;
        public int fps = 10;
        public int maxSide = 512;
        public int quality = 5;
        /**
         * 只转换前 N 秒（0 = 全部）。
         *
         * <p>纯 Java 解码 1080p 大约只有 1~3 fps，所以耗时基本上就等于
         * 「源视频帧数 / 解码速度」——<b>和输出帧率、画面大小都无关</b>。
         * 这个选项是唯一能真正压缩耗时的开关。</p>
         */
        public int maxSeconds = 0;

        public static Options defaults() {
            Options o = new Options();
            o.fps = ProjectorConfig.INSTANCE.convertFps.get();
            o.maxSide = ProjectorConfig.INSTANCE.convertMaxSide.get();
            o.quality = ProjectorConfig.INSTANCE.convertQuality.get();
            return o;
        }
    }

    /** 进度回调：{@code fraction < 0} 表示总时长未知（只能报已处理时长）。 */
    public interface Progress {
        void update(double fraction, String message);

        /** 返回 true 表示玩家点了取消，转换会被终止。 */
        boolean cancelled();
    }

    /** 转换结果。 */
    public record Result(boolean ok, String message, @Nullable Path output) {
    }

    private VideoConverter() {
    }

    /** 目标文件默认放在源文件旁边，文件名相同、后缀换成目标格式。 */
    public static Path defaultOutput(Path source, Target target) {
        String name = source.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return source.resolveSibling(name + "." + target.ext);
    }

    /**
     * 执行转换（阻塞，请放在后台线程里跑）。
     *
     * @param source 源视频文件
     * @param target 目标文件（会被覆盖）
     */
    public static Result convert(Path source, Path target, Options opts, Progress progress) {
        // 后端选择：优先用内置的纯 Java 解码器（任何平台都能用，Android 上唯一可行），
        // ffmpeg 只作为可选加速（有的话帧序列化更快，且支持 H.265/AV1 等更多编码）。
        Path ffmpeg = Ffmpeg.locateBlocking();
        boolean javaOk = JcodecBackend.available();
        if (ffmpeg == null && !javaOk) {
            return new Result(false, "没有可用的视频解码器（内置解码器不可用，也没有 ffmpeg）", null);
        }
        if (!Files.isRegularFile(source)) {
            return new Result(false, "源文件不存在", null);
        }
        try {
            Files.createDirectories(target.getParent());
        } catch (Exception ignored) {
            // 目录已存在
        }
        // 先写到临时文件，成功后再改名：避免转换失败留下半个文件被当成可用素材。
        Path temp = target.resolveSibling(target.getFileName() + ".part");
        try {
            Result r;
            if (ffmpeg != null) {
                r = opts.target == Target.ZIP
                        ? convertToZip(ffmpeg, source, temp, opts, progress)
                        : convertToMjpeg(ffmpeg, source, temp, opts, progress);
                if (!r.ok() && javaOk) {
                    // ffmpeg 失败（编码不支持等）时回退到内置解码器再试一次
                    progress.update(-1, "ffmpeg 失败，改用内置解码器重试…");
                    Files.deleteIfExists(temp);
                    r = convertWithJcodec(source, temp, opts, progress);
                }
            } else {
                r = convertWithJcodec(source, temp, opts, progress);
            }
            if (!r.ok()) {
                Files.deleteIfExists(temp);
                return r;
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            long size = Files.size(target);
            return new Result(true, String.format(Locale.ROOT,
                    "完成：%s（%.1f MB）", target.getFileName(), size / 1048576.0), target);
        } catch (Throwable t) {
            try {
                Files.deleteIfExists(temp);
            } catch (Exception ignored) {
                // 清理失败无所谓
            }
            Projector.LOGGER.error("[Projector] 视频转换失败", t);
            return new Result(false, "转换失败：" + t, null);
        }
    }

    /** 内置纯 Java 解码器（JCodec + stb_image_write）。 */
    private static Result convertWithJcodec(Path source, Path out, Options o, Progress cb) {
        try {
            cb.update(-1, "正在用内置解码器转换…");
            int frames = JcodecBackend.convert(source, out, o.target, o, cb);
            cb.update(1.0, "完成：" + frames + " 帧");
            return new Result(true, "完成：" + frames + " 帧", null);
        } catch (Throwable t) {
            Projector.LOGGER.error("[Projector] 内置解码器转换失败", t);
            if ("已取消".equals(t.getMessage())) {
                return new Result(false, "已取消", null);
            }
            return new Result(false, "内置解码器转换失败：" + t.getMessage(), null);
        }
    }

    // ------------------------------------------------------------------
    // MJPEG 单文件
    // ------------------------------------------------------------------

    private static Result convertToMjpeg(Path ffmpeg, Path source, Path out, Options o, Progress cb)
            throws Exception {
        List<String> cmd = baseCommand(ffmpeg, source, o);
        // 限制输出体积：超过存档单个媒体上限后再转下去也没意义（上传会被拒），
        // 而且 -fs 截断处如果落在帧中间，读取端会自动丢掉那个残缺帧。
        cmd.add("-fs");
        cmd.add(String.valueOf(ProjectorConfig.INSTANCE.maxVideoBytes.get()));
        cmd.add("-f");
        cmd.add("avi");
        cmd.add(out.toAbsolutePath().toString());
        return run(cmd, null, cb, "正在转换为 MJPEG…");
    }

    // ------------------------------------------------------------------
    // ZIP 帧序列
    // ------------------------------------------------------------------

    private static Result convertToZip(Path ffmpeg, Path source, Path out, Options o, Progress cb)
            throws Exception {
        List<String> cmd = baseCommand(ffmpeg, source, o);
        // 把 MJPEG 流送到标准输出，我们边读边切帧写进 zip（全程不落临时帧文件）
        cmd.add("-f");
        cmd.add("mjpeg");
        cmd.add("pipe:1");
        return run(cmd, pipe -> writeZip(pipe, out, cb), cb, "正在转换为 ZIP 帧序列…");
    }

    /** 从 MJPEG 字节流里切出每一帧 JPEG，写进 zip（STORED，不再压缩）。 */
    private static void writeZip(InputStream in, Path out, Progress cb) throws Exception {
        // 最小帧长：低于这个字节数的一定是标记噪声而不是真 JPEG，直接丢掉
        final int minFrameBytes = 128;
        final long maxBytes = ProjectorConfig.INSTANCE.maxVideoBytes.get();
        try (ZipOutputStream zip = new ZipOutputStream(
                new BufferedOutputStream(Files.newOutputStream(out)))) {
            byte[] buf = new byte[65536];
            ByteArrayOutputStream frame = new ByteArrayOutputStream(1 << 17);
            int frameIndex = 0;
            long written = 0;
            boolean inFrame = false;
            boolean full = false;
            int prev = -1;
            int n;
            while (!full && (n = in.read(buf)) > 0) {
                if (cb.cancelled()) return;
                for (int i = 0; i < n; i++) {
                    int b = buf[i] & 0xFF;
                    if (!inFrame) {
                        // 找 SOI：FF D8
                        if (prev == 0xFF && b == 0xD8) {
                            inFrame = true;
                            frame.reset();
                            frame.write(0xFF);
                            frame.write(0xD8);
                        }
                    } else {
                        frame.write(b);
                        // 找 EOI：FF D9
                        // 说明：合法 JPEG 的熵编码数据里 FF 后面必须跟 00（或 RSTn），
                        // 所以 FF D9 只可能出现在真正的 EOI 处。模组的读取端
                        // （VideoSource.scanMjpeg）用的就是同一条规则，
                        // 因此「转出来的文件一定能被自己读回来」。
                        if (prev == 0xFF && b == 0xD9) {
                            byte[] data = frame.toByteArray();
                            if (data.length >= minFrameBytes) {
                                if (written + data.length > maxBytes) {
                                    full = true;
                                } else {
                                    writeStored(zip, String.format(Locale.ROOT, "frame_%05d.jpg", frameIndex++), data);
                                    written += data.length;
                                }
                            }
                            frame.reset();
                            inFrame = false;
                            if (full) break;
                        }
                    }
                    prev = b;
                }
            }
            // 流意外结束时若还有半帧，直接丢弃（不完整的 JPEG 解不出来）
            if (frameIndex == 0) {
                throw new java.io.IOException("没有提取到任何帧");
            }
        }
    }

    /** 以 STORED 方式写入一个 zip 条目（JPEG 已压缩，无需再 deflate）。 */
    private static void writeStored(ZipOutputStream zip, String name, byte[] data) throws Exception {
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

    // ------------------------------------------------------------------
    // 命令构造与执行
    // ------------------------------------------------------------------

    /**
     * 公共的 ffmpeg 参数。
     *
     * <p>视频滤镜刻意写成两段 scale：第一段把画面等比缩进 {@code maxSide x maxSide} 的框里，
     * 第二段把宽高各截成偶数——{@code yuvj420p} 要求偶数尺寸，否则某些分辨率会直接失败。</p>
     */
    private static List<String> baseCommand(Path ffmpeg, Path source, Options o) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpeg.toString());
        cmd.add("-hide_banner");
        cmd.add("-nostdin");
        cmd.add("-y");
        cmd.add("-i");
        cmd.add(source.toAbsolutePath().toString());
        if (o.maxSeconds > 0) {
            cmd.add("-t");
            cmd.add(String.valueOf(o.maxSeconds));
        }
        cmd.add("-an");   // 不要音轨：模组不播放声音
        cmd.add("-sn");
        cmd.add("-vf");
        String max = String.valueOf(Math.max(64, o.maxSide));
        cmd.add("fps=" + Math.max(1, o.fps)
                + ",scale=" + max + ":" + max + ":force_original_aspect_ratio=decrease"
                + ",scale=trunc(iw/2)*2:trunc(ih/2)*2");
        cmd.add("-c:v");
        cmd.add("mjpeg");
        cmd.add("-q:v");
        cmd.add(String.valueOf(Math.max(1, Math.min(31, o.quality))));
        cmd.add("-pix_fmt");
        cmd.add("yuvj420p");
        // 进度走 stderr 的 key=value 行，便于一边读一边算百分比
        cmd.add("-progress");
        cmd.add("pipe:2");
        cmd.add("-nostats");
        return cmd;
    }

    /** 读取标准输出的回调（为 null 时表示输出写到文件，不读 stdout）。 */
    private interface PipeReader {
        void read(InputStream in) throws Exception;
    }

    private static Result run(List<String> cmd, @Nullable PipeReader reader, Progress cb, String whatTaken)
            throws Exception {
        Projector.LOGGER.info("[Projector] 执行转换: {}", String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        if (reader == null) {
            // 输出写文件时不读 stdout：直接丢弃，避免管道缓冲积压把 ffmpeg 卡住
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        }
        Process p = pb.start();

        // stderr：解析总时长与进度（ffmpeg 的 -progress 也写在这里）
        final double[] totalSec = {-1};
        final int[] lastFrame = {0};
        Thread errThread = new Thread(() -> {
            try (var r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (cb.cancelled()) break;
                    String t = line.trim();
                    if (t.startsWith("Duration:")) {
                        totalSec[0] = parseDuration(t);
                    } else if (t.startsWith("out_time_ms=")) {
                        double sec = parseLong(t.substring("out_time_ms=".length())) / 1_000_000.0;
                        if (sec > 0) {
                            double frac = totalSec[0] > 0 ? Math.min(1.0, sec / totalSec[0]) : -1;
                            cb.update(frac, String.format(Locale.ROOT,
                                    "%s %.0f 秒 / %s", whatTaken, sec,
                                    totalSec[0] > 0 ? String.format(Locale.ROOT, "%.0f 秒", totalSec[0]) : "未知"));
                        }
                    } else if (t.startsWith("frame=")) {
                        lastFrame[0] = (int) parseLong(t.substring("frame=".length()));
                    }
                }
            } catch (Throwable ignored) {
                // 进程结束/取消时读流会抛异常，属正常
            }
        }, "Projector-ffmpeg-log");
        errThread.setDaemon(true);
        errThread.start();

        // 读 stdout（ZIP 模式）
        final Exception[] readerError = {null};
        Thread outThread = null;
        if (reader != null) {
            outThread = new Thread(() -> {
                try (InputStream in = new BufferedInputStream(p.getInputStream(), 1 << 16)) {
                    reader.read(in);
                } catch (Exception ex) {
                    readerError[0] = ex;
                }
            }, "Projector-ffmpeg-out");
            outThread.setDaemon(true);
            outThread.start();
        }

        // 等待结束，同时响应取消
        while (!p.waitFor(200, TimeUnit.MILLISECONDS)) {
            if (cb.cancelled()) {
                p.destroy();
                if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly();
                return new Result(false, "已取消", null);
            }
        }
        if (outThread != null) outThread.join(10_000);
        int code = p.exitValue();
        if (code != 0) {
            return new Result(false, "ffmpeg 退出码 " + code + "（源文件可能是不支持的编码）", null);
        }
        if (readerError[0] != null) {
            throw readerError[0];
        }
        if (lastFrame[0] <= 0) {
            return new Result(false, "没有产出任何帧（视频时长可能为 0）", null);
        }
        cb.update(1.0, "完成：" + lastFrame[0] + " 帧");
        return new Result(true, "完成：" + lastFrame[0] + " 帧", null);
    }

    /** 解析 {@code Duration: 00:01:23.45, ...}。 */
    private static double parseDuration(String line) {
        int i = line.indexOf(':');
        if (i < 0) return -1;
        String s = line.substring(i + 1).trim();
        int comma = s.indexOf(',');
        if (comma > 0) s = s.substring(0, comma);
        String[] parts = s.split(":");
        if (parts.length != 3) return -1;
        try {
            return Integer.parseInt(parts[0].trim()) * 3600.0
                    + Integer.parseInt(parts[1].trim()) * 60.0
                    + Double.parseDouble(parts[2].trim());
        } catch (Exception ex) {
            return -1;
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim().split("\\s+")[0]);
        } catch (Exception ex) {
            return 0;
        }
    }
}

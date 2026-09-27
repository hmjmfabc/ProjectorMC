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
 * <p><b>内存策略：</b>整个转换过程是流式的——内置解码器把 MJPEG 按帧写进目标文件，
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
        // 【27.1.2】只保留内置的纯 Java 解码器（JCodec）：
        // ffmpeg 那条路已经砍掉 —— Android 上本来就调不到它（启动器沙箱 + noexec），
        // 而装了 WaterMedia 的玩家可以直接放常见格式，不需要先转码。
        boolean javaOk = JcodecBackend.available();
        if (!javaOk) {
            return new Result(false, "内置视频解码器不可用（JCodec 未加载）", null);
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
            Result r = convertWithJcodec(source, temp, opts, progress);
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

    // ------------------------------------------------------------------
    // ZIP 帧序列
    // ------------------------------------------------------------------

    /** 从 MJPEG 字节流里切出每一帧 JPEG，写进 zip（STORED，不再压缩）。 */
    /** 以 STORED 方式写入一个 zip 条目（JPEG 已压缩，无需再 deflate）。 */
    // ------------------------------------------------------------------
    // 命令构造与执行
    // ------------------------------------------------------------------

    /**
     * 公共的输出参数。
     *
     * <p>视频滤镜刻意写成两段 scale：第一段把画面等比缩进 {@code maxSide x maxSide} 的框里，
     * 第二段把宽高各截成偶数——{@code yuvj420p} 要求偶数尺寸，否则某些分辨率会直接失败。</p>
     */
    /** 读取标准输出的回调（为 null 时表示输出写到文件，不读 stdout）。 */
    private interface PipeReader {
        void read(InputStream in) throws Exception;
    }

    /** 解析 {@code Duration: 00:01:23.45, ...}。 */
}

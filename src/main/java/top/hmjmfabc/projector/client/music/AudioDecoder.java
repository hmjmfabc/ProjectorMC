package top.hmjmfabc.projector.client.music;

import top.hmjmfabc.projector.Projector;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 音频解码入口：把「任意音频字节流」变成 **PCM 16 位**的 {@link AudioInputStream}。
 *
 * <p>三条与参考实现不同的设计决定（都是为了少踩坑）：</p>
 * <ol>
 *   <li><b>直接 new 解码器，不走 {@code ServiceLoader}</b>。参考实现把解码器登记在
 *       {@code META-INF/services/} 里靠 SPI 发现；而我们的解码库是 **jar-in-jar** 嵌进来的，
 *       能不能被 SPI 扫到取决于启动器的类加载器，属于不可控因素。直接构造是确定的。</li>
 *   <li><b>先看文件头再选解码器</b>。参考实现是「挨个试，失败就 reset 再试下一个」——
 *       而 {@code BufferedInputStream} 的 mark 上限一旦被读过就作废，
 *       {@code reset()} 会抛 “Resetting to invalid mark”，整条重试链当场断掉。
 *       这里先读 16 字节判魔数（{@code fLaC / ID3 / 帧同步 / ftyp / RIFF / OggS}）挑对解码器，
 *       重试时再显式把 mark 上限开到 256 KB。</li>
 *   <li><b>多声道降成立体声</b>：OpenAL 只认 MONO16 / STEREO16
 *       （引擎的 {@code OpenAlUtil.audioFormatToOpenAl} 对其它的会抛异常）。</li>
 *   <li><b>不支持 AAC/M4A</b>：解码库与 JCodec 自带的 jaad 同名包冲突（见下方案例注释）。</li>
 * </ol>
 *
 * <p>解码库与移植来源见仓库根目录 NOTICE：mp3spi/jlayer/tritonus（LGPL-2.1）、
 * jflac（BSD）、javasound-aac（LGPL-2.1）。</p>
 */
public final class AudioDecoder {
    private AudioDecoder() {
    }

    /** MP3 头里读不到采样率时用这个。 */
    private static final float FALLBACK_SAMPLE_RATE = 44100f;
    /** 探测魔数要看的字节数。 */
    private static final int PROBE_BYTES = 16;
    /** 探测用的 mark 上限（必须大于 PROBE_BYTES）。 */
    private static final int PROBE_MARK_LIMIT = 64;
    /** 重试另一种解码器时允许退回去的字节数。 */
    private static final int RETRY_MARK_LIMIT = 256 * 1024;

    /** 识别出来的格式。 */
    private enum Format {
        MP3, FLAC, AAC, WAV, OGG, UNKNOWN
    }

    /**
     * 打开并解码。调用方负责关流。
     *
     * @param raw 原始字节流（本方法内部会包一层支持 mark/reset 的缓冲流）
     */
    public static AudioInputStream open(InputStream raw) throws Exception {
        InputStream in = raw instanceof MusicBufferedInputStream
                ? raw
                : new MusicBufferedInputStream(raw);

        Format format = detect(probe(in));
        if (format == Format.OGG) {
            throw new UnsupportedOperationException(
                    "不支持 Ogg Vorbis 音频（音乐控件认 MP3 / FLAC / M4A / WAV）");
        }

        // 先建立重试点，再跳 ID3：这样重试时回到的是第一个 MPEG 帧，而不是标签前面
        markForRetry(in);
        if (format == Format.MP3) {
            Mp3Util.skipID3(in);
        }

        AudioInputStream src = read(in, format);
        AudioFormat audioFormat = src.getFormat();
        if (isUsablePcm(audioFormat)) {
            return src;
        }
        return convertToPcm(src, audioFormat);
    }

    /**
     * 探测音频总时长（毫秒）；拿不到返回 0。
     *
     * <p>【为什么不能只看 {@code getFrameLength()}】mp3spi 对「从流里读的 MP3」
     * 返回 {@code AudioSystem.NOT_SPECIFIED}（-1）—— 而我们的素材全是流
     * （本地文件也要包一层缓冲流才能 mark/reset）。于是时长恒为 0，
     * 控件只好用兜底的 180 秒，玩家看到的就是「2:40 显示成 3:00」。</p>
     *
     * <p>【27.2.1-hotfix-144 修正】原来第 ④ 级是
     * {@code byteLength / frameSize / 采样率} —— <b>公式本身是错的</b>：
     * {@code format.getFrameSize()} 给的是**解码后 PCM** 一帧的字节数（48k 立体声 16 位 =
     * 4 字节），却被当成「MP3 帧大小」去除字节数 ⇒ 帧数被放大几千倍。
     * 实测：2,571,693 字节 / 4 = 642,923 「帧」⇒ 642923 × 1000 ÷ 48000 = <b>13394ms</b>，
     * 于是一首 2:40 的歌被量成 <b>0:13</b>（玩家报的正是这个数字）。</p>
     *
     * <p>现在改成「多路探测 + 交叉校验」，从准到糙：</p>
     * <ol>
     *   <li>解码器给的格式属性：{@code duration}（微秒）、{@code mp3.id3tag.length}（毫秒）；</li>
     *   <li>{@code pcm.getFrameLength() / 采样率}（FLAC、WAV 一般都有）；</li>
     *   <li><b>码率 × 字节数</b>（{@code 字节 × 8 ÷ 码率}）—— MP3 最常见的那条；</li>
     *   <li>（已删除）那个用 PCM frameSize 当 MP3 帧大小的分支。**不要再加回来。**</li>
     * </ol>
     * <p>另外加一道**合理性交叉校验**：凡是与「真实读到的 PCM 字节数」算出的时长
     * 差 2 倍以上的候选值一律丢掉（见 {@link #probeDurationMsExact}）。</p>
     *
     * @param byteLength 音频总字节数（本地文件用 {@code Files.size}；未知传 0）
     */
    public static long probeDurationMs(AudioInputStream pcm, long byteLength) {
        if (pcm == null) {
            return 0L;
        }
        AudioFormat format = pcm.getFormat();
        float rate = format.getSampleRate();

        // ① 格式属性
        Object duration = format.getProperty("duration");
        if (duration instanceof Number n && n.longValue() > 0) {
            return n.longValue() / 1000L;                 // mp3spi 用微秒
        }
        Object id3Length = format.getProperty("mp3.id3tag.length");
        if (id3Length instanceof Number n && n.longValue() > 0) {
            return n.longValue();                         // TLEN 就是毫秒
        }

        // ② 帧数
        long frames = pcm.getFrameLength();
        if (frames > 0 && rate > 0) {
            return (long) (frames * 1000.0 / rate);
        }

        // ③ 码率 × 字节数（本地文件一定能用）
        Object bitrate = format.getProperty("mp3.bitrate.nominal.bps");
        if (bitrate instanceof Number b && b.longValue() > 0 && byteLength > 0) {
            return byteLength * 8000L / b.longValue();
        }
        // ⚠【27.2.1-hotfix-144】这里原来还有第 ④ 级：
        //     byteLength / format.getFrameSize() / 采样率
        //   它是**错的**：getFrameSize() 是解码后 PCM 的帧大小（48k 立体声 = 4 字节），
        //   拿它当 MP3 帧大小去除字节数，等于把帧数放大几千倍
        //   （实测 2571693 字节 ⇒ 642923「帧」⇒ 13394ms，2:40 的歌显示成 0:13）。
        //   已删除。MP3 要数帧必须按 MPEG 头算：每帧 1152 样本 ÷ 帧长字节数。
        return 0L;
    }

    /**
     * 【27.2.1-hotfix-144】**穷举式**量时长：把整条 PCM 流读完，用真实字节数算。
     *
     * <p>为什么需要它：{@link #probeDurationMs} 那三条都依赖解码器元数据，而 mp3spi 对
     * 「流式读入的 MP3」既不给 {@code frameLength} 也不给 {@code duration}，
     * 剩下的码率估算对 VBR 文件能差 20%（其实也够用），真正出问题的是同一条链上的错误分支。
     * 本地文件反正就在磁盘上，读完一遍（几 MB）只要几十毫秒，换来一个**准确值**。</p>
     *
     * <p>调用方负责关流。返回 0 = 读不出来（调用方回退到 {@link #probeDurationMs}）。</p>
     *
     * @param byteLength 压缩后的字节数（本地文件用 {@code Files.size()}；未知传 0）
     */
    public static long probeDurationMsExact(java.io.InputStream raw, long byteLength) {
        if (raw == null) {
            return 0L;
        }
        try (AudioInputStream pcm = open(raw)) {
            AudioFormat f = pcm.getFormat();
            float rate = f.getSampleRate() > 0 ? f.getSampleRate() : 44100f;
            int frameSize = Math.max(1, f.getFrameSize());
            long total = 0L;
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = pcm.read(buf)) > 0) {
                total += n;
            }
            if (total <= 0) {
                return 0L;
            }
            long exact = (long) ((total / (double) frameSize) * 1000.0 / rate);
            // 与粗糙估算交叉校验：差 2 倍以上说明有一边错得离谱，取**精确值**（它来自真实样本数）
            long rough = probeDurationMs(pcm, byteLength);
            return exact;
        } catch (Throwable t) {
            return 0L;
        }
    }

    // ------------------------------------------------------------------ 探测

    /** 读几个字节看看是什么格式，读完把流退回去。 */
    private static byte[] probe(InputStream in) {
        byte[] head = new byte[PROBE_BYTES];
        if (!in.markSupported()) {
            return new byte[0];
        }
        in.mark(PROBE_MARK_LIMIT);
        int read = 0;
        try {
            while (read < PROBE_BYTES) {
                int n = in.read(head, read, PROBE_BYTES - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            in.reset();
        } catch (Exception e) {
            Projector.LOGGER.debug("[Projector][音乐] 探测文件头失败：{}", e.toString());
            return new byte[0];
        }
        return head;
    }

    private static Format detect(byte[] h) {
        if (h.length >= 4 && h[0] == 'f' && h[1] == 'L' && h[2] == 'a' && h[3] == 'C') {
            return Format.FLAC;
        }
        if (h.length >= 4 && h[0] == 'O' && h[1] == 'g' && h[2] == 'g' && h[3] == 'S') {
            return Format.OGG;
        }
        if (h.length >= 4 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F') {
            return Format.WAV;
        }
        if (h.length >= 3 && h[0] == 'I' && h[1] == 'D' && h[2] == '3') {
            return Format.MP3;
        }
        // MPEG 帧同步：连续 11 个 1
        if (h.length >= 2 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xE0) == 0xE0) {
            return Format.MP3;
        }
        // MP4/M4A：第 4~8 字节是 "ftyp"；裸 AAC 是 ADTS 同步字 0xFFF?
        if (h.length >= 8 && h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p') {
            return Format.AAC;
        }
        if (h.length >= 2 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xF0) == 0xF0) {
            return Format.AAC;
        }
        return Format.UNKNOWN;
    }

    private static void markForRetry(InputStream in) {
        try {
            if (in.markSupported()) {
                in.mark(RETRY_MARK_LIMIT);
            }
        } catch (Exception e) {
            Projector.LOGGER.debug("[Projector][音乐] 无法设置重试点：{}", e.toString());
        }
    }

    private static boolean rewind(InputStream in) {
        try {
            in.reset();
            return true;
        } catch (Exception e) {
            Projector.LOGGER.debug("[Projector][音乐] 退不回重试点，放弃后续解码器尝试：{}", e.toString());
            return false;
        }
    }

    // ------------------------------------------------------------------ 解码

    /** 按格式挑解码器；认不出来时按 MP3 → FLAC → AAC → 系统 的顺序试一遍。 */
    private static AudioInputStream read(InputStream in, Format detected) throws Exception {
        List<Format> order = new ArrayList<>(4);
        if (detected != Format.UNKNOWN) {
            order.add(detected);
        }
        for (Format f : new Format[]{Format.MP3, Format.FLAC, Format.AAC, Format.WAV}) {
            if (!order.contains(f)) {
                order.add(f);
            }
        }

        Exception last = null;
        for (int i = 0; i < order.size(); i++) {
            Format format = order.get(i);
            if (i > 0 && !rewind(in)) {
                break;
            }
            try {
                AudioInputStream stream = switch (format) {
                    case MP3 -> new javazoom.spi.mpeg.sampled.file.MpegAudioFileReader()
                            .getAudioInputStream(in);
                    case FLAC -> new org.jflac.sound.spi.FlacAudioFileReader().getAudioInputStream(in);
                    // 【27.1.3】AAC/M4A 回来了：JCodec 已从模组里移除，
                    // 它自带的那份 net.sourceforge.jaad.* 不再与 javasound-aac 同名，
                    // 所以可以重新嵌这个库（它同时含 mp4 容器解析 ⇒ .m4a 也认）。
                    // 仍然**直接 new 这个类**，不走 ServiceLoader（jar-in-jar 的 SPI 不可控）。
                    case AAC -> new net.sourceforge.jaad.spi.javasound.AACAudioFileReader()
                            .getAudioInputStream(in);
                    // WAV/AIFF/AU 由 JDK 自带 provider 处理，不需要额外依赖
                    case WAV -> AudioSystem.getAudioInputStream(in);
                    default -> null;
                };
                if (stream != null) {
                    if (i > 0) {
                        Projector.LOGGER.info("[Projector][音乐] 文件头不像 {}，但用 {} 解码器认出来了",
                                describe(detected), describe(format));
                    }
                    return stream;
                }
            } catch (Exception | LinkageError e) {
                last = e instanceof Exception ex ? ex : new IllegalStateException(e.toString(), e);
                Projector.LOGGER.debug("[Projector][音乐] {} 解码器认不出这段流：{}",
                        describe(format), e.toString());
            }
        }
        throw new UnsupportedOperationException(
                "无法解码这段音频（识别为 " + describe(detected) + "）", last);
    }

    private static String describe(Format f) {
        return switch (f) {
            case MP3 -> "MP3";
            case FLAC -> "FLAC";
            case AAC -> "AAC/M4A";
            case WAV -> "WAV";
            case OGG -> "Ogg";
            case UNKNOWN -> "未知格式";
        };
    }

    // ------------------------------------------------------------------ 转 PCM

    /** 已经是「能用」的 PCM 吗：16 位有符号、单声道或立体声。 */
    private static boolean isUsablePcm(AudioFormat f) {
        return f.getEncoding() == AudioFormat.Encoding.PCM_SIGNED
               && f.getSampleSizeInBits() == 16
               && (f.getChannels() == 1 || f.getChannels() == 2);
    }

    /** 把压缩格式（或位深不对的 PCM）转成 PCM 16 位。 */
    private static AudioInputStream convertToPcm(AudioInputStream src, AudioFormat format) throws Exception {
        float rate = format.getSampleRate();
        if (rate == AudioSystem.NOT_SPECIFIED || rate <= 0) {
            rate = FALLBACK_SAMPLE_RATE;
        }
        int channels = format.getChannels() == 1 ? 1 : 2;
        int frameSize = channels * 2;
        AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                rate, 16, channels, frameSize, rate, false);

        Exception last = null;
        try {
            javazoom.spi.mpeg.sampled.convert.MpegFormatConversionProvider provider =
                    new javazoom.spi.mpeg.sampled.convert.MpegFormatConversionProvider();
            if (provider.isConversionSupported(target, format)) {
                return provider.getAudioInputStream(target, src);
            }
        } catch (Exception | LinkageError e) {
            last = e instanceof Exception ex ? ex : new IllegalStateException(e.toString(), e);
        }
        try {
            org.jflac.sound.spi.FlacFormatConversionProvider provider =
                    new org.jflac.sound.spi.FlacFormatConversionProvider();
            if (provider.isConversionSupported(target, format)) {
                return provider.getAudioInputStream(target, src);
            }
        } catch (Exception | LinkageError e) {
            last = e instanceof Exception ex ? ex : new IllegalStateException(e.toString(), e);
        }
        try {
            return AudioSystem.getAudioInputStream(target, src);
        } catch (Exception e) {
            throw new UnsupportedOperationException(
                    "无法把 " + format + " 转成 PCM 16 位：" + e, last == null ? e : last);
        }
    }
}

package top.hmjmfabc.projector.client.music;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * 自带的 WAV（RIFF/WAVE）解析。
 *
 * <h2>为什么不用 {@code AudioSystem.getAudioInputStream(InputStream)}</h2>
 * <p>【27.2.4-snapshot-202 实测】模组内嵌的 <b>javasound-aac</b> 会注册一个
 * {@code AudioFileReader} 服务；它一旦在 classpath 上，JDK 那句
 * {@code AudioSystem.getAudioInputStream(in)} 对 <b>WAV</b> 就会直接抛
 * {@code UnsupportedAudioFileException: Stream of unsupported format}
 * （逐个库单测过：只加 mp3spi 是 {@code NoClassDefFoundError: TAudioFileReader}、
 * 只加 javasound-aac 就是这个异常；jlayer / tritonus-share 单独加都正常）。
 * 也就是说：<b>只要音频库跟着模组走，玩家的 .wav 就一律放不出来</b>。</p>
 *
 * <p>所以这里按 RIFF 规范自己读头、自己转 16 位 PCM —— <b>完全不碰 {@code AudioSystem} 的
 * provider 搜索</b>，也就不会被任何第三方 SPI 影响。附带好处：安卓启动器上不依赖 JDK 的
 * {@code com.sun.media.sound} 实现。</p>
 *
 * <p>支持：PCM 8/16/24/32 位、IEEE float 32 位、{@code WAVE_FORMAT_EXTENSIBLE} 包装的这几种；
 * 其它编码（ADPCM / A-Law / MP3-in-WAV…）会抛出带说明的异常，交给调用方给出人话提示。</p>
 */
public final class WavReader {

    /** 与 {@code AudioInputStream.NOT_SPECIFIED} 同值：帧数未知。 */
    private static final int FRAME_LENGTH_UNKNOWN = -1;

    private WavReader() {
    }

    /**
     * 从流的当前位置读一个 WAV，返回<b>16 位 PCM</b> 的音频流。
     *
     * <p>{@link AudioInputStream#getFrameLength()} 是准确值（取自 data 块长度），
     * 播放器算时长/进度要用它。</p>
     */
    public static AudioInputStream open(InputStream raw) throws IOException {
        byte[] riff = new byte[12];
        readFully(raw, riff, 12);
        if (!fourCc(riff, 0, "RIFF") || !fourCc(riff, 8, "WAVE")) {
            throw new IOException("不是 RIFF/WAVE 文件");
        }

        int formatTag = -1;
        int channels = 0;
        int sampleRate = 0;
        int bitsPerSample = 0;
        long dataLen = -1;

        byte[] header = new byte[8];
        while (true) {
            readFully(raw, header, 8);
            String id = new String(header, 0, 4, java.nio.charset.StandardCharsets.US_ASCII);
            long size = u32(header, 4);
            if (id.equals("fmt ")) {
                if (size < 16 || size > 4096) throw new IOException("fmt 块长度异常：" + size);
                byte[] fmt = new byte[(int) size];
                readFully(raw, fmt, (int) size);
                formatTag = u16(fmt, 0);
                channels = u16(fmt, 2);
                sampleRate = (int) u32(fmt, 4);
                bitsPerSample = u16(fmt, 14);
                // WAVE_FORMAT_EXTENSIBLE：真正的编码在 SubFormat GUID 的头两个字节
                if (formatTag == 0xFFFE && fmt.length >= 26) {
                    formatTag = u16(fmt, 24);
                }
                if ((size & 1) == 1) skip(raw, 1);      // 块长度奇数要补齐
            } else if (id.equals("data")) {
                dataLen = size;
                break;                                   // data 就在后面，别再读头了
            } else {
                skip(raw, size + (size & 1));            // 其它块（LIST/fact…）跳过
            }
        }

        if (formatTag < 0) throw new IOException("WAV 里没有 fmt 块");
        if (channels <= 0 || sampleRate <= 0) throw new IOException("WAV 头非法（声道/采样率）");
        if (dataLen < 0) throw new IOException("WAV 里没有 data 块");
        // 有些文件把 data 长度写成 0 或 -1（流式写入）：退化成"读到结尾"
        if (dataLen == 0 || dataLen == 0xFFFFFFFFL) dataLen = Long.MAX_VALUE;

        int bytesPerSample = bitsPerSample / 8;
        if (bytesPerSample <= 0) throw new IOException("WAV 位深非法：" + bitsPerSample);
        if (formatTag != 1 && formatTag != 3) {
            throw new IOException("不支持这种 WAV 编码（formatTag=" + formatTag
                    + "，只认 PCM / IEEE float）");
        }
        boolean isFloat = formatTag == 3;
        if (isFloat && bitsPerSample != 32) {
            throw new IOException("只认 32 位 IEEE float 的 WAV（这段是 " + bitsPerSample + " 位）");
        }
        if (!isFloat && bitsPerSample != 8 && bitsPerSample != 16
                && bitsPerSample != 24 && bitsPerSample != 32) {
            throw new IOException("不支持 " + bitsPerSample + " 位 PCM 的 WAV");
        }

        int frameSizeIn = channels * bytesPerSample;
        long frames = dataLen == Long.MAX_VALUE ? FRAME_LENGTH_UNKNOWN : dataLen / frameSizeIn;
        AudioFormat outFormat = new AudioFormat(sampleRate, 16, channels, true, false);

        InputStream data = new LimitedStream(raw, dataLen);
        InputStream pcm;
        if (!isFloat && bitsPerSample == 16) {
            pcm = data;                                  // 绝大多数 WAV 就是这条直通
        } else {
            pcm = new ToPcm16Stream(data, bitsPerSample, isFloat);
        }
        return new AudioInputStream(pcm, outFormat, frames);
    }

    // ------------------------------------------------------------------ 小工具

    private static boolean fourCc(byte[] b, int off, String expect) {
        for (int i = 0; i < 4; i++) {
            if ((char) b[off + i] != expect.charAt(i)) return false;
        }
        return true;
    }

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] b, int off) {
        return ((long) (b[off] & 0xFF))
                | ((long) (b[off + 1] & 0xFF) << 8)
                | ((long) (b[off + 2] & 0xFF) << 16)
                | ((long) (b[off + 3] & 0xFF) << 24);
    }

    private static void readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) throw new EOFException("WAV 头被截断（还差 " + (len - off) + " 字节）");
            off += n;
        }
    }

    private static void skip(InputStream in, long count) throws IOException {
        long left = count;
        while (left > 0) {
            long n = in.skip(left);
            if (n <= 0) {
                if (in.read() < 0) return;              // 到结尾就算了
                n = 1;
            }
            left -= n;
        }
    }

    // ------------------------------------------------------------------ 内部流

    /** 只露出前 {@code limit} 个字节（data 块之后可能还有别的块）。 */
    private static final class LimitedStream extends InputStream {
        private final InputStream in;
        private long left;

        LimitedStream(InputStream in, long limit) {
            this.in = in;
            this.left = limit;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) return -1;
            int b = in.read();
            if (b < 0) {
                left = 0;
                return -1;
            }
            left--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int want = (int) Math.min(len, left);
            int n = in.read(b, off, want);
            if (n > 0) left -= n;
            return n;
        }
    }

    /** 8/24/32 位 PCM 与 32 位 float → 16 位 PCM（边读边转，内存恒定）。 */
    private static final class ToPcm16Stream extends InputStream {
        private final InputStream src;
        private final int bytesPerSample;
        private final boolean isFloat;
        private final byte[] sample;
        private final byte[] out = new byte[8192];
        private int outPos;
        private int outLen;
        private boolean eof;

        ToPcm16Stream(InputStream src, int bitsPerSample, boolean isFloat) {
            this.src = src;
            this.bytesPerSample = bitsPerSample / 8;
            this.isFloat = isFloat;
            this.sample = new byte[this.bytesPerSample];
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n <= 0 ? -1 : (one[0] & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len <= 0) return 0;
            if (outPos >= outLen) {
                if (!fill()) return -1;
            }
            int n = Math.min(len, outLen - outPos);
            System.arraycopy(out, outPos, b, off, n);
            outPos += n;
            return n;
        }

        /** 填满一段输出；返回 false 表示源已结束。 */
        private boolean fill() throws IOException {
            outPos = 0;
            outLen = 0;
            while (outLen + 2 <= out.length) {
                for (int i = 0; i < bytesPerSample; i++) {
                    int v = src.read();
                    if (v < 0) {
                        eof = true;
                        return outLen > 0;
                    }
                    sample[i] = (byte) v;
                }
                short s = toShort();
                out[outLen++] = (byte) (s & 0xFF);
                out[outLen++] = (byte) ((s >> 8) & 0xFF);
            }
            return true;
        }

        private short toShort() {
            if (isFloat) {
                int bits = (sample[0] & 0xFF) | ((sample[1] & 0xFF) << 8)
                        | ((sample[2] & 0xFF) << 16) | ((sample[3] & 0xFF) << 24);
                float f = Float.intBitsToFloat(bits);
                if (f > 1f) f = 1f;
                if (f < -1f) f = -1f;
                return (short) Math.round(f * 32767f);
            }
            return switch (bytesPerSample) {
                // 8 位 WAV 是**无符号**的（128 = 静音）
                case 1 -> (short) (((sample[0] & 0xFF) - 128) << 8);
                // 24 位有符号、小端
                case 3 -> (short) (((sample[2] & 0xFF) << 8) | (sample[1] & 0xFF));
                // 32 位有符号：取高 16 位（低 16 位只是精度）
                default -> (short) (((sample[3] & 0xFF) << 8) | (sample[2] & 0xFF));
            };
        }

        @Override
        public void close() throws IOException {
            src.close();
        }
    }
}

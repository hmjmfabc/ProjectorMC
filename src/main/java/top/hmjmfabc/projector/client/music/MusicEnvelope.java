package top.hmjmfabc.projector.client.music;

import java.util.Arrays;

/**
 * 音乐波形（「语音条」那种一长条竖线）。
 *
 * <p>波形数据由解码线程**边解码边填**：每 {@link #BUCKET_MS} 毫秒算一个振幅，
 * 所以一首歌放多少、波形就画多少；还没放到的地方用一条**由歌名决定的固定花纹**
 * 占位（否则没播放时整个控件是一片空白，很难看也不好点）。</p>
 */
public final class MusicEnvelope {
    /** 每个振幅格代表多少毫秒。 */
    public static final int BUCKET_MS = 250;
    /** 竖线数量上限（画布上的条数由控件宽度决定，这里只保证数据够用）。 */
    public static final int MAX_BUCKETS = 4096;

    private final byte[] mark;      // 0 = 还没解到，1 = 已有真实数据
    private final byte[] value;     // 0..100 的振幅
    private final byte[] placeholder;

    private MusicEnvelope(int buckets, long seed) {
        int n = Math.max(1, Math.min(MAX_BUCKETS, buckets));
        this.mark = new byte[n];
        this.value = new byte[n];
        this.placeholder = new byte[n];
        // 固定花纹：用「相邻格小幅变化」的方式造一条连续的起伏，看起来像真实波形
        long s = seed * 6364136223846793005L + 1442695040888963407L;
        int cur = 35 + (int) Math.abs(seed % 30);
        for (int i = 0; i < n; i++) {
            s = s * 6364136223846793005L + 1442695040888963407L;
            int step = (int) ((s >>> 33) % 23) - 11;
            cur = Math.max(12, Math.min(92, cur + step));
            placeholder[i] = (byte) cur;
        }
    }

    /** 按「歌名 + 时长」造一条占位波形。 */
    public static MusicEnvelope forTrack(String seedText, long durationMs) {
        long duration = durationMs > 0 ? durationMs : 180_000L;
        int buckets = (int) Math.max(8, Math.min(MAX_BUCKETS, duration / BUCKET_MS));
        String seedSource = seedText == null ? "" : seedText;
        long seed = seedSource.hashCode() + duration;
        if (seed < 0) {
            seed = -seed;
        }
        return new MusicEnvelope(buckets, seed);
    }

    public int bucketCount() {
        return mark.length;
    }

    /** 解码线程写入：某个时间点的振幅（0..100）。 */
    public synchronized void put(long atMs, int amplitude) {
        int i = (int) (atMs / BUCKET_MS);
        if (i < 0 || i >= mark.length) {
            return;
        }
        value[i] = (byte) Math.max(0, Math.min(100, amplitude));
        mark[i] = 1;
    }

    /** 取某个时间点的振幅：有真实数据用真实数据，否则用占位花纹。 */
    public synchronized int valueAt(long atMs) {
        int i = (int) (atMs / BUCKET_MS);
        if (i < 0) {
            i = 0;
        }
        if (i >= mark.length) {
            // 超出已知长度（时长不准时会发生）：用结尾那一格
            int last = mark.length - 1;
            return mark[last] != 0 ? value[last] & 0xFF : placeholder[last] & 0xFF;
        }
        return mark[i] != 0 ? value[i] & 0xFF : placeholder[i] & 0xFF;
    }

    /** 这一格是否已经有真实数据（用于把「已解码」和「占位」画成不同颜色）。 */
    public synchronized boolean isReal(long atMs) {
        int i = (int) (atMs / BUCKET_MS);
        return i >= 0 && i < mark.length && mark[i] != 0;
    }

    /** 已经解出真实数据的比例（0~1），用于诊断。 */
    public synchronized float filledRatio() {
        int n = 0;
        for (byte b : mark) {
            if (b != 0) {
                n++;
            }
        }
        return (float) n / mark.length;
    }

    /** 仅供测试：清空真实数据，回到纯占位状态。 */
    synchronized void resetForTest() {
        Arrays.fill(mark, (byte) 0);
        Arrays.fill(value, (byte) 0);
    }
}

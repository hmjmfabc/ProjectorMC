package top.hmjmfabc.projector.client.music;

import top.hmjmfabc.projector.Projector;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import net.minecraft.client.sounds.AudioStream;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/**
 * 交给 Minecraft 音频引擎播放的 PCM 流。
 *
 * <p>数据流：网络 / 本地文件 → 解码器 → PCM 16 位 → **解码线程**按固定大小的分片塞进队列
 * → 渲染线程（音频线程）调 {@link #read(int)} 取走 → OpenAL 缓冲。</p>
 *
 * <p>三个必须记住的约定（都是 MC 的 {@code com.mojang.blaze3d.audio.Channel} 决定的）：</p>
 * <ol>
 *   <li>{@code read} 在**音频线程**上被调用，所以**绝对不能阻塞**：没有数据就返回 {@code null}，
 *       MC 下一 tick 会再来问（这是参考实现 Net Music Mod 的关键设计，见 NOTICE）。</li>
 *   <li>MC 会一次要 1 秒的量、并且连要 4 次（{@code Channel.pumpBuffers(4)}），
 *       所以构造时**先预填一段**，免得刚 play 就因为没有缓冲而立刻「停止」。</li>
 *   <li>歌曲放完不会有 EOF 信号：MC 那边的表现是「队列空了、声道停了」。
 *       想知道放没放完，看 {@link #finished()}。</li>
 * </ol>
 */
public final class MusicAudioStream implements AudioStream {
    /**
     * 每个分片的时长（毫秒）。
     *
     * <p>**必须与 MC 的 AL 缓冲粒度一致**：{@code Channel.attachBufferStream} 会算出
     * {@code streamingBufferSize = 1 秒的 PCM}，并且一次 {@code pumpBuffers(4)} 连取 4 个
     * （= 4 秒）。分片比它小的话，一次 {@code read(1 秒)} 要拼好几片，
     * 第一片还没解码完就返回 null，MC 那边就有槽位是空的（见 {@link #PREFILL_CHUNKS}）。</p>
     */
    private static final int CHUNK_MS = 1000;
    /** 最多缓存多少分片（≈8 秒 ≈1.5 MB @48k 立体声），超过就让解码线程等着。 */
    private static final int MAX_QUEUED_CHUNKS = 8;
    /**
     * 构造时先预填几个分片。
     *
     * <p>⚠【27.1.1 实测翻车点】这里原来是 5 × 200ms = **1 秒**，而 MC 一上来就要
     * {@code pumpBuffers(4)} 次、每次 1 秒 —— 于是 4 个 AL 槽位只填满 1 个，
     * 1 秒后源就没缓冲了 ⇒ {@code AL_STOPPED} ⇒ 引擎回收声道 ⇒ 「播一秒、循环几个音」。
     * **预填必须 ≥ 4 秒**（这里给 5 秒，多一片留给解码线程起步）。</p>
     */
    private static final int PREFILL_CHUNKS = 5;
    /** 播放途中队列暂时空了时，最多等这么久再返回 null（避免一次抖动就断流）。 */
    /**
     * 【27.2.4-200】原先 60 ms。这个 await 发生在**声音引擎线程**（{@code Channel.pumpBuffers}）上，
     * 一次声音 tick 可能连续要 4 个缓冲 ⇒ 最坏拖住 240 ms，**整个游戏的声音**都跟着卡。
     * 压到 2 ms：够吃掉同一 tick 内的抖动，又不会影响其它声道。
     */
    private static final long READ_WAIT_MS = 2L;

    private final AudioInputStream pcm;
    private final AudioFormat format;
    private final int frameSize;
    private final int chunkBytes;
    private final long bytesPerSecond;
    private final MusicEnvelope envelope;
    private final long sourceDurationMs;
    private final String label;

    private final ArrayDeque<ByteBuffer> queue = new ArrayDeque<>();
    private final ArrayDeque<ByteBuffer> pool = new ArrayDeque<>();
    private final Object lock = new Object();
    private Thread pump;

    /** 解码线程待丢弃的字节数（流内快进用；{@code seekForwardTo} 只负责写下它）。 */
    private volatile long pendingSkipBytes;

    private boolean closed;
    private boolean eof;
    private volatile Throwable failure;

    private long decodedBytes;   // 已解码（含被跳过的）
    private long handedBytes;    // 已交给 MC
    private int queuedBytes;

    private MusicAudioStream(AudioInputStream pcm, MusicEnvelope envelope, long skipMs, String label)
            throws java.io.IOException {
        this.pcm = pcm;
        this.format = pcm.getFormat();
        this.frameSize = Math.max(1, format.getFrameSize());
        float rate = format.getSampleRate() > 0 ? format.getSampleRate() : 44100f;
        this.bytesPerSecond = (long) (this.frameSize * rate);
        this.chunkBytes = Math.max(this.frameSize, (int) (this.bytesPerSecond * CHUNK_MS / 1000L));
        this.envelope = envelope;
        this.label = label;
        this.sourceDurationMs = AudioDecoder.probeDurationMs(pcm, byteLengthOf(pcm));

        prefill(skipMs);
        if (queue.isEmpty()) {
            // 一片数据都没拿到就开播 = 声道会被引擎立刻回收（空源 = AL_STOPPED），
            // 然后「续播」逻辑会一遍遍重来、一遍遍重新下载。宁可在这里明确失败。
            close();
            throw new java.io.IOException("音频数据没有到达（网络太慢或地址失效）：" + label);
        }

        this.pump = new Thread(this::pumpLoop, "Projector-Music-Decode");
        this.pump.setDaemon(true);
        this.pump.setPriority(Thread.NORM_PRIORITY - 1);
        this.pump.start();
    }

    /**
     * 打开一首歌。
     *
     * @param track  歌曲（本地文件或远端地址）
     * @param env    波形数据（解码时边解边填）
     * @param skipMs 从第几毫秒开始播（用于「断流后接着放」）
     */
    public static MusicAudioStream open(MusicTrack track, MusicEnvelope env, long skipMs) throws Exception {
        InputStream raw = track.openStream();
        AudioInputStream decoded = AudioDecoder.open(raw);
        Projector.LOGGER.info("[Projector][音乐] 开始解码 {}：{}（{} Hz {} 声道 {} 位）",
                track.displayName(), track.describeSource(),
                (int) decoded.getFormat().getSampleRate(), decoded.getFormat().getChannels(),
                decoded.getFormat().getSampleSizeInBits());
        return new MusicAudioStream(decoded, env, skipMs, track.displayName());
    }

    // ---------------------------------------------------------------- 预填

    private void prefill(long skipMs) {
        long skipBytes = skipMs > 0 ? skipMs * bytesPerSecond / 1000L : 0L;
        skipBytes -= skipBytes % frameSize;
        long startAt = System.currentTimeMillis();
        while (queue.size() < PREFILL_CHUNKS && !eof) {
            if (!readChunk(skipBytes > 0 ? skipBytes - decodedBytes : 0L)) {
                break;
            }
            // 预填不该把界面卡住：但只要已经拿到 2 片（够 AL 槽位周转）就可以先开始
            if (queue.size() >= 2 && System.currentTimeMillis() - startAt > 6000L) {
                Projector.LOGGER.info("[Projector][音乐] 预填 6 秒内拿到 {} 片，先开始播放（{}）",
                        queue.size(), label);
                break;
            }
        }
        long prefilled = queuedBytes;
        Projector.LOGGER.info("[Projector][音乐] 预填 {} 片 / {}ms（跳过快进 {}ms）：{}",
                queue.size(), prefilled * 1000L / Math.max(1L, bytesPerSecond), skipMs, label);
    }

    // ---------------------------------------------------------------- 解码线程

    private void pumpLoop() {
        while (true) {
            synchronized (lock) {
                while (!closed && queuedBytes >= chunkBytes * MAX_QUEUED_CHUNKS) {
                    try {
                        lock.wait(200L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (closed) {
                    return;
                }
            }
            if (eof) {
                // 解码完了：队列里的放完就结束，线程退出
                return;
            }
            try {
                // 【27.2.4-200】流内快进：由本线程真的去丢字节（见 seekForwardTo 的注释）
                long skip = pendingSkipBytes;
                if (skip > 0L) {
                    pendingSkipBytes = 0L;
                    if (!skipBytesOnPump(skip)) {
                        eof = true;
                        return;
                    }
                    continue;
                }
                if (!readChunk(0L)) {
                    eof = true;
                }
            } catch (Throwable t) {
                failure = t;
                eof = true;
                Projector.LOGGER.warn("[Projector][音乐] 解码中断（{}）：{}", label, t.toString());
                return;
            }
        }
    }

    /**
     * 读一片 PCM 进队列。
     *
     * @param discard 还要丢弃多少字节（断点续播时的快进；丢弃期间照样算波形）
     * @return 是否读到了数据（false = 文件读完）
     */
    private boolean readChunk(long discard) {
        byte[] buf = new byte[chunkBytes];
        int filled = 0;
        boolean sawData = false;
        while (filled < buf.length) {
            int n;
            try {
                n = pcm.read(buf, filled, buf.length - filled);
            } catch (Exception e) {
                failure = e;
                return sawData;
            }
            if (n < 0) {
                break;
            }
            sawData = true;
            filled += n;
        }
        if (filled == 0) {
            return false;
        }
        int usable = filled - (filled % frameSize);
        if (usable <= 0) {
            return sawData;
        }

        long atMs = decodedBytes * 1000L / bytesPerSecond;
        if (discard > 0) {
            // 快进：这一段只用来补波形，不进队列
            envelope.put(atMs, amplitude(buf, usable, frameSize));
            decodedBytes += usable;
            return true;
        }

        envelope.put(atMs, amplitude(buf, usable, frameSize));
        ByteBuffer dst = takeBuffer(usable);
        dst.clear();
        dst.put(buf, 0, usable);
        dst.flip();
        synchronized (lock) {
            queue.addLast(dst);
            queuedBytes += usable;
            lock.notifyAll();
        }
        decodedBytes += usable;
        return true;
    }

    /**
     * 从解码流反推「源文件总字节数」：本地文件能拿到真实大小，
     * 远端流拿不到就返回 0（那时只能靠格式属性/帧数）。
     */
    private static long byteLengthOf(AudioInputStream pcm) {
        try {
            // PCM 的目标格式与源时长无关，这里用「原始字节数」估不出来时返回 0
            return pcm.getFormat().getProperty("mp3.length.bytes") instanceof Number n
                    ? n.longValue() : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 算一片 PCM 的振幅（0..100）：取绝对值峰值，再开方压一下动态范围。 */
    static int amplitude(byte[] pcm, int length, int frameSize) {
        int peak = 0;
        for (int i = 0; i + 1 < length; i += 2) {
            int sample = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            int abs = sample < 0 ? -sample : sample;
            if (abs > peak) {
                peak = abs;
            }
        }
        if (peak == 0) {
            return 0;
        }
        double norm = Math.sqrt(peak / 32768.0);
        return (int) Math.round(norm * 100.0);
    }

    private ByteBuffer takeBuffer(int capacity) {
        synchronized (lock) {
            ByteBuffer cached = pool.pollFirst();
            if (cached != null && cached.capacity() >= capacity) {
                return cached;
            }
        }
        return ByteBuffer.allocateDirect(Math.max(capacity, chunkBytes));
    }

    // ---------------------------------------------------------------- 音频线程

    @Override
    public AudioFormat getFormat() {
        return format;
    }

    @Override
    public ByteBuffer read(int size) {
        synchronized (lock) {
            if (queue.isEmpty()) {
                // 播放途中（已经交过至少一个缓冲）遇到瞬时空队列时等一下下：
                // 返回 null 会让 MC 那边多留一个空槽位，抖动几次就可能 AL_STOPPED
                if (!eof && handedBytes > 0 && !closed) {
                    try {
                        lock.wait(READ_WAIT_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
                if (queue.isEmpty()) {
                    return null;
                }
            }
            int want = size <= 0 ? chunkBytes : size;
            ByteBuffer out = takeBufferLocked(want);
            out.clear();
            while (out.remaining() > 0 && !queue.isEmpty()) {
                ByteBuffer head = queue.peekFirst();
                int n = Math.min(out.remaining(), head.remaining());
                int oldLimit = head.limit();
                head.limit(head.position() + n);
                out.put(head);
                head.limit(oldLimit);
                if (!head.hasRemaining()) {
                    queue.pollFirst();
                }
            }
            out.flip();
            if (out.remaining() == 0) {
                return null;
            }
            handedBytes += out.remaining();
            queuedBytes = Math.max(0, queuedBytes - out.remaining());
            lock.notifyAll();
            return out;
        }
    }

    private ByteBuffer takeBufferLocked(int capacity) {
        ByteBuffer cached = pool.pollFirst();
        if (cached != null && cached.capacity() >= capacity) {
            return cached;
        }
        return ByteBuffer.allocateDirect(Math.max(capacity, chunkBytes));
    }

    // ---------------------------------------------------------------- 流内快进（不重开流）

    /**
     * 【27.2.3-hotfix-142】把**已经打开的这条流**快进到 {@code targetMs}，不重新联网、不重建解码器。
     *
     * <h2>为什么要有它（用户实测：拖进度条/暂停继续要卡好几秒）</h2>
     * 以前每一次跳进度都是 {@code MusicPlayer.play(...)} ⇒ 一整段重开：
     * ①网易云的播放地址要重新解析（一次 HTTPS）；②重新建连接（{@code ChunkedAudioStream}）；
     * ③新的解码器 + 新的预填（{@code prefill} 要等 1 秒分片）——
     * 三样加起来就是玩家感觉到的「卡好几秒」。而**参考实现（网络音乐机）是流内定位**，
     * 也就是这里做的这件事：接着往下解，把不要的样本丢掉。
     *
     * <h2>为什么只支持「往前」</h2>
     * 底层是 HTTP Range 顺序流：往前跳只是「多解一会儿再丢」，几乎瞬时的；
     * 往回跳则必须重新建连接（服务端不会倒着发），那种情况调用方仍然走重开流。
     * 所以这里返回 {@code false} 让调用方回退，语义是「这次快进没做成，你去重开吧」。
     *
     * <p>线程：由客户端（游戏线程）调用；数据只在 {@code lock} 里动，与解码线程互斥。</p>
     *
     * @param targetMs 目标位置（毫秒，相对歌曲开头）
     * @return true = 已在本流内定位；false = 做不到（已关闭 / 目标在后 / 差的太远 / 出错了）
     */
    public boolean seekForwardTo(long targetMs) {
        synchronized (lock) {
            if (closed || eof) return false;
            long nowMs = decodedBytes * 1000L / bytesPerSecond;
            if (targetMs <= nowMs) return false;              // 往后跳：交给调用方重开流
            long skipBytes = targetMs * bytesPerSecond / 1000L - decodedBytes;
            skipBytes -= skipBytes % frameSize;
            if (skipBytes <= 0) return false;

            // 丢掉队列里的旧数据：它们已经在目标位置之前了。
            // ⚠ 不能走 recycle()（那个会动 queuedBytes 的账），这里手动归还缓冲，
            //   并把 queuedBytes 明确置 0 —— 否则账目错位 ⇒ 后续「还能塞多少」算错，
            //   轻则卡顿重则永远读不到数据。
            while (!queue.isEmpty()) {
                ByteBuffer old = queue.pollFirst();
                if (pool.size() < 8) {
                    old.clear();
                    pool.addLast(old);
                }
            }
            queuedBytes = 0;

            // 【27.2.4-200 修】**绝不能拿着 lock 做 pcm.read**：那是网络读，可能几十秒，
            // 而 read(int)（声音引擎线程）要同一把锁 ⇒ 一次拖动会卡住渲染线程 + 全游戏声音。
            // 现在只把「要丢多少字节」记下来、叫醒解码线程，本方法立即返回。
            pendingSkipBytes = skipBytes;
            lock.notifyAll();
            return true;
        }
    }

    /**
     * 【27.2.4-200】在**解码线程**上丢弃 {@code toSkip} 字节（流内快进）。
     *
     * <p>全程在 pump 线程上分片读，**不持锁**，所以声音引擎线程照常取数据；
     * 期间照常把振幅填进波形，保证波形与音频位置一致。</p>
     *
     * @return true = 丢够了；false = 读失败或已到结尾
     */
    private boolean skipBytesOnPump(long toSkip) {
        byte[] scratch = null;
        long remaining = toSkip;
        while (remaining > 0L && !closed) {
            int want = (int) Math.min(remaining, 1 << 16);
            if (scratch == null || scratch.length < want) {
                scratch = new byte[want];
            }
            int n;
            try {
                n = pcm.read(scratch, 0, want);
            } catch (Exception e) {
                this.failure = e;
                return false;
            }
            if (n <= 0) {
                return false;                                 // 到结尾了
            }
            int usable = n - (n % frameSize);
            if (usable <= 0) {
                continue;
            }
            long atMs = decodedBytes * 1000L / bytesPerSecond;
            envelope.put(atMs, amplitude(scratch, usable, frameSize));
            decodedBytes += usable;
            remaining -= usable;
        }
        Projector.LOGGER.info("[Projector][音乐] 流内快进完成：解码到 {}ms（没有重开流，{}）",
                decodedBytes * 1000L / bytesPerSecond, label);
        return remaining <= 0L;
    }

    // ---------------------------------------------------------------- 状态

    /** 已解码到第几毫秒（含被快进跳过的部分）。 */
    public long decodedMs() {
        return decodedBytes * 1000L / bytesPerSecond;
    }

    /** 已经交给 MC 播放到第几毫秒（写界面用）。 */
    public long playedMs() {
        long pending = Math.max(0, handedBytes - queuedBytes);
        return pending * 1000L / bytesPerSecond;
    }

    /** 音频总长（毫秒）；解不出来时返回 0。 */
    public long sourceDurationMs() {
        return sourceDurationMs;
    }

    public MusicEnvelope envelope() {
        return envelope;
    }

    /** 放完了（数据发完 + 队列清空）。 */
    public boolean finished() {
        return eof && queuedBytes <= 0;
    }

    /** 诊断用的一行字。 */
    public String describeState() {
        return "解码=" + decodedMs() + "ms 送出=" + playedMs() + "ms 队列=" + queuedBytes + "B"
               + (eof ? " 已读完" : "") + (failure != null ? " 出错=" + failure : "");
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            queue.clear();
            queuedBytes = 0;
            lock.notifyAll();
        }
        if (pump != null) {
            pump.interrupt();
        }
        try {
            pcm.close();
        } catch (Exception e) {
            Projector.LOGGER.debug("[Projector][音乐] 关闭解码流失败：{}", e.toString());
        }
    }
}

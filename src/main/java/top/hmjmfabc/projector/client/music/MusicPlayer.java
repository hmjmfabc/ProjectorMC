package top.hmjmfabc.projector.client.music;

import com.mojang.blaze3d.audio.Channel;
import com.mojang.blaze3d.audio.Library;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import top.hmjmfabc.projector.Projector;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 本客户端的音乐播放器：把一条 PCM 流挂到游戏的音频引擎上。
 *
 * <p><b>为什么要自己借声道</b>：MC 1.21.1 的音频引擎只认「资源包里的 Ogg」——
 * {@code SoundEngine.play} 是从 {@code ResourceLocation} 去读文件的，没有
 * 「自定义音频流」的钩子（参考实现 Net Music Mod 用的是 {@code SoundInstance#getStream}，
 * 那个方法在 1.21.1 上**并不存在**）。所以这里直接向引擎借一条声道
 * （AccessTransformer 打开了 {@code SoundEngine.channelAccess} 与 {@code SoundEngine.library}），
 * 再把我们自己的 {@link MusicAudioStream} 挂上去 —— 用的正是引擎给「流式 Ogg」准备的
 * {@code Channel.attachBufferStream} 通路，因此：
 * 缓冲、距离衰减、丢帧策略全由引擎负责，我们只管喂 PCM。</p>
 *
 * <p>线程约定：所有 OpenAL 调用都通过 {@code ChannelHandle.execute} 派发到**音频线程**，
 * 我们自己绝不在渲染线程直接碰声道；音频解码在 {@code Projector-Music-*} 线程上做。</p>
 */
public final class MusicPlayer {
    public enum State {
        /** 没在放。 */
        IDLE,
        /** 正在下载/解码（还没出声）。 */
        LOADING,
        PLAYING,
        PAUSED,
        /** 加载或播放出错，{@link #error()} 里有原因。 */
        FAILED
    }

    private static volatile MusicPlayer instance;

    private final AtomicLong loadToken = new AtomicLong();

    private volatile State state = State.IDLE;
    private volatile String error = "";
    private volatile String ownerKey = "";
    private volatile MusicTrack track;
    private volatile MusicEnvelope envelope;
    private volatile MusicAudioStream stream;
    private volatile ChannelAccess.ChannelHandle handle;
    private volatile boolean stopping;

    /**
     * 控件位置与「正在放的位置」差多少就算「进度被改过」，需要重新起一段。
     *
     * <p>两侧都是墙钟推算，正常播放时误差只有重启那一下的几十毫秒，
     * 1.5 秒足够区分「正常漂移」和「玩家点了波形条调进度」。</p>
     */
    public static final long SEEK_TOLERANCE_MS = 1500L;

    /** 控件要求的位置和当前播放位置对不上（= 玩家调了进度）吗？ */
    public static boolean shouldResync(long wantMs, long haveMs) {
        return Math.abs(wantMs - haveMs) > SEEK_TOLERANCE_MS;
    }

    /** 同一首歌最多自动续播几次：防止「网络一直不通」时无限重连重下。 */
    private static final int MAX_RESUME = 3;
    private int resumes;
    /** 连续失败次数（由 {@link MusicManager} 决定还要不要再试）。 */
    private int attempts;

    /**
     * 当前这一段音频从第几毫秒开始放（续播时就是续播起点）。
     *
     * <p>【为什么不用「已交给 MC 的字节数」估位置】那个数字在开播瞬间就跳到 4 秒
     * （MC 一口气预取 4 个 1 秒缓冲），和**玩家听到的进度**差好几秒 ——
     * 拿它当续播起点就会反复回到同一个错位置（27.1.1 实测：播一秒、循环同一小段）。
     * 这里改用墙钟：段落起点 + 真实经过时间 − 累计暂停。</p>
     */
    private long segmentStartMs;
    private long segmentStartNanos;
    private long pausedNanos;
    private long pausedAtNanos;

    private double volume = 0.8;
    private float attenuation = 32f;
    private Vec3 position = Vec3.ZERO;


    private MusicPlayer() {
    }

    public static MusicPlayer get() {
        MusicPlayer local = instance;
        if (local == null) {
            synchronized (MusicPlayer.class) {
                local = instance;
                if (local == null) {
                    local = new MusicPlayer();
                    instance = local;
                }
            }
        }
        return local;
    }

    // ---------------------------------------------------------------- 播放控制

    /**
     * 开始播放（会先停掉当前这一首）。
     *
     * @param ownerKey    发起播放的控件（{@code 平面ID#控件序号}），用于「同一个控件再次点击 = 暂停」
     * @param track       歌曲
     * @param position    世界坐标（决定声音从哪来）
     * @param attenuation 多远之后听不见（方块）
     * @param volume      音量倍率（0~1）
     * @param startMs     从第几毫秒开始（断流续播用）
     * @param envelope    波形数据（由 {@code MusicManager} 持有，暂停后仍要保留）
     */
    public void play(String ownerKey, MusicTrack track, Vec3 position, float attenuation,
                     double volume, long startMs, MusicEnvelope envelope) {
        this.attempts = 0;
        this.resumes = 0;
        startSegment(ownerKey, track, position, attenuation, volume, startMs, envelope);
    }

    /** 内部续播：**不重置** attempts/resumes，否则「上限」形同虚设（27.1.1 实测过）。 */
    private void resumeInternal(String ownerKey, MusicTrack track, Vec3 position, float attenuation,
                                double volume, long startMs, MusicEnvelope envelope) {
        startSegment(ownerKey, track, position, attenuation, volume, startMs, envelope);
    }

    private void startSegment(String ownerKey, MusicTrack track, Vec3 position, float attenuation,
                              double volume, long startMs, MusicEnvelope envelope) {
        stop();
        this.envelope = envelope;
        this.segmentStartMs = Math.max(0L, startMs);
        this.segmentStartNanos = System.nanoTime();
        this.pausedNanos = 0L;
        this.pausedAtNanos = 0L;
        long token = loadToken.incrementAndGet();
        this.ownerKey = ownerKey;
        this.track = track;
        this.position = position;
        this.attenuation = attenuation;
        this.volume = volume;
        this.error = "";
        this.state = State.LOADING;
        this.resumes = 0;

        Thread loader = new Thread(() -> loadAndStart(token, track, startMs), "Projector-Music-Load");
        loader.setDaemon(true);
        loader.setPriority(Thread.NORM_PRIORITY - 1);
        loader.start();
    }

    private void loadAndStart(long token, MusicTrack track, long startMs) {
        MusicEnvelope env = envelope != null ? envelope
                : MusicEnvelope.forTrack(track.displayName(),
                        track.durationMs() > 0 ? track.durationMs() : 180_000L);
        try {
            MusicAudioStream opened = MusicAudioStream.open(track, env, startMs);
            if (token != loadToken.get()) {
                opened.close();
                return;
            }
            this.envelope = env;
            this.stream = opened;
            attachChannel(opened);
            this.state = State.PLAYING;
                Projector.LOGGER.info("[Projector][音乐] 开始播放 {}（{}），起点 {}ms",
                    track.displayName(), track.describeSource(), startMs);
        } catch (Throwable t) {
            if (token != loadToken.get()) {
                return;
            }
            this.state = State.FAILED;
            this.attempts++;
            this.error = friendlyError(t);
            Projector.LOGGER.warn("[Projector][音乐] 播放失败 {}：{}", track.describeSource(), t.toString());
            }
    }

    private static String friendlyError(Throwable t) {
        String message = t.getMessage();
        if (message == null || message.isBlank()) {
            message = t.getClass().getSimpleName();
        }
        return message.length() > 120 ? message.substring(0, 120) + "…" : message;
    }

    /** 向引擎借声道并挂上音频流（可以不在主线程调用，引擎内部会派发到音频线程）。 */
    private void attachChannel(MusicAudioStream audioStream) {
        Minecraft minecraft = Minecraft.getInstance();
        ChannelAccess channelAccess = minecraft.getSoundManager().soundEngine.channelAccess;
        Library library = minecraft.getSoundManager().soundEngine.library;
        float gain = (float) (volume * minecraft.options.getSoundSourceVolume(SoundSource.RECORDS));
        Vec3 at = position;
        float range = attenuation;
        channelAccess.createHandle(Library.Pool.STREAMING).thenAccept(created -> {
            if (stopping || stream != audioStream) {
                created.execute(Channel::stop);
                return;
            }
            this.handle = created;
            created.execute(channel -> {
                channel.setRelative(false);
                channel.setSelfPosition(at);
                float currentGain = (float) (volume
                        * Minecraft.getInstance().options.getSoundSourceVolume(SoundSource.RECORDS));
                channel.setVolume(currentGain <= 0f ? 0.0001f : currentGain);
                channel.linearAttenuation(range);
                channel.attachBufferStream(audioStream);
                channel.play();
            });
        });
    }

    /** 暂停（保留进度，下一次 {@link #resume()} 从原处继续）。 */
    public void pause() {
        ChannelAccess.ChannelHandle current = handle;
        if (current != null) {
            current.execute(Channel::pause);
        }
        if (state == State.PLAYING) {
            state = State.PAUSED;
            pausedAtNanos = System.nanoTime();
        }
    }

    /** 继续播放（引擎那边暂停过的声道直接 unpause）。 */
    public void resume() {
        ChannelAccess.ChannelHandle current = handle;
        if (current != null && stream != null) {
            if (stream.finished() || current.isStopped()) {
                // 声道已经被回收：从「听到的地方」重新起一条
                long from = currentPositionMs();
                MusicTrack t = track;
                MusicEnvelope env = envelope;
                stop();
                if (t != null) {
                    resumeInternal(ownerKey, t, position, attenuation, volume, from, env);
                }
                return;
            }
            current.execute(Channel::unpause);
            if (pausedAtNanos > 0L) {
                pausedNanos += System.nanoTime() - pausedAtNanos;
                pausedAtNanos = 0L;
            }
            state = State.PLAYING;
        }
    }

    /** 完全停止并释放声道。 */
    public void stop() {
        stopping = true;
        loadToken.incrementAndGet();
        ChannelAccess.ChannelHandle current = handle;
        handle = null;
        if (current != null) {
            current.execute(Channel::stop);
        }
        MusicAudioStream currentStream = stream;
        stream = null;
        if (currentStream != null) {
            currentStream.close();
        }
        stopping = false;
        if (state != State.IDLE || !error.isEmpty()) {
            state = State.IDLE;
            error = "";
            }
    }

    // ---------------------------------------------------------------- 每 tick

    /**
     * 客户端每 tick 调用：收尾、断流自愈、音量跟随设置。
     *
     * <p>「断流自愈」是必要的：MC 的 {@code ChannelAccess} 一旦发现声道处于停止状态
     * 就会把它回收（音乐还在路上、或者网络卡了几秒都会触发），
     * 这时只能重新借一条声道、从**已经播到的位置**接着放。</p>
     */
    public void tick() {
        MusicAudioStream current = stream;
        if (current == null) {
            return;
        }
        if (current.finished()) {
            Projector.LOGGER.info("[Projector][音乐] 播放结束：{}",
                    track == null ? "?" : track.displayName());
            stop();
            return;
        }
        ChannelAccess.ChannelHandle currentHandle = handle;
        if (currentHandle != null && currentHandle.isStopped() && !stopping) {
            if (resumes >= MAX_RESUME || current.finished()) {
                Projector.LOGGER.warn("[Projector][音乐] 声道反复被回收（续播 {} 次，听到 {}ms），放弃这一首：{}",
                        resumes, currentPositionMs(), track == null ? "?" : track.displayName());
                state = State.FAILED;
                error = "播放反复中断";
                stop();
                return;
            }
            resumes++;
            long from = Math.max(0L, currentPositionMs());
            Projector.LOGGER.info("[Projector][音乐] 声道被引擎回收（听到 {}ms），第 {} 次从该处续播",
                    from, resumes);
            MusicTrack t = track;
            if (t == null) {
                stop();
                return;
            }
            String key = ownerKey;
            double vol = volume;
            float range = attenuation;
            Vec3 at = position;
            MusicEnvelope env = envelope;
            stop();
            resumeInternal(key, t, at, range, vol, from, env);
            return;
        }
    }

    // ---------------------------------------------------------------- 参数

    /** 音量（0~1），立即生效。 */
    public void setVolume(double value) {
        this.volume = Math.max(0, Math.min(1, value));
        adjustGain();
    }

    /** 声源位置（控件在世界的中心）。 */
    public void setPosition(Vec3 pos) {
        this.position = pos;
        ChannelAccess.ChannelHandle current = handle;
        if (current != null) {
            current.execute(channel -> channel.setSelfPosition(pos));
        }
    }

    /** 衰减距离（多远听不见）。 */
    public void setAttenuation(float range) {
        this.attenuation = Math.max(1f, range);
        ChannelAccess.ChannelHandle current = handle;
        if (current != null) {
            current.execute(channel -> channel.linearAttenuation(this.attenuation));
        }
    }

    private void adjustGain() {
        ChannelAccess.ChannelHandle current = handle;
        if (current == null) {
            return;
        }
        float gain = (float) (volume
                * Minecraft.getInstance().options.getSoundSourceVolume(SoundSource.RECORDS));
        current.execute(channel -> channel.setVolume(gain <= 0f ? 0.0001f : gain));
    }

    // ---------------------------------------------------------------- 查询

    public State state() {
        return state;
    }

    public String error() {
        return error;
    }

    /** 连续失败了几次（{@link MusicManager} 用它决定还要不要再试）。 */
    public int attempts() {
        return attempts;
    }

    public boolean isActive() {
        return state == State.PLAYING || state == State.PAUSED || state == State.LOADING;
    }

    public String ownerKey() {
        return ownerKey;
    }

    public MusicTrack track() {
        return track;
    }

    public MusicEnvelope envelope() {
        return envelope;
    }

    /** 解码出来的真实时长（毫秒）；还没解出来时为 0。 */
    public long decodedDurationMs() {
        MusicAudioStream current = stream;
        return current == null ? 0L : current.sourceDurationMs();
    }

    public MusicAudioStream stream() {
        return stream;
    }

    /**
     * 已经播到第几毫秒（**玩家听到的进度**）。
     *
     * <p>墙钟计算：段落起点 + 真实经过时间 − 累计暂停。开播瞬间就是起点值，
     * 续播也从「刚才听到的地方」接着放。</p>
     */
    public long currentPositionMs() {
        if (segmentStartNanos == 0L) {
            return segmentStartMs;
        }
        long now = System.nanoTime();
        long paused = pausedNanos + (pausedAtNanos > 0L ? now - pausedAtNanos : 0L);
        long elapsedMs = (now - segmentStartNanos - paused) / 1_000_000L;
        long pos = segmentStartMs + Math.max(0L, elapsedMs);
        long duration = durationMs();
        return duration > 0 ? Math.min(pos, duration) : pos;
    }

    /** 音频总长（毫秒），未知为 0。 */
    public long durationMs() {
        MusicAudioStream current = stream;
        long fromStream = current == null ? 0L : current.sourceDurationMs();
        if (fromStream > 0) {
            return fromStream;
        }
        MusicTrack t = track;
        return t == null ? 0L : t.durationMs();
    }

    /** 诊断用的一行字（放进卡顿日志/登录日志里）。 */
    public String describeState() {
        MusicAudioStream current = stream;
        return state + (attempts > 0 ? "x" + attempts : "")
               + (ownerKey.isEmpty() ? "" : "@" + ownerKey)
               + (track == null ? "" : " " + track.displayName())
               + "｜听到=" + currentPositionMs() + "ms"
               + (current == null ? "" : "｜" + current.describeState())
               + (error.isEmpty() ? "" : "｜错误=" + error);
    }

}

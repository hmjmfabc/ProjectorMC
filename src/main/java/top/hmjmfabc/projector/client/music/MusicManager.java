package top.hmjmfabc.projector.client.music;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import top.hmjmfabc.projector.Projector;
import top.hmjmfabc.projector.ProjectorConfig;
import top.hmjmfabc.projector.client.PlaneCache;
import top.hmjmfabc.projector.common.Plane;
import top.hmjmfabc.projector.common.widget.MusicWidget;
import top.hmjmfabc.projector.network.Payloads;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 音乐控件的客户端总管：决定「现在该放哪一首」，并维护波形与歌词缓存。
 *
 * <p>规则（界面看到的表现必须与这里一致）：</p>
 * <ol>
 *   <li>控件的 {@code playing} 是**服务端权威**的，所有客户端各自据此算出「现在放到第几毫秒」；</li>
 *   <li>只有**离得够近**的客户端才真的去下载/解码（{@code music.hearDistance}），
 *       远处的客户端只显示状态、不产生任何流量；</li>
 *   <li>同时最多放 {@code music.maxConcurrent} 首（默认 1）：多个控件同时在放时，
 *       只有**最早开始的那个**出声，免得糊成一团；</li>
 *   <li>点播放键 = 发一条 {@code WidgetAction("toggle")}；本地**先乐观翻转**（点下去立刻有反馈），
 *       服务端翻转并广播；被拒时服务端会回灌真实状态，我们的乐观改动会被纠正。</li>
 * </ol>
 *
 * <p>⚠ 所有方法都要求传入 {@link Plane}：`ownerKey` 直接用「平面 id + 控件 id」拼出来，
 * **绝不靠对象身份去找归属**（本项目在这上面栽过三次）。</p>
 */
public final class MusicManager {
    private MusicManager() {
    }

    /** 波形：按控件 id 存。暂停/停止后仍然保留，否则波形会「重新长一遍」。 */
    private static final Map<UUID, MusicEnvelope> ENVELOPES = new ConcurrentHashMap<>();
    /** 歌词：按「来源|键」存，同一首歌的多个控件共用。 */
    private static final Map<String, LyricRecord> LYRICS = new ConcurrentHashMap<>();
    private static final Set<String> LYRIC_FETCHING = ConcurrentHashMap.newKeySet();
    /** 上一次播报的播放状态：只在真的变了才打日志。 */
    private static String lastReport = "";
    /** 起播冷却：同一首歌两次起播至少隔这么久（防止「起播→失败→立刻再起播」刷屏）。 */
    private static final long RESTART_COOLDOWN_MS = 1500L;
    private static final java.util.Map<String, Long> LAST_START_MS = new ConcurrentHashMap<>();
    /** 连续起播失败的次数；点一下播放键（startedGameTime 变化）就会清零。 */
    private static final java.util.Map<String, Integer> START_FAILURES = new ConcurrentHashMap<>();
    private static final int MAX_START_FAILURES = 3;

    // ------------------------------------------------------------------ 每 tick

    /** 客户端每 tick 调用（由 {@code ProjectorClient} 挂上）。 */
    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        MusicPlayer player = MusicPlayer.get();
        if (level == null || minecraft.player == null) {
            if (player.isActive()) {
                player.stop();
            }
            return;
        }
        player.tick();

        int maxConcurrent = Math.max(1, ProjectorConfig.INSTANCE.musicMaxConcurrent.get());
        double hearDistance = ProjectorConfig.INSTANCE.musicHearDistance.get();
        Vec3 eye = minecraft.player.position();
        long gameTime = level.getGameTime();

        // 「应该出声」的控件：正在播放 + 没放完 + 在听觉范围内，按开始时间排序
        record Candidate(Plane plane, MusicWidget widget) {
        }
        List<Candidate> audible = new ArrayList<>();
        for (Plane plane : PlaneCache.planesIn(level.dimension().location())) {
            for (var widget : plane.widgets) {
                if (!(widget instanceof MusicWidget music) || !music.playing || !music.hasTrack()) {
                    continue;
                }
                if (music.finishedAt(gameTime)) {
                    continue;
                }
                Vec3 at = worldCenter(plane, music);
                if (at == null || at.distanceTo(eye) > hearDistance) {
                    continue;
                }
                audible.add(new Candidate(plane, music));
            }
        }
        audible.sort(Comparator.comparingLong(c -> c.widget().startedGameTime));
        if (audible.size() > maxConcurrent) {
            audible = audible.subList(0, maxConcurrent);
        }

        if (audible.isEmpty()) {
            if (player.isActive()) {
                player.stop();
            }
        } else {
            Candidate first = audible.get(0);
            MusicWidget music = first.widget();
            String key = ownerKey(first.plane(), music);
            Vec3 at = worldCenter(first.plane(), music);
            double volume = Math.max(0, Math.min(1, music.volume))
                            * ProjectorConfig.INSTANCE.musicVolume.get();
            boolean sameOne = key.equals(player.ownerKey());
            boolean needsStart = !sameOne
                    || player.state() == MusicPlayer.State.FAILED
                    || player.state() == MusicPlayer.State.IDLE;
            if (needsStart) {
                // 【27.2】本地音乐现在按「服务端哈希」引用：本机还没有这份文件时先请求下载，
                // 下好了下一 tick 自然起播（以前这里会直接去开文件 ⇒ 抛异常 ⇒ 一声不响，
                // 玩家看到的就是「服务器上别人听不到我放的本地音乐」）。
                if (ensureAudioReady(music) && at != null
                        && allowStart(key, player, music, gameTime)) {
                    startTrack(first.plane(), music, key, at, (float) hearDistance, volume, gameTime);
                }
            } else {
                if (at != null) {
                    player.setPosition(at);
                }
                player.setVolume(volume);
                player.setAttenuation((float) hearDistance);
                noteDecodedDuration(music, player.decodedDurationMs());
                // 【27.1.1 实测】点了波形条调进度只改了控件数据，**声音不会自己跳**——
                // 播放器只在起播时读位置。这里每 tick 对一次账：对不上就从新位置重开这一段。
                long want = music.positionAt(gameTime);
                long have = player.currentPositionMs();
                if (MusicPlayer.shouldResync(want, have) && player.state() == MusicPlayer.State.PLAYING) {
                    Projector.LOGGER.info("[Projector][音乐] 进度跳变（{}ms → {}ms），从新位置续放",
                            have, want);
                    player.play(key, MusicTrack.of(music), at, (float) hearDistance, volume,
                            want, envelopeFor(music));
                }
            }
        }
        reportState(player);

        // 每秒清一次「平面上已经没有这个控件了」的波形缓存（控件被删掉时省一次挂钩）
        if (gameTime % 20L == 0L) {
            sweepCaches(level);
        }
    }

    /**
     * 【27.2】这首歌现在能放吗？不能放就顺手把「缺的那一份」要过来。
     *
     * <p>只处理「本地音乐 + 服务端哈希」这一种：网易云/直链自己会联网拉，
     * 旧存档里的路径 key 只可能在有那份文件的机器上放得出来（日志里会说明）。</p>
     *
     * @return true = 现在就能开流（或这首歌根本不归这里管）；false = 正在下载，等下一 tick
     */
    private static boolean ensureAudioReady(MusicWidget music) {
        MusicTrack track = MusicTrack.of(music);
        if (track.kind() != MusicTrack.Kind.LOCAL || !MusicTrack.isHashKey(track.key())) {
            return true;
        }
        String hash = track.key();
        if (hasAudioLocally(hash)) {
            return true;
        }
        // 【退避】tick 是 20 Hz：不设窗口的话，服务端一旦没有这份文件，
        // 每 tick 都会发一次请求（TransferLog 会被刷爆）。15 秒最多问一次。
        long now = System.currentTimeMillis();
        Long next = AUDIO_ATTEMPT.get(hash);
        if (next != null && now < next) {
            return false;
        }
        if (AUDIO_ATTEMPT.size() > 256) {
            AUDIO_ATTEMPT.clear();      // 兜底：极端情况下别把表撑爆
        }
        AUDIO_ATTEMPT.put(hash, now + AUDIO_RETRY_MS);
        if (next == null) {
            Projector.LOGGER.info("[Projector][音乐] 本机还没有这份音频（哈希 {}），"
                    + "已向服务端请求下载；下载完成后会自动开始播放", MusicTrack.shortHash(hash));
        } else {
            Projector.LOGGER.debug("[Projector][音乐] 再次请求下载音频（哈希 {}）",
                    MusicTrack.shortHash(hash));
        }
        try {
            top.hmjmfabc.projector.client.media.MediaCache.prefetch(hash, false);
        } catch (Throwable t) {
            Projector.LOGGER.debug("[Projector][音乐] 请求下载音频失败（{}）：{}",
                    MusicTrack.shortHash(hash), t.toString());
        }
        return false;
    }

    /** 本机有没有这份音频（索引出问题就当没有，别把异常抛到音频线程上）。 */
    private static boolean hasAudioLocally(String hash) {
        try {
            return top.hmjmfabc.projector.client.media.LocalMedia.hasWhole(hash);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 上一次为某个哈希请求下载的时刻 + 退避窗口（退出世界时清空）。 */
    private static final java.util.Map<String, Long> AUDIO_ATTEMPT =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 同一份音频最多多久问一次服务端。 */
    private static final long AUDIO_RETRY_MS = 15_000L;

    /** 清掉已经不存在于任何平面上的控件的波形缓存。 */
    private static void sweepCaches(ClientLevel level) {
        if (ENVELOPES.isEmpty()) {
            return;
        }
        java.util.Set<UUID> alive = new java.util.HashSet<>();
        for (Plane plane : PlaneCache.planesIn(level.dimension().location())) {
            for (var widget : plane.widgets) {
                if (widget instanceof MusicWidget music) {
                    alive.add(music.id);
                }
            }
        }
        ENVELOPES.keySet().retainAll(alive);
    }

    /**
     * 该不该再起播一次？
     *
     * <p>三条闸门（27.1.1 实测教训：没有它们的时候，一次起播失败会变成
     * **每 tick 重开一次**，日志里 18 次「开始解码」、玩家听到的是「播一秒循环几个音」）：</p>
     * <ol>
     *   <li><b>冷却</b>：同一首歌两次起播至少隔 {@link #RESTART_COOLDOWN_MS}；</li>
     *   <li><b>失败上限</b>：连续失败 {@link #MAX_START_FAILURES} 次就不再自动重试；</li>
     *   <li><b>点一下播放键就清零</b>：玩家重新点，说明他愿意再试一次。</li>
     * </ol>
     */
    private static boolean allowStart(String key, MusicPlayer player, MusicWidget widget,
                                      long gameTime) {
        // 玩家重新点过播放键（锚点变了）⇒ 一切从头再试
        long anchor = widget.startedGameTime;
        Long lastAnchor = ANCHORS.put(key, anchor);
        if (lastAnchor == null || lastAnchor != anchor) {
            START_FAILURES.remove(key);
        }
        int failures = START_FAILURES.getOrDefault(key, 0);
        if (failures >= MAX_START_FAILURES) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long last = LAST_START_MS.get(key);
        if (last != null && now - last < RESTART_COOLDOWN_MS) {
            return false;
        }
        if (player.state() == MusicPlayer.State.FAILED) {
            START_FAILURES.put(key, failures + 1);
            Projector.LOGGER.warn("[Projector][音乐] 起播失败第 {} 次（{}），{}",
                    failures + 1, player.error(),
                    failures + 1 >= MAX_START_FAILURES ? "不再自动重试，请重新点播放键" : "稍后重试");
        }
        LAST_START_MS.put(key, now);
        return true;
    }

    /** 记录每个控件「上次起播时的锚点」，锚点变了 = 玩家重新点了播放键。 */
    private static final java.util.Map<String, Long> ANCHORS = new ConcurrentHashMap<>();

    private static void startTrack(Plane plane, MusicWidget widget, String key, Vec3 at, float range,
                                   double volume, long gameTime) {
        MusicTrack track = MusicTrack.of(widget);
        if (track.isEmpty()) {
            return;
        }
        MusicEnvelope envelope = envelopeFor(widget);
        ensureLyrics(widget);
        MusicPlayer.get().play(key, track, at, range, volume, widget.positionAt(gameTime), envelope);
    }

    /** 只在状态真的变了的时候打一行日志（避免每 tick 刷屏）。 */
    private static void reportState(MusicPlayer player) {
        String now = player.isActive() ? player.describeState() : "";
        if (!now.equals(lastReport)) {
            lastReport = now;
            if (!now.isEmpty()) {
                Projector.LOGGER.info("[Projector][音乐] {}", now);
            }
        }
    }

    // ------------------------------------------------------------------ 几何与身份

    /** 控件中心的世界坐标（声音从这个点发出）。 */
    public static Vec3 worldCenter(Plane plane, MusicWidget w) {
        if (plane == null || w == null) {
            return null;
        }
        return plane.canvas().toWorld(w.centerX(), w.centerY(), 0.5);
    }

    /** 「平面 id + 控件 id」：播放器用它认领「这首歌是谁在放」。 */
    public static String ownerKey(Plane plane, MusicWidget w) {
        return (plane == null ? "?" : plane.id.toString()) + "#" + (w == null ? "?" : w.id);
    }

    // ------------------------------------------------------------------ 点击播放键

    /**
     * 世界里点了播放键：**先本地翻转**（点下去立刻有反馈），再让服务端定夺。
     *
     * <p>服务端拒绝（例如平面开了内容保护）时会回灌一次真实状态，
     * 本地那份乐观改动就跟着被纠正 —— 不需要我们另写回滚。</p>
     */
    public static void toggleInWorld(Plane plane, MusicWidget widget) {
        if (plane == null || widget == null) {
            return;
        }
        if (!widget.hasTrack()) {
            Minecraft.getInstance().player.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable("projector.msg.music_no_track"), true);
            return;
        }
        long now = gameTime();
        boolean was = widget.playing;
        widget.toggle(now);
        Projector.LOGGER.info("[Projector][音乐] 世界内点击播放键 平面={} 控件={} {}→{}（{}）",
                plane.id, widget.id, was ? "播放中" : "已停止",
                widget.playing ? "播放中" : "已停止", widget.label());

        CompoundTag args = new CompoundTag();
        PacketDistributor.sendToServer(new Payloads.WidgetAction(plane.id, widget.id, "toggle", args));
    }

    /** 世界里点了波形条：本地先跳（立刻听到），同时请服务端把位置改成同一处。 */
    public static void seekInWorld(Plane plane, MusicWidget widget, double fraction) {
        if (plane == null || widget == null || !widget.hasTrack()) {
            return;
        }
        widget.seekToFraction(fraction, gameTime());
        Projector.LOGGER.info("[Projector][音乐] 世界内调进度 {}%（{}ms / {}ms）",
                Math.round(fraction * 100), widget.positionMs, widget.effectiveDurationMs());
        CompoundTag args = new CompoundTag();
        args.putDouble("fraction", Math.max(0.0, Math.min(1.0, fraction)));
        PacketDistributor.sendToServer(new Payloads.WidgetAction(plane.id, widget.id, "seek", args));
    }

    /**
     * 解码完成后用音频里的真实时长纠正控件（本地文件本来没时长，会显示成兜底的 3:00）。
     * 只在小数/缺失时写，避免把网易云元数据里的时长覆盖掉。
     */
    public static void noteDecodedDuration(MusicWidget widget, long decodedMs) {
        if (widget == null || decodedMs <= 0) {
            return;
        }
        if (widget.durationMs <= 0 || Math.abs(widget.durationMs - decodedMs) > 1500L) {
            Projector.LOGGER.info("[Projector][音乐] 用音频实际时长校正控件：{}ms → {}ms（{}）",
                    widget.durationMs, decodedMs, widget.label());
            widget.durationMs = decodedMs;
        }
    }

    /** 换歌之后调用：波形与歌词都要重来。 */
    public static void onTrackChanged(Plane plane, MusicWidget widget) {
        if (widget == null) {
            return;
        }
        ENVELOPES.put(widget.id, MusicEnvelope.forTrack(widget.displayKey(), widget.effectiveDurationMs()));
        MusicPlayer player = MusicPlayer.get();
        if (ownerKey(plane, widget).equals(player.ownerKey())) {
            player.stop();
        }
    }

    /** 控件被删除时调用。 */
    public static void onWidgetRemoved(MusicWidget widget) {
        if (widget == null) {
            return;
        }
        ENVELOPES.remove(widget.id);
        MusicPlayer player = MusicPlayer.get();
        if (player.ownerKey().endsWith("#" + widget.id)) {
            player.stop();
        }
    }

    /** 退出世界 / 断线时清干净。 */
    public static void clearAll() {
        AUDIO_ATTEMPT.clear();
        MusicPlayer.get().stop();
        ENVELOPES.clear();
        LYRICS.clear();
        LYRIC_FETCHING.clear();
        LAST_START_MS.clear();
        START_FAILURES.clear();
        ANCHORS.clear();
        lastReport = "";
    }

    // ------------------------------------------------------------------ 缓存

    /** 波形（首次访问时按歌名生成占位花纹）。 */
    public static MusicEnvelope envelopeFor(MusicWidget w) {
        if (w == null) {
            return null;
        }
        return ENVELOPES.computeIfAbsent(w.id, id ->
                MusicEnvelope.forTrack(w.displayKey(), w.effectiveDurationMs()));
    }

    /** 当前这句歌词（没有就返回 null）。 */
    public static LyricRecord lyricFor(MusicWidget w) {
        if (w == null) {
            return null;
        }
        LyricRecord record = LYRICS.get(lyricKey(w));
        return record == null || record.isEmpty() ? null : record;
    }

    private static String lyricKey(MusicWidget w) {
        return w.sourceKind + "|" + w.sourceKey;
    }

    /** 只有网易云的歌才去取歌词（本地文件没有歌词接口）。 */
    private static void ensureLyrics(MusicWidget w) {
        if (!w.showLyric || !"NETEASE".equalsIgnoreCase(w.sourceKind)) {
            return;
        }
        String key = lyricKey(w);
        if (LYRICS.containsKey(key) || !LYRIC_FETCHING.add(key)) {
            return;
        }
        long songId = NetEaseApi.parseSongId(w.sourceKey);
        if (songId <= 0) {
            LYRICS.put(key, LyricRecord.empty());
            LYRIC_FETCHING.remove(key);
            return;
        }
        String title = w.title == null || w.title.isBlank() ? Long.toString(songId) : w.title;
        Thread fetcher = new Thread(() -> {
            try {
                LyricRecord record = NetEaseApi.lyric(songId, title);
                LYRICS.put(key, record == null ? LyricRecord.empty() : record);
                if (record != null) {
                    Projector.LOGGER.info("[Projector][音乐] 歌词已就绪：{}（{} 行）",
                            title, record.lineCount());
                }
            } catch (Throwable t) {
                // 失败也记成「空」，免得每次播放都重试一遍网络请求
                LYRICS.put(key, LyricRecord.empty());
                Projector.LOGGER.debug("[Projector][音乐] 取歌词失败：{}", t.toString());
            } finally {
                LYRIC_FETCHING.remove(key);
            }
        }, "Projector-Music-Lyric");
        fetcher.setDaemon(true);
        fetcher.setPriority(Thread.MIN_PRIORITY);
        fetcher.start();
    }

    /** 当前游戏刻。 */
    public static long gameTime() {
        ClientLevel level = Minecraft.getInstance().level;
        return level == null ? 0L : level.getGameTime();
    }

    /** 供诊断（登录日志与卡顿行用）。 */
    public static String describeCache() {
        return "波形=" + ENVELOPES.size() + " 歌词=" + LYRICS.size() + " 在取=" + LYRIC_FETCHING.size()
               + "｜" + MusicPlayer.get().describeState();
    }
}

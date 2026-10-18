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
import top.hmjmfabc.projector.compat.netmusic.api.ItemMusicCD;
import top.hmjmfabc.projector.compat.netmusic.api.resolver.MusicPlayResolverManager;
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
    /** 「开始出声」回调只注册一次（见 {@code MusicPlayer.markAudible}）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean AUDIBLE_HOOKED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * 某首歌真的出声了：解除「加载中」冻结（进度条恢复推进），并把位置对齐到听到的地方。
     * <p>【27.2.1-hotfix-144】以前 {@code markAudible()} 只改了播放器自己的时间锚点，
     * 外面**不知道**这段时间已经过完了 —— 于是「加载中」的冻结状态只能靠每 tick 的
     * 「位置追上锚点」去猜，猜不中就一直是冻结/解冻来回抖。</p>
     */
    private static void onAudible(String ownerKey) {
        if (ownerKey == null || ownerKey.isEmpty()) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            if (level == null) {
                return;
            }
            MusicPlayer player = MusicPlayer.get();
            for (Plane plane : PlaneCache.planesIn(level.dimension().location())) {
                for (var widget : plane.widgets) {
                    if (!(widget instanceof MusicWidget music) || !music.isBuffering()) {
                        continue;
                    }
                    if (!ownerKey.equals(ownerKey(plane, music))) {
                        continue;
                    }
                    long heard = Math.max(0L, player.currentPositionMs());
                    music.positionMs = heard;
                    music.startedGameTime = level.getGameTime();
                    music.clearBuffering();
                    Projector.LOGGER.info("[Projector][音乐] 已出声：解除进度冻结，位置对齐到 {}ms（{}）",
                            heard, music.label());
                }
            }
        } catch (Throwable t) {
            Projector.LOGGER.debug("[Projector][音乐] 解冻失败：{}", String.valueOf(t));
        }
    }

    public static void tick() {
        // 【27.2.4-200】「已出声」通知改由客户端线程消费（以前是音频线程直接回调，
        // 会在音频线程上遍历 PlaneCache / plane.widgets 并写控件字段 ⇒ 竞争 + 卡音频）。
        String justAudible = MusicPlayer.takePendingAudible();
        if (justAudible != null) {
            onAudible(justAudible);
        }
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
                // 【27.2.2】先看「这首歌是不是被换掉了」——换歌的是**服务端**（播放列表自动续播），
                // 本机的音频流必须跟着重开，否则声音还停在上一首。
                watchTrackChange(plane, music);
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
                syncAnchor(first.plane(), music, key, gameTime, player, at,
                        (float) hearDistance, volume);
            }
        }
        // 【加载态退出】判据与「服务端回正」那条一样：**播放器的位置追上了控件的锚点**，
        // 说明这一段真的开始出声了 ⇒ 取消进度冻结（位置取真实听到的那个，避免跳一下）
        for (Plane plane : PlaneCache.planesIn(level.dimension().location())) {
            for (var widget : plane.widgets) {
                if (!(widget instanceof MusicWidget music) || !music.isBuffering()) continue;
                String key = ownerKey(plane, music);
                if (!key.equals(player.ownerKey())) continue;
                if (Math.abs(player.currentPositionMs() - music.positionAt(gameTime)) <= 1500L) {
                    music.positionMs = Math.max(0L, player.currentPositionMs());
                    music.startedGameTime = gameTime;
                    music.clearBuffering();
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
     * 【27.2.2】发现「这个控件放的歌被换掉了」就把本机的音频流停掉，下一 tick 按新歌重开。
     *
     * <p>为什么需要它：**换歌的人不一定是本机**。播放列表的自动续播在服务端做
     * （见 {@code ServerNetHandler.tickPlaylists}），客户端只是收到一条平面广播。
     * 而 {@link #tick()} 起播的判据是「同一个控件 + 播放器还在 PLAYING 就不重开」
     * —— 结果就是：控件显示的歌名变了、声音还是上一首。</p>
     *
     * <p>这里记的是「上次看到的歌」的指纹（kind+key+时长）。变化时走
     * {@link #onTrackChanged}（波形与歌词重来 + 本机在放这一首就 stop），
     * 于是下一 tick 会自然按新歌重新起流。<b>只在真的变了时停一次</b>，
     * 不会每帧重开（重开网易云要重新联网，这是 27.2.1-140 刚修过的东西）。</p>
     */
    private static void watchTrackChange(Plane plane, MusicWidget music) {
        String key = ownerKey(plane, music);
        String sig = music.sourceKind + "|" + music.sourceKey + "|" + music.durationMs;
        String prev = LAST_TRACK_SIG.put(key, sig);
        if (prev != null && !prev.equals(sig)) {
            Projector.LOGGER.info("[Projector][音乐] 控件换歌了（服务端改的），本机重开音频流：{}", music.label());
            onTrackChanged(plane, music);
        }
    }

    /** 每个控件「上次看到的歌」的指纹（用来发现服务端替我们换了歌）。 */
    private static final java.util.Map<String, String> LAST_TRACK_SIG =
            new ConcurrentHashMap<>();

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
        java.util.Set<UUID> alive = new java.util.HashSet<>();
        java.util.Set<String> aliveKeys = new java.util.HashSet<>();
        for (Plane plane : PlaneCache.planesIn(level.dimension().location())) {
            for (var widget : plane.widgets) {
                if (widget instanceof MusicWidget music) {
                    alive.add(music.id);
                    aliveKeys.add(ownerKey(plane, music));
                }
            }
        }
        if (!ENVELOPES.isEmpty()) {
            ENVELOPES.keySet().retainAll(alive);
        }
        // 【27.2.4-200】顺手回收其它缓存 —— 以前只清 ENVELOPES，于是删掉控件之后
        // ANCHORS / OWN_WRITE / START_FAILURES / LAST_START_MS 会永久累积
        // （它们以 ownerKey 字符串为键，所以这里按同样格式构造存活集合）。
        ANCHORS.keySet().retainAll(aliveKeys);
        OWN_WRITE.keySet().retainAll(aliveKeys);
        START_FAILURES.keySet().retainAll(aliveKeys);
        LAST_START_MS.keySet().retainAll(aliveKeys);
        top.hmjmfabc.projector.common.widget.MusicWidget.retainBuffering(alive);
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

    // ------------------------------------------------------------------
    // 【27.2.1-hotfix-140】「游戏刻推算的进度」与「真的听到哪」的对账
    // ------------------------------------------------------------------

    /**
     * 控件里那三件「谁改了就说明有人动了播放」的数据。
     *
     * <p>{@code positionMs} 在播放中**不会自己走**（它是锚点，不是时钟），
     * 所以这三位一体只有「玩家点了播放/暂停、拖了波形条、服务端重新锚定」才会变 ——
     * 正好用来区分「有人改了进度」和「只是时间在流逝」。</p>
     */
    private record MusicAnchor(boolean playing, long positionMs, long startedGameTime) {
    }

    /** 我们自己上一次写进控件的锚点（用来分辨「这一变是谁改的」）。 */
    private static final java.util.Map<String, MusicAnchor> OWN_WRITE = new ConcurrentHashMap<>();

    /**
     * 「本机刚发过一个播放/进度动作，接下来几秒的回声算自己的」的时间窗。
     *
     * <p>本机的动作会**先本地改、再等服务端回声**（服务端用它的游戏刻重新锚定），
     * 两者的数值必然不完全一样；不做这个窗口的话，回声一回来就会被当成
     * 「别人改了进度」⇒ 重开音频流 ⇒ 网易云又得重新联网。</p>
     */
    private static final java.util.Map<String, Long> SELF_ACTION_UNTIL = new ConcurrentHashMap<>();
    /** 本地动作回声的宽限时长（覆盖一个来回 + 服务端处理）。 */
    private static final long SELF_ACTION_GRACE_MS = 3000L;

    /** 本地时间锚点与服务端锚点相差多少才值得重钉（毫秒）。 */
    private static final long REANCHOR_MS = 300L;
    /** 相差多少才认为「服务端那边的进度已经明显落后于大家听到的」，值得回传一次 seek。 */
    private static final long HEAL_MS = 3000L;
    /** 回传 seek 的最短间隔。 */
    private static final long HEAL_COOLDOWN_MS = 60_000L;
    /**
     * 控件锚点变了、且目标位置与本机听到的位置差超过这个数 ⇒ 才算「有人明显跳了进度」，
     * 才值得重开音频流（否则只当成一次小幅校正，绝不重开流）。
     */
    private static final long FOLLOW_MS = 3000L;
    /** 上次回传 seek 的时刻。 */
    private static final java.util.Map<String, Long> HEAL_AT = new ConcurrentHashMap<>();
    /** 「游戏刻与真实时间不一致」这条日志的限流表。 */
    private static final java.util.Map<String, Long> DRIFT_REPORT = new ConcurrentHashMap<>();

    /**
     * 把控件的进度锚点与「真的听到哪」对齐。
     *
     * <h3>为什么要这么绕（玩家报的 Bug）</h3>
     * <p>玩家原话：<b>「服务器播放网易云音乐时，会出现卡顿（音频断断续续，进度跳跃），
     * 客户端没有这种问题」</b>。三条原因叠在一起：</p>
     * <ol>
     *   <li><b>两个时钟的速率不同</b>：控件的进度按<b>游戏刻</b>推算
     *       （{@code positionMs + (gameTime - startedGameTime) × 50ms}），而听到的声音是
     *       <b>真实时间</b>。专用服务器的 TPS 一旦低于 20（很正常），游戏刻就比真实时间走得慢
     *       —— 实测 15 TPS 时每 3 秒差 0.5 秒，几秒就超过 1.5 秒容差；</li>
     *   <li>旧代码把「超过容差」当成「有人改了进度」，于是<b>重开这一段音频流</b>。
     *       本地文件重开是瞬时的（听不出来），<b>网易云要重新联网</b> ⇒ 一卡好几秒；</li>
     *   <li>重开之后再漂开、再重开 ⇒ 玩家听到的就是「断断续续 + 进度跳跃」。
     *       单人存档里客户端就是服务端、TPS 稳定 20，所以<b>从来没出现过</b>。</li>
     * </ol>
     *
     * <h3>现在的规则</h3>
     * <ul>
     *   <li><b>只有控件数据真的被改过</b>（{@link MusicAnchor} 变了、且不是本机自己的回声）
     *       才重开流 —— 那是「玩家拖了进度条 / 别人按了播放键」，理应跟随；</li>
     *   <li><b>漂移一律不重开流</b>：以「听到的位置」为准，把控件的锚点重钉到本地时钟上
     *       （只改本地、不回传服务端）。这样进度条、时间、歌词、{@code finishedAt}
     *       全都跟着<b>声音</b>走，也不会再出现「放完了却显示没放完、于是又重放一小段」；</li>
     *   <li>漂移大到 {@link #HEAL_MS} 时，<b>回传一次 seek</b>（比例制，走已有的
     *       {@code WidgetAction("seek")} 通道）让服务端与晚进来的玩家也对上，
     *       冷却 {@link #HEAL_COOLDOWN_MS} —— 不这么做的话，服务器那边的进度会一直落后于
     *       大家听到的位置，新玩家一进来就比别人慢几秒。</li>
     * </ul>
     */
    private static void syncAnchor(Plane plane, MusicWidget music, String key, long gameTime,
                                   MusicPlayer player, Vec3 at, float hearDistance, double volume) {
        MusicAnchor current = new MusicAnchor(music.playing, music.positionMs, music.startedGameTime);
        MusicAnchor own = OWN_WRITE.get(key);
        boolean changed = own == null || !own.equals(current);
        if (changed) {
            if (isOwnEcho(key)) {
                // 本机刚发过的动作的回声：只登记，不重开流
                SELF_ACTION_UNTIL.remove(key);
                OWN_WRITE.put(key, current);
                Projector.LOGGER.debug("[Projector][音乐] 吸收本机动作的回声：位置={}ms",
                        music.positionMs);
                return;
            }
            // 控件数据确实变了。但「变了」有两种，必须分开对待：
            //   ① 有人明显跳了进度（拖波形条 / 别的玩家按了播放键）⇒ 跟随，重开这一段；
            //   ② 别的客户端回传的「进度校正」（见 healServerAnchor）—— 目标位置就在
            //      大家听到的位置附近（差得不大）⇒ **不能重开流**，否则每个客户端都会因为
            //      别人的校正而重新联网（网易云一重开就是好几秒的卡顿）。
            long want = music.positionAt(gameTime);
            long heardNow = player.currentPositionMs();
            boolean playing = player.state() == MusicPlayer.State.PLAYING;
            if (playing && Math.abs(want - heardNow) > FOLLOW_MS) {
                Projector.LOGGER.info("[Projector][音乐] 控件进度被改动（{}ms → {}ms），从新位置续放",
                        heardNow, want);
                OWN_WRITE.put(key, current);
                player.play(key, MusicTrack.of(music), at, hearDistance, volume, want, envelopeFor(music));
                return;
            }
            // 差别不大（或还没在放）：只把本地锚点钉到听到的位置，**音频一刻都不动**
            OWN_WRITE.put(key, current);
            if (playing) {
                music.positionMs = Math.max(0L, heardNow);
                music.startedGameTime = gameTime;
                OWN_WRITE.put(key, new MusicAnchor(music.playing, music.positionMs,
                        music.startedGameTime));
                Projector.LOGGER.debug("[Projector][音乐] 吸收一次小幅进度校正（目标 {}ms，本机听到 {}ms）",
                        want, heardNow);
            }
            return;
        }
        if (player.state() != MusicPlayer.State.PLAYING) {
            return;
        }
        long heard = player.currentPositionMs();
        long shown = music.positionAt(gameTime);
        long diff = heard - shown;
        if (!needsReanchor(heard, shown)) {
            return;
        }
        if (Math.abs(diff) > 2000L) {
            long now = System.currentTimeMillis();
            Long last = DRIFT_REPORT.get(key);
            if (last == null || now - last > 10_000L) {
                DRIFT_REPORT.put(key, now);
                Projector.LOGGER.info("[Projector][音乐] 游戏刻与真实时间不一致（听到 {}ms / 按游戏刻算 {}ms，"
                                + "差 {}ms）：已按听到的位置重钉控件锚点，**不重开音频流**"
                                + "（服务器 TPS 低于 20 时会出现，单人存档不会）",
                        heard, shown, diff);
            }
        }
        // 以听到的为准：把控件的时间锚点重钉到本地时钟（只改本地，不回传服务端）
        music.positionMs = Math.max(0L, heard);
        music.startedGameTime = gameTime;
        OWN_WRITE.put(key, new MusicAnchor(music.playing, music.positionMs, music.startedGameTime));
        healServerAnchor(plane, music, key, heard);
    }

    /**
     * 本地时钟与服务端锚点差得够多，值得重钉控件锚点吗（纯函数，便于脚本验证）。
     *
     * <p>注意：<b>重钉 ≠ 重开流</b>。重钉只是把控件的进度改成「听到的位置」，
     * 音频一刻都不会断；【27.2.1-hotfix-140】之前这里反而是重开流的判据，
     * 网易云每次重开都要联网 ⇒ 断断续续。</p>
     */
    public static boolean needsReanchor(long heardMs, long shownMs) {
        return Math.abs(heardMs - shownMs) > REANCHOR_MS;
    }

    /** 本机刚发过播放/进度动作吗（回声宽限窗口内）。 */
    private static boolean isOwnEcho(String key) {
        Long until = SELF_ACTION_UNTIL.get(key);
        return until != null && System.currentTimeMillis() < until;
    }

    /** 登记「本机刚发过一个播放/进度动作」，接下来几秒的回声都算自己的。 */
    private static void noteOwnAction(String key) {
        if (key == null || key.isEmpty()) {
            return;
        }
        SELF_ACTION_UNTIL.put(key, System.currentTimeMillis() + SELF_ACTION_GRACE_MS);
        if (SELF_ACTION_UNTIL.size() > 256) {
            long now = System.currentTimeMillis();
            SELF_ACTION_UNTIL.entrySet().removeIf(e -> e.getValue() < now);
        }
    }

    /**
     * 让服务端的进度追上「大家听到的位置」。
     *
     * <p>只差得很大（{@link #HEAL_MS}）才发，且有 {@link #HEAL_COOLDOWN_MS} 冷却 ——
     * 这个动作会广播给所有客户端并重新锚定，不值得每秒发。</p>
     */
    private static void healServerAnchor(Plane plane, MusicWidget music, String key, long heardMs) {
        long duration = music.effectiveDurationMs();
        if (plane == null || duration <= 0 || !music.playing) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = HEAL_AT.get(key);
        if (last != null && now - last < HEAL_COOLDOWN_MS) {
            return;
        }
        HEAL_AT.put(key, now);
        double fraction = Math.max(0.0, Math.min(1.0, heardMs / (double) duration));
        noteOwnAction(key);
        CompoundTag args = new CompoundTag();
        args.putDouble("fraction", fraction);
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new top.hmjmfabc.projector.network.Payloads.WidgetAction(plane.id, music.id, "seek", args));
        Projector.LOGGER.info("[Projector][音乐] 服务端进度落后于实际听到的位置，已回传一次进度校正"
                + "（{}ms / {}ms，{}%）", heardMs, duration, Math.round(fraction * 100));
    }

    private static void startTrack(Plane plane, MusicWidget widget, String key, Vec3 at, float range,
                                   double volume, long gameTime) {
        MusicTrack track = MusicTrack.of(widget);
        if (track.isEmpty()) {
            return;
        }
        MusicEnvelope envelope = envelopeFor(widget);
        ensureLyrics(widget);
        prepareAddonResolve(track);
        // 记下「这一次起播用的是哪个锚点」：下一 tick 对账时才知道控件有没有被别人改过
        OWN_WRITE.put(key, new MusicAnchor(widget.playing, widget.positionMs, widget.startedGameTime));
        MusicPlayer.get().play(key, track, at, range, volume, widget.positionAt(gameTime), envelope);
    }

    /**
     * 【27.2.2】起播前**在后台**问一遍外部解析器（登录了 VIP 的附属）要直链。
     *
     * <p>为什么要「先问、下一次起播才用」：解析要联网、是异步的，而开流是同步的
     * （见 {@link NetEaseApi#RESOLVED} 的说明）。所以这里只发起解析，
     * 拿到之后记进缓存；下一 tick 的对账发现「地址变了」就从新地址续放
     * （{@code syncAnchor} 里那条「回退重开」会处理，玩家最多听一秒钟的低码率）。</p>
     *
     * <p>解析器一个都没注册时（没人登录 VIP）这里什么都不做，零开销。</p>
     */
    private static void prepareAddonResolve(MusicTrack track) {
        if (track == null || track.kind() != MusicTrack.Kind.NETEASE) {
            return;
        }
        if (MusicPlayResolverManager.resolvers().isEmpty()) {
            return;                       // 没有外部解析器（没登录 VIP）：零开销
        }
        String key = track.key();
        if (NetEaseApi.resolved(key) != null) {
            return;                       // 已经解析过
        }
        long songId = NetEaseApi.parseSongId(key);
        if (songId <= 0) {
            return;
        }
        ItemMusicCD.SongInfo info = new ItemMusicCD.SongInfo(
                "https://music.163.com/song?id=" + songId + ".mp3",
                track.title(), (int) Math.max(0L, track.durationMs() / 1000L));
        info.songId = (int) songId;
        if (!MusicPlayResolverManager.canResolve(info)) {
            return;
        }
        MusicPlayResolverManager.resolve(info).thenAccept(resolved -> {
            if (resolved != null && resolved.songUrl != null && !resolved.songUrl.isEmpty()) {
                NetEaseApi.putResolved(key, resolved.songUrl);
                Projector.LOGGER.info("[Projector][音乐] 外部解析器给出直链（{}）：{}",
                        MusicTrack.shortHash(key), resolved.songUrl.substring(0,
                                Math.min(60, resolved.songUrl.length())));
            }
        }).exceptionally(t -> {
            Projector.LOGGER.warn("[Projector][音乐] 外部解析器解析失败（回落到内置外链）：{}",
                    String.valueOf(t));
            return null;
        });
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
            Projector.LOGGER.info("[Projector][音乐] 播放键被按下但没有选歌（控件 {}）—— 已提示玩家", widget.id);
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

        // 【27.2.1-hotfix-140】本机动作的回声（服务端会用它的游戏刻重新锚定）算自己的，
        // 否则回声一到就会被当成「别人改了进度」⇒ 重开流 ⇒ 网易云重新联网
        noteOwnAction(ownerKey(plane, widget));
        // 【加载态】从暂停恢复播放时，声道可能已经被引擎回收 ⇒ 会重开这一段（要等）。
        // 与跳进度同一套处理：等待期间进度显示钉在当前位置。
        if (widget.playing) {
            widget.markBuffering(widget.positionAt(now));
        } else {
            widget.clearBuffering();        // 暂停：不需要加载，停止显示推进即可
        }
        CompoundTag args = new CompoundTag();
        PacketDistributor.sendToServer(new Payloads.WidgetAction(plane.id, widget.id, "toggle", args));
    }

    /** 世界里点了波形条：本地先跳（立刻听到），同时请服务端把位置改成同一处。 */
    /**
     * 【27.2.3-hotfix-142】编辑器里拖「播放进度」滑块：本机先动 + 服务端节流。
     *
     * <p>为什么单独一个入口：世界内点波形条是「点一下就跳」，发一条没有任何负担；
     * 而滑块是**连续拖动**，一次拖动会触发几十次回调。以前每次都发一条 seek ⇒
     * 服务端被连续重锚、本机连续重开音频流，玩家感觉就是「拖一下卡好几秒」。</p>
     *
     * <p>现在：①本机立刻按新位置动起来（与世界内同一条路径，含流内快进）；
     * ②发给服务端的请求按 {@link #EDITOR_SEEK_MIN_INTERVAL_MS} 节流；
     * ③松手/停在哪儿就以最后那一次为准 —— 松手后超过节流间隔的第一次会照常发出。</p>
     *
     * @param restartAudio 是否真的动音频（拖动中只在服务端节流时刻动一次，避免每帧重开流）
     */
    public static void seekInWorldForEditor(Plane plane, MusicWidget widget, double fraction,
                                           long nowMs, boolean restartAudio) {
        if (plane == null || widget == null || !widget.hasTrack()) {
            return;
        }
        String key = ownerKey(plane, widget);
        long last = EDITOR_SEEK_LAST_MS.getOrDefault(key, 0L);
        boolean send = nowMs - last >= EDITOR_SEEK_MIN_INTERVAL_MS;
        if (send) {
            EDITOR_SEEK_LAST_MS.put(key, nowMs);
        }
        // 本机先动：位置立刻跟手（这一步不重开流，只改控件数据 + 冻结显示）
        widget.seekToFraction(fraction, gameTime());
        widget.markBuffering(widget.positionMs);
        if (restartAudio) {
            seekAudioLocked(plane, widget, key, send);
        }
        if (!send) {
            return;                     // 服务端这一拍不通知，下一拍再说
        }
        CompoundTag args = new CompoundTag();
        args.putDouble("fraction", Math.max(0.0, Math.min(1.0, fraction)));
        PacketDistributor.sendToServer(new Payloads.WidgetAction(plane.id, widget.id, "seek", args));
    }

    /** 编辑器拖动 seek 的最小发送间隔（毫秒）。 */
    private static final long EDITOR_SEEK_MIN_INTERVAL_MS = 250L;
    /** 上一次发给服务端的时刻（按控件）。 */
    private static final java.util.Map<String, Long> EDITOR_SEEK_LAST_MS = new ConcurrentHashMap<>();

    /**
     * 真正让音频跳到控件当前记录的位置：先试**流内快进**，不行才重开这一段。
     * 世界内点击与编辑器拖动共用这一段，保证两条路的音画行为完全一致。
     */
    private static void seekAudioLocked(Plane plane, MusicWidget widget, String key, boolean notify) {
        // 【27.2.4-200 修】`notify` 以前是**死参数**（函数体从不读它），于是编辑器拖进度条
        // 每帧都会走到这里；一旦 seekWithinStream 失败（往回拖 / 超过 30 秒）就 player.play()
        // ⇒ 每秒几十次重开 HTTP 流 + 解码器 + 5 秒预填（hotfix-142 想修的「拖一下卡几秒」还在）。
        // 现在只有「这一拍确实要通知服务端」时才动音频，拖动中间的帧只改控件数据。
        if (!notify) {
            return;
        }
        MusicPlayer player = MusicPlayer.get();
        if (!widget.playing || !key.equals(player.ownerKey())) {
            return;
        }
        Vec3 at = worldCenter(plane, widget);
        if (at == null) {
            return;
        }
        double vol = Math.max(0, Math.min(1, widget.volume))
                * ProjectorConfig.INSTANCE.musicVolume.get();
        float hear = (float) ProjectorConfig.INSTANCE.musicHearDistance.get();
        if (!player.seekWithinStream(key, MusicTrack.of(widget), at, hear, vol,
                widget.positionMs, envelopeFor(widget))) {
            player.play(key, MusicTrack.of(widget), at, hear, vol,
                    widget.positionMs, envelopeFor(widget));
        }
    }

    public static void seekInWorld(Plane plane, MusicWidget widget, double fraction) {
        if (plane == null || widget == null || !widget.hasTrack()) {
            return;
        }
        widget.seekToFraction(fraction, gameTime());
        Projector.LOGGER.info("[Projector][音乐] 世界内调进度 {}%（{}ms / {}ms）",
                Math.round(fraction * 100), widget.positionMs, widget.effectiveDurationMs());
        // 【27.2.1-hotfix-140】本机拖了进度条：**立刻**从新位置重开这一段（原来靠「两边差得多」
        // 的漂移判据去做，现在那条判据只用于「别人改了进度」），并把这个动作的回声算自己的
        String key = ownerKey(plane, widget);
        noteOwnAction(key);
        OWN_WRITE.put(key, new MusicAnchor(widget.playing, widget.positionMs, widget.startedGameTime));
        // 【加载态】跳进度本来就要等（重开流那条路尤其慢）：
        // 等待期间把进度显示钉在目标位置，别让它自己往前跑（用户要求）
        widget.markBuffering(widget.positionMs);
        // 真的动音频：先试**流内快进**（同一首歌、往前、30 秒内 ⇒ 不重新联网、不重建解码器），
        // 做不到才回退「重开一整段」。两条路（世界内点击 / 编辑器拖动）共用这一段。
        seekAudioLocked(plane, widget, key, true);
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
        OWN_WRITE.clear();
        SELF_ACTION_UNTIL.clear();
        HEAL_AT.clear();
        DRIFT_REPORT.clear();
        MusicPlayer.get().stop();
        ENVELOPES.clear();
        LYRICS.clear();
        LYRIC_FETCHING.clear();
        LAST_START_MS.clear();
        START_FAILURES.clear();
        ANCHORS.clear();
        LAST_TRACK_SIG.clear();
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

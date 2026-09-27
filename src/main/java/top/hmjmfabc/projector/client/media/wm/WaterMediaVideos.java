package top.hmjmfabc.projector.client.media.wm;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import top.hmjmfabc.projector.Projector;
import top.hmjmfabc.projector.client.media.LocalMedia;
import top.hmjmfabc.projector.client.media.MediaCache;
import top.hmjmfabc.projector.common.widget.VideoWidget;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 视频控件的「WaterMedia 后端」调度（27.1.2）。
 *
 * <p>职责：判断某个视频控件该走哪个后端、按需打开 WaterMedia 会话、把控件的
 * <b>时间锚点</b>同步给播放器（暂停 / 跳转 / 循环），以及失败时**回退内置后端**。</p>
 *
 * <p>与音乐控件同一套思路：控件数据（服务端权威）里的时间才是准的，
 * 播放器只是跟着它走 —— 发现位置差超过 {@link #SEEK_TOLERANCE_MS} 就 seek 过去。</p>
 */
public final class WaterMediaVideos {
    private WaterMediaVideos() {
    }

    /** 位置差多少就算「该跳了」（两侧都是墙钟，正常播放时只有几十毫秒误差）。 */
    public static final long SEEK_TOLERANCE_MS = 1500L;
    /** 连续失败几次就不再尝试 WaterMedia（避免对着一个坏文件反复重开）。 */
    private static final int MAX_FAILURES = 2;

    private static final Map<UUID, Entry> SESSIONS = new ConcurrentHashMap<>();
    private static final Set<UUID> DISABLED = ConcurrentHashMap.newKeySet();

    private static final class Entry {
        volatile WaterMediaBridge.Session session;
        volatile boolean loading;
        volatile int failures;
        volatile long lastLogMs;

        /** 上次报告「本地还没有这份媒体」的时间（节流，别每帧刷屏）。 */
        volatile long lastMissMs;
    }

    /** 该控件这次渲染该用 WaterMedia 吗（纯判断，不打开任何东西）。 */
    public static boolean wants(VideoWidget w) {
        if (w == null || !WaterMediaBridge.available()) {
            return false;
        }
        if (DISABLED.contains(w.id)) {
            return false;
        }
        if (w.waterMediaForced()) {
            return true;
        }
        if (w.builtinForced()) {
            return false;
        }
        // 自动：常见的本地视频格式（mp4/webm/mkv…）只有 WaterMedia 放得了；
        // mjpg/zip 是我们自己的格式，内置后端更省事（不用惊动别的模组）。
        return WaterMediaBridge.isNativeVideo(w.mediaName);
    }

    /**
     * 取这一帧要用的纹理；没就绪返回 {@code null}（调用方显示「加载中」占位）。
     *
     * <p>必须在渲染线程调用：里面的 {@code texture()} 是纯读取，真正的解码在
     * WaterMedia 自己的线程上。</p>
     */
    public static ResourceLocation textureFor(VideoWidget w) {
        WaterMediaBridge.noteRenderThread(Thread.currentThread());   // 渲染线程在这里
        if (w == null || w.mediaId == null || w.mediaId.isEmpty()) {
            return null;
        }
        Entry entry = SESSIONS.computeIfAbsent(w.id, id -> new Entry());
        WaterMediaBridge.Session session = entry.session;
        if (session == null) {
            if (!entry.loading && entry.failures < MAX_FAILURES) {
                startAsync(w, entry);
            }
            return null;
        }
        if (!session.ready()) {
            // 还在连源/建解码器：让它先跑起来（start 只需调一次）
            session.start();
            return null;
        }
        session.preRender();         // v2：把解码好的这一帧传到它的 GL 纹理（必须渲染线程）
        // 【hotfix-101】把解码器自报的时长/位置喂回控件：进度条与跳进度都要用它。
        // 外部解码器的视频没有帧表（frameCount=1），靠 fps 算出来的「时长」只有 100ms
        // —— 那正是玩家看到的「进度条乱跳」。
        try {
            long dur = session.durationMs();
            if (dur > 0 && w.mediaDurationMs != dur) {
                w.mediaDurationMs = dur;
                if (entry.lastLogMs == 0L || System.currentTimeMillis() - entry.lastLogMs > 3000L) {
                    entry.lastLogMs = System.currentTimeMillis();
                    Projector.LOGGER.info("[Projector][视频] 读到真实时长 {}ms（{}）", dur, w.mediaName);
                }
            }
            long pos = session.timeMs();
            if (pos >= 0) {
                w.mediaPositionMs = pos;
            }
        } catch (Throwable ignored) {
            // 读不到就退回时间锚点，不影响播放
        }
        session.setLooping(w.loop);  // v2 的循环由播放器负责；v3 是空操作（按时间锚点重播）
        sync(w, session);
        return session.location();
    }

    /** 把控件的时间锚点同步给播放器（暂停 / 跳转 / 循环）。 */
    private static void sync(VideoWidget w, WaterMediaBridge.Session session) {
        session.start();
        session.setPaused(w.paused);
        if (w.paused) {
            return;
        }
        long duration = session.durationMs();
        if (duration <= 0) {
            return;
        }
        long want = desiredPositionMs(w, duration);
        long have = session.timeMs();
        if (Math.abs(want - have) > SEEK_TOLERANCE_MS) {
            session.seek(want);
            long now = System.currentTimeMillis();
            Entry entry = SESSIONS.get(w.id);
            if (entry != null && now - entry.lastLogMs > 3000L) {
                entry.lastLogMs = now;
                Projector.LOGGER.info("[Projector][视频] WaterMedia 跳转 {}ms → {}ms（控件时间锚点）", have, want);
            }
        }
    }

    /**
     * 控件当前应该播放到第几毫秒。
     *
     * <p>**与内置后端同一套时间语义**（{@code VideoWidget.currentFrame}）：
     * {@code startTimeMs > 0} 时以它为锚点算过去多久；为 0 时（老存档/新建）
     * 直接拿当前时间对时长取模 —— 两个后端算出来的位置必须一致，
     * 否则切换后端会看到画面跳一下。</p>
     */
    static long desiredPositionMs(VideoWidget w, long durationMs) {
        long d = Math.max(1L, durationMs);
        if (w.paused) {
            return Math.max(0L, Math.min(d, w.pausedMs));
        }
        long now = System.currentTimeMillis();
        long elapsed = w.startTimeMs > 0 ? Math.max(0L, now - w.startTimeMs) : now;
        if (w.loop) {
            return Math.floorMod(elapsed, d);
        }
        return Math.max(0L, Math.min(d, elapsed));
    }

    private static void startAsync(VideoWidget w, Entry entry) {
        entry.loading = true;
        final String hash = w.mediaId;
        final String name = w.mediaName;
        final boolean loop = w.loop;
        Thread worker = new Thread(() -> {
            WaterMediaBridge.Session session = null;
            boolean hadFile = false;
            try {
                Path file = playablePath(hash, name);
                if (file == null) {
                    // 本地还没这份文件（多人游戏里第一次看到这个控件）：
                    // 让缓存去要**整段文件**，下一帧再试。**这不算失败** ——
                    // 以前算失败，结果下载还没开始控件就被判「打不开」而永久改用内置后端。
                    long now = System.currentTimeMillis();
                    if (now - entry.lastMissMs > 2000L) {
                        entry.lastMissMs = now;
                        Projector.LOGGER.info("[Projector][视频] 本地还没有这份媒体（{}），已在请求整段下载", name);
                        MediaCache.prefetch(hash, true);
                    }
                } else {
                    hadFile = true;
                    session = WaterMediaBridge.open(file, loop);
                }
            } catch (Throwable t) {
                Projector.LOGGER.warn("[Projector][视频] WaterMedia 打开异常：{}", t.toString());
            } finally {
                entry.loading = false;
                if (session != null) {
                    entry.session = session;
                } else if (hadFile) {
                    // 有文件却打不开才算失败（文件损坏 / 解码器不接受）
                    entry.failures++;
                    if (entry.failures >= MAX_FAILURES) {
                        DISABLED.add(w.id);
                        Projector.LOGGER.warn("[Projector][视频] {} 连续 {} 次打不开这份视频，"
                                + "改用内置后端重试", name, entry.failures);
                    }
                }
            }
        }, "Projector-WaterMedia-Open");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    /**
     * 找到能交给 WaterMedia 的本地文件。
     *
     * <p>优先用素材目录里的**原文件**（扩展名正确）；多人游戏里只有缓存
     * （`cache/<sha1>.bin`，没有扩展名）时，就在旁边做一个**同名硬链接**
     * 加上正确扩展名（不复制内容、不额外占空间），让解码器愿意打开它。</p>
     */
    public static Path playablePath(String hash, String mediaName) {
        LocalMedia.MediaFile file = LocalMedia.byHash(hash);
        if (file != null && file.path() != null && Files.isRegularFile(file.path())) {
            return file.path();
        }
        Path cache = MediaCache.cachePath(hash, MediaCache.WHOLE, true);
        if (cache == null || !Files.isRegularFile(cache)) {
            return null;
        }
        String ext = extensionOf(mediaName);
        if (ext.isEmpty()) {
            return cache;
        }
        Path linked = cache.resolveSibling(cache.getFileName() + "." + ext);
        try {
            if (!Files.exists(linked)) {
                try {
                    Files.createLink(linked, cache);
                } catch (Throwable noLink) {
                    Files.copy(cache, linked, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return linked;
        } catch (Throwable t) {
            Projector.LOGGER.debug("[Projector][视频] 建立可播路径失败 {}：{}", cache, t.toString());
            return cache;
        }
    }

    private static String extensionOf(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1);
    }

    /** 控件被删除 / 换素材时释放会话。 */
    public static void release(UUID widgetId) {
        Entry entry = SESSIONS.remove(widgetId);
        DISABLED.remove(widgetId);
        if (entry != null && entry.session != null) {
            entry.session.release();
        }
    }

    /** 退出世界时全部释放。 */
    public static void releaseAll() {
        for (Entry entry : SESSIONS.values()) {
            if (entry.session != null) {
                entry.session.release();
            }
        }
        SESSIONS.clear();
        DISABLED.clear();
    }

    /** 已经在用 WaterMedia 的控件数（诊断用）。 */
    public static int activeCount() {
        int n = 0;
        for (Entry entry : SESSIONS.values()) {
            if (entry.session != null) {
                n++;
            }
        }
        return n;
    }

    /** 诊断用的一行字。 */
    public static String report() {
        return "WaterMedia=" + (WaterMediaBridge.available() ? "有" : "无")
               + " 会话=" + activeCount() + " 加载中=" + SESSIONS.values().stream()
                       .filter(e -> e.loading).count()
               + " 已禁用=" + DISABLED.size();
    }

}


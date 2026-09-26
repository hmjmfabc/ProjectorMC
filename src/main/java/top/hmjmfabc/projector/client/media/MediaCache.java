package top.hmjmfabc.projector.client.media;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import top.hmjmfabc.projector.Projector;
import top.hmjmfabc.projector.ProjectorConfig;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 客户端媒体纹理缓存。
 *
 * <p>职责：把「媒体哈希」变成可以直接绘制的 GPU 纹理，并管理视频的逐帧解码。</p>
 *
 * <ul>
 *   <li><b>图片</b>：首次用到时在后台线程解码，然后在渲染线程生成一次纹理，
 *       之后永久复用（直到玩家手动刷新）。</li>
 *   <li><b>图片下载</b>：本地没有该资源时，向服务端请求，全自动透明处理。</li>
 *   <li><b>视频</b>：只解码「当前这一帧」，用单线程执行器串行解码，最多缓存
 *       {@code video.frameCacheFrames} 帧纹素；播放帧率受配置上限约束。
 *       这样即使 8K 视频也不会一次性吃满显存。</li>
 * </ul>
 */
public final class MediaCache {

    /** 单张图片纹理。 */
    public static final class Entry {
        public final String hash;
        public final int width;
        public final int height;
        public final ResourceLocation location;
        public final NativeImage image;
        /** 原始文件字节（视频要用它来随机访问帧）。 */
        public final byte[] raw;
        public final boolean video;

        Entry(String hash, int width, int height, ResourceLocation location, NativeImage image,
              byte[] raw, boolean video) {
            this.hash = hash;
            this.width = width;
            this.height = height;
            this.location = location;
            this.image = image;
            this.raw = raw;
            this.video = video;
        }
    }

    /**
     * 一个**帧槽位**：一张常驻 GPU 纹理 + 一块可复用的像素缓冲。
     *
     * <p>【rc-85】以前每显示一帧就是「新建 NativeImage（1080p 一帧 ~8 MB）
     * + 新建 DynamicTexture（glTexImage2D）+ 60 秒后 release 掉旧纹理」，
     * 于是播放期间每秒几十 MB 的分配与 GL 对象增删 —— 手机上表现为
     * <b>间歇性卡顿（玩家实测「每 3 秒卡一次」）</b>，因为分配速率把 GC 与驱动都拖住了。</p>
     *
     * <p>现在槽位建好之后<b>永久复用</b>：新帧只做
     * {@code fillNativeImage(texture.getPixels(), frame)} + {@code texture.upload()}，
     * 不再有任何分配、也不再增删 GL 纹理。槽位数量仍是配置项
     * {@code video.frameCacheFrames}（默认 12）。</p>
     */
    public static final class Frame {
        public final ResourceLocation location;
        /** 常驻纹理（建一次；内容靠 upload 更新）。 */
        final DynamicTexture texture;
        /** 当前装着哪一帧；{@code -1} = 空槽。 */
        int frameIndex = -1;
        /** 已经在工作线程填好像素、等着主线程 upload 的帧号；{@code -1} = 没有待上传。 */
        int pendingUpload = -1;
        /** 【rc-87】上一次 upload() 的时刻：保护窗内不许被复用（显卡可能还在读它）。 */
        long lastUploadMs;
        long lastUsed;

        Frame(ResourceLocation location, DynamicTexture texture) {
            this.location = location;
            this.texture = texture;
            this.lastUsed = System.currentTimeMillis();
        }

        /** 槽位里的像素缓冲（尺寸与视频帧一致；由纹理持有）。 */
        public NativeImage image() {
            return texture.getPixels();
        }

        /** 这个槽位现在装着哪一帧（-1 = 空）。 */
        public int frameIndex() {
            return frameIndex;
        }
    }

    /** 某个媒体的全部帧槽位。 */
    private static final class Frames {
        final java.util.List<Frame> slots = new java.util.ArrayList<>();
        /** 【rc-85】必须并发：工作线程在 stageFrame 里写、渲染线程在 videoFrame 里读。
         *  用普通 HashMap 会偶发死循环/丢条目（本项目已经在 LocalMedia 上踩过一次同类坑）。 */
        final Map<Integer, Frame> byFrame = new ConcurrentHashMap<>();

        /** 挑一个可写的槽位（= {@link #pickSlot} 的纯逻辑，输入是本表的快照）。 */
        int pick(int max) {
            int n = slots.size();
            int[] framesArr = new int[n];
            long[] usedArr = new long[n];
            long now = System.currentTimeMillis();
            long hold = slotHoldMs();
            for (int i = 0; i < n; i++) {
                Frame f = slots.get(i);
                boolean busy = f.pendingUpload >= 0 || now - f.lastUploadMs < hold;
                // 「正在等上传」或「刚上传过、显卡可能还在用」的槽位不许动：
                // 帧号填 MAX_VALUE 表示占用中，lastUsed 也拉满，让 LRU 永远不选它
                framesArr[i] = busy ? Integer.MAX_VALUE : f.frameIndex;
                usedArr[i] = busy ? Long.MAX_VALUE : f.lastUsed;
            }
            return pickSlot(framesArr, usedArr, max);
        }
    }

    private static final Map<String, Entry> IMAGES = new ConcurrentHashMap<>();
    private static final Map<String, Frames> VIDEO_FRAMES = new ConcurrentHashMap<>();
    private static final Map<String, VideoSource> VIDEO_SOURCES = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------
    // 每个媒体的加载状态：一个对象装下「加载中 / 失败 / 重试次数 / 上次重试时间 / 已报警」
    //
    // 以前这些是 5 个各自独立的集合（LOADING Set、FAILED Set、RETRY Map、
    // LAST_RETRY Map、MISSING_REPORTED Set），一次加载要同时维护 5 处，
    // 清空时还得逐一列举（漏一个就会留下幽灵状态）。现在合并成一张表。
    // ------------------------------------------------------------------
    private static final class LoadState {
        /** 正在加载（图片为整体加载，视频为某一帧）。 */
        final Set<String> loading = ConcurrentHashMap.newKeySet();
        /** 已判定失败、等待重试。 */
        boolean failed;
        /** 已尝试次数。 */
        int attempts;
        /** 上次尝试时间（毫秒）。 */
        long lastAttemptMs;
        /** 「加载不出来」的警告是否已经打过。 */
        boolean warned;
    }

    /** key = 媒体哈希（视频帧用 {@code "hash#frame"}，加载态与整体共用同一条记录）。 */
    private static final Map<String, LoadState> LOADS = new ConcurrentHashMap<>();

    private static LoadState state(String hash) {
        return LOADS.computeIfAbsent(hash, k -> new LoadState());
    }

    /**
     * 【rc-83】一次分块下载的完整状态。
     *
     * <p><b>这张表就是「同一份媒体同时只允许一次下载」的唯一闸门</b>：
     * 记录只在下述三种情况下才被移除 —— ①收齐（{@code last} 或已到文件尾）
     * ②失败（服务端说没有 / 校验不过）③空闲超过 {@link #DOWNLOAD_STALE_MS}。</p>
     *
     * <p>以前用的是 {@code PARTIALS}（缓冲）＋ {@code LoadState.loading} 里的 {@code "dl"}
     * 标志，而那个标志**每收到一个分片就被移除**，于是「纹理还没就绪」的渲染线程
     * 每一帧都能重新发起一次 {@code offset=0} 的请求：服务端把同一段 200 KB 反复下发，
     * 重组缓冲永远填不到尾部 ⇒ 一份 4 MB 的图片实际下发 58 MB / 98.6 MB 两个会话都收不完，
     * 24 MB 的视频整段下载重复三次，链路被灌满 ⇒ 保活包/操作包被饿死、
     * 玩家表现为「延迟特别大，圈选平面和破坏方块都点不动」，最后连接被 reset。</p>
     */
    private static final class Download {
        final String hash;
        final int frame;
        /** 声明总字节（第一片到达前为 -1）。 */
        int total = -1;
        /** 重组缓冲（按声明总字节分配；声明变化时重建）。 */
        byte[] data;
        /** 已收到的字节数高水位（供「加载中…（x/y，xx%）」显示）。 */
        int received;
        /** 被抑制掉的重复请求次数（> 0 说明有调用方在反复要同一份媒体）。 */
        int suppressed;
        final long startedMs = System.currentTimeMillis();
        long lastChunkMs = startedMs;

        Download(String hash, int frame) {
            this.hash = hash;
            this.frame = frame;
        }

        long idleMs() {
            return System.currentTimeMillis() - lastChunkMs;
        }

        /** 取（必要时按声明长度重建）重组缓冲。 */
        byte[] buffer(int declared) {
            if (declared > 0 && (data == null || data.length != declared)) {
                data = new byte[Math.max(1, declared)];
                total = declared;
            }
            return data;
        }
    }

    /** 在途下载：{@code hash#frame -> Download}（见 {@link Download} 的说明）。 */
    private static final Map<String, Download> DOWNLOADS = new ConcurrentHashMap<>();

    /** 空闲多久算「这次下载已经死了」：超时后才允许重新发起。 */
    private static final long DOWNLOAD_STALE_MS = 15_000L;

    /** 单次请求的分片大小（服务端也按 200 KB 夹一次上界）。 */
    private static final int CHUNK_BYTES = 200 * 1024;

    /** 【rc-83】视频「整段文件」用的帧号：**负数**，与第 0 帧区分开。 */
    public static final int WHOLE = -1;

    /**
     * 【rc-83】发送口：默认走 NeoForge 分发器，测试可替换成「只记录不发包」。
     *
     * <p>无头环境（验证脚本）没有 Minecraft 实例，所以这一层必须可替换，
     * 否则「一次下载只发一次请求、分片偏移必须递增」这条不变量根本无法验证。</p>
     */
    static java.util.function.Consumer<top.hmjmfabc.projector.network.Payloads.MediaRequest> SENDER =
            req -> Minecraft.getInstance().execute(() -> {
                try {
                    net.neoforged.neoforge.network.PacketDistributor.sendToServer(req);
                } catch (Throwable ignored) {
                }
            });

    /** 【rc-83】下载节奏控制（限速用）：单独一条守护线程，别占媒体池。 */
    private static final java.util.concurrent.ScheduledExecutorService PACE =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Projector-Pace");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });

    private static final ThreadFactory FACTORY = r -> {
        Thread t = new Thread(r, "Projector-Media");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    };
    private static ExecutorService WORKER;

    private MediaCache() {
    }

    public static ExecutorService worker() {
        if (WORKER == null || WORKER.isShutdown()) {
            // 【rc-79】**两个**线程。以前是单线程，任何长任务（解码、落盘、等网络）
            // 都会把上传/下载的分片处理排在后面；更要命的是
            // CachePrefetcher 的等待循环也占着它，而它等的东西恰好要在这个池里执行
            // ⇒ 互等死锁（玩家实测：无法上传任何媒体，永远卡在「正在准备…」）。
            WORKER = Executors.newFixedThreadPool(2, FACTORY);
        }
        return WORKER;
    }

    /**
     * 【rc-79】带看门狗的提交：任务排队超过 2 秒就先留一行日志。
     *
     * <p>「卡住」类问题的第一手证据就是「任务提交了但迟迟没开始跑」——
     * 以前完全没有这类日志，只能靠读代码猜。凡是有可能长时间阻塞的任务，
     * 一律走这里提交。</p>
     */
    public static void submit(String what, Runnable task) {
        long queuedAt = System.currentTimeMillis();
        worker().execute(() -> {
            long waited = System.currentTimeMillis() - queuedAt;
            if (waited > 2000L) {
                Projector.LOGGER.warn(
                        "[Projector] 媒体工作线程繁忙：{} 排队 {} ms 才开始（持续出现说明有任务长期占用线程）",
                        what, waited);
            }
            task.run();
        });
    }

    // ------------------------------------------------------------------
    // 图片
    // ------------------------------------------------------------------

    /** 取图片纹理；本地/服务端都还没有时返回 null 并自动发起加载。 */
    @Nullable
    public static Entry image(String hash) {
        if (hash == null || hash.isEmpty()) return null;
        Entry e = IMAGES.get(hash);
        if (e != null) return e;
        // 【rc-83】这份媒体正在下载：**直接返回**，不要再每帧重新发起一次请求。
        // 以前这里没有这道早退，而 requestLoad 的闸门在工作线程交出网络请求后立刻解除，
        // 于是渲染线程 60 次/秒地重发起下载 —— 这正是「同一份媒体被反复下发」的源头之一。
        if (isDownloading(hash, 0)) return null;
        LoadState st = state(hash);
        if (st.loading.contains("")) return null;
        if (st.failed) {
            retryLater(hash);
            reportMissing(hash);
            return null;
        }
        requestLoad(hash, false, 0);
        return null;
    }

    // ------------------------------------------------------------------
    // 【rc-83】在途下载闸门 / 限速 / 发送
    // ------------------------------------------------------------------

    /** 这个 key 是不是正在下载中（未超时）。 */
    static boolean isDownloading(String hash, int frame) {
        Download d = DOWNLOADS.get(key(hash, frame));
        return d != null && d.idleMs() < DOWNLOAD_STALE_MS;
    }

    /** 生效的下载限速（字节/秒，0 = 不限）。 */
    static long downloadPace() {
        try {
            return Math.max(0L, ProjectorConfig.INSTANCE.downloadPaceBytesPerSec.get());
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * 【rc-83】按限速算「这一片之后要等多久」。
     *
     * <p>批量流量（动辄几十 MB 的媒体）与控制流量（挖方块、圈选平面）走同一条连接，
     * 不限速就会把保活包和交互包饿死（第二轮的 72 / 77 两条都是这一族）。
     * 纯函数，便于脚本验证。</p>
     */
    static long paceDelayMs(int bytes, long rateBytesPerSec) {
        if (rateBytesPerSec <= 0L || bytes <= 0) return 0L;
        return Math.max(0L, bytes * 1000L / rateBytesPerSec);
    }

    /** 【rc-83】发一个分片请求（续传走这里，按限速延后发送）。 */
    private static void sendChunkRequest(String hash, int frame, int offset, int length) {
        long delay = paceDelayMs(length, downloadPace());
        if (delay <= 0L) {
            SENDER.accept(new top.hmjmfabc.projector.network.Payloads.MediaRequest(hash, frame, offset, length));
            return;
        }
        try {
            PACE.schedule(() -> SENDER.accept(
                            new top.hmjmfabc.projector.network.Payloads.MediaRequest(hash, frame, offset, length)),
                    delay, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            SENDER.accept(new top.hmjmfabc.projector.network.Payloads.MediaRequest(hash, frame, offset, length));
        }
    }

    private static String shortHash(String hash) {
        return hash == null ? "null" : hash.substring(0, Math.min(8, hash.length()));
    }

    /** 图片一直加载不出来时，每张只报一次，便于定位「重启后纹理丢失」。 */
    private static void reportMissing(String hash) {
        LoadState st = state(hash);
        if (st.warned) return;
        st.warned = true;
        top.hmjmfabc.projector.Projector.LOGGER.warn(
                "[Projector] 图片 {} 加载不出来（本地缓存与服务端都没拿到）。本地索引={} 重试次数={}",
                shortHash(hash), LocalMedia.byHash(hash) != null ? "有" : "没有", st.attempts);
    }

    /**
     * 【rc-82】服务端明确回答「没有这一帧」之后的退避表：{@code 哈希#帧 -> 解禁时刻}。
     *
     * <p>没有它的时候，一个「服务端没有的帧」会在每次渲染时被重新请求（实测同一帧
     * 被请求 70~90 次，每次都写一行失败日志 ⇒ 4.5 分钟 3.1 MB）。</p>
     */
    private static final java.util.Map<String, Long> MISSING_UNTIL =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 退避期间被跳过的请求次数（用于打一行汇总，而不是每次请求都打）。 */
    private static final java.util.Map<String, Integer> MISSING_SKIPS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 退避时长：服务端说没有，就别一直问。 */
    private static final long MISSING_BACKOFF_MS = 30_000L;

    /** 【rc-82】服务端告知的「当日出站已用尽」：已经打过日志的哈希（每份只打一次）。 */
    private static final java.util.Set<String> QUOTA_BLOCKED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 【rc-82】视频的**真实帧数**（服务端下发时带过来的），用来夹住帧号。 */
    private static final java.util.Map<String, Integer> KNOWN_FRAMES =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 【rc-82】已经为某个哈希请求过「整段文件」的标记（流式抖动时只补一次）。 */
    /**
     * 【rc-88】「这个哈希的逐帧请求已经够多，该考虑改成整段下载一次了」——渲染线程只往这里打标记，
     * 真正的判断（要读文件系统）由工作线程在 {@link #escalateToWholeIfNeeded} 里做。
     */
    private static final java.util.Set<String> WHOLE_CANDIDATE =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static final java.util.Set<String> WHOLE_FILE_REQUESTED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 【rc-82】某个哈希已经流式取过多少帧（超过阈值就改走「整段一次拿走」）。 */
    private static final java.util.Map<String, Integer> STREAM_COUNT =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 流式取帧超过这个数量就认为「与其一帧帧要，不如整段要一次」。 */
    private static final int STREAM_TO_WHOLE_THRESHOLD = 60;

    /** 记录服务端告诉我们的视频真实帧数（用于夹住帧号，避免一直问越界的帧）。 */
    public static void noteFrameCount(String hash, int frameCount) {
        if (hash == null || frameCount <= 0) return;
        KNOWN_FRAMES.put(hash, frameCount);
    }

    /** 视频帧纹理。 */
    @Nullable
    public static Frame videoFrame(String hash, int frame) {
        if (hash == null || hash.isEmpty()) return null;
        Frames fs = framesOf(hash);
        Frame f = fs.byFrame.get(frame);
        if (f != null) {
            // lastUsed 会被工作线程的槽位挑选读到，改它要和工作线程同步
            synchronized (fs) {
                f.lastUsed = System.currentTimeMillis();
            }
            return f;
        }
        // 【rc-82 ①】服务端当日出站已用尽：**一个字节都别再要**。
        // 以前这里会一直问，服务端一直拒（实测 4587 次拒绝、每个请求都写一行日志）。
        if (top.hmjmfabc.projector.client.ClientServerInfo.quotaExhausted()) {
            if (QUOTA_BLOCKED.add(hash)) {
                Projector.LOGGER.warn("[Projector][客户端][下载] 服务端当日出站流量已用尽，暂停请求媒体"
                        + "（哈希={}；次日 0:00 自动恢复）", shortHash(hash));
            }
            return null;
        }
        // 【rc-82 ②】帧号越界：服务端早就把真实帧数告诉我们了，别再去问不存在的帧
        Integer known = KNOWN_FRAMES.get(hash);
        if (known != null && frame >= known) {
            return null;
        }
        // 【rc-83】整段下载进行中：**暂停逐帧请求**。
        // 以前整段下载和逐帧播放会同时灌同一条链路（实测 24 MB 的视频整段 + 767 次逐帧），
        // 白白多出成倍的流量，延迟直接崩掉。
        if (isDownloading(hash, WHOLE)) return null;
        // 【rc-85】记下播放头真正在等哪一帧：晚到的旧帧就不必再占主线程上传了
        LAST_WANTED.put(hash, frame);
        // 【rc-82 ③】流式抖动：一帧帧要超过阈值就改走「整段文件要一次」，
        // 之后播放全在本地解码，服务端不用重复下发同一份内容。
        Integer streamed = STREAM_COUNT.merge(hash, 1, Integer::sum);
        // 【rc-88 血泪】这里以前**直接**判断「本地有没有整段」，而 `LocalMedia.hasWhole()`
        // 会 `rescanIfStale()` → 每 2 秒**在渲染线程上**重扫素材目录 + 重算每个文件的 SHA-1
        //（玩家目录里 685 MB 的 mp4 单独就要 1013 ms）⇒ 实测「一画视频就每 2 秒卡 1 秒」。
        // 暂停播放时 videoFrame 提前命中、走不到这里，所以「暂停就不卡」。
        //
        // 现在渲染线程只做一件事：**记下「这个哈希该考虑改成整段下载了」**（纯内存操作）。
        // 真正的判断（要读文件系统：素材目录 + 下载缓存里的两种命名）交给工作线程
        // —— 见 {@link #escalateToWholeIfNeeded}，它在 requestLoad 的 worker 里被调用。
        if (streamed >= STREAM_TO_WHOLE_THRESHOLD) {
            WHOLE_CANDIDATE.add(hash);
        }

        LoadState st = state(hash);
        // 【rc-82】服务端说「没有这一帧」之后，30 秒内不再问同一帧（只累计跳过次数）
        String mk = key(hash, frame);
        Long until = MISSING_UNTIL.get(mk);
        if (until != null) {
            if (System.currentTimeMillis() < until) {
                MISSING_SKIPS.merge(mk, 1, Integer::sum);
                return null;
            }
            MISSING_UNTIL.remove(mk);
            Integer skipped = MISSING_SKIPS.remove(mk);
            if (skipped != null && skipped > 0) {
                Projector.LOGGER.info("[Projector][客户端][下载] 服务端没有这一帧，退避期间已跳过 {} 次重复请求"
                        + "（哈希={} 帧={}）", skipped, shortHash(hash), frame);
            }
        }
        if (st.loading.contains(String.valueOf(frame))) return null;
        if (st.failed) {
            retryLater(hash);
            return null;
        }
        requestLoad(hash, true, frame);
        return null;
    }

    /**
     * 【rc-85】挑一个要写入的槽位下标 —— <b>纯函数</b>，便于脚本验证。
     *
     * <p>规则：①先找空槽（{@code frameIndex < 0}，且 {@code pendingUpload} 用 0 表示「占用中」）
     * ②槽位还没建满就返回 {@code slots.length}（调用方新建一个）
     * ③都满了就换掉最久未用的那个（LRU）—— 复用它的纹理，不新建 GL 对象。</p>
     */
    static int pickSlot(int[] frameIndexes, long[] lastUsed, int max) {
        int n = frameIndexes.length;
        for (int i = 0; i < n; i++) {
            if (frameIndexes[i] < 0 && lastUsed[i] != Long.MAX_VALUE) return i;
        }
        if (n < Math.max(1, max)) return n;
        // 全部槽位都在「保护窗」里（刚上传过 / 等着上传）⇒ 返回 -1：
        // 这一帧干脆不画，等下一帧。宁可掉帧，也不要把显卡还在用的纹理改掉
        //（那会强制驱动同步，正是玩家实测「画视频就卡」的形态之一）。
        int worst = -1;
        for (int i = 0; i < n; i++) {
            if (lastUsed[i] == Long.MAX_VALUE) continue;
            if (worst < 0 || lastUsed[i] < lastUsed[worst]) worst = i;
        }
        return worst;
    }

    /** 取（必要时建立）某个媒体的槽位表。 */
    private static Frames framesOf(String hash) {
        return VIDEO_FRAMES.computeIfAbsent(hash, k -> new Frames());
    }

    /** 最多保留多少个帧槽位（配置项 {@code video.frameCacheFrames}，默认 12）。 */
    private static int maxFrameSlots() {
        try {
            // 【rc-87】至少 8 个：保护窗 + 手机上几百毫秒的 GPU 队列深度下，
            // 4 个槽位会让大半帧都被跳过（玩家日志里就是「槽位=4」）。
            return Math.max(8, ProjectorConfig.INSTANCE.videoFrameCacheFrames.get());
        } catch (Throwable t) {
            return 12;
        }
    }

    private static void requestLoad(String hash, boolean video, int frame) {
        final String key = video ? hash + "#" + frame : hash;
        if (video) {
            if (!state(hash).loading.add(String.valueOf(frame))) return;
        } else {
            if (!state(hash).loading.add("")) return;
        }
        LoadState st = state(hash);
        st.attempts++;
        st.lastAttemptMs = System.currentTimeMillis();
        worker().execute(() -> {
            try {
                byte[] data = null;
                Path path = null;
                // 【rc-88】渲染线程只打了「候选」标记，判断与文件系统读取都在工作线程做。
                if (video) escalateToWholeIfNeeded(hash);

                if (video) {
                    // 1) 本地有完整视频文件：直接按帧解码
                    // 【rc-84】byHash 现在也认「下载缓存里的整段视频」（hash.bin 与旧的 hash_f0.bin），
                    // 所以这里不再需要自己拼 cacheDir 路径 —— 以前那条判断只认 hash.bin，
                    // 旧缓存（hash_f0.bin）命中不了，于是把本地已有的整段视频又向服务端要了一遍。
                    LocalMedia.MediaFile whole = LocalMedia.byHash(hash);
                    boolean wholeIsVideo = whole != null;
                    if (wholeIsVideo) {
                        Path p = whole.path();
                        VideoSource src = VIDEO_SOURCES.get(hash);
                        if (src == null) {
                            src = VideoSource.open(p);
                            if (src != null) VIDEO_SOURCES.put(hash, src);
                        }
                        if (src == null) {
                            markFailed(hash);
                            return;
                        }
                        long td = System.nanoTime();
                        ImageCodec.Decoded decoded = src.decode(frame);
                        if (decoded == null) return;
                        ImageCodec.Decoded sized = ImageCodec.downscale(decoded,
                                ProjectorConfig.INSTANCE.maxDecodedImageSize.get());
                        DECODE_NANOS.addAndGet(System.nanoTime() - td);
                        stageFrame(hash, frame, sized);
                        return;
                    }
                    // 2) 本地没有：向服务端要「这一帧」（单包即可，通常一帧 < 200KB）
                    loadFrameFromServer(hash, frame);
                    return;
                }

                LocalMedia.MediaFile file = LocalMedia.byHash(hash);
                if (file == null) {
                    // 【关键】进世界后第一次渲染常常早于本地索引建好，
                    // 这时 byHash 会误报「本地没有」，白白走一次服务端请求
                    //（失败后要等 10 秒重试才恢复，表现就是「重启后图片丢了」）。
                    // 这里补一次索引刷新再查，能命中就完全不需要网络。
                    try {
                        LocalMedia.rescanIfStale();
                    } catch (Throwable ignored) {
                        // 刷新失败就按原逻辑走服务端
                    }
                    file = LocalMedia.byHash(hash);
                }
                if (file != null) {
                    path = file.path();
                    data = java.nio.file.Files.readAllBytes(path);
                } else {
                    requestFromServer("按需渲染", hash, 0);
                    return;
                }
                finish(hash, data, path, false, 0);
            } catch (Throwable t) {
                Projector.LOGGER.warn("[Projector] 载入媒体失败 {}: {}", hash, t.toString());
                markFailed(hash);
            } finally {
                state(hash).loading.remove(video ? String.valueOf(frame) : "");
            }
        });
    }

    /**
     * 【rc-88】「要不要把逐帧播放改成整段下载一次」——**只允许在工作线程调用**。
     *
     * <p>这里会走 {@link LocalMedia#hasWhole(String)}：它会重扫素材目录（可能给大文件算 SHA-1），
     * 还会检查下载缓存里的两种命名（{@code <hash>.bin} 与旧的 {@code <hash>_f0.bin}）。
     * 以前这段判断在渲染线程上，正是「一画视频就每 2 秒卡 1 秒」的根因；现在渲染线程只往
     * {@link #WHOLE_CANDIDATE} 打标记，由这里做决定。</p>
     *
     * <p>rc-84 的语义必须保留：本地已经有整段（含旧命名）时**不要**再向服务端要一次 ——
     * 否则就是「明明有还去要一遍」的老毛病（回归见 {@code tmp/v23}）。</p>
     */
    static void escalateToWholeIfNeeded(String hash) {
        if (hash == null || hash.isEmpty()) return;
        if (!WHOLE_CANDIDATE.contains(hash)) return;
        Integer streamed = STREAM_COUNT.get(hash);
        if (streamed == null || streamed < STREAM_TO_WHOLE_THRESHOLD) return;
        if (WHOLE_FILE_REQUESTED.contains(hash)) {
            WHOLE_CANDIDATE.remove(hash);
            return;
        }
        try {
            if (LocalMedia.hasWhole(hash)) {
                WHOLE_CANDIDATE.remove(hash);      // 本地已有整段：不必切换，也不必再问
                return;
            }
        } catch (Throwable t) {
            return;                                // 查不出来就保守处理：继续逐帧，下次再说
        }
        if (!WHOLE_FILE_REQUESTED.add(hash)) return;
        WHOLE_CANDIDATE.remove(hash);
        Projector.LOGGER.info("[Projector][客户端][下载] 已流式取帧 {} 次，改为整段下载一次"
                + "（避免服务端反复下发同一份媒体）哈希={}", streamed, shortHash(hash));
        // 【rc-83】整段文件用帧号 -1：以前用 0，而服务端把「帧 0」当成「整段文件」，
        // 于是视频每循环回第 0 帧都会重发一次整段请求（实测 3 次 24 MB）。
        requestFromServer("按需渲染（流式取帧 " + streamed + " 次，改整段下载）", hash, WHOLE);
    }

    /**
     * 从服务端拉取视频的某一帧（一次性请求，最多 200 KB）。
     *
     * <p>【rc-83】帧号 0 现在**是**第 0 帧（合法的单帧请求）：以前协议里
     * {@code frame == 0} 被当成「整段文件」，于是每次播放循环回第 0 帧，
     * 服务端都会重发一次整段文件 —— 24 MB 的视频被重复下发 3 次，
     * 全靠这个语义混淆撑起来的。整段文件现在改用 {@link #WHOLE}（-1）。</p>
     */
    private static void loadFrameFromServer(String hash, int frame) {
        requestFromServer("按需渲染（视频逐帧）", hash, frame);
    }

    /** 网络线程/工作线程调用：数据到手后完成解码。 */
    public static void finish(String hash, byte[] data, @Nullable Path path, boolean video, int frame) {
        if (data == null || data.length == 0) {
            Projector.LOGGER.warn("[Projector] 媒体 {} 数据为空（path={}）", shortHash(hash), path);
            markFailed(hash);
            return;
        }
        if (video) {
            // 视频源与帧缓存：只有拿到「完整文件」时才需要整段映射；
            // 服务端按需下发的单帧走的是内存解码路径（path 指向该帧的缓存文件）。
            VideoSource src = VIDEO_SOURCES.get(hash);
            if (src == null && path != null && java.nio.file.Files.isRegularFile(path)) {
                // 不要经 LocalMedia.byHash 判断：本地用户文件的索引键是 "local:<路径>"，
                // 按 SHA-1 是查不到的，那样刚上传的视频会一直显示占位块。
                src = VideoSource.open(path);
                if (src != null) {
                    VIDEO_SOURCES.put(hash, src);
                    // 顺便登记，后续按 hash 也能直接查到这份文件
                    LocalMedia.registerCache(hash, path);
                }
            }
            if (src == null) {
                markFailed(hash);
                // 【rc-83】整段文件到手却打不开（不是可播放的 MJPEG/ZIP 之类）：
                // 把整段标记撤掉，让重试回到逐帧流式 —— 否则会一直卡在「加载中…」。
                if (frame < 0) {
                    WHOLE_FILE_REQUESTED.remove(hash);
                    STREAM_COUNT.remove(hash);
                    Projector.LOGGER.warn("[Projector][客户端][下载] 整段视频已下载但打不开，退回逐帧播放"
                            + "（哈希={} 路径={}）", shortHash(hash), path);
                }
                return;
            }
            // 【rc-83】整段下载（frame < 0）先解第 0 帧顶上，播放随后全部走本地文件
            int want = frame < 0 ? 0 : frame;
            ImageCodec.Decoded decoded = src.decode(want);
            if (decoded == null && frame < 0) {
                Projector.LOGGER.warn("[Projector][客户端][下载] 整段视频第 0 帧解码失败（哈希={}）",
                        shortHash(hash));
                return;
            }
            if (decoded == null) return;
            ImageCodec.Decoded sized = ImageCodec.downscale(decoded,
                    ProjectorConfig.INSTANCE.maxDecodedImageSize.get());
            stageFrame(hash, want, sized);
            return;
        }
        ImageCodec.Decoded decoded = ImageCodec.decode(data);
        if (decoded == null) {
            Projector.LOGGER.warn("[Projector] 图片 {} 解码失败（{} 字节，path={}）",
                    shortHash(hash), data.length, path);
            markFailed(hash);
            return;
        }
        ImageCodec.Decoded sized = ImageCodec.downscale(decoded,
                ProjectorConfig.INSTANCE.maxDecodedImageSize.get());
        Minecraft.getInstance().execute(() -> uploadImage(hash, sized, data));
    }

    private static void uploadImage(String hash, ImageCodec.Decoded decoded, byte[] raw) {
        if (IMAGES.containsKey(hash)) return;
        NativeImage img = new NativeImage(NativeImage.Format.RGBA, decoded.width(), decoded.height(), false);
        ImageCodec.fillNativeImage(img, decoded);
        ResourceLocation loc = Projector.id("media/" + sanitize(hash));
        TextureManager tm = Minecraft.getInstance().getTextureManager();
        tm.register(loc, new DynamicTexture(img));
        IMAGES.put(hash, new Entry(hash, decoded.width(), decoded.height(), loc, img, raw, false));
        Projector.LOGGER.debug("[Projector] 图片纹理就绪 {} ({}x{})", hash, decoded.width(), decoded.height());
    }

    /**
     * 【rc-85】把解好的一帧交给槽位：<b>像素填充在工作线程做，主线程只做 GL 上传</b>。
     *
     * <p>为什么要这样切：把整帧像素写进 {@code NativeImage} 是<b>纯 CPU</b> 操作
     *（{@code setPixelRGBA} 逐像素，1024x576 一帧 59 万次调用，实测在手机上要十几毫秒），
     * 以前它在主线程上跑 —— 于是<b>视频的每一帧都在主线程上啃十几毫秒</b>，
     * 播放时表现为「客户端间歇性卡顿」（玩家报的就是「每 3 秒卡一次」）。
     * 现在这段搬到媒体的工作线程，主线程只剩 {@code texture.upload()}（驱动侧拷贝）。</p>
     *
     * <p>同时：①槽位建好之后永久复用（不再每帧新建纹理/图像，也不再 release）
     * ②播放头已经走过去的「过时帧」直接丢掉，不占主线程。</p>
     */
    private static void stageFrame(String hash, int frame, ImageCodec.Decoded decoded) {
        Frames fs = framesOf(hash);
        synchronized (fs) {
            if (fs.byFrame.containsKey(frame)) return;

            // 过时帧直接丢：播放头已经领先 2 帧以上，上传它没有意义（马上会被替换）
            int wanted = LAST_WANTED.getOrDefault(hash, frame);
            if (frame < wanted - 1) {
                STALE_SKIPPED.incrementAndGet();
                state(hash).loading.remove(String.valueOf(frame));
                return;
            }

            int max = maxFrameSlots();
            int idx = fs.pick(max);
            if (idx < 0) {
                // 所有槽位都在保护窗里：这一帧不画（掉帧），别去动显卡正在读的纹理
                GPU_HELD_SKIPPED.incrementAndGet();
                state(hash).loading.remove(String.valueOf(frame));
                return;
            }
            Frame slot;
            if (idx == fs.slots.size()) {
                slot = newSlot(hash, fs, idx, decoded);      // 建槽位（含一次 new NativeImage + 注册纹理）
                fs.slots.add(slot);
                SLOTS_CREATED.incrementAndGet();
            } else {
                slot = fs.slots.get(idx);
                if (slot.frameIndex >= 0) fs.byFrame.remove(slot.frameIndex);
                NativeImage px = slot.texture.getPixels();
                if (px == null || px.getWidth() != decoded.width() || px.getHeight() != decoded.height()) {
                    // 分辨率变了（同一个哈希换了源文件）才需要换像素缓冲；正常永远走不到这里
                    NativeImage img = new NativeImage(NativeImage.Format.RGBA,
                            Math.max(1, decoded.width()), Math.max(1, decoded.height()), false);
                    slot.texture.setPixels(img);             // setPixels 会关掉旧的（已核对字节码）
                }
            }
            // ↓ 工作线程：只写内存，不碰 GL
            long tf = System.nanoTime();
            ImageCodec.fillNativeImage(slot.texture.getPixels(), decoded);
            FILL_NANOS.addAndGet(System.nanoTime() - tf);
            slot.pendingUpload = frame;
            FRAMES_FILLED.incrementAndGet();
        }
        // ↓ 主线程：只做 GL 上传 + 记账（几微秒 + 一次驱动拷贝）
        Minecraft.getInstance().execute(() -> commitFrame(hash, frame));
    }

    /** 主线程：把「已经填好像素」的槽位上传到 GPU，并把它登记成第 {@code frame} 帧。 */
    private static void commitFrame(String hash, int frame) {
        long t0 = System.nanoTime();
        Frames fs = VIDEO_FRAMES.get(hash);
        if (fs == null) return;
        synchronized (fs) {
            if (fs.byFrame.containsKey(frame)) return;
            Frame slot = null;
            for (Frame f : fs.slots) {
                if (f.pendingUpload == frame) {
                    slot = f;
                    break;
                }
            }
            if (slot == null) return;
            // 又一次过时检查：主线程排队期间播放头可能又走了
            int wanted = LAST_WANTED.getOrDefault(hash, frame);
            if (frame < wanted - 1) {
                STALE_SKIPPED.incrementAndGet();
                slot.pendingUpload = -1;
                state(hash).loading.remove(String.valueOf(frame));
                return;
            }
            long tu = System.nanoTime();
            slot.texture.upload();                     // 同一张纹理写像素：不新建 GL 对象
            UPLOAD_NANOS.addAndGet(System.nanoTime() - tu);
            slot.pendingUpload = -1;
            slot.frameIndex = frame;
            slot.lastUsed = System.currentTimeMillis();
            fs.byFrame.put(frame, slot);
            FRAMES_UPLOADED.incrementAndGet();
            slot.lastUploadMs = System.currentTimeMillis();
            long dur = System.nanoTime() - t0;
            MAIN_NANOS.addAndGet(dur);
            noteMainOp("纹理上传", dur);
            long durMs = dur / 1_000_000L;
            // 【rc-87】上传一旦变慢就加大保护窗（GPU 队列深），持续快就慢慢收回
            if (durMs >= SLOW_UPLOAD_MS) {
                long next = Math.min(MAX_SLOT_HOLD_MS, Math.max(MIN_SLOT_HOLD_MS, SLOT_HOLD_MS * 2));
                if (next != SLOT_HOLD_MS) {
                    SLOT_HOLD_MS = next;
                    Projector.LOGGER.info("[Projector][客户端][卡顿] 纹理上传出现 {} ms 停顿，"
                            + "把帧槽位保护窗调到 {} ms（避免复写显卡还在用的纹理）", durMs, next);
                }
                FAST_UPLOADS.set(0);
            } else if (durMs <= 20L && SLOT_HOLD_MS > MIN_SLOT_HOLD_MS
                    && FAST_UPLOADS.incrementAndGet() > 120L) {
                FAST_UPLOADS.set(0);
                SLOT_HOLD_MS = Math.max(MIN_SLOT_HOLD_MS, SLOT_HOLD_MS - SLOT_HOLD_MS / 8);
            }
            if (durMs >= 50L && System.currentTimeMillis() - lastSlowUploadLog > 10_000L) {
                lastSlowUploadLog = System.currentTimeMillis();
                NativeImage px = slot.texture.getPixels();
                Projector.LOGGER.warn("[Projector][客户端][卡顿] 视频帧上传慢：{} ms"
                                + "（尺寸={}x{} 帧={}；这一句说明卡在 GL 上传，不是编码/解码）",
                        durMs, px == null ? 0 : px.getWidth(), px == null ? 0 : px.getHeight(), frame);
            }
            return;
        }
    }

    /** 建一个新的帧槽位（一张常驻纹理 + 一块像素缓冲）。每个媒体最多 {@code maxFrameSlots()} 个。 */
    private static Frame newSlot(String hash, Frames fs, int idx, ImageCodec.Decoded decoded) {
        NativeImage img = new NativeImage(NativeImage.Format.RGBA,
                Math.max(1, decoded.width()), Math.max(1, decoded.height()), false);
        ResourceLocation loc = Projector.id("video/" + sanitize(hash) + "_slot" + idx);
        DynamicTexture tex = new DynamicTexture(img);
        Minecraft.getInstance().getTextureManager().register(loc, tex);
        return new Frame(loc, tex);
    }

    // ------------------------------------------------------------------
    // 【rc-86】耗时统计：把「卡顿到底是不是本模组造成的」变成数字
    // ------------------------------------------------------------------
    /** 主线程：commitFrame 总耗时（含 GL 上传）。 */
    private static final java.util.concurrent.atomic.AtomicLong MAIN_NANOS =
            new java.util.concurrent.atomic.AtomicLong();
    /** 主线程：其中 texture.upload() 的耗时。 */
    private static final java.util.concurrent.atomic.AtomicLong UPLOAD_NANOS =
            new java.util.concurrent.atomic.AtomicLong();
    /** 工作线程：解码耗时。 */
    private static final java.util.concurrent.atomic.AtomicLong DECODE_NANOS =
            new java.util.concurrent.atomic.AtomicLong();
    /** 工作线程：填像素耗时。 */
    private static final java.util.concurrent.atomic.AtomicLong FILL_NANOS =
            new java.util.concurrent.atomic.AtomicLong();
    /**
     * 【rc-87】帧槽位保护窗：刚上传过的槽位在这么多毫秒内不许被复用。
     *
     * <p>手机上（FCL + 图形转发层）渲染命令是排队交给 GPU 的，队列可能有几百毫秒深；
     * 若此时往显卡还在采样的纹理里写新像素，驱动就必须<b>同步等待</b>——
     * 表现为「画视频时每隔一两秒卡一下」。这里<b>自适应</b>：一旦某次 upload 超过
     * {@link #SLOW_UPLOAD_MS}，就把保护窗翻倍（上限 {@link #MAX_SLOT_HOLD_MS}）；
     * 连续上传都快时再慢慢降回去。宁可掉几帧，也不要卡主线程。</p>
     */
    private static volatile long SLOT_HOLD_MS = 200L;
    private static final long MIN_SLOT_HOLD_MS = 100L;
    private static final long MAX_SLOT_HOLD_MS = 2000L;
    private static final long SLOW_UPLOAD_MS = 60L;
    private static final java.util.concurrent.atomic.AtomicLong FAST_UPLOADS =
            new java.util.concurrent.atomic.AtomicLong();

    private static long slotHoldMs() {
        return SLOT_HOLD_MS;
    }

    /** 【rc-87】保护窗当前值（诊断/测试用）。 */
    public static long slotHoldMsForTest() {
        return SLOT_HOLD_MS;
    }

    /** 【rc-86】「上传慢」日志的限流时间戳。 */
    private static volatile long lastSlowUploadLog;
    /** 主线程上单次最久的操作（诊断用：停顿那一刻到底卡在哪个调用上）。 */
    private static final java.util.concurrent.atomic.AtomicLong MAX_MAIN_OP_NANOS =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicReference<String> MAX_MAIN_OP =
            new java.util.concurrent.atomic.AtomicReference<>("-");

    private static void noteMainOp(String what, long nanos) {
        long prev = MAX_MAIN_OP_NANOS.get();
        if (nanos > prev && MAX_MAIN_OP_NANOS.compareAndSet(prev, nanos)) {
            MAX_MAIN_OP.set(what + " " + (nanos / 1_000_000L) + "ms");
        }
    }

    /** 【rc-86】各段耗时快照：{主线程ms, 上传ms, 解码ms, 填帧ms, 最大单次ms}。 */
    public static long[] timings() {
        return new long[]{MAIN_NANOS.get() / 1_000_000L, UPLOAD_NANOS.get() / 1_000_000L,
                DECODE_NANOS.get() / 1_000_000L, FILL_NANOS.get() / 1_000_000L,
                MAX_MAIN_OP_NANOS.get() / 1_000_000L};
    }

    /** 【rc-86】主线程上最久的那一次操作（名字+耗时）。 */
    public static String maxMainOp() {
        return MAX_MAIN_OP.get();
    }

    /** 【rc-86】某个媒体当前解码出来的像素尺寸（诊断用）；没有则 null。 */
    @Nullable
    public static int[] decodedSize(String hash) {
        Frames fs = VIDEO_FRAMES.get(hash);
        if (fs == null || fs.slots.isEmpty()) return null;
        NativeImage px = fs.slots.get(0).texture.getPixels();
        return px == null ? null : new int[]{px.getWidth(), px.getHeight()};
    }

    /** 【rc-85】播放头当前真正在等哪一帧（渲染线程写、工作线程读）——用来丢掉过时帧。 */
    private static final Map<String, Integer> LAST_WANTED = new ConcurrentHashMap<>();
    /** 【rc-85】统计量（卡顿看门狗打日志用）：填了像素的帧 / 丢掉的过时帧 / 建过的槽位 / 已上传。 */
    private static final java.util.concurrent.atomic.AtomicLong FRAMES_FILLED =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong FRAMES_UPLOADED =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong STALE_SKIPPED =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong SLOTS_CREATED =
            new java.util.concurrent.atomic.AtomicLong();
    /** 【rc-87】因为「保护窗内没有可用槽位」而跳过的帧数。 */
    private static final java.util.concurrent.atomic.AtomicLong GPU_HELD_SKIPPED =
            new java.util.concurrent.atomic.AtomicLong();

    private static String sanitize(String hash) {
        StringBuilder sb = new StringBuilder(hash.length());
        for (int i = 0; i < hash.length(); i++) {
            char c = hash.charAt(i);
            sb.append(Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == '/' ? c : '_');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 服务端拉取
    // ------------------------------------------------------------------

    /** 正在下载中的分片：hash#frame -> 已累积的字节。 */


    private static String key(String hash, int frame) {
        return hash + "#" + frame;
    }

    // ------------------------------------------------------------------
    // 【②⑫】对外的三个小工具：本地是否已有 / 传输进度 / 主动预取
    // ------------------------------------------------------------------

    /**
     * 本地（素材目录或 cache 目录）是否已经有这份媒体。
     *
     * <p>{@code LocalMedia} 的索引同时按路径与 SHA-1 建立，所以这一句就覆盖了
     * 「我本机就有这个文件」和「以前从服务端下载过」两种情况。</p>
     */
    public static boolean isLocallyAvailable(String hash) {
        if (hash == null || hash.isEmpty()) return false;
        if (!top.hmjmfabc.projector.server.Sanitize.isHash(hash)) return false;
        try {
            return LocalMedia.byHash(hash) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 【⑫】正在传输的进度：{@code {已收字节, 总字节}}；没有进行中的传输时返回 null。
     *
     * @param frame 图片传 0；视频传当前播放帧（找不到时自动回退到「整段下载」那条记录）
     */
    @Nullable
    public static long[] transferProgress(String hash, int frame) {
        if (hash == null || hash.isEmpty()) return null;
        Download d = DOWNLOADS.get(key(hash, frame));
        if (d == null) {
            // 【rc-83】视频整段下载时播放帧是变化的，进度要看整段那条记录
            d = DOWNLOADS.get(key(hash, WHOLE));
        }
        if (d == null) return null;
        long total = d.total > 0 ? d.total : Math.max(1, d.received);
        return new long[]{d.received, total};
    }

    /**
     * 【⑫】主动请求下载一份媒体（供「平衡 / 激进」两档的后台预缓存使用）。
     *
     * <p>已经存在于本地时直接返回 false，<b>一个字节都不会发出去</b>——
     * 这正是用户 ② 想要的「先比哈希、命中就不下载」。</p>
     */
    public static boolean prefetch(String hash, boolean video) {
        if (!top.hmjmfabc.projector.server.Sanitize.isHash(hash)) return false;
        if (isLocallyAvailable(hash)) return false;
        int frame = video ? WHOLE : 0;
        if (isDownloading(hash, frame)) return false;      // 【rc-83】已经在下了，别重发
        requestFromServer("后台预缓存（缓存档位）", hash, frame);
        return true;
    }

    /**
     * 【rc-83】向服务端发起**一次**下载（唯一入口）。
     *
     * <p>同一个 {@code hash#frame} 只要还有在途下载（且没超时），这里就**只累计、不发送** ——
     * 这是本轮延迟风暴的根治点：以前「在途」标志挂在 {@code LoadState.loading} 上、
     * 每收一个分片就被移除，于是每一次渲染都能重新发一次 {@code offset=0} 的请求，
     * 服务端把同一段内容反复下发（实测一张图片下发 58 MB / 98.6 MB 两个会话都收不完）。</p>
     *
     * @param reason 日志里的下载原因（一次下载只打一行「开始」）
     */
    static void requestFromServer(String reason, String hash, int frame) {
        final String k = key(hash, frame);
        Download live = DOWNLOADS.get(k);
        if (live != null) {
            if (live.idleMs() < DOWNLOAD_STALE_MS) {
                live.suppressed++;
                TransferLog.downloadSuppressed(hash, frame, live.suppressed, live.received, live.total);
                return;
            }
            DOWNLOADS.remove(k, live);
            Projector.LOGGER.info("[Projector][客户端][下载] 下载超时重来（{} ms 没有新分片）哈希={} 帧={}",
                    live.idleMs(), shortHash(hash), frame < 0 ? "整段" : frame);
        }
        DOWNLOADS.put(k, new Download(hash, frame));
        TransferLog.downloadStart(reason, hash, frame, frame != 0 || videoOf(hash));
        // 首片立即发（限速只作用在续传上：一次下载的第一片永远不等）
        SENDER.accept(new top.hmjmfabc.projector.network.Payloads.MediaRequest(hash, frame, 0, CHUNK_BYTES));
    }

    /**
     * 收到服务端下发的媒体分片。
     *
     * <p>分片按 offset 累积在内存里（同时也会提升对应的下载请求），
     * 全部收齐后才解码并生成纹理——这样一个视频帧即使被拆成多个包
     * 也不会互相覆盖。</p>
     */
    /**
     * 收到服务端下发的媒体分片。
     *
     * <p>这个方法由网络线程调用，因此只做一次数组拷贝就立刻返回；
     * 累积、落盘与解码全部交给后台线程，避免堵住 netty 连接导致读超时掉线。</p>
     */
    public static void onMediaChunk(String hash, int frame, int offset, int total, boolean video,
                                    byte[] data, boolean last, boolean missing) {
        final String k = key(hash, frame);
        if (missing) {
            // 【rc-82】把**真实原因**写清楚：以前不管什么原因都写「服务端没有这份媒体」，
            // 结果玩家看到 4587 行假原因（真实原因是「当日出站流量已达上限」）。
            String why = top.hmjmfabc.projector.client.ClientServerInfo.quotaExhausted()
                    ? "服务端当日出站流量已达上限" : "服务端没有这份媒体（或读取失败）";
            // 【rc-82】只在这一帧真的登记过「开始」时写失败行；否则只累计
            boolean logged = TransferLog.downloadFail(hash, frame, why);
            // 退避：30 秒内不再为这一帧发请求，期间只累计跳过次数（避免刷屏）
            MISSING_UNTIL.put(key(hash, frame), System.currentTimeMillis() + MISSING_BACKOFF_MS);
            if (MISSING_SKIPS.size() > 4096) {
                MISSING_SKIPS.clear();       // 兜底：极端情况下别把表撑爆
            }
            if (MISSING_UNTIL.size() > 4096) {
                long nowMs = System.currentTimeMillis();
                MISSING_UNTIL.entrySet().removeIf(e -> e.getValue() < nowMs);
            }
            markFailed(hash);
            DOWNLOADS.remove(k);
            if (!logged) {
                Projector.LOGGER.debug("[Projector] 重复的「服务端没有这份媒体」哈希={} 帧={}",
                        shortHash(hash), frame);
            }
            return;
        }
        final byte[] copy = data == null ? new byte[0] : data.clone();
        // 【rc-79】分片处理必须走带看门狗的提交：它要是排队太久，下载就永远不会完成，
        // 而「等下载完成」的调用方（预缓存 / 加载中显示）会一直等下去。
        submit("处理媒体分片 " + hash, () -> {
            try {
                handleChunk(hash, frame, offset, total, video, copy, last);
            } catch (Throwable t) {
                Projector.LOGGER.warn("[Projector] 处理媒体分片失败 {}: {}", hash, t.toString());
            }
        });
    }

    private static void handleChunk(String hash, int frame, int offset, int total, boolean video,
                                    byte[] data, boolean last) {
        // 【②】分片只累加计数，不逐片打日志（一次 64 MB 会切成几百片）
        TransferLog.downloadChunk(hash, frame, data == null ? 0 : data.length);
        final String k = key(hash, frame);
        if (!top.hmjmfabc.projector.server.Sanitize.isHash(hash)) {
            markFailed(hash);
            DOWNLOADS.remove(k);
            return;
        }
        if (total <= 0 || total > 64 * 1024 * 1024) {
            // 【rc-76】以前这里静默 markFailed ⇒ 玩家只看到控件一直「加载中…」而日志里一个字都没有。
            // 现在写明原因（分块下载目前把整个文件缓在内存里，所以有这条硬上限）。
            Projector.LOGGER.warn("[Projector][客户端][下载] 拒绝接收：声明大小 {} 字节"
                            + "（>64MB 的媒体暂时不能分块下载，见 PROJECTOR_GUIDE 已知限制）哈希={}",
                    total, hash);
            TransferLog.downloadFail(hash, frame, "声明大小 " + total + " 字节超过 64MB 分块下载上限");
            markFailed(hash);
            DOWNLOADS.remove(k);
            return;
        }
        if (data == null || data.length == 0) {
            DOWNLOADS.remove(k);
            return;
        }
        // 顺手清扫「卡住的分块下载」：中断的下载会把缓冲永久留在内存里
        //（一个 24 MB 的视频卡一次就是 24 MB 泄漏）。stale 判定与「能否重发」共用同一个阈值。
        DOWNLOADS.entrySet().removeIf(en -> en.getValue().idleMs() > 120_000L);
        // 迟到的回包（在途记录已被超时清掉）也要认，否则这一片白收、偏移链就断了
        Download dl = DOWNLOADS.computeIfAbsent(k, x -> new Download(hash, frame));
        byte[] buffer = dl.buffer(total);
        if (buffer == null) {
            DOWNLOADS.remove(k);
            markFailed(hash);
            return;
        }
        dl.lastChunkMs = System.currentTimeMillis();
        int end = Math.min(buffer.length, offset + data.length);
        if (offset >= 0 && offset < buffer.length) {
            System.arraycopy(data, 0, buffer, offset, Math.max(0, end - offset));
        }
        // 【⑫】记下高水位，供「加载中…（xxxBytes/xxxBytes，xx%）」显示
        dl.received = Math.max(dl.received, Math.max(0, end));

        final int next = offset + data.length;
        if (!last && dl.total > 0 && next < dl.total) {
            // 【rc-83】续传：**不解除在途标志、也不登记新的「开始」**（限速后的下一片）。
            // 以前这里没有任何在途标志，而调用方每帧都会重新发一次 offset=0 的请求 ⇒
            // 同一份媒体的同一段被服务端反复下发，重组缓冲永远填不到尾。
            sendChunkRequest(hash, frame, next, dl.total - next);
            return;
        }

        DOWNLOADS.remove(k);
        byte[] full = dl.data;
        if (full == null) return;

        try {
            // 服务端媒体没有本地路径：写入缓存目录，按内容分文件避免互相覆盖
            Path cached = cachePath(hash, frame, video);
            try {
                java.nio.file.Files.createDirectories(LocalMedia.cacheDir());
                java.nio.file.Files.write(cached, full);
            } catch (Exception ex) {
                Projector.LOGGER.warn("[Projector] 缓存媒体失败 {}: {}", hash, ex.toString());
            }
            LocalMedia.registerCache(hash, cached);
            // 【②】下载完成：含落盘路径、分片数、字节数、用时、速率
            TransferLog.downloadDone(hash, frame, full.length, cached);
            // 【rc-76 哈希校验】只有「整段文件」（frame < 0）才可能与哈希对得上；
            // 按帧请求拿到的是文件里的一段，不能拿它校验。
            // 24 MB 的 SHA-1 放在工作线程算，别在主线程上卡一下。
            if (frame < 0 && full.length > 0) {
                final byte[] checkBytes = full;
                final String checkHash = hash;
                worker().execute(() -> {
                    String actual = LocalMedia.sha1(checkBytes);
                    boolean ok = actual != null && actual.equalsIgnoreCase(checkHash);
                    TransferLog.hashCheck(checkHash, actual, ok, checkBytes.length);
                    if (!ok) {
                        // 让下一次加载重新下载，而不是一直用坏文件
                        markFailed(checkHash);
                        // 【rc-83】整段下载失败：把「已经整段要过一次」的标记撤掉，
                        // 否则重试时会退回逐帧流式（每帧一个来回），永远补不完整段。
                        // 次数上限由 LoadState.attempts（3 次）兜着，不会无限重试。
                        WHOLE_FILE_REQUESTED.remove(checkHash);
                        STREAM_COUNT.remove(checkHash);
                    }
                });
            }
            finish(hash, full, cached, video, frame);
        } catch (Throwable t) {
            // 【rc-83】收齐之后的任何异常都必须有人**结算**这次传输与加载状态：
            // 以前这里抛出去（比如缓存目录不可写）会留下一条永远没有结束的「开始」登记，
            // 媒体也永远停在「加载中…」（第 66 条：聚合状态必须有人结算）。
            Projector.LOGGER.warn("[Projector][客户端][下载] 收齐后处理失败 {}: {}", hash, t.toString());
            TransferLog.downloadFail(hash, frame, "收齐后落盘/解码失败：" + t);
            markFailed(hash);
        }
    }

    /**
     * 服务端媒体的本地缓存文件路径。
     *
     * <p>整段媒体用 {@code hash.bin}；视频单帧用 {@code hash_fN.bin}，
     * 两者分开存放，避免「按帧下载」把整段缓存覆盖掉。</p>
     *
     * <p>【rc-83】整段文件的帧号是 {@link #WHOLE}（-1），也要落到 {@code hash.bin} ——
     * 以前它走的是 {@code video} 分支，结果整段视频被存成 {@code hash_f0.bin}，
     * 重启后「本地已有整段」的判定（看 {@code hash.bin}）就命中不了。</p>
     */
    public static Path cachePath(String hash, int frame, boolean video) {
        if (!top.hmjmfabc.projector.server.Sanitize.isHash(hash)) {
            // 服务端理论上是可信的，但客户端也不能用它拼路径：
            // 一个被改造的服务端可以靠 "../../x" 这种哈希在缓存目录之外写文件。
            return LocalMedia.cacheDir().resolve("invalid.bin");
        }
        return video && frame >= 0
                ? LocalMedia.cacheDir().resolve(hash + "_f" + frame + ".bin")
                : LocalMedia.cacheDir().resolve(hash + ".bin");
    }

    // ------------------------------------------------------------------
    // 维护
    // ------------------------------------------------------------------

    /**
     * 失败后的温和重试。
     *
     * <p>媒体可能因为服务端刚启动、网络抖动或玩家尚未收到广播而暂时取不到，
     * 这里允许在 30 秒内最多重试 3 次（每次间隔约 10 秒），
     * 超过就放弃并保持占位显示，避免持续发请求。</p>
     */
    /**
     * 标记某个媒体加载失败。
     *
     * <p>以前这里要同时改 3 个集合（FAILED / RETRY / LAST_RETRY），
     * 现在只是状态对象上的一个标志。</p>
     */
    /** 这份哈希是不是视频（只用于日志展示；索引里查不到就按图片记）。 */
    private static boolean videoOf(String hash) {
        try {
            for (LocalMedia.MediaFile f : LocalMedia.byKind(true)) {
                if (hash != null && hash.equals(f.hash())) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void markFailed(String hash) {
        state(hash).failed = true;
    }

    /**
     * 失败后的温和重试。
     *
     * <p>媒体可能因为服务端刚启动、网络抖动或玩家尚未收到广播而暂时取不到，
     * 这里允许在 30 秒内最多重试 3 次（每次间隔约 10 秒），
     * 超过就放弃并保持占位显示，避免持续发请求。</p>
     */
    private static void retryLater(String hash) {
        LoadState st = state(hash);
        if (st.attempts >= 3) return;
        long now = System.currentTimeMillis();
        if (now - st.lastAttemptMs < 10_000L) return;
        st.attempts++;
        st.lastAttemptMs = now;
        st.failed = false;
        Projector.LOGGER.debug("[Projector] 重试载入媒体 {} (第 {} 次)", shortHash(hash), st.attempts);
        TransferLog.downloadRetry(hash, -1, st.attempts);
    }

    /** 释放某个哈希的全部纹理（媒体被替换后调用）。 */
    public static void invalidate(String hash) {
        Entry e = IMAGES.remove(hash);
        TextureManager tm = Minecraft.getInstance().getTextureManager();
        if (e != null) {
            try {
                tm.release(e.location);
            } catch (Exception ignored) {
            }
            e.image.close();
        }
        Frames frames = VIDEO_FRAMES.remove(hash);
        if (frames != null) {
            for (Frame f : frames.slots) {
                try {
                    tm.release(f.location);
                } catch (Exception ignored) {
                }
                NativeImage img = f.texture.getPixels();
                if (img != null) img.close();
            }
            frames.slots.clear();
            frames.byFrame.clear();
        }
        LAST_WANTED.remove(hash);
        VideoSource src = VIDEO_SOURCES.remove(hash);
        if (src != null) src.close();
        LOADS.remove(hash);
        // 【rc-83】这份媒体所有在途下载（含整段）一起作废
        DOWNLOADS.keySet().removeIf(k -> k.startsWith(hash + "#"));
    }

    /**
     * 清掉「失败/加载中」状态，让所有媒体重新走一遍加载流程。
     *
     * <p>进入世界时调用。原因：请求失败的重试次数是有限的（避免刷屏式重试），
     * 而玩家重进世界是一个全新的时机——服务端此时一定就绪了，
     * 不重置的话图片会一直停在「媒体未就绪」，只能靠重新选一次文件来救。</p>
     */
    public static void resetFailures() {
        LOADS.clear();
    }

    /** 释放全部纹理（断开连接 / 退出世界时调用）。 */
    public static void clearAll() {
        Set<String> hashes = new HashSet<>(IMAGES.keySet());
        hashes.addAll(VIDEO_FRAMES.keySet());
        for (String h : hashes) {
            invalidate(h);
        }
        IMAGES.clear();
        VIDEO_FRAMES.clear();
        for (VideoSource s : VIDEO_SOURCES.values()) s.close();
        VIDEO_SOURCES.clear();
        LOADS.clear();
        DOWNLOADS.clear();
    }

    /** 【rc-83】当前在途下载数（测试用）：同一份媒体同时只允许一次下载。 */
    static int downloadingCount() {
        return DOWNLOADS.size();
    }

    /** 已缓存纹理数量（调试用）。 */
    public static int cachedImages() {
        return IMAGES.size();
    }

    /**
     * 【rc-85】媒体流水线的统计快照，供卡顿看门狗打日志：
     * {@code {已上传帧, 丢弃的过时帧, 已建槽位, 当前槽位总数, 在途下载数}}。
     *
     * <p>玩家报「视频的存在会使得客户端间歇性卡顿（每 3 秒卡一次）」时，
     * 这组数字能立刻分辨是「帧上传太密」还是「内存/GC」问题 ——
     * 以前只有「卡」这一个主观描述，什么也定位不了。</p>
     */
    public static long[] stats() {
        long slots = 0;
        for (Frames f : VIDEO_FRAMES.values()) slots += f.slots.size();
        long frames = 0;
        for (Frames f : VIDEO_FRAMES.values()) frames += f.byFrame.size();
        long pending = 0;
        for (Frames f : VIDEO_FRAMES.values()) {
            for (Frame s : f.slots) if (s.pendingUpload >= 0) pending++;
        }
        return new long[]{FRAMES_UPLOADED.get(), STALE_SKIPPED.get(), SLOTS_CREATED.get(),
                slots, frames, DOWNLOADS.size(), FRAMES_FILLED.get(), pending,
                GPU_HELD_SKIPPED.get()};
    }

    /** 【rc-85】当前有几个媒体在建槽位（诊断用）。 */
    public static int videoMediaCount() {
        return VIDEO_FRAMES.size();
    }
}

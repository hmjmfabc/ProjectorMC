package top.hmjmfabc.projector.client.web;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.phys.Vec3;
import top.hmjmfabc.projector.Projector;
import top.hmjmfabc.projector.ProjectorConfig;
import top.hmjmfabc.projector.client.PlaneCache;
import top.hmjmfabc.projector.common.Plane;
import top.hmjmfabc.projector.common.PlaneDistance;
import top.hmjmfabc.projector.common.widget.WebWidget;
import top.hmjmfabc.projector.common.widget.Widget;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 网页控件的**会话与纹理**管理（27.2 / Build 116）。
 *
 * <p>按 {@code tmp/research/web-widget-spec.md §4} 实现，职责与
 * {@code client/media/wm/WaterMediaVideos} 完全对应（同一个作者、同一套约定）：
 * 按控件管理浏览器会话的生命周期、把浏览器给的 GL 纹理包成 {@link AbstractTexture}
 * 注册进纹理管理器、给渲染层一个 {@link #textureFor}、失败/无帧的降级与禁用名单。</p>
 *
 * <h2>公开接口（契约，别改签名）</h2>
 * <pre>
 *   textureFor(WebWidget)                  // 没会话/纹理未就绪 = null（渲染线程）
 *   tick(Minecraft, Collection&lt;Plane&gt;)     // 渲染阶段每帧驱动
 *   action(WebWidget, int)                 // 世界内按钮（任意线程，内部转主线程）
 *   navigate(WebWidget, String)            // 编辑器里改完地址
 *   discard(WebWidget)                     // 丢弃该控件的会话
 *   hasSession(WebWidget) / status(WebWidget)
 * </pre>
 *
 * <h2>四条硬约束（规范 §4，逐条都落在代码里）</h2>
 * <ol>
 *   <li><b>创建浏览器只在渲染线程、且仅 {@link McefBridge#available()} 时</b>：
 *       {@link #textureFor} 与 {@link #tick} 都只在渲染线程路径上被调（见 {@code WorldPlaneRenderer}），
 *       非渲染线程的入口（{@link #action}）会先 {@code mc.execute(...)} 切回主线程。
 *       MCEF 的硬约束见 {@link McefBridge} 类注释。</li>
 *   <li><b>尺寸</b> = 控件画布尺寸 × {@link WebWidget#pixelPerUnit}，夹在
 *       {@code 64x36 ~ 1920x1080}，宽高比按控件（只用一个缩放系数，见 {@link #pixelSize}）。</li>
 *   <li><b>并发上限</b> {@link #MAX_SESSIONS} = 2（超限按**最久未用**释放）+
 *       <b>离开视野</b> {@link #IDLE_RELEASE_MS} = 15 秒释放；尺寸变了才 resize。</li>
 *   <li><b>无帧超时降级</b>：创建后 {@link #NO_FRAME_TIMEOUT_MS} 内 {@code textureId()} 仍为 0
 *       ⇒ 标记该控件「无帧」、释放会话，{@link #status} 给出可操作提示。
 *       （{@code getTextureID()==0} 的含义是「**还没上传过任何一帧**」，不是「没初始化」——
 *       所以这里的做法是「等 + 超时」，**绝不**去反射调 {@code MCEFRenderer.initialize()}，
 *       见 {@code mcef-api.md §4.4}。）</li>
 * </ol>
 *
 * <h2>本类是「生命周期」，不是「渲染驱动」（mcef-api.md §4.1/§4.3）</h2>
 * <p>CEF 心跳（MCEF 自己的 mixin 在 {@code GameRenderer.render} 头部泵消息循环）与纹理上传
 * （{@code MCEFBrowser.onPaint}）都由 MCEF 负责 ⇒ <b>我们没有任何「每帧必须调」的方法</b>。
 * {@link #tick} 只在做四件事：按需创建 / <b>尺寸真的变了才</b> {@code resize} /
 * 到点自动刷新 / 释放长期没人看的会话。{@link #textureFor} 每帧只读一次纹理 id。</p>
 *
 * <h2>异常口径</h2>
 * <p>本类与 {@link McefBridge} 的每个 try 都是 {@code catch (Throwable)}：
 * 安卓上 MCEF 的原生库加载失败抛的是 {@code UnsatisfiedLinkError}/{@code LinkageError} 这类
 * {@code Error}，{@code catch (Exception)} 一个都抓不到（{@code mcef-api.md §6}）。
 * 平台闸门（安卓直接判「本设备不支持」，连 {@code Class.forName} 都不做）在 {@link McefBridge#available()}。</p>
 *
 * <p><b>TODO（配置，规范 §5.9 已规划 {@code ProjectorConfig} 的 {@code web} 段）</b>：
 * 现在三个数还是常量，接配置时改这三处读 {@code ProjectorConfig.INSTANCE} 即可 ——
 * {@link #MAX_SESSIONS} → {@code web.maxSessions}、{@link #IDLE_RELEASE_MS} →
 * {@code web.idleReleaseSec}×1000、{@link #NO_FRAME_TIMEOUT_MS} → {@code web.noFrameTimeoutSec}×1000。
 * （键名以 §5.9 为准；键还不存在时不要引用，否则编译不过。）</p>
 *
 * <h2>线程</h2>
 * <p>{@link #textureFor} / {@link #tick}：<b>渲染线程</b>（它们会碰 GL 纹理名与浏览器创建）。
 * {@link #action} / {@link #navigate} / {@link #discard} / {@link #releaseAll} / {@link #status} /
 * {@link #hasSession}：内部只读表或自行切回主线程，<b>从任意线程调都安全</b>
 * （会走 {@code MCEFBrowser.close()} 的那几条必须切线程 —— 它里面有裸 GL 调用）。</p>
 *
 * <h2>接线</h2>
 * <ul>
 *   <li>{@link #tick} 挂在 {@code WorldPlaneRenderer}（渲染路径，内部 250ms 节流）。</li>
 *   <li>{@link #releaseAll} 挂在 {@code ProjectorClient} 的 onLogout（已接）。</li>
 *   <li>控件被删除时 {@link #discard}（不接也行：15 秒没再看到会自动释放）。</li>
 * </ul>
 */
public final class WebSessions {
    private WebSessions() {
    }

    // ------------------------------------------------------------------ 常量（规范 §4 点名的那几个）

    /**
     * 同时存在的浏览器上限的**默认值**（超限按最久未用释放）。
     *
     * <p>实际取值见 {@link #maxSessions()}：配置 {@code web.maxSessions} 优先，
     * 读不到（配置还没加载/被改坏）就用这个默认值 —— 渲染路径上绝不允许因为读配置抛异常。</p>
     */
    public static final int MAX_SESSIONS = 2;

    /** 离开视野多久释放（毫秒）的**默认值**；实际取值见 {@link #idleReleaseMs()}（配置 {@code web.idleReleaseSec}）。 */
    public static final long IDLE_RELEASE_MS = 15_000L;

    /** 「无帧」判定超时（毫秒）的**默认值**；实际取值见 {@link #noFrameTimeoutMs()}（配置 {@code web.noFrameTimeoutSec}）。 */
    public static final long NO_FRAME_TIMEOUT_MS = 10_000L;

    // ------------------------------------------------------------------ 配置取值器
    //
    // 【27.2】这三个量以前是写死的常量，配置里那三个键就成了没人读的死键
    // （项目纪律：不留死代码/死配置）。统一从这里取，且**任何异常都退回默认值**：
    // 这四个方法是渲染路径每帧都会走到的，读配置失败绝不能让画面塌掉。

    /** 同屏浏览器上限（配置 {@code web.maxSessions}，下限 1）。 */
    private static int maxSessions() {
        try {
            return Math.max(1, ProjectorConfig.INSTANCE.webMaxSessions.get());
        } catch (Throwable t) {
            return MAX_SESSIONS;
        }
    }

    /** 离开视野多久释放（毫秒，配置 {@code web.idleReleaseSec}，下限 1 秒）。 */
    private static long idleReleaseMs() {
        try {
            return Math.max(1, ProjectorConfig.INSTANCE.webIdleReleaseSec.get()) * 1000L;
        } catch (Throwable t) {
            return IDLE_RELEASE_MS;
        }
    }

    /** 「无帧」判定超时（毫秒，配置 {@code web.noFrameTimeoutSec}，下限 1 秒）。 */
    private static long noFrameTimeoutMs() {
        try {
            return Math.max(1, ProjectorConfig.INSTANCE.webNoFrameTimeoutSec.get()) * 1000L;
        } catch (Throwable t) {
            return NO_FRAME_TIMEOUT_MS;
        }
    }

    /** 同一个控件连续建失败多少次就不再重试。 */
    private static final int MAX_FAILURES = 2;

    /**
     * 尺寸变化要「稳定这么久」才真的下发给浏览器（毫秒）。
     *
     * <p>{@code resize} 会让桥 {@code setViewport} + <b>stop/重启 CDP 截屏流</b>，
     * 而编辑器里拖动尺寸会每帧都改画布尺寸 ⇒ 不去抖的话流永远刚起就被打断，
     * 表现就是「画面只有第一帧」。真机实测过 1.9 秒 111 次 resize。</p>
     */
    private static final long RESIZE_SETTLE_MS = 500L;

    /** 两次「新建浏览器」之间至少隔多久（毫秒）：原生建浏览器不便宜，别一帧建好几个。 */
    private static final long MIN_CREATE_INTERVAL_MS = 250L;

    /** 渲染尺寸的夹取范围（像素，规范 §4：`64x36 ~ 1920x1080`）。 */
    public static final int MIN_W_PX = 64;
    public static final int MIN_H_PX = 36;
    public static final int MAX_W_PX = 1920;
    public static final int MAX_H_PX = 1080;

    /** 同一个控件同一条日志至少隔多久（毫秒）。 */
    private static final long LOG_MIN_INTERVAL_MS = 3_000L;

    /** 「扫一遍所有平面找候选」的最小间隔（毫秒）。 */
    private static final long SCAN_INTERVAL_MS = 250L;

    // ------------------------------------------------------------------ 状态

    /** 每个网页控件一条（UUID → 记账）。 */
    private static final Map<UUID, Entry> SESSIONS = new ConcurrentHashMap<>();

    /**
     * 「已经放弃重试」的控件：UUID → 可操作的原因。
     *
     * <p>独立于 {@link #SESSIONS} 存放：会话被释放后条目会被删掉，
     * 但「这个控件为什么打不开」必须留到玩家改地址为止（{@link #status} 要用它给提示）。
     * 记录里带着**判死时的 URL**：地址一改就撤销（改了就是想再试一次）。</p>
     */
    private static final Map<UUID, Blocked> BLOCKED = new ConcurrentHashMap<>();

    /** 上一次真正新建浏览器的时刻（限流用）。 */
    private static volatile long lastCreateMs;

    /** 上次完整扫描的时刻（渲染阶段每帧调 {@link #tick}，得节流）。 */
    private static volatile long lastScanMs;

    /**
     * 上一次见到的 {@code ClientLevel} 实例（换世界判据）。
     *
     * <p>{@link #tick} 只在关卡里被调，收不到「退出世界」通知；不自己判一下，
     * 上一个世界的浏览器会一直活着。（{@code ProjectorClient.onLogout} 里那句
     * {@link #releaseAll} 是主路径，这里是兜底。）</p>
     */
    private static volatile Object lastLevel;

    /** 「MCEF 不可用」只提示一次，别每帧刷屏。 */
    private static volatile boolean unavailableLogged;

    /** 判死记录：原因 + 判死时的地址。 */
    private static final class Blocked {
        final String reason;
        final String url;

        Blocked(String reason, String url) {
            this.reason = reason;
            this.url = url == null ? "" : url;
        }
    }

    /** 一个控件的运行期记账。 */
    private static final class Entry {
        final UUID id;
        volatile McefBridge.Session session;
        /** 注册进纹理管理器的那个 ResourceLocation（与控件一一对应、稳定不变）。 */
        volatile ResourceLocation texture;
        /** 当前**已经通知浏览器**的视口尺寸（变了才 resize，别每帧 resize）。 */
        volatile int width;
        volatile int height;
        /** 已经交给浏览器的地址（变了才 load，避免每帧 load 同一页）。 */
        volatile String loadedUrl = "";
        volatile double zoom = Double.NaN;
        /** 上次看到它「在视野内」的时刻。 */
        volatile long lastSeenMs;
        /** 上次被用到的时刻（LRU 判据）。 */
        volatile long lastUsedMs;
        /** 创建时刻（无帧超时判据）。 */
        volatile long createdAtMs;
        /** 上次 load / 自动刷新的时刻。 */
        volatile long lastRefreshMs;
        /** 连续建失败次数。 */
        volatile int failures;
        // ---- 尺寸去抖（27.2 真机事故）----
        // 编辑器里拖尺寸时，画布每变 1 单位（=8 像素）就会触发一次 resize，
        // 而每次 resize 都要 stop + 重启 CDP 截屏流 ⇒ 流刚起就被打断，
        // 画面永远只有第一帧。实测：1.9 秒里 resize 111 次、帧数停在 1。
        // 所以「目标尺寸」必须**稳定一小段时间**才真的下发给浏览器。
        volatile int pendingW;
        volatile int pendingH;
        volatile long pendingSinceMs;

        final Map<String, AtomicInteger> logCounts = new ConcurrentHashMap<>();
        final Map<String, Long> logTimes = new ConcurrentHashMap<>();

        Entry(UUID id) {
            this.id = id;
        }

        /** 同一 key 最多 3 次、且至少隔 {@link #LOG_MIN_INTERVAL_MS}（别刷屏）。 */
        void note(String key, String fmt, Object... args) {
            long now = System.currentTimeMillis();
            Long last = logTimes.get(key);
            if (last != null && now - last < LOG_MIN_INTERVAL_MS) {
                return;
            }
            int n = logCounts.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
            if (n > 3) {
                return;
            }
            logTimes.put(key, now);
            Projector.LOGGER.info(fmt, args);
        }
    }

    // ------------------------------------------------------------------ 给渲染层

    /**
     * 这一帧该用哪张纹理；没有会话 / 纹理还没就绪一律 {@code null}（上层画占位）。
     *
     * <p><b>只能从渲染线程调用</b>（要读 GL 纹理名）。副作用有两个：
     * ①把「真的被画出来了」记成「在用」（LRU 与视野判据）；
     * ②tick 还没建会话时兜底建一个 —— 同样受 {@link #MAX_SESSIONS} 与
     * {@link #MIN_CREATE_INTERVAL_MS} 限制，不会一帧建一堆。</p>
     *
     * <p>⚠ <b>纹理 id 为 0 时必须返回 null</b>：{@code QuadCollector.textureReady()} 只判
     * 「纹理管理器里有没有这个 key」，返回非 null 却 id=0 会让上层 bind 到 GL 纹理 0（一块黑）。</p>
     *
     * <p>⚠ <b>id 每帧现问</b>（mcef-bridge 报告第 2 条）：这里没有、也不许有任何「上次的 id」缓存 ——
     * MCEFBrowser 内部销毁重建后 id 会变，适配器里的 {@code getId()} 也是每次转发。</p>
     */
    public static ResourceLocation textureFor(WebWidget w) {
        if (w == null || w.id == null || !McefBridge.available()) {
            return null;
        }
        long now = System.currentTimeMillis();
        Entry e = SESSIONS.computeIfAbsent(w.id, Entry::new);
        maintain(w, e, now, true);
        e.lastSeenMs = now;
        e.lastUsedMs = now;
        McefBridge.Session s = e.session;
        if (s == null || !s.valid() || s.textureId() == 0) {
            return null;
        }
        return e.texture;
    }

    // ------------------------------------------------------------------ 生命周期

    /**
     * 每帧由渲染阶段驱动（{@code WorldPlaneRenderer}）：按需创建 / resize / 自动刷新 / 释放。
     *
     * <p>内部 {@link #SCAN_INTERVAL_MS} 节流：完整扫一遍「该维度所有平面 × 控件」250ms 一次就够，
     * 逐帧那部分在 {@link #textureFor} 里。</p>
     */
    public static void tick(Minecraft mc, Collection<Plane> planes) {
        if (mc == null || mc.level == null || mc.player == null) {
            return;
        }
        long now = System.currentTimeMillis();

        // ★【27.2-pre-135 性能】本帧有没有网页控件 —— 顺手算一次（平面集合本来每帧就要过），
        //   给每帧的悬停/射线做闸门：世界里一个网页控件都没有时，一次射线都不做。
        anyWidget = hasWebWidget(planes);

        // 0) 换世界（新的 ClientLevel 实例）⇒ 先放掉上一个世界的会话（兜底，见 lastLevel 注释）
        Object level = mc.level;
        if (level != lastLevel) {
            if (lastLevel != null) {
                Projector.LOGGER.info("[Projector][网页] 检测到换世界，释放上一个世界的网页会话");
                releaseAll();
            }
            lastLevel = level;
        }

        if (!McefBridge.available()) {
            // 没装 / 没初始化：收干净并只提示一次（安卓上这是常态，别刷屏）
            if (!SESSIONS.isEmpty()) {
                releaseAll();
            }
            if (!unavailableLogged) {
                unavailableLogged = true;
                Projector.LOGGER.info("[Projector][网页] 网页控件暂时无法显示：{}（本模组其余功能不受影响）",
                        McefBridge.reason());
            }
            return;
        }

        if (now - lastScanMs < SCAN_INTERVAL_MS) {
            return;
        }
        lastScanMs = now;

        // 1) 找出「此刻可见」的网页控件，按距离排序（近的优先占名额）
        List<Candidate> visible = new ArrayList<>();
        if (planes != null) {
            Vec3 eye = mc.player.getEyePosition();
            ResourceLocation dim = mc.level.dimension().location();
            double range = ProjectorConfig.INSTANCE.renderDistance.get();
            for (Plane plane : planes) {
                if (plane == null || plane.widgets.isEmpty()) {
                    continue;
                }
                if (plane.dimension != null && !plane.dimension.equals(dim)) {
                    continue;
                }
                if (!PlaneDistance.withinRange(plane, eye.x, eye.y, eye.z, range)) {
                    continue;
                }
                // 复制一份再遍历：控件可能在同步里被整体替换（同 WorldPlaneRenderer 的谨慎）
                for (Widget widget : new ArrayList<>(plane.widgets)) {
                    if (widget instanceof WebWidget web && web.id != null) {
                        visible.add(new Candidate(web, distanceTo(plane, web, eye)));
                    }
                }
            }
        }
        visible.sort(Comparator.comparingDouble(c -> c.distance));

        // 2) 最近的 MAX_SESSIONS 个「留」，其余这次不留（已有会话的按最久未用释放，见下）
        Set<UUID> keep = new HashSet<>();
        for (int i = 0; i < visible.size() && i < maxSessions(); i++) {
            keep.add(visible.get(i).widget.id);
        }

        int created = 0;
        for (Candidate c : visible) {
            WebWidget w = c.widget;
            Entry e = SESSIONS.computeIfAbsent(w.id, Entry::new);
            e.lastSeenMs = now;
            if (!keep.contains(w.id)) {
                // 可见但排不进前 N：这次不建。
                // 已有会话**不能立刻放掉**：渲染层可能正在画它（textureFor 每帧刷 lastUsedMs），
                // 立刻放掉会变成「tick 释放 → 渲染层下帧重建」的抖动循环；
                // 判据与「离开视野」同源：真的最久没人用了才让位。
                if (e.session != null && now - e.lastUsedMs > idleReleaseMs()) {
                    release(w.id, "超出并发上限 " + maxSessions() + "（按最久未用让位给更近的控件）");
                }
                continue;
            }
            e.lastUsedMs = now;
            boolean need = e.session == null || !e.session.valid();
            if (need && created >= 1) {
                continue;   // 每帧最多建一个：下一帧再处理（原生建浏览器不便宜）
            }
            if (maintain(w, e, now, true) && need) {
                created++;
            }
        }

        // 3) 兜底：真的超了上限就按最久未用释放
        trimToCap(keep);

        // 4) 长期没再看到的控件 ⇒ 释放会话与纹理；连记账条目一起丢掉（否则跑久了表只增不减）。
        //    （判死原因另存在 BLOCKED 里，条目丢了也不影响 status 的提示。）
        for (Entry e : new ArrayList<>(SESSIONS.values())) {
            long idle = now - e.lastSeenMs;
            if (e.session != null) {
                if (idle > idleReleaseMs()) {
                    release(e.id, "离开视野超过 " + (idleReleaseMs() / 1000) + " 秒");
                }
            } else if (idle > idleReleaseMs() * 4) {
                SESSIONS.remove(e.id);
            }
        }
    }

    /** 便捷版：自己从 {@code PlaneCache} 取当前维度的平面（接线少一行；与二参版同义）。 */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) {
            return;
        }
        tick(mc, PlaneCache.planesIn(mc.level.dimension().location()));
    }

    /**
     * 维护一个会话：建 / resize / load / 缩放 / 自动刷新 / 无帧判死 / 状态回写。
     *
     * @param allowCreate 允许在这一步新建浏览器吗
     * @return 这一步**新建了**浏览器 = true
     */
    private static boolean maintain(WebWidget w, Entry e, long now, boolean allowCreate) {
        if (w == null || w.id == null) {
            return false;   // 控件身份都没有：什么都没法记（并发表不收 null 键）
        }
        // 地址改了 ⇒ 撤销上次判的死刑（改了就是想再试一次）
        Blocked blocked = BLOCKED.get(w.id);
        if (blocked != null && !w.targetUrl().equals(blocked.url)) {
            BLOCKED.remove(w.id);
            e.failures = 0;
            Projector.LOGGER.info("[Projector][网页] 控件={} 地址已改，重新尝试创建浏览器：{}",
                    shortId(w.id), w.targetUrl());
        }

        McefBridge.Session s = e.session;
        if (s == null || !s.valid()) {
            return allowCreate && create(w, e, now);
        }

        // ① 无帧超时：浏览器建起来了却一帧都没有（安卓：原生层在、外部 Chromium 不在）⇒ 降级
        if (now - e.createdAtMs > noFrameTimeoutMs() && s.textureId() == 0) {
            String reason = noFrameReason();
            BLOCKED.put(w.id, new Blocked(reason, w.targetUrl()));
            Projector.LOGGER.warn("[Projector][网页] 控件={} 无帧：创建后 {} 秒没有任何画面，已释放并停止重试"
                            + "（url={} 视口={}x{}）", shortId(w.id), noFrameTimeoutMs() / 1000, w.targetUrl(),
                    e.width, e.height);
            release(w.id, "无帧超时（" + (noFrameTimeoutMs() / 1000) + " 秒）");
            return false;
        }

        // ② 控件尺寸 / 渲染密度变了 ⇒ 重算视口尺寸（宽高比跟着控件）
        //    ⚠ MCEFBrowser.resize 内部**没有去重**（mcef-api.md §4.3：lastWidth/lastHeight 是给
        //    onPaint 判断整帧/脏矩形用的），每帧无条件调会让 CEF 每帧重排 ⇒ 必须由我们
        //    用 Entry 里的 width/height 判断「真的变了」才调。
        int[] size = pixelSize(w);
        if (size != null) {
            if (size[0] == e.width && size[1] == e.height) {
                // 目标与当前一致：没有待处理的变化
                e.pendingW = size[0];
                e.pendingH = size[1];
                e.pendingSinceMs = now;
            } else if (e.pendingW != size[0] || e.pendingH != size[1]) {
                // 目标又变了（还在拖）：重新计时，**先不下发**
                e.pendingW = size[0];
                e.pendingH = size[1];
                e.pendingSinceMs = now;
            } else if (now - e.pendingSinceMs >= RESIZE_SETTLE_MS) {
                // 目标已经稳定 ≥ RESIZE_SETTLE_MS：这时才真的 resize
                //（resize 会让桥 stop+重启截屏流，绝不能每帧做）
                s.resize(size[0], size[1]);
                Projector.LOGGER.info("[Projector][网页] 控件={} 视口 {}x{} → {}x{}"
                                + "（画布 {}x{} 单位 × {} 像素/单位，稳定 {} ms 后下发）",
                        shortId(w.id), e.width, e.height, size[0], size[1],
                        trim(w.w), trim(w.h), trim(w.pixelPerUnit), RESIZE_SETTLE_MS);
                e.width = size[0];
                e.height = size[1];
                e.pendingSinceMs = now;
            }
        }

        // ③ 地址变了 ⇒ 加载
        String want = w.targetUrl();
        if (!want.equals(e.loadedUrl)) {
            s.load(want);
            e.loadedUrl = want;
            e.lastRefreshMs = now;
            Projector.LOGGER.info("[Projector][网页] 控件={} 加载 {}（视口 {}x{}）",
                    shortId(w.id), want, e.width, e.height);
        }

        // ④ 页面缩放变了
        if (Double.isNaN(e.zoom) || Math.abs(e.zoom - w.zoom) > 1.0E-6) {
            s.setZoom(w.zoom);
            e.zoom = w.zoom;
            e.note("zoom", "[Projector][网页] 控件={} 缩放设为 {}", shortId(w.id), trim(w.zoom));
        }

        // ⑤ 自动刷新
        if (w.autoRefreshSec > 0 && now - e.lastRefreshMs >= w.autoRefreshSec * 1000L) {
            s.reload();
            e.lastRefreshMs = now;
            e.note("auto-refresh", "[Projector][网页] 控件={} 自动刷新（每 {} 秒）：{}",
                    shortId(w.id), w.autoRefreshSec, want);
        }

        // ⑥ 运行期状态回写控件（界面显示「加载中…」/ 标题，字段见 WebWidget 的 transient 段）
        //    标题来自 MCEFClient 的全局标题监听（唯一合法路径，见 McefBridge.registerTitleWatcher）；
        //    拿不到就留空 —— 界面显示地址（w.displayUrl() / status() 里的 currentUrl()）同样够用。
        w.loading = s.loading();
        String title = s.title();
        if (!title.isEmpty() && !title.equals(w.title)) {
            w.title = title;
            e.note("title", "[Projector][网页] 控件={} 标题=\"{}\"", shortId(w.id),
                    title.length() > 60 ? title.substring(0, 60) : title);
        }
        return false;
    }

    /** 真正新建浏览器并注册纹理。 */
    private static boolean create(WebWidget w, Entry e, long now) {
        if (BLOCKED.containsKey(w.id) || e.failures >= MAX_FAILURES) {
            return false;
        }
        if (liveCount() >= maxSessions()) {
            e.note("cap", "[Projector][网页] 已达并发上限 {}，控件={} 这次不创建", maxSessions(), shortId(w.id));
            return false;
        }
        if (now - lastCreateMs < MIN_CREATE_INTERVAL_MS) {
            return false;
        }
        int[] size = pixelSize(w);
        if (size == null) {
            e.note("degenerate", "[Projector][网页] 控件={} 尺寸非法（{}x{}），跳过",
                    shortId(w.id), trim(w.w), trim(w.h));
            return false;
        }
        lastCreateMs = now;

        // 只在 available() 时创建（McefBridge.create 内部还会再确认一次）
        McefBridge.Session s = McefBridge.create(w.targetUrl(), w.transparent, size[0], size[1]);
        if (s == null) {
            e.failures++;
            if (e.failures >= MAX_FAILURES) {
                BLOCKED.put(w.id, new Blocked("打不开（连续 " + e.failures + " 次创建浏览器失败："
                        + McefBridge.reason() + "）—— 改一下地址或重进世界可重试", w.targetUrl()));
                Projector.LOGGER.warn("[Projector][网页] 控件={} 连续 {} 次打不开，已停止重试：url={}（原因={}）",
                        shortId(w.id), e.failures, w.targetUrl(), McefBridge.reason());
            } else {
                Projector.LOGGER.warn("[Projector][网页] 控件={} 创建浏览器失败（第 {} 次）：url={} 原因={}",
                        shortId(w.id), e.failures, w.targetUrl(), McefBridge.reason());
            }
            return false;
        }

        e.session = s;
        e.width = size[0];
        e.height = size[1];
        e.loadedUrl = w.targetUrl();
        e.zoom = w.zoom;
        e.createdAtMs = now;
        e.lastRefreshMs = now;
        e.texture = registerTexture(w, e);
        Projector.LOGGER.info("[Projector][网页] 控件={} 已创建浏览器：url={} 视口={}x{} 透明={} 并发={}/{}",
                shortId(w.id), w.targetUrl(), size[0], size[1], w.transparent, liveCount(), maxSessions());
        return true;
    }

    /**
     * 把浏览器的 GL 纹理注册成 MC 认得的 {@code ResourceLocation}。
     *
     * <p>与 WaterMedia 视频完全相同的那一招：只重写 {@code getId()} 转发，
     * 渲染管线（{@code RenderType.text(loc)}）一行都不用改。</p>
     *
     * <p>⚠ 对着 MC 1.21.1 源码确认过：{@code TextureManager.register} 会调 {@code load()}；
     * {@code release(loc)} 只调 {@code close()}，而 1.21.1 的 {@code AbstractTexture.close()}
     * 是**空实现**、我们的适配器又从不设 {@code id} 字段 ⇒ <b>MC 这两条路径都不会删掉 MCEF 的纹理</b>
     * （真正删纹理的是 {@link McefBridge.Session#close()} 里的 {@code glDeleteTextures}）。</p>
     */
    private static ResourceLocation registerTexture(WebWidget w, Entry e) {
        ResourceLocation loc = textureLocation(w);
        try {
            Minecraft.getInstance().getTextureManager().register(loc, new ForwardTexture(e));
        } catch (Throwable t) {
            Projector.LOGGER.warn("[Projector][网页] 控件={} 注册纹理失败：{}", shortId(w.id), t.toString());
        }
        return loc;
    }

    /**
     * 纹理名：{@code projector:web/&lt;UUID 去掉短横&gt;}。
     *
     * <p>{@code ResourceLocation} 的 path 只允许 {@code [a-z0-9/._-]}：{@code UUID.toString()}
     * 本来就是小写十六进制 + 短横，<b>把短横一起去掉</b>最保险（不依赖「短横恰好合法」这件事，
     * 也顺手避开任何大小写/非 ASCII 的意外）。</p>
     */
    public static ResourceLocation textureLocation(WebWidget w) {
        String key = w == null || w.id == null
                ? "unknown" + Math.abs(System.identityHashCode(w))
                : w.id.toString().replace("-", "");
        return Projector.id("web/" + key);
    }

    /**
     * 丢掉某个网页控件的浏览器会话（控件被删除 / 换地址时调它）。
     *
     * <p>与 {@link #releaseWidget(UUID)} <b>完全等价</b>（实现只有一份：{@code discardNow}）；
     * 手边有控件对象时用这个更直观，只有 UUID 时用那个。</p>
     *
     * <p>同时把「已经放弃重试」的记录一起清掉：调用方显式丢弃 = 想重新来一遍。</p>
     */
    public static void discard(WebWidget w) {
        if (w != null) {
            releaseWidget(w.id);
        }
    }

    /**
     * 丢掉某个网页控件的会话（按 id）—— {@link #discard(WebWidget)} 的等价入口。
     *
     * <p><b>可在任意线程调用</b>：内部释放会走 {@code MCEFBrowser.close()}，
     * 而它里面有裸 GL 调用 ⇒ 不在主线程时先切回主线程（{@code Minecraft.execute} 在主线程上是同步跑的）。</p>
     */
    public static void releaseWidget(UUID widgetId) {
        if (widgetId == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && !mc.isSameThread()) {
            mc.execute(() -> discardNow(widgetId));
            return;
        }
        discardNow(widgetId);
    }

    private static void discardNow(UUID id) {
        release(id, "主动丢弃（删除 / 换地址 / 离开视野）");
        BLOCKED.remove(id);
    }

    /** 释放一个控件的会话与纹理（内部用；对外的入口是 {@link #discard}）。 */
    private static void release(UUID widgetId, String reason) {
        Entry e = SESSIONS.remove(widgetId);
        if (e == null) {
            return;
        }
        McefBridge.Session s = e.session;
        e.session = null;
        if (s != null) {
            s.close();                      // 必须在渲染线程（里面 glDeleteTextures）
            Projector.LOGGER.info("[Projector][网页] 控件={} 已释放会话：{}（并发={}/{}）",
                    shortId(widgetId), reason, liveCount(), maxSessions());
        }
        if (e.texture != null) {
            try {
                Minecraft.getInstance().getTextureManager().release(e.texture);
            } catch (Throwable ignored) {
                // 纹理没注册成功过也无所谓
            }
            e.texture = null;
        }
    }

    /**
     * 退出世界 / 关客户端时全部释放（{@code ProjectorClient.onLogout} 已经在调）。
     *
     * <p><b>可在任意线程调用</b>（同样因为 {@code close()} 里有裸 GL 调用，非主线程时先切回主线程）。</p>
     */
    public static void releaseAll() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && !mc.isSameThread()) {
            mc.execute(WebSessions::releaseAllNow);
            return;
        }
        releaseAllNow();
    }

    private static void releaseAllNow() {
        for (UUID id : new ArrayList<>(SESSIONS.keySet())) {
            release(id, "退出世界");
        }
        // ★【27.2-pre-135 玩家要求】退出世界/断开服务器 ⇒ **立刻清空外部 Chromium**：
        //   停流 + 清 JS 堆 + 关掉**所有**标签页（含玩家点开的 target=_blank 新页、
        //   历次残留的页面）。配置 runtime.closeBrowserOnExit=true 时连浏览器一起关。
        //   走兼容层的公开反射入口（不链接任何类；没装兼容层时静默跳过）。
        String summary = McefBridge.purgeExternal();
        if (summary != null && !summary.isEmpty()) {
            Projector.LOGGER.info("[Projector][网页] 退出世界清理外部浏览器：{}", summary);
        }
        SESSIONS.clear();
        BLOCKED.clear();
        anyWidget = false;
        unavailableLogged = false;
        lastCreateMs = 0L;
        lastScanMs = 0L;
        lastLevel = null;
    }

    /** 超出上限的会话按最久未用释放（{@code keep} 里的不动）。 */
    private static void trimToCap(Set<UUID> keep) {
        while (liveCount() > maxSessions()) {
            Entry victim = null;
            for (Entry e : SESSIONS.values()) {
                if (e.session == null || keep.contains(e.id)) {
                    continue;
                }
                if (victim == null || e.lastUsedMs < victim.lastUsedMs) {
                    victim = e;
                }
            }
            if (victim == null) {
                return;   // 活着的全在 keep 里（正常到不了这里）
            }
            release(victim.id, "超出并发上限 " + maxSessions() + "（按最久未用释放）");
        }
    }

    // ------------------------------------------------------------------ 世界内按钮

    /**
     * 世界内按钮按下（{@link WebWidget#BTN_BACK} 等）。
     *
     * <p><b>可在任意线程调用</b>：不在主线程时先切回主线程再执行
     * （{@code Minecraft.execute} 在主线程上是**同步跑**的，见 {@code BlockableEventLoop.execute}）。</p>
     *
     * <p>这里**只管导航**，不碰控件数据、不做权限判定 —— 那些在上层
     * （与视频的「播放控制不算改内容」同一个口径）。</p>
     */
    public static void action(WebWidget w, int button) {
        if (w == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && !mc.isSameThread()) {
            mc.execute(() -> actionNow(w, button));
            return;
        }
        actionNow(w, button);
    }

    private static void actionNow(WebWidget w, int button) {
        if (w.id == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Entry e = SESSIONS.get(w.id);
        McefBridge.Session s = e == null ? null : e.session;
        if (s == null || !s.valid()) {
            // 会话没建：**不静默失败**，打一行说明为什么（§5.5 第 4 条）
            String why = !McefBridge.available() ? McefBridge.reason()
                    : (BLOCKED.containsKey(w.id) ? "该控件已被判「无帧/打不开」，改地址才能重试"
                            : "还没有会话（不在视野内或还没轮到）");
            Projector.LOGGER.info("[Projector][网页] 控件={} 按下按钮 {} 但暂时无效：{}（url={}）",
                    shortId(w.id), buttonName(button), why, w.targetUrl());
            return;
        }
        e.lastUsedMs = now;
        switch (button) {
            case WebWidget.BTN_BACK -> {
                if (s.canGoBack()) {
                    s.back();
                } else {
                    e.note("no-back", "[Projector][网页] 控件={} 没有上一页可退", shortId(w.id));
                }
            }
            case WebWidget.BTN_FORWARD -> {
                if (s.canGoForward()) {
                    s.forward();
                } else {
                    e.note("no-forward", "[Projector][网页] 控件={} 没有下一页可进", shortId(w.id));
                }
            }
            case WebWidget.BTN_REFRESH -> {
                s.reload();
                e.lastRefreshMs = now;
            }
            case WebWidget.BTN_HOME -> {
                String home = w.homeTarget();
                s.load(home);
                e.loadedUrl = home;
                e.lastRefreshMs = now;
            }
            default -> {
                Projector.LOGGER.info("[Projector][网页] 控件={} 收到未知按钮 {}", shortId(w.id), button);
                return;
            }
        }
        Projector.LOGGER.info("[Projector][网页] 控件={} 按钮 {}：当前地址={} 加载中={}",
                shortId(w.id), buttonName(button), s.currentUrl(), s.loading());
    }

    private static String buttonName(int button) {
        return switch (button) {
            case WebWidget.BTN_BACK -> "←";
            case WebWidget.BTN_FORWARD -> "→";
            case WebWidget.BTN_REFRESH -> "⟳";
            case WebWidget.BTN_HOME -> "⌂";
            default -> "?" + button;
        };
    }

    // ------------------------------------------------------------------ 编辑器里换地址

    /**
     * 编辑器里改完地址 ⇒ 让浏览器立刻跟过去（{@code WidgetEditorScreen} 回车 / 确认时调）。
     *
     * <ol>
     *   <li>地址先过 {@link WebWidget#acceptable}（只认 {@code http/https/about:blank}）；
     *       <b>不合法不静默</b>：打一行说明原因后返回（界面上的红字由编辑器自己给）。</li>
     *   <li>已经有活着的会话 ⇒ <b>就地 {@code load()}</b>：立刻生效，不用重建浏览器
     *       （重建要等原生建浏览器，1 秒上下），后退历史还留着 —— 与真实浏览器一致。</li>
     *   <li>还没会话 ⇒ 只记一笔：下一次创建自然就是新地址（可能是远在天边的控件，
     *       <b>不在这里建浏览器</b>）。会话被丢弃过的（{@link #discard}）同理，会重新尝试。</li>
     *   <li>顺手 {@link WebControls#request(UUID)}：作者主动设过地址 = 他要用这个控件，
     *       回世界后那排按钮栏该浮出来（与「点过才给」的规矩一致，而不是绕过它）。</li>
     * </ol>
     */
    public static void navigate(WebWidget w, String url) {
        if (w == null || w.id == null) {
            return;
        }
        String target = url == null || url.trim().isEmpty() ? WebWidget.BLANK : url.trim();
        if (!WebWidget.acceptable(target)) {
            Projector.LOGGER.warn("[Projector][网页] 控件={} 收到不被接受的地址（只支持 http/https/about:blank），"
                    + "已忽略：{}", shortId(w.id), target);
            return;
        }
        long now = System.currentTimeMillis();
        WebControls.request(w.id);
        Entry e = SESSIONS.get(w.id);
        McefBridge.Session s = e == null ? null : e.session;
        if (s == null || !s.valid()) {
            Projector.LOGGER.info("[Projector][网页] 控件={} 地址改为 {}：现在还没有会话，下一帧按新地址创建",
                    shortId(w.id), target);
            return;
        }
        s.load(target);
        e.loadedUrl = target;
        e.lastRefreshMs = now;
        e.lastUsedMs = now;
        e.lastSeenMs = now;
        w.title = "";                      // 旧标题属于旧页面，先清掉（下一帧从新页面读）
        Projector.LOGGER.info("[Projector][网页] 控件={} 地址改为 {}（就地加载，视口 {}x{}）",
                shortId(w.id), target, e.width, e.height);
    }

    // ------------------------------------------------------------------ 状态查询

    /** 这个控件现在有活着的会话吗。 */
    public static boolean hasSession(WebWidget w) {
        if (w == null || w.id == null) {
            return false;
        }
        Entry e = SESSIONS.get(w.id);
        return e != null && e.session != null && e.session.valid();
    }

    // ------------------------------------------------------------------
    // 输入转发要用的三个读取口（27.2，只给 client/web/WebInput 用）
    //
    // 为什么不直接把 SESSIONS 暴露出去：Entry 是**记账结构**（还带着去抖、判死、日志限流
    // 一堆状态），输入层只该看到「会话」和「已经下发的视口尺寸」这两件事。
    // ------------------------------------------------------------------

    /**
     * 这个控件（按 id）当前的浏览器会话；没有 / 已关闭 / 已释放都返回 {@code null}。
     *
     * <p>⚠ 调用方一律再判一次 {@code valid()}：这里返回的对象可能在
     * 下一刻就被 {@link #release} 丢掉（控件离开视野是正常事）。</p>
     */
    static McefBridge.Session sessionFor(UUID widgetId) {
        if (widgetId == null) {
            return null;
        }
        Entry e = SESSIONS.get(widgetId);
        return e == null ? null : e.session;
    }

    /**
     * 视口尺寸（像素）——<b>「已经下发给浏览器」的那一份</b>，即 {@code Entry.width/height}。
     *
     * <p>⚠ <b>输入换算是这里唯一正确的尺寸来源</b>：resize 有
     * {@link #RESIZE_SETTLE_MS} 的去抖，编辑器里刚拖过尺寸时
     * 「按控件现在算出来的 {@link #pixelSize}」与浏览器里真实的视口<b>还不是一回事</b>，
     * 用后者换算出来的点会整体偏移（真机上就是「点不中」）。</p>
     *
     * @return {@code {宽, 高}}；没有会话 / 还没下发过尺寸返回 {@code null}
     */
    static int[] viewportFor(UUID widgetId) {
        if (widgetId == null) {
            return null;
        }
        Entry e = SESSIONS.get(widgetId);
        if (e == null || e.width <= 0 || e.height <= 0) {
            return null;
        }
        return new int[]{e.width, e.height};
    }

    /**
     * 「这个控件刚被我用手碰过」：续上视野与 LRU 的时间戳。
     *
     * <p>输入转发时调。理由：玩家正在看/点这个网页时，它<b>不该</b>被
     * {@link #idleReleaseMs()} 的「离开视野」判据或因并发上限的 LRU 让位给放掉 ——
     * 那会变成「点了两下页面忽然变回占位块」。</p>
     */
    static void markUsed(UUID widgetId) {
        if (widgetId == null) {
            return;
        }
        Entry e = SESSIONS.get(widgetId);
        if (e == null) {
            return;
        }
        long now = System.currentTimeMillis();
        e.lastUsedMs = now;
        e.lastSeenMs = now;
    }

    /**
     * 给界面显示的一行状态（如实描述，不假装能显示）。
     *
     * <p>顺序即优先级：不可用 &gt; 已判死（含**无帧**）&gt; 没会话 &gt; 初始化中 &gt; 加载中 &gt; 已就绪。</p>
     *
     * <p><b>判据全是「轮询 + {@code textureId()!=0}」的组合</b>（mcef-bridge 报告第 1 条）：
     * 安卓桥那条路上 {@code onLoadEnd} 永不触发，所以状态绝不能靠加载完成回调驱动 ——
     * 「没有画面」看 {@code textureId()}、「还在拉页面」看轮询 {@code isLoading()}，
     * 两者都可靠且两边平台一致。</p>
     */
    /**
     * 这个控件的网页**出画面了没有**（{@code textureId != 0}）。
     *
     * <p>给「世界里这一下该干什么」用：没有画面时左键点了也是白点，
     * 右键就要改成打开编辑器 + 提示玩家先解决画面（否则玩家会被卡死）。</p>
     */
    public static boolean pictureReady(WebWidget w) {
        Entry e = w == null || w.id == null ? null : SESSIONS.get(w.id);
        return e != null && e.session != null && e.session.valid() && e.session.textureId() != 0;
    }

    public static String status(WebWidget w) {
        if (w == null || w.id == null) {
            return "";
        }
        if (!McefBridge.available()) {
            // 前置还没就绪（含「它正在下载运行库、界面被它接管」这种正常态）：
            // 如实说「还没准备好」，**不抛异常、不阻塞、不等**（纯读缓存标志）
            return McefBridge.reason();
        }
        Blocked blocked = BLOCKED.get(w.id);
        if (blocked != null) {
            return blocked.reason;
        }
        Entry e = SESSIONS.get(w.id);
        if (e == null || e.session == null || !e.session.valid()) {
            return "未创建（不在视野内 / 还没轮到 / 已超出并发上限 " + maxSessions() + "）";
        }
        if (e.session.textureId() == 0) {
            long waited = Math.max(0L, (System.currentTimeMillis() - e.createdAtMs) / 1000L);
            return "正在初始化…（已等 " + waited + " 秒，"
                    + (noFrameTimeoutMs() / 1000) + " 秒还没有画面就会放弃）";
        }
        if (e.session.loading()) {
            return "加载中…";
        }
        String url = e.session.currentUrl();
        return url.isEmpty() ? "已就绪" : "已就绪：" + url;
    }

    /**
     * 无帧时的可操作提示。
     *
     * <p>给玩家看，所以不出现 CDP / Chromium / jcef 这些词（规范 §5.6）——
     * 安卓上还有一层「本设备不支持」的平台闸门（{@link McefBridge#reason()}）。</p>
     */
    private static String noFrameReason() {
        return "没有画面：" + (noFrameTimeoutMs() / 1000)
                + " 秒内一帧都没来 —— 安卓上请先确认 Termux 里的浏览器还在后台运行"
                + "（手机休眠/清后台会把它杀掉），再改一下地址重试";
    }

    /** 正在用的浏览器数（诊断 / 上限判定）。 */
    public static int liveCount() {
        int n = 0;
        for (Entry e : SESSIONS.values()) {
            if (e.session != null && e.session.valid()) {
                n++;
            }
        }
        return n;
    }

    /**
     * 诊断用的一行字（塞日志 / 卡顿行用）。
     *
     * <p>只用中性词（「前置」而不是模组名）—— 万一有人把它贴到界面上，
     * 也不违反 §5.6「界面不出现后端名词」的口径；原因文案走 {@link #status}。</p>
     */
    public static String report() {
        int ready = 0;
        int waiting = 0;
        for (Entry e : SESSIONS.values()) {
            if (e.session == null) {
                continue;
            }
            if (e.session.textureId() == 0) {
                waiting++;
            } else {
                ready++;
            }
        }
        return "网页控件[前置=" + (McefBridge.available() ? "已就绪" : "不可用")
                + " 浏览器=" + liveCount() + "/" + maxSessions()
                + " 有帧=" + ready + " 无帧=" + waiting
                + " 已判死=" + BLOCKED.size() + "]";
    }

    // ------------------------------------------------------------------ 工具

    /** 控件 + 到摄像机的距离（排序用）。 */
    private static final class Candidate {
        final WebWidget widget;
        final double distance;

        Candidate(WebWidget widget, double distance) {
            this.widget = widget;
            this.distance = distance;
        }
    }

    /** 控件中心的距离（只用于排序，不要求精确；同平面上多个控件也能分出前后）。 */
    private static double distanceTo(Plane plane, WebWidget w, Vec3 eye) {
        try {
            Vec3 center = plane.canvas().toWorld(w.centerX(), w.centerY(), 0.0);
            return eye.distanceTo(center);
        } catch (Throwable t) {
            return PlaneDistance.distanceToBox(eye.x, eye.y, eye.z,
                    plane.bounds().minX, plane.bounds().minY, plane.bounds().minZ,
                    plane.bounds().maxX, plane.bounds().maxY, plane.bounds().maxZ) + 1.0;
        }
    }

    /**
     * 视口尺寸（像素）：画布尺寸 × {@link WebWidget#pixelPerUnit}，夹在
     * {@code 64x36 ~ 1920x1080}，**宽高比按控件**（只用一个缩放系数）。
     *
     * <p>极端细长的控件（例如宽高比 64:1）在「不低于下限 + 不超上限」之间无解，
     * 这时以**不超上限**为准 —— 宁可扁一点，也不要把显存撑爆。</p>
     *
     * @return {@code {w, h}}；控件尺寸非法时 {@code null}
     */
    /** 抓帧宽度上限（读配置；读不到用 1280）。 */
    public static int maxCaptureWidth() {
        try {
            return Math.max(320, PROJECTOR_CONFIG_CAPW());
        } catch (Throwable t) {
            return 1280;
        }
    }

    private static int PROJECTOR_CONFIG_CAPW() {
        return top.hmjmfabc.projector.ProjectorConfig.INSTANCE == null ? 1280
                : top.hmjmfabc.projector.ProjectorConfig.INSTANCE.webMaxCaptureWidth.get();
    }

    /** 平面集合里有没有网页控件（纯遍历，不碰文件系统/锁）。 */
    private static boolean hasWebWidget(Collection<Plane> planes) {
        if (planes == null || planes.isEmpty()) {
            return false;
        }
        for (Plane plane : planes) {
            if (plane == null || plane.widgets.isEmpty()) {
                continue;
            }
            for (top.hmjmfabc.projector.common.widget.Widget w : plane.widgets) {
                if (w instanceof WebWidget) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 本帧里有没有「网页控件」存在（给每帧的悬停/射线做闸门）。
     *
     * <p>{@code WebSessions.tick(mc, planes)} 本来每帧就要遍历一遍平面，
     * 顺手把这个标记算出来；{@code ClientInputHandler.onRenderFrame} 就能
     * <b>在没有任何网页控件的世界里一次射线都不做</b>（纯 CPU 白省）。</p>
     */
    public static boolean anyWidgetInWorld() {
        return anyWidget;
    }

    private static volatile boolean anyWidget;

    public static int[] pixelSize(WebWidget w) {
        if (w == null || !(w.w > 0) || !(w.h > 0)) {
            return null;
        }
        double ppu = w.pixelPerUnit > 0 ? w.pixelPerUnit : 8.0;
        double wpx = Math.max(1.0, w.w * ppu);
        double hpx = Math.max(1.0, w.h * ppu);
        // ★【27.2-pre-135 性能】抓帧宽度封顶（配置 web.maxCaptureWidth，默认 1280）：
        //   每一帧的代价都正比于像素数（Chromium 编码 → 网络 → 纯 Java 解码 → GL 上传）。
        //   平面上的网页在手机上通常只占屏幕几百像素，抓 1080p/1920 纯属浪费。
        double capW = Math.max(MIN_W_PX, Math.min(MAX_W_PX, maxCaptureWidth()));
        if (wpx > capW) {
            double k = capW / wpx;
            wpx = capW;
            hpx = Math.max(1.0, hpx * k);        // 等比缩，画面不会被拉变形
        }
        double hi = Math.min(MAX_W_PX / wpx, MAX_H_PX / hpx);
        double lo = Math.max(MIN_W_PX / wpx, MIN_H_PX / hpx);
        double scale = 1.0;
        if (scale > hi) {
            scale = hi;
        }
        if (scale < lo) {
            scale = Math.min(lo, hi);
        }
        int outW = Math.max(1, Math.min(MAX_W_PX, (int) Math.round(wpx * scale)));
        int outH = Math.max(1, Math.min(MAX_H_PX, (int) Math.round(hpx * scale)));
        return new int[]{outW, outH};
    }

    /** UUID 短码（日志用，一眼能对上）。 */
    public static String shortId(UUID id) {
        if (id == null) {
            return "?";
        }
        String s = id.toString();
        return s.length() > 8 ? s.substring(0, 8) : s;
    }

    private static String trim(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    // ------------------------------------------------------------------ 纹理适配器

    /**
     * 只做一件事：把 {@code getId()} 转发给 MCEF 的 GL 纹理 id。
     *
     * <p>{@code load()} 必须是空实现且<b>绝不能抛</b>：{@code TextureManager.register}
     * 会调它，抛 {@code IOException} 会把这条纹理换成「丢失纹理」（画出来是紫黑格）。</p>
     */
    private static final class ForwardTexture extends AbstractTexture {
        private final Entry entry;

        ForwardTexture(Entry entry) {
            this.entry = entry;
        }

        @Override
        public int getId() {
            McefBridge.Session s = entry.session;
            return s == null ? 0 : s.textureId();
        }

        @Override
        public void load(ResourceManager manager) {
            // 纹理由 MCEF 自己创建与上传，这里不需要加载任何资源
        }
    }
}

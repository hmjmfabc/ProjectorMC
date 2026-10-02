package top.hmjmfabc.projector.client.media;

import top.hmjmfabc.projector.common.widget.VideoWidget;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 视频控件的「世界内小控件」显隐状态（hotfix-98）。
 *
 * <p>玩法：站在世界里点视频的<b>左下角</b>，会浮出一个小播放键 + 一条进度条；
 * 再点播放键就启停，点进度条就跳进度；<b>5 秒没有任何操作就自动隐藏</b>
 * （不挡画面，也不需要玩家手动关）。</p>
 *
 * <p>状态只存在客户端：它纯粹是「要不要画那几个按钮」，与控件数据无关，
 * 所以不进 NBT、不走网络。控件被删除 / 退出世界时清掉即可。</p>
 */
public final class VideoControls {
    private VideoControls() {
    }

    /** 无操作多久自动隐藏（毫秒）。 */
    public static final long HIDE_MS = 5000L;

    /** 控件 UUID → 隐藏时刻。 */
    private static final Map<UUID, Long> VISIBLE_UNTIL = new ConcurrentHashMap<>();

    /**
     * 世界内点播放键 → 启停。
     *
     * <p>本地先乐观生效（玩家立刻看到按钮变了），再发动作给服务端；
     * 服务端是权威，广播回来会覆盖成一致的状态。</p>
     */
    public static void toggleInWorld(top.hmjmfabc.projector.common.Plane plane,
                                     VideoWidget w) {
        if (plane == null || w == null) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean next = !w.paused;
        w.setPausedAt(next, now);
        reveal(w.id, now);
        net.minecraft.nbt.CompoundTag args = new net.minecraft.nbt.CompoundTag();
        args.putBoolean("value", next);
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new top.hmjmfabc.projector.network.Payloads.WidgetAction(plane.id, w.id, "toggle", args));
        top.hmjmfabc.projector.Projector.LOGGER.info(
                "[Projector][视频] 世界内点击播放键 平面={} 控件={} {}→{}",
                plane.id.toString().substring(0, 8), w.id.toString().substring(0, 8),
                next ? "播放" : "暂停", next ? "（暂停→播放）" : "（播放→暂停）");
    }

    /** 世界内点进度条 → 跳进度（按**毫秒**发，服务端不需要知道时长也能跳对）。 */
    public static void seekInWorld(top.hmjmfabc.projector.common.Plane plane,
                                   VideoWidget w, double fraction) {
        if (plane == null || w == null) {
            return;
        }
        double f = Math.max(0.0, Math.min(1.0, fraction));
        long now = System.currentTimeMillis();
        long duration = w.durationMs();
        long ms = (long) (f * duration);
        w.seekToMs(ms, now);
        reveal(w.id, now);
        net.minecraft.nbt.CompoundTag args = new net.minecraft.nbt.CompoundTag();
        args.putLong("ms", ms);
        // 顺带把真实时长告诉服务端：外部解码器的视频它自己算不出来
        if (duration > 0) {
            args.putLong("durationMs", duration);
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new top.hmjmfabc.projector.network.Payloads.WidgetAction(plane.id, w.id, "seek", args));
        top.hmjmfabc.projector.Projector.LOGGER.info(
                "[Projector][视频] 世界内调进度 {}%（{}ms / {}ms）平面={} 控件={}",
                Math.round(f * 100), ms, duration,
                plane.id.toString().substring(0, 8), w.id.toString().substring(0, 8));
    }

    /** 叫出控件（每次交互都续上 5 秒）。 */
    public static void reveal(UUID widgetId) {
        reveal(widgetId, System.currentTimeMillis());
    }

    public static void reveal(UUID widgetId, long nowMs) {
        if (widgetId != null) {
            VISIBLE_UNTIL.put(widgetId, nowMs + HIDE_MS);
        }
    }

    /** 现在要不要画控件。 */
    public static boolean visible(UUID widgetId, long nowMs) {
        if (widgetId == null) {
            return false;
        }
        Long until = VISIBLE_UNTIL.get(widgetId);
        return until != null && nowMs < until;
    }

    public static boolean visible(VideoWidget w) {
        return w != null && visible(w.id, System.currentTimeMillis());
    }

    // ------------------------------------------------------------------
    // 【27.1.3】按需加载：「点过播放」才允许下载
    // ------------------------------------------------------------------

    /**
     * 我这一端**点过这个视频的控件**（世界内点左下角 / 点播放键 / 拖进度条 /
     * 编辑器里点播放）—— 只有点过，才允许为它下载。
     *
     * <p>为什么必须存在：视频是这条链上最贵的东西（几十~几百 MB 的流量和磁盘）。
     * 以前只要走到平面附近、渲染到它，客户端就自动开始下载 —— 玩家只是路过、
     * 或者只想看平面上的图片，也会白白吃掉流量。现在**点一下才开始**。</p>
     *
     * <p>两个刻意的设计：</p>
     * <ol>
     *   <li><b>状态只在客户端、按玩家各记一份</b>：别人点播放不该决定我的机器要不要下载
     *       （视频的播放/暂停是服务端权威的，但「我要不要加载」纯属本地）。</li>
     *   <li><b>本地已经有的素材不需要点</b>：上传过的视频/图片本机就有，照旧直接显示；
     *       门只挡「需要联网下载」的那一步。</li>
     * </ol>
     */
    private static final Map<UUID, Boolean> REQUESTED = new ConcurrentHashMap<>();

    /** 记下「我要加载这个控件」；每次点击都会调到。 */
    public static void request(UUID widgetId) {
        if (widgetId != null) {
            REQUESTED.put(widgetId, Boolean.TRUE);
        }
    }

    /** 我点过这个控件吗。 */
    public static boolean requested(UUID widgetId) {
        return widgetId != null && REQUESTED.containsKey(widgetId);
    }

    public static boolean requested(VideoWidget w) {
        return w != null && requested(w.id);
    }

    /** 还剩多少毫秒隐藏（诊断用）。 */
    public static long remainingMs(UUID widgetId, long nowMs) {
        Long until = VISIBLE_UNTIL.get(widgetId);
        return until == null ? 0L : Math.max(0L, until - nowMs);
    }

    /** 立刻隐藏（点别处 / 关闭界面时）。 */
    public static void hide(UUID widgetId) {
        if (widgetId != null) {
            VISIBLE_UNTIL.remove(widgetId);
        }
    }

    /** 控件被删除 / 换素材时清掉，避免 UUID 复用时又冒出来。 */
    public static void forget(UUID widgetId) {
        hide(widgetId);
        if (widgetId != null) {
            // 换了素材/换了链接 ⇒ 「我点过」作废，重新点一次才下载（否则新视频会立刻开下）
            REQUESTED.remove(widgetId);
        }
    }

    /** 退出世界时全清。 */
    public static void clear() {
        VISIBLE_UNTIL.clear();
        REQUESTED.clear();
    }

    /** 诊断：当前可见的控件数。 */
    public static int visibleCount(long nowMs) {
        int n = 0;
        for (Long until : VISIBLE_UNTIL.values()) {
            if (until != null && nowMs < until) {
                n++;
            }
        }
        return n;
    }
}

package top.hmjmfabc.projector.client.media;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import top.hmjmfabc.projector.Projector;
import top.hmjmfabc.projector.ProjectorConfig;
import top.hmjmfabc.projector.client.ClientServerInfo;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 【⑫】媒体缓存档位与后台预缓存。
 *
 * <p>旧行为是「渲染到哪个媒体才下载哪个」，新行为是<b>一进服务器就按档位开始缓存</b>：</p>
 * <table border="1">
 *   <tr><th>档位</th><th>行为</th></tr>
 *   <tr><td>a 保守</td><td>所有媒体都只在需要渲染时才缓存（可能长时间加载不出来）</td></tr>
 *   <tr><td>b 平衡（默认，推荐）</td><td>后台提前缓存服务端<b>图片</b>；视频仍按需缓存</td></tr>
 *   <tr><td>c 激进</td><td>提前缓存<b>全部</b>媒体（很费服务端带宽，除非服务端在内网）</td></tr>
 * </table>
 *
 * <p>服务端配置项「允许客户端后台缓存视频」默认<b>不允许</b>；不允许时，
 * 客户端即使选 c 也会<b>自动回滚到 b</b>（只预缓存图片）。</p>
 *
 * <p>预取是<b>串行 + 限速</b>的：一次只请求一份，收到之后（或超时后）再请求下一份。
 * 若并发请求，几百张图片会同时把服务端出站打满，与「节约带宽」的初衷相反。</p>
 */
public final class CachePrefetcher {

    /** 两次预取请求之间的最小间隔（毫秒）。 */
    private static final long GAP_MS = 400L;
    /** 单份媒体的等待上限（毫秒）；超时就跳过，绝不死等。 */
    private static final long PER_ITEM_TIMEOUT_MS = 120_000L;

    private static final Deque<String[]> QUEUE = new ArrayDeque<>();
    private static volatile boolean running;
    private static volatile int done, total;
    /** 已经处理过（成功或放弃）的哈希，避免同一会话反复重排。 */
    private static final java.util.Set<String> HANDLED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private CachePrefetcher() {
    }

    /** 生效的档位：配置档位 + 服务端开关共同决定。 */
    public static int effectiveTier() {
        int tier;
        try {
            tier = ProjectorConfig.INSTANCE.cacheTier.get();
        } catch (Throwable t) {
            tier = 1;
        }
        if (tier < 0) tier = 0;
        if (tier > 2) tier = 2;
        // 服务端不允许后台缓存视频 -> 激进自动回滚到平衡
        if (tier == 2 && !ClientServerInfo.allowBackgroundVideoCache()) {
            tier = 1;
        }
        return tier;
    }

    public static String tierName(int tier) {
        return switch (tier) {
            case 0 -> "\u4fdd\u5b88";
            case 2 -> "\u6fc0\u8fdb";
            default -> "\u5e73\u8861";
        };
    }

    /** 收到服务端清单后调用：按档位重建预取队列并启动。 */
    public static synchronized void onServerInfo() {
        QUEUE.clear();
        HANDLED.clear();
        done = 0;
        total = 0;
        int tier = effectiveTier();
        if (tier == 0) {
            Projector.LOGGER.info("[Projector] 缓存档位=保守：只在需要渲染时才下载媒体");
            return;
        }
        boolean withVideo = (tier == 2);
        int queued = 0;
        for (ClientServerInfo.Media m : ClientServerInfo.media().values()) {
            if (m.video() && !withVideo) continue;
            if (MediaCache.isLocallyAvailable(m.hash())) continue;
            QUEUE.add(new String[]{m.hash(), m.video() ? "1" : "0"});
            queued++;
        }
        total = queued;
        Projector.LOGGER.info("[Projector] 缓存档位={}（含视频={}）：需要预缓存 {} 份媒体",
                tierName(tier), withVideo, queued);
        if (queued == 0) {
            return;
        }
        notifyPlayer("\u5df2\u5f00\u59cb\u540e\u53f0\u7f13\u5b58\u5a92\u4f53\uff08"
                + tierName(tier) + "\uff09\uff1a\u5171 " + queued + " \u4efd\u3002\u8bf7\u52ff\u624b\u52a8\u6e05\u7406 Projector \u7f13\u5b58\u76ee\u5f55\u3002");
        if (!running || LOOP_THREAD == null || !LOOP_THREAD.isAlive()) {
            running = true;
            // 【rc-79】必须用**自己的**线程：这个循环会阻塞等待下载完成
            // （{@code awaitDownload}），而下载的分片处理跑在 {@code MediaCache.worker()}
            // 上。以前两者共用同一个单线程池 ⇒ 预取循环占着唯一线程等自己的后续任务 ⇒
            // 永久互等，玩家看到的就是「任何媒体都传不上去，卡在正在准备…」。
            Thread t = new Thread(CachePrefetcher::loop, "Projector-Prefetch");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            LOOP_THREAD = t;
            t.start();
        }
    }

    /** 【rc-79】预取循环自己的线程（不要用 MediaCache.worker()，见 start() 里的说明）。 */
    private static volatile Thread LOOP_THREAD;

    public static void reset() {
        synchronized (CachePrefetcher.class) {
            QUEUE.clear();
            HANDLED.clear();
            done = 0;
            total = 0;
        }
    }

    /** 进度（供 HUD / 调试用）。 */
    public static int[] progress() {
        return new int[]{done, total};
    }

    private static void loop() {
        while (true) {
            String[] item;
            synchronized (CachePrefetcher.class) {
                item = QUEUE.poll();
                if (item == null) {
                    running = false;
                    int d = done, t = total;
                    if (t > 0) {
                        Projector.LOGGER.info("[Projector] 后台预缓存完成：{}/{}", d, t);
                    }
                    return;
                }
            }
            String hash = item[0];
            boolean video = "1".equals(item[1]);
            if (!HANDLED.add(hash)) continue;      // 本会话已经处理过
            try {
                if (!MediaCache.isLocallyAvailable(hash)) {
                    if (MediaCache.prefetch(hash, video)) {
                        awaitDownload(hash, video);
                    }
                }
            } catch (Throwable t) {
                Projector.LOGGER.debug("[Projector] 预缓存 {} 失败：{}", hash, t.toString());
            } finally {
                done++;
            }
            try {
                Thread.sleep(GAP_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                synchronized (CachePrefetcher.class) {
                    running = false;
                }
                return;
            }
        }
    }

    /**
     * 等一份媒体下载完成。
     *
     * <p>同时看两件事：本地索引里出现了这个哈希（成功），或超过等待上限（放弃）。
     * 用轮询而不是回调，是为了不让预取线程与下载线程互相持锁。</p>
     */
    private static void awaitDownload(String hash, boolean video) {
        long deadline = System.currentTimeMillis() + PER_ITEM_TIMEOUT_MS;
        if (video) {
            // 视频很大，给更宽的窗口
            deadline = System.currentTimeMillis() + PER_ITEM_TIMEOUT_MS * 5;
        }
        while (System.currentTimeMillis() < deadline) {
            if (MediaCache.isLocallyAvailable(hash)) return;
            if (ClientServerInfo.quotaExhausted()) return;   // 服务端当日流量已尽，别再等了
            try {
                Thread.sleep(250L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void notifyPlayer(String message) {
        try {
            Minecraft.getInstance().execute(() -> {
                var player = Minecraft.getInstance().player;
                if (player != null) {
                    player.displayClientMessage(Component.literal(message), false);
                }
            });
        } catch (Throwable ignored) {
        }
    }
}

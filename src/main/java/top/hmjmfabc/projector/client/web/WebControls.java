package top.hmjmfabc.projector.client.web;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import top.hmjmfabc.projector.client.ClientPermissions;
import top.hmjmfabc.projector.common.Plane;
import top.hmjmfabc.projector.common.widget.WebWidget;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 网页控件的「世界内按钮栏」显隐状态与点击处理（27.2）。
 *
 * <p>玩法与视频控件的小播放键<b>完全一致</b>：对着网页控件<b>底部那条</b>点一下，
 * 就会浮出 ⟳ ← → ⌂ 四个小按钮；<b>5 秒没有任何操作就自动隐藏</b>
 * （不挡网页，也不需要玩家手动关）。</p>
 *
 * <p>状态只存在客户端：它纯粹是「要不要画那几个按钮」，与控件数据无关，
 * 所以不进 NBT、不走网络。控件被删除 / 退出世界时清掉即可。</p>
 *
 * <p><b>几何只有一个来源</b>：命中用 {@link WebWidget#hitButton(double, double)}
 * （内部就是 {@code buttonBox(i)}），绘制用同一份 {@code buttonBox(i)} ——
 * 否则必然出现「看得见点不着」。</p>
 *
 * <p><b>坐标</b>：世界里的点击先由 {@code ClientInputHandler.intersectPlane} 变成
 * <b>画布坐标</b>（和视频控件同一条路），这里再经 {@link WebWidget#toLocal}
 * 逆变换成控件局部坐标交给 {@code hitButton}（它要的是局部坐标，
 * 而 {@code buttonBox} 也是局部的）。</p>
 */
public final class WebControls {
    private WebControls() {
    }

    /** 无操作多久自动隐藏（毫秒）——与视频控件同一档。 */
    public static final long HIDE_MS = 5000L;

    /** 控件 UUID → 隐藏时刻。 */
    private static final Map<UUID, Long> VISIBLE_UNTIL = new ConcurrentHashMap<>();

    /**
     * 我这一端「点过这个网页控件」。
     *
     * <p>为什么需要它（与视频的「点过播放才下载」同一个思路）：网页控件比图片重得多 ——
     * 它会拉起一个浏览器进程并持续联网。所以按钮栏<b>不是</b>一进视野就浮出来，
     * 而是「我点过它」之后才允许出现，配合 5 秒窗口，既够用也不碍事。</p>
     */
    private static final Map<UUID, Boolean> REQUESTED = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------
    // 显示
    // ------------------------------------------------------------------

    /**
     * 现在要不要画这条按钮栏。
     *
     * <p>三条缺一不可：①作者在编辑器里开着「显示按钮栏」；
     * ②我这一端点过它（{@link #request}）；③还在 5 秒窗口内。</p>
     */
    public static boolean visible(WebWidget w) {
        return visible(w, System.currentTimeMillis());
    }

    public static boolean visible(WebWidget w, long nowMs) {
        if (w == null || w.id == null || !w.showControls) {
            return false;
        }
        if (!requested(w.id)) {
            return false;
        }
        Long until = VISIBLE_UNTIL.get(w.id);
        return until != null && nowMs < until;
    }

    /** 叫出按钮栏（每次交互都续上 5 秒），同时记下「我点过」。 */
    public static void reveal(UUID widgetId) {
        reveal(widgetId, System.currentTimeMillis());
    }

    public static void reveal(UUID widgetId, long nowMs) {
        if (widgetId != null) {
            REQUESTED.put(widgetId, Boolean.TRUE);
            VISIBLE_UNTIL.put(widgetId, nowMs + HIDE_MS);
        }
    }

    /**
     * 记下「我要用这个网页控件」，并顺带把按钮栏叫出来 5 秒；每次点击都会调到。
     *
     * <p>与视频那边略有不同：视频的 {@code request} 是「允许下载」的**门**（下载很贵），
     * 而网页这一层的「我点过它」和「把它叫出来」是同一件事 —— 这里没有下载门，
     * 所以 {@code request} 直接等价于 {@link #reveal(UUID)}。
     * {@code WebSessions.navigate()} 里也会调它（作者主动设过地址 = 他要用这个控件），
     * 于是设完地址回到世界，那排按钮栏会先浮出来一下。</p>
     */
    public static void request(UUID widgetId) {
        reveal(widgetId);
    }

    /** 我点过这个控件吗。 */
    public static boolean requested(UUID widgetId) {
        return widgetId != null && REQUESTED.containsKey(widgetId);
    }

    public static boolean requested(WebWidget w) {
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

    /** 控件被删除 / 关掉按钮栏时清掉，避免 UUID 复用时又冒出来。 */
    public static void forget(UUID widgetId) {
        hide(widgetId);
        if (widgetId != null) {
            REQUESTED.remove(widgetId);
        }
    }

    /**
     * 退出世界时全清（{@code ProjectorClient.onLogout} 已经在调）。
     *
     * <p>顺带把网页输入的运行期状态（页面光标、悬停节流、待发的松开）一起清掉：
     * 「清掉这个控件在世界里的浮层状态」只有这一个入口，别在多处各清一半。</p>
     */
    public static void clear() {
        VISIBLE_UNTIL.clear();
        REQUESTED.clear();
        WebInput.clear();
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

    // ------------------------------------------------------------------
    // 点击
    // ------------------------------------------------------------------

    /** 世界里的一次点击：先做世界射线（复用视频控件那套逆变换），再交给 {@link #click}。 */
    public static boolean handleWorldClick(net.minecraft.client.Minecraft mc, Plane plane,
                                          WebWidget w) {
        if (mc == null || mc.player == null || plane == null || w == null) {
            return false;
        }
        Vec3 eye = mc.player.getEyePosition(1.0f);
        Vec3 dir = mc.player.getViewVector(1.0f);
        Double t = top.hmjmfabc.projector.client.ClientInputHandler.intersectPlane(
                plane, eye, dir, top.hmjmfabc.projector.ProjectorConfig.INSTANCE.selectDistance.get());
        if (t == null) {
            return false;
        }
        Vec3 hit = eye.add(dir.scale(t));
        return click(plane, w, plane.canvasX(hit), plane.canvasY(hit));
    }

    /**
     * 世界里点在网页控件上（参数是<b>画布坐标</b>）。
     *
     * @return true 表示这次点击已经被吃掉（调用方直接 return）
     */
    public static boolean click(Plane plane, WebWidget w, double canvasX, double canvasY) {
        if (plane == null || w == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        // 局部坐标：hitButton/inBar 要的都是控件局部坐标（未旋转、相对锚点）
        double[] local = w.toLocal(canvasX, canvasY);
        boolean vis = visible(w, now);

        if (vis) {
            int index = w.hitButton(local[0], local[1]);
            if (index >= 0) {
                // 允许他人操作：与视频控件同一条口径 —— 这是「用一下」不是「改内容」，
                // 但作者可以关掉它（publicControls），此时只有能改内容的人按得动。
                // 【27.2】判据抽成 usable(...)：网页画面的点击转发（WebInput）用的是**同一份**。
                if (!usable(plane, w)) {
                    notifyDenied();
                    reveal(w.id, now);
                    return true;
                }
                // 本地立刻导航（每个客户端各有自己的浏览器），同时发一条给服务端留审计
                WebSessions.action(w, index);
                sendAction(plane, w, index);
                reveal(w.id, now);
                top.hmjmfabc.projector.Projector.LOGGER.info(
                        "[Projector][\u7f51\u9875] \u4e16\u754c\u5185\u70b9\u4e86\u6309\u94ae {} \u5e73\u9762={} \u63a7\u4ef6={}",
                        actionName(index), plane.id.toString().substring(0, 8),
                        w.id.toString().substring(0, 8));
                return true;
            }
        }
        if (w.showControls && w.inBar(local[0], local[1])) {
            // 只叫出按钮栏，不做别的：避免「想调出来却误触后退」
            // 【snapshot-126】这条分支**吃掉点击却什么都不转发**，以前是静默的
            // ⇒ 真机上看起来就是「点网页没反应」。现在留痕（见 WebInput.logBarSwallowed）：
            // 玩家下次一试，日志就能把「落在按钮栏区域」与「权限/会话/判据」区分开。
            WebInput.logBarSwallowed(local[0], local[1], w);
            reveal(w.id, now);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 「用一下」的判据（**唯一一份**）
    // ------------------------------------------------------------------

    /**
     * 网页控件的「用一下」判据：作者开着 {@code publicControls}，或者我本来就能改这个平面的内容。
     *
     * <p><b>这是全项目唯一一份</b>：世界里的四个导航按钮（{@link #click}）与
     * 网页画面的点击/滚轮转发（{@code WebInput}）都调它。本项目复发过的坑是
     * 「同一件事两份判定，总有一份忘改」——所以这里连提示文案也是同一份
     * （{@link #notifyDenied()}）。</p>
     *
     * <p>纯逻辑重载（{@link #usable(boolean, boolean)}）是给无头验证用的：
     * 语义就是「或」，一眼可验，不需要造一个真的平面与玩家。</p>
     */
    public static boolean usable(Plane plane, WebWidget w) {
        return w != null && usable(w.publicControls, ClientPermissions.canEditContent(plane));
    }

    /**
     * 判据的纯逻辑形式（真值表见 {@code tmp/v35/T35.java}）。
     *
     * <p>只有这两条输入：①作者允许别人用（{@code publicControls}）
     * ②我本来就能改这个平面的内容（{@code canEditContent}）。</p>
     */
    public static boolean usable(boolean publicControls, boolean canEditContent) {
        return publicControls || canEditContent;
    }

    /**
     * 「作者不允许他人操作这个网页」的提示（与按钮栏/点击转发共用一句话，不写两遍文案）。
     *
     * <p>同一句话至少隔 {@link #DENY_MSG_INTERVAL_MS} 才再显示一次：滚轮一次会来好几条事件、
     * 连点也会来好几下，不节流就是刷屏。</p>
     *
     * <p>尽力而为：拿不到客户端/玩家时静默（这段也可能在无头验证里被调到，
     * 一句提示发不出去不该让输入处理崩）。</p>
     */
    static void notifyDenied() {
        long now = System.currentTimeMillis();
        if (now - lastDenyMsgMs < DENY_MSG_INTERVAL_MS) {
            return;
        }
        lastDenyMsgMs = now;
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.player != null) {
                mc.player.displayClientMessage(Component.translatable("projector.msg.web_public_denied"), true);
            }
        } catch (Throwable ignored) {
            // 提示发不出去不影响判定结果
        }
    }

    /**
     * 「右键不操作 —— 请用左键」的明文提示（玩家 2026-10-02 口径）。
     *
     * <p>网页控件在世界里只认**左键**；右键什么都不做，但必须<b>告诉玩家为什么</b>（不许静默）。
     * 同一句话 2 秒最多一次，和权限那句同一个做法（尽力而为，拿不到玩家就只留日志）。</p>
     */
    public static void notifyLeftNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastLeftMsgMs < LEFT_MSG_INTERVAL_MS) {
            return;
        }
        lastLeftMsgMs = now;
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.player != null) {
                mc.player.displayClientMessage(Component.translatable(
                        "projector.msg.web_need_left",
                        "\u64cd\u4f5c\u7f51\u9875\u8bf7\u7528\u5de6\u952e"), true);
            }
        } catch (Throwable ignored) {
            // 提示发不出去不影响判定结果
        }
        top.hmjmfabc.projector.Projector.LOGGER.info(
                "[Projector][网页] 右键不操作：网页控件只认**左键**（已提示玩家）");
    }

    /**
     * 「网页还没有画面，先等一下 / 检查 Termux 里的浏览器」的明文提示（限流）。
     *
     * <p>真机上最容易踩的一条：Termux 里的 Chromium 被系统清掉之后，网页控件还能建会话、
     * 还能收到点击事件，但<b>永远不会有画面</b> —— 玩家看到的却是「点了没反应」。
     * 所以这条必须说出来（不许静默）。</p>
     */
    public static void notifyNoPage() {
        long now = System.currentTimeMillis();
        if (now - lastNoPageMsgMs < LEFT_MSG_INTERVAL_MS) {
            return;
        }
        lastNoPageMsgMs = now;
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.player != null) {
                mc.player.displayClientMessage(Component.translatable(
                        "projector.msg.web_no_page",
                        "\u7f51\u9875\u8fd8\u6ca1\u6709\u753b\u9762\uff1a\u8bf7\u786e\u8ba4 Termux \u91cc\u7684\u6d4f\u89c8\u5668\u8fd8\u5728\u8fd0\u884c"),
                        true);
            }
        } catch (Throwable ignored) {
            // 同上：提示尽力而为
        }
        top.hmjmfabc.projector.Projector.LOGGER.info(
                "[Projector][网页] 网页没有画面：已提示玩家检查 Termux 里的浏览器（点击暂不生效）");
    }

    private static volatile long lastNoPageMsgMs;

    /** 「准心要对准网页控件」的明文提示（按了 I 但没瞄准时）。 */
    public static void notifyNeedAim() {
        long now = System.currentTimeMillis();
        if (now - lastAimMsgMs < LEFT_MSG_INTERVAL_MS) {
            return;
        }
        lastAimMsgMs = now;
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.player != null) {
                mc.player.displayClientMessage(Component.translatable(
                        "projector.msg.web_need_aim",
                        "\u5148\u628a\u51c6\u5fc3\u5bf9\u51c6\u7f51\u9875\u63a7\u4ef6\uff0c\u518d\u6309 I \u6253\u5b57"),
                        true);
            }
        } catch (Throwable ignored) {
        }
        top.hmjmfabc.projector.Projector.LOGGER.info(
                "[Projector][网页] 按了 I 但准心没落在网页控件上（已提示玩家）");
    }

    private static volatile long lastAimMsgMs;

    /** 「打完回车就送进网页」的提示（按 I 之后）。 */
    public static void notifyTyping() {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.player != null) {
                mc.player.displayClientMessage(Component.translatable(
                        "projector.msg.web_typing",
                        "\u6b63\u5728\u8f93\u5165\u7ed9\u7f51\u9875\uff1a\u6253\u5b8c\u56de\u8f66\u5373\u9001\u5165\uff08\u4e0d\u4f1a\u53d1\u5230\u516c\u5c4f\uff09"),
                        true);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 「请用左键」的最小重复间隔（毫秒）。 */
    private static final long LEFT_MSG_INTERVAL_MS = 2_000L;

    private static volatile long lastLeftMsgMs;

    /** 「不允许操作」这句话的最小重复间隔（毫秒）。 */
    private static final long DENY_MSG_INTERVAL_MS = 1_000L;

    private static volatile long lastDenyMsgMs;

    /** 按钮下标 → 动作名（与 {@code WebWidget.BTN_*} 一一对应）。 */
    public static String actionName(int index) {
        return switch (index) {
            case WebWidget.BTN_BACK -> "back";
            case WebWidget.BTN_FORWARD -> "forward";
            case WebWidget.BTN_REFRESH -> "refresh";
            case WebWidget.BTN_HOME -> "home";
            default -> "unknown" + index;
        };
    }

    /**
     * 把导航动作发一条给服务端：导航是「用一下」，服务端在权限闸门<b>之前</b>放行并打审计日志
     * （见 {@code ServerNetHandler.onWidgetAction} 的网页分支）。
     *
     * <p>它<b>不改控件内容、也不广播</b>：网页画面在每个客户端各自的浏览器里，
     * 我按「后退」不该把别人的页面也倒回去。</p>
     */
    private static void sendAction(Plane plane, WebWidget w, int index) {
        try {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new top.hmjmfabc.projector.network.Payloads.WidgetAction(
                            plane.id, w.id, actionName(index), new CompoundTag()));
        } catch (Throwable t) {
            // 单人存档 / 未连接时不发也不该崩（导航本身已经在本地做完了）
            top.hmjmfabc.projector.Projector.LOGGER.debug(
                    "[Projector][网页] 导航动作未能发给服务端（单人/未连接？）：{}", t.toString());
        }
    }

    /** 编辑器按钮（⟳ ← → ⌂）共用：本地导航 + 发审计 + 叫出按钮栏。 */
    public static void navigateByButton(Plane plane, WebWidget w, int index) {
        if (w == null) {
            return;
        }
        WebSessions.action(w, index);
        if (plane != null) {
            sendAction(plane, w, index);
        }
        reveal(w.id);
        // 编辑器里按过 ⇒ 回到世界时这条栏也会先浮出来一下（5 秒），
        // 免得玩家以为「按钮没生效」。
    }
}

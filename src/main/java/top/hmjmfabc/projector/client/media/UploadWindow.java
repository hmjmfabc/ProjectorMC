package top.hmjmfabc.projector.client.media;

/**
 * 【rc-77】上传流控窗口（纯逻辑，便于无头环境验证）。
 *
 * <p>要解决的问题：{@code PacketDistributor.sendToServer} 是**非阻塞**的，
 * 如果按「能发多快就发多快」写，几十 MB 会在客户端 socket 里排队，
 * 连保活包都挤不出去 ⇒ 服务端判超时踢人，而这一份文件还白传（用户重试 ⇒ 流量成倍）。
 * 所以改成「最多领先窗口大小的未确认字节」，等服务端的 {@code MediaAck} 再继续。</p>
 *
 * <p>窗口用最简单的 AIMD：每收到一次推进就翻倍（上限 {@link #MAX_WINDOW}），
 * 超时则减半（下限 {@link #MIN_WINDOW}）。链路好时很快跑满，链路差时自动收敛。</p>
 */
public final class UploadWindow {

    /** 最少同时出发的分片数（再差也要能推进）。 */
    public static final int MIN_WINDOW = 4;
    /** 最多同时出发的分片数（避免又把数据堆起来）。 */
    public static final int MAX_WINDOW = 64;
    /** 起步窗口：小一点，先摸清链路。 */
    public static final int START_WINDOW = 8;

    private int window = START_WINDOW;
    private int acked;
    private int timeouts;

    /** 当前窗口（未确认分片数上限）。 */
    public int window() {
        return window;
    }

    /** 服务端已确认收到的字节数。 */
    public int acked() {
        return acked;
    }

    /** 已经超时过几次（排查用）。 */
    public int timeouts() {
        return timeouts;
    }

    /** 还能不能再发：未确认字节数 < 窗口。 */
    public boolean canSend(int inFlight) {
        return inFlight < window;
    }

    /** 收到确认：只认「往前推进」的确认，重复/回退的确认不缩小窗口。 */
    public void onAck(int received) {
        if (received <= acked) return;
        acked = received;
        window = Math.min(MAX_WINDOW, Math.max(MIN_WINDOW, window * 2));
    }

    /** 等确认超时：窗口减半，重试。 */
    public void onTimeout() {
        timeouts++;
        window = Math.max(MIN_WINDOW, window / 2);
    }
}

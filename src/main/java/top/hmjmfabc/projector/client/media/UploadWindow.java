package top.hmjmfabc.projector.client.media;

/**
 * 【rc-77】上传流控窗口（纯逻辑，便于无头环境验证）。
 *
 * <p>要解决的问题：{@code PacketDistributor.sendToServer} 是**非阻塞**的，
 * 如果按「能发多快就发多快」写，几十 MB 会在客户端 socket 里排队，
 * 连保活包都挤不出去 ⇒ 服务端判超时踢人，而这一份文件还白传（用户重试 ⇒ 流量成倍）。
 * 所以改成「最多领先窗口大小的**未确认字节**」，等服务端的 {@code MediaAck} 再继续。</p>
 *
 * <p>窗口用最简单的 AIMD：每收到一次推进就翻倍（上限 {@link #MAX_WINDOW_BYTES}），
 * 超时则减半（下限 {@link #MIN_WINDOW_BYTES}）。链路好时很快跑满，链路差时自动收敛。</p>
 *
 * <p>⚠ <b>单位必须是字节</b>（27.2.4-201 修）：以前窗口是「分片个数」，而调用方传进来的是
 * <b>字节数</b> —— 一个 20 KB 的分片发出去后「在途字节 = 20480」立刻超过窗口上限 64，
 * 于是<b>永远只有一个分片在途</b>，整条上传退化成「发一片、等一个来回、再发一片」。
 * 玩家看到的「上传奇慢无比」就是这条：速度 = 分片大小 ÷ 一次来回，
 * 与链路带宽无关。现在窗口与在途量都是字节，窗口内可以同时压进几十个分片
 * ⇒ 吞吐由带宽决定，不再由延迟决定。</p>
 */
public final class UploadWindow {

    /** 最少同时在途字节数（再差的链路也要能推进；≈ 2 个 128 KB 分片）。 */
    public static final int MIN_WINDOW_BYTES = 256 * 1024;
    /** 最多同时在途字节数（客户端堆与 socket 写缓冲的上限，4 MB）。 */
    public static final int MAX_WINDOW_BYTES = 4 * 1024 * 1024;
    /** 起步窗口：1 MB（≈ 8 个 128 KB 分片，一上来就够把「每片等一个来回」摊平）。 */
    public static final int START_WINDOW_BYTES = 1024 * 1024;

    private long windowBytes = START_WINDOW_BYTES;
    private long acked;
    private int timeouts;

    /** 当前窗口（允许的未确认**字节数**上限）。 */
    public long windowBytes() {
        return windowBytes;
    }

    /** 服务端已确认收到的字节数。 */
    public long acked() {
        return acked;
    }

    /** 已经超时过几次（排查用）。 */
    public int timeouts() {
        return timeouts;
    }

    /**
     * 还能不能再发。
     *
     * @param inFlightBytes 已发出但还没被确认的**字节数**（= 已发送 − 已确认）
     */
    public boolean canSend(long inFlightBytes) {
        return inFlightBytes < windowBytes;
    }

    /** 收到确认：只认「往前推进」的确认，重复/回退的确认不缩小窗口。 */
    public void onAck(long received) {
        if (received <= acked) {
            return;
        }
        acked = received;
        windowBytes = Math.min(MAX_WINDOW_BYTES, Math.max(MIN_WINDOW_BYTES, windowBytes * 2));
    }

    /** 等确认超时：窗口减半，重试。 */
    public void onTimeout() {
        timeouts++;
        windowBytes = Math.max(MIN_WINDOW_BYTES, windowBytes / 2);
    }
}

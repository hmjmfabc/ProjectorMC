import top.hmjmfabc.projector.client.media.UploadWindow;

/**
 * T18 ——【rc-77】上传流控窗口（纯逻辑）。
 *
 * <p>背景：上传用 {@code PacketDistributor.sendToServer} 是**非阻塞**的，
 * 不流控就会把几十 MB 塞进 socket 写缓冲，连保活包都排不出去 ⇒ 服务端判 {@code Timed out}
 * 踢人、这一份文件还白传（用户重试 ⇒ 流量成倍）。所以改成「最多领先窗口大小的未确认分片」。</p>
 *
 * <p>这里断言的是窗口本身的行为：起步值、AIMD（确认翻倍 / 超时减半）、上下限，
 * 以及一条**物理不变量**：最坏情况下的在途字节数必须远小于「能把保活包饿死」的量级
 *（保活包一旦排在几十 MB 后面，30 秒的超时窗口必然被吃满）。</p>
 */
public final class T18 {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        UploadWindow w = new UploadWindow();

        check("起步窗口 = " + UploadWindow.START_WINDOW, w.window() == UploadWindow.START_WINDOW,
                "实际=" + w.window());
        check("起步时已确认字节数 = 0", w.acked() == 0, "实际=" + w.acked());

        // 窗口内可以继续发
        check("未确认分片数 < 窗口时允许继续发送", w.canSend(UploadWindow.START_WINDOW - 1), "");
        check("未确认分片数 = 窗口时不再发送", !w.canSend(UploadWindow.START_WINDOW), "");

        // 确认推进 → 窗口翻倍
        w.onAck(20 * 1024);
        check("收到确认后已确认字节数推进", w.acked() == 20 * 1024, "实际=" + w.acked());
        check("确认推进后窗口翻倍", w.window() == UploadWindow.START_WINDOW * 2, "实际=" + w.window());

        // 重复/回退的确认不许改变窗口（否则一个重复包就把窗口越推越大）
        int before = w.window();
        w.onAck(20 * 1024);
        w.onAck(1024);
        check("重复 / 回退的确认不改变窗口", w.window() == before && w.acked() == 20 * 1024,
                "窗口=" + w.window() + " 已确认=" + w.acked());

        // 一直推进 → 封顶
        for (int i = 0; i < 20; i++) w.onAck(w.acked() + 20 * 1024);
        check("窗口不会超过上限 " + UploadWindow.MAX_WINDOW, w.window() == UploadWindow.MAX_WINDOW,
                "实际=" + w.window());

        // 超时 → 减半，且不低于下限
        w.onTimeout();
        check("超时后窗口减半", w.window() == UploadWindow.MAX_WINDOW / 2, "实际=" + w.window());
        for (int i = 0; i < 20; i++) w.onTimeout();
        check("窗口不会低于下限 " + UploadWindow.MIN_WINDOW, w.window() == UploadWindow.MIN_WINDOW,
                "实际=" + w.window());
        check("超时次数被记录（供排查）", w.timeouts() == 21, "实际=" + w.timeouts());

        // 物理不变量：最坏在途字节 = 窗口上限 × 分片大小，必须远小于 4 MB
        int chunk = 20 * 1024;
        long worstInFlight = (long) UploadWindow.MAX_WINDOW * chunk;
        check("最坏在途字节 " + worstInFlight + " 字节 < 4 MB（不会把保活包饿死）",
                worstInFlight < 4L * 1024 * 1024, "实际=" + worstInFlight);

        System.out.println();
        System.out.println("== " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " ==  " + pass + " passed");
        if (fail > 0) System.exit(1);
    }

    private static void check(String what, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("PASS " + what);
        } else {
            fail++;
            System.out.println("FAIL " + what + (detail.isEmpty() ? "" : "   ← " + detail));
        }
    }

    private T18() {
    }
}

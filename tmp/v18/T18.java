import top.hmjmfabc.projector.client.media.UploadWindow;

/**
 * T18 ——【rc-77 / 27.2.4-201】上传流控窗口（纯逻辑）。
 *
 * <p>背景：上传用 {@code PacketDistributor.sendToServer} 是**非阻塞**的，
 * 不流控就会把几十 MB 塞进 socket 写缓冲，连保活包都排不出去 ⇒ 服务端判 {@code Timed out}
 * 踢人、这一份文件还白传（用户重试 ⇒ 流量成倍）。所以改成「最多领先窗口字节的未确认数据」。</p>
 *
 * <p>⚠ 本轮修复的重点是**单位**：以前窗口是「分片个数」（4~64），而调用方传进来的是
 * 「在途字节数」。一个 20 KB 的分片发出去，在途字节 20480 立刻大于 64 ⇒
 * 窗口永远只放行一个分片 ⇒ 上传退化成「发一片、等一个来回、再发一片」，
 * 速度 = 分片大小 ÷ 一次来回（玩家实测：几十 MB 的视频要几十分钟，
 * 也就是他报的「上传慢到离谱」）。下面有专门的断言把这条守死。</p>
 */
public final class T18 {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        UploadWindow w = new UploadWindow();

        // ---------------------------------------------------------------
        // 一、基础行为（起步值 / AIMD / 上下限）
        // ---------------------------------------------------------------
        check("起步窗口 = " + UploadWindow.START_WINDOW_BYTES + " 字节",
                w.windowBytes() == UploadWindow.START_WINDOW_BYTES, "实际=" + w.windowBytes());
        check("起步时已确认字节数 = 0", w.acked() == 0, "实际=" + w.acked());

        check("在途字节 < 窗口时允许继续发送", w.canSend(UploadWindow.START_WINDOW_BYTES - 1), "");
        check("在途字节 = 窗口时不再发送", !w.canSend(UploadWindow.START_WINDOW_BYTES), "");

        w.onAck(128 * 1024);
        check("收到确认后已确认字节数推进", w.acked() == 128 * 1024, "实际=" + w.acked());
        check("确认推进后窗口翻倍", w.windowBytes() == UploadWindow.START_WINDOW_BYTES * 2,
                "实际=" + w.windowBytes());

        long before = w.windowBytes();
        w.onAck(128 * 1024);
        w.onAck(1024);
        check("重复 / 回退的确认不改变窗口", w.windowBytes() == before && w.acked() == 128 * 1024,
                "窗口=" + w.windowBytes() + " 已确认=" + w.acked());

        for (int i = 0; i < 20; i++) {
            w.onAck(w.acked() + 128 * 1024);
        }
        check("窗口不会超过上限 " + UploadWindow.MAX_WINDOW_BYTES,
                w.windowBytes() == UploadWindow.MAX_WINDOW_BYTES, "实际=" + w.windowBytes());

        w.onTimeout();
        check("超时后窗口减半", w.windowBytes() == UploadWindow.MAX_WINDOW_BYTES / 2,
                "实际=" + w.windowBytes());
        for (int i = 0; i < 20; i++) {
            w.onTimeout();
        }
        check("窗口不会低于下限 " + UploadWindow.MIN_WINDOW_BYTES,
                w.windowBytes() == UploadWindow.MIN_WINDOW_BYTES, "实际=" + w.windowBytes());
        check("超时次数被记录（供排查）", w.timeouts() == 21, "实际=" + w.timeouts());

        // ---------------------------------------------------------------
        // 二、物理不变量：窗口内的在途字节必须有限（不能把保活包饿死）
        // ---------------------------------------------------------------
        check("窗口上限 " + UploadWindow.MAX_WINDOW_BYTES + " 字节 ≤ 4 MB",
                UploadWindow.MAX_WINDOW_BYTES <= 4 * 1024 * 1024,
                "实际=" + UploadWindow.MAX_WINDOW_BYTES);
        check("窗口下限 < 上限", UploadWindow.MIN_WINDOW_BYTES < UploadWindow.MAX_WINDOW_BYTES, "");
        check("起步值落在上下限之间",
                UploadWindow.START_WINDOW_BYTES >= UploadWindow.MIN_WINDOW_BYTES
                        && UploadWindow.START_WINDOW_BYTES <= UploadWindow.MAX_WINDOW_BYTES, "");

        // ---------------------------------------------------------------
        // 三、【本轮回归】窗口按字节算 ⇒ 一次能压进多个分片
        //
        // 模拟上传线程的发送循环（与 MediaUploader 里那段同构）：
        //   while (offset < total && canSend(offset - acked)) { 发一片 }
        // 断言：**起步窗口内至少压进 4 个 128 KB 分片**。
        // 旧实现（窗口 = 分片个数）在 20 KB 分片上只能压进 1 个 —— 这条会直接失败。
        // ---------------------------------------------------------------
        int chunk = 128 * 1024;
        int startPieces = simulateInFlight(chunk, UploadWindow.START_WINDOW_BYTES);
        check("起步窗口至少能压进 4 个 128 KB 分片", startPieces >= 4, "实际=" + startPieces + " 片");
        int maxPieces = simulateInFlight(chunk, UploadWindow.MAX_WINDOW_BYTES);
        check("窗口跑满后至少能压进 8 个 128 KB 分片", maxPieces >= 8, "实际=" + maxPieces + " 片");

        // 反向自检：如果把「在途字节」当「分片个数」用（旧口径），这里只可能是 1
        int oldStyle = oldStyleInFlight(chunk, 20 * 1024);
        check("旧口径（窗口 = 分片个数）确实只能压进 1 个分片 —— 这就是上传慢的根因",
                oldStyle == 1, "实际=" + oldStyle);

        // 吞吐换算：旧口径下 ≈ 分片 ÷ 一次来回；新口径下由窗口决定
        double oldBytesPerSec = chunk / 0.05;
        double newBytesPerSec = (double) maxPieces * chunk / 0.05;
        check("新口径的估算吞吐至少是旧口径的 8 倍（" + mb(newBytesPerSec) + " vs "
                + mb(oldBytesPerSec) + "）", newBytesPerSec >= oldBytesPerSec * 8, "");

        System.out.println();
        System.out.println("== " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " ==  "
                + pass + " passed");
        if (fail > 0) System.exit(1);
    }

    /** 与 MediaUploader 的发送循环同构：把窗口调到目标值后，能连续发几片。 */
    private static int simulateInFlight(int chunk, long windowBytes) {
        UploadWindow w = new UploadWindow();
        int guard = 0;
        while (w.windowBytes() < windowBytes && guard++ < 1000) {
            w.onAck(w.acked() + chunk);
        }
        int sent = 0;
        long offset = 0;
        while (w.canSend(offset - w.acked()) && sent < 10_000) {
            offset += chunk;
            sent++;
        }
        return sent;
    }

    /** 旧口径复现：窗口 = 分片个数，判断时却传在途**字节** ⇒ 只可能是 1 片。 */
    private static int oldStyleInFlight(int chunkBytes, int windowChunks) {
        long offset = 0;
        long acked = 0;
        int sent = 0;
        while (offset - acked < windowChunks && sent < 10_000) {
            offset += chunkBytes;
            sent++;
        }
        return sent;
    }

    private static String mb(double bytesPerSec) {
        return String.format(java.util.Locale.ROOT, "%.1f MB/s", bytesPerSec / 1048576.0);
    }

    private static void check(String what, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("PASS " + what);
        } else {
            fail++;
            System.out.println("FAIL " + what + (detail.isEmpty() ? "" : "   \u2190 " + detail));
        }
    }

    private T18() {
    }
}

import top.hmjmfabc.projector.client.media.LocalMedia;
import top.hmjmfabc.projector.client.media.MediaUploader;
import top.hmjmfabc.projector.common.OnlineVideoLink;
import top.hmjmfabc.projector.common.widget.VideoWidget;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * T42 ——【27.2.4-201】三件事的回归：①MJPEG / ZIP 帧序列不再被识别成视频；
 * ②暂停时的位置由「客户端上报的真实毫秒」决定（不再掉回开头）；
 * ③暂停键在控件隐藏时也生效（几何判据）。
 *
 * <p>背景（玩家原话）：
 * <ul>
 *   <li>「mjpeg和zip帧序列居然还能被识别，这不合理」；</li>
 *   <li>「视频暂停键有时会失灵」；</li>
 *   <li>「本地视频进度条会在暂停后重置」。</li>
 * </ul>
 *
 * <p>②的根因在服务端：外部解码器的视频 {@code frameCount=1, fps=10} ⇒ 服务端算出来的
 * 「时长」只有 100 毫秒，而 {@code setPausedAt} 在服务端拿这个错的时长去冻结位置，
 * 于是 {@code pausedMs} 落在 0~99ms —— 玩家看到进度条一暂停就掉回开头。
 * 现在客户端把真实位置（{@code ms}）与真实时长（{@code durationMs}）随动作一起发过去，
 * 服务端按它 {@code seekToMs} 精确落点。本套用与 {@code ServerNetHandler} 逐字一致的三步验证。</p>
 */
public final class T42 {

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        formats();
        pausePosition();
        clickGeometry();

        System.out.println();
        System.out.println("== " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " ==  "
                + pass + " passed");
        if (fail > 0) System.exit(1);
    }

    // ------------------------------------------------------------------
    // ① 格式拦截
    // ------------------------------------------------------------------
    private static void formats() throws Exception {
        check("视频扩展名列表里不再有 mjpg", !LocalMedia.VIDEO_EXT.contains("mjpg"),
                LocalMedia.VIDEO_EXT.toString());
        check("视频扩展名列表里不再有 mjpeg", !LocalMedia.VIDEO_EXT.contains("mjpeg"),
                LocalMedia.VIDEO_EXT.toString());
        check("视频扩展名列表里不再有 zip", !LocalMedia.VIDEO_EXT.contains("zip"),
                LocalMedia.VIDEO_EXT.toString());
        check("常见容器仍在列表里（mp4/webm/mkv）",
                LocalMedia.VIDEO_EXT.contains("mp4") && LocalMedia.VIDEO_EXT.contains("webm")
                        && LocalMedia.VIDEO_EXT.contains("mkv"), LocalMedia.VIDEO_EXT.toString());

        // 在线直链：mjpg / zip 不再被当成视频扩展名（否则「点播放」会去拉一个放不出来的文件）
        check("在线直链不再认 .mjpg", OnlineVideoLink.extOf("https://x.test/a.mjpg").isEmpty(),
                "'" + OnlineVideoLink.extOf("https://x.test/a.mjpg") + "'");
        check("在线直链不再认 .zip", OnlineVideoLink.extOf("https://x.test/a.zip").isEmpty(),
                "'" + OnlineVideoLink.extOf("https://x.test/a.zip") + "'");
        check("在线直链仍认 .mp4", "mp4".equals(OnlineVideoLink.extOf("https://x.test/a.mp4")),
                "'" + OnlineVideoLink.extOf("https://x.test/a.mp4") + "'");

        // 唯一入口 probeVideo：帧序列一律 null（本测试环境没装 WaterMedia，所以 mp4 也是 null，
        // 这里只断言「帧序列被拒绝」这一条，不依赖任何外置模组）
        Path dir = Path.of("tmp/v42");
        Files.createDirectories(dir);
        Path mjpg = dir.resolve("sample.mjpg");
        // 两个 JPEG 帧拼起来 —— 旧实现（VideoSource.detect）会认成 MJPEG
        byte[] one = {(byte) 0xFF, (byte) 0xD8, 0, 0, (byte) 0xFF, (byte) 0xD9};
        byte[] two = new byte[one.length * 2];
        System.arraycopy(one, 0, two, 0, one.length);
        System.arraycopy(one, 0, two, one.length, one.length);
        Files.write(mjpg, two);
        check("probeVideo 拒绝 .mjpg（不再走内置帧序列解码）",
                MediaUploader.probeVideo(mjpg) == null, "不该返回元数据");

        Path zip = dir.resolve("frames.zip");
        Files.write(zip, new byte[]{0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 0, 0, 0});
        check("probeVideo 拒绝 .zip（不再走内置帧序列解码）",
                MediaUploader.probeVideo(zip) == null, "不该返回元数据");
        check("probeVideo 对空路径不崩", MediaUploader.probeVideo(null) == null, "");
    }

    // ------------------------------------------------------------------
    // ② 暂停位置
    // ------------------------------------------------------------------
    private static void pausePosition() {
        long wall = 1_700_000_000_000L;

        // ---- 客户端侧的控件：解码器报出了真实时长 60 秒，播到 12.345 秒 ----
        VideoWidget client = new VideoWidget();
        client.frameCount = 1;
        client.fps = 10;
        client.mediaDurationMs = 60_000L;
        client.startTimeMs = wall - 12_345L;
        client.loop = true;
        check("客户端算出的位置 = 12345ms", client.positionMs(wall) == 12_345L,
                "实际=" + client.positionMs(wall));

        // ---- 服务端手里的同一份控件：**不知道真实时长**（解码器在客户端） ----
        VideoWidget server = new VideoWidget();
        server.frameCount = 1;
        server.fps = 10;
        server.startTimeMs = 0;                 // 老存档 / 外部解码器的常态

        // 旧实现：服务端自己冻结位置 ⇒ 落在 0~99ms（这就是「进度条重置」）
        server.setPausedAt(true, wall);
        long oldStyle = server.pausedMs;
        check("旧实现（服务端自己算）冻结位置落在 0~99ms —— 就是「一暂停进度条就重置」",
                oldStyle >= 0 && oldStyle < 100, "实际=" + oldStyle);

        // 新实现：服务端先收 durationMs，再按客户端上报的 ms 精确落点
        VideoWidget fixed = new VideoWidget();
        fixed.frameCount = 1;
        fixed.fps = 10;
        fixed.startTimeMs = 0;
        long mediaMs = 60_000L;
        long reportedMs = 12_345L;
        fixed.mediaDurationMs = mediaMs;                 // ← 动作里带过来的 durationMs
        fixed.setPausedAt(true, wall);                   // ← 翻转状态
        fixed.seekToMs(reportedMs, wall);                // ← 再按客户端上报的位置落点
        check("新实现：暂停位置 = 客户端上报的 12345ms", fixed.pausedMs == reportedMs,
                "实际=" + fixed.pausedMs);
        check("新实现：位置读数也是 12345ms", fixed.positionMs(wall) == reportedMs,
                "实际=" + fixed.positionMs(wall));
        check("新实现：暂停状态为真", fixed.paused, "");

        // 暂停期间时间流逝，位置必须**不动**
        check("暂停 5 秒后位置仍然是 12345ms", fixed.positionMs(wall + 5_000L) == reportedMs,
                "实际=" + fixed.positionMs(wall + 5_000L));

        // 继续播放：从暂停处接着走（服务端 startTimeMs = wall2 - pausedMs）
        long resumeWall = wall + 5_000L;
        fixed.setPausedAt(false, resumeWall);
        check("继续播放后立刻仍是 12345ms", fixed.positionMs(resumeWall) == reportedMs,
                "实际=" + fixed.positionMs(resumeWall));
        check("继续播放 1.5 秒后 = 13845ms", fixed.positionMs(resumeWall + 1_500L) == 13_845L,
                "实际=" + fixed.positionMs(resumeWall + 1_500L));

        // 时长未知时不允许把位置夹成 0（旧代码 durationKnown()=false 时会按 100ms 夹）
        VideoWidget noDuration = new VideoWidget();
        noDuration.frameCount = 1;
        noDuration.fps = 10;
        noDuration.setPausedAt(true, wall);
        noDuration.seekToMs(9_000L, wall);
        check("时长未知时 seekToMs 仍然保住 9000ms（不被夹成 100ms）",
                noDuration.pausedMs == 9_000L, "实际=" + noDuration.pausedMs);

        // 非循环视频播到末尾再点继续 = 从头放（既有手感，别退化）
        VideoWidget ended = new VideoWidget();
        ended.frameCount = 1;
        ended.fps = 10;
        ended.mediaDurationMs = 30_000L;
        ended.loop = false;
        ended.setPausedAt(true, wall);
        ended.seekToMs(29_900L, wall);
        ended.setPausedAt(false, wall + 1_000L);
        check("非循环播到结尾再继续 → 从头播放", ended.positionMs(wall + 1_000L) == 0L,
                "实际=" + ended.positionMs(wall + 1_000L));
    }

    // ------------------------------------------------------------------
    // ③ 暂停键的点击几何
    // ------------------------------------------------------------------
    private static void clickGeometry() {
        VideoWidget w = new VideoWidget();
        w.x = 0;
        w.y = 0;
        w.w = 160;
        w.h = 90;
        // 播放键方框（局部坐标）→ 取中心，换算成「未旋转画布坐标」再判命中
        double[] box = w.controlBox();
        double cx = (box[0] + box[2]) / 2.0;
        double cy = (box[1] + box[3]) / 2.0;
        check("点在播放键里 → 命中", w.hitControl(cx, cy), "局部=(" + cx + "," + cy + ")");
        double[] l = w.toLocal(cx, cy);
        check("toLocal 与 rotPt 互逆", Math.abs(l[0] - cx) < 1e-6 && Math.abs(l[1] - cy) < 1e-6,
                "(" + l[0] + "," + l[1] + ")");

        // 旋转 90° 后仍然点得中（几何取自控件、渲染与命中同源）
        VideoWidget r = new VideoWidget();
        r.x = 100;
        r.y = 40;
        r.w = 160;
        r.h = 90;
        r.rot = 90;
        double[] rb = r.controlBox();
        double rcx = (rb[0] + rb[2]) / 2.0;
        double rcy = (rb[1] + rb[3]) / 2.0;
        double[] world = r.rotPt(rcx, rcy);        // 局部 → 画布
        check("旋转 90° 后点在播放键上仍然命中", r.hitControl(world[0], world[1]),
                "画布=(" + world[0] + "," + world[1] + ")");

        // 播放键必须落在热区里（这样「隐藏时点按钮」才可能被识别成点按钮）
        check("播放键方框在热区之内",
                box[0] >= 0 && box[1] >= 0 && box[2] <= w.w && box[3] <= w.h, "");
        check("热区覆盖播放键中心", w.hitHotZone(cx, cy), "");
    }

    private static void check(String what, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("PASS " + what);
        } else {
            fail++;
            System.out.println("FAIL " + what + (detail == null || detail.isEmpty()
                    ? "" : "   \u2190 " + detail));
        }
    }

    private T42() {
    }
}

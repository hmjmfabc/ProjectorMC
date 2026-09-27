package top.hmjmfabc.projector.client.media.wm;

import net.minecraft.nbt.CompoundTag;
import top.hmjmfabc.projector.client.media.VideoControls;
import top.hmjmfabc.projector.client.media.VideoProbe;
import top.hmjmfabc.projector.common.widget.VideoWidget;

import java.util.ArrayList;
import java.util.List;

/**
 * 【27.1.2】WaterMedia 兼容层 + 视频后端选择的纯逻辑验证（T29）。
 *
 * <p>真实播放没法在无头环境跑（要有 WaterMedia 与 GL），所以这里钉的是
 * **判断逻辑与许可边界**：</p>
 * <ul>
 *   <li>哪些扩展名该交给 WaterMedia（MP4/WebM/MKV…），哪些该留给内置后端（mjpg/zip）；</li>
 *   <li>控件时间锚点 → 播放位置的换算（暂停 / 循环 / 越界）；</li>
 *   <li>后端选择字段的 NBT 往返与默认值；</li>
 *   <li><b>许可边界</b>：源码里不许出现对 WaterMedia 的 import / 依赖 / 打包 ——
 *       它是 PolyForm Strict（禁止使用与分发），只能运行时反射调用它的公开 API。</li>
 *   <li><b>两代 API</b>（Build 96 实测踩到）：v3（`MediaAPI`）与 v2（`VideoPlayer` + `preRender()`）
 *       都要认，缺一代就有玩家放不了 mp4。</li>
 * </ul>
 */
public class T29 {
    private static int passed;
    private static final List<String> failed = new ArrayList<>();

    public static void main(String[] args) {
        formats();
        backendField();
        positionMath();
        licenceBoundary();
        dualDialect();
        headerProbe();
        blurredBoundary();
        playbackControls();
        renderingFixes();
        syncByDistance();

        System.out.println();
        for (String f : failed) {
            System.out.println("  \u274c " + f);
        }
        System.out.println("== " + (failed.isEmpty() ? "ALL PASS ==" : "FAILED ==") + "  "
                + passed + " passed, " + failed.size() + " failed");
        if (!failed.isEmpty()) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ 格式判定

    private static void formats() {
        check("mp4 交给 WaterMedia", WaterMediaBridge.isNativeVideo("a.mp4"));
        check("webm 交给 WaterMedia", WaterMediaBridge.isNativeVideo("b.WEBM"));
        check("mkv / mov / avi 都算", WaterMediaBridge.isNativeVideo("c.mkv")
                && WaterMediaBridge.isNativeVideo("d.mov") && WaterMediaBridge.isNativeVideo("e.avi"));
        check("mjpg 留给内置后端", !WaterMediaBridge.isNativeVideo("f.mjpg"));
        check("zip 帧序列留给内置后端", !WaterMediaBridge.isNativeVideo("g.zip"));
        check("图片不算视频", !WaterMediaBridge.isNativeVideo("h.png"));
        check("没有扩展名 / null 不崩", !WaterMediaBridge.isNativeVideo("noext")
                && !WaterMediaBridge.isNativeVideo(null) && !WaterMediaBridge.isNativeVideo(""));
        check("无头环境（没有 ModList）判为不可用", !WaterMediaBridge.available());
    }

    // ------------------------------------------------------------------ 后端字段

    private static void backendField() {
        VideoWidget w = new VideoWidget();
        check("默认后端 = 自动", w.backend == VideoWidget.BACKEND_AUTO);
        check("默认既不强制内置也不强制 WaterMedia", !w.builtinForced() && !w.waterMediaForced());
        check("名称显示为「自动」", "自动".equals(w.backendName()));

        w.backend = VideoWidget.BACKEND_BUILTIN;
        check("强制内置判定正确", w.builtinForced() && !w.waterMediaForced());
        check("名称显示为「内置」", "内置".equals(w.backendName()));

        w.backend = VideoWidget.BACKEND_WATERMEDIA;
        check("强制 WaterMedia 判定正确", w.waterMediaForced() && !w.builtinForced());

        CompoundTag tag = w.save();
        VideoWidget loaded = (VideoWidget) top.hmjmfabc.projector.common.widget.Widgets.load(tag);
        check("NBT 往返保住后端选择", loaded != null && loaded.backend == VideoWidget.BACKEND_WATERMEDIA);
        check("NBT 没写后端时按自动（老存档）", !new CompoundTag().contains("backend"));
    }

    // ------------------------------------------------------------------ 位置换算

    private static void positionMath() {
        VideoWidget w = new VideoWidget();
        w.fps = 10;
        w.loop = true;
        w.startTimeMs = System.currentTimeMillis();   // 刚打上锚点
        w.paused = false;
        w.pausedFrame = 0;

        long atStart = WaterMediaVideos.desiredPositionMs(w, 10_000L);
        check("刚开播位置接近 0（±1.5 秒内）", Math.abs(atStart) < 1500L);
        check("锚点为 0（老存档）时按当前时间对时长取模，与内置后端一致",
                WaterMediaVideos.desiredPositionMs(legacy(), 10_000L) < 10_000L);

        // 用一个「已经过去 3 秒」的锚点
        w.startTimeMs = System.currentTimeMillis() - 3000L;
        long after3s = WaterMediaVideos.desiredPositionMs(w, 60_000L);
        check("3 秒后位置在 3 秒附近", Math.abs(after3s - 3000L) < 1500L);

        // 越界（超过总长）必须被夹住
        long clamped = WaterMediaVideos.desiredPositionMs(w, 1000L);
        check("不循环时位置被夹到总长", clamped <= 1000L);

        w.loop = true;
        long looped = WaterMediaVideos.desiredPositionMs(w, 1000L);
        check("循环时位置绕回 [0,总长)", looped >= 0 && looped < 1000L);

        // 暂停：位置取 pausedMs（hotfix-101 起以毫秒为唯一来源 ——
        // 外部解码器的视频没有帧号，用帧号记位置会退化成 0 = 续播从头开始）
        w.paused = true;
        w.pausedMs = 5_000L;
        w.pausedFrame = 50;
        long paused = WaterMediaVideos.desiredPositionMs(w, 60_000L);
        check("暂停时位置 = pausedMs", Math.abs(paused - 5000L) < 1L);
        check("暂停位置不会随时间走", WaterMediaVideos.desiredPositionMs(w, 60_000L) == paused);

        w.paused = false;
        w.pausedFrame = 0;
        check("时长为 0 时不崩", WaterMediaVideos.desiredPositionMs(w, 0L) >= 0);
    }

    /** 一份 startTimeMs = 0 的控件（老存档/新建）。 */
    private static VideoWidget legacy() {
        VideoWidget w = new VideoWidget();
        w.startTimeMs = 0;
        w.paused = false;
        w.loop = true;
        w.fps = 10;
        return w;
    }

    // ------------------------------------------------------------------ 许可边界

    /**
     * WaterMedia 是 PolyForm Strict（禁止使用 / 分发），所以：
     * **不许 import 它的类、不许声明依赖、不许把它打进 jar** —— 只能反射调 API。
     * 这几条是硬性许可边界，值得用脚本每次钉住。
     */
    private static void licenceBoundary() {
        java.nio.file.Path src = java.nio.file.Path.of("src/main/java");
        int scanned = 0;
        boolean imported = false;
        try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.walk(src)) {
            for (java.nio.file.Path f : stream.filter(p -> p.toString().endsWith(".java")).toList()) {
                scanned++;
                String text = java.nio.file.Files.readString(f);
                if (text.contains("import org.watermedia")) {
                    imported = true;
                    System.out.println("      " + f);
                }
            }
        } catch (Exception e) {
            failed.add("读不到 src/main/java：" + e);
            return;
        }
        check("没有任何文件 import WaterMedia（扫了 " + scanned + " 个文件）", !imported);

        String build;
        try {
            build = java.nio.file.Files.readString(java.nio.file.Path.of("build.gradle"));
        } catch (Exception e) {
            failed.add("读不到 build.gradle");
            return;
        }
        boolean depends = false;
        for (String line : build.split("\\n")) {
            String t = line.trim();
            if (t.startsWith("//") || t.startsWith("*")) {
                continue;
            }
            if (t.contains("watermedia") || t.contains("waterframes")) {
                depends = true;
                System.out.println("      " + t);
            }
        }
        check("build.gradle 没有 WaterMedia 依赖（不编译链接它）", !depends);
        check("桥接代码只按类名字符串反射调用",
                readSelf().contains("Class.forName(\"org.watermedia.api.media.MediaAPI\")"));
    }

    // ------------------------------------------------------------------ 两代 API（Build 96 的坑）

    /**
     * WaterMedia **两代 API 都要认**。
     *
     * <p>Build 95 交付后玩家实测报「装了 WaterMedia 还是说无法识别的视频格式」：
     * 工作区那份源码是 **v3（3.0.0.23）**，玩家装的是 **v2（2.1.37）**，
     * 两代类名完全不同 ⇒ 反射 `Class.forName("org.watermedia.api.media.MediaAPI")` 直接
     * ClassNotFoundException ⇒ 桥接判为不可用 ⇒ 回退内置后端（放不了 mp4）。</p>
     *
     * <p>这里钉住「两代的入口都在源码里」以及「v2 专属的每帧上传 / 循环钩子确实被调用」——
     * 少任何一个，v2 用户那边就是黑屏或不能循环。</p>
     */
    private static void dualDialect() {
        String self = readSelf();
        check("桥接认 v3 的入口 MediaAPI", self.contains("Class.forName(\"org.watermedia.api.media.MediaAPI\")"));
        check("桥接认 v2 的入口 VideoPlayer",
                self.contains("Class.forName(\"org.watermedia.api.player.videolan.VideoPlayer\")"));
        check("v2 的播放控制在 BasePlayer 上绑定",
                self.contains("org.watermedia.api.player.videolan.BasePlayer"));
        check("v2 的每帧上传钩子 preRender 有反射调用", self.contains("\"preRender\""));
        check("v2 的循环钩子 setRepeatMode 有反射调用", self.contains("\"setRepeatMode\""));
        check("v2 的跳转钩子 seekTo(long) 有反射调用", self.contains("\"seekTo\""));
        check("方言枚举三态且标签可读",
                WaterMediaBridge.Dialect.values().length == 3
                        && "无".equals(WaterMediaBridge.Dialect.NONE.label())
                        && "v2".equals(WaterMediaBridge.Dialect.V2.label())
                        && "v3".equals(WaterMediaBridge.Dialect.V3.label()));
        check("无头环境判为 NONE（不崩、不乱认）",
                WaterMediaBridge.dialect() == WaterMediaBridge.Dialect.NONE
                        && "无".equals(WaterMediaBridge.describeDialect()));

        String videos = readFile("src/main/java/top/hmjmfabc/projector/client/media/wm/WaterMediaVideos.java");
        check("调度器每帧调 preRender（v2 不上传帧就是黑屏）", videos.contains("session.preRender()"));
        check("调度器把控件的循环状态交给播放器", videos.contains("session.setLooping(w.loop)"));
        check("v2 的播放器在渲染线程构造（它构造函数里就 glGenTextures）",
                self.contains("newV2Player") && self.contains("CountDownLatch")
                        && self.contains("Minecraft.getInstance().execute"));
    }

    // ------------------------------------------------------------------ 文件头尺寸探测（Build 97）

    /**
     * MP4 / MKV 的宽高必须能「只读文件头」拿到。
     *
     * <p>因为交给外部解码器放的格式我们<b>不解码</b>，没有尺寸就只能按 1:1 摆控件，
     * 竖屏视频会被拉成方的。这里用手工拼出来的最小容器验证解析器：
     * MP4 走 box 树（含 moov 在**文件尾**的手机录制情形），MKV 走 EBML 元素。</p>
     */
    private static void headerProbe() {
        try {
            // ① MP4：ftyp + mdat + moov{ trak{ tkhd 1920x1080 } }，moov 故意放最后
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            out.write(box("ftyp", concat(bytes("isom"), u32(0x200), bytes("isom"), bytes("mp41"))));
            out.write(box("mdat", new byte[64]));
            out.write(box("moov", box("trak", box("tkhd", tkhd(1920, 1080)))));
            java.nio.file.Path mp4 = java.nio.file.Files.createTempFile("t29-", ".mp4");
            java.nio.file.Files.write(mp4, out.toByteArray());
            int[] mp4Size = VideoProbe.size(mp4);
            check("MP4 尺寸从 box 树里读出来（1920x1080）",
                    mp4Size != null && mp4Size[0] == 1920 && mp4Size[1] == 1080);
            java.nio.file.Files.deleteIfExists(mp4);

            // ② MKV：EBML 头 + PixelWidth(0xB0) / PixelHeight(0xBA)
            java.io.ByteArrayOutputStream mkv = new java.io.ByteArrayOutputStream();
            mkv.write(new byte[]{(byte) 0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, (byte) 0x9F});
            mkv.write(new byte[32]);
            mkv.write(new byte[]{(byte) 0xB0, (byte) 0x82, 0x07, (byte) 0x80});   // 1920
            mkv.write(new byte[]{(byte) 0xBA, (byte) 0x82, 0x04, 0x38});           // 1080
            java.nio.file.Path mkvFile = java.nio.file.Files.createTempFile("t29-", ".mkv");
            java.nio.file.Files.write(mkvFile, mkv.toByteArray());
            int[] mkvSize = VideoProbe.size(mkvFile);
            check("MKV/WebM 尺寸从 EBML 元素里读出来（1920x1080）",
                    mkvSize != null && mkvSize[0] == 1920 && mkvSize[1] == 1080);
            java.nio.file.Files.deleteIfExists(mkvFile);

            // ③ 认不出来时必须安静地返回 null（不能抛、不能瞎报尺寸）
            java.nio.file.Path png = java.nio.file.Files.createTempFile("t29-", ".png");
            java.nio.file.Files.write(png, new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0,
                    0, 0, 0, 0});
            check("不是视频容器时返回 null（不抛异常）", VideoProbe.size(png) == null);
            check("null / 不存在的路径也安全",
                    VideoProbe.size(null) == null
                            && VideoProbe.size(java.nio.file.Path.of("tmp/v29/没有这个文件.mp4")) == null);
            java.nio.file.Files.deleteIfExists(png);
        } catch (Exception e) {
            failed.add("文件头探测用例异常：" + e);
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    private static byte[] u32(long v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    private static byte[] concat(byte[]... parts) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.write(p, 0, p.length);
        }
        return out.toByteArray();
    }

    private static byte[] box(String type, byte[] payload) {
        return concat(u32(8L + payload.length), bytes(type), payload);
    }

    /** tkhd（version 0）：宽高是末尾两个 16.16 定点数。 */
    private static byte[] tkhd(int w, int h) {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        b.write(0);                                        // version
        b.write(new byte[3], 0, 3);                        // flags
        b.write(new byte[4], 0, 4);                        // creation
        b.write(new byte[4], 0, 4);                        // modification
        b.write(new byte[4], 0, 4);                        // trackID
        b.write(new byte[4], 0, 4);                        // reserved
        b.write(new byte[4], 0, 4);                        // duration
        b.write(new byte[8], 0, 8);                        // reserved
        b.write(new byte[2], 0, 2);                        // layer
        b.write(new byte[2], 0, 2);                        // alternate group
        b.write(new byte[2], 0, 2);                        // volume
        b.write(new byte[2], 0, 2);                        // reserved
        b.write(new byte[36], 0, 36);                      // matrix
        b.write(u32((long) w << 16), 0, 4);                // width  16.16
        b.write(u32((long) h << 16), 0, 4);                // height 16.16
        return b.toByteArray();
    }

    // ------------------------------------------------------------------ 界面不许提后端（Build 97）

    /**
     * 「模糊界限」：玩家不该知道有两套解码方式，也不该看到格式限制。
     *
     * <p>Build 96 的实测反馈原文：「模糊 watermedia 与 JCodec 的界限，不要特殊提示，
     * 把『仅支持 MJPEG/ZIP 帧序列』的字样都删掉」。这里把它钉成源码级断言，
     * 免得下次改文案时又漏回去。</p>
     */
    private static void blurredBoundary() {
        String picker = readFile("src/main/java/top/hmjmfabc/projector/client/gui/MediaPickerScreen.java");
        String editor = readFile("src/main/java/top/hmjmfabc/projector/client/gui/WidgetEditorScreen.java");
        String convert = readFile("src/main/java/top/hmjmfabc/projector/client/gui/ConvertScreen.java");
        String uploader = readFile("src/main/java/top/hmjmfabc/projector/client/media/MediaUploader.java");

        check("界面里不再出现 WaterMedia 字样（选择器）", !picker.contains("WaterMedia"));
        check("界面里不再出现 WaterMedia 字样（编辑器）", !editor.contains("WaterMedia"));
        check("界面里不再出现 WaterMedia 字样（转换器）", !convert.contains("WaterMedia"));
        check("编辑器不再提供「后端」选择按钮", !editor.contains("\u540e\u7aef\uff1a"));
        check("不再有「仅支持 MJPEG 或 ZIP 帧序列」这类格式限制文案",
                !picker.contains("MJPEG \u6216 ZIP") && !uploader.contains("MJPEG \u6216 ZIP")
                        && !picker.contains("\u65e0\u6cd5\u8bc6\u522b\u7684\u89c6\u9891\u683c\u5f0f")
                        && !uploader.contains("\u65e0\u6cd5\u8bc6\u522b\u7684\u89c6\u9891\u683c\u5f0f"));

        check("上传/选择器都走统一探测入口 probeVideo",
                picker.contains("MediaUploader.probeVideo(") && uploader.contains("probeVideo("));
        check("外部解码器格式只登记本地文件，不建内置帧索引",
                picker.contains("MediaCache.registerExternal(")
                        && readFile("src/main/java/top/hmjmfabc/projector/client/media/MediaCache.java")
                        .contains("public static void registerExternal("));
        String videos = readFile("src/main/java/top/hmjmfabc/projector/client/media/wm/WaterMediaVideos.java");
        check("本地缺文件不算「打不开」（否则下载还没开始就被禁用）",
                videos.contains("else if (hadFile)") && videos.contains("MediaCache.prefetch(hash, true)"));
    }

    // ------------------------------------------------------------------ 视频播放控制（hotfix-98）

    /**
     * 「暂停/启动按钮有时不好使」的根因回归。
     *
     * <p>以前没有任何地方记录「暂停时停在哪一帧」（{@code pausedFrame} 恒为 0），
     * 点暂停画面直接跳回第一帧，点继续又从全局时间接着算——按钮看着就是没反应/乱跳。
     * 现在暂停会冻结当前帧、继续会把时间锚点挪到等于那一帧的位置。</p>
     */
    private static void playbackControls() {
        // 100 帧 @10fps = 10 秒
        VideoWidget w = new VideoWidget();
        w.w = 64;
        w.h = 48;
        w.frameCount = 100;
        w.fps = 10;
        w.loop = true;
        long t0 = 1_700_000_000_000L;

        check("时长 = 帧数 / 帧率（100 帧 10fps = 10 秒）", w.durationMs() == 10_000L);

        // 播到第 3 秒（锚点在 t0 - 3000）
        w.startTimeMs = t0 - 3_000L;
        check("播放位置按时长锚点算（≈3 秒）", Math.abs(w.positionMs(t0) - 3_000L) <= 100L);
        check("进度比例 ≈0.3", Math.abs(w.progressFraction(t0) - 0.3) < 0.02);

        // 暂停：必须冻结在**当前这一帧**，而不是第 0 帧
        w.setPausedAt(true, t0);
        check("暂停后冻结在当前帧（不是跳回第 0 帧）", w.paused && w.pausedFrame == 30);
        check("暂停后位置保持不变（≈3 秒）", Math.abs(w.positionMs(t0 + 5_000L) - 3_000L) <= 100L);

        // 继续：从暂停处接着放（不能按全局时间跳到 8 秒）
        long t1 = t0 + 5_000L;
        w.setPausedAt(false, t1);
        check("继续后从暂停处接着放（不是跳到全局时间）",
                !w.paused && Math.abs(w.positionMs(t1) - 3_000L) <= 100L);
        check("继续后过 1 秒 ≈ 4 秒", Math.abs(w.positionMs(t1 + 1_000L) - 4_000L) <= 120L);

        // 跳进度：播放中与暂停中都要生效
        w.seekToFraction(0.5, t1);
        check("播放中跳进度 50% ≈ 5 秒", Math.abs(w.positionMs(t1) - 5_000L) <= 120L);
        w.setPausedAt(true, t1);
        w.seekToFraction(0.8, t1);
        check("暂停中跳进度 80% ≈ 8 秒（暂停帧跟着变）",
                Math.abs(w.positionMs(t1) - 8_000L) <= 120L);
        check("跳进度被夹在 0~1", clampSeek(w, -5.0) == 0L && clampSeek(w, 9.9) <= w.durationMs());

        // 不循环 + 已放到结尾：再点继续应从头上重放（播放器常规手感）
        VideoWidget e = new VideoWidget();
        e.w = 64;
        e.h = 48;
        e.frameCount = 100;
        e.fps = 10;
        e.loop = false;
        e.startTimeMs = t0 - 20_000L;
        e.setPausedAt(true, t0);
        e.setPausedAt(false, t0);
        check("不循环放到结尾后继续 = 从头重放", e.positionMs(t0) <= 200L);

        // ---- 世界内小播放键的几何（与点击判定同源）----
        check("播放键在左下角（x/y 都从内边距开始）",
                w.controlBox()[0] == w.controlPadding() && w.controlBox()[1] == w.controlPadding());
        double[] hot = w.controlHotZone();
        double[] box = w.controlBox();
        check("热区包住播放键", hot[0] <= box[0] && hot[1] <= box[1]
                && hot[2] >= box[2] && hot[3] >= box[3]);
        check("热区不会超出控件", hot[2] <= w.w + 0.001 && hot[3] <= w.h + 0.001);
        double[] bar = w.progressBox();
        check("进度条在播放键右侧、控件之内",
                bar[0] > box[2] - 0.001 && bar[2] <= w.w + 0.001 && bar[1] < bar[3]);
        check("点在播放键上判为播放键（含旋转后的坐标换算）",
                w.hitControl(w.x + box[0] + 0.5, w.y + box[1] + 0.5));
        check("点在控件右上角不算播放键也不在热区",
                !w.hitControl(w.x + w.w - 0.5, w.y + w.h - 0.5)
                        && !w.hitHotZone(w.x + w.w - 0.5, w.y + w.h - 0.5));
        double frac = w.seekFractionAt(w.x + (bar[0] + bar[2]) / 2.0, w.y + (bar[1] + bar[3]) / 2.0);
        check("点在进度条中点 = 50%", Math.abs(frac - 0.5) < 0.03);
        check("点在进度条之外返回 -1",
                w.seekFractionAt(w.x + w.w - 0.2, w.y + w.h - 0.2) < 0);

        // ---- 【hotfix-101】外部解码器的视频：时长未知时进度条不许乱跳 ----
        VideoWidget ext = new VideoWidget();
        ext.w = 64;
        ext.h = 48;
        ext.frameCount = 1;            // 外部视频没有帧表 ⇒ frameCount=1
        ext.fps = 10;
        ext.loop = true;
        ext.startTimeMs = t0 - 3_000L;
        check("时长未知（frameCount=1 且没读到真时长）时 durationKnown()=false",
                !ext.durationKnown());
        ext.mediaDurationMs = 600_000L;      // 解码器报出 10 分钟
        check("读到真实时长后 durationKnown()=true",
                ext.durationKnown() && ext.durationMs() == 600_000L);
        check("位置按真实时长算（不再每 100ms 绕一圈）",
                Math.abs(ext.progressFraction(t0) - 3_000.0 / 600_000.0) < 0.005);
        // 按毫秒跳（服务端不需要知道时长）
        ext.seekToMs(120_000L, t0);
        check("按毫秒跳转生效", Math.abs(ext.positionMs(t0) - 120_000L) <= 120L);
        ext.setPausedAt(true, t0);
        check("暂停冻结在毫秒位置（外部解码器没有帧号也能续播）",
                ext.paused && Math.abs(ext.pausedMs - 120_000L) <= 120L);
        ext.setPausedAt(false, t0 + 1_000L);
        check("继续播放从暂停处接着（不是从头）",
                Math.abs(ext.positionMs(t0 + 1_000L) - 120_000L) <= 200L);

        VideoWidget legacy = new VideoWidget();
        legacy.w = 64;
        legacy.h = 48;
        legacy.frameCount = 100;
        legacy.fps = 10;
        net.minecraft.nbt.CompoundTag old = new net.minecraft.nbt.CompoundTag();
        old.putInt("frames", 100);
        old.putDouble("fps", 10);
        old.putBoolean("paused", true);
        old.putInt("pausedFrame", 50);          // 旧存档只有帧号
        legacy.loadExtra(old);
        check("旧存档（只有 pausedFrame）自动换算成 pausedMs=5000",
                Math.abs(legacy.pausedMs - 5_000L) < 1L);

        // ---- 浮层 5 秒无操作隐藏 ----
        java.util.UUID id = java.util.UUID.randomUUID();
        long now = 1_700_000_000_000L;
        check("自动隐藏时长 = 5 秒", VideoControls.HIDE_MS == 5000L);
        check("没点过 = 不显示", !VideoControls.visible(id, now));
        VideoControls.reveal(id, now);
        check("点一下之后显示", VideoControls.visible(id, now));
        check("4.9 秒还在", VideoControls.visible(id, now + 4_900L));
        check("5 秒后自动隐藏", !VideoControls.visible(id, now + 5_000L));
        VideoControls.reveal(id, now);
        VideoControls.reveal(id, now + 4_000L);
        check("期间再点一次会续期", VideoControls.visible(id, now + 8_500L));
        VideoControls.hide(id);
        check("主动隐藏立刻生效", !VideoControls.visible(id, now));
        VideoControls.clear();
    }

    private static long clampSeek(VideoWidget w, double f) {
        w.seekToFraction(f, 1_700_000_000_000L);
        return w.positionMs(1_700_000_000_000L);
    }

    // ------------------------------------------------------------------ 渲染修复（hotfix-98）

    /**
     * 「水下什么都渲染不出来」「平面反面什么都看不到」两条根因的源码级回归。
     *
     * <p>MC 源码实证：{@code RenderType.text} 用的是默认 {@code CULL}（背面剔除），
     * 且 {@code rendertype_text} 的顶点着色器算 {@code fog_distance}、片元做线性雾混合
     * —— 所以水下浓雾会把整块屏幕染成水色，从反面看则一个四边形都提交不上去。</p>
     */
    private static void renderingFixes() {
        String collector = readFile("src/main/java/top/hmjmfabc/projector/client/render/QuadCollector.java");
        String world = readFile("src/main/java/top/hmjmfabc/projector/client/render/WorldPlaneRenderer.java");
        String client = readFile("src/main/java/top/hmjmfabc/projector/client/ProjectorClient.java");
        String input = readFile("src/main/java/top/hmjmfabc/projector/client/ClientInputHandler.java");
        String server = readFile("src/main/java/top/hmjmfabc/projector/network/ServerNetHandler.java");
        String editor = readFile("src/main/java/top/hmjmfabc/projector/client/gui/WidgetEditorScreen.java");
        String renderer = readFile("src/main/java/top/hmjmfabc/projector/client/render/WidgetRenderer.java");

        check("**不再处理反面**（背面朝外的四边形被 CULL 剔除 = 彻底不透视）",
                !collector.contains("backfaceView") && !collector.contains("seeThrough")
                        && !world.contains("backside") && !world.contains("BACKFACE_SEE_THROUGH"));
        check("渲染阶段在半透明块之前（水面/玻璃不再挡掉水下内容）",
                client.contains("AFTER_ENTITIES")
                        && !client.contains("Stage.AFTER_TRANSLUCENT_BLOCKS"));
        check("渲染时把雾推到极远（水下不再整片染成水色）",
                client.contains("setShaderFogStart(1.0e6f)") && client.contains("getShaderFogStart()"));
        check("雾设置用完必须还原", client.contains("finally"));
        check("世界里左键/右键都能点视频控件（触屏才点得到）",
                input.contains("event.isAttack()") && input.contains("handleVideoControl"));
        check("视频浮层由渲染器画出", renderer.contains("VideoOverlayRenderer.draw"));
        String overlay = readFile("src/main/java/top/hmjmfabc/projector/client/render/VideoOverlayRenderer.java");
        check("播放键**直接调用音乐的绘制函数**（逐像素一样，不再是自画的一套）",
                overlay.contains("MusicWidgetRenderer.ring(")
                        && overlay.contains("MusicWidgetRenderer.roundedRect(")
                        && overlay.contains("MusicWidgetRenderer.roundPolygon(")
                        && overlay.contains("radius * 0.09"));
        check("时长未知时不画已播放段（否则进度条乱跳）",
                overlay.contains("durationKnown()"));
        check("播放控制不做内容校验（内容保护只防改内容）",
                server.contains("playbackAction") && server.contains("!playbackAction"));
        check("视频 toggle/seek 由服务端权威处理并广播",
                server.contains("video.setPausedAt") && server.contains("video.seekToFraction")
                        && server.contains("broadcastPlane(level, plane)"));
        check("编辑器暂停按钮走单一入口 setPausedAt",
                editor.contains("vw.setPausedAt(") && !editor.contains("vw.paused = !vw.paused"));
        int videoBranch = editor.indexOf("instanceof VideoWidget");
        int playBtnAt = editor.indexOf("Button playBtn");
        check("编辑器把播放/循环放在最上面一行（不再被 bottomLimit 吃掉）",
                videoBranch > 0 && playBtnAt > videoBranch
                        && playBtnAt < editor.indexOf("sliderRow", videoBranch));

        // ---- 删除权限：默认人人可删，开了删除保护才要求 4 级 ----
        String perms = readFile("src/main/java/top/hmjmfabc/projector/server/PlanePermissions.java");
        String dialog = readFile("src/main/java/top/hmjmfabc/projector/client/gui/PlaneDialogScreen.java");
        String plane = readFile("src/main/java/top/hmjmfabc/projector/common/Plane.java");
        check("默认人人可删（只有开了删除保护才看等级）",
                perms.contains("!plane.deleteProtect") && perms.contains("DELETE_PROTECT_LEVEL = 4"));
        check("开关删除保护只认等级 4", perms.contains("canToggleDeleteProtection"));
        check("服务端有 protectDelete 操作", server.contains("case \"protectDelete\""));
        check("拒绝删除时给玩家明确提示（不许静默）",
                server.contains("projector.msg.delete_protected"));
        check("对话框有删除保护按钮", dialog.contains("deleteProtectButton") && dialog.contains("canDeleteNow()"));
        check("删除按钮不再要求「管理员或创建者」", !dialog.contains("deleteButton.active = allowed;"));
        check("删除保护字段进了 NBT", plane.contains("deleteProtect"));

        // 真跑一遍 NBT 往返：老存档没有这个键时必须默认「关」（否则旧平面会突然删不掉）
        try {
            top.hmjmfabc.projector.common.Plane p = new top.hmjmfabc.projector.common.Plane();
            p.id = java.util.UUID.randomUUID();       // save() 要写 id，测试里补上
            p.dimension = net.minecraft.resources.ResourceLocation.parse("minecraft:overworld");
            p.face = net.minecraft.core.Direction.NORTH;
            p.anchor = new net.minecraft.core.BlockPos(0, 64, 0);
            check("新平面默认没有删除保护", !p.deleteProtect);
            p.deleteProtect = true;
            top.hmjmfabc.projector.common.Plane back =
                    top.hmjmfabc.projector.common.Plane.load(p.save());
            check("删除保护能存进 NBT 再读回来", back.deleteProtect);
            net.minecraft.nbt.CompoundTag tag = p.save();
            tag.remove("deleteProtect");
            check("老存档缺这个键 = 默认关闭（人人可删）",
                    !top.hmjmfabc.projector.common.Plane.load(tag).deleteProtect);
        } catch (Throwable t) {
            failed.add("删除保护 NBT 往返异常：" + t);
        }
    }

    // ------------------------------------------------------------------ 按距离同步（hotfix-101）

    /**
     * 服务端「按距离订阅」的判据。
     *
     * <p>用户实测指出登录时把**所有**平面推给客户端是白烧流量。判据是
     * **点到包围盒的最近距离**（不是到锚点的距离）—— 用纯数字钉住符号与边界，
     * 因为「差一格」这种错误肉眼看不出来。</p>
     */
    private static void syncByDistance() {
        // 一堵 10x1x10 的墙（x∈[0,10], y∈[64,65], z∈[0,10]）
        double minX = 0, minY = 64, minZ = 0, maxX = 10, maxY = 65, maxZ = 10;

        check("点在盒内 = 距离 0",
                top.hmjmfabc.projector.common.PlaneDistance.distanceToBox(
                        5, 64.5, 5, minX, minY, minZ, maxX, maxY, maxZ) == 0.0);
        check("正前方 3 格 = 距离 3",
                Math.abs(top.hmjmfabc.projector.common.PlaneDistance.distanceToBox(
                        5, 64.5, 13, minX, minY, minZ, maxX, maxY, maxZ) - 3.0) < 1e-9);
        // 盒子 y 上界是 65、z 上界是 10：取 y=69（高 4）、z=13（远 3）⇒ 距离正好 5
        check("斜角是欧氏距离（3-4-5）",
                Math.abs(top.hmjmfabc.projector.common.PlaneDistance.distanceToBox(
                        5, 69, 13, minX, minY, minZ, maxX, maxY, maxZ) - 5.0) < 1e-9);
        check("**贴在大平面旁边但离锚点很远时仍算在范围内**"
                        + "（用包围盒而不是锚点，否则大平面会被误判成太远）",
                top.hmjmfabc.projector.common.PlaneDistance.withinRange(
                        9.5, 64.5, 9.5,
                        new net.minecraft.world.phys.AABB(minX, minY, minZ, maxX, maxY, maxZ),
                        512.0));
        check("恰好等于范围 = 算在范围内（闭区间）",
                top.hmjmfabc.projector.common.PlaneDistance.withinRange(
                        5, 64.5, 512, new net.minecraft.world.phys.AABB(minX, minY, minZ, maxX, maxY, maxZ),
                        502.0));
        check("超出范围 1 格 = 剔除",
                !top.hmjmfabc.projector.common.PlaneDistance.withinRange(
                        5, 64.5, 513, new net.minecraft.world.phys.AABB(minX, minY, minZ, maxX, maxY, maxZ),
                        502.0));
        check("范围 0 = 不限（退回旧行为）",
                top.hmjmfabc.projector.common.PlaneDistance.withinRange(
                        0, 0, 100000, new net.minecraft.world.phys.AABB(minX, minY, minZ, maxX, maxY, maxZ),
                        0));
        check("空参数不崩（返回 false）",
                !top.hmjmfabc.projector.common.PlaneDistance.withinRange(
                        0, 0, 0, null, 512.0)
                        && !top.hmjmfabc.projector.common.PlaneDistance.withinRange(
                        null, 0, 0, 0, 512.0));

        // ---- 源码级：服务端真的按订阅发，而不是全维度刷 ----
        String server = readFile("src/main/java/top/hmjmfabc/projector/network/ServerNetHandler.java");
        String events = readFile("src/main/java/top/hmjmfabc/projector/server/ServerEvents.java");
        String config = readFile("src/main/java/top/hmjmfabc/projector/ProjectorConfig.java");
        check("新增 syncDistance 配置项（默认 512，0 = 不限）",
                config.contains("planeSyncDistance")
                        && config.contains("defineInRange(\"syncDistance\", 512, 0, 8192)"));
        check("服务端有订阅表与每秒扫描",
                server.contains("tickSubscriptions") && server.contains("KNOWN_DIM"));
        check("编译广播不再「发给整个维度」",
                !server.contains("PacketDistributor.sendToPlayersInDimension"));
        check("出圈时给客户端发移除（复用删除那套语义，协议没变）",
                server.contains("tag.putUUID(\"plane\", id)"));
        check("同步与剔除用的是同一份距离判据（包围盒）",
                server.contains("PlaneDistance.withinRange"));
        check("每秒结算挂在服务端 tick 上", events.contains("ServerNetHandler.tickSubscriptions"));
        check("登出清订阅表", events.contains("forgetSubscriptions"));
    }

    private static String readFile(String path) {
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of(path));
        } catch (Exception e) {
            failed.add("读不到 " + path);
            return "";
        }
    }

    private static String readSelf() {
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of(
                    "src/main/java/top/hmjmfabc/projector/client/media/wm/WaterMediaBridge.java"));
        } catch (Exception e) {
            failed.add("读不到 WaterMediaBridge 源码");
            return "";
        }
    }

    // ------------------------------------------------------------------ 断言

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  \u2705 " + name);
        } else {
            failed.add(name);
        }
    }
}

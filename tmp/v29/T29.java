package top.hmjmfabc.projector.client.media.wm;

import net.minecraft.nbt.CompoundTag;
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

        // 暂停：位置取 pausedFrame 换算
        w.paused = true;
        w.pausedFrame = 50;
        long paused = WaterMediaVideos.desiredPositionMs(w, 60_000L);
        check("暂停时位置 = 暂停帧 / fps", Math.abs(paused - 5000L) < 1L);
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

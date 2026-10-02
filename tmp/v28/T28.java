package top.hmjmfabc.projector.client.music;

import net.minecraft.nbt.CompoundTag;
import top.hmjmfabc.projector.common.widget.MusicWidget;

import java.util.ArrayList;
import java.util.List;

/**
 * 【27.1.1 音乐控件】纯逻辑验证（T28）。
 *
 * <p>覆盖**能脱离游戏跑的部分**：控件几何与点击判定、播放状态机、时间与歌曲 ID 解析、
 * 波形数据、歌词（LRC）解析、NBT 存取往返。真实解码与 OpenAL 出声没法在无头环境验证，
 * 那部分由玩家实测（解码库/引擎通路已经用字节码核对过）。</p>
 *
 * <p>⚠ 断言里的「不变量」都要能**抓住真 bug**：例如播放状态机如果写成「每次都从头开始」，
 * 「暂停后位置必须留在原处」这一条就会红。</p>
 */
public class T28 {
    private static int passed;
    private static final List<String> failed = new ArrayList<>();

    public static void main(String[] args) {
        widgetGeometry();
        widgetPlayState();
        nbtRoundTrip();
        timeFormat();
        songIdParsing();
        searchUrlEscaping();
        envelope();
        amplitude();
        lyricParsing();
        trackParsing();
        distIsolation();
        localMusicSharing();
        titleTruncation();
        seekResync();
        durationProbe();
        audioBuffering();

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

    // ------------------------------------------------------------------ 几何

    private static void widgetGeometry() {
        MusicWidget w = new MusicWidget();
        w.x = 10;
        w.y = 20;
        w.w = 112;
        w.h = 20;
        double[] b = w.buttonBox();
        double[] wave = w.waveBox();
        double[] time = w.timeBox();

        check("播放键在控件内", b[0] >= 0 && b[1] >= 0 && b[2] <= w.w && b[3] <= w.h);
        check("播放键是正方形（±10%）", Math.abs((b[2] - b[0]) - (b[3] - b[1])) < (b[2] - b[0]) * 0.1);
        check("播放键在最左边（波形起点在它右边）", wave[0] > b[2]);
        check("波形在时间左边", wave[2] <= time[0] + 0.001);
        check("时间框贴右边", Math.abs(time[2] - (w.w - w.padding())) < 0.001);

        // 点击判定：锚点偏移 + 尺寸变化后仍然自洽
        double cx = w.x + (b[0] + b[2]) / 2;
        double cy = w.y + (b[1] + b[3]) / 2;
        check("点在播放键中心 = 命中", w.hitButton(cx, cy));
        check("点在控件右端 = 未命中播放键", !w.hitButton(w.x + w.w - 1, cy));
        check("点在控件外面 = 未命中", !w.hitButton(w.x - 30, cy));

        // 旋转 90° 后仍然跟着控件转（hitButton 必须走 toLocal）
        MusicWidget r = new MusicWidget();
        r.x = 0;
        r.y = 0;
        r.w = 112;
        r.h = 20;
        r.rot = 90;
        double[] rb = r.buttonBox();
        double rcx = (rb[0] + rb[2]) / 2;
        double rcy = (rb[1] + rb[3]) / 2;
        double[] rotated = top.hmjmfabc.projector.client.render.TextRenderer.rot(
                r.x, r.y, r.rot, rcx - r.x, rcy - r.y);
        check("旋转后按旋转后的坐标点仍命中", r.hitButton(r.x + rotated[0], r.y + rotated[1]));
        check("旋转后按未旋转坐标点不再命中", !r.hitButton(rcx, rcy));

        // 竖条数量：跟宽度走，且有上下限
        check("自动竖条数在 3~96 之间",
                w.effectiveBarCount() >= 3 && w.effectiveBarCount() <= 96);
        w.barCount = 1000;
        check("手填竖条数被夹到 256", w.effectiveBarCount() == 256);
        w.barCount = 0;
    }

    // ------------------------------------------------------------------ 播放状态机

    private static void widgetPlayState() {
        MusicWidget w = new MusicWidget();
        MusicTrack.applyTo(w, MusicTrack.netease(123456L, "测试歌曲", "测试歌手", 200_000L));
        check("换歌后回到未播放", !w.playing && w.positionMs == 0L);
        check("时长被记录下来", w.durationMs == 200_000L);
        check("有效时长 = 记录值", w.effectiveDurationMs() == 200_000L);

        long t0 = 1000L;
        w.toggle(t0);
        check("toggle 之后变成播放中", w.playing);
        check("刚开始位置是 0", w.positionAt(t0) == 0L);
        check("10 秒后位置 = 10000ms", w.positionAt(t0 + 200L) == 10_000L);
        check("进度 = 0.05", Math.abs(w.progressAt(t0 + 200L) - 0.05) < 1e-6);
        check("没放完", !w.finishedAt(t0 + 200L));

        // 暂停：位置必须**留在原处**，不能再往前走
        w.toggle(t0 + 200L);
        check("toggle 之后变成暂停", !w.playing);
        check("暂停位置 = 10000ms", w.positionMs == 10_000L);
        check("暂停后过很久位置也不动", w.positionAt(t0 + 2000L) == 10_000L);

        // 继续：从暂停处接着走
        w.toggle(t0 + 2000L);
        check("继续后从暂停处接着走", w.positionAt(t0 + 2100L) == 15_000L);

        // 放完之后再点：必须从头开始，而不是立刻又「结束」
        w.playing = true;
        w.startedGameTime = t0;
        w.positionMs = 0L;
        w.durationMs = 60_000L;
        check("播放中超出时长时 finishedAt 为真", w.finishedAt(t0 + 2000L));
        w.toggle(t0 + 2000L);
        check("放完再点 = 从头开始（playing 且位置 0）", w.playing && w.positionMs == 0L);

        // 位置永远被夹在 [0, 时长]
        check("位置不会超过时长", w.positionAt(t0 + 100_000L) <= w.effectiveDurationMs());
        check("位置不会是负数", w.positionAt(0L) >= 0L);

        // 没选歌时 toggle 不该崩
        MusicWidget empty = new MusicWidget();
        empty.toggle(0L);
        check("空控件 toggle 不崩且不进入播放", !empty.hasTrack());
    }

    // ------------------------------------------------------------------ NBT

    private static void nbtRoundTrip() {
        MusicWidget a = new MusicWidget();
        MusicTrack.applyTo(a, MusicTrack.netease(987654321L, "夜曲", "周杰伦", 227_000L));
        a.fontSize = 11.5;
        a.corner = 7;
        a.volume = 0.42;
        a.accentColor = 0xFF112233;
        a.waveColor = 0xFF778899;
        a.textColor = 0xFFAABBCC;
        a.barCount = 33;
        a.showLyric = false;
        a.x = 12.5;
        a.y = 3.25;
        a.w = 130;
        a.h = 22;
        a.playing = true;
        a.startedGameTime = 4242L;
        a.positionMs = 5000L;

        CompoundTag tag = a.save();
        MusicWidget b = (MusicWidget) top.hmjmfabc.projector.common.widget.Widgets.load(tag);

        check("NBT 往返：类型还是音乐控件", b != null && b.kind() == MusicWidget.KIND_MUSIC);
        check("NBT 往返：id 一致", b != null && b.id.equals(a.id));
        check("NBT 往返：歌名/歌手/时长", b != null
                && b.title.equals("夜曲") && b.artist.equals("周杰伦") && b.durationMs == 227_000L);
        check("NBT 往返：来源与键", b != null
                && "NETEASE".equals(b.sourceKind) && "987654321".equals(b.sourceKey));
        check("NBT 往返：播放状态与锚点", b != null
                && b.playing && b.startedGameTime == 4242L && b.positionMs == 5000L);
        check("NBT 往返：样式字段", b != null
                && Math.abs(b.fontSize - 11.5) < 1e-9 && Math.abs(b.corner - 7) < 1e-9
                && Math.abs(b.volume - 0.42) < 1e-9 && b.barCount == 33 && !b.showLyric
                && b.accentColor == 0xFF112233
                && b.waveColor == 0xFF778899 && b.textColor == 0xFFAABBCC);
        check("NBT 往返：几何字段", b != null && Math.abs(b.w - 130) < 1e-9
                && Math.abs(b.h - 22) < 1e-9 && Math.abs(b.x - 12.5) < 1e-9);
        check("NBT 往返：再生出来的歌与原歌等价", b != null
                && MusicTrack.of(b).playUrl().contains("id=987654321"));

        // 旧存档（没有音乐字段）也要能读
        CompoundTag legacy = new CompoundTag();
        legacy.putInt("kind", MusicWidget.KIND_MUSIC);
        MusicWidget fresh = (MusicWidget) top.hmjmfabc.projector.common.widget.Widgets.load(legacy);
        check("缺字段的旧数据能读成默认值", fresh != null
                && fresh.fontSize > 0 && !fresh.playing && fresh.volume > 0);
    }

    // ------------------------------------------------------------------ 时间

    private static void timeFormat() {
        check("0ms → 0:00", "0:00".equals(fmt(0)));
        check("61000ms → 1:01", "1:01".equals(fmt(61_000L)));
        check("3599000ms → 59:59", "59:59".equals(fmt(3_599_000L)));
        check("3661000ms → 1:01:01", "1:01:01".equals(fmt(3_661_000L)));
        check("负数按 0 处理", "0:00".equals(fmt(-5L)));
    }

    private static String fmt(long ms) {
        return top.hmjmfabc.projector.client.render.MusicWidgetRenderer.formatTime(ms);
    }

    // ------------------------------------------------------------------ 歌曲 ID

    private static void songIdParsing() {
        check("纯数字 ID", NetEaseApi.parseSongId("2058263838") == 2058263838L);
        check("带空格的 ID", NetEaseApi.parseSongId("  123  ") == 123L);
        check("分享链接",
                NetEaseApi.parseSongId("https://music.163.com/song?id=456&userid=1") == 456L);
        check("外链地址",
                NetEaseApi.parseSongId("https://music.163.com/song/media/outer/url?id=789.mp3") == 789L);
        check("不是 ID 时返回 -1", NetEaseApi.parseSongId("周杰伦 夜曲") == -1L);
        check("空字符串返回 -1", NetEaseApi.parseSongId("") == -1L);
        check("null 返回 -1", NetEaseApi.parseSongId(null) == -1L);
    }

    private static void searchUrlEscaping() {
        String url = NetEaseApi.searchUrl("a&type=1000", 10);
        check("关键字里的 & 必须被转义（不能污染查询参数）",
                url.contains("s=a%26type%3D1000") && url.endsWith("&type=1&limit=10"));
        check("中文关键字被编码", NetEaseApi.searchUrl("夜曲", 5).contains("%E5%A4%9C%E6%9B%B2"));
        check("limit 被夹到 1~30",
                NetEaseApi.searchUrl("x", 999).endsWith("limit=30")
                && NetEaseApi.searchUrl("x", 0).endsWith("limit=1"));
        check("详情/歌词 URL 里带着歌曲 ID",
                NetEaseApi.detailUrl(42L).contains("id=42") && NetEaseApi.lyricUrl(42L).contains("id=42"));
    }

    // ------------------------------------------------------------------ 波形

    private static void envelope() {
        MusicEnvelope env = MusicEnvelope.forTrack("测试歌曲", 60_000L);
        check("波形格数 = 时长/250ms", env.bucketCount() == 240);
        check("初始没有真实数据", !env.isReal(0L) && env.filledRatio() == 0f);
        check("未解码时也有占位振幅（不会是 0）", env.valueAt(0L) > 0);

        env.put(1000L, 80);
        check("写入后 isReal 为真", env.isReal(1000L));
        check("取得到刚写进去的振幅", env.valueAt(1000L) == 80);
        check("读数被夹在 0~100", valueAfterPut(env, 1500L, 500) == 100);

        env.resetForTest();
        check("清空后回到占位状态", !env.isReal(1000L) && env.filledRatio() == 0f);

        // 越界时间不能崩
        env.put(-100L, 50);
        env.put(10_000_000L, 50);
        check("越界写入不崩、也不越界读", env.valueAt(10_000_000L) >= 0);

        MusicEnvelope a = MusicEnvelope.forTrack("同一首歌", 100_000L);
        MusicEnvelope b = MusicEnvelope.forTrack("同一首歌", 100_000L);
        check("同一首歌的占位花纹是确定的", a.valueAt(5000L) == b.valueAt(5000L));
        MusicEnvelope c = MusicEnvelope.forTrack("另一首歌", 100_000L);
        boolean differs = false;
        for (int i = 0; i < 40; i++) {
            if (a.valueAt(i * 250L) != c.valueAt(i * 250L)) {
                differs = true;
                break;
            }
        }
        check("不同歌的占位花纹不同（换歌看得出来）", differs);
    }

    private static int valueAfterPut(MusicEnvelope env, long at, int amplitude) {
        env.put(at, amplitude);
        return env.valueAt(at);
    }

    private static void amplitude() {
        // 静音
        byte[] silence = new byte[400];
        check("静音振幅 = 0", MusicAudioStream.amplitude(silence, silence.length, 4) == 0);
        // 满幅方波
        byte[] loud = new byte[400];
        for (int i = 0; i + 1 < loud.length; i += 2) {
            loud[i] = (byte) 0x00;
            loud[i + 1] = (byte) 0x7F;   // +32767
        }
        check("满幅振幅接近 100", MusicAudioStream.amplitude(loud, loud.length, 4) >= 99);
        // 半幅
        byte[] half = new byte[400];
        for (int i = 0; i + 1 < half.length; i += 2) {
            half[i] = (byte) 0x00;
            half[i + 1] = (byte) 0x40;   // 16384
        }
        int halfAmp = MusicAudioStream.amplitude(half, half.length, 4);
        check("半幅振幅在 70~72（开方压缩后）", halfAmp >= 70 && halfAmp <= 72);
        check("振幅与字节数无关（只跟峰值有关）",
                MusicAudioStream.amplitude(half, 40, 4) == halfAmp);
    }

    // ------------------------------------------------------------------ 歌词

    private static void lyricParsing() {
        String lrc = "[00:01.00]第一句\n[00:03.50]第二句\n[00:05.00][00:09.00]重复句\n[00:07]无小数\n[ti:标题]\n";
        LyricRecord r = LyricRecord.parse(lrc, null, "测试");
        check("歌词解析成功", r != null && !r.isEmpty());
        check("20 tick = 1 秒", r != null && "第一句".equals(r.lineAt(20)));
        check("3.5 秒取到第二句（70 tick）", r != null && "第二句".equals(r.lineAt(70)));
        check("一行多个时间戳都被登记（参考实现的缺口）",
                r != null && "重复句".equals(r.lineAt(100)) && "重复句".equals(r.lineAt(180)));
        check("没有小数部分的 [00:07] 也能解析", r != null && "无小数".equals(r.lineAt(140)));
        check("元数据行不产生歌词", r != null && r.lineCount() == 5);

        // 非破坏性：来回读不能把前面的行「吃掉」
        if (r != null) {
            r.lineAt(400);
            r.lineAt(0);
            check("查询不破坏数据（可以回退、可以重播）", "第一句".equals(r.lineAt(20)));
        }
        check("第一句之前给第一句而不是空白",
                r != null && "第一句".equals(r.lineAt(0)));

        check("空歌词返回 null", LyricRecord.parse("", null, null) == null
                && LyricRecord.parse(null, null, null) == null);
        check("空对象不是「有歌词」", LyricRecord.empty().isEmpty());

        LyricRecord translated = LyricRecord.parse("[00:01.00]原文\n", "[00:01.00]译文\n", "x");
        check("译文能取到", translated != null && "译文".equals(translated.translationAt(20)));
    }

    // ------------------------------------------------------------------ 歌曲来源

    private static void trackParsing() {
        MusicTrack netease = MusicTrack.netease(555L, "歌", "手", 1000L);
        check("网易云直链是官方外链", netease.playUrl().startsWith("https://music.163.com/song/media/outer/url?id=555"));
        check("本地来源的 playUrl 就是路径本身",
                "a.mp3".equals(MusicTrack.local("a.mp3", "a", 0L).playUrl()));
        check("直链来源的 playUrl 就是 URL",
                "http://x/y.mp3".equals(MusicTrack.direct("http://x/y.mp3", "y", 0L).playUrl()));
        check("显示名带歌手", netease.displayName().contains("歌") && netease.displayName().contains("手"));
        check("能识别音频扩展名",
                MusicTrack.isAudioFile("a.mp3") && MusicTrack.isAudioFile("b.FLAC")
                && MusicTrack.isAudioFile("c.wav") && !MusicTrack.isAudioFile("d.mp4")
                && !MusicTrack.isAudioFile("e.png"));
        // 【翻车一次换来的断言，Build 112 起口径反转】AAC/M4A 现在**在**可播放列表里：
        // 当年禁止它是因为 javasound-aac 与 JCodec 自带的 net.sourceforge.jaad.* 同名包
        // （两个 jar 一起嵌会让 ModLauncher 启动阶段直接崩：
        //  ResolutionException: Modules javasound.aac and jcodec export package … to module mp3spi）。
        // 现在 JCodec 已整个移除，冲突消失，于是 AAC/M4A 加回来 —— **JCodec 变成禁止项**。
        check("AAC/M4A 在可播放列表里（Build 112 起恢复）",
                MusicTrack.isAudioFile("x.m4a") && MusicTrack.isAudioFile("y.aac")
                && MusicTrack.isAudioFile("z.M4A"));
        sourceShouldNotContain("build.gradle", "jcodec",
                "build.gradle 的依赖里不许再出现 JCodec（已由 Build 112 移除）");
        check("找不到的本地文件返回 null", MusicTrack.resolveLocal("绝对不存在的文件.mp3") == null);
        check("空的来源算空", MusicTrack.local("", "", 0L).isEmpty());
    }

    /** 源码级断言：某个文件里不许出现某个字符串（用于钉住「翻过车的地方」）。 */
    private static void sourceShouldNotContain(String path, String needle, String name) {
        try {
            String text = java.nio.file.Files.readString(java.nio.file.Path.of(path));
            boolean hit = false;
            for (String line : text.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("#")) {
                    continue;   // 注释里为了解释原因会提到它，不算违规
                }
                if (trimmed.contains(needle)) {
                    hit = true;
                    break;
                }
            }
            check(name, !hit);
        } catch (Exception e) {
            failed.add(name + "（读不到 " + path + "）");
        }
    }

    /** 播放中调进度必须能被发现（否则只有进度条动、声音不动）。 */
    private static void seekResync() {
        check("同一位置不算跳变",
                !top.hmjmfabc.projector.client.music.MusicPlayer.shouldResync(10_000L, 10_000L));
        check("几十毫秒漂移不算跳变",
                !top.hmjmfabc.projector.client.music.MusicPlayer.shouldResync(10_000L, 10_400L));
        check("差 2 秒算跳变", top.hmjmfabc.projector.client.music.MusicPlayer.shouldResync(12_000L, 10_000L));
        check("往前跳也算（差值取绝对值）",
                top.hmjmfabc.projector.client.music.MusicPlayer.shouldResync(5_000L, 20_000L));
        check("阈值在 0.5~3 秒之间（太灵敏会自己打断自己）",
                top.hmjmfabc.projector.client.music.MusicPlayer.SEEK_TOLERANCE_MS >= 500
                && top.hmjmfabc.projector.client.music.MusicPlayer.SEEK_TOLERANCE_MS <= 3000);
    }

    /** 时长探测三级兜底（本轮「2:40 显示成 3:00」的回归）。 */
    private static void durationProbe() {
        // ③ 码率 × 字节数：128 kbps 的 2,560,000 字节 ≈ 160 秒
        javax.sound.sampled.AudioFormat fmt = new javax.sound.sampled.AudioFormat(
                javax.sound.sampled.AudioFormat.Encoding.PCM_SIGNED, 44100f, 16, 2, 4, 44100f, false,
                java.util.Map.<String, Object>of("mp3.bitrate.nominal.bps", 128000));
        long byBitrate = top.hmjmfabc.projector.client.music.AudioDecoder.probeDurationMs(
                new javax.sound.sampled.AudioInputStream(new java.io.ByteArrayInputStream(new byte[0]), fmt, 0L),
                2560000L);
        check("按码率×字节数算出 160 秒（2:40）", byBitrate == 160_000L);

        // ① 格式属性优先（mp3spi 的 duration 是微秒）
        javax.sound.sampled.AudioFormat withDuration = new javax.sound.sampled.AudioFormat(
                javax.sound.sampled.AudioFormat.Encoding.PCM_SIGNED, 44100f, 16, 2, 4, 44100f, false,
                java.util.Map.<String, Object>of("duration", 160_500_000L));
        check("格式属性 duration（微秒）优先",
                top.hmjmfabc.projector.client.music.AudioDecoder.probeDurationMs(
                        new javax.sound.sampled.AudioInputStream(
                                new java.io.ByteArrayInputStream(new byte[0]), withDuration, 0L), 0L)
                        == 160_500L);

        // ② 帧数
        javax.sound.sampled.AudioFormat plain = new javax.sound.sampled.AudioFormat(
                javax.sound.sampled.AudioFormat.Encoding.PCM_SIGNED, 44100f, 16, 2, 4, 44100f, false);
        check("有帧数时按帧数算（44100 帧 = 1000ms）",
                top.hmjmfabc.projector.client.music.AudioDecoder.probeDurationMs(
                        new javax.sound.sampled.AudioInputStream(
                                new java.io.ByteArrayInputStream(new byte[0]), plain, 44100L), 0L)
                        == 1000L);
        check("什么都拿不到时返回 0（由解码后校正兜底）",
                top.hmjmfabc.projector.client.music.AudioDecoder.probeDurationMs(
                        new javax.sound.sampled.AudioInputStream(
                                new java.io.ByteArrayInputStream(new byte[0]), plain, 0L), 0L) == 0L);
    }

    // ------------------------------------------------------------------ 单行省略号

    /** 标题「一行放不下就省略号」——用假度量函数验证纯逻辑（10 单位/字）。 */
    private static void titleTruncation() {
        java.util.function.ToDoubleFunction<String> m = t -> t.length() * 10.0;
        check("放得下就不动它",
                "短标题".equals(top.hmjmfabc.projector.client.render.MusicWidgetRenderer
                        .truncate("短标题", 100, m)));
        String cut = top.hmjmfabc.projector.client.render.MusicWidgetRenderer
                .truncate("这是一个很长很长的标题", 50, m);
        check("放不下 ⇒ 省略号截断", cut.endsWith("\u2026") && cut.length() <= 5);
        check("截断后必须真的放得下", m.applyAsDouble(cut) <= 50);
        check("窄到极限只剩省略号",
                "\u2026".equals(top.hmjmfabc.projector.client.render.MusicWidgetRenderer
                        .truncate("很长很长的标题", 5, m)));
        check("空串/空文字不崩",
                "".equals(top.hmjmfabc.projector.client.render.MusicWidgetRenderer
                        .truncate("", 50, m))
                && "".equals(top.hmjmfabc.projector.client.render.MusicWidgetRenderer
                        .truncate(null, 50, m)));

        MusicWidget w = new MusicWidget();
        check("默认没有黑底（background=0 不填充）", w.background == 0);
        check("边框与图形都用白色（textColor）", w.textColor == 0xFFFFFFFF);

        // 波形条上「点哪跳哪」：几何与绘制同源
        MusicWidget seek = new MusicWidget();
        seek.x = 0;
        seek.y = 0;
        seek.w = 112;
        seek.h = 20;
        double[] wave = seek.waveBox();
        double midY = seek.y + seek.h / 2;
        check("点波形最左 = 0%", Math.abs(seek.seekFractionAt(seek.x + wave[0], midY)) < 1e-6);
        check("点波形最右 = 100%", Math.abs(seek.seekFractionAt(seek.x + wave[2], midY) - 1.0) < 1e-6);
        check("点正中间 ≈ 50%",
                Math.abs(seek.seekFractionAt(seek.x + (wave[0] + wave[2]) / 2, midY) - 0.5) < 0.02);
        check("点控件外面不算（交给编辑器那条路）",
                seek.seekFractionAt(seek.x - 20, midY) < 0 && seek.seekFractionAt(seek.x + wave[0], seek.y - 30) < 0);
        seek.seekToFraction(0.5, 100L);
        check("跳到 50% 位置正确", Math.abs(seek.positionMs - seek.effectiveDurationMs() / 2) <= 1);
        seek.playing = true;
        seek.startedGameTime = 500L;
        seek.seekToFraction(0.25, 100L);
        check("播放中跳进度会重锚时间点", seek.startedGameTime == 100L);
        seek.seekToFraction(5.0, 100L);
        check("越界比例被夹到 100%", seek.positionMs == seek.effectiveDurationMs());
        check("默认字号偏小（≤8）", w.fontSize <= 8.0);
    }

    // ------------------------------------------------------------------ 音频缓冲

    /**
     * 【27.1.1 实测翻车点】预填缓冲必须够填满 MC 的 4 个 AL 槽位（每个 1 秒）。
     *
     * <p>只预填 1 秒时，MC 的 {@code pumpBuffers(4)} 里后 3 次拿到 null ⇒ 只有 1 个槽位有数据
     * ⇒ 1 秒后源没缓冲 ⇒ AL_STOPPED ⇒ 引擎回收声道 ⇒ 表现为「播一秒、循环几个音」。
     * 这条断言把「预填 ≥ 4 秒」钉住（源码级，因为真实解码没法在无头环境跑）。</p>
     */
    private static void audioBuffering() {
        try {
            String src = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "src/main/java/top/hmjmfabc/projector/client/music/MusicAudioStream.java"));
            int chunk = intConst(src, "CHUNK_MS");
            int prefill = intConst(src, "PREFILL_CHUNKS");
            int queued = intConst(src, "MAX_QUEUED_CHUNKS");
            System.out.println("      分片=" + chunk + "ms 预填=" + prefill + "片 队列上限=" + queued + "片");
            check("预填总时长 ≥ 4 秒（MC 的 4 个 AL 槽位）", chunk * prefill >= 4000);
            check("分片粒度 = MC 的 1 秒缓冲", chunk == 1000);
            check("队列上限 ≥ 预填（否则自己把自己卡住）", queued >= prefill);
        } catch (Exception e) {
            failed.add("读不到 MusicAudioStream 源码：" + e);
        }
    }

    /** 从源码里抠出某个 int 常量的值（形如 {@code NAME = 1234;}）。 */
    private static int intConst(String source, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile(name + "\\s*=\\s*(\\d+)").matcher(source);
        if (!m.find()) {
            throw new IllegalStateException("找不到常量 " + name);
        }
        return Integer.parseInt(m.group(1));
    }

    // ------------------------------------------------------------------ dist 隔离

    /**
     * 【dist 隔离】{@code common/} 下的类会被专用服务端加载，里面**绝不能出现**
     * 对 {@code client} 包的引用（哪怕只是方法签名里的类型）——
     * 一旦出现，专用服务端解析到它就 FATAL（AGENTS §5.5 第 67 条）。
     *
     * <p>这条是「源码级断言」：它抓的是**结构性**问题，跑一次就能防住以后有人
     * 图省事在控件类里直接引用播放器。同时验证「音乐控件确实只用基本类型存来源」。</p>
     */
    private static void distIsolation() {
        java.nio.file.Path common = java.nio.file.Path.of(
                "src/main/java/top/hmjmfabc/projector/common");
        java.util.List<String> offenders = new java.util.ArrayList<>();
        int scanned = 0;
        try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.walk(common)) {
            for (java.nio.file.Path f : stream.filter(p -> p.toString().endsWith(".java")).toList()) {
                scanned++;
                String text = java.nio.file.Files.readString(f);
                for (String line : text.split("\n")) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("*") || trimmed.startsWith("//")) {
                        continue;   // 注释里提到不算
                    }
                    if (trimmed.contains("projector.client") || trimmed.contains("client.music")) {
                        offenders.add(f.getFileName() + ": " + trimmed);
                    }
                }
            }
        } catch (Exception e) {
            failed.add("读不到 common/ 源码：" + e);
            return;
        }
        check("common/ 下的类一个都不许引用 client 包（扫了 " + scanned + " 个文件）",
                offenders.isEmpty());
        if (!offenders.isEmpty()) {
            for (String o : offenders) {
                System.out.println("      " + o);
            }
        }

        MusicWidget w = new MusicWidget();
        check("音乐控件用字符串存来源类型（不引用客户端枚举）",
                "LOCAL".equals(w.sourceKind));
        MusicTrack.applyTo(w, MusicTrack.netease(1L, "t", "a", 1000L));
        check("client 侧入口能写回控件", "NETEASE".equals(w.sourceKind) && "1".equals(w.sourceKey));
        check("client 侧入口能读回歌曲", MusicTrack.of(w).kind() == MusicTrack.Kind.NETEASE);
    }

    // ------------------------------------------------------------------ 本地音乐共享（27.2）

    /**
     * 【27.2】玩家报的 Bug：**服务器上其他玩家听不到玩家 A 放置的本地音乐**。
     *
     * <p>根因：本地音乐以前只在控件里存一个<b>文件路径</b>，而那份文件只在 A 的机器上。
     * 修法：选歌时把音频按 SHA-1 发布到服务端，控件存哈希；其他客户端缺这份就下载。
     * 这一组把「两条半」钉住 —— 配额判据、哈希识别、发布与下载的接线。</p>
     */
    private static void localMusicSharing() {
        // ① 音频按视频那一档配额（否则一首 6 MB 的 MP3 会被 4 MB 的图片档挡在本地）
        check("音频扩展名判定：mp3/flac/wav/m4a/aac（大小写都认）",
                top.hmjmfabc.projector.server.Sanitize.isAudioName("a.mp3")
                        && top.hmjmfabc.projector.server.Sanitize.isAudioName("A.FLAC")
                        && top.hmjmfabc.projector.server.Sanitize.isAudioName("x.wav")
                        && top.hmjmfabc.projector.server.Sanitize.isAudioName("x.m4a")
                        && top.hmjmfabc.projector.server.Sanitize.isAudioName("x.aac"));
        check("音频扩展名判定：视频/图片/无扩展名/空 一律不是",
                !top.hmjmfabc.projector.server.Sanitize.isAudioName("a.mp4")
                        && !top.hmjmfabc.projector.server.Sanitize.isAudioName("a.png")
                        && !top.hmjmfabc.projector.server.Sanitize.isAudioName("song.")
                        && !top.hmjmfabc.projector.server.Sanitize.isAudioName("song")
                        && !top.hmjmfabc.projector.server.Sanitize.isAudioName("")
                        && !top.hmjmfabc.projector.server.Sanitize.isAudioName(null));
        check("配额档位：音频走视频档（服务端收首片时用同一条判据）",
                top.hmjmfabc.projector.network.ServerNetHandler.videoQuota(false, "song.mp3")
                        && top.hmjmfabc.projector.network.ServerNetHandler.videoQuota(true, "v.mp4")
                        && !top.hmjmfabc.projector.network.ServerNetHandler.videoQuota(false, "p.png"));

        // ② 真正把「6 MB 的音频」送进上传前检查：按图片档必被拒，按视频档放行
        java.nio.file.Path big = null;
        try {
            java.nio.file.Path dir = java.nio.file.Path.of("tmp/v28");
            java.nio.file.Files.createDirectories(dir);
            big = dir.resolve("t28-audio-quota.mp3");
            byte[] blob = new byte[6 * 1024 * 1024];
            java.util.Arrays.fill(blob, (byte) 7);
            java.nio.file.Files.write(big, blob);
            var audio = top.hmjmfabc.projector.client.media.MediaUploader
                    .prepare(big, false, false);
            java.nio.file.Path asImage = dir.resolve("t28-image-quota.png");
            java.nio.file.Files.write(asImage, blob);
            var image = top.hmjmfabc.projector.client.media.MediaUploader
                    .prepare(asImage, false, false);
            check("同一份 6 MB 数据：音频过（视频档），换成 .png 就被挡（图片档）",
                    audio.ok() && !image.ok());
            java.nio.file.Files.deleteIfExists(asImage);
        } catch (Throwable t) {
            check("上传前检查的两档配额（音频 6 MB 过 / 图片 6 MB 拒）：" + t, false);
        } finally {
            if (big != null) {
                try {
                    java.nio.file.Files.deleteIfExists(big);
                } catch (Throwable ignored) {
                    // 删不掉不影响断言
                }
            }
        }

        // ③ 哈希 key 与路径 key 必须分得清（旧存档是路径，27.2 起是哈希）
        String hash = "0123456789abcdef0123456789abcdef01234567";
        check("哈希 key 认得出来（40 位十六进制）", MusicTrack.isHashKey(hash));
        check("路径 key 不当成哈希",
                !MusicTrack.isHashKey("/storage/emulated/0/x.mp3")
                        && !MusicTrack.isHashKey("songs/x.flac")
                        && !MusicTrack.isHashKey("")
                        && !MusicTrack.isHashKey(null)
                        && !MusicTrack.isHashKey(hash.substring(0, 39)));
        check("短哈希只取前 8 位（日志用）",
                MusicTrack.shortHash(hash).equals("01234567")
                        && MusicTrack.shortHash("abc").equals("abc"));

        // ④ 本机没有这份音频时：**必须抛一条写明原因的 IOException**，不能静默、不能 NPE
        MusicTrack missing = MusicTrack.local(hash, "t", 0L);
        String message = "";
        boolean threw = false;
        try (var in = missing.openStream()) {
            threw = false;
        } catch (java.io.IOException e) {
            threw = true;
            message = String.valueOf(e.getMessage());
        } catch (Throwable t) {
            message = "非 IOException：" + t;
        }
        check("哈希音频不在本机时抛 IOException 且写明「还没下载到本机」",
                threw && message.contains("\u8fd8\u6ca1\u4e0b\u8f7d\u5230\u672c\u673a"));
        check("诊断文本里带短哈希（一眼看出是哪一份）", message.contains("01234567"));
        check("来源描述也区分哈希与路径",
                new MusicTrack(MusicTrack.Kind.LOCAL, hash, "t", "", 0)
                        .describeSource().contains("\u670d\u52a1\u7aef\u97f3\u9891"));

        // ⑤ 接线：选歌要发布、听歌要下载（源码级，防「只改了一半」）
        String picker = read("src/main/java/top/hmjmfabc/projector/client/gui/MusicPickerScreen.java");
        String share = read("src/main/java/top/hmjmfabc/projector/client/music/MusicShare.java");
        String manager = read("src/main/java/top/hmjmfabc/projector/client/music/MusicManager.java");
        String server = read("src/main/java/top/hmjmfabc/projector/network/ServerNetHandler.java");
        check("选歌界面把本地音频发布到服务端（MusicShare.publish）",
                picker.contains("MusicShare.publish(file") && picker.contains("isHashKey"));
        check("发布走的是图片/视频同一条上传管道（MediaUploader.upload，非视频语义）",
                share.contains("MediaUploader.upload(file, false"));
        check("发布前先把自己那一份登记进索引（否则连自己也放不了）",
                share.contains("LocalMedia.registerCache(hash, file)"));
        check("发布失败必须留痕（WARN 明说「其他玩家听不到这首」）",
                share.contains("\u5176\u4ed6\u73a9\u5bb6\u542c\u4e0d\u5230\u8fd9\u9996"));
        check("听歌侧：本机没有就请求下载，且**有退避**（20 Hz 的 tick 不能每 tick 发一次）",
                manager.contains("ensureAudioReady(music)")
                        && manager.contains("MediaCache.prefetch(hash, false)")
                        && manager.contains("AUDIO_RETRY_MS"));
        check("听歌侧不再「先开流再失败」：起播前先问一句能不能放",
                manager.contains("ensureAudioReady(music) && at != null"));
        check("服务端按「视频档」给音频配额（否则 6 MB 的歌传不上去）",
                server.contains("videoQuota(payload.video(), payload.name())"));
    }

    private static String read(String path) {
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of(path));
        } catch (Throwable t) {
            failed.add("读不到 " + path + "：" + t);
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

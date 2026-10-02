package top.hmjmfabc.projector.common.widget;

import net.minecraft.nbt.CompoundTag;

import java.util.List;

/**
 * 音乐控件（27.1.1 新增，第 10 种控件类型）。
 *
 * <p>外观：圆角矩形边框，左边是播放/暂停键，中间是「语音条」式的长条波形 + 文字
 * （歌名或当前歌词），最右边是时间（当前/总长）。</p>
 *
 * <p>播放状态是**服务端权威**的：{@link #playing} + {@link #startedGameTime}
 * 一起决定「现在放到第几毫秒」，所有客户端各算各的、结果一致（和视频控件的
 * 时间锚点同一个思路）。</p>
 *
 * <p>⚠ 几何只有一个来源：{@link #buttonBox()}（未旋转的局部坐标）。
 * 渲染与世界里点击都用它，免得出现「画在这里、点在那里」的两套几何。</p>
 */
public class MusicWidget extends Widget {
    /** 时间文字预留宽度（画布单位）：按「00:00/00:00」10 个字符估。 */
    private static final double TIME_CHARS = 10.0;

    /** 歌名/歌词字体（默认内置的 Minecraft 字体，中文也能显示）。 */
    public String fontId = Fonts.MINECRAFT_AE;
    /** 标题/时间字号（画布单位）。默认偏小：一行放得下更多字。 */
    public double fontSize = 7.5;

    /**
     * 来源类型（{@code LOCAL} / {@code NETEASE} / {@code URL}）。
     *
     * <p>刻意存**字符串**而不是枚举：本类在 {@code common/} 里，会被专用服务端加载，
     * 而解码/播放相关的类型都在 {@code client/} 包 —— 一旦在这里引用它们，
     * 专用服务端就有可能在解析本类时碰到客户端类，直接 FATAL（见 AGENTS §5.5 第 67 条）。
     * 所以这里**只用基本类型**，与播放有关的对象由客户端侧
     * ({@code MusicTrack.of/applyTo}) 负责转换。</p>
     */
    public String sourceKind = "LOCAL";
    public String sourceKey = "";
    public String title = "";
    public String artist = "";
    /** 时长（毫秒，0 = 未知）。 */
    public long durationMs;

    /** 是否正在播放（服务端权威）。 */
    public boolean playing;
    /** 暂停时停在的位置（毫秒）。 */
    public long positionMs;
    /** 开始播放那一刻的游戏刻（配合 playing 算当前位置）。 */
    public long startedGameTime;

    /** 音量倍率（0~1），再乘游戏「唱片机」音量的那一档。 */
    public double volume = 0.8;
    /** 圆角半径（画布单位）。 */
    public double corner = 5;
    /** 进度 / 播放键颜色。 */
    public int accentColor = 0xFF4FC3F7;
    /** 文字颜色。 */
    public int textColor = 0xFFFFFFFF;
    /**
     * 【27.2-pre-136】边框颜色（用户要求「音乐控件支持边框调色」）。
     *
     * <p>以前边框直接借用 {@link #textColor}，所以「文字改红、边框跟着变红」。
     * 现在边框有独立的颜色，默认白色（与播放键圆环一致）。</p>
     */
    public int borderColor = 0xFFFFFFFF;
    /** 波形未播放部分的颜色。 */
    public int waveColor = 0xFF5A6472;
    /** 是否在有歌词时显示歌词（否则一直显示歌名）。 */
    public boolean showLyric = true;
    /** 竖线条数（0 = 按宽度自动）。 */
    public int barCount = 0;

    @Override
    public int kind() {
        return KIND_MUSIC;
    }

    @Override
    public String label() {
        String t = title == null || title.isBlank() ? sourceKey : title;
        return (t == null || t.isBlank() ? "（未选择音乐）" : t);
    }

    @Override
    public List<String> fontIds() {
        return List.of(fontId == null ? "" : fontId);
    }

    @Override
    protected void saveExtra(CompoundTag t) {
        t.putString("font", fontId == null ? "" : fontId);
        t.putDouble("fs", fontSize);
        t.putString("srcKind", sourceKind == null ? "LOCAL" : sourceKind);
        t.putString("srcKey", sourceKey == null ? "" : sourceKey);
        t.putString("title", title == null ? "" : title);
        t.putString("artist", artist == null ? "" : artist);
        t.putLong("dur", durationMs);
        t.putBoolean("playing", playing);
        t.putLong("pos", positionMs);
        t.putLong("start", startedGameTime);
        t.putDouble("vol", volume);
        t.putDouble("corner", corner);
        t.putInt("accent", accentColor);
        t.putInt("textColor", textColor);
        t.putInt("borderColor", borderColor);
        t.putInt("wave", waveColor);
        t.putBoolean("lyric", showLyric);
        t.putInt("bars", barCount);
    }

    @Override
    public void loadExtra(CompoundTag t) {
        fontId = t.contains("font") ? t.getString("font") : Fonts.MINECRAFT_AE;
        fontSize = t.contains("fs") ? t.getDouble("fs") : 7.5;
        sourceKind = t.contains("srcKind") ? t.getString("srcKind") : "LOCAL";
        sourceKey = t.getString("srcKey");
        title = t.getString("title");
        artist = t.getString("artist");
        durationMs = t.getLong("dur");
        playing = t.getBoolean("playing");
        positionMs = t.getLong("pos");
        startedGameTime = t.getLong("start");
        volume = t.contains("vol") ? t.getDouble("vol") : 0.8;
        corner = t.contains("corner") ? t.getDouble("corner") : 5;
        accentColor = t.contains("accent") ? t.getInt("accent") : 0xFF4FC3F7;
        textColor = t.contains("textColor") ? t.getInt("textColor") : 0xFFFFFFFF;
        // 旧存档没有边框色 -> 跟随文字色，观感与以前完全一致
        borderColor = t.contains("borderColor") ? t.getInt("borderColor") : textColor;
        waveColor = t.contains("wave") ? t.getInt("wave") : 0xFF5A6472;
        showLyric = !t.contains("lyric") || t.getBoolean("lyric");
        barCount = t.getInt("bars");
    }

    // ------------------------------------------------------------------ 歌曲

    /**
     * 换一首歌（自动回到「未播放、位置 0」）。
     *
     * <p>只收基本类型，调用方（客户端）负责把 {@code MusicTrack} 拆开传进来，
     * 见 {@code MusicTrack.applyTo(widget)}。</p>
     */
    public void setTrackInfo(String kind, String key, String songTitle, String songArtist,
                             long duration) {
        sourceKind = kind == null || kind.isBlank() ? "LOCAL" : kind;
        sourceKey = key == null ? "" : key;
        title = songTitle == null ? "" : songTitle;
        artist = songArtist == null ? "" : songArtist;
        durationMs = Math.max(0L, duration);
        playing = false;
        positionMs = 0L;
        startedGameTime = 0L;
    }

    /** 波形占位花纹用哪个字符串当种子（换歌就换花纹）。 */
    public String displayKey() {
        String t = title == null || title.isBlank() ? sourceKey : title;
        return (t == null ? "" : t) + (artist == null || artist.isBlank() ? "" : " - " + artist);
    }

    public boolean hasTrack() {
        return sourceKey != null && !sourceKey.isBlank();
    }

    // ------------------------------------------------------------------ 播放状态

    /** 总时长（毫秒）：控件里存的不准或没有时，用兜底值。 */
    public long effectiveDurationMs() {
        return durationMs > 0 ? durationMs : 180_000L;
    }

    /** 当前播放位置（毫秒）。 */
    public long positionAt(long gameTime) {
        long pos = Math.max(0L, positionMs);
        if (playing && gameTime >= startedGameTime) {
            pos += (gameTime - startedGameTime) * 50L;
        }
        long duration = effectiveDurationMs();
        return Math.max(0L, Math.min(duration, pos));
    }

    /** 放完了吗（用来把界面画成「已结束」）。 */
    public boolean finishedAt(long gameTime) {
        return durationMs > 0 && positionAt(gameTime) >= durationMs;
    }

    /** 进度（0~1）。 */
    public double progressAt(long gameTime) {
        return (double) positionAt(gameTime) / effectiveDurationMs();
    }

    /**
     * 服务端/客户端共用的状态翻转：播放 ⇄ 暂停。
     *
     * <p>已经在播放 ⇒ 记下当前位置并暂停；否则从当前位置开始播放（放到尾了则从头开始）。</p>
     */
    public void toggle(long gameTime) {
        if (playing) {
            long at = positionAt(gameTime);
            if (durationMs > 0 && at >= durationMs - 250L) {
                // 已经放完：再点一次应该是**从头重放**，而不是「暂停在结尾」
                //（音乐播放器都是这个手感，测试也把这条钉住了）
                positionMs = 0L;
                startedGameTime = gameTime;
                playing = true;
                return;
            }
            positionMs = at;
            playing = false;
        } else {
            if (durationMs > 0 && positionMs >= durationMs - 250L) {
                positionMs = 0L;
            }
            startedGameTime = gameTime;
            playing = true;
        }
    }

    // ------------------------------------------------------------------ 几何

    /** 内边距（画布单位）。 */
    public double padding() {
        return Math.max(1.0, h * 0.16);
    }

    /** 播放键的边长（画布单位）：尽量方，且不超过高度的 70%。 */
    public double buttonSize() {
        return Math.max(2.0, Math.min(h - padding() * 2, h * 0.68));
    }

    /** 时间文字占的宽度。 */
    public double timeWidth() {
        return Math.max(12.0, fontSize * TIME_CHARS * 0.58);
    }

    /**
     * 播放键方框，返回 {@code {x0, y0, x1, y1}}（**相对控件锚点、未旋转**的画布坐标）。
     * 渲染与点击都读这里。
     */
    public double[] buttonBox() {
        double size = buttonSize();
        double pad = (h - size) / 2.0;
        double x0 = padding();
        return new double[]{x0, pad, x0 + size, pad + size};
    }

    /** 波形 + 文字的区域（同样相对锚点、未旋转）。 */
    public double[] waveBox() {
        double[] b = buttonBox();
        double x0 = b[2] + padding() * 0.8;
        double x1 = w - padding() - timeWidth();
        if (x1 <= x0 + 2) {
            // 控件太窄：波形让位给文字
            x1 = x0 + 2;
        }
        return new double[]{x0, padding(), x1, h - padding()};
    }

    /** 时间文字区域（右对齐）。 */
    public double[] timeBox() {
        double x1 = w - padding();
        return new double[]{x1 - timeWidth(), padding(), x1, h - padding()};
    }

    /**
     * 点在「波形区」的哪个比例上（0~1）；不在波形区返回 -1。
     *
     * <p>用于「点/拖波形条调进度」。几何同样取自 {@link #waveBox()}，
     * 与绘制共用一份，免得出现「看着点在条上、算出来是别的比例」。</p>
     */
    public double seekFractionAt(double canvasX, double canvasY) {
        double[] local = toLocal(canvasX, canvasY);
        if (local == null) {
            return -1;
        }
        double[] box = waveBox();
        // 纵向放宽到整个控件高度（手指粗，别逼玩家对准细条）
        if (local[1] < -padding() || local[1] > h + padding()) {
            return -1;
        }
        if (local[0] < box[0] || local[0] > box[2]) {
            return -1;
        }
        double width = Math.max(0.001, box[2] - box[0]);
        return Math.max(0.0, Math.min(1.0, (local[0] - box[0]) / width));
    }

    /** 按比例设置播放位置（不改播放/暂停状态）。 */
    public void seekToFraction(double fraction, long gameTime) {
        long duration = effectiveDurationMs();
        long target = (long) (Math.max(0.0, Math.min(1.0, fraction)) * duration);
        positionMs = Math.max(0L, Math.min(duration, target));
        if (playing) {
            startedGameTime = gameTime;
        }
    }

    /** 世界坐标（画布坐标）处是否点在播放键上（自动处理控件旋转）。 */
    public boolean hitButton(double canvasX, double canvasY) {
        double[] local = toLocal(canvasX, canvasY);
        if (local == null) {
            return false;
        }
        double[] box = buttonBox();
        // 触摸设备指尖比较粗，判定范围放宽一点（左右各 0.5 画布单位）
        return local[0] >= box[0] - 0.5 && local[0] <= box[2] + 0.5
               && local[1] >= box[1] - 0.5 && local[1] <= box[3] + 0.5;
    }

    /** 竖线条数（按宽度自动时）。 */
    public int effectiveBarCount() {
        if (barCount > 0) {
            return Math.max(1, Math.min(256, barCount));
        }
        double[] box = waveBox();
        double width = Math.max(1.0, box[2] - box[0]);
        return Math.max(3, Math.min(96, (int) (width / 1.6)));
    }
}

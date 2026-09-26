package top.hmjmfabc.projector.common.widget;

import net.minecraft.nbt.CompoundTag;

/**
 * 时钟控件：显示游戏内时间，支持多种样式自由搭配。
 *
 * <h2>时间换算（用户 ⑤.2 明确要求）</h2>
 * <p>一游戏日 = <b>20 分钟</b>（不是 24 小时），显示小时 = {@code tick / 24000 × 20}：</p>
 * <pre>
 *   tick     0  -> 00:00  早晨(day)
 *   tick  6000  -> 05:00  正午(noon)
 *   tick 12000  -> 10:00  日落
 *   tick 18000  -> 15:00  午夜
 *   tick 24000  -> 20:00 = 次日 00:00
 * </pre>
 *
 * <h2>样式（用户 ⑦）</h2>
 * <ul>
 *   <li>① <b>标题</b>：默认 {@code Current Time:}，可自定义（支持 {@code &} 格式化代码）；</li>
 *   <li>② <b>时钟样式</b>：
 *     <ul>
 *       <li>{@link #STYLE_SIMPLE} 简约 —— 与旧版一致，如 {@code 12:00}（默认）；</li>
 *       <li>{@link #STYLE_UPPER} 大写 —— 如 {@code 拾贰时〇〇分}；</li>
 *       <li>{@link #STYLE_STACK} 堆叠 —— 上面一行小时、下面一行分钟，两者等大。</li>
 *     </ul>
 *   </li>
 *   <li>③ <b>午别</b>：可选，启用后在时钟下方显示（边界见 {@link #periodLabel}）。</li>
 * </ul>
 * <p>三者都可以在编辑界面里自由开关/切换。</p>
 */
public class ClockWidget extends Widget {

    /** 样式：简约（与旧版一致），如 {@code 12:00}。 */
    public static final int STYLE_SIMPLE = 0;
    /** 样式：大写，如 {@code 拾贰时〇〇分}。 */
    public static final int STYLE_UPPER = 1;
    /** 样式：堆叠，上面一行小时、下面一行分钟，两者等大。 */
    public static final int STYLE_STACK = 2;

    public static final String[] STYLE_IDS = {"simple", "upper", "stack"};
    public static final String[] STYLE_NAMES = {"\u7b80\u7ea6", "\u5927\u5199", "\u5806\u53e0"};

    public String fontId = Fonts.CAVIAR_DREAMS;
    public double fontSize = 12;
    /** 时区偏移（分钟）。 */
    public int offsetMinutes;
    /** 是否显示秒（游戏内秒）。 */
    public boolean showSeconds;
    /** 颜色（ARGB）。 */
    public int color = 0xFFFFFFFF;
    /** 是否显示世界内时间文字前的「宝石」之类前缀；这里保留给扩展用。 */
    public boolean shadow = true;

    // ---------------- ⑦ 新增：标题 / 样式 / 午别 ----------------

    /** ① 标题文字（支持 & 格式化代码）。 */
    public String title = "Current Time:";
    /** 是否显示标题。 */
    public boolean showTitle = true;
    /** 标题字号（画布单位）。 */
    public double titleSize = 8;
    /** 标题颜色（ARGB）。 */
    public int titleColor = 0xFFBFBFBF;
    /**
     * ① 标题的字体（用户要求「都应该能分别调整字体」）。
     *
     * <p>默认用 Minecraft AE 而不是 Caviar Dreams：后者只有 582 个码点、
     * <b>没有任何中文字形</b>，标题里只要有汉字就会变成一排红方块。</p>
     */
    public String titleFontId = Fonts.MINECRAFT_AE;

    /** ② 时钟样式（{@link #STYLE_SIMPLE} / {@link #STYLE_UPPER} / {@link #STYLE_STACK}）。 */
    public int style = STYLE_SIMPLE;

    /** ③ 是否显示午别。 */
    public boolean showPeriod;
    /** 午别颜色（ARGB）。 */
    public int periodColor = 0xFFFFD479;
    /** 午别字号（画布单位）。 */
    public double periodSize = 8;
    /** ③ 午别的字体（与标题、时钟本体分开设置）。午别是中文，默认必须有中文字形。 */
    public String periodFontId = Fonts.MINECRAFT_AE;

    @Override
    public int kind() {
        return KIND_CLOCK;
    }

    @Override
    public String label() {
        return "\u65f6\u949f(" + STYLE_NAMES[safeStyle()] + " UTC"
                + (offsetMinutes >= 0 ? "+" : "")
                + String.format(java.util.Locale.ROOT, "%.1f", offsetMinutes / 60.0) + ")";
    }

    private int safeStyle() {
        return style < 0 || style >= STYLE_NAMES.length ? STYLE_SIMPLE : style;
    }

    /** 【①】本控件引用的字体（供联机字体核验使用）。 */
    @Override
    public java.util.List<String> fontIds() {
        // ① 三处字体都要参与联机核验，漏一个就会出现「服务端没装却能用」
        return java.util.List.of(
                fontId == null ? "" : fontId,
                titleFontId == null ? "" : titleFontId,
                periodFontId == null ? "" : periodFontId);
    }

    @Override
    protected void saveExtra(CompoundTag t) {
        t.putString("font", fontId);
        t.putDouble("fs", fontSize);
        t.putInt("offset", offsetMinutes);
        t.putBoolean("seconds", showSeconds);
        t.putInt("color", color);
        t.putBoolean("shadow", shadow);
        // ⑦ 新增字段
        t.putString("title", title == null ? "" : title);
        t.putBoolean("showTitle", showTitle);
        t.putDouble("titleSize", titleSize);
        t.putInt("titleColor", titleColor);
        t.putString("titleFont", titleFontId);
        t.putInt("style", style);
        t.putString("styleId", STYLE_IDS[safeStyle()]);
        t.putBoolean("showPeriod", showPeriod);
        t.putInt("periodColor", periodColor);
        t.putString("periodFont", periodFontId);
        t.putDouble("periodSize", periodSize);
    }

    @Override
    public void loadExtra(CompoundTag t) {
        fontId = t.contains("font") ? t.getString("font") : Fonts.CAVIAR_DREAMS;
        fontSize = t.contains("fs") ? t.getDouble("fs") : 12;
        offsetMinutes = t.getInt("offset");
        showSeconds = t.getBoolean("seconds");
        color = t.contains("color") ? t.getInt("color") : 0xFFFFFFFF;
        shadow = !t.contains("shadow") || t.getBoolean("shadow");
        // ⑦ 新增字段：旧存档没有这些键，全部走默认值
        title = t.contains("title") ? t.getString("title") : "Current Time:";
        showTitle = !t.contains("showTitle") || t.getBoolean("showTitle");
        titleSize = t.contains("titleSize") ? t.getDouble("titleSize") : 8;
        titleColor = t.contains("titleColor") ? t.getInt("titleColor") : 0xFFBFBFBF;
        titleFontId = t.contains("titleFont") ? t.getString("titleFont") : Fonts.MINECRAFT_AE;
        style = t.contains("style") ? t.getInt("style") : STYLE_SIMPLE;
        showPeriod = t.getBoolean("showPeriod");
        periodColor = t.contains("periodColor") ? t.getInt("periodColor") : 0xFFFFD479;
        periodFontId = t.contains("periodFont") ? t.getString("periodFont") : Fonts.MINECRAFT_AE;
        periodSize = t.contains("periodSize") ? t.getDouble("periodSize") : 8;
    }

    /** 一游戏日对应的现实分钟数（用户明确要求：**20 分钟**，不是 24 小时）。 */
    public static final int GAME_DAY_MINUTES = 20;
    /** 一游戏日的刻数（Minecraft 固定 24000）。 */
    private static final long TICKS_PER_DAY = 24000L;

    /**
     * 根据游戏刻计算显示用的 (小时, 分钟, 秒)。
     *
     * <p>显示的是「一游戏日 = 20 小时制」的时钟。也就是说：tick 是唯一的真值来源，
     * 显示小时 = tick / 24000 × 20。早期实现按 24 小时制映射（还把 0 刻当成 06:00），
     * 与设计意图不符。</p>
     */
    public int[] timeOfDay(long dayTime) {
        double dayFraction = Math.floorMod(dayTime, TICKS_PER_DAY) / (double) TICKS_PER_DAY;
        double hoursF = dayFraction * GAME_DAY_MINUTES;
        // 时区偏移以「分钟」为单位，但这里的一分钟同样是「显示分钟」，直接加在小时上
        hoursF += offsetMinutes / 60.0;
        // 回到 [0, 20) 区间
        hoursF = ((hoursF % GAME_DAY_MINUTES) + GAME_DAY_MINUTES) % GAME_DAY_MINUTES;
        int hours = (int) Math.floor(hoursF);
        double rem = (hoursF - hours) * 60.0;
        int minutes = (int) Math.floor(rem);
        int seconds = (int) Math.floor((rem - minutes) * 60.0);
        return new int[]{hours, minutes, seconds};
    }

    /** 格式化后的字符串，例如 {@code 10:00} 或 {@code 10:00:30}（简约样式）。 */
    public String format(long dayTime) {
        int[] t = timeOfDay(dayTime);
        if (showSeconds) {
            return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", t[0], t[1], t[2]);
        }
        return String.format(java.util.Locale.ROOT, "%02d:%02d", t[0], t[1]);
    }

    /**
     * 时钟本体的显示行。
     *
     * <p>简约/大写返回 1 行；堆叠返回 2 行，<b>索引 0 是最上面那一行（小时）、
     * 索引 1 是下面那一行（分钟）</b>。渲染层必须按「先画下面的、后画上面的」
     * 顺序绘制（画布 y 轴向上）。</p>
     */
    public String[] clockLines(long dayTime) {
        int[] t = timeOfDay(dayTime);
        return switch (safeStyle()) {
            case STYLE_UPPER -> new String[]{
                    upperTwo(t[0]) + "\u65f6" + upperTwo(t[1]) + "\u5206"
                            + (showSeconds ? upperTwo(t[2]) + "\u79d2" : "")};
            case STYLE_STACK -> new String[]{
                    String.format(java.util.Locale.ROOT, "%02d", t[0]),
                    String.format(java.util.Locale.ROOT, "%02d", t[1])};
            default -> new String[]{format(dayTime)};
        };
    }

    /**
     * ③ 午别标签。一游戏日 = 20 小时 = 1200 显示分钟。
     *
     * <pre>
     *   00:00 ~ 04:30  上午      0    ~ 270
     *   04:30 ~ 05:30  中午      270  ~ 330
     *   05:30 ~ 10:30  下午      330  ~ 630
     *   10:30 ~ 14:30  傍晚      630  ~ 870
     *   14:30 ~ 15:30  午夜      870  ~ 930
     *   15:30 ~ 19:30  凌晨      930  ~ 1170
     *   19:30 ~ 20:00(=00:00) 清晨  1170 ~ 1200
     * </pre>
     */
    public String periodLabel(long dayTime) {
        int[] t = timeOfDay(dayTime);
        int mins = t[0] * 60 + t[1];
        if (mins < 270) return "\u4e0a\u5348";   // 上午
        if (mins < 330) return "\u4e2d\u5348";   // 中午
        if (mins < 630) return "\u4e0b\u5348";   // 下午
        if (mins < 870) return "\u508d\u665a";   // 傍晚
        if (mins < 930) return "\u5348\u591c";   // 午夜
        if (mins < 1170) return "\u51cc\u6668";  // 凌晨
        return "\u6e05\u6668";                    // 清晨
    }

    /** 标题是否真的会被画出来。 */
    public boolean hasTitle() {
        return showTitle && title != null && !title.isEmpty();
    }

    // ------------------------------------------------------------------
    // 大写数字（壹贰叁…）
    // ------------------------------------------------------------------

    /** 〇 壹 贰 叁 肆 伍 陆 柒 捌 玖 */
    private static final char[] UPPER_DIGITS = {
            '\u3007', '\u58f9', '\u8d30', '\u53c1', '\u8086',
            '\u4f0d', '\u9646', '\u67d2', '\u634c', '\u7396'};
    /** 拾 */
    private static final char UPPER_TEN = '\u62fe';

    /**
     * 把 0~99 写成两位大写汉字。
     *
     * <p>{@code 0 -> 〇〇}、{@code 5 -> 〇五}、{@code 10 -> 拾}、
     * {@code 12 -> 拾贰}、{@code 19 -> 拾玖}。</p>
     */
    public static String upperTwo(int n) {
        if (n < 0) n = 0;
        if (n < 10) {
            return "" + UPPER_DIGITS[0] + UPPER_DIGITS[n];
        }
        if (n < 20) {
            return "" + UPPER_TEN + (n == 10 ? "" : String.valueOf(UPPER_DIGITS[n % 10]));
        }
        return "" + UPPER_DIGITS[n / 10] + UPPER_TEN
                + (n % 10 == 0 ? "" : String.valueOf(UPPER_DIGITS[n % 10]));
    }
}

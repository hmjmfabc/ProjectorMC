package top.hmjmfabc.projector.common.text;

import java.util.ArrayList;
import java.util.List;

/**
 * Minecraft 格式化代码的解析器。
 *
 * <p>模组里的文本使用 {@code &} 代替 {@code §}：{@code &4} = 深红、{@code &l} = 粗体……
 * 想显示真正的 {@code &} 时写 {@code &&}。</p>
 *
 * <p>另外额外支持一种 6 位十六进制颜色写法：{@code &#RRGGBB}，
 * 方便调色盘里选任意颜色。</p>
 */
public final class FormatCodes {

    /** 传统 16 色（ARGB）。 */
    public static final int[] COLORS = {
            0xFF000000, // 0 黑
            0xFF0000AA, // 1 深蓝
            0xFF00AA00, // 2 深绿
            0xFF00AAAA, // 3 深青
            0xFFAA0000, // 4 深红
            0xFFAA00AA, // 5 深紫
            0xFFFFAA00, // 6 金
            0xFFAAAAAA, // 7 灰
            0xFF555555, // 8 深灰
            0xFF5555FF, // 9 蓝
            0xFF55FF55, // a 绿
            0xFF55FFFF, // b 青
            0xFFFF5555, // c 红
            0xFFFF55FF, // d 粉
            0xFFFFFF55, // e 黄
            0xFFFFFFFF, // f 白
    };

    /** 颜色代码字符（小写）。 */
    public static final String COLOR_CHARS = "0123456789abcdef";

    /** 颜色代码对应的中文名，用于调色盘提示。 */
    public static final String[] COLOR_NAMES = {
            "\u9ed1\u8272", "\u6df1\u84dd", "\u6df1\u7eff", "\u6df1\u9752",
            "\u6df1\u7ea2", "\u6df1\u7d2b", "\u91d1\u8272", "\u7070\u8272",
            "\u6df1\u7070", "\u84dd\u8272", "\u7eff\u8272", "\u9752\u8272",
            "\u7ea2\u8272", "\u7c89\u8272", "\u9ec4\u8272", "\u767d\u8272"};

    /** 修饰符（非颜色）格式化代码。 */
    public static final String STYLE_CHARS = "klmnor";

    /** 渐变类型：不使用渐变。 */
    public static final int GRAD_NONE = 0;
    /** 渐变类型：彩色渐变（{@code &z}）。 */
    public static final int GRAD_RAINBOW = 1;
    /** 渐变类型：双色渐变（{@code &s<色A>e<色B>}）。 */
    public static final int GRAD_TWO = 2;

    /** 一段文本的运行片段：文字 + 样式。 */
    public record Run(String text, Style style) {
    }

    /** 文本样式。 */
    public static final class Style {
        public int color = 0xFFFFFFFF;
        public boolean obfuscated;
        public boolean bold;
        public boolean strikethrough;
        public boolean underline;
        public boolean italic;

        /**
         * 渐变类型（{@link #GRAD_NONE} / {@link #GRAD_RAINBOW} / {@link #GRAD_TWO}）。
         *
         * <p>【⑨.2 / ⑨.3】不为 NONE 时，{@link #color} 会被忽略，
         * 实际颜色由 {@link TextLayout} 按每个字形在片段中的位置插值算出。</p>
         */
        public int gradient = GRAD_NONE;
        /** 双色渐变的起点色（左）。 */
        public int gradFrom = 0xFFFFFFFF;
        /** 双色渐变的终点色（右）。 */
        public int gradTo = 0xFFFFFFFF;

        public Style copy() {
            Style s = new Style();
            s.color = color;
            s.obfuscated = obfuscated;
            s.bold = bold;
            s.strikethrough = strikethrough;
            s.underline = underline;
            s.italic = italic;
            s.gradient = gradient;
            s.gradFrom = gradFrom;
            s.gradTo = gradTo;
            return s;
        }

        public boolean sameAs(Style o) {
            return o != null && color == o.color && obfuscated == o.obfuscated && bold == o.bold
                    && strikethrough == o.strikethrough && underline == o.underline && italic == o.italic
                    && gradient == o.gradient && gradFrom == o.gradFrom && gradTo == o.gradTo;
        }
    }

    private FormatCodes() {
    }

    /** 把 {@code &} 代码解析成若干「同样式」的片段。 */
    public static List<Run> parse(String input) {
        List<Run> out = new ArrayList<>();
        if (input == null || input.isEmpty()) {
            return out;
        }
        Style style = new Style();
        StringBuilder buf = new StringBuilder();
        int i = 0;
        int n = input.length();
        while (i < n) {
            char c = input.charAt(i);
            if (c == '&' && i + 1 < n) {
                char nx = input.charAt(i + 1);
                if (nx == '&') {
                    buf.append('&');
                    i += 2;
                    continue;
                }
                if (nx == '#') {
                    // &#RRGGBB
                    if (i + 8 <= n) {
                        String hex = input.substring(i + 2, i + 8);
                        Integer rgb = tryHex(hex);
                        if (rgb != null) {
                            flush(out, buf, style);
                            style = style.copy();
                            style.color = 0xFF000000 | rgb;
                            i += 8;
                            continue;
                        }
                    }
                }
                char lc = Character.toLowerCase(nx);
                // ---- 【⑨.2】&z：彩色渐变 ----
                if (lc == 'z') {
                    flush(out, buf, style);
                    style = style.copy();
                    style.gradient = GRAD_RAINBOW;
                    i += 2;
                    continue;
                }
                // ---- 【⑨.3】&s<色A>e<色B>：双色渐变 ----
                if (lc == 's') {
                    int[] two = parseTwoColor(input, i);
                    if (two != null) {
                        flush(out, buf, style);
                        style = style.copy();
                        style.gradient = GRAD_TWO;
                        style.gradFrom = two[0];
                        style.gradTo = two[1];
                        i = two[2];
                        continue;
                    }
                    // 解析失败：按普通未知代码处理（原样输出），不要吞掉玩家打的字
                }
                int ci = COLOR_CHARS.indexOf(lc);
                if (ci >= 0) {
                    flush(out, buf, style);
                    style = style.copy();
                    style.color = COLORS[ci];
                    // 颜色代码会重置所有修饰符（与原版一致）
                    style.bold = style.italic = style.underline = style.strikethrough = style.obfuscated = false;
                    // 颜色代码同时结束渐变（原版语义：新的颜色指令覆盖旧的）
                    style.gradient = GRAD_NONE;
                    i += 2;
                    continue;
                }
                switch (lc) {
                    case 'k' -> {
                        flush(out, buf, style);
                        style = style.copy();
                        style.obfuscated = true;
                    }
                    case 'l' -> {
                        flush(out, buf, style);
                        style = style.copy();
                        style.bold = true;
                    }
                    case 'm' -> {
                        flush(out, buf, style);
                        style = style.copy();
                        style.strikethrough = true;
                    }
                    case 'n' -> {
                        flush(out, buf, style);
                        style = style.copy();
                        style.underline = true;
                    }
                    case 'o' -> {
                        flush(out, buf, style);
                        style = style.copy();
                        style.italic = true;
                    }
                    case 'r' -> {
                        flush(out, buf, style);
                        style = new Style();
                    }
                    default -> {
                        // 未知代码，原样输出
                        buf.append(c);
                        i++;
                        continue;
                    }
                }
                i += 2;
                continue;
            }
            buf.append(c);
            i++;
        }
        flush(out, buf, style);
        return out;
    }

    private static void flush(List<Run> out, StringBuilder buf, Style style) {
        if (buf.length() > 0) {
            out.add(new Run(buf.toString(), style.copy()));
            buf.setLength(0);
        }
    }

    private static Integer tryHex(String s) {
        try {
            return Integer.parseInt(s, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 渐变（⑨.2 &z / ⑨.3 &s..e..）
    // ------------------------------------------------------------------

    /**
     * 解析 {@code &s<色A>[e]<色B>}，返回 {@code {色A, 色B, 新下标}}；解析不了返回 null。
     *
     * <p><b>两种写法都要认</b>（规格与用户原例并不一致，这里以「都能用」为准）：</p>
     * <ul>
     *   <li>规格写法：{@code &s#FF0000e#0000FF} —— 中间有一个 {@code e} 分隔符；</li>
     *   <li>用户原例：{@code &s#66CCFF&#39C5BB} —— 色B 直接以 {@code &#RRGGBB}
     *       的形式跟在色A 后面，<b>没有</b> {@code e}。</li>
     * </ul>
     * <p>注意 {@code e} 本身也是 16 色里的黄色，所以只有「{@code e} 后面确实跟着
     * 一个颜色」时才把它当分隔符，否则按黄色处理。</p>
     */
    private static int[] parseTwoColor(String s, int ampIndex) {
        int p = ampIndex + 2; // 跳过 "&s"
        int[] a = readColorSpec(s, p);
        if (a == null) return null;
        p = a[1];
        int[] b = null;
        // 先试「e 作分隔符」，前提是 e 后面真的是一个颜色
        if (p < s.length() && Character.toLowerCase(s.charAt(p)) == 'e') {
            int[] after = readColorSpec(s, p + 1);
            if (after != null) b = after;
        }
        // 再试「色B 紧接着色A」（用户原例的写法）
        if (b == null) b = readColorSpec(s, p);
        if (b == null) return null;
        return new int[]{a[0], b[0], b[1]};
    }

    /**
     * 读一个颜色写法，返回 {@code {argb, 新下标}}。
     * 支持三种：{@code #RRGGBB}、{@code &#RRGGBB}、单个 16 色字符（如 {@code c} = 红）。
     */
    private static int[] readColorSpec(String s, int p) {
        if (p < s.length() && s.charAt(p) == '&') p++;
        if (p < s.length() && s.charAt(p) == '#') {
            if (p + 7 <= s.length()) {
                Integer rgb = tryHex(s.substring(p + 1, p + 7));
                if (rgb != null) return new int[]{0xFF000000 | rgb, p + 7};
            }
            return null;
        }
        if (p < s.length()) {
            int ci = COLOR_CHARS.indexOf(Character.toLowerCase(s.charAt(p)));
            if (ci >= 0) return new int[]{COLORS[ci], p + 1};
        }
        return null;
    }

    /**
     * 按位置 {@code t}（0~1）取渐变色。
     *
     * <p>彩色渐变走 HSV 色相环 0°→360°，所以既「彩」又在首尾自然衔接，
     * 重复的文本看起来也是连续的。双色渐变则在两端颜色之间线性插值。</p>
     */
    public static int gradientColor(Style style, double t) {
        if (style == null) return 0xFFFFFFFF;
        double k = t < 0 ? 0 : (t > 1 ? 1 : t);
        if (style.gradient == GRAD_RAINBOW) {
            return 0xFF000000 | hsv((float) (k * 360.0), 0.85f, 1.0f);
        }
        if (style.gradient == GRAD_TWO) {
            int r = lerp((style.gradFrom >> 16) & 0xFF, (style.gradTo >> 16) & 0xFF, k);
            int g = lerp((style.gradFrom >> 8) & 0xFF, (style.gradTo >> 8) & 0xFF, k);
            int b = lerp(style.gradFrom & 0xFF, style.gradTo & 0xFF, k);
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return style.color;
    }

    /** 这个样式是否需要按位置取色。 */
    public static boolean isGradient(Style style) {
        return style != null && style.gradient != GRAD_NONE;
    }

    private static int lerp(int a, int b, double t) {
        int v = (int) Math.round(a + (b - a) * t);
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    /** HSV -> RGB（h: 0~360, s/v: 0~1），返回 0xRRGGBB。 */
    private static int hsv(float h, float s, float v) {
        float hh = ((h % 360f) + 360f) % 360f / 60f;
        int i = (int) Math.floor(hh);
        float f = hh - i;
        float p = v * (1f - s);
        float q = v * (1f - s * f);
        float t = v * (1f - s * (1f - f));
        float r, g, b;
        switch (i % 6) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        int ri = Math.round(r * 255f), gi = Math.round(g * 255f), bi = Math.round(b * 255f);
        return (ri << 16) | (gi << 8) | bi;
    }

    /** 调色盘用：渐变色块的展示采样（在渐变上取若干个点）。 */
    public static int[] gradientSamples(int gradient, int from, int to, int count) {
        int n = Math.max(2, count);
        int[] out = new int[n];
        Style s = new Style();
        s.gradient = gradient;
        s.gradFrom = from;
        s.gradTo = to;
        for (int i = 0; i < n; i++) {
            out[i] = gradientColor(s, i / (double) (n - 1));
        }
        return out;
    }

    /** 去掉所有格式化代码，得到纯文本（用于列表显示 / 宽度测量）。 */
    public static String strip(String input) {
        if (input == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Run r : parse(input)) {
            sb.append(r.text());
        }
        return sb.toString();
    }

    /** 取首个有效颜色（用于调色盘初始值），找不到时返回白色。 */
    public static int firstColor(String input) {
        List<Run> runs = parse(input);
        for (Run r : runs) {
            if (!r.text().isEmpty()) {
                return r.style().color;
            }
        }
        return 0xFFFFFFFF;
    }
}

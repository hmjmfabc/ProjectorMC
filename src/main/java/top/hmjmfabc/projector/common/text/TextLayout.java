package top.hmjmfabc.projector.common.text;

import java.util.ArrayList;
import java.util.List;

/**
 * 与渲染后端无关的文本排版器。
 *
 * <p>负责：</p>
 * <ul>
 *   <li>解析 {@code &} 格式化代码，得到带样式的片段；</li>
 *   <li>按字体度量逐码点排版（含 kerning 与代理对）；</li>
 *   <li>按 {@code wrapWidth} 自动换行、按 {@code \n} 强制换行；</li>
 *   <li>把每个字形通过 {@link GlyphSink} 回调交给具体渲染实现。</li>
 * </ul>
 *
 * <p><b>坐标约定</b>：排版坐标系 x 向右、y 向上，基线在 y = 0，
 * 首行基线为 y = -ascent（ascent 为负值）。调用方给出的是文字外接矩形
 * 的左下角，排版器据此计算首行基线。世界渲染时把 y 取负即可翻到屏幕方向，
 * 因此本类完全不需要关心摄像机与方块朝向。</p>
 */
public final class TextLayout {

    /** 字体度量接口（由 {@code TtfFont} 实现，避免 common 包依赖客户端类）。 */
    public interface FontMetrics {
        /** 光栅化像素高度。 */
        float rasterPx();

        float ascentPx();

        float descentPx();

        float lineHeightPx();

        float advance(int codePoint);

        float kerning(int left, int right);

        /** 返回字形尺寸 [bearingX, bearingY, width, height]；无字形返回 null。 */
        float[] glyphBox(int codePoint);
    }

    /** 字形回调。所有坐标都在「文字局部坐标系」中（x 向右、y 向上，原点 = 文字左下角）。 */
    @FunctionalInterface
    public interface GlyphSink {
        /**
         * @param x0,y0,x1,y1 字形在局部坐标系中的矩形（y 向上）
         * @param u0,v0,u1,v1 图集 UV
         * @param page        图集页码
         * @param argb        颜色
         * @param style       样式标志：1=粗体 2=斜体 4=下划线 8=删除线
         */
        void glyph(double x0, double y0, double x1, double y1,
                   float u0, float v0, float u1, float v1, int page,
                   int argb, int style);
    }

    public static final int STYLE_BOLD = 1;
    public static final int STYLE_ITALIC = 2;
    public static final int STYLE_UNDERLINE = 4;
    public static final int STYLE_STRIKETHROUGH = 8;

    /** 一个待绘制字形的完整描述。 */
    public static final class GlyphRun {
        public int codePoint;
        public double x0, y0, x1, y1;
        public float u0, v0, u1, v1;
        public int page;
        public int argb;
        public int style;
    }

    /** 排版结果尺寸（画布单位）。 */
    public record Metrics(double width, double height, double lines) {
    }

    private TextLayout() {
    }

    /**
     * 只测量尺寸（用于自动调整控件大小）。
     *
     * <p><b>必须与 {@link #layoutRuns} 完全同源</b>：这里直接量同一份排版结果的
     * 包围盒，所以「控件方框」与「实际画出来的字形」永远严丝合缝。
     * 早期版本这里另写了一套测量逻辑，与渲染时的排版有偏差，
     * 表现就是「文字跟控件方框总是对不上」。</p>
     *
     * <p>返回的 {@code width}/{@code height} 是字形的<b>墨迹包围盒</b>尺寸，
     * 与排版结果的 {@code [minX,maxX] x [minY,maxY]} 一致。</p>
     */
    public static Metrics measure(String text, FontMetrics font, double fontSize, double wrapWidth, double lineSpacing) {
        List<GlyphRun> runs = layoutRuns(text, font, fontSize, wrapWidth, lineSpacing, 0, 0);
        if (runs.isEmpty()) {
            return new Metrics(0, Math.max(fontSize * 0.8, 1), 1);
        }
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (GlyphRun g : runs) {
            minX = Math.min(minX, g.x0);
            maxX = Math.max(maxX, g.x1);
            minY = Math.min(minY, g.y0);
            maxY = Math.max(maxY, g.y1);
        }
        return new Metrics(maxX - minX, Math.max(1, maxY - minY), countLines(text, wrapWidth));
    }

    /**
     * 排版并通过回调输出字形。
     *
     * @param text        原始文本（含 {@code &} 代码）
     * @param font        字体度量
     * @param fontSize    字号（画布单位），1 格 = 16
     * @param wrapWidth   自动换行宽度（画布单位，&lt;=0 表示不自动换行）
     * @param lineSpacing 行距倍数
     * @param originX     文字外接矩形左边界
     * @param originY     文字外接矩形下边界
     */
    public static List<GlyphRun> layoutRuns(String text, FontMetrics font, double fontSize,
                                            double wrapWidth, double lineSpacing,
                                            double originX, double originY) {
        List<GlyphRun> out = new ArrayList<>();
        if (text == null || text.isEmpty() || font == null) {
            return out;
        }
        double scale = fontSize / font.rasterPx();
        double lineHeight = font.lineHeightPx() * scale * Math.max(0.4, lineSpacing);
        double descent = font.descentPx() * scale; // 负值

        List<FormatCodes.Run> runs = FormatCodes.parse(text);

        // 先把所有 (codepoint, style) 摊平，便于跨片段处理换行与 kerning
        List<Cell> cells = new ArrayList<>();
        for (FormatCodes.Run run : runs) {
            String s = run.text();
            int i = 0;
            while (i < s.length()) {
                int cp = s.codePointAt(i);
                i += Character.charCount(cp);
                Cell c = new Cell();
                c.codePoint = cp;
                c.style = run.style();
                c.newline = (cp == '\n');
                cells.add(c);
            }
        }
        if (cells.isEmpty()) {
            return out;
        }

        // ------------------------------------------------------------------
        // 【⑨.2 &z 彩色渐变 / ⑨.3 &s..e.. 双色渐变】分配每个字形的渐变位置
        // ------------------------------------------------------------------
        // 同一段渐变里的字形共用一个 Style 实例（FormatCodes.parse 在每次遇到
        // 格式化代码时才 copy 一次），所以「实例相同」就等于「属于同一段渐变」。
        // 先数出这段里有几个字形，再按序号插值：一段文本正好铺满一整条渐变。
        for (int i = 0; i < cells.size(); ) {
            FormatCodes.Style st = cells.get(i).style;
            if (!FormatCodes.isGradient(st)) {
                i++;
                continue;
            }
            int j = i;
            int count = 0;
            while (j < cells.size() && cells.get(j).style == st) {
                if (!cells.get(j).newline) count++;
                j++;
            }
            int k = 0;
            for (int m = i; m < j; m++) {
                Cell c = cells.get(m);
                if (c.newline) continue;
                c.gradT = count <= 1 ? 0.0 : k / (double) (count - 1);
                k++;
            }
            i = j;
        }

        // 第一次遍历：确定实际行数（考虑自动换行），以便把整段文字垂直居中在 [originY, originY+height]
        int totalLines;
        {
            double penX = 0;
            int prev = -1;
            int lines = 1;
            for (Cell c : cells) {
                if (c.newline) {
                    lines++;
                    penX = 0;
                    prev = -1;
                    continue;
                }
                float[] b = font.glyphBox(c.codePoint);
                float a = b == null ? (float) (fontSize * 0.5) : (float) (font.advance(c.codePoint) * scale);
                if (prev >= 0) penX += font.kerning(prev, c.codePoint) * scale;
                if (wrapWidth > 0 && penX > 0 && penX + a > wrapWidth) {
                    lines++;
                    penX = 0;
                }
                penX += a;
                prev = c.codePoint;
            }
            totalLines = lines;
        }

        double penX = 0;
        double penY = originY + (totalLines - 1) * lineHeight - descent; // 首行基线
        int prevCp = -1;

        for (Cell c : cells) {
            if (c.newline) {
                penX = 0;
                penY -= lineHeight;
                prevCp = -1;
                continue;
            }
            float[] box = font.glyphBox(c.codePoint);
            if (box == null) {
                // 字体不含该字符：退化成一个方框
                double adv = fontSize * 0.5;
                out.add(fallbackRun(originX + penX, penY, adv, fontSize, c));
                penX += adv;
                prevCp = -1;
                continue;
            }
            float adv = font.advance(c.codePoint);
            float bearingX = box[0], bearingY = box[1], gw = box[2], gh = box[3];
            double ker = prevCp >= 0 ? font.kerning(prevCp, c.codePoint) : 0;
            penX += ker * scale;

            if (wrapWidth > 0 && penX > 0 && penX + (adv * scale) > wrapWidth) {
                penX = 0;
                prevCp = -1;
                penY -= lineHeight;
            }

            double gx0 = originX + penX + bearingX * scale;
            double gy0 = penY + bearingY * scale;
            double gx1 = gx0 + gw * scale;
            double gy1 = gy0 + gh * scale;
            if (gw > 0 && gh > 0) {
                GlyphRun g = new GlyphRun();
                g.codePoint = c.codePoint;
                g.x0 = gx0;
                g.y0 = gy0;
                g.x1 = gx1;
                g.y1 = gy1;
                g.argb = FormatCodes.isGradient(c.style)
                        ? FormatCodes.gradientColor(c.style, c.gradT) : c.style.color;
                g.style = styleBits(c.style);
                out.add(g);
            }
            penX += adv * scale;
            prevCp = c.codePoint;
        }
        return out;
    }

    /** 缺字占位方框。 */
    private static GlyphRun fallbackRun(double x, double baselineY, double w, double h, Cell c) {
        GlyphRun g = new GlyphRun();
        g.codePoint = -1;
        g.x0 = x;
        g.y0 = baselineY - h * 0.1;
        g.x1 = x + w * 0.9;
        g.y1 = g.y0 + h * 0.7;
        // 缺字占位也按渐变取色：否则一行彩虹里会突兀地插一个红方块
        g.argb = FormatCodes.isGradient(c.style)
                ? FormatCodes.gradientColor(c.style, c.gradT) : 0xFFFF5555;
        g.style = styleBits(c.style);
        return g;
    }

    /**
     * 排版并把每个字形通过 {@link GlyphSink} 回调出去。
     *
     * <p>现在只是一层薄包装：内部调用 {@link #layoutRuns}，因此与
     * {@link #measure} 使用的是同一份排版结果。</p>
     */
    public static void layout(String text, FontMetrics font, double fontSize, double wrapWidth,
                              double lineSpacing, double originX, double originY, GlyphSink sink) {
        for (GlyphRun g : layoutRuns(text, font, fontSize, wrapWidth, lineSpacing, originX, originY)) {
            sink.glyph(g.x0, g.y0, g.x1, g.y1, g.u0, g.v0, g.u1, g.v1, g.page, g.argb, g.style);
        }
    }

    public static int styleBits(FormatCodes.Style s) {
        int bits = 0;
        if (s.bold) bits |= STYLE_BOLD;
        if (s.italic) bits |= STYLE_ITALIC;
        if (s.underline) bits |= STYLE_UNDERLINE;
        if (s.strikethrough) bits |= STYLE_STRIKETHROUGH;
        return bits;
    }

    private static final class Cell {
        int codePoint;
        FormatCodes.Style style;
        boolean newline;
        /** 【⑨.2 / ⑨.3】本字形在所属渐变片段中的位置（0~1）；非渐变为 0。 */
        double gradT;
    }

    // ------------------------------------------------------------------
    // 【27.2-pre-136】「打字」动画：按可见字数取前缀
    // ------------------------------------------------------------------

    /**
     * 文本里有多少个<b>可见字符</b>（码点）。
     *
     * <p>格式化代码（{@code &a} / {@code &#RRGGBB} / {@code &z} / {@code &s..e..}）不算，
     * {@code &&} 算一个（它渲染出来就是一个 {@code &}）；换行算一个
     * （打字动画里换行也是一次「敲键」）。</p>
     */
    public static int visibleCount(String raw) {
        if (raw == null || raw.isEmpty()) {
            return 0;
        }
        int n = raw.length();
        int i = 0;
        int count = 0;
        while (i < n) {
            int code = FormatCodes.codeLengthAt(raw, i);
            if (code > 0) {
                if (raw.charAt(i + 1) == '&') {
                    count++;   // && = 一个字面 &
                }
                i += code;
                continue;
            }
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            count++;
        }
        return count;
    }

    /**
     * 取「前 {@code visible} 个可见字符」的前缀，<b>格式化代码原样保留、绝不截断到一半</b>。
     *
     * <p>打字动画每帧都要调它：里面的字符会一个个冒出来，而颜色/粗体等代码
     * 必须从一开始就带上——否则「打到第 5 个字时颜色突然变」或者
     * 半截代码（{@code &#FF}）被当成普通文字画出来。</p>
     *
     * <p>码点整取：代理对（emoji）不会被劈成两半。</p>
     */
    public static String visiblePrefix(String raw, int visible) {
        if (raw == null || raw.isEmpty() || visible <= 0) {
            return "";
        }
        int n = raw.length();
        int i = 0;
        int used = 0;
        StringBuilder out = null;
        while (i < n && used < visible) {
            int code = FormatCodes.codeLengthAt(raw, i);
            if (code > 0) {
                if (raw.charAt(i + 1) == '&') {
                    // ⚠ 必须原样写回 "&&" 而不是一个 '&'：
                    // 只写一个 '&' 的话，它后面那个字会被当成格式化代码吃掉
                    // （"&a" 是绿色！），画出来的文字会缺字、还整段变色。
                    if (out == null) out = new StringBuilder(n);
                    out.append("&&");
                    used++;
                } else {
                    if (out == null) out = new StringBuilder(n);
                    out.append(raw, i, i + code);
                }
                i += code;
                continue;
            }
            int cp = raw.codePointAt(i);
            int step = Character.charCount(cp);
            if (out == null) out = new StringBuilder(n);
            out.append(raw, i, i + step);
            i += step;
            used++;
        }
        // 前面的格式化代码会原样带上（即使一个可见字符都还没打出来）：
        // 这样「打第一个字」的时候颜色就已经对了，而不是打完才发现颜色变了。
        return out == null ? "" : out.toString();
    }

    /** 粗略统计行数（用于定位首行基线）。 */
    public static int countLines(String text, double wrapWidth) {
        if (text == null || text.isEmpty()) return 1;
        String plain = FormatCodes.strip(text);
        int lines = 1;
        for (int i = 0; i < plain.length(); i++) {
            if (plain.charAt(i) == '\n') lines++;
        }
        if (wrapWidth > 0) {
            // 无法在没有字体度量时精确计算，这里只保证不低估；实际行数由 layout 决定
            return lines;
        }
        return lines;
    }

    /**
     * 精确定位首行基线的辅助方法：给定总行数与度量，返回首行基线相对外接矩形下边界的高度。
     */
    public static double firstBaseline(double fontSize, FontMetrics font, double lineSpacing, int totalLines) {
        double scale = fontSize / font.rasterPx();
        double lineHeight = font.lineHeightPx() * scale * Math.max(0.4, lineSpacing);
        double descent = font.descentPx() * scale;
        return (totalLines - 1) * lineHeight - descent;
    }
}

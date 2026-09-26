package top.hmjmfabc.projector.common.widget;

import net.minecraft.nbt.CompoundTag;

/**
 * 排行榜控件（用户 ③）：调用任意计分板项（objective），把所有玩家按分数排序。
 *
 * <ul>
 *   <li>可调用<b>任意</b>计分板项；</li>
 *   <li>排序方向可设：由高到低（默认）/ 由低到高；</li>
 *   <li>标题可自定义；</li>
 *   <li><b>标题、序号、玩家名、分数四部分各自独立设置字体与颜色。</b></li>
 * </ul>
 *
 * <p>分数数据是<b>客户端本地读取</b>的（{@code ClientLevel.getScoreboard()}）——
 * 原版本来就会把计分板同步给所有客户端，所以这里不需要新增任何网络包。</p>
 */
public class LeaderboardWidget extends Widget {

    /** 计分板项名称（objective name）。空 = 未设置，渲染成提示。 */
    public String objective = "";
    /** 自定义标题。 */
    public String title = "\u6392\u884c\u699c";
    /** true = 由高到低；false = 由低到高。 */
    public boolean descending = true;
    /** 最多显示多少行。 */
    public int maxRows = 10;

    public boolean showTitle = true;
    public boolean showIndex = true;
    public boolean showScore = true;

    /** 字号（画布单位）。 */
    public double titleSize = 10;
    public double rowSize = 9;
    public double lineSpacing = 1.15;

    /** 四部分各自的颜色（ARGB）。 */
    public int titleColor = 0xFFFFD479;
    public int indexColor = 0xFF9AA0B0;
    public int nameColor = 0xFFE8E8F0;
    public int scoreColor = 0xFF7FD4FF;

    /** 四部分各自的字体 ID（用户要求「分别更换字体」）。 */
    public String titleFontId = Fonts.CAVIAR_DREAMS;
    public String indexFontId = Fonts.CAVIAR_DREAMS;
    public String nameFontId = Fonts.CAVIAR_DREAMS;
    public String scoreFontId = Fonts.CAVIAR_DREAMS;

    @Override
    public int kind() {
        return KIND_LEADERBOARD;
    }

    @Override
    public String label() {
        return "\u6392\u884c\u699c(" + (objective == null || objective.isEmpty() ? "\u672a\u8bbe\u7f6e" : objective) + ")";
    }

    /** 行数上限的安全取值。 */
    public int safeMaxRows() {
        return maxRows < 1 ? 1 : Math.min(maxRows, 64);
    }

    /** 【①】本控件引用的字体（供联机字体核验使用）。 */
    @Override
    public java.util.List<String> fontIds() {
        return java.util.List.of(
                titleFontId, indexFontId, nameFontId, scoreFontId);
    }

    @Override
    protected void saveExtra(CompoundTag t) {
        t.putString("objective", objective == null ? "" : objective);
        t.putString("title", title == null ? "" : title);
        t.putBoolean("desc", descending);
        t.putInt("maxRows", maxRows);
        t.putBoolean("showTitle", showTitle);
        t.putBoolean("showIndex", showIndex);
        t.putBoolean("showScore", showScore);
        t.putDouble("titleSize", titleSize);
        t.putDouble("rowSize", rowSize);
        t.putDouble("lineSpacing", lineSpacing);
        t.putInt("titleColor", titleColor);
        t.putInt("indexColor", indexColor);
        t.putInt("nameColor", nameColor);
        t.putInt("scoreColor", scoreColor);
        t.putString("titleFont", titleFontId);
        t.putString("indexFont", indexFontId);
        t.putString("nameFont", nameFontId);
        t.putString("scoreFont", scoreFontId);
    }

    @Override
    public void loadExtra(CompoundTag t) {
        objective = t.contains("objective") ? t.getString("objective") : "";
        title = t.contains("title") ? t.getString("title") : "\u6392\u884c\u699c";
        descending = !t.contains("desc") || t.getBoolean("desc");
        maxRows = t.contains("maxRows") ? t.getInt("maxRows") : 10;
        showTitle = !t.contains("showTitle") || t.getBoolean("showTitle");
        showIndex = !t.contains("showIndex") || t.getBoolean("showIndex");
        showScore = !t.contains("showScore") || t.getBoolean("showScore");
        titleSize = t.contains("titleSize") ? t.getDouble("titleSize") : 10;
        rowSize = t.contains("rowSize") ? t.getDouble("rowSize") : 9;
        lineSpacing = t.contains("lineSpacing") ? t.getDouble("lineSpacing") : 1.15;
        titleColor = t.contains("titleColor") ? t.getInt("titleColor") : 0xFFFFD479;
        indexColor = t.contains("indexColor") ? t.getInt("indexColor") : 0xFF9AA0B0;
        nameColor = t.contains("nameColor") ? t.getInt("nameColor") : 0xFFE8E8F0;
        scoreColor = t.contains("scoreColor") ? t.getInt("scoreColor") : 0xFF7FD4FF;
        titleFontId = t.contains("titleFont") ? t.getString("titleFont") : Fonts.CAVIAR_DREAMS;
        indexFontId = t.contains("indexFont") ? t.getString("indexFont") : Fonts.CAVIAR_DREAMS;
        nameFontId = t.contains("nameFont") ? t.getString("nameFont") : Fonts.CAVIAR_DREAMS;
        scoreFontId = t.contains("scoreFont") ? t.getString("scoreFont") : Fonts.CAVIAR_DREAMS;
    }
}

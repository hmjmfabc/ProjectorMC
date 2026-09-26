package top.hmjmfabc.projector.common.widget;

import net.minecraft.nbt.CompoundTag;

/**
 * 天气控件：小图标 + 文字，例如「☀️ 晴天」。
 *
 * <p>天气与图标全部由客户端根据所在维度的天气状态实时计算，不需要同步数据。
 * 图标使用模组内置的小型纹理（assets/projector/textures/widget/weather_*.png），
 * 与文字分别绘制，避免依赖系统 emoji 字体。</p>
 */
public class WeatherWidget extends Widget {

    public String fontId = Fonts.CAVIAR_DREAMS;
    public double fontSize = 12;
    public int color = 0xFFFFFFFF;
    public boolean showText = true;
    public boolean showIcon = true;
    /** 图标占控件高度的比例。 */
    public double iconScale = 0.85;
    public boolean shadow = true;
    /** 是否显示维度名（如「主世界 晴天」）。 */
    public boolean showDimension;

    @Override
    public int kind() {
        return KIND_WEATHER;
    }

    @Override
    public String label() {
        return "\u5929\u6c14";
    }

    /** 【①】本控件引用的字体（供联机字体核验使用）。 */
    @Override
    public java.util.List<String> fontIds() {
        return java.util.List.of(fontId == null ? "" : fontId);
    }

    @Override
    protected void saveExtra(CompoundTag t) {
        t.putString("font", fontId);
        t.putDouble("fs", fontSize);
        t.putInt("color", color);
        t.putBoolean("showText", showText);
        t.putBoolean("showIcon", showIcon);
        t.putDouble("iconScale", iconScale);
        t.putBoolean("shadow", shadow);
        t.putBoolean("showDim", showDimension);
    }

    @Override
    public void loadExtra(CompoundTag t) {
        fontId = t.contains("font") ? t.getString("font") : Fonts.CAVIAR_DREAMS;
        fontSize = t.contains("fs") ? t.getDouble("fs") : 12;
        color = t.contains("color") ? t.getInt("color") : 0xFFFFFFFF;
        showText = !t.contains("showText") || t.getBoolean("showText");
        showIcon = !t.contains("showIcon") || t.getBoolean("showIcon");
        iconScale = t.contains("iconScale") ? t.getDouble("iconScale") : 0.85;
        shadow = !t.contains("shadow") || t.getBoolean("shadow");
        showDimension = t.getBoolean("showDim");
    }
}

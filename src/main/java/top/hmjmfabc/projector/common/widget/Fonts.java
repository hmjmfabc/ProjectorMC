package top.hmjmfabc.projector.common.widget;

/** 内置字体 ID 常量。 */
public final class Fonts {

    /** 默认字体：Minecraft AE（模组内置 minecraft.ttf）。 */
    public static final String MINECRAFT_AE = "minecraft_ae";
    /** 第二种内置字体：Caviar Dreams（百分比控件强制使用）。 */
    public static final String CAVIAR_DREAMS = "caviar_dreams";
    /** 客户端自行导入的字体的前缀，完整 ID 形如 {@code custom:myfont}。 */
    public static final String CUSTOM_PREFIX = "custom:";

    public static String custom(String name) {
        return CUSTOM_PREFIX + name;
    }

    public static boolean isCustom(String fontId) {
        return fontId != null && fontId.startsWith(CUSTOM_PREFIX);
    }

    public static String customName(String fontId) {
        return isCustom(fontId) ? fontId.substring(CUSTOM_PREFIX.length()) : fontId;
    }

    private Fonts() {
    }
}

package top.hmjmfabc.projector.common.game;

/** 【⑪】三种对战模式。 */
public final class GameMode {

    /** 玩家 VS AI。 */
    public static final int PVE = 0;
    /** 玩家 VS 玩家（同一台机器上轮流操作）。 */
    public static final int PVP = 1;
    /** 斗蛐蛐：AI VS AI。 */
    public static final int AI_VS_AI = 2;

    public static final String[] NAMES = {
            // 注意：蛐 = U+86D0。曾经误写成 U+8717（蜗），界面上显示成「斗蜗蜗」。
            "\u4eba\u673a\u5bf9\u6218", "\u53cc\u4eba\u5bf9\u6218", "\u6597\u86d0\u86d0\uff08AI vs AI\uff09"};

    private GameMode() {
    }

    public static String name(int mode) {
        return mode < 0 || mode >= NAMES.length ? NAMES[0] : NAMES[mode];
    }

    public static int clamp(int mode) {
        return mode < 0 || mode >= NAMES.length ? PVE : mode;
    }
}

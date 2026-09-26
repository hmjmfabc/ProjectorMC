package top.hmjmfabc.projector.common.game;

/**
 * 【⑪】棋类游戏的定义。
 *
 * <p>编号会写进控件存档，<b>只能往后追加，绝不能改动已有编号</b>，
 * 否则旧存档里的棋局会突然变成另一种棋。</p>
 *
 * <h2>为什么没有「军棋」</h2>
 * <p>用户已要求删除军棋：Projector 的平面是<b>所有人共享的一块公共屏幕</b>，
 * 而军棋的核心玩法是<b>暗棋</b>（双方看不到对方棋子的种类）——
 * 要做到这一点必须给每个玩家各自一份视角，这在共享平面上无法实现。
 * 编号 4 因此<b>保留为空号</b>（不回收再利用），这样旧存档里的军棋控件
 * 会被安全地识别为「已移除」而不是变成另一种棋。</p>
 */
public final class GameKind {

    public static final int TIC_TAC_TOE = 0;
    public static final int GOMOKU = 1;
    public static final int XIANGQI = 2;
    public static final int GO = 3;
    /** 【空号】原「军棋」，已按用户要求删除，编号不回收。 */
    public static final int RETIRED_JUNQI = 4;
    public static final int CHESS = 5;

    /** 索引必须与上面的编号一一对应（含空号）。 */
    private static final String[] NAMES = {
            "\u4e95\u5b57\u68cb", "\u4e94\u5b50\u68cb", "\u8c61\u68cb",
            "\u56f4\u68cb", "\uff08\u5df2\u79fb\u9664\uff09", "\u56fd\u9645\u8c61\u68cb"};

    /** 界面上「切换棋种」要按这个顺序走（跳过空号）。 */
    public static final int[] PLAYABLE = {TIC_TAC_TOE, GOMOKU, XIANGQI, GO, CHESS};

    private GameKind() {
    }

    /**
     * 棋种名字。
     *
     * <p><b>刻意不走 {@link #clamp}</b>：空号（已移除的军棋）要如实显示成「（已移除）」，
     * 而不是被伪装成井字棋——日志里看到「（已移除）」才知道那是旧存档里的军棋控件，
     * 看到「井字棋」只会让人以为玩家真的在下井字棋。</p>
     */
    public static String name(int kind) {
        if (kind < 0 || kind >= NAMES.length) return NAMES[TIC_TAC_TOE];
        return NAMES[kind];
    }

    public static boolean isPlayable(int kind) {
        for (int k : PLAYABLE) {
            if (k == kind) return true;
        }
        return false;
    }

    /** 把任意编号夹到「可玩」的棋种上（旧存档里的军棋会回落到井字棋）。 */
    public static int clamp(int kind) {
        return isPlayable(kind) ? kind : TIC_TAC_TOE;
    }

    /** 切换棋种：返回下一个可玩的棋种。 */
    public static int next(int kind) {
        int cur = clamp(kind);
        for (int i = 0; i < PLAYABLE.length; i++) {
            if (PLAYABLE[i] == cur) {
                return PLAYABLE[(i + 1) % PLAYABLE.length];
            }
        }
        return TIC_TAC_TOE;
    }
}

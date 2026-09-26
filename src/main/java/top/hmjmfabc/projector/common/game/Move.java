package top.hmjmfabc.projector.common.game;

/**
 * 【⑪】一个着法。
 *
 * @param fx 起点 x；<b>落子类棋（井字棋/五子棋/围棋）用 -1</b> 表示「从手里直接落到目标格」
 * @param fy 起点 y（同上）
 * @param tx 目标 x
 * @param ty 目标 y
 * @param piece 出手的棋子编码（走子类棋用它区分是哪一枚在动；落子类传 0 即可）
 */
public record Move(int fx, int fy, int tx, int ty, int piece) {

    /** 落子（井字棋 / 五子棋 / 围棋）。 */
    public static Move drop(int x, int y) {
        return new Move(-1, -1, x, y, 0);
    }

    /** 走子（象棋 / 军棋 / 国际象棋）。 */
    public static Move step(int fx, int fy, int tx, int ty, int piece) {
        return new Move(fx, fy, tx, ty, piece);
    }

    /** 是不是「落子」而不是「走子」。 */
    public boolean isDrop() {
        return fx < 0 || fy < 0;
    }

    @Override
    public String toString() {
        return isDrop() ? ("->" + tx + "," + ty) : (fx + "," + fy + "->" + tx + "," + ty);
    }
}

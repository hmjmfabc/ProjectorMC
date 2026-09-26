package top.hmjmfabc.projector.common.game;

import java.util.List;

/**
 * 【⑪】棋类游戏的统一框架。
 *
 * <p>六种棋（井字棋 / 五子棋 / 中国象棋 / 围棋 / 军棋 / 国际象棋）共用一个
 * 「棋盘是 int 数组 + 规则实现」的模型，好处是 AI、存档、渲染、界面全都只写一份：</p>
 * <ul>
 *   <li><b>棋盘</b>：一维 {@code int[]}，索引 = {@code y * width + x}；</li>
 *   <li><b>棋子编码</b>：{@code 0} = 空；{@code >0} 先手方、{@code <0} 后手方，
 *       绝对值是棋子种类（各棋自己定义）；</li>
 *   <li><b>着法</b>：{@link Move}。落子类（井字棋/五子棋/围棋）用
 *       {@code fx=fy=-1} 表示「从手里落到 (tx,ty)」，走子类用全部四个坐标。</li>
 * </ul>
 *
 * <p><b>对称性约定</b>：所有实现都必须对「正负号」一视同仁——
 * 先手方是正数、后手方是负数，任何方向性逻辑都只能用符号而不是具体的棋子编号，
 * 否则 AI 执后手时会立刻下出臭棋（这是这类代码最常见的 bug）。</p>
 */
public interface GameRules {

    /** 游戏种类，见 {@link GameKind}。 */
    int kind();

    int width();

    int height();

    /** 初始棋盘（返回一份新数组，调用方可以随便改）。 */
    int[] initialBoard();

    /** 生成 {@code side}（+1 或 -1）的全部合法着法。 */
    List<Move> moves(int[] board, int side);

    /**
     * 落子/走子，返回新局面；<b>不得修改入参</b>。
     *
     * @param side 这一手是谁下的（+1 先手 / -1 后手）。
     *             <b>必须显式传入，不能从 Move 里猜</b>——否则 AI 执后手时
     *             会把子下成对方的颜色（这类 bug 在棋盘代码里非常常见）。
     */
    int[] apply(int[] board, Move m, int side);

    /**
     * 判定结果。
     *
     * @param side               轮到哪一方（用于「无子可走判负」）
     * @param movesWithoutProgress 连续多少手没有吃子/没有推进（用于判和，调用方维护）
     * @return 0=进行中、1=先手胜、-1=后手胜、2=和棋
     */
    int result(int[] board, int side, int movesWithoutProgress);

    /** 站在 {@code side} 角度的局面评估，越大越好。单位是「任意分值」，只用于同一棋种内比较。 */
    int evaluate(int[] board, int side);

    /** 棋子显示成什么字符（渲染与界面共用）。 */
    String glyph(int piece);

    /**
     * 棋子落在**交叉点**上还是**格子中间**。
     *
     * <p>这是两类棋盘的画法差异，搞错就会像「谁家象棋在格子中间」那样离谱：</p>
     * <ul>
     *   <li>{@code true}（交叉点）：<b>围棋、五子棋、中国象棋、军棋</b> ——
     *       棋盘是若干条横竖线，棋子摆在<b>线的交点</b>上；</li>
     *   <li>{@code false}（格心）：<b>井字棋、国际象棋</b> ——
     *       棋子摆在格子里，棋盘画成棋盘格。</li>
     * </ul>
     */
    default boolean piecesOnIntersections() {
        return false;
    }

    /** 这个棋种在「斗蛐蛐」循环里，一手棋最多允许 AI 思考多少毫秒。 */
    default long aiThinkMs() {
        return 250;
    }

    // ------------------------------------------------------------------
    // 通用工具（所有实现都可以直接用）
    // ------------------------------------------------------------------

    static int idx(int w, int x, int y) {
        return y * w + x;
    }

    static boolean inside(int w, int h, int x, int y) {
        return x >= 0 && y >= 0 && x < w && y < h;
    }

    static int at(int[] b, int w, int h, int x, int y) {
        return inside(w, h, x, y) ? b[idx(w, x, y)] : Integer.MIN_VALUE;
    }

    /** 棋盘上的棋子总数（用于判断「是不是空盘」之类）。 */
    static int countPieces(int[] b) {
        int n = 0;
        for (int v : b) {
            if (v != 0) n++;
        }
        return n;
    }

    /** 某方的棋子总「力量」（按绝对值求和）——很多评估函数都从它开始。 */
    static int material(int[] b, int side) {
        int sum = 0;
        for (int v : b) {
            if (v != 0 && Integer.signum(v) == side) sum += Math.abs(v);
        }
        return sum;
    }
}

package top.hmjmfabc.projector.common.game;

/**
 * 【⑪】AI 智能档位。
 *
 * <p>用户反馈「AI 太聪明了打不赢」，所以做成三档，并<b>把原来的逻辑定为「高」</b>：</p>
 * <table border="1">
 *   <tr><th>档位</th><th>搜索深度</th><th>时间预算</th><th>会不会失误</th><th>适合谁</th></tr>
 *   <tr><td>{@link #NORMAL 正常}</td><td>2 层</td><td>约 0.4×</td>
 *       <td><b>会</b>：35% 的概率不走最优手，而是在前几名里挑一个</td>
 *       <td>想赢、想轻松下</td></tr>
 *   <tr><td>{@link #HIGH 高}（默认）</td><td>4 层</td><td>1×</td><td>不会</td>
 *       <td>就是之前那一版</td></tr>
 *   <tr><td>{@link #EXTREME 极限}</td><td>6 层 + 威胁延伸</td><td>约 3×</td>
 *       <td>不会，而且会主动算对手的杀棋</td>
 *       <td>找虐</td></tr>
 * </table>
 *
 * <p>「极限」相比「高」多做了三件事：①深度从 4 提到 6；②时间与节点上限放宽；
 * ③<b>威胁延伸</b>——搜索时如果发现对手下一手就能连成五子/吃王，
 * 就额外多搜一层去看「我堵了之后他还成不成」，这是棋力提升最明显的一招。
 * 内存方面仍然守住「节点数上限」这条闸，不会因为档位变高而失控。</p>
 */
public final class GameDifficulty {

    public static final int NORMAL = 0;
    public static final int HIGH = 1;
    public static final int EXTREME = 2;

    public static final String[] NAMES = {
            "\u6b63\u5e38", "\u9ad8\uff08\u9ed8\u8ba4\uff09", "\u6781\u9650"};

    private GameDifficulty() {
    }

    public static String name(int d) {
        return d < 0 || d >= NAMES.length ? NAMES[HIGH] : NAMES[d];
    }

    public static int clamp(int d) {
        return d < 0 || d > EXTREME ? HIGH : d;
    }

    /** 下一个档位（界面上的循环按钮用）。 */
    public static int next(int d) {
        int c = clamp(d);
        return c >= EXTREME ? NORMAL : c + 1;
    }

    /** 该档位允许的最大搜索深度（还会被棋种自身的上限压一次）。 */
    public static int maxDepth(int d, int kindDepth) {
        return switch (clamp(d)) {
            case NORMAL -> Math.min(2, kindDepth);
            case EXTREME -> Math.max(kindDepth, switch (kindDepth) {
                case GameKind.TIC_TAC_TOE -> 9;      // 局面太小，直接搜到底
                case GameKind.GO -> 3;
                default -> 6;
            });
            default -> kindDepth;
        };
    }

    /** 时间预算倍率：正常档思考更快（也更弱），极限档愿意思考更久。 */
    public static double timeScale(int d) {
        return switch (clamp(d)) {
            case NORMAL -> 0.4;
            case EXTREME -> 3.0;
            default -> 1.0;
        };
    }

    /** 节点数上限倍率（内存闸门）。 */
    public static double nodeScale(int d) {
        return switch (clamp(d)) {
            case NORMAL -> 0.15;
            case EXTREME -> 4.0;
            default -> 1.0;
        };
    }

    /** 「会失误」档位下，这一手不走最优手的概率。 */
    public static double blunderChance(int d) {
        return clamp(d) == NORMAL ? 0.35 : 0.0;
    }

    /** 是否开启「威胁延伸」（极限档专属）。 */
    public static boolean threatExtension(int d) {
        return clamp(d) == EXTREME;
    }
}

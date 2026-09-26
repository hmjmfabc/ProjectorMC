package top.hmjmfabc.projector.common.game;

import java.util.List;

/**
 * 【⑪】棋类 AI：带 alpha-beta 剪枝的迭代加深搜索。
 *
 * <h2>为什么必须限深限时</h2>
 * <p>硬性约束是「任何操作 RAM 不得超过 3500 MB」「Mod 负载不能过高」，
 * 而棋盘搜索的节点数是<b>指数</b>增长的：五子棋 15x15 的着法数上百，
 * 深 5 层就是上百亿节点。所以这里同时设了三道闸：</p>
 * <ol>
 *   <li>{@link #MAX_DEPTH} 深度上限；</li>
 *   <li>{@link #MAX_NODES} 节点数上限（到顶立刻返回当前最好解）；</li>
 *   <li>每一层算完检查一次时间预算（{@code rules.aiThinkMs()}），
 *       超时就<<b>用上一层已经算完的结果</b>——这正是「迭代加深」最实用的地方。</li>
 * </ol>
 * <p>搜索过程只分配一个小数组（着法列表）且逐个深层递归复用，
 * 因此内存占用是「深度 × 每层着法数」，与棋种规模无关。</p>
 *
 * <h2>智能档位（用户要求做成三档）</h2>
 * <p>见 {@link GameDifficulty}：正常（会失误、搜得浅）/ 高（默认，就是原来那一版）/
 * 极限（更深 + 威胁延伸）。档位只影响「搜多深、想多久、以及要不要故意不走最优手」，
 * 三道闸（深度/节点/时间）在任何档位下都生效。</p>
 *
 * <h2>它下得怎么样</h2>
 * <p>象棋/国际象棋用「子力价值 + 机动性」评估，深度 3~4；
 * 五子棋用「活口形状打分」，深度 4 但带强剪枝；
 * 围棋用「子 + 围空」，因为围棋的评估函数在浅层搜索下意义有限，
 * 所以深度压到 2 并偏向「能提子/能连片」的着法。
 * 目标不是打赢人类高手，而是<b>在服务端/客户端的算力预算内下出合理的一手</b>。</p>
 */
public final class GameAi {

    /** 深度上限。 */
    public static final int MAX_DEPTH = 4;
    /** 单次搜索的节点数上限。 */
    public static final int MAX_NODES = 120_000;

    /** 一次搜索结果。 */
    public record Choice(Move move, int score, int depth, int nodes, boolean timedOut) {
    }

    private final GameRules rules;
    /** 智能档位，见 {@link GameDifficulty}。 */
    private final int difficulty;
    private int nodes;
    private int nodeCap;
    private long deadline;
    private boolean timedOut;
    /** 极限档的「威胁延伸」：记录上一层是否发现了对手的致命威胁。 */
    private boolean threatSeen;

    public GameAi(GameRules rules) {
        this(rules, GameDifficulty.HIGH);
    }

    public GameAi(GameRules rules, int difficulty) {
        this.rules = rules;
        this.difficulty = GameDifficulty.clamp(difficulty);
    }

    /**
     * 为 {@code side} 选一手棋。
     *
     * @return 选中的着法；没有合法着法时返回 null
     */
    public Choice choose(int[] board, int side) {
        return choose(board, side, -1);
    }

    /**
     * 为 {@code side} 选一手棋，并<b>排除会话层禁止的落点</b>（围棋的简化劫争）。
     *
     * <p><b>为什么 AI 必须知道禁令：</b>{@code GameRules.moves()} 是纯函数、拿不到会话状态，
     * 所以它生成的候选里<b>包含</b>被劫争禁令挡住的落点。AI 一旦选中它，
     * {@code GameSession.play()} 会拒绝，而调用方通常会拿同一手再问一遍 —— 死循环。
     * 因此禁令必须由调用方（{@link GameSession#bannedDropIndex()}）传进来，
     * 在<b>根节点</b>就把它从候选里剔除；搜索内部不需要（内部是对局面的假设推演，
     * 不承担会话层的劫争状态）。</p>
     *
     * @param bannedDrop 禁止落子的点位下标（{@code ty * width + tx}）；{@code -1} = 无禁令
     */
    public Choice choose(int[] board, int side, int bannedDrop) {
        List<Move> roots = rules.moves(board, side);
        if (bannedDrop >= 0) {
            int w = rules.width();
            roots.removeIf(m -> m.isDrop() && m.ty() * w + m.tx() == bannedDrop);
        }
        if (roots.isEmpty()) return null;
        nodes = 0;
        timedOut = false;
        nodeCap = (int) Math.max(2_000, MAX_NODES * GameDifficulty.nodeScale(difficulty));
        deadline = System.currentTimeMillis()
                + (long) Math.max(60.0, rules.aiThinkMs() * GameDifficulty.timeScale(difficulty));

        Move best = roots.get(0);
        int bestScore = Integer.MIN_VALUE;
        int reachedDepth = 0;
        // 收集「最后一轮算完」的根着法评分，供「正常档故意失误」使用
        java.util.List<int[]> lastScores = new java.util.ArrayList<>();

        int maxDepth = GameDifficulty.maxDepth(difficulty, depthFor());
        for (int depth = 1; depth <= maxDepth; depth++) {
            Move iterBest = null;
            int iterScore = Integer.MIN_VALUE;
            int alpha = Integer.MIN_VALUE + 1;
            // 把上一轮的最好着法排到最前面：alpha-beta 的剪枝效率几乎全靠着法排序
            if (best != null) {
                roots.remove(best);
                roots.add(0, best);
            }
            java.util.List<int[]> roundScores = new java.util.ArrayList<>();
            int idx = 0;
            for (Move m : roots) {
                int[] nb = rules.apply(board, m, side);
                threatSeen = false;
                int sc = -negamax(nb, -side, depth - 1, -Integer.MAX_VALUE + 1, -alpha, 1);
                roundScores.add(new int[]{idx, sc});
                if (sc > iterScore) {
                    iterScore = sc;
                    iterBest = m;
                }
                if (sc > alpha) alpha = sc;
                if (timedOut || nodes >= nodeCap) break;
                idx++;
            }
            if (iterBest != null) {
                best = iterBest;
                bestScore = iterScore;
                reachedDepth = depth;
                lastScores = roundScores;
            }
            if (timedOut || nodes >= nodeCap) break;
            // 已经算到必胜/必败就不必再深了
            if (Math.abs(bestScore) >= 1_000_000) break;
        }
        // ---- 「正常」档：按概率故意不走最优手 ----
        // 关键：**只在「好几手分数接近」时**才失误。如果最优手是唯一能救命的
        // （分数明显高于其它），再弱也不能乱走——否则 AI 会蠢到不像在下棋。
        double blunder = GameDifficulty.blunderChance(difficulty);
        if (blunder > 0 && lastScores.size() >= 3 && Math.random() < blunder) {
            lastScores.sort((x, y) -> Integer.compare(y[1], x[1]));
            int bestSc = lastScores.get(0)[1];
            java.util.List<Integer> close = new java.util.ArrayList<>();
            for (int i = 1; i < lastScores.size() && i <= 3; i++) {
                // 分数落差在「一个子的价值」以内（1000 分）才算「差不多」
                if (bestSc - lastScores.get(i)[1] <= 1000) close.add(lastScores.get(i)[0]);
            }
            if (!close.isEmpty()) {
                int pick = close.get((int) (Math.random() * close.size()));
                if (pick >= 0 && pick < roots.size()) {
                    best = roots.get(pick);
                    bestScore = bestSc - 1;
                }
            }
        }
        return new Choice(best, bestScore == Integer.MIN_VALUE ? 0 : bestScore, reachedDepth, nodes, timedOut);
    }

    /** 不同棋种的搜索深度：着法越多、评估越粗糙的，就搜得浅一些。 */
    private int depthFor() {
        return switch (rules.kind()) {
            case GameKind.TIC_TAC_TOE -> 9;        // 局面极小，可以搜到底
            case GameKind.GOMOKU -> 4;
            case GameKind.GO -> 2;
            default -> 4;                          // 象棋 / 国际象棋
        };
    }

    /**
     * 负极大值搜索。
     *
     * <p>用 negamax 而不是「最大/最小各写一遍」，是因为棋盘代码里最容易错的就是
     * 「把某一方的评估符号写反」——negamax 只有一个取负号的地方，
     * 想写错都难。</p>
     */
    private int negamax(int[] board, int side, int depth, int alpha, int beta, int ply) {
        nodes++;
        if ((nodes & 0x3F) == 0) {
            // 每 64 个节点看一次时钟：比每个节点都调 currentTimeMillis 便宜得多
            if (System.currentTimeMillis() > deadline || nodes >= nodeCap) {
                timedOut = true;
                return rules.evaluate(board, side);
            }
        }
        int res = rules.result(board, side, 0);
        if (res != 0) {
            if (res == 2) return 0;
            // 越早获胜分越高，避免 AI「拖着不赢」
            return res == side ? (1_000_000 - ply * 100) : (-1_000_000 + ply * 100);
        }
        if (depth <= 0) {
            // 【极限档的威胁延伸】到了深度下限时再看一眼：
            // 如果这一手下去之后「对手立刻就能赢」（对手存在一步制胜的着法），
            // 那就不能在这里停——多搜一层，去看看我堵住之后他还成不成。
            // 这是棋力提升最明显的一招，而且只在真的出现威胁时才多花时间。
            if (GameDifficulty.threatExtension(difficulty) && !threatSeen && ply < 10
                    && opponentHasImmediateWin(board, side)) {
                threatSeen = true;
                int ext = -negamax(board, side, 2, alpha, beta, ply);
                threatSeen = false;
                return ext;
            }
            return rules.evaluate(board, side);
        }
        // 着法只生成一次：result() 里刻意不做「无棋可走」判定，
        // 就是为了不让每个节点白白生成两遍（见 GameRules.result 的注释）。
        List<Move> moves = rules.moves(board, side);
        if (moves.isEmpty()) {
            return -1_000_000 + ply * 100;   // 无棋可走 = 输
        }
        orderMoves(board, moves, side);
        int bestScore = Integer.MIN_VALUE + 1;
        for (Move m : moves) {
            int[] nb = rules.apply(board, m, side);
            int sc = -negamax(nb, -side, depth - 1, -beta, -alpha, ply + 1);
            if (sc > bestScore) bestScore = sc;
            if (sc > alpha) alpha = sc;
            if (alpha >= beta) break;        // 剪枝
            if (timedOut) break;
        }
        return bestScore;
    }

    /** 对手（{@code -side}）是不是存在「一步就能赢」的着法。 */
    private boolean opponentHasImmediateWin(int[] board, int side) {
        java.util.List<Move> opp = rules.moves(board, -side);
        // 着法太多时不做这个检查（围棋/五子棋中盘可能上百手），避免反而变慢
        if (opp.size() > 80) return false;
        for (Move m : opp) {
            int[] nb = rules.apply(board, m, -side);
            if (rules.result(nb, side, 0) == -side) return true;
        }
        return false;
    }

    /**
     * 着法排序：先看吃子，吃到的子越大越靠前。
     *
     * <p>只做这一条就已经能砍掉绝大部分搜索量——这就是「着法排序决定剪枝效率」。</p>
     */
    private void orderMoves(int[] board, List<Move> moves, int side) {
        int w = rules.width();
        moves.sort((a, b) -> score(board, w, b, side) - score(board, w, a, side));
    }

    private int score(int[] board, int w, Move m, int side) {
        int gain = 0;
        if (!m.isDrop()) {
            int target = board[m.ty() * w + m.tx()];
            if (target != 0 && Integer.signum(target) != side) {
                gain += 1000 + Math.abs(target);
            }
        }
        return gain;
    }
}

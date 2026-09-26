package top.hmjmfabc.projector.common.game;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;
import top.hmjmfabc.projector.Projector;

import java.util.List;
import java.util.UUID;

/**
 * 【⑪】一局棋的完整状态机（可以整体存进控件的 NBT）。
 *
 * <h2>职责</h2>
 * <ul>
 *   <li>保存棋盘、轮到谁、结果、手数；</li>
 *   <li>接收玩家落子（{@link #humanMove}）；</li>
 *   <li>为 AI 提供「现在该不该动、动哪一手」——但<b>不自己开线程</b>，
 *       由界面在主线程之外驱动（见 {@code ChessGameScreen}）。</li>
 * </ul>
 *
 * <h2>斗蛐蛐（AI vs AI）循环与内存</h2>
 * <p>用户特别提醒「小心内存泄露」。这里的做法是：</p>
 * <ul>
 *   <li>状态机是<b>纯值对象</b>（一个 int 数组 + 几个 int），每手棋产生的新数组
 *       由旧数组直接丢弃，没有任何监听器、回调、线程或缓存表；</li>
 *   <li>AI 搜索<b>不开线程</b>，而是在界面给的执行器上跑一次性的任务，
 *       任务结束后不保留任何引用；</li>
 *   <li>{@link #stopLoop()} 会把循环开关关掉，界面在关闭时<b>必须调用它</b>——
 *       否则一个看不见的界面还会每秒算一次 AI。</li>
 * </ul>
 */
public final class GameSession {

    /** 单局硬性手数上限（约 = 双方各 300 手），到顶判和。 */
    public static final int MAX_MOVES = 600;

    /** 双方：+1 先手、-1 后手。 */
    public static final int SIDE_A = 1;
    public static final int SIDE_B = -1;

    public int kind = GameKind.TIC_TAC_TOE;
    public int mode = GameMode.PVE;

    /** 棋盘：{@code y * width + x}。 */
    public int[] board = new int[0];
    /** 轮到谁。 */
    public int turn = SIDE_A;
    /** 0=进行中、1=A 胜、-1=B 胜、2=和棋。 */
    public int result;

    public int moveCount;
    /** 连续多少手没有吃子（用于判和）。 */
    public int quietMoves;
    /** 上一次被吃掉的子的位置，用于「吃过路兵」与「劫争」判断。 */
    public int lastFrom = -1;
    public int lastTo = -1;
    /** 记录最后两手的落点，用于围棋劫争禁用。 */
    public int prevTo = -1;

    /** 被吃掉的子数（双方各自的战果，仅用于显示）。 */
    public int capturedByA;
    public int capturedByB;

    /** 斗蛐蛐：是否循环进行。 */
    public boolean loopEnabled;
    /** 已经打了多少局（斗蛐蛐循环计数）。 */
    public int roundsPlayed;
    public int roundsWonByA;
    public int roundsWonByB;

    /** 【⑪】AI 智能档位，见 {@link GameDifficulty}。默认「高」（与之前的行为一致）。 */
    public int difficulty = GameDifficulty.HIGH;

    /** 走棋方是不是 AI（由界面判断「该不该替我落子」）。 */
    public boolean isAiTurn() {
        if (result != 0) return false;
        if (mode == GameMode.AI_VS_AI) return true;
        // 人机对战：AI 执后手
        return mode == GameMode.PVE && turn == SIDE_B;
    }

    /** 这一方是不是 AI（用于界面提示「AI 思考中」）。 */
    public boolean isAiSide(int side) {
        if (mode == GameMode.AI_VS_AI) return true;
        return mode == GameMode.PVE && side == SIDE_B;
    }

    public GameRules rules() {
        return GameRulesFactory.create(kind);
    }

    public int width() {
        return rules().width();
    }

    public int height() {
        return rules().height();
    }

    // ------------------------------------------------------------------
    // 开局 / 落子
    // ------------------------------------------------------------------

    /** 重新开局（保留棋种与模式）。 */
    public void reset() {
        GameRules r = rules();
        board = r.initialBoard();
        turn = SIDE_A;
        result = 0;
        moveCount = 0;
        quietMoves = 0;
        lastFrom = -1;
        lastTo = -1;
        prevTo = -1;
        capturedByA = 0;
        capturedByB = 0;
    }

    /**
     * 把另一份棋局的全部字段拷到本对象上（<b>保留本对象的身份</b>）。
     *
     * <p><b>为什么必须原地拷：</b>服务端每次广播平面，客户端都会重新解析出一份棋局。
     * 如果直接 {@code this.game = 新对象}，那么「已经把这一手提交上去、
     * 正等工作线程算完 AI」的那个任务就会在结果回来时发现
     * {@code widget.game != 它当初算的那份局面}，于是<b>把自己的结果丢掉</b>。
     * 表现出来就是「人类下完 AI 不动，必须再点一下棋盘」。
     * 这与 {@code Plane.applyFrom} 里 widgets 的处理是同一类问题
     * （AGENTS.md §5.5 第 25 条）。</p>
     */
    public void copyFrom(GameSession o) {
        if (o == null) return;
        this.kind = GameKind.clamp(o.kind);
        this.mode = GameMode.clamp(o.mode);
        // 棋盘另起一份数组：渲染线程与 AI 线程都在读，不要让两边看到同一个正在被改的数组
        this.board = o.board == null ? new int[0] : o.board.clone();
        this.turn = o.turn;
        this.result = o.result;
        this.moveCount = o.moveCount;
        this.quietMoves = o.quietMoves;
        this.lastFrom = o.lastFrom;
        this.lastTo = o.lastTo;
        this.prevTo = o.prevTo;
        this.capturedByA = o.capturedByA;
        this.capturedByB = o.capturedByB;
        this.loopEnabled = o.loopEnabled;
        this.roundsPlayed = o.roundsPlayed;
        this.roundsWonByA = o.roundsWonByA;
        this.roundsWonByB = o.roundsWonByB;
        this.difficulty = o.difficulty;
    }

    /** 换棋种。 */
    public void setKind(int newKind) {
        this.kind = GameKind.clamp(newKind);
        reset();
    }

    /**
     * 玩家（或界面）走一手。
     *
     * @return 是否成功
     */
    public boolean humanMove(Move m) {
        return play(m, turn);
    }

    /**
     * 【围棋·简化劫争】当前被禁止落子的点位下标（{@code ty * width + tx}）；{@code -1} = 无禁令。
     *
     * <p><b>为什么必须公开这一份判定：</b>着法生成 {@code GameRules.moves()} 是<b>纯函数</b>，
     * 只看棋盘、看不到会话状态，所以它<b>不知道</b>这条禁令。历史上只有 {@code play()}
     * 知道禁令，于是 AI 会老老实实选中被禁的落点，{@code play()} 拒绝、局面不推进、
     * 调用方拿着同一手再问一遍——围棋 AI 回合就此<b>永久卡死</b>
     * （斗蛐蛐连一局都打不完，人机模式玩家也走不了）。</p>
     *
     * <p>现在 {@code GameAi.choose()} 与 {@code play()} <b>共用这一个方法</b>，
     * 不允许再出现第二份实现（同 AGENTS.md §5.5「同一个东西有两套 = 迟早对不上」）。</p>
     */
    public int bannedDropIndex() {
        return kind == GameKind.GO ? prevTo : -1;
    }

    /** 这一手是不是被劫争禁令挡住的落点。<b>禁令的唯一实现</b>，别处不许再抄一遍。 */
    public boolean isBannedDrop(Move m) {
        int ban = bannedDropIndex();
        return ban >= 0 && m != null && m.isDrop() && m.ty() * width() + m.tx() == ban;
    }

    /** 这一手现在能不能走：合法着法 + 劫争禁令。{@code play()} 与 AI 共用同一份判定。 */
    public boolean canPlay(Move m, int side) {
        if (result != 0 || m == null || side != turn) return false;
        for (Move cand : rules().moves(board, side)) {
            if (sameMove(cand, m)) return !isBannedDrop(cand);
        }
        return false;
    }

    /**
     * 兜底着法：按着法顺序取第一个「现在真的能走」的手。
     *
     * <p>只给 {@link #aiMove()} 在着法被会话拒绝时用——保证局面一定推进，
     * 否则调用方会无限重试同一手。这里用 {@link #canPlay}（与 {@code play()} 同一份判定）
     * 而不是只看禁令：将来若再加别的会话级限制，兜底着法也不会再撞墙。</p>
     */
    @Nullable
    public Move firstPlayable() {
        for (Move m : rules().moves(board, turn)) {
            if (canPlay(m, turn)) return m;
        }
        return null;
    }

    /**
     * 没有「现在真的能下」的着法就收尾（五子棋/围棋按和棋，其余按无子可动判负）。
     *
     * <p><b>为什么不能只看 {@code moves()} 是否为空：</b>围棋唯一的空点若正好被劫争禁令
     * 挡着，{@code moves()} 非空、却<b>谁都无法落子</b> —— 这一局会永远停在那里
     * （AI 选不出手、玩家点了只会得到「不合法」）。这是和「AI 卡死」同一族的状态：
     * 对局无法推进。</p>
     *
     * @return 是否因此收尾
     */
    public boolean settleIfNoPlayableMove() {
        if (result != 0) return false;
        for (Move m : rules().moves(board, turn)) {
            if (!isBannedDrop(m)) return false;
        }
        result = kind == GameKind.GOMOKU || kind == GameKind.GO ? 2 : -turn;
        return true;
    }

    /** 以 {@code side} 的身份走一手，并结算结果。 */
    public boolean play(Move m, int side) {
        if (result != 0 || m == null || side != turn) return false;
        GameRules r = rules();
        // 合法性必须校验：界面可能因为误触传进一个非法着法，
        // 而「吃掉自己的子」「走过河」这类 bug 一旦写进存档就再也解释不清了。
        boolean legal = false;
        for (Move cand : r.moves(board, side)) {
            if (sameMove(cand, m)) {
                legal = true;
                m = cand;
                break;
            }
        }
        if (!legal) return false;
        // 简化版围棋劫争：禁止立刻回到上一手的位置。
        // 【必须与着法生成共用同一份判定】见 bannedDropIndex() 的说明。
        if (isBannedDrop(m)) {
            return false;
        }
        int before = countSide(board, -side);
        board = r.apply(board, m, side);
        int after = countSide(board, -side);
        int captured = Math.max(0, before - after);
        if (side == SIDE_A) capturedByA += captured;
        else capturedByB += captured;
        quietMoves = captured > 0 ? 0 : quietMoves + 1;
        prevTo = lastTo;
        lastFrom = m.isDrop() ? -1 : m.fy() * width() + m.fx();
        lastTo = m.ty() * width() + m.tx();
        moveCount++;
        turn = -side;
        result = r.result(board, turn, quietMoves);
        // 「轮到谁却没棋可走 -> 判负」只在这里判一次（不是每个搜索节点都判）：
        // 这一步需要生成全部着法，放在搜索里会把开销翻倍。
        // 顺带也把五子棋/围棋的「无处可下」收尾成和棋。
        // 【注意是按「可下着法」判断，不是按 moves() 是否为空】见该方法注释。
        settleIfNoPlayableMove();
        // 【安全阀】硬性手数上限：斗蛐蛐是**自动循环**的，
        // 万一某种棋出现双方都不肯推进的死循环，这里必须能刹住，
        // 否则会一直算下去（用户专门提醒过「小心内存泄露」）。
        if (result == 0 && moveCount >= MAX_MOVES) {
            result = 2;
        }
        return true;
    }

    private static boolean sameMove(Move a, Move b) {
        return a.tx() == b.tx() && a.ty() == b.ty() && a.fx() == b.fx() && a.fy() == b.fy();
    }

    private int countSide(int[] b, int side) {
        int n = 0;
        for (int v : b) {
            if (v != 0 && Integer.signum(v) == side) n++;
        }
        return n;
    }

    /**
     * AI 的一手。
     *
     * <p><b>这是个阻塞调用</b>（最多 {@code rules.aiThinkMs()} 毫秒），
     * 调用方必须放在工作线程上，绝不能直接在渲染线程里跑。</p>
     */
    @Nullable
    public GameAi.Choice aiMove() {
        if (result != 0) return null;
        // 一个可下着法都没有（含「唯一空点被劫争禁令挡着」）-> 先收尾，
        // 否则对局会停在「谁都走不了」的状态上（界面上看不出原因）。
        if (settleIfNoPlayableMove()) return null;
        GameRules r = rules();
        GameAi ai = new GameAi(r, difficulty);
        // 【关键】把「劫争禁令」交给着法生成一起考虑：AI 的候选里根本不会出现被禁的落点。
        GameAi.Choice c = ai.choose(board, turn, bannedDropIndex());
        if (c == null || c.move() == null) return c;
        if (play(c.move(), turn)) return c;
        // 走到这里说明「着法生成」与「会话规则」又不一致了（历史上正是劫争禁令）。
        // 【绝不能静默返回】调用方会拿着同一手反复重试，局面永不推进 —— 死循环。
        // 这里改走兜底着法，保证一定推进一步；日志留证据。
        Move fallback = firstPlayable();
        Projector.LOGGER.warn("[Projector] AI 着法被会话拒绝（{}，手数 {}）-> 改走兜底着法 {}",
                GameKind.name(kind), moveCount, fallback == null ? "（无）" : "有");
        if (fallback != null && play(fallback, turn)) {
            return new GameAi.Choice(fallback, c.score(), c.depth(), c.nodes(), c.timedOut());
        }
        // 连兜底都走不了：返回 null 如实表示「这一手没走出去」。
        // 【绝不能把没落地的着法当成功返回】调用方会以为局面动了（当年就是这么死循环的）。
        return null;
    }

    /** 斗蛐蛐循环：一局结束后自动开下一局。 */
    public void nextRoundIfLooping() {
        if (!loopEnabled || result == 0) return;
        roundsPlayed++;
        if (result == 1) roundsWonByA++;
        else if (result == -1) roundsWonByB++;
        reset();
    }

    public void stopLoop() {
        loopEnabled = false;
    }

    /** 结果文本。 */
    public String resultText() {
        return switch (result) {
            case 1 -> "\u5148\u624b\u80dc";
            case -1 -> "\u540e\u624b\u80dc";
            case 2 -> "\u548c\u68cb";
            default -> "";
        };
    }

    public String turnText() {
        if (result != 0) return resultText();
        return (turn == SIDE_A ? "\u5148\u624b" : "\u540e\u624b")
                + (isAiSide(turn) ? "\uff08AI\uff09" : "") + " \u884c\u68cb";
    }

    /** 当前合法着法（界面用来判断某格能不能点）。 */
    public List<Move> legalMoves() {
        return rules().moves(board, turn);
    }

    // ------------------------------------------------------------------
    // 存档
    // ------------------------------------------------------------------

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putInt("kind", kind);
        t.putInt("mode", mode);
        t.putIntArray("board", board == null ? new int[0] : board);
        t.putInt("turn", turn);
        t.putInt("result", result);
        t.putInt("moves", moveCount);
        t.putInt("quiet", quietMoves);
        t.putInt("lastFrom", lastFrom);
        t.putInt("lastTo", lastTo);
        t.putInt("prevTo", prevTo);
        t.putInt("capA", capturedByA);
        t.putInt("capB", capturedByB);
        t.putInt("difficulty", difficulty);
        t.putBoolean("loop", loopEnabled);
        t.putInt("rounds", roundsPlayed);
        t.putInt("winA", roundsWonByA);
        t.putInt("winB", roundsWonByB);
        return t;
    }

    public static GameSession load(CompoundTag t) {
        GameSession g = new GameSession();
        g.kind = GameKind.clamp(t.getInt("kind"));
        g.mode = GameMode.clamp(t.getInt("mode"));
        g.board = t.getIntArray("board");
        g.turn = t.contains("turn") ? t.getInt("turn") : SIDE_A;
        g.result = t.getInt("result");
        g.moveCount = t.getInt("moves");
        g.quietMoves = t.getInt("quiet");
        g.lastFrom = t.getInt("lastFrom");
        g.lastTo = t.getInt("lastTo");
        g.prevTo = t.getInt("prevTo");
        g.capturedByA = t.getInt("capA");
        g.capturedByB = t.getInt("capB");
        g.difficulty = t.contains("difficulty") ? GameDifficulty.clamp(t.getInt("difficulty"))
                : GameDifficulty.HIGH;
        g.loopEnabled = t.getBoolean("loop");
        g.roundsPlayed = t.getInt("rounds");
        g.roundsWonByA = t.getInt("winA");
        g.roundsWonByB = t.getInt("winB");
        // 棋盘尺寸与棋种不匹配（改过版本/手改存档）时直接重开一局，
        // 否则后面每一次 moves() 都会数组越界崩在渲染线程上。
        int expect = GameRulesFactory.create(g.kind).width()
                * GameRulesFactory.create(g.kind).height();
        if (g.board == null || g.board.length != expect) {
            g.reset();
        }
        return g;
    }

    /** 供 UI 显示棋盘用的安全取子。 */
    public int at(int x, int y) {
        int w = width(), h = height();
        if (x < 0 || y < 0 || x >= w || y >= h) return 0;
        int id = y * w + x;
        return id < board.length ? board[id] : 0;
    }

    /** 随机种子/对局 ID（仅用于界面显示，不参与逻辑）。 */
    public UUID id = UUID.randomUUID();
}

package top.hmjmfabc.projector.common.widget;

import net.minecraft.nbt.CompoundTag;
import top.hmjmfabc.projector.Projector;
import top.hmjmfabc.projector.common.game.GameKind;
import top.hmjmfabc.projector.common.game.GameMode;
import top.hmjmfabc.projector.common.game.GameSession;

/**
 * 【⑪】棋类游戏控件：把一局棋摆在平面上，所有人都能看到同一个局面。
 *
 * <p>五种棋（井字棋 / 五子棋 / 象棋 / 围棋 / 国际象棋）共用这一个控件，
 * 棋种与对战模式都存在控件里，可以在编辑界面随时切换。</p>
 *
 * <h2>为什么棋子字体默认用 Minecraft AE</h2>
 * <p>棋子是用文字画的（{@code 车马炮}、{@code ♞♛}、{@code ●○}），
 * 而另一个内置字体 Caviar Dreams <b>没有任何中文字形</b>。
 * 默认用 Minecraft AE，玩家换成别的字体时要自己确认那款字体有对应字形
 * （缺字会画成小方块，一眼就能看出来）。</p>
 *
 * <h2>状态同步</h2>
 * <p>整局棋（{@link GameSession}）都在控件的 NBT 里，因此照常走
 * {@code updateWidget} 通道同步：服务端权威，所有人看到同一盘棋。
 * 对局界面每次操作后把它提交上去，服务端广播回来时按输入框的同一套逻辑覆盖。</p>
 *
 * <h2>乐观落子与「过期回声」</h2>
 * <p>客户端落子后会<b>立刻</b>更新本地棋局并提交，服务端过一会儿才把状态广播回来
 * （就是这里的 {@link #loadExtra}）。如果这份回声还停留在<b>更早</b>的局面，
 * 以前会直接 {@code copyFrom} 把本地棋局整体退回去 —— 这会造成两个后果：
 * 画面上的棋子「弹回去一下」，以及<b>正在工作线程上算的那一手因为手数对不上被丢弃，
 * 而丢弃路径不会再排下一手，斗蛐蛐自走就此停住</b>（用户报的「下几个子就停了」）。
 * 现在用 {@link #pendingMoves} 记住「本地已落子、回声还没回来」，在这段时间里
 * 拒绝让回声把棋局往回退（超时 {@link #ECHO_GRACE_MS} 后仍然以服务端为准，并打日志）。</p>
 */
public class ChessWidget extends Widget {

    /** {@link #pendingMoves} 的宽限时间：超过它就以服务端为准（防止本地与服务端长期分歧）。 */
    private static final long ECHO_GRACE_MS = 3000L;

    /** 棋局状态。 */
    public GameSession game = new GameSession();

    /**
     * 本地已经落子并提交、但服务端回声还没追上来的手数；{@code -1} = 没有在途提交。
     *
     * <p>只由 {@link #noteLocalSubmit()} 写入，提交落子之后必须调一次。</p>
     */
    private int pendingMoves = -1;
    /** 那次在途提交发生的时间，用于 {@link #ECHO_GRACE_MS} 超时。 */
    private long pendingSince;

    /** 棋盘背景色（ARGB）。 */
    public int boardColor = 0xFF12121C;
    /** 格线颜色。 */
    public int lineColor = 0xFF7A7A92;
    /** 先手棋子颜色。 */
    public int colorA = 0xFFFF6666;
    /** 后手棋子颜色。 */
    public int colorB = 0xFF66AAFF;
    /** 棋子字体。默认 Minecraft AE（有中文字形）。 */
    public String fontId = Fonts.MINECRAFT_AE;
    /** 棋子字号。 */
    public double fontSize = 12;
    /** 是否画棋盘格线。 */
    public boolean showGrid = true;
    /** 是否高亮最后一手。 */
    public boolean highlightLast = true;
    /** 高亮颜色。 */
    public int highlightColor = 0x80FFD479;

    /**
     * 每一格棋格占多少画布单位（16 单位 = 1 方块，所以 12 = 0.75 格方块）。
     *
     * <p>这个值决定「墙面展示」能不能看清：太小的格线会细到亚像素，
     * 在远处糊成一片。新建控件时按「列数 x 行数」反推整体尺寸。</p>
     */
    public static final double CELL_UNITS = 13.0;

    public ChessWidget() {
        // 【必须在这里 reset】否则 game.board 是长度为 0 的空数组，
        // 渲染时 g.at(x,y) 永远返回 0 —— 画出来只有格线、一颗子都没有。
        game.reset();
        w = game.width() * CELL_UNITS;
        h = game.height() * CELL_UNITS;
    }

    /**
     * 按当前棋盘把控件框调整到「每格 CELL_UNITS」的大小。
     *
     * <p><b>换棋种时必须调用。</b>否则会出现这种情况：新建时是井字棋（3x3），
     * 框只有 39x39 单位；切成五子棋（15x15）后格子变成 2.6 单位（0.16 格方块），
     * 格线细到亚像素、棋子挤成一团——看起来就像「棋类控件完全没画出来」。</p>
     *
     * <p>玩家如果之后在缩略图里手动拖过尺寸，就按他拖的来（这里只在换棋种时调一次）。</p>
     */
    public void fitToBoard() {
        ensureBoard();
        int cols = Math.max(1, game.width());
        int rows = Math.max(1, game.height());
        setSize(cols * CELL_UNITS, rows * CELL_UNITS);
    }

    /**
     * 确保棋局与棋种一致。
     *
     * <p>任何可能不一致的来源（手改存档、旧版本数据、切换棋种时中途失败）
     * 都在这里被兜住并重开一局。渲染与对局界面在动手之前都必须先调它。</p>
     */
    public void ensureBoard() {
        if (game == null) {
            game = new GameSession();
            game.reset();
            return;
        }
        int want = game.rules().width() * game.rules().height();
        if (game.board == null || game.board.length != want) {
            top.hmjmfabc.projector.Projector.LOGGER.warn(
                    "[Projector] 棋类控件的棋盘尺寸({})与棋种 {}（应为 {}）不一致，已重置棋局",
                    game.board == null ? "null" : String.valueOf(game.board.length),
                    top.hmjmfabc.projector.common.game.GameKind.name(game.kind), want);
            game.reset();
        }
    }

    @Override
    public int kind() {
        return KIND_CHESS;
    }

    @Override
    public String label() {
        return GameKind.name(game.kind) + "\u00b7" + GameMode.name(game.mode);
    }

    @Override
    public java.util.List<String> fontIds() {
        return java.util.List.of(fontId == null ? "" : fontId);
    }

    /**
     * 【rc-84】界面该「着重显示」的落点。
     *
     * <p>玩家原话：「五子棋第一步那几个特殊的点应该着重显示，要不然只提示不合法」。
     * 这条也适用于象棋：选中一个子之后，把<b>它能走到的所有目标</b>画出来，
     * 玩家就不会再靠「点一下试试 / 不合法」来猜规则。</p>
     *
     * <ul>
     *   <li><b>空盘</b>（{@code moveCount == 0}）的落子类棋：返回全部合法第一手。
     *       五子棋与围棋的第一手都被规则限制在<b>天元</b>（棋盘正中心）这一点上，
     *       所以界面必须把这一点画出来，否则玩家点到别处只会看到「不合法」。</li>
     *   <li><b>已选中棋子</b>的走子类棋：返回该子的全部合法目标。</li>
     *   <li>其余情况返回空表（不画）。</li>
     * </ul>
     */
    public java.util.List<top.hmjmfabc.projector.common.game.Move> hintMoves() {
        ensureBoard();
        GameSession g = game;
        if (g == null || g.result != 0) return java.util.List.of();
        if (selCol >= 0 && selRow >= 0) {
            java.util.List<top.hmjmfabc.projector.common.game.Move> out = new java.util.ArrayList<>();
            for (var m : g.legalMoves()) {
                if (m.fx() == selCol && m.fy() == selRow) out.add(m);
            }
            return out;
        }
        if (g.moveCount == 0) {
            // 只有落子类棋才有「第一手落点」可提示。这条早退很重要：这个方法每帧都会被调用，
            // 象棋/国际象棋空盘时若照样跑一遍 legalMoves()，就是每帧几十个临时对象白算。
            boolean dropGame = g.kind == top.hmjmfabc.projector.common.game.GameKind.GOMOKU
                    || g.kind == top.hmjmfabc.projector.common.game.GameKind.GO
                    || g.kind == top.hmjmfabc.projector.common.game.GameKind.TIC_TAC_TOE;
            if (!dropGame) return java.util.List.of();
            java.util.List<top.hmjmfabc.projector.common.game.Move> out = new java.util.ArrayList<>();
            for (var m : g.legalMoves()) {
                if (m.isDrop()) out.add(m);
            }
            return out;
        }
        return java.util.List.of();
    }

    /** 空盘时是不是「第一手只允许下在特定几点」的棋（五子棋/围棋）——界面据此换文案。 */
    public boolean firstMoveRestricted() {
        ensureBoard();
        GameSession g = game;
        if (g == null) return false;
        boolean dropGame = g.kind == top.hmjmfabc.projector.common.game.GameKind.GOMOKU
                || g.kind == top.hmjmfabc.projector.common.game.GameKind.GO;
        if (!dropGame || g.moveCount != 0) return false;
        return g.legalMoves().size() < g.width() * g.height();
    }

    /**
     * 【rc-84】把棋盘收进平面：整块棋盘必须落在平面范围内，否则<b>超出部分没有可点击的面</b>，
     * 右键永远打不到那些点位 —— 玩家看到的现象就是「那边的子动不了 / 吃不到」
     * （实测反馈：「兵越不过楚河汉界，炮吃不到相隔的子」）。
     *
     * <p>平面装不下原始尺寸时按比例缩小（每格间距等比缩小，棋盘比例不变），
     * 并把左上角收进平面内。</p>
     *
     * @return true = 做了收缩（调用方可以据此打一行日志）
     */
    public static boolean fitIntoPlane(ChessWidget cw, double planeW, double planeH) {
        if (cw == null || !(planeW > 0) || !(planeH > 0)) return false;
        cw.ensureBoard();
        double needW = Math.max(1.0, cw.game.width() * CELL_UNITS);
        double needH = Math.max(1.0, cw.game.height() * CELL_UNITS);
        boolean shrunk = false;
        if (needW > planeW || needH > planeH) {
            double k = Math.min(planeW / needW, planeH / needH);
            cw.setSize(needW * k, needH * k);
            shrunk = true;
        }
        double maxX = Math.max(0.0, planeW - cw.w);
        double maxY = Math.max(0.0, planeH - cw.h);
        cw.x = cw.x < 0 ? 0 : Math.min(cw.x, maxX);
        cw.y = cw.y < 0 ? 0 : Math.min(cw.y, maxY);
        return shrunk;
    }

    /**
     * 报告「本地刚把一次落子提交给服务端」。
     *
     * <p>提交落子的每一处都必须调它（AI 走完、玩家走完），否则 {@link #loadExtra}
     * 就分不清「这份回声是更早的局面」还是「服务端真的把棋局退回去了」。</p>
     */
    public void noteLocalSubmit() {
        pendingMoves = game == null ? -1 : game.moveCount;
        pendingSince = System.currentTimeMillis();
    }

    @Override
    protected void saveExtra(CompoundTag t) {
        t.put("game", game.save());
        t.putInt("boardColor", boardColor);
        t.putInt("lineColor", lineColor);
        t.putInt("colorA", colorA);
        t.putInt("colorB", colorB);
        t.putString("font", fontId);
        t.putDouble("fs", fontSize);
        t.putBoolean("grid", showGrid);
        t.putBoolean("hl", highlightLast);
        t.putInt("hlColor", highlightColor);
    }

    @Override
    public void loadExtra(CompoundTag t) {
        if (t.contains("game")) {
            GameSession fresh = GameSession.load(t.getCompound("game"));
            if (game == null) {
                game = fresh;
            } else if (pendingMoves >= 0 && fresh.moveCount < game.moveCount
                    && System.currentTimeMillis() - pendingSince < ECHO_GRACE_MS) {
                // 【回声比本地旧 -> 不许退回】见 pendingMoves 的说明。
                // 只把「不影响对局进度」的字段合进来，棋局保持本地的乐观状态。
                game.kind = GameKind.clamp(fresh.kind);
                game.mode = GameMode.clamp(fresh.mode);
                Projector.LOGGER.debug("[Projector] 忽略过期的棋局回声：本地 {} 手 / 回声 {} 手",
                        game.moveCount, fresh.moveCount);
            } else {
                if (pendingMoves >= 0 && fresh.moveCount < game.moveCount) {
                    Projector.LOGGER.warn("[Projector] 棋局回声长期落后（本地 {} 手 / 回声 {} 手），"
                            + "按服务端为准回退", game.moveCount, fresh.moveCount);
                }
                pendingMoves = -1;
                // 【必须原地拷】直接换对象会让「正在算 AI 的那个任务」把自己结果丢掉，
                // 表现为「人类下完 AI 不动，要再点一下」（详见 GameSession.copyFrom 的注释）
                game.copyFrom(fresh);
            }
        } else if (game == null) {
            game = new GameSession();
            game.reset();
        }
        // 旧存档 / 手改存档都可能带着尺寸不对的棋盘，这里统一兜住
        ensureBoard();
        boardColor = t.contains("boardColor") ? t.getInt("boardColor") : 0xFF12121C;
        lineColor = t.contains("lineColor") ? t.getInt("lineColor") : 0xFF7A7A92;
        colorA = t.contains("colorA") ? t.getInt("colorA") : 0xFFFF6666;
        colorB = t.contains("colorB") ? t.getInt("colorB") : 0xFF66AAFF;
        fontId = t.contains("font") ? t.getString("font") : Fonts.MINECRAFT_AE;
        fontSize = t.contains("fs") ? t.getDouble("fs") : 12;
        showGrid = !t.contains("grid") || t.getBoolean("grid");
        highlightLast = !t.contains("hl") || t.getBoolean("hl");
        highlightColor = t.contains("hlColor") ? t.getInt("hlColor") : 0x80FFD479;
    }

    /** 这一方的棋子颜色。 */
    public int sideColor(int side) {
        return side > 0 ? colorA : colorB;
    }

    // ------------------------------------------------------------------
    // 【⑪】在世界里直接点棋盘
    // ------------------------------------------------------------------

    /**
     * 世界内点选用的临时状态（<b>不进存档</b>，每个客户端各自一份）。
     *
     * <p>走子类棋需要「先点自己的子、再点目标格」，这个选中状态是纯本地的 UI 状态，
     * 同步给服务端或别的玩家都没有意义。</p>
     */
    public int selCol = -1;
    public int selRow = -1;

    /** 点格子的结果。 */
    public enum Click {
        /** 做出了一个动作（落子/走子）。 */
        PLAYED,
        /** 选中了自己的棋子，等第二下。 */
        SELECTED,
        /** 这一步不合法。 */
        ILLEGAL,
        /** 还没选子，先点一个自己的棋子。 */
        NEED_SELECT,
        /** 轮到 AI / 已经结束 / 点了界外。 */
        IGNORED
    }

    /**
     * 在世界里点了第 (col,row) 个点位（交叉点式）或格子（格心式）。
     *
     * <p>这是「不打开界面就能在游戏内下棋」的核心：一次右键 = 一次点格子。</p>
     */
    public Click clickCell(int col, int row) {
        ensureBoard();
        GameSession g = game;
        int cw = g.width(), ch = g.height();
        if (col < 0 || row < 0 || col >= cw || row >= ch) return Click.IGNORED;
        if (g.result != 0 || g.isAiTurn()) return Click.IGNORED;
        int side = g.turn;
        boolean pieceGame = g.kind == top.hmjmfabc.projector.common.game.GameKind.XIANGQI
                || g.kind == top.hmjmfabc.projector.common.game.GameKind.CHESS;
        if (pieceGame) {
            int p = g.at(col, row);
            if (p != 0 && Integer.signum(p) == side) {
                if (selCol == col && selRow == row) {
                    selCol = selRow = -1;      // 再点一下同一个子 = 取消选中
                    return Click.SELECTED;
                }
                selCol = col;
                selRow = row;
                return Click.SELECTED;
            }
            if (selCol < 0 || selRow < 0) return Click.NEED_SELECT;
            for (var m : g.legalMoves()) {
                if (m.fx() == selCol && m.fy() == selRow && m.tx() == col && m.ty() == row) {
                    selCol = selRow = -1;
                    if (!g.canPlay(m, side)) return Click.ILLEGAL;
                    g.humanMove(m);
                    return Click.PLAYED;
                }
            }
            selCol = selRow = -1;
            return Click.ILLEGAL;
        }
        for (var m : g.legalMoves()) {
            if (m.isDrop() && m.tx() == col && m.ty() == row) {
                // 【必须问 canPlay，不能只看 legalMoves】围棋的劫争禁令只存在于会话层，
                // legalMoves 里仍有这个落点；直接 humanMove 会返回 false，
                // 以前却照样返回 PLAYED -> 玩家看到「已落子」但棋盘没动（静默失败）。
                if (!g.canPlay(m, side)) return Click.ILLEGAL;
                g.humanMove(m);
                return Click.PLAYED;
            }
        }
        return Click.ILLEGAL;
    }

    /**
     * 棋盘几何：{@code spacing} 是相邻点位的间距，{@code ox/oy} 是
     * 「第 0 列、第 ch-1 行」那个点位的画布坐标基准。
     *
     * <p><b>渲染与命中检测必须共用这一份</b>——以前两边各算一套，
     * 只要有一边写错就会出现「看着点在子上、结果点到了隔壁」。</p>
     */
    public record Geometry(double spacing, double ox, double oy, boolean intersections,
                           int cols, int rows) {
    }

    /** 算出当前棋盘几何。 */
    public Geometry geometry() {
        ensureBoard();
        var rules = game.rules();
        int cw = Math.max(1, rules.width());
        int ch = Math.max(1, rules.height());
        boolean inter = rules.piecesOnIntersections();
        double spacing;
        double ox, oy;
        if (inter) {
            // 交叉点式：四周留半个间距的边距，所以分母直接用列/行数
            spacing = Math.min(w / cw, h / ch);
            ox = x + (w - spacing * (cw - 1)) * 0.5;
            oy = y + (h - spacing * (ch - 1)) * 0.5;
        } else {
            // 格心式：格子铺满整个框
            spacing = Math.min(w / cw, h / ch);
            ox = x + (w - spacing * cw) * 0.5;
            oy = y + (h - spacing * ch) * 0.5;
        }
        return new Geometry(spacing, ox, oy, inter, cw, ch);
    }

    /**
     * 第 (col,row) 个点位/格子的<b>中心</b>的画布坐标。
     *
     * <p>行序：第 0 行在<b>最上面</b>（画布 y 轴向上），所以纵坐标用 {@code rows-1-row}。</p>
     */
    public double[] centerOf(int col, int row) {
        Geometry geo = geometry();
        double cx = geo.intersections() ? geo.ox() + col * geo.spacing()
                : geo.ox() + (col + 0.5) * geo.spacing();
        double cy = geo.oy() + (geo.rows() - 1 - row) * geo.spacing()
                + (geo.intersections() ? 0 : 0.5 * geo.spacing());
        return new double[]{cx, cy};
    }

    /**
     * {@link #centerOf} 的逆运算：把画布坐标换算成点位/格子下标。
     *
     * @return {@code {col,row}}；落在棋盘外返回 null
     */
    @org.jetbrains.annotations.Nullable
    public int[] cellAt(double canvasX, double canvasY) {
        Geometry geo = geometry();
        if (geo.spacing() <= 0.001) return null;
        double lx = (canvasX - geo.ox()) / geo.spacing();
        double ly = (canvasY - geo.oy()) / geo.spacing();
        int col, row;
        if (geo.intersections()) {
            col = (int) Math.round(lx);
            row = geo.rows() - 1 - (int) Math.round(ly);
        } else {
            col = (int) Math.floor(lx);
            row = geo.rows() - 1 - (int) Math.floor(ly);
        }
        if (col < 0 || row < 0 || col >= geo.cols() || row >= geo.rows()) return null;
        return new int[]{col, row};
    }
}

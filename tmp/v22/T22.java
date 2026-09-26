import top.hmjmfabc.projector.common.game.*;

import java.util.ArrayList;
import java.util.List;

/**
 * T22 —— 玩家报的两条规则 Bug（象棋兵/炮）＋ 五子棋首手提示的取证。
 *
 * <p>直接对着规则实现跑「物理不变量」：不看源码推理，只看 moves() 到底给出了哪些着法。</p>
 */
public final class T22 {

    private static int pass, fail;
    private static final int W = 9, H = 10;
    private static final int KING = 1, ADVISOR = 2, ELEPHANT = 3, HORSE = 4, ROOK = 5, CANNON = 6, PAWN = 7;

    public static void main(String[] args) {
        GameRules xq = GameRulesFactory.create(GameKind.XIANGQI);
        check("象棋棋盘 9x10", xq.width() == 9 && xq.height() == 10, xq.width() + "x" + xq.height());

        // ---- ① 兵能不能过河 ----
        // 红兵（+，向上即 y 减小）在 y=5（未过河），应能走到 y=4（过河）
        int[] b = new int[W * H];
        b[5 * W + 4] = PAWN;
        b[9 * W + 0] = KING;     // 红将
        b[0 * W + 8] = -KING;    // 黑将
        List<Move> ms = xq.moves(b, 1);
        check("红兵 y=5 → y=4（过河那一步）必须合法", has(ms, 4, 5, 4, 4), "实际着法=" + ms);
        boolean sidewaysBefore = has(ms, 4, 5, 3, 5) || has(ms, 4, 5, 5, 5);
        check("红兵未过河时不许横走", !sidewaysBefore, "实际着法=" + ms);

        // 红兵已过河（y=4）应能横走
        int[] b2 = new int[W * H];
        b2[4 * W + 4] = PAWN;
        b2[3 * W + 4] = 0;
        b2[9 * W + 0] = KING;
        b2[0 * W + 8] = -KING;
        List<Move> ms2 = xq.moves(b2, 1);
        check("红兵过河后可以横走（y=4 → x±1）",
                has(ms2, 4, 4, 3, 4) && has(ms2, 4, 4, 5, 4), "实际着法=" + ms2);
        check("红兵过河后仍可继续向前（y=4 → y=3）", has(ms2, 4, 4, 4, 3), "实际着法=" + ms2);

        // 一路推到底：y=4 → 3 → 2 → 1 → 0
        int[] b3 = new int[W * H];
        b3[4 * W + 4] = PAWN;
        b3[9 * W + 0] = KING;
        b3[0 * W + 8] = -KING;
        check("红兵过河后能一直推进到黑方底线 y=0",
                xq.moves(b3, 1).stream().anyMatch(m -> m.tx() == 4 && m.ty() < 4),
                "实际着法=" + xq.moves(b3, 1));

        // 黑兵（-，向下即 y 增大）在 y=4 应能走到 y=5
        int[] b4 = new int[W * H];
        b4[4 * W + 4] = -PAWN;
        b4[9 * W + 0] = KING;
        b4[0 * W + 8] = -KING;
        check("黑兵 y=4 → y=5（过河）必须合法",
                has(xq.moves(b4, -1), 4, 4, 4, 5), "实际着法=" + xq.moves(b4, -1));

        // ---- ② 炮吃子 ----
        // x 轴：炮(0,4) 空(1,4) 空(2,4) 炮架(3,4)=黑兵 空(4,4) 黑车(5,4)
        int[] c = new int[W * H];
        c[4 * W + 0] = CANNON;
        c[4 * W + 3] = -PAWN;      // 炮架
        c[4 * W + 5] = -ROOK;      // 目标
        c[9 * W + 4] = KING;
        c[0 * W + 4] = -KING;
        List<Move> cs = xq.moves(c, 1);
        check("炮能吃掉隔着**一个**子的敌子（0,4 → 5,4）", has(cs, 0, 4, 5, 4), "实际着法=" + cs);
        check("炮不能吃炮架本身（0,4 → 3,4）", !has(cs, 0, 4, 3, 4), "实际着法=" + cs);
        check("炮不能越过炮架落到空点（0,4 → 4,4）", !has(cs, 0, 4, 4, 4), "实际着法=" + cs);
        check("炮能平移到炮架前的空点（0,4 → 1,4 与 0,4 → 2,4）",
                has(cs, 0, 4, 1, 4) && has(cs, 0, 4, 2, 4), "实际着法=" + cs);

        // 两个子相隔：炮架(3,4) + 敌子(4,4) + 又一颗(5,4)
        int[] c2 = new int[W * H];
        c2[4 * W + 0] = CANNON;
        c2[4 * W + 3] = -PAWN;
        c2[4 * W + 4] = -HORSE;
        c2[4 * W + 5] = -ROOK;
        c2[9 * W + 4] = KING;
        c2[0 * W + 4] = -KING;
        List<Move> cs2 = xq.moves(c2, 1);
        check("炮打第一个敌子（0,4 → 4,4）", has(cs2, 0, 4, 4, 4), "实际着法=" + cs2);
        check("炮不能打第二个敌子（0,4 → 5,4，中间有两颗）", !has(cs2, 0, 4, 5, 4), "实际着法=" + cs2);

        // ---- ③ 五子棋首手：UI 要着重显示的「特殊的点」到底是哪些 ----
        GameRules gmk = GameRulesFactory.create(GameKind.GOMOKU);
        int[] empty = gmk.initialBoard();
        List<int[]> first = new ArrayList<>();
        for (Move m : gmk.moves(empty, 1)) first.add(new int[]{m.tx(), m.ty()});
        StringBuilder sb = new StringBuilder();
        for (int[] p : first) sb.append("(").append(p[0]).append(",").append(p[1]).append(") ");
        check("五子棋空盘首手：moves() 给出的合法点（UI 必须把这些点着重显示）",
                !first.isEmpty(), "空盘首手合法点为空！");
        System.out.println("      → 空盘首手合法点 = " + first.size() + " 个：" + sb);

        // 黑棋落子后，白棋应能下在附近
        int[] after = gmk.apply(empty, Move.drop(7, 7), 1);
        check("五子棋第二手有合法点（不能只认中心）", !gmk.moves(after, -1).isEmpty(),
                "第二手没有合法点");

        // ---- ④ 真的点一遍（控件层 clickCell，与玩家在世界里点棋盘同一条路径）----
        var widget = new top.hmjmfabc.projector.common.widget.ChessWidget();
        widget.game.kind = GameKind.XIANGQI;
        widget.game.mode = GameMode.PVP;      // 双人：两边都由本机点，避免 isAiTurn 挡路
        widget.game.reset();
        check("控件层：初始局面 32 个子", countPieces(widget.game.board) == 32,
                "实有=" + countPieces(widget.game.board));

        // 红兵 (0,6) → (0,5) → (0,4)（过河）→ (1,4)（过河后横走）
        check("点击 红兵(0,6) = 选中",
                widget.clickCell(0, 6) == top.hmjmfabc.projector.common.widget.ChessWidget.Click.SELECTED, "");
        check("点击 (0,5) = 走子成功",
                widget.clickCell(0, 5) == top.hmjmfabc.projector.common.widget.ChessWidget.Click.PLAYED, "");
        // 轮到黑方了：把回合让回红方（模拟黑方随便走一手）
        widget.clickCell(0, 3);   // 黑卒
        widget.clickCell(0, 4);   // 黑卒前进（吃红兵？红兵在 0,5，不冲突）
        check("此时轮到红方", widget.game.turn == 1, "turn=" + widget.game.turn);
        int[] pawn = findPiece(widget.game, PAWN, 1);
        check("红兵已到 (0,5)", pawn != null && pawn[0] == 0 && pawn[1] == 5,
                pawn == null ? "找不到红兵" : ("在 " + pawn[0] + "," + pawn[1]));
        check("点击 红兵 = 选中",
                widget.clickCell(pawn[0], pawn[1]) == top.hmjmfabc.projector.common.widget.ChessWidget.Click.SELECTED, "");
        check("点击 (0,4) = **过河成功**",
                widget.clickCell(0, 4) == top.hmjmfabc.projector.common.widget.ChessWidget.Click.PLAYED,
                "点过河那一步被拒了 —— 正是玩家报的「兵越不过楚河汉界」");
        int[] pawn2 = findPiece(widget.game, PAWN, 1);
        check("红兵确实落在 (0,4)", pawn2 != null && pawn2[0] == 0 && pawn2[1] == 4,
                pawn2 == null ? "找不到红兵" : ("在 " + pawn2[0] + "," + pawn2[1]));
        // 黑方再走一手（(2,3) 卒前进）
        widget.clickCell(2, 3);
        widget.clickCell(2, 4);
        check("点击 过河后的红兵 = 选中",
                widget.clickCell(pawn2[0], pawn2[1]) == top.hmjmfabc.projector.common.widget.ChessWidget.Click.SELECTED, "");
        check("点击 (1,4) = **过河后横走成功**",
                widget.clickCell(1, 4) == top.hmjmfabc.projector.common.widget.ChessWidget.Click.PLAYED,
                "过河后不能横走");

        // 炮吃相隔的子：摆一个干净局面
        var w2 = new top.hmjmfabc.projector.common.widget.ChessWidget();
        w2.game.kind = GameKind.XIANGQI;
        w2.game.mode = GameMode.PVP;
        w2.game.reset();
        int[] bb = new int[W * H];
        bb[4 * W + 4] = CANNON;      // 红炮 (4,4)
        bb[4 * W + 3] = 0;
        bb[7 * W + 4] = -ROOK;       // 目标（同一列，y=7）
        bb[6 * W + 4] = -PAWN;       // 炮架（同一列，y=6）
        bb[9 * W + 0] = KING;
        bb[0 * W + 8] = -KING;
        w2.game.board = bb;
        w2.game.turn = 1;
        check("点击 红炮(4,4) = 选中",
                w2.clickCell(4, 4) == top.hmjmfabc.projector.common.widget.ChessWidget.Click.SELECTED, "");
        check("点击 (4,7) = **炮隔着炮架吃掉敌子**",
                w2.clickCell(4, 7) == top.hmjmfabc.projector.common.widget.ChessWidget.Click.PLAYED,
                "炮吃不到相隔的子 —— 正是玩家报的现象");
        check("炮吃子后敌子消失（(4,7) 变成红炮）", w2.game.at(4, 7) == CANNON,
                "at(4,7)=" + w2.game.at(4, 7));

        // ---- ⑤ AI 自走（斗蛐蛐）：AI 会不会用兵过河 / 用炮吃子 ----
        GameSession ai = new GameSession();
        ai.setKind(GameKind.XIANGQI);
        ai.mode = GameMode.AI_VS_AI;
        ai.difficulty = GameDifficulty.HIGH;
        int plies = 0, pawnMoves = 0, pawnCross = 0, cannonMoves = 0, cannonCaps = 0;
        for (int i = 0; i < 60 && ai.result == 0; i++) {
            int side = ai.turn;
            // 【注意】aiMove() 会**直接把这一手走掉**，所以棋子必须在落子之前读
            int[] before = ai.board.clone();
            GameAi.Choice ch = ai.aiMove();
            if (ch == null) break;
            Move m = ch.move();
            int p = Math.abs(before[m.fy() * W + m.tx()]);
            int target = before[m.ty() * W + m.tx()];
            if (p == PAWN) {
                pawnMoves++;
                boolean wasHere = side > 0 ? m.fy() >= 5 : m.fy() <= 4;
                boolean nowThere = side > 0 ? m.ty() <= 4 : m.ty() >= 5;
                if (wasHere && nowThere) pawnCross++;
            }
            if (p == CANNON) {
                cannonMoves++;
                if (target != 0 && Integer.signum(target) != side) cannonCaps++;
            }
            plies++;
        }
        System.out.println("      → AI 自走 " + plies + " 手：兵走 " + pawnMoves + " 次（其中过河 " + pawnCross
                + " 次）、炮走 " + cannonMoves + " 次（其中吃子 " + cannonCaps + " 次）；局面 result="
                + ai.result + " 子数=" + countPieces(ai.board));
        check("AI 会用兵过河（斗蛐蛐里兵不能永远待在自家半场）", pawnCross > 0,
                "60 手里一次都没过河 —— 与玩家报的「兵越不过楚河汉界」一致");
        check("AI 会用炮吃子（隔着炮架的跳吃）", cannonCaps > 0,
                "60 手里一次都没跳吃 —— 与玩家报的「炮吃不到相隔的子」一致");

        // ---- ⑥ 界面必须「着重显示」可下的点（rc-84）----
        var w3 = new top.hmjmfabc.projector.common.widget.ChessWidget();
        w3.game.kind = GameKind.GOMOKU;
        w3.game.mode = GameMode.PVP;
        w3.game.reset();
        check("五子棋空盘：firstMoveRestricted()=true（第一手被规则限制在特殊点）",
                w3.firstMoveRestricted(), "");
        check("五子棋空盘：hintMoves() 给出的正是那个「特殊的点」（天元 7,7）",
                w3.hintMoves().size() == 1 && w3.hintMoves().get(0).tx() == 7
                        && w3.hintMoves().get(0).ty() == 7,
                "实得=" + w3.hintMoves());
        w3.game.play(w3.game.legalMoves().get(0), w3.game.turn);
        check("五子棋落子后不再限制首手（提示消失）", !w3.firstMoveRestricted(), "");

        // 象棋：选中一个子后必须能拿到它的全部落点（玩家要的「别只说『不合法』」）
        var w4 = new top.hmjmfabc.projector.common.widget.ChessWidget();
        w4.game.kind = GameKind.XIANGQI;
        w4.game.mode = GameMode.PVP;
        w4.game.reset();
        check("象棋未选子时没有落点提示", w4.hintMoves().isEmpty(), "实得=" + w4.hintMoves());
        w4.clickCell(0, 6);   // 选中红兵
        var pawnHints = w4.hintMoves();
        check("象棋选中红兵后，提示恰好是它的合法着法（未过河 = 只有向前一步）",
                pawnHints.size() == 1 && pawnHints.get(0).tx() == 0 && pawnHints.get(0).ty() == 5,
                "实得=" + pawnHints);

        // 炮：选中后提示里必须包含「隔着炮架吃子」那一步
        var w5 = new top.hmjmfabc.projector.common.widget.ChessWidget();
        w5.game.kind = GameKind.XIANGQI;
        w5.game.mode = GameMode.PVP;
        w5.game.reset();
        int[] b5 = new int[W * H];
        b5[4 * W + 4] = CANNON;
        b5[6 * W + 4] = -PAWN;      // 炮架
        b5[7 * W + 4] = -ROOK;      // 目标
        b5[9 * W + 0] = KING;
        b5[0 * W + 8] = -KING;
        w5.game.board = b5;
        w5.game.turn = 1;
        w5.clickCell(4, 4);
        boolean jumpHinted = false;
        for (var hm : w5.hintMoves()) if (hm.tx() == 4 && hm.ty() == 7) jumpHinted = true;
        check("象棋选中炮后，提示里包含「隔一个子吃子」的落点", jumpHinted,
                "实得=" + w5.hintMoves() + "（玩家看到的就是「炮吃不到相隔的子」）");

        // ---- ⑦ 棋盘必须完整落在平面内（超出的部分永远点不到）----
        var w6 = new top.hmjmfabc.projector.common.widget.ChessWidget();
        w6.game.kind = GameKind.XIANGQI;
        w6.game.reset();
        w6.x = -5;
        w6.y = 40;
        boolean shrunk = top.hmjmfabc.projector.common.widget.ChessWidget.fitIntoPlane(w6, 128, 128);
        check("平面 128x128 装不下象棋（7.3x8.1 格）：必须收缩", shrunk, "没有收缩");
        check("收缩后整块棋盘都在平面内（x/y>=0 且 x+w<=128, y+h<=128）",
                w6.x >= 0 && w6.y >= 0 && w6.x + w6.w <= 128.001 && w6.y + w6.h <= 128.001,
                String.format("x=%.1f y=%.1f w=%.1f h=%.1f", w6.x, w6.y, w6.w, w6.h));
        var w7 = new top.hmjmfabc.projector.common.widget.ChessWidget();
        w7.game.kind = GameKind.XIANGQI;
        w7.game.reset();
        check("平面足够大时不动它（不改变玩家摆好的尺寸）",
                !top.hmjmfabc.projector.common.widget.ChessWidget.fitIntoPlane(w7, 240, 240), "");

        System.out.println();
        System.out.println("== " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " ==  " + pass + " passed");
        if (fail > 0) System.exit(1);
    }

    private static int countPieces(int[] b) {
        int n = 0;
        for (int v : b) if (v != 0) n++;
        return n;
    }

    /** 找某一方某个兵种的位置（棋盘上可能有多枚，取最靠上的那枚）。 */
    private static int[] findPiece(GameSession g, int type, int side) {
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int v = g.at(x, y);
                if (Math.abs(v) == type && Integer.signum(v) == side) return new int[]{x, y};
            }
        }
        return null;
    }

    private static boolean has(List<Move> ms, int fx, int fy, int tx, int ty) {
        for (Move m : ms) if (m.fx() == fx && m.fy() == fy && m.tx() == tx && m.ty() == ty) return true;
        return false;
    }

    private static void check(String what, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  ✅ " + what);
        } else {
            fail++;
            System.out.println("  ❌ " + what + (detail.isEmpty() ? "" : "  —— " + detail));
        }
    }
}

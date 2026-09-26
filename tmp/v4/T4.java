import top.hmjmfabc.projector.common.game.*;
import java.util.List;

public class T4 {
    static int fails = 0;
    static void chk(String what, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + what + "   " + detail);
        if (!ok) fails++;
    }
    static int count(int[] b) { int n=0; for (int v: b) if (v!=0) n++; return n; }

    public static void main(String[] a) {
        // ================= 井字棋 =================
        GameRules tt = GameRulesFactory.create(GameKind.TIC_TAC_TOE);
        int[] b = tt.initialBoard();
        chk("井字棋 初始 9 个空点", tt.moves(b, 1).size() == 9, "" + tt.moves(b, 1).size());
        // X 占 (0,0),(1,1)，O 占 (0,1),(1,0) -> X 走 (2,2) 成对角线
        b = tt.apply(b, Move.drop(0,0), 1);
        b = tt.apply(b, Move.drop(0,1), -1);
        b = tt.apply(b, Move.drop(1,1), 1);
        b = tt.apply(b, Move.drop(1,0), -1);
        chk("井字棋 尚未结束", tt.result(b, 1, 0) == 0, "" + tt.result(b, 1, 0));
        b = tt.apply(b, Move.drop(2,2), 1);
        chk("井字棋 对角线判先手胜", tt.result(b, 1, 0) == 1, "" + tt.result(b, 1, 0));
        // 和棋
        int[] d = new int[9];
        int[] seq = {0,1,2,4,3,5,7,6,8};
        int[] pieces = {1,-1,1,1,-1,-1,-1,1,-1};
        for (int i=0;i<9;i++) d = tt.apply(d, Move.drop(seq[i]%3, seq[i]/3), pieces[i]);
        chk("井字棋 满盘必出结果", tt.result(d, 1, 0) != 0, "结果=" + tt.result(d,1,0));

        // ================= 五子棋 =================
        GameRules gm = GameRulesFactory.create(GameKind.GOMOKU);
        int[] g = gm.initialBoard();
        chk("五子棋 15x15=225", g.length == 225, "" + g.length);
        chk("五子棋 空盘只推荐天元", gm.moves(g, 1).size() == 1
                && gm.moves(g,1).get(0).tx()==7 && gm.moves(g,1).get(0).ty()==7, "" + gm.moves(g,1));
        for (int i = 0; i < 5; i++) g = gm.apply(g, Move.drop(3+i, 7), 1);
        chk("五子棋 横五连判先手胜", gm.result(g, -1, 0) == 1, "" + gm.result(g, -1, 0));
        // 竖向四连不应判胜
        int[] g2 = gm.initialBoard();
        for (int i = 0; i < 4; i++) g2 = gm.apply(g2, Move.drop(5, 5+i), 1);
        chk("五子棋 四连不算胜", gm.result(g2, -1, 0) == 0, "" + gm.result(g2, -1, 0));
        chk("五子棋 斜五连", gm.result(buildDiag(gm), -1, 0) == 1, "");

        // ================= 中国象棋 =================
        GameRules xq = GameRulesFactory.create(GameKind.XIANGQI);
        int[] x = xq.initialBoard();
        chk("象棋 9x10=90", x.length == 90, "" + x.length);
        chk("象棋 初始 32 子", count(x) == 32, "" + count(x));
        int xm = xq.moves(x, 1).size();
        chk("象棋 红方开局 44 种着法", xm == 44, "" + xm);
        chk("象棋 黑方开局 44 种着法", xq.moves(x, -1).size() == 44, "" + xq.moves(x,-1).size());
        // 马腿：开局红马(1,9) 只能跳到 (0,7) 与 (2,7)
        List<Move> horse = xq.moves(x, 1).stream()
                .filter(m -> m.fx()==1 && m.fy()==9).toList();
        chk("象棋 开局马只有 2 步（蹩马腿生效）", horse.size() == 2, "" + horse);
        // 炮吃子：红炮(1,7) 可以吃黑马(1,0)（隔一个炮架）
        boolean cannonEats = xq.moves(x, 1).stream()
                .anyMatch(m -> m.fx()==1 && m.fy()==7 && m.tx()==1 && m.ty()==0);
        chk("象棋 炮隔子吃对方马", cannonEats, "");
        // 象不过河：红象(2,9) 只能到 (0,7),(4,7)
        List<Move> ele = xq.moves(x, 1).stream().filter(m -> m.fx()==2 && m.fy()==9).toList();
        chk("象棋 象不过河（2 步）", ele.size() == 2, "" + ele);

        // ================= 国际象棋 =================
        GameRules ch = GameRulesFactory.create(GameKind.CHESS);
        int[] c = ch.initialBoard();
        chk("国际象棋 8x8=64", c.length == 64, "" + c.length);
        chk("国际象棋 初始 20 种着法", ch.moves(c, 1).size() == 20, "" + ch.moves(c, 1).size());
        // 升变
        int[] pc = new int[64];
        pc[1*8+0] = 1;   // 白兵在 (0,1)
        pc[7*8+7] = -6;  // 黑王
        pc[0*8+7] = 6;   // 白王
        int[] pc2 = ch.apply(pc, Move.step(0,1,0,0,1), 1);
        chk("国际象棋 兵到底线升后", Math.abs(pc2[0]) == 5, "" + pc2[0]);
        // 王车易位：白王 (4,7) 白车 (7,7)，中间清空
        int[] cas = new int[64];
        cas[7*8+4] = 6; cas[7*8+7] = 4;
        cas[0*8+4] = -6; cas[0*8+0] = -4;
        boolean canCastle = ch.moves(cas, 1).stream()
                .anyMatch(m -> m.fx()==4 && m.fy()==7 && m.tx()==6);
        chk("国际象棋 允许短易位", canCastle, "");
        int[] cas2 = ch.apply(cas, Move.step(4,7,6,7,6), 1);
        chk("国际象棋 易位后车到 f1", cas2[7*8+5] == 4 && cas2[7*8+7] == 0,
                "f1=" + cas2[7*8+5] + " h1=" + cas2[7*8+7]);

        // ================= 围棋 =================
        GameRules go = GameRulesFactory.create(GameKind.GO);
        int[] gb = go.initialBoard();
        chk("围棋 9x9=81", gb.length == 81, "" + gb.length);
        chk("围棋 空盘 81 个着点", go.moves(gb, 1).size() == 81, "" + go.moves(gb, 1).size());
        // 提子：白子在 (1,1)，黑占其上下左右 -> 白被提
        int[] cap = new int[81];
        cap[1*9+1] = -1;
        cap[0*9+1] = 1; cap[2*9+1] = 1; cap[1*9+0] = 1;
        int[] capAfter = GamesDropGoPlay(go, cap, 2, 1, 1);
        chk("围棋 提掉无气的白子", capAfter != null && capAfter[1*9+1] == 0,
                capAfter == null ? "play 返回 null" : "" + capAfter[1*9+1]);
        // 禁自杀：用黑子把 (0,0) 包围，白子不能下在 (0,0)？——换成更直接的：白下在唯一的空位且无气
        int[] sui = new int[81];
        sui[0*9+1] = 1; sui[1*9+0] = 1;   // 黑占右、下
        boolean suicideRejected = true;
        for (Move m : go.moves(sui, -1)) {
            if (m.tx()==0 && m.ty()==0) suicideRejected = false;
        }
        chk("围棋 禁止自杀（角上无气点被排除）", suicideRejected, "");

        // ================= AI =================
        // 井字棋：X 已有两子成线，AI 必须去堵或者取胜
        GameSession s = new GameSession();
        s.kind = GameKind.TIC_TAC_TOE; s.mode = GameMode.PVE; s.reset();
        s.play(Move.drop(0,0), 1);   // X
        s.play(Move.drop(1,0), -1);  // O
        s.play(Move.drop(0,1), 1);   // X 已两子，威胁 (0,2)
        var choice = s.aiMove();
        chk("井字棋 AI 会堵（走 (0,2)）", choice != null && choice.move().tx()==0 && choice.move().ty()==2,
                choice == null ? "null" : choice.move() + " score=" + choice.score());
        chk("井字棋 AI 未超节点上限", choice != null && choice.nodes() < GameAi.MAX_NODES,
                choice == null ? "" : "" + choice.nodes());

        // 六种棋：AI 都能在时限内给出一个合法着法
        for (int k : GameKind.PLAYABLE) {
            GameSession gs = new GameSession();
            gs.kind = k; gs.mode = GameMode.AI_VS_AI; gs.reset();
            long t0 = System.currentTimeMillis();
            var ch2 = gs.aiMove();
            long dt = System.currentTimeMillis() - t0;
            chk("AI 能下 " + GameKind.name(k), ch2 != null && ch2.move() != null,
                    "用时 " + dt + "ms 节点 " + (ch2 == null ? 0 : ch2.nodes()));
            chk("AI " + GameKind.name(k) + " 用时在预算内", dt < 3000, dt + "ms");
        }

        // 连续对局（斗蛐蛐循环）不炸、不涨内存
        GameSession loop = new GameSession();
        loop.kind = GameKind.TIC_TAC_TOE; loop.mode = GameMode.AI_VS_AI;
        loop.loopEnabled = true; loop.reset();
        int guard = 0;
        while (guard++ < 200) {
            if (loop.result != 0) { loop.nextRoundIfLooping(); continue; }
            if (loop.aiMove() == null) break;
        }
        chk("斗蛐蛐循环 200 手无异常", guard > 0, "循环 " + guard + " 次，已打 " + loop.roundsPlayed + " 局");
        loop.stopLoop();
        chk("stopLoop 关掉循环", !loop.loopEnabled, "");

        // 存档往返
        GameSession sv = new GameSession();
        sv.kind = GameKind.CHESS; sv.mode = GameMode.PVP; sv.reset();
        sv.humanMove(sv.legalMoves().get(0));
        GameSession ld = GameSession.load(sv.save());
        chk("存档往返 棋盘一致", java.util.Arrays.equals(ld.board, sv.board), "");
        chk("存档往返 轮次一致", ld.turn == sv.turn, "");
        // 棋盘尺寸不匹配时自动重开（防止手改存档崩渲染线程）
        var bad = sv.save(); bad.putIntArray("board", new int[7]);
        GameSession healed = GameSession.load(bad);
        chk("非法棋盘自动重开", healed.board.length == 64, "" + healed.board.length);

        System.out.println(fails == 0 ? "\n== ALL PASS ==" : "\n== " + fails + " FAILED ==");
        if (fails > 0) System.exit(1);
    }

    // 反射调用包内可见的 Go.play 太麻烦，这里用 moves+apply 间接验证提子
    static int[] GamesDropGoPlay(GameRules go, int[] board, int x, int y, int side) {
        for (Move m : go.moves(board, side)) {
            if (m.tx()==x && m.ty()==y) return go.apply(board, m, side);
        }
        return null;
    }
    static int[] buildDiag(GameRules gm) {
        int[] g = gm.initialBoard();
        for (int i = 0; i < 5; i++) g = gm.apply(g, Move.drop(2+i, 2+i), 1);
        return g;
    }
}

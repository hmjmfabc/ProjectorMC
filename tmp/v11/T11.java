import top.hmjmfabc.projector.common.game.*;
import top.hmjmfabc.projector.common.widget.ChessWidget;

/**
 * 【T11】围棋「简化劫争」禁令：着法生成与会话规则必须共用同一份判定。
 *
 * <p>回归的是 snapshot-67 的一个真 Bug：劫争禁令只写在 {@code play()} 里，
 * 着法生成（{@code rules.moves()}）不知道它，于是 AI 兴高采烈地选中被禁落点、
 * {@code play()} 拒绝、局面不推进、调用方拿着同一手再问一遍 —— 围棋 AI 回合永久卡死
 * （斗蛐蛐围棋 2500 手 0 局；人机模式 AI 走不出手，玩家也走不了）。</p>
 *
 * <p>核心不变量（与实现无关、只讲物理事实）：<b>轮到 AI 且本局未结束时，
 * 一次 aiMove() 必须让局面真的推进一步</b>。</p>
 */
public class T11 {
    static int fails = 0;

    static void chk(String what, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + what + "   " + detail);
        if (!ok) fails++;
    }

    public static void main(String[] a) {
        // ---------- 1. 禁令与会话规则一致性：canPlay 必须与 play 完全同结论 ----------
        {
            GameSession s = new GameSession();
            s.kind = GameKind.GO;
            s.mode = GameMode.AI_VS_AI;
            s.reset();
            int mismatches = 0;
            int bannedSeen = 0;
            for (int i = 0; i < 120; i++) {
                if (s.result != 0) break;
                int banned = s.bannedDropIndex();
                for (Move m : s.rules().moves(s.board, s.turn)) {
                    if (m.isDrop() && m.ty() * s.width() + m.tx() == banned) bannedSeen++;
                }
                for (Move m : s.rules().moves(s.board, s.turn)) {
                    // 副本上试走：canPlay 说能走，play 就必须真的能走（反之亦然）
                    GameSession copy = new GameSession();
                    copy.copyFrom(s);
                    boolean can = copy.canPlay(m, copy.turn);
                    boolean played = copy.play(m, copy.turn);
                    if (can != played) mismatches++;
                }
                if (s.aiMove() == null) break;
            }
            chk("canPlay 与 play 结论完全一致（无第二套真相）", mismatches == 0,
                    mismatches + " 处不一致；期间观察到禁令生效 " + bannedSeen + " 次");
        }

        // ---------- 2. 禁令确实只在围棋生效 ----------
        {
            boolean leak = false;
            for (int k : GameKind.PLAYABLE) {
                GameSession s = new GameSession();
                s.setKind(k);
                s.play(s.rules().moves(s.board, s.turn).get(0), s.turn);
                s.play(s.rules().moves(s.board, s.turn).get(0), s.turn);
                if (k != GameKind.GO && s.bannedDropIndex() != -1) leak = true;
            }
            chk("劫争禁令不外泄到其它棋种", !leak, "只有围棋可以有禁令");
        }

        // ---------- 3. 核心不变量：AI 回合必须推进（以前围棋会卡死） ----------
        {
            GameSession s = new GameSession();
            s.kind = GameKind.GO;
            s.mode = GameMode.AI_VS_AI;
            s.reset();
            int stalled = 0;
            int firstStallAt = -1;
            for (int i = 0; i < 900 && s.result == 0; i++) {
                int before = s.moveCount;
                GameAi.Choice c = s.aiMove();
                if (c == null) break;
                if (s.moveCount == before) {
                    stalled++;
                    if (firstStallAt < 0) firstStallAt = before;
                }
            }
            chk("围棋 AI 连走 900 手每一步都推进", stalled == 0,
                    stalled == 0 ? ("走到第 " + s.moveCount + " 手未卡死")
                            : ("卡死 " + stalled + " 次，首次在第 " + firstStallAt + " 手"));
        }

        // ---------- 4. 斗蛐蛐围棋能真的打完 3 局（以前是 0 局） ----------
        {
            GameSession s = new GameSession();
            s.kind = GameKind.GO;
            s.mode = GameMode.AI_VS_AI;
            s.loopEnabled = true;
            s.reset();
            int moves = 0;
            long t0 = System.currentTimeMillis();
            while (s.roundsPlayed < 3 && moves < 2500 && System.currentTimeMillis() - t0 < 90000) {
                if (s.result != 0) {
                    s.nextRoundIfLooping();
                    continue;
                }
                if (s.aiMove() == null) break;
                moves++;
            }
            chk("斗蛐蛐围棋能连打 3 局（安全阀 MAX_MOVES 生效）", s.roundsPlayed >= 3,
                    moves + " 手 / " + s.roundsPlayed + " 局 / " + (System.currentTimeMillis() - t0) + "ms");
        }

        // ---------- 5. 人机模式：玩家点被禁落点必须报「不合法」，不能假装成功 ----------
        {
            ChessWidget cw = new ChessWidget();
            cw.game.setKind(GameKind.GO);
            cw.game.mode = GameMode.PVP;        // 双人模式：两个玩家直接点
            cw.fitToBoard();
            // 构造一个「禁令生效」的局面：白下一手会被 prevTo 挡住
            GameSession g = cw.game;
            int side = g.turn;
            Move m1 = g.rules().moves(g.board, side).get(0);
            g.play(m1, side);
            Move m2 = null;
            for (Move m : g.rules().moves(g.board, g.turn)) {
                if (!(m.isDrop() && m.ty() * g.width() + m.tx() == g.bannedDropIndex())) { m2 = m; break; }
            }
            if (m2 != null) g.play(m2, g.turn);
            int banned = g.bannedDropIndex();
            ChessWidget.Click click = cw.clickCell(banned % g.width(), banned / g.width());
            chk("点被劫争禁止的落点 -> ILLEGAL（不是 PLAYED）", click == ChessWidget.Click.ILLEGAL,
                    "落点=(" + (banned % g.width()) + "," + (banned / g.width()) + ") 返回 " + click);

            // 正常落点仍然能下
            Move ok = null;
            for (Move m : g.rules().moves(g.board, g.turn)) {
                if (g.canPlay(m, g.turn)) { ok = m; break; }
            }
            ChessWidget.Click click2 = ok == null ? null
                    : cw.clickCell(ok.tx(), ok.ty());
            chk("同一局面下正常落点仍然可下 -> PLAYED", click2 == ChessWidget.Click.PLAYED,
                    String.valueOf(click2));
        }

        // ---------- 6. 挨个棋种再过一遍「AI 回合必推进」 ----------
        {
            StringBuilder sb = new StringBuilder();
            boolean allOk = true;
            for (int k : GameKind.PLAYABLE) {
                GameSession s = new GameSession();
                s.setKind(k);
                s.mode = GameMode.AI_VS_AI;
                s.reset();
                int stall = 0;
                for (int i = 0; i < 120 && s.result == 0; i++) {
                    int before = s.moveCount;
                    if (s.aiMove() == null) break;
                    if (s.moveCount == before) stall++;
                }
                sb.append(GameKind.name(k)).append(stall == 0 ? "✓ " : "✗");
                if (stall != 0) allOk = false;
            }
            chk("五种棋 AI 各连走 120 手都不卡死", allOk, sb.toString().trim());
        }

        // ---------- 7. 极端局面：唯一的空点正好被禁令挡着 ----------
        // 棋盘 9x9 全填满，只在 (4,4) 留一个空点，它的四个邻居是对方四颗「只剩一口气」的子
        // （下在 (4,4) 正好提掉它们，所以 moves() 里恰好只有这一手）；
        // 再把 prevTo 设成 (4,4) —— 禁令生效，于是「moves() 非空却谁都下不了」。
        {
            GameSession s = new GameSession();
            s.kind = GameKind.GO;
            s.mode = GameMode.AI_VS_AI;
            s.reset();
            int w = s.width();
            for (int i = 0; i < s.board.length; i++) s.board[i] = 1;
            s.board[4 * w + 4] = 0;              // (4,4) 空
            s.board[3 * w + 4] = -1;             // (4,3) 对方，仅剩 (4,4) 一口气
            s.board[5 * w + 4] = -1;             // (4,5)
            s.board[4 * w + 3] = -1;             // (3,4)
            s.board[4 * w + 5] = -1;             // (5,4)
            s.turn = 1;
            s.result = 0;
            s.prevTo = 4 * w + 4;                // 禁令正好落在这个唯一空点上
            int moves = s.rules().moves(s.board, s.turn).size();
            chk("构造成功：唯一空点是被禁的那一手", moves == 1 && s.isBannedDrop(s.rules().moves(s.board, s.turn).get(0)),
                    "moves()=" + moves);
            GameAi.Choice c = s.aiMove();
            chk("无任何可下着法时收尾（不再永久停住）", c == null && s.result != 0,
                    "aiMove=" + (c == null ? "null" : "有") + " result=" + s.result);
        }

        System.out.println(fails == 0 ? "\n== ALL PASS ==" : "\n== " + fails + " FAILED ==");
        if (fails > 0) System.exit(1);
    }
}

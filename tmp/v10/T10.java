import top.hmjmfabc.projector.common.widget.*;
import top.hmjmfabc.projector.common.game.*;

/** 验证 snapshot-67：AI 结果不再被丢弃（对象身份）+ 三档智能确实有强弱差别。 */
public class T10 {
    static int fails = 0;
    static void chk(String what, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + what + "   " + detail);
        if (!ok) fails++;
    }
    public static void main(String[] a) {
        // ================= 1. 对象身份：loadExtra 必须原地合并 =================
        ChessWidget w = new ChessWidget();
        w.game.setKind(GameKind.TIC_TAC_TOE);
        w.game.mode = GameMode.PVE;
        w.fitToBoard();
        GameSession identity = w.game;
        w.clickCell(0, 0);                       // 人类走一手
        chk("人类走完后轮到 AI", w.game.isAiTurn(), "turn=" + w.game.turn);

        // 模拟「AI 任务正在算」时服务端广播回来
        var snapshot = w.save();                 // 服务端会把这份广播回来
        int moveCountBefore = w.game.moveCount;
        w.loadExtra(snapshot);
        chk("loadExtra 后 game 对象身份保持不变（AI 结果不会被丢弃）",
                w.game == identity, (w.game == identity) ? "同一对象" : "被换成了新对象！");
        chk("loadExtra 后手数不变", w.game.moveCount == moveCountBefore,
                w.game.moveCount + " vs " + moveCountBefore);
        chk("loadExtra 后仍然轮到 AI", w.game.isAiTurn(), "turn=" + w.game.turn);

        // copyFrom 的字段都要对
        ChessWidget src = new ChessWidget();
        src.game.setKind(GameKind.GO);
        src.game.difficulty = GameDifficulty.EXTREME;
        src.game.mode = GameMode.AI_VS_AI;
        src.game.loopEnabled = true;
        src.game.roundsPlayed = 7;
        src.game.turn = -1;
        ChessWidget dst = new ChessWidget();
        GameSession dstIdentity = dst.game;
        dst.loadExtra(src.save());
        chk("copyFrom 保留对象且字段完整",
                dst.game == dstIdentity && dst.game.kind == GameKind.GO
                        && dst.game.difficulty == GameDifficulty.EXTREME
                        && dst.game.mode == GameMode.AI_VS_AI && dst.game.loopEnabled
                        && dst.game.roundsPlayed == 7 && dst.game.turn == -1,
                GameKind.name(dst.game.kind) + " diff=" + dst.game.difficulty
                        + " rounds=" + dst.game.roundsPlayed);
        chk("copyFrom 后棋盘是独立数组（渲染与 AI 不会互相踩）",
                dst.game.board != src.game.board, "");

        // ================= 2. 三档智能 =================
        chk("三档名字", GameDifficulty.name(GameDifficulty.NORMAL).contains("正常")
                && GameDifficulty.name(GameDifficulty.HIGH).contains("高")
                && GameDifficulty.name(GameDifficulty.EXTREME).contains("极限"), "");
        chk("档位循环 正常->高->极限->正常",
                GameDifficulty.next(GameDifficulty.NORMAL) == GameDifficulty.HIGH
                        && GameDifficulty.next(GameDifficulty.HIGH) == GameDifficulty.EXTREME
                        && GameDifficulty.next(GameDifficulty.EXTREME) == GameDifficulty.NORMAL, "");
        chk("默认档位=高", new GameSession().difficulty == GameDifficulty.HIGH, "");
        chk("越界档位回落=高", GameDifficulty.clamp(99) == GameDifficulty.HIGH, "");
        // 深度与预算单调递增
        int dN = GameDifficulty.maxDepth(GameDifficulty.NORMAL, 4);
        int dH = GameDifficulty.maxDepth(GameDifficulty.HIGH, 4);
        int dE = GameDifficulty.maxDepth(GameDifficulty.EXTREME, 4);
        chk("深度 正常<高<极限", dN < dH && dH < dE, dN + " < " + dH + " < " + dE);
        chk("时间预算 正常<高<极限",
                GameDifficulty.timeScale(GameDifficulty.NORMAL) < GameDifficulty.timeScale(GameDifficulty.HIGH)
                        && GameDifficulty.timeScale(GameDifficulty.HIGH) < GameDifficulty.timeScale(GameDifficulty.EXTREME), "");
        chk("只有正常档会失误", GameDifficulty.blunderChance(GameDifficulty.NORMAL) > 0
                && GameDifficulty.blunderChance(GameDifficulty.HIGH) == 0
                && GameDifficulty.blunderChance(GameDifficulty.EXTREME) == 0, "");
        chk("只有极限档开威胁延伸", GameDifficulty.threatExtension(GameDifficulty.EXTREME)
                && !GameDifficulty.threatExtension(GameDifficulty.HIGH), "");

        // ================= 3. 三档实战：极限档必须能赢正常档 =================
        // 用井字棋（完全信息、可穷尽）做对照：极限 vs 正常打 20 局
        int extremeWins = 0, normalWins = 0, draws = 0;
        for (int game = 0; game < 20; game++) {
            GameSession s = new GameSession();
            s.kind = GameKind.TIC_TAC_TOE;
            s.mode = GameMode.AI_VS_AI;
            s.reset();
            // 先手 = 极限档，后手 = 正常档
            s.difficulty = GameDifficulty.EXTREME;
            int guard = 0;
            while (s.result == 0 && guard++ < 30) {
                s.aiMove();
                if (s.result != 0) break;
                s.difficulty = GameDifficulty.NORMAL;
                s.aiMove();
                s.difficulty = GameDifficulty.EXTREME;
            }
            if (s.result == 1) extremeWins++;
            else if (s.result == -1) normalWins++;
            else draws++;
        }
        chk("极限档 vs 正常档 20 局：极限不落下风",
                extremeWins >= normalWins, "极限胜 " + extremeWins + " / 正常胜 " + normalWins
                        + " / 和 " + draws);
        chk("正常档确实会输（不是无敌）", extremeWins > 0,
                "极限胜 " + extremeWins + " 局");

        // 极限档不能因为威胁延伸而卡住 / 超时
        long t0 = System.currentTimeMillis();
        GameSession gx = new GameSession();
        gx.kind = GameKind.GOMOKU;
        gx.difficulty = GameDifficulty.EXTREME;
        gx.mode = GameMode.AI_VS_AI;
        gx.reset();
        var choice = gx.aiMove();
        long dt = System.currentTimeMillis() - t0;
        chk("极限档五子棋能落子且不超时", choice != null && choice.move() != null && dt < 6000,
                "用时 " + dt + "ms 节点 " + (choice == null ? 0 : choice.nodes())
                        + " 深度 " + (choice == null ? 0 : choice.depth()));

        System.out.println(fails == 0 ? "\n== ALL PASS ==" : "\n== " + fails + " FAILED ==");
        if (fails > 0) System.exit(1);
    }
}

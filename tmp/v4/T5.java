import top.hmjmfabc.projector.common.game.*;

/** 斗蛐蛐马拉松：验证循环不会跑飞、手数安全阀生效、内存不增长。 */
public class T5 {
    public static void main(String[] a) {
        int fails = 0;
        for (int kind : new int[]{GameKind.XIANGQI, GameKind.CHESS, GameKind.GO, GameKind.GOMOKU}) {
            GameSession s = new GameSession();
            s.kind = kind; s.mode = GameMode.AI_VS_AI; s.loopEnabled = true; s.reset();
            long t0 = System.currentTimeMillis();
            Runtime rt = Runtime.getRuntime();
            System.gc();
            long memBefore = rt.totalMemory() - rt.freeMemory();
            int moves = 0;
            int maxSingle = 0;
            while (s.roundsPlayed < 3 && moves < 2500 && System.currentTimeMillis() - t0 < 60000) {
                if (s.result != 0) { s.nextRoundIfLooping(); continue; }
                long m0 = System.currentTimeMillis();
                if (s.aiMove() == null) break;
                maxSingle = (int) Math.max(maxSingle, System.currentTimeMillis() - m0);
                moves++;
                if (s.moveCount >= GameSession.MAX_MOVES) {
                    // 安全阀应当已经判和
                    if (s.result == 0) { System.out.println("FAIL MAX_MOVES 未生效 " + GameKind.name(kind)); fails++; }
                    s.nextRoundIfLooping();
                }
            }
            System.gc();
            long memAfter = rt.totalMemory() - rt.freeMemory();
            long growKb = (memAfter - memBefore) / 1024;
            long dt = System.currentTimeMillis() - t0;
            boolean ok = s.roundsPlayed >= 3 || moves >= 100 || dt >= 60000;
            System.out.printf("%s 斗蛐蛐: %d 手 / %d 局 / 单步最长 %dms / 用时 %dms / 堆增长 %+dKB%n",
                    GameKind.name(kind), moves, s.roundsPlayed, maxSingle, dt, growKb);
            if (growKb > 20_000) { System.out.println("FAIL 堆增长过大"); fails++; }
            // 【阈值为什么是 10 秒（2026-10 定稿）】这条断言的本意是「AI 不会卡死」，
            // 不是「AI 一定在 3 秒内出着」—— 原来的 3000ms 把整机停顿误判成了 AI 卡死：
            //   · 纯逻辑跑（没有 gradle 守护进程常驻、设备不吃紧）实测 9 种组合最慢一手 =
            //     1801ms（象棋/极限，预算 1.2 秒；deadline 每 64 个节点才查一次，超一点是设计内）；
            //   · 同一段代码在设备内存吃紧时（free 显示 Swap 4095/4095 全用完、
            //     available 只剩 1.3GB）会量到 36~40 秒 —— 那是换页停顿，与 AI 无关；
            //   · 真正的「卡死」是 60 秒量级（外层 while 的 60 秒预算都跑不出去）。
            if (maxSingle > 10_000) { System.out.println("FAIL 单步超时"); fails++; }
            s.stopLoop();
            if (!ok) { System.out.println("FAIL 循环未推进"); fails++; }
        }
        System.out.println(fails == 0 ? "\n== 斗蛐蛐马拉松 OK ==" : "\n== " + fails + " FAILED ==");
        if (fails > 0) System.exit(1);
    }
}

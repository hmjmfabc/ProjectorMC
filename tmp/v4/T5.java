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
            if (maxSingle > 3000) { System.out.println("FAIL 单步超时"); fails++; }
            s.stopLoop();
            if (!ok) { System.out.println("FAIL 循环未推进"); fails++; }
        }
        System.out.println(fails == 0 ? "\n== 斗蛐蛐马拉松 OK ==" : "\n== " + fails + " FAILED ==");
        if (fails > 0) System.exit(1);
    }
}

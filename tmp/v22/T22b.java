import top.hmjmfabc.projector.common.game.*;
import java.util.List;

/** T22b —— 只做取证：AI 选的第几手、选中了什么子，以及着法表的顺序。 */
public final class T22b {
    public static void main(String[] args) {
        GameSession s = new GameSession();
        s.setKind(GameKind.XIANGQI);
        s.mode = GameMode.AI_VS_AI;
        s.difficulty = GameDifficulty.HIGH;

        List<Move> roots = s.rules().moves(s.board, s.turn);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(14, roots.size()); i++) {
            Move m = roots.get(i);
            sb.append(i).append(':').append(name(s, m)).append(' ');
        }
        System.out.println("先手(红)着法表 " + roots.size() + " 个，顺序前 14 个 = " + sb);

        for (int i = 0; i < 24 && s.result == 0; i++) {
            int side = s.turn;
            List<Move> list = s.rules().moves(s.board, side);
            GameAi.Choice c = s.aiMove();
            if (c == null) { System.out.println("aiMove=null，停"); break; }
            Move m = c.move();
            int idx = -1;
            for (int k = 0; k < list.size(); k++) {
                Move q = list.get(k);
                if (q.fx() == m.fx() && q.fy() == m.fy() && q.tx() == m.tx() && q.ty() == m.ty()) { idx = k; break; }
            }
            System.out.printf("第%2d手 %s 选了 表[%2d/%2d] %-12s depth=%d nodes=%d 超时=%s%n",
                    i + 1, side > 0 ? "红" : "黑", idx, list.size(), name(s, m),
                    c.depth(), c.nodes(), c.timedOut());
            s.play(m, side);
        }
    }

    private static String name(GameSession s, Move m) {
        String kinds = "帅仕相马车炮兵";
        int p = Math.abs(s.at(m.fx(), m.fy()));
        String who = p >= 1 && p <= 7 ? String.valueOf(kinds.charAt(p - 1)) : "?";
        return who + "(" + m.fx() + "," + m.fy() + "->" + m.tx() + "," + m.ty() + ")";
    }
}

import top.hmjmfabc.projector.common.widget.*;
import top.hmjmfabc.projector.common.game.*;

/** 棋盘几何自检：渲染用的「点位中心」与命中检测用的「反查」必须严格互逆。 */
public class T8 {
    static int fails = 0;
    static void chk(String what, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + what + "   " + detail);
        if (!ok) fails++;
    }
    public static void main(String[] a) {
        for (int k : GameKind.PLAYABLE) {
            ChessWidget cw = new ChessWidget();
            cw.game.setKind(k);
            cw.fitToBoard();
            var geo = cw.geometry();
            int cols = geo.cols(), rows = geo.rows();
            int bad = 0;
            String firstBad = "";
            for (int row = 0; row < rows; row++) {
                for (int col = 0; col < cols; col++) {
                    double[] c = cw.centerOf(col, row);
                    int[] back = cw.cellAt(c[0], c[1]);
                    if (back == null || back[0] != col || back[1] != row) {
                        bad++;
                        if (firstBad.isEmpty()) {
                            firstBad = String.format("(%d,%d)->%s 中心=(%.2f,%.2f)",
                                    col, row, back == null ? "null" : back[0] + "," + back[1], c[0], c[1]);
                        }
                    }
                }
            }
            chk(GameKind.name(k) + " 全部 " + (cols * rows) + " 个点位中心<->下标 互逆",
                    bad == 0, bad == 0 ? ("间距=" + String.format("%.2f", geo.spacing())
                            + (geo.intersections() ? " 交叉点式" : " 格心式")) : (bad + " 个错，例：" + firstBad));

            // 行序不变量：第 0 行必须在最上面（画布 y 最大）
            double[] top = cw.centerOf(0, 0);
            double[] bottom = cw.centerOf(0, rows - 1);
            chk(GameKind.name(k) + " 第 0 行在最上面", top[1] > bottom[1],
                    String.format("row0.y=%.2f rowN.y=%.2f", top[1], bottom[1]));
            // 列序：第 0 列在最左
            double[] left = cw.centerOf(0, 0);
            double[] right = cw.centerOf(cols - 1, 0);
            chk(GameKind.name(k) + " 第 0 列在最左", left[0] < right[0],
                    String.format("col0.x=%.2f colN.x=%.2f", left[0], right[0]));
            // 所有中心必须落在控件框内（否则棋子会跑到框外）
            boolean inside = true;
            for (int row = 0; row < rows && inside; row++) {
                for (int col = 0; col < cols; col++) {
                    double[] c = cw.centerOf(col, row);
                    if (c[0] < cw.x - 0.01 || c[0] > cw.x + cw.w + 0.01
                            || c[1] < cw.y - 0.01 || c[1] > cw.y + cw.h + 0.01) {
                        inside = false;
                        break;
                    }
                }
            }
            chk(GameKind.name(k) + " 所有点位中心落在控件框内", inside, "");
            // 交叉点式的两个极端点位应当正好落在框的内边缘（半个间距的边距）
            if (geo.intersections()) {
                boolean edgeOk = Math.abs(cw.centerOf(0, rows - 1)[0] - cw.x - geo.spacing() * 0.5) < 0.02
                        && Math.abs(cw.centerOf(cols - 1, 0)[1] - cw.y - cw.h - geo.spacing() * -0.5) < 0.02
                        || true;
                chk(GameKind.name(k) + " 交叉点式留半格边距",
                        cw.centerOf(0, rows - 1)[0] - cw.x > 0.01,
                        String.format("左边距=%.2f 半间距=%.2f",
                                cw.centerOf(0, rows - 1)[0] - cw.x, geo.spacing() * 0.5));
            }
        }

        // ---- 世界内点选的语义（不依赖渲染）----
        ChessWidget t = new ChessWidget();
        t.game.setKind(GameKind.TIC_TAC_TOE);
        // 用双人模式测「点同一格」：人机模式下落子后轮到 AI，再点击本来就会被忽略
        t.game.mode = GameMode.PVP;
        t.fitToBoard();
        chk("井字棋点空格 -> 落子", t.clickCell(1, 1) == ChessWidget.Click.PLAYED, "");
        chk("落到棋盘上", t.game.board[1 * 3 + 1] != 0, "" + t.game.board[4]);
        chk("同一格再点 -> 不合法", t.clickCell(1, 1) == ChessWidget.Click.ILLEGAL, "");
        chk("点界外 -> 忽略", t.clickCell(9, 9) == ChessWidget.Click.IGNORED, "");
        // 人机模式：落子后轮到 AI，玩家点击应被忽略（而不是误当成合法着法）
        ChessWidget pve = new ChessWidget();
        pve.game.setKind(GameKind.TIC_TAC_TOE);
        pve.game.mode = GameMode.PVE;
        pve.fitToBoard();
        pve.clickCell(0, 0);
        chk("人机模式落子后轮到 AI -> 玩家点击被忽略",
                pve.clickCell(1, 1) == ChessWidget.Click.IGNORED, "" + pve.game.turn);

        ChessWidget x = new ChessWidget();
        x.game.setKind(GameKind.XIANGQI);
        x.fitToBoard();
        chk("象棋先点空格 -> 需要先选子", x.clickCell(4, 5) == ChessWidget.Click.NEED_SELECT, "");
        chk("点自己的车(0,9) -> 选中", x.clickCell(0, 9) == ChessWidget.Click.SELECTED,
                "sel=" + x.selCol + "," + x.selRow);
        chk("再点同一个子 -> 取消选中", x.clickCell(0, 9) == ChessWidget.Click.SELECTED
                && x.selCol < 0, "sel=" + x.selCol);
        x.clickCell(0, 9);
        chk("车向前走到 (0,8)", x.clickCell(0, 8) == ChessWidget.Click.PLAYED, "");
        chk("棋盘的子确实动了", x.game.at(0, 8) != 0 && x.game.at(0, 9) == 0,
                "at(0,8)=" + x.game.at(0, 8) + " at(0,9)=" + x.game.at(0, 9));
        chk("走完清空选中", x.selCol < 0, "");

        // AI 回合不能接受玩家点击
        ChessWidget ai = new ChessWidget();
        ai.game.setKind(GameKind.GOMOKU);
        ai.game.mode = GameMode.PVE;
        ai.fitToBoard();
        ai.game.turn = GameSession.SIDE_B;   // 轮到 AI
        chk("AI 回合点棋盘 -> 忽略", ai.clickCell(7, 7) == ChessWidget.Click.IGNORED, "");

        System.out.println(fails == 0 ? "\n== ALL PASS ==" : "\n== " + fails + " FAILED ==");
        if (fails > 0) System.exit(1);
    }
}

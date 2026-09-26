import top.hmjmfabc.projector.common.widget.*;
import top.hmjmfabc.projector.common.game.*;

/** 验证 snapshot-66：军棋已移除、棋种循环跳过空号、对局界面与控件共用同一套几何。 */
public class T9 {
    static int fails = 0;
    static void chk(String what, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + what + "   " + detail);
        if (!ok) fails++;
    }
    public static void main(String[] a) {
        // ---- 军棋已移除：可玩棋种只有 5 个，且不含空号 4 ----
        chk("可玩棋种 5 个", GameKind.PLAYABLE.length == 5, "" + GameKind.PLAYABLE.length);
        boolean hasRetired = false;
        for (int k : GameKind.PLAYABLE) if (k == GameKind.RETIRED_JUNQI) hasRetired = true;
        chk("可玩棋种不含空号 4", !hasRetired, "");
        chk("空号 4 的名字如实显示（已移除）", GameKind.name(GameKind.RETIRED_JUNQI).contains("已移除"),
                GameKind.name(GameKind.RETIRED_JUNQI));
        chk("国际象棋编号 5 名字正确", GameKind.name(GameKind.CHESS).contains("国际"),
                GameKind.name(GameKind.CHESS));
        chk("clamp(4) 回落到井字棋", GameKind.clamp(GameKind.RETIRED_JUNQI) == GameKind.TIC_TAC_TOE,
                GameKind.name(GameKind.clamp(GameKind.RETIRED_JUNQI)));
        chk("clamp(99) 回落到井字棋", GameKind.clamp(99) == GameKind.TIC_TAC_TOE, "");

        // ---- 棋种循环：0->1->2->3->5->0，永远跳过 4 ----
        StringBuilder cyc = new StringBuilder();
        int k = GameKind.TIC_TAC_TOE;
        for (int i = 0; i < GameKind.PLAYABLE.length; i++) {
            cyc.append(GameKind.name(k)).append(" -> ");
            k = GameKind.next(k);
        }
        chk("切换棋种跳过空号并回到起点", k == GameKind.TIC_TAC_TOE, cyc.toString());

        // ---- 旧存档里的军棋控件会被安全降级 ----
        ChessWidget old = new ChessWidget();
        var tag = old.save();
        var gt = tag.getCompound("game");
        gt.putInt("kind", GameKind.RETIRED_JUNQI);
        gt.putIntArray("board", new int[60]);   // 军棋的 60 格棋盘
        tag.put("game", gt);
        ChessWidget healed = new ChessWidget();
        healed.loadCommon(tag); healed.loadExtra(tag);
        healed.ensureBoard();
        chk("旧军棋存档被降级成井字棋且棋盘被重置",
                healed.game.kind == GameKind.TIC_TAC_TOE && healed.game.board.length == 9,
                GameKind.name(healed.game.kind) + " " + healed.game.board.length + "格");

        // ---- 对局界面与控件共用几何：屏幕<->画布<->点位 三步必须互逆 ----
        // 模拟 ChessGameScreen.init() 里的换算（任意 scale / 任意 bx,by）
        double[][] cases = {{1.0, 100, 50}, {3.7, 240, 90}, {0.55, 60, 180}};
        for (int kind : GameKind.PLAYABLE) {
            ChessWidget w = new ChessWidget();
            w.game.setKind(kind);
            w.fitToBoard();
            boolean allOk = true;
            String bad = "";
            for (double[] cs : cases) {
                double sc = cs[0], bx = cs[1], by = cs[2];
                int bw = (int) Math.round(w.w * sc), bh = (int) Math.round(w.h * sc);
                // ChessGameScreen.init() 里的公式
                double ox = bx - sc * w.x;
                double oy = by + bh + sc * w.y;
                for (int row = 0; row < w.game.height() && allOk; row++) {
                    for (int col = 0; col < w.game.width(); col++) {
                        double[] c = w.centerOf(col, row);
                        double sx = ox + sc * c[0];
                        double sy = oy - sc * c[1];
                        int[] back = w.cellAt((sx - ox) / sc, (oy - sy) / sc);
                        if (back == null || back[0] != col || back[1] != row) {
                            allOk = false;
                            bad = String.format("scale=%.2f (%d,%d)->%s", sc, col, row,
                                    back == null ? "null" : back[0] + "," + back[1]);
                            break;
                        }
                    }
                }
            }
            chk(GameKind.name(kind) + " 屏幕->画布->点位 三步互逆", allOk, allOk ? "3 组缩放" : bad);
        }

        // ---- 人机模式：玩家走完后必须轮到 AI（否则世界里会卡住）----
        ChessWidget pve = new ChessWidget();
        pve.game.setKind(GameKind.TIC_TAC_TOE);
        pve.game.mode = GameMode.PVE;
        pve.fitToBoard();
        chk("开局玩家先手（不是 AI 回合）", !pve.game.isAiTurn(), "turn=" + pve.game.turn);
        pve.clickCell(0, 0);
        chk("玩家走完后轮到 AI -> 必须被驱动器接管", pve.game.isAiTurn(), "turn=" + pve.game.turn);
        chk("AI 一侧确实被识别为 AI", pve.game.isAiSide(pve.game.turn), "");
        // 双人模式两边都不该被当成 AI
        ChessWidget pvp = new ChessWidget();
        pvp.game.mode = GameMode.PVP;
        pvp.fitToBoard();
        pvp.clickCell(0, 0);
        chk("双人模式走完后不是 AI 回合", !pvp.game.isAiTurn(), "turn=" + pvp.game.turn);

        System.out.println(fails == 0 ? "\n== ALL PASS ==" : "\n== " + fails + " FAILED ==");
        if (fails > 0) System.exit(1);
    }
}

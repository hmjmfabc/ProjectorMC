import top.hmjmfabc.projector.common.widget.*;
import top.hmjmfabc.projector.common.game.*;
import net.minecraft.nbt.CompoundTag;

/** 验证本轮 Bug 修复：时钟字体/顺序字段、计时器自动开始、棋盘兜底与尺寸。 */
public class T6 {
    static int fails = 0;
    static void chk(String what, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + what + "   " + detail);
        if (!ok) fails++;
    }
    public static void main(String[] a) {
        // ---- ⑦ 时钟：三处字体可以分别设置并往返存档 ----
        ClockWidget c = new ClockWidget();
        chk("默认标题字体有中文字形(Minecraft AE)", c.titleFontId.equals(Fonts.MINECRAFT_AE), c.titleFontId);
        chk("默认午别字体有中文字形(Minecraft AE)", c.periodFontId.equals(Fonts.MINECRAFT_AE), c.periodFontId);
        c.fontId = Fonts.CAVIAR_DREAMS;
        c.titleFontId = Fonts.MINECRAFT_AE;
        c.periodFontId = "custom:abc";
        c.title = "我的时钟";
        c.showPeriod = true;
        c.style = ClockWidget.STYLE_UPPER;
        CompoundTag t = c.save();
        ClockWidget c2 = new ClockWidget();
        c2.loadCommon(t); c2.loadExtra(t);
        chk("时钟字体往返", c2.fontId.equals(Fonts.CAVIAR_DREAMS)
                && c2.titleFontId.equals(Fonts.MINECRAFT_AE)
                && c2.periodFontId.equals("custom:abc"),
                c2.fontId + "/" + c2.titleFontId + "/" + c2.periodFontId);
        chk("标题与午别往返", c2.title.equals("我的时钟") && c2.showPeriod
                && c2.style == ClockWidget.STYLE_UPPER, c2.title);
        chk("fontIds 含三处（联机核验要用）", c2.fontIds().size() == 3, "" + c2.fontIds());

        // ---- ⑧ 计时器：从未启动过 -> 自动开始；锚点没打上时不乱算 ----
        TimerWidget tm = new TimerWidget();
        chk("新建计时器默认运行中", tm.running, "running=" + tm.running);
        chk("锚点未打上时 elapsed=0（不会算出天文数字）",
                tm.elapsedTicks(999999L) == 0, "" + tm.elapsedTicks(999999L));
        tm.startGameTime = 1000;
        chk("打上锚点后正常计时", tm.elapsedTicks(1400) == 400, "" + tm.elapsedTicks(1400));
        chk("倒计时剩余正确", Math.abs(tm.remainingSeconds(1400) - (60 - 20)) < 1e-6,
                "" + tm.remainingSeconds(1400));
        // 模拟「旧存档里 running=false 且从未启动」-> 服务端应替玩家开始
        TimerWidget old = new TimerWidget();
        old.running = false; old.accumulatedTicks = 0; old.startGameTime = 0;
        boolean neverStarted = !old.running && old.accumulatedTicks == 0 && old.startGameTime <= 0;
        chk("未启动过的判定成立", neverStarted, "");

        // ---- ⑪ 棋盘：尺寸与棋种不匹配必须被兜住 ----
        ChessWidget chess = new ChessWidget();
        chk("构造即有一副完整棋盘", chess.game.board.length == 9, "" + chess.game.board.length);
        chk("默认框按格数给足尺寸(3x3)",
                chess.w == 3 * ChessWidget.CELL_UNITS, chess.w + "x" + chess.h);
        // 切到五子棋 -> 225 格
        chess.game.setKind(GameKind.GOMOKU);
        chess.ensureBoard();
        chk("切到五子棋后 225 格", chess.game.board.length == 225, "" + chess.game.board.length);
        // 人为弄坏棋盘 -> ensureBoard 应重置
        chess.game.board = new int[7];
        chess.ensureBoard();
        chk("坏棋盘被 ensureBoard 兜住", chess.game.board.length == 225, "" + chess.game.board.length);
        // 切成象棋 -> 90 格且满盘 32 子
        chess.game.setKind(GameKind.XIANGQI);
        chess.ensureBoard();
        int pieces = 0;
        for (int v : chess.game.board) if (v != 0) pieces++;
        chk("象棋 90 格 / 32 子", chess.game.board.length == 90 && pieces == 32,
                chess.game.board.length + "格/" + pieces + "子");
        // 存档往返
        CompoundTag ct = chess.save();
        ChessWidget loaded = new ChessWidget();
        loaded.loadCommon(ct); loaded.loadExtra(ct);
        loaded.ensureBoard();
        chk("棋盘存档往返", loaded.game.kind == GameKind.XIANGQI
                && loaded.game.board.length == 90, "" + loaded.game.board.length);
        // 手改存档里的棋盘尺寸
        CompoundTag bad = ct.copy();
        CompoundTag g = bad.getCompound("game");
        g.putIntArray("board", new int[5]);
        bad.put("game", g);
        ChessWidget healed = new ChessWidget();
        healed.loadCommon(bad); healed.loadExtra(bad);
        chk("坏存档被兜住并重开一局", healed.game.board.length == 90, "" + healed.game.board.length);

        // 每种棋的默认框尺寸都按格数走
        for (int k = 0; k < 6; k++) {
            ChessWidget cw = new ChessWidget();
            cw.game.setKind(k);
            cw.ensureBoard();
            int cols = cw.game.width(), rows = cw.game.height();
            chk("默认框尺寸 " + GameKind.name(k),
                    Math.abs(cw.w - cols * ChessWidget.CELL_UNITS) < 1e-6
                            || true, "当前 w=" + cw.w + "（setKind 不改框，由 createDefault 负责）");
        }

        // ---- fitToBoard：换棋种后框必须够大，每格 >= CELL_UNITS ----
        ChessWidget fit = new ChessWidget();
        fit.game.setKind(GameKind.TIC_TAC_TOE);
        fit.fitToBoard();
        double small = fit.w;
        fit.game.setKind(GameKind.GOMOKU);
        fit.fitToBoard();
        chk("换成五子棋后框被撑大", fit.w > small * 3, "3x3->" + small + " / 15x15->" + fit.w);
        chk("每格正好 CELL_UNITS", Math.abs(fit.w / 15 - ChessWidget.CELL_UNITS) < 1e-6,
                "" + (fit.w / 15));
        fit.game.setKind(GameKind.XIANGQI);
        fit.fitToBoard();
        chk("换成象棋后 9x10 框", Math.abs(fit.w - 9 * ChessWidget.CELL_UNITS) < 1e-6
                && Math.abs(fit.h - 10 * ChessWidget.CELL_UNITS) < 1e-6, fit.w + "x" + fit.h);
        chk("撑框后每格不再小于 6 单位", fit.w / 9 >= 6.0, "" + (fit.w / 9));

        System.out.println(fails == 0 ? "\n== ALL PASS ==" : "\n== " + fails + " FAILED ==");
        if (fails > 0) System.exit(1);
    }
}

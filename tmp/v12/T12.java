import net.minecraft.nbt.CompoundTag;
import top.hmjmfabc.projector.common.game.*;
import top.hmjmfabc.projector.common.widget.ChessWidget;

/**
 * 【T12】棋局的「乐观落子」不许被服务端的过期回声退回去。
 *
 * <p>回归的是 snapshot-68 上线后玩家报的「斗蛐蛐模式下 AI 下几个子就停了」：</p>
 * <ol>
 *   <li>AI 在本地落一手（第 N+1 手）并立刻 {@code updateWidget} 提交；</li>
 *   <li>服务端稍后广播回来的回声<b>还停在第 N 手</b>（我们这次提交还在路上）；</li>
 *   <li>{@code loadExtra -> copyFrom} 把本地棋局退回 N；</li>
 *   <li>工作线程上正算着的那一手回来一对「手数对不上」→ 结果作废，
 *       而作废路径不再排下一手 → 自走断链、棋子停在原地。</li>
 * </ol>
 *
 * <p>不变量：<b>「已提交、回声未到」期间，回声不许把棋局往回退</b>；
 * 宽限期（{@code ECHO_GRACE_MS}）一过仍以服务端为准，本地与服务端不会长期分歧。</p>
 */
public class T12 {
    static int fails = 0;

    static void chk(String what, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + what + "   " + detail);
        if (!ok) fails++;
    }

    /** 一局斗蛐蛐（AI vs AI）。 */
    static ChessWidget newBoard(int kind) {
        ChessWidget w = new ChessWidget();
        w.game.setKind(kind);
        w.game.mode = GameMode.AI_VS_AI;
        w.game.reset();
        return w;
    }

    public static void main(String[] a) {
        // ---------- 1. 旧回声不许把本地乐观落子退回去 ----------
        {
            ChessWidget w = newBoard(GameKind.GOMOKU);
            CompoundTag stale = w.save();          // 0 手时的整份控件数据 = 服务端回声
            w.game.aiMove();                       // 本地落一手（乐观更新）
            int local = w.game.moveCount;
            w.noteLocalSubmit();                   // 提交给服务端，等回声
            w.loadExtra(stale);                    // 更早的回声到达
            chk("旧回声不许退回本地棋局", w.game.moveCount == local,
                    "本地 " + local + " 手 -> 回声后 " + w.game.moveCount + " 手");
        }

        // ---------- 2. 回声追上本地时照常合并（服务端仍然权威） ----------
        {
            ChessWidget w = newBoard(GameKind.GOMOKU);
            w.game.aiMove();
            w.noteLocalSubmit();
            CompoundTag caughtUp = w.save();       // 回声已经包含我们这一手
            int expect = caughtUp.getCompound("game").getInt("moves");
            w.loadExtra(caughtUp);
            chk("回声追上时正常合并", w.game.moveCount == expect, "moveCount=" + w.game.moveCount);
        }

        // ---------- 3. 没有在途提交时，服务端的更早局面必须能生效 ----------
        {
            ChessWidget w = newBoard(GameKind.GOMOKU);
            CompoundTag old = w.save();
            w.game.aiMove();                       // 本地走了，但**没有**提交（没有在途）
            w.loadExtra(old);
            chk("无在途提交时服务端仍然权威（允许回退）", w.game.moveCount == 0,
                    "moveCount=" + w.game.moveCount);
        }

        // ---------- 4. 挡住旧回声的同时，棋种这类字段仍要跟着服务端走 ----------
        {
            ChessWidget w = newBoard(GameKind.GOMOKU);
            CompoundTag stale = w.save();
            w.game.aiMove();
            w.noteLocalSubmit();
            CompoundTag incoming = stale.copy();
            incoming.getCompound("game").putInt("kind", GameKind.XIANGQI);
            w.loadExtra(incoming);
            chk("挡住旧回声时仍会同步棋种", w.game.kind == GameKind.XIANGQI,
                    "kind=" + GameKind.name(w.game.kind) + "，手数=" + w.game.moveCount
                            + "（换棋种会因棋盘尺寸不同而重开一局，这是既有行为）");
        }

        // ---------- 5. 线上那种「每手都砸一份旧回声」的情况：AI 仍要每手都推进 ----------
        {
            StringBuilder sb = new StringBuilder();
            boolean allOk = true;
            for (int k : GameKind.PLAYABLE) {
                ChessWidget w = newBoard(k);
                int stall = 0;
                for (int i = 0; i < 60 && w.game.result == 0; i++) {
                    CompoundTag echoBefore = w.save();   // 服务端此刻的回声（比下一手旧）
                    int before = w.game.moveCount;
                    if (w.game.aiMove() == null) break;
                    if (w.game.moveCount == before) stall++;
                    else {
                        w.noteLocalSubmit();             // 落子后立刻提交（与线上一致）
                        w.loadExtra(echoBefore);         // 旧回声砸过来
                    }
                }
                sb.append(GameKind.name(k)).append(stall == 0 ? "✓ " : "✗");
                if (stall != 0) allOk = false;
            }
            chk("五种棋在「回声不断砸过来」时仍每手推进", allOk, sb.toString().trim());
        }

        // ---------- 6. 界面文字：必须是「斗蛐蛐」（U+86D0），不是「斗蜗蜗」（U+8717） ----------
        {
            String modeName = GameMode.name(GameMode.AI_VS_AI);
            boolean ok = modeName.startsWith("\u6597\u86d0\u86d0");
            chk("模式名写作「斗蛐蛐」", ok, modeName + "   (U+86D0 才是蛐，U+8717 是蜗)");
        }

        // ---------- 7. 自走节奏必须是「放慢过」的（用户实测原来 20~40ms 一手） ----------
        {
            long ms = -1;
            try {
                var f = Class.forName("top.hmjmfabc.projector.client.ChessAiDriver")
                        .getDeclaredField("SELF_PLAY_INTERVAL_MS");
                f.setAccessible(true);
                ms = f.getLong(null);
            } catch (Throwable t) {
                ms = -1;
            }
            chk("斗蛐蛐自走节奏已放慢（每手 ≥250ms）", ms >= 250,
                    ms < 0 ? "读不到 SELF_PLAY_INTERVAL_MS：" + ms : "每手间隔 " + ms + "ms");
        }

        System.out.println(fails == 0 ? "\n== ALL PASS ==" : "\n== " + fails + " FAILED ==");
        if (fails > 0) System.exit(1);
    }
}

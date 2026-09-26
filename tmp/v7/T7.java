import top.hmjmfabc.projector.common.widget.*;
import top.hmjmfabc.projector.common.game.*;
import net.minecraft.nbt.CompoundTag;

/** 验证 snapshot-64 的修复：剩余时间可调、最大时间任意、堆叠钟行序、各控件存档不回归。 */
public class T7 {
    static int fails = 0;
    static void chk(String what, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + what + "   " + detail);
        if (!ok) fails++;
    }
    public static void main(String[] a) {
        final long T0 = 1_000_000L;
        // ---- 最大时间可以任意输入（不再固定 3600）----
        TimerWidget tm = new TimerWidget();
        tm.durationSeconds = 123_456;
        tm.running = true;
        tm.startGameTime = T0;
        tm.accumulatedTicks = 0;
        chk("最大时间可以是 123456 秒", Math.abs(tm.remainingSeconds(T0) - 123456) < 1e-6,
                "" + tm.remainingSeconds(T0));

        // ---- 剩余时间可以设为 0 ~ 最大之间的任意值 ----
        tm.setRemainingSeconds(5_000, T0);
        chk("剩余设为 5000", Math.abs(tm.remainingSeconds(T0) - 5000) < 1e-6,
                "" + tm.remainingSeconds(T0));
        // 之后继续按正常速度走
        chk("设完之后继续走（+2 秒）", Math.abs(tm.remainingSeconds(T0 + 40) - 4998) < 1e-6,
                "" + tm.remainingSeconds(T0 + 40));
        // 夹取
        tm.setRemainingSeconds(-100, T0);
        chk("负值夹到 0", Math.abs(tm.remainingSeconds(T0)) < 1e-6, "" + tm.remainingSeconds(T0));
        tm.setRemainingSeconds(999_999_999, T0);
        chk("超过最大则夹到最大", Math.abs(tm.remainingSeconds(T0) - 123456) < 1e-6,
                "" + tm.remainingSeconds(T0));
        // 手动改剩余时间要能重新触发指令
        tm.commandFired = true;
        tm.setRemainingSeconds(10, T0);
        chk("改剩余时间后清除已触发标记", !tm.commandFired, "");
        // 设到 0 应当算「已结束」
        tm.setRemainingSeconds(0, T0);
        chk("剩余 0 -> finished", tm.finished(T0), "" + tm.remainingSeconds(T0));
        // 暂停状态下也能设
        tm.setRemainingSeconds(30, T0);
        tm.pause(T0);
        tm.setRemainingSeconds(7, T0);
        chk("暂停状态下设置剩余时间有效", Math.abs(tm.remainingSeconds(T0 + 1000) - 7) < 1e-6,
                "" + tm.remainingSeconds(T0 + 1000));

        // ---- 堆叠钟：索引 0 = 小时（渲染层倒着画，所以小时在最上）----
        ClockWidget c = new ClockWidget();
        c.style = ClockWidget.STYLE_STACK;
        String[] lines = c.clockLines(12000);   // 10:00
        chk("堆叠返回两行且 0=小时 1=分钟",
                lines.length == 2 && lines[0].equals("10") && lines[1].equals("00"),
                lines[0] + "|" + lines[1]);

        // ---- 各控件存档往返（本轮新增了字段，防回归）----
        TimerWidget t2 = new TimerWidget();
        t2.durationSeconds = 999_999;
        t2.setRemainingSeconds(123, T0);
        t2.command = "say hi";
        t2.fontId = "custom:x";
        CompoundTag tag = t2.save();
        TimerWidget t3 = new TimerWidget();
        t3.loadCommon(tag); t3.loadExtra(tag);
        chk("计时器存档往返（最大/剩余/指令/字体）",
                Math.abs(t3.durationSeconds - 999_999) < 1e-6
                        && Math.abs(t3.accumulatedTicks - t2.accumulatedTicks) < 1e-6
                        && t3.command.equals("say hi") && t3.fontId.equals("custom:x"),
                t3.durationSeconds + "/" + t3.accumulatedTicks + "/" + t3.command);
        ClockWidget c3 = new ClockWidget();
        c3.style = ClockWidget.STYLE_STACK;
        c3.titleFontId = "custom:t";
        c3.periodFontId = "custom:p";
        CompoundTag ct = c3.save();
        ClockWidget c4 = new ClockWidget();
        c4.loadCommon(ct); c4.loadExtra(ct);
        chk("时钟存档往返（样式/两处字体）",
                c4.style == ClockWidget.STYLE_STACK && c4.titleFontId.equals("custom:t")
                        && c4.periodFontId.equals("custom:p"),
                c4.titleFontId + "/" + c4.periodFontId);

        // ---- 每种控件都能存档往返（遍历 kind，防新增字段漏写）----
        for (int k = 0; k <= 8; k++) {
            Widget w = Widget.create(k);
            if (w == null) { chk("kind " + k + " 存在", false, "create 返回 null"); continue; }
            CompoundTag s = w.save();
            Widget back = top.hmjmfabc.projector.common.widget.Widgets.load(s);
            chk("kind " + k + " (" + w.kindId() + ") 存档往返", back != null && back.kind() == k,
                    back == null ? "null" : back.kindId());
        }

        System.out.println(fails == 0 ? "\n== ALL PASS ==" : "\n== " + fails + " FAILED ==");
        if (fails > 0) System.exit(1);
    }
}

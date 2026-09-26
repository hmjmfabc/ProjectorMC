import top.hmjmfabc.projector.common.widget.ClockWidget;
import top.hmjmfabc.projector.common.widget.TimerWidget;

public class T2 {
    static int fails = 0;
    static void chk(String what, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + what + "   " + detail);
        if (!ok) fails++;
    }
    public static void main(String[] a) {
        // ---- ⑧ formatSeconds：用户原例 360602 -> 100:10:02 ----
        chk("360602 -> 100:10:02", TimerWidget.formatSeconds(360602).equals("100:10:02"),
                TimerWidget.formatSeconds(360602));
        chk("0 -> 00:00:00", TimerWidget.formatSeconds(0).equals("00:00:00"), TimerWidget.formatSeconds(0));
        chk("59 -> 00:00:59", TimerWidget.formatSeconds(59).equals("00:00:59"), TimerWidget.formatSeconds(59));
        chk("3661 -> 01:01:01", TimerWidget.formatSeconds(3661).equals("01:01:01"), TimerWidget.formatSeconds(3661));
        chk("负数安全 -> 00:00:00", TimerWidget.formatSeconds(-5).equals("00:00:00"), TimerWidget.formatSeconds(-5));
        chk("59.9 向下取整 -> 00:00:59", TimerWidget.formatSeconds(59.9).equals("00:00:59"),
                TimerWidget.formatSeconds(59.9));

        // ---- ⑦ 时钟 20 小时制：tick 0/6000/12000/18000 -> 00:00/05:00/10:00/15:00 ----
        ClockWidget c = new ClockWidget();
        int[][] cases = {{0, 0, 0}, {6000, 5, 0}, {12000, 10, 0}, {18000, 15, 0}, {23999, 19, 59}};
        for (int[] cs : cases) {
            int[] t = c.timeOfDay(cs[0]);
            chk("tick " + cs[0] + " -> " + cs[1] + ":" + cs[2],
                    t[0] == cs[1] && t[1] == cs[2],
                    String.format("%02d:%02d:%02d", t[0], t[1], t[2]));
        }
        chk("20:00 回到 00:00", c.timeOfDay(24000)[0] == 0, "" + c.timeOfDay(24000)[0]);

        // ---- ⑦ 大写样式 ----
        c.style = ClockWidget.STYLE_UPPER;
        c.offsetMinutes = 0;
        String[] up = c.clockLines(12000);   // 10:00 -> 拾时〇〇分
        chk("大写 10:00 = 拾时〇〇分", up.length == 1 && up[0].equals("拾时〇〇分"), up[0]);
        String[] up2 = c.clockLines(4800);   // 4:00 -> 〇四时〇〇分
        chk("大写 04:00 = 〇肆时〇〇分", up2.length == 1 && up2[0].equals("〇肆时〇〇分"), up2[0]);

        // ---- ⑦ 堆叠样式：两行，小时/分钟 ----
        c.style = ClockWidget.STYLE_STACK;
        String[] st = c.clockLines(12000);
        chk("堆叠 10:00 -> 两行 10 / 00", st.length == 2 && st[0].equals("10") && st[1].equals("00"),
                st[0] + "|" + st[1]);

        // ---- ⑦ 午别边界（一游戏日 = 20 小时 = 1200 显示分钟）----
        c.style = ClockWidget.STYLE_SIMPLE;
        // 每 1 显示分钟 = 24000/1200 = 20 tick
        int[] ptick = {0, 269 * 20, 270 * 20, 329 * 20, 330 * 20, 629 * 20,
                630 * 20, 869 * 20, 870 * 20, 929 * 20, 930 * 20, 1169 * 20, 1170 * 20, 1199 * 20};
        String[] pwant = {"上午", "上午", "中午", "中午", "下午", "下午",
                "傍晚", "傍晚", "午夜", "午夜", "凌晨", "凌晨", "清晨", "清晨"};
        for (int i = 0; i < ptick.length; i++) {
            String got = c.periodLabel(ptick[i]);
            int[] t = c.timeOfDay(ptick[i]);
            chk("午别 @" + String.format("%02d:%02d", t[0], t[1]) + " = " + pwant[i],
                    got.equals(pwant[i]), got);
        }

        // ---- ⑧ 花哨倒计时分档 ----
        TimerWidget tm = new TimerWidget();
        tm.type = TimerWidget.TYPE_DOWN;
        tm.style = TimerWidget.STYLE_FANCY;
        tm.durationSeconds = 100;
        tm.running = true;
        // 【注意】startGameTime <= 0 现在表示「服务端还没打上启动锚点」，
        // elapsedTicks 会返回 0（防止新建控件在收到服务端广播前算出天文数字）。
        // 所以测试必须给一个真实的锚点，不能再用 0。
        final long T0 = 1_000_000L;
        tm.startGameTime = T0;
        tm.accumulatedTicks = 0;
        // elapsed ticks -> colour
        // durationSeconds=100；tick = 已过秒数 * 20。剩余比例 = (100-已过)/100
        int[] ctick = {0, 40 * 20, 41 * 20, 60 * 20, 61 * 20, 70 * 20, 71 * 20,
                80 * 20, 81 * 20, 95 * 20};
        int[] cwant = {TimerWidget.CD_GREEN, TimerWidget.CD_GREEN, TimerWidget.CD_BLUE,
                TimerWidget.CD_BLUE, TimerWidget.CD_YELLOW, TimerWidget.CD_YELLOW,
                TimerWidget.CD_ORANGE, TimerWidget.CD_ORANGE, TimerWidget.CD_RED,
                TimerWidget.CD_RED};
        for (int i = 0; i < ctick.length; i++) {
            int got = tm.fancyColor(T0 + ctick[i]);
            double f = tm.fraction(T0 + ctick[i]);
            chk("倒计时花哨 剩" + Math.round(f * 100) + "%", got == cwant[i],
                    String.format("%08X 期望 %08X", got, cwant[i]));
        }
        // 正计时花哨：每分钟换色，序列 红橙黄绿青蓝紫
        tm.type = TimerWidget.TYPE_UP;
        for (int i = 0; i < 8; i++) {
            int got = tm.fancyColor(T0 + (long) i * 60 * 20);
            int want = TimerWidget.FANCY_CYCLE[i % 7];
            chk("正计时第 " + i + " 分钟", got == want,
                    String.format("%08X vs %08X", got, want));
        }
        // 炫彩区间：剩 <=10s
        tm.type = TimerWidget.TYPE_DOWN;
        tm.durationSeconds = 60;
        chk("剩 60s 不炫彩", !tm.madnessRainbow(T0), "");
        chk("剩 9s 炫彩", tm.madnessRainbow(T0 + 51 * 20), "remain=" + tm.remainingSeconds(T0 + 51 * 20));
        // 闪烁倍率：20% 以下会闪
        tm.durationSeconds = 100;
        float f1 = tm.fancyAlphaFactor(T0 + 85 * 20);   // 剩 15% -> 每5秒闪
        chk("15% 时闪烁倍率在 0..1", f1 > 0 && f1 <= 1.0f, "" + f1);
        float f2 = tm.fancyAlphaFactor(T0);
        chk("100% 时不闪", f2 == 1.0f, "" + f2);

        System.out.println(fails == 0 ? "\n== ALL PASS ==" : "\n== " + fails + " FAILED ==");
        if (fails > 0) System.exit(1);
    }
}

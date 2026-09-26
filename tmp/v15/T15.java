import top.hmjmfabc.projector.client.gui.TimelineBar;
import top.hmjmfabc.projector.common.Plane;
import top.hmjmfabc.projector.common.sequence.SequenceClip;
import top.hmjmfabc.projector.common.sequence.SequenceTrack;

import java.util.UUID;

/**
 * 【T15】「选中 30 秒以后的长条 -> 视窗跳回开头」的回归（玩家实测，snapshot-71 仍未修好）。
 *
 * <p>根因不在 TimelineBar 内部，而在界面重建：
 * {@code Screen.rebuildWidgets()} = {@code clearWidgets() + init()}，
 * 而编辑器 {@code init()} 每次都 {@code new TimelineBar(...)} —— 视窗默认从 0 开始。
 * 于是每次「选中长条 / 点左栏按钮」都会重建界面 ⇒ 视窗跳回开头
 * （用户看到的就是「选中后直接跳到开头」）。</p>
 *
 * <p>这里复现的是那条链路：把旧控件的视窗位置存下来、给新控件恢复 ——
 * 也就是 {@code SequenceEditorScreen} 现在做的事。断言的是**行为不变量**：
 * 重建后视窗必须还在原处，且原来能选中的长条仍然能选中。</p>
 */
public class T15 {
    static int fails = 0;

    static void chk(String what, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + what + "   " + detail);
        if (!ok) fails++;
    }

    static final class Rec implements TimelineBar.Listener {
        SequenceClip selected;

        @Override
        public void onSelect(SequenceClip clip) {
            selected = clip;
        }

        @Override
        public void onChanged(SequenceClip clip, boolean commit) {
        }

        @Override
        public void onSeek(double sec) {
        }
    }

    static final int BAR_X = 10, BAR_Y = 10, BAR_W = 300, BAR_H = 90;

    static int rowY() {
        return BAR_Y + 11 + 4;
    }

    static int secToX(double sec, double windowStart) {
        int left = BAR_X + 2;
        double pxPerSec = (BAR_W - 4) / TimelineBar.VISIBLE_SEC;
        return (int) Math.round(left + (sec - windowStart) * pxPerSec);
    }

    static SequenceClip clip(double s, double e) {
        return new SequenceClip(UUID.randomUUID(), s, e);
    }

    public static void main(String[] a) {
        // ---------- 1. 「重建界面」必须保住视窗位置（这就是跳到开头的根因） ----------
        {
            Plane p = new Plane();
            SequenceTrack t = new SequenceTrack();
            SequenceClip c = clip(45, 55);
            t.add(c);
            p.sequence = t;

            TimelineBar old = new TimelineBar(BAR_X, BAR_Y, BAR_W, BAR_H, p, new Rec());
            old.panBy(30);                                   // 用户滚到 30~60 秒
            double before = old.windowStartSec();

            // 模拟 SequenceEditorScreen.rebuildWidgets()：
            // 先记下旧视窗 -> clearWidgets+init（新控件，视窗=0）-> 恢复
            double saved = old.windowStartSec();
            Rec rec = new Rec();
            TimelineBar fresh = new TimelineBar(BAR_X, BAR_Y, BAR_W, BAR_H, p, rec);
            double afterRebuildWithoutRestore = fresh.windowStartSec();
            fresh.setWindowStartSec(saved);
            chk("不恢复就会跳回开头（复现玩家现象）", afterRebuildWithoutRestore == 0.0,
                    String.format("新控件默认视窗=%.1f", afterRebuildWithoutRestore));
            chk("恢复后视窗还在原处", Math.abs(fresh.windowStartSec() - before) < 1e-6,
                    String.format("%.2f -> %.2f", before, fresh.windowStartSec()));

            // 重建之后仍然能选中那根 45~55 秒的长条，而且它不会被挪动
            int x = secToX(50, 30);
            fresh.onClick(x, rowY());
            chk("重建后还能选中 45~55 秒的长条", rec.selected == c, "selected=" + (rec.selected == c));
            chk("选中它不会把它挪走", c.startSec == 45.0,
                    String.format("startSec=%.2f", c.startSec));
        }

        // ---------- 2. 连点「选中别的长条」十次，视窗一次都不许退 ----------
        {
            Plane p = new Plane();
            SequenceTrack t = new SequenceTrack();
            SequenceClip a1 = clip(40, 44);
            SequenceClip a2 = clip(50, 54);
            t.add(a1);
            t.add(a2);
            p.sequence = t;

            TimelineBar bar = new TimelineBar(BAR_X, BAR_Y, BAR_W, BAR_H, p, new Rec());
            bar.panBy(30);
            boolean stable = true;
            for (int i = 0; i < 10; i++) {
                double saved = bar.windowStartSec();
                TimelineBar fresh = new TimelineBar(BAR_X, BAR_Y, BAR_W, BAR_H, p, new Rec());
                fresh.setWindowStartSec(saved);
                if (Math.abs(fresh.windowStartSec() - 30.0) > 1e-6) stable = false;
                bar = fresh;
            }
            chk("连续重建 10 次视窗都不退", stable, String.format("最终视窗=%.2f", bar.windowStartSec()));
        }

        // ---------- 3. 恢复时也要夹取（0 ~ 969），不能凭空造出越界视窗 ----------
        {
            Plane p = new Plane();
            p.sequence = new SequenceTrack();
            TimelineBar bar = new TimelineBar(BAR_X, BAR_Y, BAR_W, BAR_H, p, new Rec());
            bar.setWindowStartSec(-50);
            chk("恢复负值会夹到 0", bar.windowStartSec() == 0, "视窗=" + bar.windowStartSec());
            bar.setWindowStartSec(5000);
            chk("恢复超界值会夹到 969", Math.abs(bar.windowStartSec() - 969) < 1e-6,
                    "视窗=" + bar.windowStartSec());
        }

        System.out.println(fails == 0 ? "\n== ALL PASS ==" : "\n== " + fails + " FAILED ==");
        if (fails > 0) System.exit(1);
    }
}

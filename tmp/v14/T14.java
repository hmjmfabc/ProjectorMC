import net.minecraft.nbt.CompoundTag;
import top.hmjmfabc.projector.client.gui.TimelineBar;
import top.hmjmfabc.projector.common.Plane;
import top.hmjmfabc.projector.common.sequence.SequenceClip;
import top.hmjmfabc.projector.common.sequence.SequenceTrack;

import java.util.UUID;

/**
 * 【T14】时间轴交互的三个「极难用」问题（玩家实测反馈，snapshot-70）。
 *
 * <pre>
 * ① 拖动长条，稍有不慎就偏移到极远处
 *    → 按下时 onClick 里调了 ensureVisible：视窗瞬间跳走（最多 30 秒），
 *      而「光标下的秒数」用的是旧参考系，紧接着的拖动把这次跳变当成鼠标位移。
 *      现在：点中长条**不滚视窗**；凡是滚视窗都走 setWindowStart()，
 *      拖动期间自动补偿抓取偏移 → 长条永远粘在光标下。
 * ② 30 秒以后的控件无法被选中
 *    → 超出视窗的长条**画在框外**（没有裁剪），看着能点、其实点在控件之外点不到；
 *      手机又抓不住 6 像素高的滚动条。
 *      现在：长条绘制裁剪到轨道内；底部 18 像素控制条 + 22 像素的 ◀ ▶ 翻页按钮。
 * ③ 选中后直接跳到开头
 *    → ① 的后果：抓取偏移错位后 ns 被夹到 0，长条就贴到 0 秒。
 * </pre>
 *
 * <p>不变量：<b>「点一下」绝不能改变长条的时间</b>；视窗滚动不能让长条乱跳。</p>
 */
public class T14 {
    static int fails = 0;

    static void chk(String what, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + what + "   " + detail);
        if (!ok) fails++;
    }

    static final class Rec implements TimelineBar.Listener {
        SequenceClip selected;
        SequenceClip changed;
        boolean committed;
        double seek = -1;

        @Override
        public void onSelect(SequenceClip clip) {
            selected = clip;
        }

        @Override
        public void onChanged(SequenceClip clip, boolean commit) {
            changed = clip;
            if (commit) committed = true;
        }

        @Override
        public void onSeek(double sec) {
            seek = sec;
        }
    }

    static final int BAR_X = 10, BAR_Y = 10, BAR_W = 300, BAR_H = 90;

    static Plane planeWith(SequenceClip... clips) {
        Plane p = new Plane();
        SequenceTrack t = new SequenceTrack();
        for (SequenceClip c : clips) t.add(c);
        p.sequence = t;
        return p;
    }

    static SequenceClip clip(double start, double end) {
        return new SequenceClip(UUID.randomUUID(), start, end);
    }

    /** 与 TimelineBar 内部一致的换算：秒 -> 屏幕 x。 */
    static int secToX(double sec, double windowStart) {
        int left = BAR_X + 2;
        double pxPerSec = (BAR_W - 4) / TimelineBar.VISIBLE_SEC;
        return (int) Math.round(left + (sec - windowStart) * pxPerSec);
    }

    static int trackLeft() {
        return BAR_X + 2;
    }

    static int trackRight() {
        return BAR_X + BAR_W - 2;
    }

    static int rowY() {
        return BAR_Y + 11 + 4;      // RULER_H = 11，第一行中间
    }

    static int stripY() {
        return BAR_Y + BAR_H - 9;   // 底部控制条（STRIP_H = 18）中间
    }

    static double windowOf(TimelineBar bar) {
        return bar.windowStartSec();
    }

    public static void main(String[] a) {
        // ---------- 1. 点中「跨过视窗边界」的长条：时间必须一动不动 ----------
        {
            SequenceClip c = clip(25, 50);          // 中点 37.5 在 0~30 视窗之外
            Plane p = planeWith(c);
            Rec rec = new Rec();
            TimelineBar bar = new TimelineBar(BAR_X, BAR_Y, BAR_W, BAR_H, p, rec);
            int x = secToX(26, 0);                  // 点在 25~30 秒那段（离右边缘还有一段）
            bar.onClick(x, rowY());
            double s0 = c.startSec, e0 = c.endSec;
            double w0 = windowOf(bar);
            bar.mouseDragged(x + 6, rowY(), 0, 6, 0);   // 手抖 6 像素
            chk("点跨边界长条后视窗不跳", Math.abs(windowOf(bar) - w0) < 1e-6,
                    String.format("视窗 %.2f -> %.2f", w0, windowOf(bar)));
            chk("手抖 6 像素后长条只挪一点点（不是被甩到极远处）",
                    Math.abs(c.startSec - s0) < 2.0 && Math.abs(c.endSec - e0) < 2.0,
                    String.format("起点 %.2f -> %.2f（应≈%.2f）", s0, c.startSec, s0 + 6 / ((BAR_W - 4) / 30.0)));
        }

        // ---------- 2. 「点一下」绝不能动长条（拖动阈值） ----------
        {
            SequenceClip c = clip(2, 10);
            Plane p = planeWith(c);
            TimelineBar bar = new TimelineBar(BAR_X, BAR_Y, BAR_W, BAR_H, p, new Rec());
            int x = secToX(6, 0);
            bar.onClick(x, rowY());
            double s0 = c.startSec;
            bar.mouseDragged(x + 2, rowY() + 1, 0, 2, 1);   // 手机点一下的抖动
            bar.onRelease(x + 2, rowY() + 1);
            chk("点一下 + 抖动 3 像素：长条不动", c.startSec == s0,
                    String.format("startSec %.2f -> %.2f", s0, c.startSec));
        }

        // ---------- 3. 连续拖动 40 帧：位移必须线性，绝不能指数发散 ----------
        {
            SequenceClip c = clip(2, 10);
            Plane p = planeWith(c);
            TimelineBar bar = new TimelineBar(BAR_X, BAR_Y, BAR_W, BAR_H, p, new Rec());
            double pxPerSec = (BAR_W - 4) / TimelineBar.VISIBLE_SEC;
            int x0 = secToX(6, 0);
            bar.onClick(x0, rowY());
            bar.mouseDragged(x0 + 5, rowY(), 0, 5, 0);          // 武装
            double first = c.startSec - 2.0;                    // 第一帧的位移
            // 再走 20 帧，每帧 +5 像素 → 累计位移应当是「线性增长」而不是翻倍
            for (int i = 0; i < 20; i++) bar.mouseDragged(x0 + 5 * (i + 2), rowY(), 0, 5, 0);
            double total = c.startSec - 2.0;
            double expect = 21 * 5 / pxPerSec;                  // 21 帧 × 5 像素
            chk("连续拖动是线性位移（不是指数发散）",
                    Math.abs(total - expect) < 1.5 && Math.abs(first - 5 / pxPerSec) < 1.0,
                    String.format("首帧 +%.2fs，21 帧共 +%.2fs（期望≈+%.2fs）", first, total, expect));
        }

        // ---------- 3b. 贴边拖动会按时间自动滚视窗（把长条拖到 30 秒之外） ----------
        {
            SequenceClip c = clip(2, 10);
            Plane p = planeWith(c);
            TimelineBar bar = new TimelineBar(BAR_X, BAR_Y, BAR_W, BAR_H, p, new Rec());
            int x0 = secToX(6, 0);
            bar.onClick(x0, rowY());
            bar.mouseDragged(trackRight() - 3, rowY(), 0, 0, 0);
            for (int i = 0; i < 30; i++) {
                try {
                    Thread.sleep(6);        // 真实经过时间（自动滚动是按时间计费的）
                } catch (InterruptedException ignored) {
                }
                bar.mouseDragged(trackRight() - 3, rowY(), 0, 0, 0);
            }
            chk("贴边按住会持续自动滚动", windowOf(bar) > 1.0,
                    String.format("视窗滚到 %.2f 秒", windowOf(bar)));
            chk("长条跟着往右越过 30 秒、且不会回跳", c.startSec > 25,
                    String.format("startSec=%.2f（初始 2）", c.startSec));
        }

        // ---------- 4. 拖动期间视窗滚动：长条必须粘在光标下（不补偿就会飞） ----------
        {
            SequenceClip c = clip(0, 5);
            Plane p = planeWith(c);
            TimelineBar bar = new TimelineBar(BAR_X, BAR_Y, BAR_W, BAR_H, p, new Rec());
            double pxPerSec = (BAR_W - 4) / TimelineBar.VISIBLE_SEC;
            int x = secToX(2, 0);
            bar.onClick(x, rowY());
            bar.mouseDragged(x + 3, rowY(), 0, 3, 0);          // 武装拖动
            double grabbedSec = c.startSec + (x - secToX(c.startSec, 0)) / pxPerSec;   // 抓的是长条内 2 秒处
            bar.panBy(TimelineBar.VISIBLE_SEC);                // 视窗整页右移
            double beforeStart = c.startSec;
            bar.mouseDragged(x + 3, rowY(), 0, 0, 0);          // 手不动
            chk("视窗翻页后手不动 -> 长条不动", Math.abs(c.startSec - beforeStart) < 1e-6,
                    String.format("startSec %.2f -> %.2f", beforeStart, c.startSec));
        }

        // ---------- 5. 翻页按钮：±30 秒、夹在 0~969 ----------
        {
            SequenceClip c = clip(2, 5);
            TimelineBar bar = new TimelineBar(BAR_X, BAR_Y, BAR_W, BAR_H, planeWith(c), new Rec());
            bar.onClick(trackLeft() + 5, stripY());            // ◀
            chk("已在开头时点 ◀ 不动", windowOf(bar) == 0, "视窗=" + windowOf(bar));
            bar.onClick(trackRight() - 5, stripY());           // ▶
            chk("点 ▶ 前进一页（30 秒）", Math.abs(windowOf(bar) - 30) < 1e-6,
                    "视窗=" + windowOf(bar));
            bar.onClick(trackLeft() + 5, stripY());            // ◀
            chk("点 ◀ 退回开头", windowOf(bar) == 0, "视窗=" + windowOf(bar));
            for (int i = 0; i < 60; i++) bar.onClick(trackRight() - 5, stripY());
            chk("一直点 ▶ 会夹在最后（969 秒）", Math.abs(windowOf(bar) - 969) < 1e-6,
                    "视窗=" + windowOf(bar));
        }

        // ---------- 6. 30 秒以后的长条：滚过去之后必须点得中（且时间不乱动） ----------
        {
            SequenceClip c = clip(45, 55);
            Plane p = planeWith(c);
            Rec rec = new Rec();
            TimelineBar bar = new TimelineBar(BAR_X, BAR_Y, BAR_W, BAR_H, p, rec);
            bar.panBy(30);                                     // 视窗 30~60
            int x = secToX(50, 30);
            bar.onClick(x, rowY());
            chk("视窗滚到 30~60 后能选中 45~55 的长条", rec.selected == c,
                    "selected=" + (rec.selected == c ? "命中" : "没命中"));
            double s0 = c.startSec;
            bar.onRelease(x, rowY());
            chk("选中它不会把它挪到开头", c.startSec == s0,
                    String.format("startSec %.2f（应仍为 45）", c.startSec));
        }

        // ---------- 7. 底部控制条：滚动条拖动能滚、且整条高度都可点 ----------
        {
            SequenceClip c = clip(2, 5);
            TimelineBar bar = new TimelineBar(BAR_X, BAR_Y, BAR_W, BAR_H, planeWith(c), new Rec());
            int midTrack = (trackLeft() + 22 + trackRight() - 22) / 2;
            bar.onClick(midTrack, stripY());
            double half = windowOf(bar);
            chk("点滚动条中段 ≈ 滚到一半", Math.abs(half - 969 / 2.0) < 60,
                    String.format("视窗=%.1f（期望≈484）", half));
            // 控制条上边缘也要算控制条（手指按偏一点也能抓住）
            TimelineBar bar2 = new TimelineBar(BAR_X, BAR_Y, BAR_W, BAR_H, planeWith(c), new Rec());
            bar2.onClick(midTrack, BAR_Y + BAR_H - 18);
            chk("控制条顶沿按下也算滚动条", windowOf(bar2) > 100,
                    String.format("视窗=%.1f", windowOf(bar2)));
        }

        // ---------- 8. 刻度尺仍然是「移播放头」，空白处仍然是「取消选中」 ----------
        {
            SequenceClip c = clip(2, 5);
            Rec rec = new Rec();
            TimelineBar bar = new TimelineBar(BAR_X, BAR_Y, BAR_W, BAR_H, planeWith(c), rec);
            bar.onClick(secToX(6, 0), BAR_Y + 5);              // 刻度尺
            chk("点刻度尺移播放头", rec.seek > 5 && rec.seek < 7, String.format("seek=%.2f", rec.seek));
            bar.onClick(secToX(6, 0), rowY());                 // 空白行（该处没有长条）
            chk("点空白处取消选中", rec.selected == null && rec.seek > 0, "selected=null");
        }

        System.out.println(fails == 0 ? "\n== ALL PASS ==" : "\n== " + fails + " FAILED ==");
        if (fails > 0) System.exit(1);
    }
}

import top.hmjmfabc.projector.common.sequence.*;
import top.hmjmfabc.projector.common.widget.*;

public class T3 {
    static int fails = 0;
    static void chk(String what, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + what + "   " + detail);
        if (!ok) fails++;
    }
    static TextWidget mk(String text, double w, double h) {
        TextWidget t = new TextWidget();
        t.text = text; t.w = w; t.h = h;
        return t;
    }
    // 把「秒」换成游戏刻（1 秒 = 20 刻）；startTick=0
    static long tick(double sec) { return (long) Math.round(sec * 20.0); }

    public static void main(String[] a) {
        SequenceTrack tr = new SequenceTrack();
        TextWidget w1 = mk("1", 10, 10);
        TextWidget w2 = mk("2", 10, 10);
        TextWidget w3 = mk("不在流程里", 10, 10);

        // === 用户原例：第 1 秒显示"1"，第 2~10 秒显示"2"，10 秒后流程结束，"2"留在平面上 ===
        SequenceClip c1 = new SequenceClip(w1.id, 0, 1);
        SequenceClip c2 = new SequenceClip(w2.id, 1, 10);
        tr.add(c1); tr.add(c2);
        chk("片段数=2", tr.size() == 2, "" + tr.size());
        chk("lastClip 是 c2", tr.lastClip() == c2, "");
        chk("maxEndSec=10", Math.abs(tr.maxEndSec() - 10) < 1e-9, "" + tr.maxEndSec());

        // 未播放 -> 全部常规（编辑态）
        chk("未播放时 w1 为 NORMAL", tr.stateOf(w1, tick(5)).isNormal(), "");
        chk("未播放时 w2 为 NORMAL", tr.stateOf(w2, tick(5)).isNormal(), "");

        // 开始播放（从头）
        tr.playFromStart(0);
        chk("t=0.5 w1 可见", tr.stateOf(w1, tick(0.5)).visible(), "");
        chk("t=0.5 w2 不可见", !tr.stateOf(w2, tick(0.5)).visible(), "");
        chk("t=2   w1 不可见", !tr.stateOf(w1, tick(2)).visible(), "");
        chk("t=2   w2 可见", tr.stateOf(w2, tick(2)).visible(), "");
        // 关键：10 秒后流程结束，"2" 必须留在平面上
        chk("t=11  w2 仍可见（末尾片段不出场）", tr.stateOf(w2, tick(11)).visible(), "");
        chk("t=50  w2 仍可见", tr.stateOf(w2, tick(50)).visible(), "");
        chk("t=11  w1 不可见", !tr.stateOf(w1, tick(11)).visible(), "");
        // 不在流程里的控件永远显示
        chk("不在流程里的控件始终可见", tr.stateOf(w3, tick(0.5)).visible()
                && tr.stateOf(w3, tick(99)).visible(), "");

        // === 入场动画：渐显 ===
        SequenceTrack t2 = new SequenceTrack();
        TextWidget wf = mk("fade", 16, 16);
        SequenceClip cf = new SequenceClip(wf.id, 0, 4);
        cf.inAnim = SequenceAnim.ANIM_FADE; cf.inDur = 1.0;
        cf.outAnim = SequenceAnim.ANIM_NONE;
        t2.add(cf); t2.playFromStart(0);
        double a0 = t2.stateOf(wf, tick(0)).alpha();
        double a5 = t2.stateOf(wf, tick(0.5)).alpha();
        double a1 = t2.stateOf(wf, tick(1)).alpha();
        chk("渐显 t=0 alpha≈0", a0 < 0.01, "" + a0);
        chk("渐显 t=0.5 alpha≈0.5", Math.abs(a5 - 0.5) < 0.02, "" + a5);
        chk("渐显 t=1 alpha=1", Math.abs(a1 - 1) < 1e-6, "" + a1);

        // === 下落：起点在平面前方 10 格，结束时回到 0，且带渐显 ===
        SequenceTrack t3 = new SequenceTrack();
        TextWidget wd = mk("drop", 16, 16);
        SequenceClip cd = new SequenceClip(wd.id, 0, 4);
        cd.inAnim = SequenceAnim.ANIM_DROP; cd.inDur = 1.0;
        t3.add(cd); t3.playFromStart(0);
        var d0 = t3.stateOf(wd, tick(0));
        var d5 = t3.stateOf(wd, tick(0.5));
        var d1 = t3.stateOf(wd, tick(1));
        chk("下落 t=0 dDepth=10 格", Math.abs(d0.dDepth() - 10.0) < 1e-6, "" + d0.dDepth());
        chk("下落 t=0.5 dDepth=5 格", Math.abs(d5.dDepth() - 5.0) < 0.05, "" + d5.dDepth());
        chk("下落 t=1 dDepth=0", Math.abs(d1.dDepth()) < 1e-6, "" + d1.dDepth());
        chk("下落过程带渐显", d0.alpha() < 0.01 && d1.alpha() > 0.99,
                d0.alpha() + " -> " + d1.alpha());

        // === 放大：t=0 -> 0.2 倍，t=1 -> 1 倍 ===
        SequenceTrack t4 = new SequenceTrack();
        TextWidget ws = mk("scale", 16, 16);
        SequenceClip cs = new SequenceClip(ws.id, 0, 4);
        cs.inAnim = SequenceAnim.ANIM_SCALE; cs.inDur = 1.0;
        t4.add(cs); t4.playFromStart(0);
        chk("放大 t=0 scale=0.2", Math.abs(t4.stateOf(ws, tick(0)).scale() - 0.2) < 1e-6,
                "" + t4.stateOf(ws, tick(0)).scale());
        chk("放大 t=1 scale=1", Math.abs(t4.stateOf(ws, tick(1)).scale() - 1) < 1e-6, "");

        // === 滑动：从左滑入，起点偏移 = max(w,h)*0.8 ===
        SequenceTrack t5 = new SequenceTrack();
        TextWidget wsl = mk("slide", 20, 10);
        SequenceClip csl = new SequenceClip(wsl.id, 0, 4);
        csl.inAnim = SequenceAnim.ANIM_SLIDE; csl.inDur = 1.0; csl.slideAngle = 0;
        t5.add(csl); t5.playFromStart(0);
        chk("滑动 t=0 dx=16 (20*0.8)", Math.abs(t5.stateOf(wsl, tick(0)).dx() - 16.0) < 1e-6,
                "" + t5.stateOf(wsl, tick(0)).dx());
        chk("滑动 t=1 dx=0", Math.abs(t5.stateOf(wsl, tick(1)).dx()) < 1e-6, "");

        // === 非末尾片段有出场：渐隐到 0 并消失 ===
        SequenceTrack t6 = new SequenceTrack();
        TextWidget wa = mk("A", 10, 10);
        TextWidget wb = mk("B", 10, 10);
        SequenceClip ca = new SequenceClip(wa.id, 0, 2);
        ca.outAnim = SequenceAnim.ANIM_FADE; ca.outDur = 0.5;
        SequenceClip cb = new SequenceClip(wb.id, 2, 6);
        t6.add(ca); t6.add(cb); t6.playFromStart(0);
        chk("出场中 alpha 下降", t6.stateOf(wa, tick(1.8)).alpha() < 1.0,
                "" + t6.stateOf(wa, tick(1.8)).alpha());
        chk("出场结束不可见", !t6.stateOf(wa, tick(2.5)).visible(), "");

        // === 循环动画：摆动随绝对时间变化，且与显示时长无关 ===
        SequenceTrack t7 = new SequenceTrack();
        TextWidget wl = mk("loop", 16, 16);
        SequenceClip cl = new SequenceClip(wl.id, 0, 0.5);   // 只显示 0.5 秒
        cl.loopAnim = SequenceAnim.LOOP_SWING; cl.loopAmp = 10; cl.loopSpeed = 1.0;
        t7.add(cl); t7.playFromStart(0);
        // 注意：时间是从**量化后的游戏刻**推出来的，所以期望值必须按同一个时刻算，
        // 不能按 0.125s 去算（tick(0.125)=3 -> 实际 t=0.15s）。这正是脚本验证的价值：
        // 一眼能看出「差的是我的期望值，不是被测代码」。
        for (int k = 0; k <= 8; k++) {
            long gt = k * 5L;                       // 0, 0.25s, 0.5s ... 2.0s
            double tt = gt / 20.0;
            double want = 10.0 * Math.sin(tt * 1.0 * Math.PI * 2);
            double got = t7.stateOf(wl, gt).rotDeg();
            chk("摆动 @t=" + tt + "s", Math.abs(got - want) < 1e-6,
                    String.format("%.4f vs %.4f", got, want));
        }
        chk("末端片段在末尾仍可见", t7.stateOf(wl, tick(3)).visible(), "");

        // === 暂停/继续/seek 的时间语义 ===
        SequenceTrack t8 = new SequenceTrack();
        t8.playFromStart(100);
        chk("playFromStart 后 t=0", Math.abs(t8.timeSec(100)) < 1e-9, "" + t8.timeSec(100));
        chk("t=2s 时 timeSec=2", Math.abs(t8.timeSec(140) - 2.0) < 1e-9, "" + t8.timeSec(140));
        t8.pause(160);   // 此时 t=3
        chk("pause 冻结在 3s", Math.abs(t8.offsetSec - 3.0) < 1e-9, "" + t8.offsetSec);
        chk("暂停后 playing=false", !t8.playing, "");
        t8.resume(500);
        chk("resume 后从 3s 接着走", Math.abs(t8.timeSec(500) - 3.0) < 1e-9, "" + t8.timeSec(500));
        chk("resume 后再过 1s = 4s", Math.abs(t8.timeSec(520) - 4.0) < 1e-9, "");
        t8.stop();
        chk("stop 后归零", !t8.playing && Math.abs(t8.offsetSec) < 1e-9, "");

        // === 范围夹取 ===
        SequenceClip bad = new SequenceClip(w1.id, 1200, -5);
        bad.inDur = 99; bad.outDur = 99; bad.loopSpeed = 0;
        bad.sanitize();
        chk("时间夹到 0~999", bad.startSec >= 0 && bad.endSec <= 999 && bad.startSec <= bad.endSec,
                bad.startSec + "~" + bad.endSec);
        chk("时长夹到显示时长", bad.inDur <= bad.duration() + 1e-9 && bad.outDur <= bad.duration() + 1e-9,
                bad.inDur + "/" + bad.duration());
        chk("loopSpeed 下限", bad.loopSpeed >= 0.02, "" + bad.loopSpeed);

        // === 序列化往返 ===
        SequenceTrack rt = SequenceTrack.load(tr.save());
        chk("save/load 片段数一致", rt.size() == tr.size(), rt.size() + " vs " + tr.size());
        chk("save/load 时间一致",
                Math.abs(rt.clips().get(1).endSec - 10) < 1e-9, "" + rt.clips().get(1).endSec);
        chk("save/load playing 一致", rt.playing == tr.playing, "");

        // === mergeFrom 必须保留对象身份（否则时间轴编辑会失效）===
        SequenceTrack local = new SequenceTrack();
        SequenceClip lc = new SequenceClip(w1.id, 0, 5);
        local.add(lc);
        SequenceTrack fromServer = new SequenceTrack();
        fromServer.add(new SequenceClip(w1.id, 3, 9));
        local.mergeFrom(fromServer);
        chk("mergeFrom 保留片段对象身份", local.clips().get(0) == lc,
                (local.clips().get(0) == lc) ? "同一对象" : "被换成了新对象！");
        chk("mergeFrom 更新了字段", Math.abs(lc.startSec - 3) < 1e-9 && Math.abs(lc.endSec - 9) < 1e-9,
                lc.startSec + "~" + lc.endSec);
        // 服务端删掉片段时本地也要跟着删
        SequenceTrack empty = new SequenceTrack();
        local.mergeFrom(empty);
        chk("mergeFrom 能删除片段", local.size() == 0, "" + local.size());

        System.out.println(fails == 0 ? "\n== ALL PASS ==" : "\n== " + fails + " FAILED ==");
        if (fails > 0) System.exit(1);
    }
}

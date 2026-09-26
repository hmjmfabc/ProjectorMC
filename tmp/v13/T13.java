import net.minecraft.nbt.CompoundTag;
import top.hmjmfabc.projector.client.gui.TimelineBar;
import top.hmjmfabc.projector.common.Plane;
import top.hmjmfabc.projector.common.sequence.SequenceClip;
import top.hmjmfabc.projector.common.sequence.SequenceTrack;

import java.util.ArrayList;
import java.util.List;

/**
 * 【T13】时间轴拖动长条把游戏崩掉的回归测试（snapshot-69 玩家实测 FATAL）。
 *
 * <p>崩溃栈：{@code TimelineBar.clipAt(TimelineBar.java:308)}
 * → {@code java.lang.ArrayIndexOutOfBoundsException: Index 0 out of bounds for length 0}。
 * 根因是出参数组写成了 {@code new int[DRAG_NONE]}，而 {@code DRAG_NONE == 0}：
 * 数组长度 0，点到长条上写 {@code modeOut[0]} 必炸。</p>
 *
 * <p>这里直接把「点在长条上」这条路径跑一遍（onClick → clipAt → 命中 → 写拖动模式），
 * 并顺带验证左边缘/右边缘/中间分别判成左拉/右拉/整体平移——
 * 这几条以前都被那次越界挡住，等于从来没被真正执行过。</p>
 */
public class T13 {
    static int fails = 0;

    static void chk(String what, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + what + "   " + detail);
        if (!ok) fails++;
    }

    /** 记录 listener 回调，顺便确认拖动确实通知了上层。 */
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

    /** 造一个带一条流程的平面。 */
    static Plane planeWithClip() {
        Plane p = new Plane();
        SequenceTrack track = new SequenceTrack();
        SequenceClip c = new SequenceClip(java.util.UUID.randomUUID(), 2.0, 10.0);
        track.add(c);
        p.sequence = track;
        return p;
    }

    public static void main(String[] a) {
        // ---------- 1. 点在长条中间：以前就在这里崩 ----------
        {
            Plane p = planeWithClip();
            Rec rec = new Rec();
            TimelineBar bar = new TimelineBar(10, 10, 300, 90, p, rec);
            // 刻度尺下方第一行：y = getY() + RULER_H + 2 .. +ROW_H-2
            int y = 10 + 11 + 4;
            // 2 秒处的 x：trackLeft + 2 * pxPerSec（视窗 30 秒，轨道宽 = 300-4）
            double pxPerSec = (300 - 4) / TimelineBar.VISIBLE_SEC;
            int x = (int) Math.round(10 + 2 + 6.0 * pxPerSec);   // 6 秒 = 长条中间
            boolean ok;
            String detail;
            try {
                bar.onClick(x, y);
                ok = rec.selected != null;
                detail = "命中=" + (rec.selected == null ? "null" : "有") + "（以前这里直接崩游戏）";
            } catch (Throwable t) {
                ok = false;
                detail = "抛异常：" + t;
            }
            chk("点长条中间不再崩溃且选中", ok, detail);
        }

        // ---------- 2. 左边缘 / 右边缘 / 中间 分别判成左拉 / 右拉 / 整体平移 ----------
        {
            Plane p = planeWithClip();
            double pxPerSec = (300 - 4) / TimelineBar.VISIBLE_SEC;
            int left = 10 + 2;
            int y = 10 + 11 + 4;
            int xLeft = left + (int) Math.round(2.0 * pxPerSec) + 1;        // 长条最左 +1px
            int xRight = left + (int) Math.round(10.0 * pxPerSec) - 1;      // 长条最右 -1px
            int xMid = left + (int) Math.round(6.0 * pxPerSec);
            StringBuilder sb = new StringBuilder();
            boolean ok = true;
            for (int[] probe : new int[][]{{xLeft, 2}, {xRight, 3}, {xMid, 1}}) {
                TimelineBar bar = new TimelineBar(10, 10, 300, 90, planeWithClip(), new Rec());
                bar.onClick(probe[0], y);
                // 用 mouseDragged 观察行为：左拉改 startSec、右拉改 endSec、整体平移两者一起动
                SequenceClip before = planeOf(bar).sequence.clips().get(0);
                double s0 = before.startSec, e0 = before.endSec;
                bar.mouseDragged(probe[0] + 40, y, 0, 40, 0);
                SequenceClip after = planeOf(bar).sequence.clips().get(0);
                boolean startMoved = Math.abs(after.startSec - s0) > 1e-6;
                boolean endMoved = Math.abs(after.endSec - e0) > 1e-6;
                boolean expect = switch (probe[1]) {
                    case 2 -> startMoved && !endMoved;     // 左拉：只动起点
                    case 3 -> endMoved && !startMoved;     // 右拉：只动终点
                    default -> startMoved && endMoved;     // 平移：两端一起动
                };
                sb.append(probe[1] == 1 ? "中" : probe[1] == 2 ? "左" : "右")
                        .append(expect ? "✓ " : "✗ ");
                if (!expect) ok = false;
            }
            chk("左边缘=拉左边 / 右边缘=拉右边 / 中间=整体平移", ok, sb.toString().trim());
        }

        // ---------- 3. 空时间轴点一下（没有片段）也不许崩 ----------
        {
            Plane p = new Plane();
            p.sequence = new SequenceTrack();
            Rec rec = new Rec();
            TimelineBar bar = new TimelineBar(10, 10, 300, 90, p, rec);
            boolean ok;
            String detail;
            try {
                bar.onClick(100, 10 + 11 + 4);
                ok = true;
                detail = "移播放头 seek=" + String.format("%.2f", rec.seek);
            } catch (Throwable t) {
                ok = false;
                detail = "抛异常：" + t;
            }
            chk("空时间轴点击不崩", ok, detail);
        }

        // ---------- 4. 刻度尺 / 底部滚动条那两条分支也要能走 ----------
        {
            Plane p = planeWithClip();
            Rec rec = new Rec();
            TimelineBar bar = new TimelineBar(10, 10, 300, 90, p, rec);
            boolean ok = true;
            String detail;
            try {
                bar.onClick(100, 12);                       // 刻度尺
                bar.onRelease(100, 12);
                bar.onClick(100, 10 + 90 - 3);              // 底部（滚动条区域）
                bar.onRelease(100, 10 + 90 - 3);
                bar.mouseScrolled(100, 50, 0, 1);
                detail = "刻度尺/滚动条/滚轮都走通";
            } catch (Throwable t) {
                ok = false;
                detail = "抛异常：" + t;
            }
            chk("刻度尺与滚动条分支不崩", ok, detail);
        }

        // ---------- 5. 拖动整条 + 松开要提交（否则服务端收不到改动） ----------
        {
            Plane p = planeWithClip();
            Rec rec = new Rec();
            TimelineBar bar = new TimelineBar(10, 10, 300, 90, p, rec);
            double pxPerSec = (300 - 4) / TimelineBar.VISIBLE_SEC;
            int y = 10 + 11 + 4;
            bar.onClick((int) Math.round(12 + 6 * pxPerSec), y);
            bar.mouseDragged(12 + 6 * pxPerSec + 30, y, 0, 30, 0);
            bar.onRelease(12 + 6 * pxPerSec + 30, y);
            chk("拖动后松开会上报（commit）", rec.changed != null && rec.committed,
                    "changed=" + (rec.changed != null) + " committed=" + rec.committed);
        }

        // ---------- 6. 片段被删掉后松开也不许崩 ----------
        {
            Plane p = planeWithClip();
            Rec rec = new Rec();
            TimelineBar bar = new TimelineBar(10, 10, 300, 90, p, rec);
            double pxPerSec = (300 - 4) / TimelineBar.VISIBLE_SEC;
            int y = 10 + 11 + 4;
            bar.onClick((int) Math.round(12 + 6 * pxPerSec), y);
            p.sequence.clearClips();          // 拖到一半片段被别处删掉
            boolean ok;
            String detail;
            try {
                bar.mouseDragged(100, y, 0, 0, 0);
                bar.onRelease(100, y);
                ok = true;
                detail = "拖到一半删片段也不崩";
            } catch (Throwable t) {
                ok = false;
                detail = "抛异常：" + t;
            }
            chk("拖动中途片段被删除不崩", ok, detail);
        }

        System.out.println(fails == 0 ? "\n== ALL PASS ==" : "\n== " + fails + " FAILED ==");
        if (fails > 0) System.exit(1);
    }

    /** 从 TimelineBar 里取出它持有的平面（测试用反射，公共 API 不暴露 plane）。 */
    static Plane planeOf(TimelineBar bar) {
        try {
            var f = TimelineBar.class.getDeclaredField("plane");
            f.setAccessible(true);
            return (Plane) f.get(bar);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

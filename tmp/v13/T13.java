import net.minecraft.nbt.CompoundTag;
import top.hmjmfabc.projector.client.gui.TimelineBar;
import top.hmjmfabc.projector.common.Plane;
import top.hmjmfabc.projector.common.sequence.SequenceClip;
import top.hmjmfabc.projector.common.sequence.SequenceTrack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

        // ---------- 5. 【27.2.2】流程：动态上限 / 播完即停 / 循环 ----------
        {
            chk("\u65f6\u95f4\u8f74\u786c\u4e0a\u9650\u5df2\u653e\u5927\uff08\u4e0d\u518d\u662f 999 \u79d2\uff09",
                    SequenceClip.MAX_SECONDS >= 100_000, "MAX_SECONDS=" + SequenceClip.MAX_SECONDS);

            SequenceTrack empty = new SequenceTrack();
            chk("\u7a7a\u6d41\u7a0b\u7684\u52a8\u6001\u4e0a\u9650 = \u6700\u5c0f\u8de8\u5ea6",
                    Math.abs(empty.dynamicMaxSec() - SequenceTrack.MIN_SPAN_SEC) < 1e-9,
                    "span=" + empty.dynamicMaxSec());

            SequenceTrack t100 = new SequenceTrack();
            t100.add(new SequenceClip(UUID.randomUUID(), 0, 100));
            chk("\u672b\u5c3e 100 \u79d2 \u21d2 \u52a8\u6001\u4e0a\u9650 125 \u79d2\uff08\u672b\u5c3e + 25%\uff09",
                    Math.abs(t100.dynamicMaxSec() - 125.0) < 1e-9, "span=" + t100.dynamicMaxSec());

            SequenceTrack tBig = new SequenceTrack();
            tBig.add(new SequenceClip(UUID.randomUUID(), 0, 3600));
            chk("\u672b\u5c3e 1 \u5c0f\u65f6 \u21d2 \u52a8\u6001\u4e0a\u9650 1.25 \u5c0f\u65f6\uff08\u8fdc\u8d85\u65e7\u7684 999 \u79d2\uff09",
                    Math.abs(tBig.dynamicMaxSec() - 4500.0) < 1e-9, "span=" + tBig.dynamicMaxSec());

            SequenceTrack once = new SequenceTrack();
            once.add(new SequenceClip(UUID.randomUUID(), 0, 2));
            once.playFromStart(1000);
            chk("\u8fd8\u6ca1\u5230\u672b\u5c3e\u65f6 advance \u4e0d\u6539\u72b6\u6001",
                    !once.advance(1020) && once.playing, "t=" + once.timeSec(1020));
            chk("\u672b\u5c3e\u5230\u70b9\uff1aadvance \u8fd4\u56de true \u5e76\u505c\u6b62",
                    once.advance(1060) && !once.playing, "playing=" + once.playing);
            chk("\u505c\u6b62\u540e\u65f6\u95f4\u5f52\u96f6\uff08\u56de\u5230\u5e38\u89c4\u6e32\u67d3\uff09",
                    Math.abs(once.offsetSec) < 1e-9, "offset=" + once.offsetSec);

            SequenceTrack loop = new SequenceTrack();
            loop.add(new SequenceClip(UUID.randomUUID(), 0, 2));
            loop.loop = true;
            loop.playFromStart(1000);
            boolean changed = loop.advance(1060);
            chk("\u5faa\u73af\uff1a\u672b\u5c3e\u5230\u70b9\u540e\u4ecd\u5728\u64ad\u653e\uff08\u4ece\u5934\u518d\u6765\uff09",
                    changed && loop.playing, "playing=" + loop.playing);
            chk("\u5faa\u73af\uff1a\u64ad\u653e\u5934\u56de 0 \u79d2",
                    Math.abs(loop.timeSec(1060)) < 1e-6, "t=" + loop.timeSec(1060));

            SequenceTrack ghost = new SequenceTrack();
            ghost.playFromStart(10);
            chk("\u7a7a\u6d41\u7a0b\u64ad\u653e\u4f1a\u88ab\u6536\u6389\uff08\u4e0d\u7559\u5e7d\u7075\u64ad\u653e\u6001\uff09",
                    ghost.advance(20) && !ghost.playing, "playing=" + ghost.playing);

            SequenceTrack rt = new SequenceTrack();
            rt.loop = true;
            rt.add(new SequenceClip(UUID.randomUUID(), 0, 5));
            SequenceTrack back = SequenceTrack.load(rt.save());
            chk("NBT \u5f80\u8fd4\u540e\u5faa\u73af\u6807\u8bb0\u8fd8\u5728", back.loop, "loop=" + back.loop);
        }

        // ---------- 6. \u63a5\u7ebf\uff1a\u505c / \u5faa\u73af\u7531\u670d\u52a1\u7aef\u6bcf\u523b\u5224\u5b9a ----------
        {
            String events = read("src/main/java/top/hmjmfabc/projector/server/ServerEvents.java");
            String server = read("src/main/java/top/hmjmfabc/projector/network/ServerNetHandler.java");
            String editor = read("src/main/java/top/hmjmfabc/projector/client/gui/SequenceEditorScreen.java");
            String bar = read("src/main/java/top/hmjmfabc/projector/client/gui/TimelineBar.java");
            String preview = read("src/main/java/top/hmjmfabc/projector/client/gui/SequencePreviewScreen.java");
            chk("\u670d\u52a1\u7aef\u6bcf\u523b\u8c03 tickSequences", events.contains("ServerNetHandler.tickSequences("), "");
            chk("tickSequences \u771f\u7684\u5728\u5224 advance", server.contains("seq.advance(gameTime)"), "");
            chk("\u53d8\u4e86\u624d\u5e7f\u64ad\uff08\u4e0d\u4e71\u53d1\uff09",
                    server.contains("if (!seq.advance(gameTime)) continue;"), "");
            chk("\u7f16\u8f91\u5668\u6709\u5faa\u73af\u5f00\u5173\u6309\u94ae",
                    editor.contains("\u5faa\u73af:\u5f00") && editor.contains("sequence.loop = !"), "");
            chk("\u7f16\u8f91\u5668\u65b0\u52a0\u7247\u6bb5\u7528\u52a8\u6001\u4e0a\u9650", editor.contains("dynamicMaxSec()"), "");
            chk("\u957f\u6761\u53ef\u62d6\u52a8\u8303\u56f4\u7528\u52a8\u6001\u4e0a\u9650", bar.contains("dynamicMaxSec()"), "");
            chk("\u9884\u89c8\u8fdb\u5ea6\u6761\u5206\u6bcd\u7528\u52a8\u6001\u4e0a\u9650", preview.contains("dynamicMaxSec()"), "");
        }

        // ---------- 7. \u3010\u4fee\u3011/nmbl \u7684 dist \u5f52\u4f4d ----------
        {
            String common = read("src/main/java/top/hmjmfabc/projector/compat/betterlogin/BetterLoginBridge.java");
            String bridge = read("src/main/java/top/hmjmfabc/projector/compat/betterlogin/client/ClientCommandBridge.java");
            String main = read("src/main/java/top/hmjmfabc/projector/Projector.java");
            String client = read("src/main/java/top/hmjmfabc/projector/client/ProjectorClient.java");
            chk("\u516c\u5171\u4fa7\u4e0d\u518d\u5f15\u7528/\u5b9e\u73b0\u5ba2\u6237\u7aef\u4e8b\u4ef6\u7c7b\u578b",
                    !common.contains("import net.neoforged.neoforge.client.")
                            && !common.contains("onRegisterClientCommands"), "");
            chk("\u670d\u52a1\u7aef\u547d\u4ee4\u5728\u516c\u5171\u4fa7\u6ce8\u518c",
                    main.contains("BetterLoginBridge::onRegisterCommands"), "");
            chk("\u5ba2\u6237\u7aef\u547d\u4ee4\u62c6\u5230\u5ba2\u6237\u7aef\u4e13\u7528\u6865",
                    bridge.contains("RegisterClientCommandsEvent")
                            && client.contains("ClientCommandBridge::onRegisterClientCommands"), "");
            chk("\u5ba2\u6237\u7aef\u5165\u53e3\u4e0d\u518d\u6ce8\u518c\u670d\u52a1\u7aef\u547d\u4ee4",
                    !client.contains("BetterLoginBridge::onRegisterCommands"), "");
        }

        System.out.println(fails == 0 ? "\n== ALL PASS ==" : "\n== " + fails + " FAILED ==");
        if (fails > 0) System.exit(1);
    }

    static String read(String rel) {
        try {
            return Files.readString(Path.of(rel));
        } catch (Exception e) {
            return "";
        }
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

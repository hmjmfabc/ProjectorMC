package top.hmjmfabc.projector.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import top.hmjmfabc.projector.common.Plane;
import top.hmjmfabc.projector.common.sequence.SequenceClip;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 【⑩】时间轴控件（自绘）。
 *
 * <p>对应剪映 PC 版那种「长条 + 可拉两端」的编辑体验：</p>
 * <ul>
 *   <li><b>框子最多显示 30 秒</b>，下方有滑块可以在 <b>0~999 秒</b>范围内左右拖到别处；</li>
 *   <li>每个片段画成一根长条，<b>两端可以拉动</b>增减显示时间，中间拖动就是整条平移；</li>
 *   <li>拖动时带<b>卡位</b>：靠近别的片段的入场/出场时刻（或整秒）时会正好吸过去；</li>
 *   <li>点一下长条选中它，点空白处拖动就是移播放头。</li>
 * </ul>
 *
 * <p>不做「自动避免重叠」——剪辑软件也允许片段叠在一起（叠了就同时显示，
 * 靠控件的 {@code zOff} 分层决定谁在前面）。卡位已经足够让玩家对齐了。</p>
 */
public class TimelineBar extends AbstractWidget {

    /** 框子里最多显示多少秒（用户要求 30 秒）。 */
    public static final double VISIBLE_SEC = 30.0;
    /** 时间轴总长度（秒）。 */
    public static final double MAX_SEC = SequenceClip.MAX_SECONDS;

    /** 刻度尺高度。 */
    private static final int RULER_H = 11;
    /** 滚动条可见高度。 */
    private static final int SCROLL_H = 6;
    /**
     * 底部控制条的总高度（◀ / 滚动条 / ▶）。
     *
     * <p>比滚动条本身高得多是<b>故意的</b>：手机上原来的滚动条只有 6 像素，
     * 手指根本按不中，于是「30 秒以后的长条选不到」——
     * 现在整条 18 像素都是触摸区，左右还各有一个 22 像素的翻页按钮。</p>
     */
    private static final int STRIP_H = 18;
    /** 底部左右两个翻页按钮的宽度。 */
    private static final int PAN_W = 22;
    /** 点一次翻页按钮走多少秒。 */
    private static final double PAN_SEC = VISIBLE_SEC;
    /**
     * 拖动「武装」阈值（像素）。
     *
     * <p>手机点一下难免抖两下；以前只要有 1 像素位移就进入拖动，
     * 于是「稍有不慎长条就挪了」。位移不到这个值就一律当成点击。</p>
     */
    private static final int DRAG_ARM_PX = 5;
    /** 拖动时鼠标进入边缘多少像素内开始自动滚动（把长条拖到视窗之外要用它）。 */
    private static final int EDGE_PAN_PX = 16;
    /** 贴边按住时，视窗每秒自动滚多少秒（一整屏 = 30 秒/秒）。 */
    private static final double EDGE_PAN_SEC_PER_SEC = 30.0;
    /** 长条高度（每一行）。 */
    private static final int ROW_H = 13;
    /** 判「抓到了端点」的像素容差。 */
    private static final int EDGE_GRAB = 5;
    /** 卡位吸附的像素容差。 */
    private static final int SNAP_PX = 7;

    /** 时间轴回调。 */
    public interface Listener {
        void onSelect(@Nullable SequenceClip clip);

        /** 片段被改动；{@code finished} 为 true 表示这一次拖动已经结束（应当提交给服务端）。 */
        void onChanged(SequenceClip clip, boolean finished);

        /** 点击空白处移动播放头（秒）。 */
        void onSeek(double sec);
    }

    private final Plane plane;
    private final Listener listener;

    /** 视窗左边缘对应的秒数（滚动位置）。 */
    private double windowStart;
    private double playheadSec;

    @Nullable
    private SequenceClip selected;
    @Nullable
    private SequenceClip hovered;

    /** 拖动状态。 */
    private static final int DRAG_NONE = 0;
    private static final int DRAG_MOVE = 1;
    private static final int DRAG_LEFT = 2;
    private static final int DRAG_RIGHT = 3;
    private static final int DRAG_SCROLL = 4;
    private static final int DRAG_PLAYHEAD = 5;
    private int dragMode = DRAG_NONE;
    @Nullable
    private SequenceClip dragClip;
    private double dragGrabSec;
    /** 按下那一刻长条的起点（拖动期间不变；抓取偏移要用它才幂等）。 */
    private double dragStartAtPress;
    /** 上次边缘自动滚动的时刻，用于按「真实经过时间」算滚动量。 */
    private long lastEdgePanMs;
    /** 按下时的鼠标位置，用于「拖动阈值」（见 {@link #DRAG_ARM_PX}）。 */
    private double pressX, pressY;
    /** 是否已经越过阈值、真正进入拖动。 */
    private boolean dragArmed;

    public TimelineBar(int x, int y, int width, int height, Plane plane, Listener listener) {
        super(x, y, width, height, Component.literal("timeline"));
        this.plane = plane;
        this.listener = listener;
    }

    // ------------------------------------------------------------------
    // 坐标换算
    // ------------------------------------------------------------------

    private int trackLeft() {
        return getX() + 2;
    }

    private int trackRight() {
        return getX() + getWidth() - 2;
    }

    private double trackW() {
        return Math.max(1, trackRight() - trackLeft());
    }

    private double pxPerSec() {
        return trackW() / VISIBLE_SEC;
    }

    private int secToX(double sec) {
        return (int) Math.round(trackLeft() + (sec - windowStart) * pxPerSec());
    }

    private double xToSec(double x) {
        return windowStart + (x - trackLeft()) / pxPerSec();
    }

    private double maxWindowStart() {
        return Math.max(0, MAX_SEC - VISIBLE_SEC);
    }

    /**
     * 改视窗位置（<b>唯一入口</b>，别处不许直接写 {@code windowStart}）。
     *
     * <p><b>为什么必须唯一：</b>拖动长条时，鼠标位置换算成「秒」要用 {@code windowStart}
     * 当参考系。视窗一旦滚动而抓取偏移没跟着补偿，下一次拖动就会把这次滚动当成
     * 鼠标移动 —— 长条瞬间被甩到 0 或 999（玩家实测的「稍有不慎就偏移到极远处」）。
     * 所以这里统一补偿：<b>视窗滚多少，抓取点就挪多少</b>，长条永远粘在光标下。</p>
     */
    private void setWindowStart(double v) {
        double nv = Math.max(0, Math.min(maxWindowStart(), v));
        if (nv == windowStart) return;
        if (isDraggingClip()) dragGrabSec += nv - windowStart;
        windowStart = nv;
    }

    /** 此刻是否正在拖动某个长条（含拉两头）。 */
    private boolean isDraggingClip() {
        return dragClip != null
                && (dragMode == DRAG_MOVE || dragMode == DRAG_LEFT || dragMode == DRAG_RIGHT);
    }

    /** 底部控制条的上边界。 */
    private int stripTop() {
        return getY() + getHeight() - STRIP_H;
    }

    /** 滚动条轨道左端（让出左边的翻页按钮）。 */
    private int barLeft() {
        return trackLeft() + PAN_W;
    }

    /** 滚动条轨道右端（让出右边的翻页按钮）。 */
    private int barRight() {
        return trackRight() - PAN_W;
    }

    /** 当前视窗显示的时间范围（供界面文字用）。 */
    public double windowStartSec() {
        return windowStart;
    }

    /**
     * 恢复视窗位置（编辑器重建控件时用）。
     *
     * <p><b>为什么必须有它：</b>{@code Screen.rebuildWidgets()} = clearWidgets + init，
     * 而编辑器每次「选中长条」都会重建一次 —— 新的 TimelineBar 视窗从 0 开始，
     * 于是「滚到 30 秒以后选中一根长条」会当场跳回开头（玩家实测的 Bug）。
     * 编辑器负责把旧视窗的位置记下来，重建后调这里恢复。</p>
     */
    public void setWindowStartSec(double sec) {
        setWindowStart(sec);
    }

    public void setSelected(@Nullable SequenceClip clip) {
        this.selected = clip;
    }

    @Nullable
    public SequenceClip selected() {
        return selected;
    }

    public void setPlayhead(double sec) {
        this.playheadSec = Math.max(0, Math.min(MAX_SEC, sec));
    }

    /**
     * 让视窗滚到某个时刻附近（<b>供程序调用</b>：选中片段、刚添加片段时用）。
     *
     * <p><b>点中长条时绝对不要调它</b>：视窗在按下的瞬间跳走，会让「光标下是哪一秒」
     * 整个错位，紧接着的拖动就把长条甩到极远处（玩家实测的第二个现象）。
     * 用户点得到的地方本来就在视窗里，不需要滚。</p>
     */
    public void ensureVisible(double sec) {
        if (sec < windowStart || sec > windowStart + VISIBLE_SEC) {
            setWindowStart(sec - VISIBLE_SEC * 0.4);
        }
    }

    /** 视窗翻页（点底部 ◀ / ▶ 时用）；{@code dir} 为 -1 / +1。 */
    public void panBy(double seconds) {
        setWindowStart(windowStart + seconds);
    }

    // ------------------------------------------------------------------
    // 行分配（避免互相压在一起看不清）
    // ------------------------------------------------------------------

    /** 给每个片段分配一行：贪心地放进第一个不重叠的行。 */
    private List<Integer> rows(List<SequenceClip> clips) {
        List<double[]> rowRanges = new ArrayList<>();
        List<Integer> out = new ArrayList<>(clips.size());
        for (SequenceClip c : clips) {
            int row = -1;
            for (int i = 0; i < rowRanges.size(); i++) {
                double[] r = rowRanges.get(i);
                if (c.startSec >= r[1] || c.endSec <= r[0]) {
                    row = i;
                    r[0] = Math.min(r[0], c.startSec);
                    r[1] = Math.max(r[1], c.endSec);
                    break;
                }
            }
            if (row < 0) {
                row = rowRanges.size();
                rowRanges.add(new double[]{c.startSec, c.endSec});
            }
            out.add(row);
        }
        return out;
    }

    private int rowTop(int row) {
        return getY() + RULER_H + 2 + row * ROW_H;
    }

    private int rowCount() {
        return Math.max(1, (getHeight() - RULER_H - STRIP_H - 6) / ROW_H);
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    @Override
    protected void renderWidget(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        int x0 = getX(), y0 = getY(), w = getWidth(), h = getHeight();
        // 外框
        gfx.fill(x0, y0, x0 + w, y0 + h, 0xF0121218);
        gfx.fill(x0, y0, x0 + w, y0 + 1, 0xFF3A3A55);
        gfx.fill(x0, y0 + h - 1, x0 + w, y0 + h, 0xFF3A3A55);
        gfx.fill(x0, y0, x0 + 1, y0 + h, 0xFF3A3A55);
        gfx.fill(x0 + w - 1, y0, x0 + w, y0 + h, 0xFF3A3A55);

        var font = Minecraft.getInstance().font;
        List<SequenceClip> clips = plane.sequence == null ? List.of() : plane.sequence.clips();
        List<Integer> rowOf = rows(clips);

        // ---- 刻度尺 ----
        gfx.fill(trackLeft(), y0 + RULER_H - 1, trackRight(), y0 + RULER_H, 0x60FFFFFF);
        // 每秒一条短刻度、每 5 秒一条长刻度 + 文字
        int firstSec = (int) Math.floor(windowStart);
        int lastSec = (int) Math.ceil(windowStart + VISIBLE_SEC);
        for (int s = Math.max(0, firstSec); s <= Math.min((int) MAX_SEC, lastSec); s++) {
            int sx = secToX(s);
            if (sx < trackLeft() || sx > trackRight()) continue;
            boolean major = s % 5 == 0;
            int tickH = major ? 6 : 3;
            gfx.fill(sx, y0 + RULER_H - 1 - tickH, sx + 1, y0 + RULER_H - 1, 0x90FFFFFF);
            if (major) {
                gfx.drawString(font, s + "s", sx + 2, y0 + 1, 0xFF9AA0B0, false);
            }
        }

        // ---- 播放头 ----
        int phX = secToX(playheadSec);
        if (phX >= trackLeft() && phX <= trackRight()) {
            gfx.fill(phX, y0 + RULER_H - 1, phX + 1, stripTop() - 2, 0xFFFF5555);
        }

        // ---- 片段长条 ----
        // 【必须裁剪到轨道区域内】以前不裁剪，超出 30 秒视窗的长条会**画到框子外面**
        // （糊在预览图/面板上），看着像能点，实际点在控件之外根本点不到 ——
        // 玩家报的「30 秒以后的控件无法被选中」有一半是它。
        hovered = null;
        int maxRow = rowCount();
        gfx.enableScissor(trackLeft(), rowTop(0) - 2, trackRight(), stripTop() - 1);
        for (int i = 0; i < clips.size(); i++) {
            SequenceClip c = clips.get(i);
            int row = rowOf.get(i) % maxRow;
            int bx = secToX(c.startSec);
            int bw = Math.max(6, secToX(c.endSec) - bx);
            if (bx > trackRight() || bx + bw < trackLeft()) continue;   // 视窗外
            int by = rowTop(row);
            int bh = ROW_H - 2;
            boolean sel = c == selected;
            boolean over = mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + bh;
            if (over) hovered = c;
            int fill = sel ? 0xFF3E7BB8 : (over ? 0xFF2F5F8C : 0xFF274B6E);
            gfx.fill(bx, by, bx + bw, by + bh, fill);
            gfx.fill(bx, by, bx + bw, by + 1, sel ? 0xFF9FD4FF : 0xFF5A8FC0);
            gfx.fill(bx, by + bh - 1, bx + bw, by + bh, sel ? 0xFF9FD4FF : 0xFF5A8FC0);
            // 入场/出场动画区用更亮的色段标出来，一眼能看出动画占了多久
            int inW = (int) Math.round(c.inDur * pxPerSec());
            int outW = (int) Math.round(c.outDur * pxPerSec());
            if (c.inAnim != 0 && inW > 0) {
                gfx.fill(bx, by + 1, Math.min(bx + inW, bx + bw - 1), by + bh - 1, 0x80FFD479);
            }
            if (c.outAnim != 0 && outW > 0) {
                gfx.fill(Math.max(bx + 1, bx + bw - outW), by + 1, bx + bw, by + bh - 1, 0x80FF8855);
            }
            // 名字
            String label = clipLabel(c);
            if (bw > 24) {
                gfx.drawString(font, label, bx + 3, by + 3, 0xFFE8E8F0, false);
            }
            // 端点抓手
            if (sel) {
                gfx.fill(bx, by, bx + 2, by + bh, 0xFFFFFFFF);
                gfx.fill(bx + bw - 2, by, bx + bw, by + bh, 0xFFFFFFFF);
            }
        }
        gfx.disableScissor();

        // ---- 底部控制条：◀ | 滚动条 | ▶ ----
        // 手机友好：整条 STRIP_H 都是触摸区，滚动条只是画在正中间那条细线。
        int cs = stripTop();
        gfx.fill(trackLeft(), cs, trackRight(), y0 + h - 1, 0xFF16181F);
        boolean canBack = windowStart > 0.5;
        boolean canFwd = windowStart < maxWindowStart() - 0.5;
        boolean overBack = mouseX < trackLeft() + PAN_W && mouseY >= cs && mouseY < y0 + h;
        boolean overFwd = mouseX >= trackRight() - PAN_W && mouseY >= cs && mouseY < y0 + h;
        gfx.fill(trackLeft() + 1, cs + 1, trackLeft() + PAN_W - 1, y0 + h - 2,
                canBack ? (overBack ? 0xFF3E7BB8 : 0xFF2B3A4E) : 0xFF1B1D24);
        gfx.fill(trackRight() - PAN_W + 1, cs + 1, trackRight() - 1, y0 + h - 2,
                canFwd ? (overFwd ? 0xFF3E7BB8 : 0xFF2B3A4E) : 0xFF1B1D24);
        gfx.drawCenteredString(font, "\u25c0", trackLeft() + PAN_W / 2 + 1, cs + (STRIP_H - 8) / 2,
                canBack ? 0xFFDDE6F2 : 0xFF555A66);
        gfx.drawCenteredString(font, "\u25b6", trackRight() - PAN_W / 2 - 1, cs + (STRIP_H - 8) / 2,
                canFwd ? 0xFFDDE6F2 : 0xFF555A66);

        int sy = cs + (STRIP_H - SCROLL_H) / 2;
        gfx.fill(barLeft(), sy, barRight(), sy + SCROLL_H - 2, 0xFF20242C);
        double frac = VISIBLE_SEC / MAX_SEC;
        int barW = Math.max(20, (int) Math.round((barRight() - barLeft()) * frac));
        double t = maxWindowStart() <= 0 ? 0 : windowStart / maxWindowStart();
        int barX = barLeft() + (int) Math.round(((barRight() - barLeft()) - barW) * t);
        gfx.fill(barX, sy, barX + barW, sy + SCROLL_H - 2, 0xFF5A5A80);

        if (clips.isEmpty()) {
            gfx.drawCenteredString(font, "\u23f1 \u65f6\u95f4\u8f74\u4e3a\u7a7a\uff1a\u5728\u4e0a\u65b9\u9884\u89c8\u56fe\u91cc\u70b9\u4e2d\u63a7\u4ef6\uff0c\u518d\u6309\u300c\u6dfb\u52a0\u5230\u6d41\u7a0b\u300d",
                    (trackLeft() + trackRight()) / 2, rowTop(0) + 2, 0xFF7A7A92);
        }
    }

    /** 长条上显示的名字：优先控件内容，其次类型。 */
    private String clipLabel(SequenceClip c) {
        for (var w : plane.widgets) {
            if (w.id.equals(c.widgetId)) {
                String s = w.label();
                return s.length() > 14 ? s.substring(0, 13) + "\u2026" : s;
            }
        }
        return "(\u5df2\u5220\u9664)";
    }

    // ------------------------------------------------------------------
    // 交互
    // ------------------------------------------------------------------

    /**
     * 命中结果：命中的长条 + 该用哪种拖动模式。
     *
     * <p><b>为什么用 record 而不是「传入 int[] 写出参」：</b>原来写成
     * {@code int[] mode = new int[DRAG_NONE]; clipAt(x, y, mode)}，而 {@code DRAG_NONE == 0}
     * ⇒ 数组长度 0 ⇒ 一旦点在长条上就 {@code modeOut[0] = ...} 越界，
     * 直接把游戏崩掉（玩家实测：时间轴里一拖长条就 FATAL）。
     * 出参数组把「长度」和「取值」两件事混在一起，最容易这样写错；改成返回值后不可能再错。</p>
     */
    private record Hit(SequenceClip clip, int mode) {
    }

    @Nullable
    private Hit clipAt(double mouseX, double mouseY) {
        if (plane.sequence == null) return null;
        List<SequenceClip> clips = plane.sequence.clips();
        List<Integer> rowOf = rows(clips);
        int maxRow = rowCount();
        for (int i = clips.size() - 1; i >= 0; i--) {   // 后画的在上面，倒序命中
            SequenceClip c = clips.get(i);
            int row = rowOf.get(i) % maxRow;
            int bx = secToX(c.startSec);
            int bw = Math.max(6, secToX(c.endSec) - bx);
            int by = rowTop(row);
            int bh = ROW_H - 2;
            if (mouseX < bx || mouseX >= bx + bw || mouseY < by || mouseY >= by + bh) continue;
            int mode;
            if (mouseX - bx <= EDGE_GRAB) mode = DRAG_LEFT;
            else if (bx + bw - mouseX <= EDGE_GRAB) mode = DRAG_RIGHT;
            else mode = DRAG_MOVE;
            return new Hit(c, mode);
        }
        return null;
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        pressX = mouseX;
        pressY = mouseY;
        dragArmed = false;          // 先把拖动「缴械」：位移不够就一直算点击
        lastEdgePanMs = System.currentTimeMillis();   // 边缘自动滚动的计时基准
        // ---- 底部控制条：◀ 翻页 / 滚动条 / ▶ 翻页 ----
        if (mouseY >= stripTop()) {
            if (mouseX < trackLeft() + PAN_W) {
                panBy(-PAN_SEC);
                return;
            }
            if (mouseX >= trackRight() - PAN_W) {
                panBy(PAN_SEC);
                return;
            }
            if (maxWindowStart() > 0) {
                dragMode = DRAG_SCROLL;
                applyScrollDrag(mouseX);
                return;
            }
        }
        if (mouseY < getY() + RULER_H) {
            // 点刻度尺 = 移播放头
            dragMode = DRAG_PLAYHEAD;
            seekTo(mouseX);
            return;
        }
        Hit hit = clipAt(mouseX, mouseY);
        if (hit != null) {
            selected = hit.clip();
            dragClip = hit.clip();
            dragMode = hit.mode();
            dragGrabSec = xToSec(mouseX);
            dragStartAtPress = hit.clip().startSec;
            // 【绝对不要在这里滚视窗】按下瞬间视窗一跳，光标下的秒数就变了参考系，
            // 紧接着的拖动会把长条甩到极远处（详见 setWindowStart 的说明）。
            // 点得到的地方本来就在视窗里，不需要滚。
            if (listener != null) listener.onSelect(hit.clip());
            return;
        }
        // 空白处：取消选中 + 移播放头
        selected = null;
        if (listener != null) listener.onSelect(null);
        dragMode = DRAG_PLAYHEAD;
        seekTo(mouseX);
    }

    private void seekTo(double mouseX) {
        double sec = Math.max(0, Math.min(MAX_SEC, xToSec(mouseX)));
        playheadSec = sec;
        if (listener != null) listener.onSeek(sec);
    }

    /**
     * 点/拖底部滚动条：滑块中心跟着光标。
     *
     * <p><b>按下就直接跳过去。</b>原先记了「按下时相对滑块左端的偏移」，
     * 而滑块左端是按当前 windowStart 算出来的 —— 于是刚按下的一瞬间
     * {@code frac} 恒等于 0，<b>只要碰到滚动条，视窗就跳回开头</b>
     * （玩家报的「选中后直接跳到开头」有一半是它）。</p>
     */
    private void applyScrollDrag(double mouseX) {
        double span = Math.max(1, barRight() - barLeft());
        double frac = (mouseX - barLeft()) / span;
        setWindowStart(frac * maxWindowStart());
    }

    /**
     * 拖到轨道边缘时自动滚动视窗（把长条拖到 30 秒之外必须靠它）。
     *
     * <p><b>按「时间」计费，不按「事件」计费：</b>拖动事件的频率随帧率变，
     * 按事件给固定秒数会变成「这台机器滚 30 秒/秒、那台滚 6 秒/次」——
     * 手感不可预测，还容易一下甩出去。这里用真实经过时间换算，
     * 单次最多按 50 毫秒算，所以一次手抖最多滚 1.5 秒。</p>
     *
     * <p>走 {@link #setWindowStart}，抓取偏移会一起补偿 —— 长条始终粘在光标下。</p>
     */
    private void edgeAutoScroll(double mouseX) {
        long now = System.currentTimeMillis();
        double dt = Math.min(0.05, Math.max(0.0, (now - lastEdgePanMs) / 1000.0));
        lastEdgePanMs = now;
        if (dt <= 0) return;
        if (mouseX < trackLeft() + EDGE_PAN_PX) setWindowStart(windowStart - EDGE_PAN_SEC_PER_SEC * dt);
        else if (mouseX > trackRight() - EDGE_PAN_PX) setWindowStart(windowStart + EDGE_PAN_SEC_PER_SEC * dt);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        switch (dragMode) {
            case DRAG_SCROLL -> {
                applyScrollDrag(mouseX);
                return true;
            }
            case DRAG_PLAYHEAD -> {
                seekTo(mouseX);
                return true;
            }
            case DRAG_MOVE, DRAG_LEFT, DRAG_RIGHT -> {
                if (dragClip == null) return false;
                // 【拖动阈值】手机点一下难免抖两下；位移不够就一直不进入拖动，
                // 免得「稍有不慎」把长条挪走（玩家实测的第二个抱怨）。
                if (!dragArmed) {
                    if (Math.abs(mouseX - pressX) + Math.abs(mouseY - pressY) < DRAG_ARM_PX) {
                        return true;
                    }
                    dragArmed = true;
                }
                // 拖到边缘就自动滚视窗（滚的同时抓取偏移会一起补偿，长条粘在光标下）
                edgeAutoScroll(mouseX);
                double sec = xToSec(mouseX);
                if (dragMode == DRAG_MOVE) {
                    double dur = dragClip.duration();
                    // 【抓取偏移必须是「按下那一刻」的常量】
                    // 以前这里写的是 `dragGrabSec - dragClip.startSec`，而 startSec 每帧都在变，
                    // 于是每帧都会把上一帧的位移再加一遍 —— 位移指数发散：
                    // 手抖 1 像素、几十帧之后就冲到 962 秒（玩家报的「偏移到极远处」的元凶）。
                    // 现在偏移取「按下时的鼠标秒数 − 按下时的起点」，公式幂等，拖多少帧都不会累积。
                    double grabOffset = dragGrabSec - dragStartAtPress;
                    double ns = Math.max(0, Math.min(MAX_SEC - dur, sec - grabOffset));
                    ns = snapMove(ns, dur, dragClip);
                    dragClip.startSec = ns;
                    dragClip.endSec = ns + dur;
                } else if (dragMode == DRAG_LEFT) {
                    double ns = Math.max(0, Math.min(dragClip.endSec, snap(sec, dragClip)));
                    dragClip.startSec = ns;
                } else {
                    double ne = Math.min(MAX_SEC, Math.max(dragClip.startSec, snap(sec, dragClip)));
                    dragClip.endSec = ne;
                }
                dragClip.sanitize();
                if (listener != null) listener.onChanged(dragClip, false);
                return true;
            }
            default -> {
                return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
            }
        }
    }

    /**
     * 卡位：把一个时刻吸附到「其它片段的入场/出场时刻」或整秒。
     *
     * <p>用户要求：「拖动时遇见其他控件的入场/出场处时，会正好卡位」。
     * 这里额外把整秒也当吸附点——剪映里对齐整秒同样是刚需，
     * 而且能让「第 2~10 秒」这种设定一次就拖准。</p>
     */
    private double snap(double sec, @Nullable SequenceClip exclude) {
        double tolSec = SNAP_PX / Math.max(0.001, pxPerSec());
        double best = sec;
        double bestDist = tolSec;
        if (plane.sequence != null) {
            for (SequenceClip c : plane.sequence.clips()) {
                if (c == exclude) continue;
                double d1 = Math.abs(sec - c.startSec);
                if (d1 < bestDist) {
                    bestDist = d1;
                    best = c.startSec;
                }
                double d2 = Math.abs(sec - c.endSec);
                if (d2 < bestDist) {
                    bestDist = d2;
                    best = c.endSec;
                }
            }
        }
        // 整秒
        double rounded = Math.round(sec);
        double dr = Math.abs(sec - rounded);
        if (dr < bestDist) {
            bestDist = dr;
            best = rounded;
        }
        return best;
    }

    /** 整条平移时的卡位：两端都可以吸附（取更近的那一端）。 */
    private double snapMove(double start, double dur, @Nullable SequenceClip exclude) {
        double tolSec = SNAP_PX / Math.max(0.001, pxPerSec());
        double bestStart = start;
        double bestDist = tolSec;
        double[] candidates = snapCandidates(exclude);
        for (double cand : candidates) {
            // 片段的起点贴到 cand
            double d = Math.abs(start - cand);
            if (d < bestDist) {
                bestDist = d;
                bestStart = cand;
            }
            // 片段的终点贴到 cand
            double d2 = Math.abs(start + dur - cand);
            if (d2 < bestDist) {
                bestDist = d2;
                bestStart = cand - dur;
            }
        }
        double rounded = Math.round(start);
        if (Math.abs(start - rounded) < bestDist) {
            bestStart = rounded;
        }
        return Math.max(0, Math.min(MAX_SEC - dur, bestStart));
    }

    private double[] snapCandidates(@Nullable SequenceClip exclude) {
        List<Double> list = new ArrayList<>();
        if (plane.sequence != null) {
            for (SequenceClip c : plane.sequence.clips()) {
                if (c == exclude) continue;
                list.add(c.startSec);
                list.add(c.endSec);
            }
        }
        list.add(0.0);
        double[] out = new double[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }

    @Override
    public void onRelease(double mouseX, double mouseY) {
        if (dragClip != null && dragArmed && dragMode != DRAG_NONE && dragMode != DRAG_SCROLL
                && dragMode != DRAG_PLAYHEAD) {
            if (listener != null) listener.onChanged(dragClip, true);
        }
        dragMode = DRAG_NONE;
        dragClip = null;
        dragArmed = false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // 滚轮 = 快速平移视窗（比拖滚动条快得多）
        if (maxWindowStart() <= 0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        double step = Math.signum(scrollY) * VISIBLE_SEC * 0.25;
        setWindowStart(windowStart - step);
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
    }

    /** 供外部（编辑器）在数据变化后刷新选中引用。 */
    public void reselect(UUID widgetId) {
        selected = plane.sequence == null ? null : plane.sequence.clipOf(widgetId);
    }
}

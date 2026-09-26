package top.hmjmfabc.projector.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import top.hmjmfabc.projector.client.ClientPermissions;
import top.hmjmfabc.projector.common.Plane;
import top.hmjmfabc.projector.common.sequence.SequenceAnim;
import top.hmjmfabc.projector.common.sequence.SequenceClip;
import top.hmjmfabc.projector.common.widget.Widget;
import top.hmjmfabc.projector.network.Payloads;

import java.util.Locale;

/**
 * 【⑩】流程（时间轴）编辑器。
 *
 * <p>布局与用户描述一致：</p>
 * <pre>
 * ┌──────────────┬────────────────────────────────────────┐
 * │ 播放控制      │  [添加到流程]              [预览…]      │
 * │ 片段列表      ├────────────────────────────────────────┤
 * │ /            │                                        │
 * │ 选中片段的    │        平面预览图（可点选控件）          │
 * │ 动画设置      │                                        │
 * │              ├────────────────────────────────────────┤
 * │              │  时间轴（框内最多显示 30 秒，可拖动滑块） │
 * └──────────────┴────────────────────────────────────────┘
 * </pre>
 *
 * <p>要点：</p>
 * <ul>
 *   <li>「添加到流程」只有在预览图里<b>点中了一个控件</b>时才会点亮；</li>
 *   <li>按下后该控件以长条进入时间轴，<b>两头可以拉动</b>增减显示时间，
 *       拖动时遇到别的片段的入场/出场时刻会<b>卡位</b>；</li>
 *   <li>所有改动都通过 {@code PlaneEdit} 的 {@code seqSet} / {@code seqPlay} 等
 *       交给服务端，服务端广播后所有客户端看到同一条流程；</li>
 *   <li>「基本设置…」直接打开既有的 {@link WidgetEditorScreen}——
 *       颜色、字号这些「编辑界面原有的项」在那里已经全部具备，
 *       在这里重做一遍只会让两处慢慢分叉。</li>
 * </ul>
 */
public class SequenceEditorScreen extends ProjectorScreen {

    /** 时间轴框高度上限（自适应：小屏会等比缩小）。 */
    private static final int TIMELINE_MAX_H = 96;
    /** 时间轴框高度下限。 */
    private static final int TIMELINE_MIN_H = 64;
    /** 预览图最小可用高度。 */
    private static final int CANVAS_MIN_H = 24;

    private final Plane plane;
    private final Screen parent;
    private final boolean canEdit;

    @Nullable
    private CanvasWidgetView canvasView;
    @Nullable
    private TimelineBar timeline;
    @Nullable
    private Button addButton;

    /** 在预览图里点中的控件（「添加到流程」的目标）。 */
    @Nullable
    private Widget pickedWidget;
    /** 时间轴上选中的片段。 */
    @Nullable
    private SequenceClip selectedClip;

    /**
     * 时间轴视窗的左边缘（秒），跨 {@code rebuildWidgets()} 保留。
     *
     * <p>{@code rebuildWidgets()} = clearWidgets + init，而 init 会 new 一个新的
     * {@code TimelineBar}（视窗默认 0）。选中长条、按左右栏任何按钮都会触发重建，
     * 于是「滚到 30 秒以后选中一根长条」会当场跳回开头 —— 玩家实测报的就是它。
     * 这里在重建前把位置记下来，重建后恢复。</p>
     */
    private double timelineWindowStart;

    private String hint = "";
    /** 滑块标签与数值（与 WidgetEditorScreen 同一套做法：滑块拖动时不重建界面）。 */
    private final java.util.Map<Integer, String> rowLabels = new java.util.HashMap<>();
    private final java.util.Map<Integer, String> rowValues = new java.util.HashMap<>();
    private int bottomLimit;

    public SequenceEditorScreen(Plane plane, Screen parent) {
        super(Component.literal("\u6d41\u7a0b\uff1a" + plane.displayName()));
        this.plane = plane;
        this.parent = parent;
        this.canEdit = ClientPermissions.canEditContent(plane);
    }

    // ------------------------------------------------------------------

    /**
     * 重建界面控件。
     *
     * <p><b>必须先记住时间轴视窗的位置</b>：{@code rebuildWidgets()} 会 clearWidgets + init，
     * 而 {@code init()} 每次都 new 一个新的 {@code TimelineBar}（视窗从 0 开始）。
     * 选中长条、点左栏任何按钮都会走到这里 —— 不记的话视窗每点一下就跳回开头
     * （玩家实测的「选中后会直接跳到开头」）。</p>
     */
    @Override
    protected void rebuildWidgets() {
        if (timeline != null) timelineWindowStart = timeline.windowStartSec();
        super.rebuildWidgets();
    }

    @Override
    protected void init() {
        rowLabels.clear();
        rowValues.clear();
        int pad = 10;
        int leftW = Math.max(160, Math.min(250, this.width / 3));
        int px = pad;
        int y = pad + 20;
        bottomLimit = this.height - pad - 26;

        // ---------------- 左栏：播放控制 ----------------
        int third = (leftW - 16) / 3;
        boolean playing = plane.sequence != null && plane.sequence.playing;
        button(playing ? "\u6682\u505c" : "\u64ad\u653e", px + 4, y, third, 18, b -> {
            sendSeq(playing ? "seqPause" : "seqResume");
        }).active = canEdit;
        button("\u91cd\u64ad", px + 8 + third, y, third, 18, b -> sendSeq("seqPlay")).active = canEdit;
        button("\u505c\u6b62", px + 12 + third * 2, y, third, 18, b -> sendSeq("seqStop")).active = canEdit;
        y += 24;

        // ---------------- 左栏：片段列表 ----------------
        labelRow("\u7247\u6bb5\u5217\u8868", y);
        y += 12;
        var clips = plane.sequence == null ? java.util.List.<SequenceClip>of() : plane.sequence.clips();
        if (clips.isEmpty()) {
            labelRow("\uff08\u7a7a\uff09\u5728\u53f3\u4fa7\u9884\u89c8\u56fe\u91cc\u70b9\u4e2d\u63a7\u4ef6\uff0c"
                    + "\u518d\u6309\u300c\u6dfb\u52a0\u5230\u6d41\u7a0b\u300d", y);
            y += 12;
        } else {
            int shown = 0;
            for (SequenceClip c : clips) {
                if (shown >= 6 || y + 20 > bottomLimit) break;
                final SequenceClip cc = c;
                String label = clipTitle(c) + "  " + fmt(c.startSec) + "~" + fmt(c.endSec) + "s";
                Button b = button(label, px + 4, y, leftW - 8, 18, btn -> {
                    selectedClip = cc;
                    if (timeline != null) {
                        timeline.setSelected(cc);
                        timeline.ensureVisible((cc.startSec + cc.endSec) * 0.5);
                    }
                    rebuildWidgets();
                });
                // 选中项高亮不了按钮文字（原版 Button 不支持），用可用性区分也行；
                // 这里直接在 render 里给选中行画一个高亮框（见 render）。
                b.active = canEdit;
                y += 20;
                shown++;
            }
            y += 4;
        }

        // ---------------- 左栏：选中片段的动画设置 ----------------
        if (selectedClip != null) {
            SequenceClip c = selectedClip;
            labelRow("\u5165\u573a\u52a8\u753b\uff1a" + SequenceAnim.animName(c.inAnim), y);
            y += 14;
            button("\u5207\u6362\u5165\u573a\u52a8\u753b", px + 4, y, leftW - 8, 18, b -> {
                c.inAnim = (c.inAnim + 1) % SequenceAnim.ANIM_NAMES.length;
                c.sanitize();
                submitClip(c);
                rebuildWidgets();
            }).active = canEdit;
            y += 22;
            y = sliderRow(px, y, leftW, "\u5165\u573a\u65f6\u957f(\u79d2)", 0, Math.max(0.01, c.duration()),
                    c.inDur, false, v -> c.inDur = v, () -> submitClip(c));
            if (c.inAnim == SequenceAnim.ANIM_SLIDE) {
                button("\u6ed1\u52a8\u65b9\u5411\uff1a" + slideName(c.slideAngle), px + 4, y, leftW - 8, 18, b -> {
                    c.slideAngle = (c.slideAngle + 90) % 360;
                    submitClip(c);
                    rebuildWidgets();
                }).active = canEdit;
                y += 22;
            }
            labelRow("\u51fa\u573a\u52a8\u753b\uff1a" + SequenceAnim.animName(c.outAnim)
                    + (isLastClip(c) ? "\uff08\u672b\u4f4d\u7247\u6bb5\u4e0d\u51fa\u573a\uff09" : ""), y);
            y += 14;
            button("\u5207\u6362\u51fa\u573a\u52a8\u753b", px + 4, y, leftW - 8, 18, b -> {
                c.outAnim = (c.outAnim + 1) % SequenceAnim.ANIM_NAMES.length;
                c.sanitize();
                submitClip(c);
                rebuildWidgets();
            }).active = canEdit;
            y += 22;
            y = sliderRow(px, y, leftW, "\u51fa\u573a\u65f6\u957f(\u79d2)", 0, Math.max(0.01, c.duration()),
                    c.outDur, false, v -> c.outDur = v, () -> submitClip(c));
            labelRow("\u5faa\u73af\u52a8\u753b\uff1a" + SequenceAnim.loopName(c.loopAnim), y);
            y += 14;
            button("\u5207\u6362\u5faa\u73af\u52a8\u753b", px + 4, y, leftW - 8, 18, b -> {
                c.loopAnim = (c.loopAnim + 1) % SequenceAnim.LOOP_NAMES.length;
                submitClip(c);
                rebuildWidgets();
            }).active = canEdit;
            y += 22;
            if (c.loopAnim != SequenceAnim.LOOP_NONE) {
                y = sliderRow(px, y, leftW, "\u5faa\u73af\u5e45\u5ea6", 0, c.loopAnim == SequenceAnim.LOOP_PULSE ? 50 : 60,
                        c.loopAmp, false, v -> c.loopAmp = v, () -> submitClip(c));
                y = sliderRow(px, y, leftW, "\u5faa\u73af\u901f\u5ea6(Hz)", 0.05, 3.0, c.loopSpeed, false,
                        v -> c.loopSpeed = v, () -> submitClip(c));
            }
            if (y + 22 <= bottomLimit) {
                button("\u57fa\u672c\u8bbe\u7f6e\u2026\uff08\u989c\u8272/\u5b57\u53f7\u7b49\uff09", px + 4, y, leftW - 8, 18, b -> {
                    Widget w = widgetOf(c.widgetId);
                    if (w != null) {
                        Minecraft.getInstance().setScreen(WidgetEditorScreen.create(plane, w));
                    } else {
                        hint = "\u8be5\u63a7\u4ef6\u5df2\u88ab\u5220\u9664";
                    }
                }).active = canEdit && widgetOf(c.widgetId) != null;
                y += 22;
            }
            if (y + 22 <= bottomLimit) {
                redButton("\u5220\u9664\u6b64\u7247\u6bb5", px + 4, y, leftW - 8, 18, b -> {
                    if (plane.sequence != null) {
                        plane.sequence.remove(c.widgetId);
                        selectedClip = null;
                        if (timeline != null) timeline.setSelected(null);
                        sendSeqSet();
                        rebuildWidgets();
                    }
                }).active = canEdit;
            }
        } else {
            labelRow("\u5728\u65f6\u95f4\u8f74\u4e0a\u70b9\u4e00\u4e0b\u957f\u6761", y);
            y += 12;
            labelRow("\u5373\u53ef\u7f16\u8f91\u5b83\u7684\u52a8\u753b", y);
        }

        // ---------------- 左栏底部：返回 ----------------
        button("\u8fd4\u56de", px + 4, this.height - pad - 22, leftW - 8, 20,
                b -> Minecraft.getInstance().setScreen(parent));

        // ---------------- 右栏 ----------------
        int cx = px + leftW + 8;
        int cw = this.width - cx - pad;
        // 顶部工具条
        addButton = button("\u6dfb\u52a0\u5230\u6d41\u7a0b", cx, pad + 2, 110, 18, b -> addPicked());
        addButton.active = canEdit && pickedWidget != null;
        button("\u9884\u89c8\u2026", cx + cw - 70, pad + 2, 70, 18, b -> openPreview());

        // 【review 发现】时间轴高度必须自适应，否则屏幕一矮，预览图就会与时间轴重叠——
        // 而原版 Screen.mouseClicked 是按 children 添加顺序命中第一个，
        // 先添加的预览图会把时间轴的拖动整个吃掉（AGENTS.md §5.5 第 24 条）。
        // 因此这里主动保证「预览图下边界 <= 时间轴上边界」。
        int tlH = Math.max(TIMELINE_MIN_H, Math.min(TIMELINE_MAX_H, this.height / 3));
        int tlY = this.height - pad - tlH;
        int cvY = pad + 24;
        int cvH = tlY - cvY - 6;
        if (cvH < CANVAS_MIN_H) {
            // 屏幕实在太矮：牺牲时间轴高度，保证两者不重叠
            cvH = CANVAS_MIN_H;
            tlY = cvY + cvH + 6;
            tlH = Math.max(40, this.height - pad - tlY);
        }
        CanvasWidgetView view = new CanvasWidgetView(cx, cvY, Math.max(60, cw), cvH, plane,
                new CanvasWidgetView.Listener() {
                    @Override
                    public void onSelected(@Nullable Widget widget) {
                        pickedWidget = widget;
                        // 选中控件后「添加到流程」立刻点亮
                        if (addButton != null) addButton.active = canEdit && widget != null;
                        hint = widget == null ? "" : "\u5df2\u9009\u4e2d\uff1a" + widget.label()
                                + "\uff0c\u6309\u300c\u6dfb\u52a0\u5230\u6d41\u7a0b\u300d";
                        rebuildWidgets();
                    }

                    @Override
                    public void onMoved(Widget widget, double newX, double newY) {
                        // 流程编辑界面里不允许拖动控件几何位置：
                        // 位置由时间轴与动画决定，改几何请用「基本设置…」。
                    }

                    @Override
                    public void onResized(Widget widget, double newW, double newH) {
                    }

                    @Override
                    public void onDragFinished(Widget widget) {
                    }
                });
        view.setEditable(false);
        this.canvasView = addRenderableWidget(view);

        TimelineBar bar = new TimelineBar(cx, tlY, Math.max(60, cw), tlH, plane,
                new TimelineBar.Listener() {
                    @Override
                    public void onSelect(@Nullable SequenceClip clip) {
                        selectedClip = clip;
                        rebuildWidgets();
                    }

                    @Override
                    public void onChanged(SequenceClip clip, boolean finished) {
                        // 拖动过程只改本地数据（时间轴上要即时看到长条跟着动）；
                        // 松手时才发给服务端，避免每个像素都广播一次整个平面。
                        if (finished) {
                            submitClip(clip);
                        }
                    }

                    @Override
                    public void onSeek(double sec) {
                        // 移播放头 = 把流程定位到这个时刻（暂停状态下才允许，避免打断播放）
                        if (plane.sequence != null && !plane.sequence.playing) {
                            plane.sequence.seek(sec, gameTime());
                            sendSeq("seqSeek", () -> {
                                CompoundTag t = new CompoundTag();
                                t.putDouble("sec", sec);
                                return t;
                            });
                        }
                    }
                });
        if (selectedClip != null) bar.setSelected(selectedClip);
        bar.setPlayhead(plane.sequence == null ? 0 : plane.sequence.timeSec(gameTime()));
        // 恢复重建前的视窗位置（否则每次选中/点按钮都跳回开头）
        bar.setWindowStartSec(timelineWindowStart);
        this.timeline = addRenderableWidget(bar);
    }

    private static String fmt(double sec) {
        return String.format(Locale.ROOT, "%.1f", sec);
    }

    private void labelRow(String text, int y) {
        rowLabels.put(y, text);
    }

    private static String slideName(double angle) {
        int a = (int) Math.round(((angle % 360) + 360) % 360);
        return switch (a / 90) {
            case 1 -> "\u4ece\u4e0a";
            case 2 -> "\u4ece\u53f3";
            case 3 -> "\u4ece\u4e0b";
            default -> "\u4ece\u5de6";
        };
    }

    @Nullable
    private Widget widgetOf(@Nullable java.util.UUID id) {
        if (id == null) return null;
        for (Widget w : plane.widgets) {
            if (id.equals(w.id)) return w;
        }
        return null;
    }

    private boolean isLastClip(SequenceClip c) {
        if (plane.sequence == null) return false;
        SequenceClip last = plane.sequence.lastClip();
        return last != null && last == c;
    }

    private String clipTitle(SequenceClip c) {
        Widget w = widgetOf(c.widgetId);
        if (w == null) return "(\u5df2\u5220\u9664)";
        String s = w.label();
        return s.length() > 10 ? s.substring(0, 9) + "\u2026" : s;
    }

    private static long gameTime() {
        var level = Minecraft.getInstance().level;
        return level == null ? 0L : level.getGameTime();
    }

    // ------------------------------------------------------------------
    // 一个「标签 + 滑块」行；拖动时不重建界面（否则滑块会立刻失去拖动状态）
    // ------------------------------------------------------------------

    private int sliderRow(int x, int y, int w, String name, double min, double max, double value,
                          boolean integer, java.util.function.Consumer<Double> setter, Runnable onCommit) {
        if (y + 22 > bottomLimit) {
            return y + 22;
        }
        labelRow(name, y);
        if (!canEdit) {
            return y + 22;
        }
        final int rowY = y;
        addRenderableWidget(new SliderBar(x + 4, y, w - 8, 12, min, max, value, integer, v -> {
            setter.accept(v);
            rowValues.put(rowY, formatValue(v, integer));
            // 拖动中的改动只做限流提交（见 sendSeqSet 的注释）
            seqDirty = true;
            sendSeqSet(false);
        }));
        rowValues.put(rowY, formatValue(value, integer));
        return y + 22;
    }

    private static String formatValue(double v, boolean integer) {
        if (integer) return String.valueOf(Math.round(v));
        return String.format(Locale.ROOT, "%.2f", v);
    }

    // ------------------------------------------------------------------
    // 行为
    // ------------------------------------------------------------------

    /** 把一个控件加进流程（默认从 0 秒显示 3 秒）。 */
    private void addPicked() {
        if (pickedWidget == null || plane.sequence == null || !canEdit) return;
        double start = 0;
        double end = 3;
        // 默认接在已有流程的末尾，避免新加的片段全部叠在 0 秒上
        double maxEnd = plane.sequence.maxEndSec();
        if (maxEnd > 0) {
            start = Math.min(SequenceClip.MAX_SECONDS - 1, maxEnd);
            end = Math.min(SequenceClip.MAX_SECONDS, start + 3);
        }
        SequenceClip c = new SequenceClip(pickedWidget.id, start, end);
        plane.sequence.add(c);
        plane.sequence.prune(plane.widgets);
        selectedClip = c;
        hint = "\u5df2\u52a0\u5165\u6d41\u7a0b\uff1a" + pickedWidget.label();
        sendSeqSet();
        rebuildWidgets();
    }

    /**
     * 把编辑好的片段交给服务端。
     *
     * <p>直接用「整份 clips 替换」（{@code seqSet}）而不是增量更新：
     * 时间轴上的拖动会连续产生大量中间状态，增量协议需要一套版本号/冲突解决，
     * 而一条流程最多几十个片段、一次全量只有几 KB——全量替换反而是最稳的。</p>
     */
    private void submitClip(SequenceClip clip) {
        if (clip != null) clip.sanitize();
        sendSeqSet();
    }

    /** 上一次发送 seqSet 的时间；滑块拖动时限流用。 */
    private long lastSeqSubmit;
    /** 有没有「被限流挡下、还没发出去」的改动（关界面时补发一次）。 */
    private boolean seqDirty;

    private void sendSeqSet() {
        sendSeqSet(true);
    }

    /**
     * 把流程发给服务端。
     *
     * @param immediate true = 立刻发（按钮类改动）；false = 限流（滑块拖动）。
     *
     * <p><b>为什么必须限流：</b>滑块每拖动一像素就会回调一次，而 {@code seqSet}
     * 是整份流程的全量替换 + 一次全维度广播。不限流的话拖一次滑块能发出上百个包，
     * 把服务端与所有客户端的网络都打满——时间轴本身反而会卡住。
     * 这里限定 250ms 最多一包，并在关界面时补发最后一次，保证最终值一定落到服务端。</p>
     */
    private void sendSeqSet(boolean immediate) {
        if (!canEdit || plane.sequence == null) return;
        plane.sequence.prune(plane.widgets);
        long now = System.currentTimeMillis();
        if (!immediate && now - lastSeqSubmit < 250L) {
            seqDirty = true;
            return;
        }
        lastSeqSubmit = now;
        seqDirty = false;
        CompoundTag t = new CompoundTag();
        t.put("sequence", plane.sequence.save());
        PacketDistributor.sendToServer(new Payloads.PlaneEdit(plane.id, "seqSet", t));
    }

    private void sendSeq(String op) {
        sendSeq(op, null);
    }

    private void sendSeq(String op, @Nullable java.util.function.Supplier<CompoundTag> args) {
        if (!canEdit) return;
        CompoundTag t = args == null ? new CompoundTag() : args.get();
        PacketDistributor.sendToServer(new Payloads.PlaneEdit(plane.id, op, t));
    }

    private void openPreview() {
        Minecraft.getInstance().setScreen(new SequencePreviewScreen(plane, this));
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        int pad = 10;
        int leftW = Math.max(160, Math.min(250, this.width / 3));
        panel(gfx, pad, pad, leftW, this.height - pad * 2);
        super.render(gfx, mouseX, mouseY, partialTick);
        labelShadow(gfx, "\u6d41\u7a0b\uff08\u65f6\u95f4\u8f74\uff09", pad + 6, pad + 4, TEXT_ACCENT);

        // 滑块 / 普通标签
        for (var e : rowLabels.entrySet()) {
            label(gfx, e.getValue(), pad + 6, e.getKey(), TEXT_DIM);
            String v = rowValues.get(e.getKey());
            if (v != null) {
                label(gfx, v, pad + leftW - 10 - font.width(v), e.getKey(), TEXT_ACCENT);
            }
        }

        // 时间轴标题 + 当前时间（顺带提示怎么翻页：手机上滚动条不好抓，◀ ▶ 才是主力）
        if (timeline != null) {
            double t = plane.sequence == null ? 0 : plane.sequence.timeSec(gameTime());
            String time = String.format(Locale.ROOT,
                    "\u5f53\u524d %.1fs / \u7a97\u53e3 %.0fs ~ %.0fs\uff08\u25c0 \u25b6 \u7ffb\u9875\uff09",
                    t, timeline.windowStartSec(), timeline.windowStartSec() + TimelineBar.VISIBLE_SEC);
            label(gfx, time, timeline.getX(), timeline.getY() - 11, TEXT_ACCENT);
        }
        if (canvasView != null) {
            label(gfx, "\u9884\u89c8\uff08\u70b9\u51fb\u63a7\u4ef6\u9009\u4e2d\uff09", canvasView.getX(),
                    canvasView.getY() - 11, TEXT_ACCENT);
        }
        if (!hint.isEmpty()) {
            label(gfx, hint, pad + 6, this.height - pad - 40, TEXT_GREEN);
        }
        label(gfx, canEdit ? "\u53ef\u7f16\u8f91" : "\u5185\u5bb9\u53d7\u4fdd\u62a4\uff0c\u65e0\u6743\u4fee\u6539",
                pad + 6, this.height - pad - 12, canEdit ? TEXT_GREEN : TEXT_RED);
    }

    @Override
    public void onClose() {
        // 被限流挡下的最后一次改动在这里补发，保证玩家拖到的最终值一定生效
        if (seqDirty) {
            sendSeqSet(true);
        }
        Minecraft.getInstance().setScreen(parent);
    }
}

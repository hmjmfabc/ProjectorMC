package top.hmjmfabc.projector.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import top.hmjmfabc.projector.common.sequence.SequenceAnim;

import java.util.function.IntConsumer;

/**
 * 【②⑦.2-pre-136】「选择动画…」的二级界面：把全部动画类型列出来，<b>每一个都能实时预览</b>。
 *
 * <p>玩家原话：流程的动画类型太少；「切换动画」这种一个个轮着试的按钮看不清有哪些可选。
 * 所以这个界面：</p>
 * <ul>
 *   <li><b>一次列全</b>（入场/出场 13 种、循环 8 种），不用轮着切；</li>
 *   <li>每一行左边都有一个<b>小预览框</b>，里面那个白色小方块正在按该动画真实地动；</li>
 *   <li>预览用的求值函数与世界里播放用的是<b>同一份</b>
 *       （{@link SequenceAnim#inEffect}/{@link SequenceAnim#outEffect}/{@link SequenceAnim#loopEffect}），
 *       所以「预览里看到的样子」就是「世界里播放的样子」；</li>
 *   <li>点中即生效（立刻提交给服务端并回到流程编辑器）。</li>
 * </ul>
 *
 * <p>预览框里的坐标是<b>屏幕像素</b>：把 {@code dist} 按预览框大小传进去，
 * 这样「位移」在预览里正好是一格多一点，视觉比例与实物一致；
 * 深度方向的动画（下落/飞入）在预览里用「先小后大 + 渐显」近似表达——
 * 二维界面上没法真的做前后位移。</p>
 */
public class SequenceAnimPickerScreen extends ProjectorScreen {

    /** 入场。 */
    public static final int MODE_IN = 0;
    /** 出场。 */
    public static final int MODE_OUT = 1;
    /** 循环。 */
    public static final int MODE_LOOP = 2;

    /** 一轮预览的周期（毫秒）。 */
    private static final long CYCLE_MS = 2600L;
    /** 一轮里「动画进行」占多久（毫秒），其余时间保持在结束状态。 */
    private static final long ANIM_MS = 1200L;

    private final Screen parent;
    private final int mode;
    private final int current;
    private final IntConsumer onPick;

    /** 每一行的预览框（x, y, w, h）与对应的动画编号；render 里按它画预览。 */
    private final java.util.List<int[]> rowBoxes = new java.util.ArrayList<>();
    private final java.util.List<Integer> rowKinds = new java.util.ArrayList<>();

    /** 玩家点过之后给一句反馈（例如「已选择：滑动」）。 */
    private String message = "";
    private long openedAt = System.currentTimeMillis();

    public SequenceAnimPickerScreen(Screen parent, int mode, int current, IntConsumer onPick) {
        super(Component.literal(titleOf(mode)));
        this.parent = parent;
        this.mode = mode;
        this.current = current;
        this.onPick = onPick;
    }

    private static String titleOf(int mode) {
        return switch (mode) {
            case MODE_OUT -> "\u9009\u62e9\u51fa\u573a\u52a8\u753b";
            case MODE_LOOP -> "\u9009\u62e9\u5faa\u73af\u52a8\u753b";
            default -> "\u9009\u62e9\u5165\u573a\u52a8\u753b";
        };
    }

    /**
     * 本界面要列的名字表。
     *
     * <p>【27.2-pre-138】出场用**自己**的名字表：编号 4 入场叫「下落」、出场叫「上升」
     * （两者逻辑互为镜像）。表中没有 {@code hintOf} 那种「另写一套」的地方，
     * 名字与说明都从 {@link SequenceAnim} 取，界面里不留第二份文案。</p>
     */
    private String[] names() {
        return switch (mode) {
            case MODE_LOOP -> SequenceAnim.LOOP_NAMES;
            case MODE_OUT -> SequenceAnim.OUT_ANIM_NAMES;
            default -> SequenceAnim.ANIM_NAMES;
        };
    }

    private String hintOf(int id) {
        return switch (mode) {
            case MODE_LOOP -> SequenceAnim.loopHint(id);
            case MODE_OUT -> SequenceAnim.outAnimHint(id);
            default -> SequenceAnim.animHint(id);
        };
    }

    @Override
    protected void init() {
        rowBoxes.clear();
        rowKinds.clear();
        int pad = 10;
        int count = names().length;
        int top = pad + 30;
        int bottom = this.height - pad - 24;
        int avail = Math.max(20, bottom - top);
        // 选列数：按「每行至少 17px」尽量用少的列（列太多按钮就窄了）；
        // 一列装不下（安卓小屏很常见）就两列、三列 —— 保证 13 种动画一次全列出来。
        int cols = 3;
        for (int c = 1; c <= 3; c++) {
            cols = c;
            int rowsNeeded = (count + c - 1) / c;
            if (rowsNeeded * 17 <= avail) break;
        }
        int rows = Math.max(1, (count + cols - 1) / cols);
        int cellH = Math.max(12, Math.min(30, avail / rows));
        int colW = (this.width - pad * 2 - (cols - 1) * 4) / cols;

        for (int i = 0; i < count; i++) {
            int col = i / rows;
            int row = i % rows;
            int cx = pad + col * (colW + 4);
            int cy = top + row * cellH;
            int boxW = Math.min(46, Math.max(16, colW / 3));
            int boxH = Math.max(11, cellH - 3);
            final int id = i;
            // 预览框位置先记下来，render 里画动态方块（按钮画不了动画）
            rowBoxes.add(new int[]{cx + 2, cy + 1, boxW, boxH});
            rowKinds.add(id);
            String label = names()[i] + (i == current ? "  \u2714" : "");
            button(label, cx + boxW + 6, cy, Math.max(36, colW - boxW - 8), cellH - 2, b -> {
                onPick.accept(id);
                message = "\u5df2\u9009\u62e9\uff1a" + names()[id];
                Minecraft.getInstance().setScreen(parent);
            });
        }

        // 底部：退出
        button("\u8fd4\u56de", pad, this.height - pad - 20, 60, 18,
                b -> Minecraft.getInstance().setScreen(parent));
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        super.render(gfx, mouseX, mouseY, partialTick);
        int pad = 10;
        labelShadow(gfx, titleOf(mode), pad, pad + 4, TEXT_ACCENT);
        label(gfx, "\u6bcf\u4e2a\u52a8\u753b\u90fd\u5728\u5b9e\u65f6\u9884\u89c8\uff08\u4e0e\u4e16\u754c\u91cc\u64ad\u653e\u7684\u662f\u540c\u4e00\u5957\uff09",
                pad + 4, pad + 18, TEXT_DIM);
        if (mode == MODE_OUT) {
            // 「末位片段不出场」是流程本身的规则，不是 bug，写在界面上免得玩家以为动画没生效
            label(gfx, "\u6ce8\uff1a\u6d41\u7a0b\u91cc\u7684\u6700\u540e\u4e00\u4e2a\u7247\u6bb5\u4e0d\u4f1a\u51fa\u573a",
                    pad + 4, this.height - pad - 32, TEXT_RED);
        }
        if (!message.isEmpty()) {
            label(gfx, message, pad + 150, this.height - pad - 15, TEXT_GREEN);
        }

        long now = System.currentTimeMillis();
        for (int i = 0; i < rowBoxes.size(); i++) {
            int[] box = rowBoxes.get(i);
            int id = rowKinds.get(i);
            // 每行的相位错开一点，看得更清楚（同一时刻各行进度不同）
            drawPreview(gfx, box, id, now);
        }
    }

    /** 画一行里的预览：一个白色小方块按该动画的状态动。 */
    private void drawPreview(GuiGraphics gfx, int[] box, int id, long now) {
        int bx = box[0], by = box[1], bw = box[2], bh = box[3];
        if (bw < 4 || bh < 4) return;
        // 预览框底色 + 边框（让「透明」也看得出来）
        gfx.fill(bx, by, bx + bw, by + bh, 0x40000000);
        gfx.fill(bx, by, bx + bw, by + 1, 0x30FFFFFF);
        gfx.fill(bx, by + bh - 1, bx + bw, by + bh, 0x30FFFFFF);
        gfx.fill(bx, by, bx + 1, by + bh, 0x30FFFFFF);
        gfx.fill(bx + bw - 1, by, bx + bw, by + bh, 0x30FFFFFF);

        // 一轮：0~ANIM_MS 动画，之后保持结束状态
        double cyclePos = ((now - openedAt) % CYCLE_MS) / (double) CYCLE_MS;
        double p = Math.min(1.0, cyclePos * CYCLE_MS / (double) ANIM_MS);
        double dist = Math.max(6.0, bh * 0.9);
        SequenceAnim.State st;
        if (mode == MODE_LOOP) {
            double phase = (now - openedAt) / 1000.0 * 2.0 * Math.PI * 0.6;
            st = SequenceAnim.loopEffect(id, phase, Math.max(6.0, bh * 0.45));
        } else if (mode == MODE_OUT) {
            // 出场：前 30% 时间停在原位，之后才走 —— 否则一进界面就看不到「原来在哪」
            double q = Math.max(0.0, (cyclePos - 0.3) / 0.7);
            st = SequenceAnim.outEffect(id, q, 0, dist);
        } else {
            st = SequenceAnim.inEffect(id, p, 0, dist);
        }
        if (!st.visible()) return;

        double baseW = bw * 0.52, baseH = bh * 0.46;
        double w = Math.max(1.0, baseW * st.effScaleX());
        double h = Math.max(0.5, baseH * st.effScaleY());
        double cx = bx + bw / 2.0 + st.dx() * 0.55;
        double cy = by + bh / 2.0 - st.dy() * 0.55;   // 屏幕 y 向下，画布 y 向上
        // 深度方向：越靠前画得越大一点点（下落/飞入在预览里的可视化）
        double depthK = 1.0 - Math.min(0.35, Math.abs(st.dDepth()) * 0.03);
        w *= depthK;
        h *= depthK;

        int alpha = (int) Math.round(255 * SequenceAnim.clamp01(st.alpha()));
        if (alpha <= 4) return;
        int color = (alpha << 24) | 0x00E8E8F0;

        gfx.pose().pushPose();
        gfx.pose().translate(cx, cy, 0);
        if (st.rotDeg() != 0) {
            gfx.pose().mulPose(new org.joml.Matrix4f()
                    .rotateZ((float) Math.toRadians(-st.rotDeg())));
        }
        int x0 = (int) Math.round(-w / 2), x1 = (int) Math.round(w / 2);
        int y0 = (int) Math.round(-h / 2), y1 = (int) Math.round(h / 2);
        gfx.fill(x0, y0, x1, y1, color);
        gfx.pose().popPose();
    }
}

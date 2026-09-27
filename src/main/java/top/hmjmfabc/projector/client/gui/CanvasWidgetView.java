package top.hmjmfabc.projector.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import top.hmjmfabc.projector.client.render.SelectionRenderer;
import top.hmjmfabc.projector.common.Plane;
import top.hmjmfabc.projector.common.PlaneCanvas;
import top.hmjmfabc.projector.common.widget.Widget;

/**
 * 画布缩略图控件。
 *
 * <p>把平面按比例缩放进一个小矩形，显示每个控件的位置与范围，并支持：</p>
 * <ul>
 *   <li>点击选中控件；</li>
 *   <li>拖拽移动控件（拖拽的结果由调用方决定是本地预览还是提交服务端）；</li>
 *   <li>拖拽右下角小方块缩放控件大小。</li>
 * </ul>
 *
 * <p>这就是设计稿里推荐的「缩略图方案」：所有控件的位置与大小调整都在
 * 这个可视化的画布上完成，比在游戏世界里盲调方便得多。</p>
 */
public class CanvasWidgetView extends AbstractWidget {

    public interface Listener {
        void onSelected(@Nullable Widget widget);

        void onMoved(Widget widget, double newX, double newY);

        void onResized(Widget widget, double newW, double newH);

        /** 拖拽结束（鼠标松开）：用于把最终状态补交一次。 */
        default void onDragFinished(Widget widget) {
        }
    }

    private final Plane plane;
    private final Listener listener;
    private boolean editable = true;

    private double scale = 1.0;
    private double offsetX;
    private double offsetY;

    @Nullable
    private Widget selected;
    @Nullable
    private Widget dragging;
    private boolean resizeMode;
    private double dragStartCanvasX;
    private double dragStartCanvasY;
    private double dragStartW;
    private double dragStartH;
    private double dragStartX;
    private double dragStartY;

    public CanvasWidgetView(int x, int y, int width, int height, Plane plane, Listener listener) {
        super(x, y, width, height, Component.literal("canvas"));
        this.plane = plane;
        this.listener = listener;
    }

    public void setEditable(boolean editable) {
        this.editable = editable;
    }

    public boolean isEditable() {
        return editable;
    }

    @Nullable
    public Widget selected() {
        return selected;
    }

    public void setSelected(@Nullable Widget w) {
        this.selected = w;
    }

    public Plane plane() {
        return plane;
    }

    /** 计算缩放比例（保持长宽比，居中显示）。 */
    private void updateTransform() {
        int cw = Math.max(1, plane.width);
        int ch = Math.max(1, plane.height);
        double sx = (double) (getWidth() - 8) / cw;
        double sy = (double) (getHeight() - 8) / ch;
        scale = Math.min(sx, sy);
        // 上限：小平面（例如 1x1 格）上也别把缩略图放大到失真
        scale = Math.min(scale, 12.0);
        if (scale <= 0 || !Double.isFinite(scale)) scale = 1;
        offsetX = getX() + (getWidth() - cw * scale) / 2.0;
        offsetY = getY() + (getHeight() - ch * scale) / 2.0;
    }

    /** 画布坐标 -> 屏幕坐标（y 轴翻转，画布向上为正）。 */
    public double[] toScreen(double cx, double cy) {
        return new double[]{offsetX + cx * scale, offsetY + (plane.height - cy) * scale};
    }

    /**
     * 把控件位置约束到「大部分落在画布内」。
     *
     * <p>允许最多 25% 伸出画布（或至少 8 单位），但控件必须有相当一部分在画布内 ——
     * 否则玩家一不小心就会把它拖到画布外几百单位处，从此在世界里再也看不到，
     * 表现出来就是「控件消失 / 尺寸看着不对」。</p>
     */
    public static double[] clampPos(double x, double y, double w, double h, int canvasW, int canvasH) {
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            return new double[]{0, 0};
        }
        double cw = Math.max(1, canvasW);
        double ch = Math.max(1, canvasH);
        double slackX = Math.max(8.0, Math.min(w, cw) * 0.25);
        double slackY = Math.max(8.0, Math.min(h, ch) * 0.25);
        double nx = Math.max(-slackX, Math.min(cw - Math.min(w, cw) * 0.5, x));
        double ny = Math.max(-slackY, Math.min(ch - Math.min(h, ch) * 0.5, y));
        return new double[]{nx, ny};
    }

    /** 屏幕坐标 -> 画布坐标。 */
    public double[] toCanvas(double sx, double sy) {
        return new double[]{(sx - offsetX) / scale, plane.height - (sy - offsetY) / scale};
    }

    @Override
    protected void renderWidget(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        // 服务端每次广播都会让 plane.widgets 换成新实例，
        // 因此每帧按 UUID 重新绑定选中项，否则高亮与缩放手柄会在同步后消失。
        if (selected != null) {
            Widget fresh = plane.widgetById(selected.id);
            if (fresh != null) {
                selected = fresh;
            }
        }
        if (dragging != null) {
            Widget fresh = plane.widgetById(dragging.id);
            if (fresh != null) {
                dragging = fresh;
            } else {
                dragging = null;
            }
        }
        updateTransform();
        int cw = Math.max(1, plane.width);
        int ch = Math.max(1, plane.height);

        // 画布底板
        gfx.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), 0xFF0A0A12);
        double[] tl = toScreen(0, ch);
        double[] br = toScreen(cw, 0);
        int bx0 = (int) Math.floor(tl[0]);
        int by0 = (int) Math.floor(tl[1]);
        int bx1 = (int) Math.ceil(br[0]);
        int by1 = (int) Math.ceil(br[1]);
        gfx.fill(bx0, by0, bx1, by1, 0xFF1A1A28);

        // 每格一条参考线（最多 64 条，防止大平面卡顿）
        int stepX = Math.max(1, cw / 64);
        int stepY = Math.max(1, ch / 64);
        for (int gx = 0; gx <= cw; gx += stepX * PlaneCanvas.UNITS_PER_BLOCK) {
            double[] p = toScreen(gx, ch);
            double[] q = toScreen(gx, 0);
            gfx.fill((int) p[0], (int) p[1], (int) p[0] + 1, (int) q[1], 0x22FFFFFF);
        }
        for (int gy = 0; gy <= ch; gy += stepY * PlaneCanvas.UNITS_PER_BLOCK) {
            double[] p = toScreen(0, gy);
            double[] q = toScreen(cw, gy);
            gfx.fill((int) p[0], (int) p[1], (int) q[0], (int) p[1] + 1, 0x22FFFFFF);
        }

        // 各控件
        for (Widget w : plane.widgets) {
            drawWidget(gfx, w, w == selected);
        }

        // 外框
        gfx.fill(bx0, by0, bx1, by0 + 1, 0xFF5A5A80);
        gfx.fill(bx0, by1 - 1, bx1, by1, 0xFF5A5A80);
        gfx.fill(bx0, by0, bx0 + 1, by1, 0xFF5A5A80);
        gfx.fill(bx1 - 1, by0, bx1, by1, 0xFF5A5A80);
    }

    private void drawWidget(GuiGraphics gfx, Widget w, boolean isSelected) {
        double[] aabb = SelectionRenderer.widgetAabb(w);
        double[] p0 = toScreen(aabb[0], aabb[3]);
        double[] p1 = toScreen(aabb[2], aabb[1]);
        int x0 = (int) Math.floor(p0[0]);
        int y0 = (int) Math.floor(p0[1]);
        int x1 = (int) Math.ceil(p1[0]);
        int y1 = (int) Math.ceil(p1[1]);
        if (x1 <= x0) x1 = x0 + 1;
        if (y1 <= y0) y1 = y0 + 1;

        // 图片/视频：直接把素材贴图画进预览。
        // 预选用的是 GUI 渲染管线（与世界中那条完全不同），所以它顺带是一个排查工具：
        // 预览里有图、世界里没有 -> 世界渲染问题；两边都没有 -> 纹理本身没就绪。
        boolean drewMedia = drawMediaThumb(gfx, w, x0, y0, x1, y1);

        int fill = switch (w.kind()) {
            case Widget.KIND_TEXT -> 0x5533AAFF;
            case Widget.KIND_IMAGE -> 0x5533FF88;
            case Widget.KIND_VIDEO -> 0x55FF8833;
            case Widget.KIND_CLOCK -> 0x55AAAAFF;
            case Widget.KIND_WEATHER -> 0x55FFDD33;
            case Widget.KIND_PROGRESS -> 0x55FF44AA;
            case Widget.KIND_MUSIC -> 0x554FC3F7;
            default -> 0x55FFFFFF;
        };
        gfx.fill(x0, y0, x1, y1, fill);
        if (drewMedia) {
            // 已经有真实素材了，背景色只留很淡的一层，别盖住图
            fill = (fill & 0x00FFFFFF) | 0x22000000;
        }
        int border = isSelected ? 0xFFFFD54A : 0x80FFFFFF;
        gfx.fill(x0, y0, x1, y0 + 1, border);
        gfx.fill(x0, y1 - 1, x1, y1, border);
        gfx.fill(x0, y0, x0 + 1, y1, border);
        gfx.fill(x1 - 1, y0, x1, y1, border);

        if (isSelected && editable) {
            // 右下角缩放手柄
            gfx.fill(x1 - 4, y1 - 4, x1, y1, 0xFFFFD54A);
        }
        if (w == dragging) {
            gfx.fill(x0, y0, x1, y1, 0x33FFFFFF);
        }
    }

    /**
     * 把图片/视频素材画进预览框，成功返回 true。
     *
     * @param sx0,sy0,sx1,sy1 预览框在屏幕上的像素范围
     */
    private boolean drawMediaThumb(GuiGraphics gfx, Widget w, int sx0, int sy0, int sx1, int sy1) {
        try {
            String mediaId;
            double u0, v0, u1, v1;
            if (w instanceof top.hmjmfabc.projector.common.widget.ImageWidget iw) {
                mediaId = iw.mediaId;
                u0 = iw.u0;
                v0 = iw.v0;
                u1 = iw.u1;
                v1 = iw.v1;
            } else if (w instanceof top.hmjmfabc.projector.common.widget.VideoWidget vw) {
                mediaId = vw.mediaId;
                u0 = 0;
                v0 = 0;
                u1 = 1;
                v1 = 1;
            } else {
                return false;
            }
            if (mediaId == null || mediaId.isEmpty()) return false;
            var entry = top.hmjmfabc.projector.client.media.MediaCache.image(mediaId);
            if (entry == null) return false;
            int tw = Math.max(1, entry.width);
            int th = Math.max(1, entry.height);
            int uw = (int) Math.round((u1 - u0) * tw);
            int vh = (int) Math.round((v1 - v0) * th);
            if (uw <= 0 || vh <= 0) return false;
            gfx.blit(entry.location, sx0, sy0, sx1 - sx0 + 1, sy1 - sy0 + 1,
                    (float) (u0 * tw), (float) (v0 * th), uw, vh, tw, th);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 交互
    // ------------------------------------------------------------------

    /**
     * 只要点在画布区域内就算「命中」，返回 true 让容器把后续拖拽事件转发过来。
     *
     * <p>这一点很关键：原版 {@code AbstractWidget.onClick} 只在 {@code clicked()} 为 true
     * 时才会被调用，而我们希望在画布的任意空白处点击也能取消选中；
     * 同时拖动控件时鼠标经常会移到控件外，必须保证容器仍然把拖拽事件送给我们。</p>
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!this.active || !this.visible) return false;
        if (button != 0) return false;   // 只响应左键，避免右键/中键改选中态
        if (mouseX < getX() || mouseX >= getX() + getWidth()
                || mouseY < getY() || mouseY >= getY() + getHeight()) {
            return false;
        }
        Minecraft.getInstance().getSoundManager().play(
                net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                        net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
        this.onClick(mouseX, mouseY);
        return true;
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        updateTransform();
        double[] c = toCanvas(mouseX, mouseY);
        Widget hit = hitWidget(c[0], c[1]);
        if (hit == null) {
            selected = null;
            listener.onSelected(null);
            return;
        }
        selected = hit;
        listener.onSelected(hit);
        if (!editable) return;

        double[] aabb = SelectionRenderer.widgetAabb(hit);
        double[] handle = toScreen(aabb[2], aabb[1]);
        resizeMode = Math.abs(mouseX - handle[0]) < 6 && Math.abs(mouseY - handle[1]) < 6;
        dragging = hit;
        dragStartCanvasX = c[0];
        dragStartCanvasY = c[1];
        dragStartW = hit.w;
        dragStartH = hit.h;
        dragStartX = hit.x;
        dragStartY = hit.y;
    }

    /**
     * 接管拖拽事件。
     *
     * <p>原版 {@code Screen}（容器）在收到拖拽时会把事件转发给「当前被拖拽的子组件」。
     * 我们的做法是在 {@link #onClick} 里记录拖拽起点，之后由本方法持续更新，
     * 因此这里只需要在有拖拽目标时返回 true，把事件「消费掉」。</p>
     */
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging == null) return false;
        updateTransform();
        double[] c = toCanvas(mouseX, mouseY);
        double dx = c[0] - dragStartCanvasX;
        double dy = c[1] - dragStartCanvasY;
        if (resizeMode) {
            double nw = Math.max(0.5, dragStartW + dx);
            double nh = Math.max(0.5, dragStartH + dy);
            // 节流：只有变化超过 1 个单位（1/16 格）时才回调，避免鼠标移动刷屏网络包
            if (Math.abs(nw - dragging.w) < 1.0 && Math.abs(nh - dragging.h) < 1.0) {
                return true;
            }
            listener.onResized(dragging, nw, nh);
        } else {
            double nx = dragStartX + dx;
            double ny = dragStartY + dy;
            if (Math.abs(nx - dragging.x) < 1.0 && Math.abs(ny - dragging.y) < 1.0) {
                return true;
            }
            listener.onMoved(dragging, nx, ny);
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        // 只保留这一个释放入口：原版 super.mouseReleased 会回调 onRelease，
        // 两处都写会导致 onDragFinished 被触发两次（重复发网络包）。
        if (dragging != null) {
            listener.onDragFinished(dragging);
        }
        dragging = null;
        resizeMode = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Nullable
    public Widget hitWidget(double cx, double cy) {
        for (int i = plane.widgets.size() - 1; i >= 0; i--) {
            Widget w = plane.widgets.get(i);
            if (w.hitTest(cx, cy, 0.5)) return w;
        }
        return null;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, Component.literal("\u5e73\u9762\u753b\u5e03"));
    }
}

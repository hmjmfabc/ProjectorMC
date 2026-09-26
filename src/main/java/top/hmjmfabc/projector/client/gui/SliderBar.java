package top.hmjmfabc.projector.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * 轻量滑块（自绘）。
 *
 * <p>用于「无极调节」字号、时长、透明度等连续量。支持点击与拖拽，
 * 在触屏上同样可用（Android 触控启动器会把触摸映射成鼠标事件）。</p>
 */
public class SliderBar extends AbstractWidget {

    private final double min;
    private final double max;
    private final boolean integer;
    private final Consumer<Double> onChange;
    private double value;
    private boolean dragging;

    public SliderBar(int x, int y, int width, int height, double min, double max, double value,
                     boolean integer, Consumer<Double> onChange) {
        super(x, y, width, height, Component.literal("slider"));
        this.min = min;
        this.max = max;
        this.value = clamp(value);
        this.integer = integer;
        this.onChange = onChange;
    }

    public double value() {
        return value;
    }

    public void setValue(double v) {
        this.value = clamp(v);
    }

    private double clamp(double v) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    private void apply(double mouseX) {
        double t = (mouseX - getX()) / Math.max(1.0, getWidth());
        t = Math.max(0, Math.min(1, t));
        double nv = min + t * (max - min);
        if (integer) nv = Math.round(nv);
        value = clamp(nv);
        if (onChange != null) {
            onChange.accept(value);
        }
    }

    @Override
    protected void renderWidget(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        int y = getY() + getHeight() / 2;
        gfx.fill(getX(), y - 1, getX() + getWidth(), y + 1, 0xFF3A3A55);
        double t = (value - min) / Math.max(1.0e-6, (max - min));
        int hx = getX() + (int) Math.round(t * getWidth());
        gfx.fill(getX(), y - 1, hx, y + 1, 0xFF7FD4FF);
        gfx.fill(hx - 3, getY(), hx + 3, getY() + getHeight(), isHoveredOrFocused() ? 0xFFFFFFFF : 0xFFCCCCDD);
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        dragging = true;
        apply(mouseX);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging) {
            apply(mouseX);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public void onRelease(double mouseX, double mouseY) {
        dragging = false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
    }

    /** 便捷：创建一个带数值标签的滑块。 */
    public static SliderBar create(int x, int y, int w, int h, double min, double max, double value,
                                   boolean integer, Consumer<Double> onChange) {
        return new SliderBar(x, y, w, h, min, max, value, integer, onChange);
    }
}

package top.hmjmfabc.projector.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;

/**
 * 调色盘里的一个色块。
 *
 * <p>颜色取自 Minecraft 传统 16 色（{@code &0}~{@code &f}），
 * 与设计稿里「调色盘使用格式化代码中规定的颜色」一致。</p>
 */
public class ColorSwatch extends AbstractWidget {

    private final int color;
    private final boolean selected;
    private final IntConsumer onPick;

    public ColorSwatch(int x, int y, int size, int color, boolean selected, IntConsumer onPick) {
        super(x, y, size, size, Component.literal("color"));
        this.color = color;
        this.selected = selected;
        this.onPick = onPick;
    }

    @Override
    protected void renderWidget(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        gfx.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), color | 0xFF000000);
        int border = selected ? 0xFFFFFFFF : (isHoveredOrFocused() ? 0xFFFFD54A : 0xFF303040);
        gfx.fill(getX(), getY(), getX() + getWidth(), getY() + 1, border);
        gfx.fill(getX(), getY() + getHeight() - 1, getX() + getWidth(), getY() + getHeight(), border);
        gfx.fill(getX(), getY(), getX() + 1, getY() + getHeight(), border);
        gfx.fill(getX() + getWidth() - 1, getY(), getX() + getWidth(), getY() + getHeight(), border);
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        if (onPick != null) {
            onPick.accept(color);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
    }
}

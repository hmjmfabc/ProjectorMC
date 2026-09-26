package top.hmjmfabc.projector.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 极简可滚动列表控件（自绘）。
 *
 * <p>没有使用原版的 {@code ObjectSelectionList}，原因是本模组需要在列表项里
 * 显示自定义的多行信息（文件名、尺寸、时长），自绘更直接，也避免了
 * 与版本间的泛型签名差异。</p>
 */
public class ScrollList<T> extends AbstractWidget {

    private final List<T> items = new ArrayList<>();
    private final Function<T, String> primary;
    private final Function<T, String> secondary;
    private final BiConsumer<T, Integer> onPick;
    private final int itemHeight = 20;

    private int scroll;
    private T hovered;

    public ScrollList(int x, int y, int width, int height,
                      Function<T, String> primary, Function<T, String> secondary,
                      BiConsumer<T, Integer> onPick) {
        super(x, y, width, height, Component.literal("list"));
        this.primary = primary;
        this.secondary = secondary;
        this.onPick = onPick;
    }

    public void setItems(List<T> newItems) {
        items.clear();
        if (newItems != null) items.addAll(newItems);
        scroll = 0;
    }

    public List<T> items() {
        return items;
    }

    public T hovered() {
        return hovered;
    }

    private int visibleRows() {
        return Math.max(1, getHeight() / itemHeight);
    }

    private int maxScroll() {
        return Math.max(0, items.size() - visibleRows());
    }

    @Override
    protected void renderWidget(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        gfx.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), 0xFF12121C);
        hovered = null;
        if (items.isEmpty()) {
            gfx.drawCenteredString(net.minecraft.client.Minecraft.getInstance().font,
                    "\uff08\u7a7a\uff09", getX() + getWidth() / 2, getY() + getHeight() / 2 - 4, 0xFF808090);
            return;
        }
        int rows = visibleRows();
        for (int i = 0; i < rows; i++) {
            int idx = scroll + i;
            if (idx >= items.size()) break;
            T item = items.get(idx);
            int y = getY() + i * itemHeight;
            boolean over = mouseX >= getX() && mouseX < getX() + getWidth()
                    && mouseY >= y && mouseY < y + itemHeight;
            if (over) {
                hovered = item;
                gfx.fill(getX(), y, getX() + getWidth(), y + itemHeight, 0x40FFFFFF);
            }
            gfx.drawString(net.minecraft.client.Minecraft.getInstance().font,
                    primary.apply(item), getX() + 4, y + 2, 0xFFE8E8F0, false);
            String sub = secondary == null ? null : secondary.apply(item);
            if (sub != null) {
                gfx.drawString(net.minecraft.client.Minecraft.getInstance().font,
                        sub, getX() + 4, y + 11, 0xFF9090A8, false);
            }
        }
        // 滚动条
        if (maxScroll() > 0) {
            int barH = Math.max(8, getHeight() * rows / Math.max(1, items.size()));
            int barY = getY() + (getHeight() - barH) * scroll / maxScroll();
            gfx.fill(getX() + getWidth() - 3, barY, getX() + getWidth() - 1, barY + barH, 0xFF5A5A80);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.signum(scrollY)));
        return true;
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        // 直接用点击坐标算行号，不要依赖上一帧渲染时记下的 hovered：
        // 在触屏上「移动 + 按下」可能在同一批次到达，此时 hovered 还是旧值，
        // 会造成点错行甚至点不中。
        int row = (int) ((mouseY - getY()) / itemHeight);
        int index = scroll + row;
        if (row < 0 || index < 0 || index >= items.size()) return;
        T item = items.get(index);
        hovered = item;
        if (onPick != null) {
            onPick.accept(item, index);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
    }
}

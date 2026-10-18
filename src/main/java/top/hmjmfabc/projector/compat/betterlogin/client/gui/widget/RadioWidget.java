package top.hmjmfabc.projector.compat.betterlogin.client.gui.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.layouts.LayoutSettings;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

public class RadioWidget extends AbstractWidget {
    protected List<Option> options = new ArrayList<>();
    protected LinearLayout layout;
    protected int selectedIndex = -1;
    protected Consumer<Integer> onOptionSelected;

    public RadioWidget(
            int pX, int pY, List<Component> options,
            int optionHorizontalPadding, int optionVerticalPadding,
            Consumer<Integer> onOptionSelected
    ) {
        super(pX, pY, 0, 0, Component.empty());
        this.onOptionSelected = onOptionSelected;

        Font font = Minecraft.getInstance().font;
        int lineHeight = font.lineHeight + 2 * optionVerticalPadding;

        int width = options.stream()
                .map(text -> {
                    int index = this.options.size();
                    Option option = new Option(text, font, () -> setSelected(index));
                    this.options.add(option);
                    return option.getWidth() + 2 * optionHorizontalPadding;
                })
                .reduce(Integer::sum)
                .orElse(0);

        setWidth(width);
        setHeight(lineHeight);

        layout = new LinearLayout(width, lineHeight, LinearLayout.Orientation.HORIZONTAL);
        layout.setPosition(pX, pY);
        LayoutSettings layoutSettings = layout.newCellSettings()
                .paddingHorizontal(optionHorizontalPadding)
                .paddingVertical(optionVerticalPadding)
                .alignHorizontallyCenter()
                .alignVerticallyMiddle();
        this.options.forEach(option -> layout.addChild(option, layoutSettings));
    }

    public void setSelected(int index) {
        if (selectedIndex == index || index < 0 || index >= options.size()) {
            return;
        }
        if (selectedIndex != -1) {
            options.get(selectedIndex).setSelected(false);
        }
        selectedIndex = index;

        options.get(index).setSelected(true);

        onOptionSelected.accept(index);
    }

    @Override
    protected void renderWidget(GuiGraphics guiGraphics, int i, int i1, float v) {
        layout.setPosition(getX(), getY());
        layout.arrangeElements();
        for (Option option : options) {
            option.render(guiGraphics, i, i1, v);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
    }

    @Override
    public boolean mouseClicked(double pMouseX, double pMouseY, int pButton) {
        Iterator<Option> iterator = this.options.iterator();
        Option option;
        do {
            if (!iterator.hasNext()) {
                return false;
            }
            option = iterator.next();
        } while (!option.mouseClicked(pMouseX, pMouseY, pButton));
        return true;
    }

    public static class Option extends AbstractWidget {
        protected Component text;
        protected Component underlinedMessage;
        protected boolean isSelected = false;
        protected Runnable onPress;

        public Option(Component text, Font font, Runnable onPress) {
            super(0, 0, font.width(text), font.lineHeight, text);
            this.text = text;
            this.underlinedMessage = ComponentUtils.mergeStyles(text.copy(), Style.EMPTY.withUnderlined(true));
            this.onPress = onPress;
        }

        public void setSelected(boolean selected) {
            isSelected = selected;
        }

        @Override
        protected void renderWidget(GuiGraphics guiGraphics, int i, int i1, float v) {
            int color = isSelected ? 0xFFFFFFFF : 0xFFAAAAAA;
            Component displayText = isHovered() ? underlinedMessage : text;
            guiGraphics.drawString(Minecraft.getInstance().font, displayText, getX(), getY(), color);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        }

        @Override
        public void onClick(double pMouseX, double pMouseY) {
            onPress.run();
        }
    }
}

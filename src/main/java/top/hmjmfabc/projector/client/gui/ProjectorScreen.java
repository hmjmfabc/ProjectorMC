package top.hmjmfabc.projector.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * 模组所有界面的基类，提供一套轻量的布局与绘制工具。
 *
 * <p>之所以不直接堆 {@code Button.builder(...)}，是因为本模组界面里控件数量多、
 * 需要动态增删（例如按权限隐藏按钮），统一封装后代码更短、更不容易出错。</p>
 */
public abstract class ProjectorScreen extends Screen {

    /** 面板背景色（半透明深色）。 */
    protected static final int PANEL_BG = 0xD0101018;
    protected static final int PANEL_BORDER = 0xFF3A3A55;
    protected static final int TEXT_NORMAL = 0xFFE8E8F0;
    protected static final int TEXT_DIM = 0xFF9A9AB0;
    protected static final int TEXT_RED = 0xFFFF5555;
    protected static final int TEXT_GREEN = 0xFF66DD77;
    protected static final int TEXT_ACCENT = 0xFF7FD4FF;

    protected ProjectorScreen(Component title) {
        super(title);
    }

    // ------------------------------------------------------------------
    // 组件工厂
    // ------------------------------------------------------------------

    protected Button button(String label, int x, int y, int w, int h, Button.OnPress action) {
        Button b = Button.builder(Component.literal(label), action).bounds(x, y, w, h).build();
        addRenderableWidget(b);
        return b;
    }

    protected Button button(Component label, int x, int y, int w, int h, Button.OnPress action) {
        Button b = Button.builder(label, action).bounds(x, y, w, h).build();
        addRenderableWidget(b);
        return b;
    }

    protected Button redButton(String label, int x, int y, int w, int h, Button.OnPress action) {
        Button b = Button.builder(Component.literal(label).withStyle(s -> s.withColor(0xFF5555)), action)
                .bounds(x, y, w, h).build();
        addRenderableWidget(b);
        return b;
    }

    protected EditBox editBox(int x, int y, int w, int h, String initial, int maxLength,
                              @Nullable Consumer<String> responder) {
        EditBox box = new EditBox(font, x, y, w, h, Component.empty());
        box.setMaxLength(maxLength);
        box.setValue(initial == null ? "" : initial);
        if (responder != null) {
            box.setResponder(responder);
        }
        addRenderableWidget(box);
        return box;
    }

    // ------------------------------------------------------------------
    // 绘制工具
    // ------------------------------------------------------------------

    protected void panel(GuiGraphics gfx, int x, int y, int w, int h) {
        gfx.fill(x, y, x + w, y + h, PANEL_BG);
        gfx.fill(x, y, x + w, y + 1, PANEL_BORDER);
        gfx.fill(x, y + h - 1, x + w, y + h, PANEL_BORDER);
        gfx.fill(x, y, x + 1, y + h, PANEL_BORDER);
        gfx.fill(x + w - 1, y, x + w, y + h, PANEL_BORDER);
    }

    protected void label(GuiGraphics gfx, String text, int x, int y, int color) {
        gfx.drawString(font, text, x, y, color, false);
    }

    protected void labelShadow(GuiGraphics gfx, String text, int x, int y, int color) {
        gfx.drawString(font, text, x, y, color, true);
    }

    protected void centeredLabel(GuiGraphics gfx, String text, int centerX, int y, int color) {
        gfx.drawCenteredString(font, text, centerX, y, color);
    }

    protected void divider(GuiGraphics gfx, int x, int y, int w) {
        gfx.fill(x, y, x + w, y + 1, 0x40FFFFFF);
    }

    // ------------------------------------------------------------------
    // 通用行为
    // ------------------------------------------------------------------

    @Override
    public boolean isPauseScreen() {
        // 平面/控件编辑不暂停游戏，方便边看效果边改（与原版告示牌一致）
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        // 半透明遮罩，保证界面内容可读
        gfx.fill(0, 0, this.width, this.height, 0x88000000);
    }

    // 注意：原版 Screen.render() 内部已经会调用 renderBackground()，
    // 这里不要再显式调用一次，否则遮罩会被叠加两遍（画面过暗、白费一遍全屏填充）。

    /** 便捷：把按钮设为不可用并给出提示。 */
    protected static void disable(@Nullable AbstractWidget w, String tooltip) {
        if (w != null) {
            w.active = false;
        }
    }

    /**
     * 取语言文件里的一条文本，并去掉里面的 {@code §} 颜色码。
     *
     * <p>为什么需要它：{@code §c…} 这类格式码是给**聊天栏**用的（那里由
     * {@code Component} 渲染管线解析），而界面上我们用 {@code drawString} +
     * 自己的配色画文字，格式码会原样变成两个奇怪的字符。
     * 所以界面用 {@code plainLang(...)}，聊天用 {@code Component.translatable(...)}。</p>
     *
     * @param key      语言文件键
     * @param fallback 取不到时的兜底文本（例如资源包缺这一条）
     */
    protected static String plainLang(String key, String fallback) {
        try {
            String s = net.minecraft.network.chat.Component.translatable(key).getString();
            StringBuilder b = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\u00a7' && i + 1 < s.length()) {
                    i++;
                    continue;
                }
                b.append(c);
            }
            return b.length() == 0 ? fallback : b.toString();
        } catch (Throwable t) {
            return fallback;
        }
    }
}

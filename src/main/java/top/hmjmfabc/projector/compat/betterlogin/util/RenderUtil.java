package top.hmjmfabc.projector.compat.betterlogin.util;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

public class RenderUtil {

    public static void drawCenteredString(GuiGraphics guiGraphics, Font font, Component text, int x, int y, int color, boolean shadow) {
        guiGraphics.drawString(font, text, x - font.width(text) / 2, y, color, shadow);
    }

    public static void drawCenteredStringNoShadow(GuiGraphics guiGraphics, Font font, Component text, int x, int y, int color) {
        drawCenteredString(guiGraphics, font, text, x, y, color, false);
    }

}

package top.hmjmfabc.projector.compat.betterlogin.client.gui.function;

import net.minecraft.client.gui.GuiGraphics;

@FunctionalInterface
public interface RenderConsumer {
    void accept(GuiGraphics graphics, int mouseX, int mouseY, float partialTick);
}

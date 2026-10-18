package top.hmjmfabc.projector.compat.neo.client.event;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 【Fabric 移植层】{@code top.hmjmfabc.projector.compat.neo.client.event.RenderGuiEvent} 的替身。
 *
 * <p>本模组只用 {@link Post}：HUD 画完之后叠加"投影仪"的提示层。
 * Fabric 侧对应 {@code HudRenderCallback}。</p>
 */
public class RenderGuiEvent {

    private final GuiGraphics guiGraphics;

    private RenderGuiEvent(GuiGraphics guiGraphics) {
        this.guiGraphics = guiGraphics;
    }

    public GuiGraphics getGuiGraphics() {
        return guiGraphics;
    }

    /** HUD 绘制结束之后。 */
    public static final class Post extends RenderGuiEvent {
        public Post(GuiGraphics guiGraphics) {
            super(guiGraphics);
        }
    }
}

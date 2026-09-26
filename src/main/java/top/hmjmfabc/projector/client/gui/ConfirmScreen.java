package top.hmjmfabc.projector.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 通用确认对话框（用于删除控件 / 删除平面等不可逆操作）。
 */
public class ConfirmScreen extends ProjectorScreen {

    private final Screen parent;
    private final String message;
    private final String detail;
    private final Runnable onConfirm;

    public ConfirmScreen(Screen parent, String message, String detail, Runnable onConfirm) {
        super(Component.literal(message));
        this.parent = parent;
        this.message = message;
        this.detail = detail;
        this.onConfirm = onConfirm;
    }

    @Override
    protected void init() {
        int w = 220;
        int x = (this.width - w) / 2;
        int y = (this.height - 90) / 2;
        button("\u786e\u5b9a", x + 10, y + 62, (w - 30) / 2, 20, b -> {
            try {
                onConfirm.run();
            } catch (Throwable t) {
                top.hmjmfabc.projector.Projector.LOGGER.error("[Projector] 确认操作执行失败", t);
            } finally {
                // 【必须放在 finally 里】回调自己可能已经切走了界面（例如删除后直接回管理平面），
                // 那就不要再动；如果它还赖在这里，就由确认框负责退场。
                // 放 finally 是为了保证「回调抛异常」时也不会把玩家困在确认框里。
                Minecraft mc = Minecraft.getInstance();
                if (mc.screen == this) {
                    mc.setScreen(parent);
                }
            }
        });
        button("\u53d6\u6d88", x + 20 + (w - 30) / 2, y + 62, (w - 30) / 2, 20,
                b -> Minecraft.getInstance().setScreen(parent));
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        super.render(gfx, mouseX, mouseY, partialTick);
        int w = 220;
        int h = 90;
        int x = (this.width - w) / 2;
        int y = (this.height - h) / 2;
        panel(gfx, x, y, w, h);
        centeredLabel(gfx, message, this.width / 2, y + 16, TEXT_RED);
        centeredLabel(gfx, detail, this.width / 2, y + 34, TEXT_DIM);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}

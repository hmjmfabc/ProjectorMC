package top.hmjmfabc.projector.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import top.hmjmfabc.projector.client.SelectionState;
import top.hmjmfabc.projector.common.Plane;
import top.hmjmfabc.projector.common.widget.Widget;

/**
 * 轻量 HUD 提示。
 *
 * <p>只在「已经圈选了一个平面」时显示一行提示，内容包括平面名称与可用按键，
 * 以及准心所指控件的名称。目的是让操作路径对第一次上手的玩家可发现——
 * 模组的主要交互都藏在 U / I / 右键上，没有提示很容易不知道该按什么。</p>
 *
 * <p>没有任何平面被选中时完全不绘制，所以正常游玩时不会占屏幕。</p>
 */
public final class ProjectorHud {

    /** 控件拾取结果缓存键（按游戏刻缓存，避免每帧做射线检测）。 */
    private static top.hmjmfabc.projector.client.ClientInputHandler.WidgetPick cachedPick;
    private static long cachedPickTick = -1;

    private ProjectorHud() {
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null) return;
        if (mc.options.hideGui) return;

        Plane plane = SelectionState.plane();
        if (plane == null) return;

        GuiGraphics gfx = event.getGuiGraphics();
        int x = 6;
        int y = 6;
        // 常驻版本标识：一眼确认加载的是哪个构建
        gfx.drawString(mc.font, "Projector " + top.hmjmfabc.projector.Projector.BUILD_TAG,
                x, y, 0xFF7FD4FF, true);
        y += 11;

        // 第一行始终显示构建标识 + 图集状态：用来确认「跑的是哪一版」「图集纹理是否就绪」
        String build = top.hmjmfabc.projector.Projector.BUILD_TAG;
        boolean atlasOk = top.hmjmfabc.projector.client.font.FontManager.atlasPages() > 0;
        gfx.drawString(mc.font, "Projector " + build + (atlasOk ? "" : "  [图集未就绪]"),
                x, y, atlasOk ? 0xFF66DD77 : 0xFFFF5555, true);
        y += 11;

        String title = "\u25c8 \u5e73\u9762\uff1a" + plane.displayName();
        gfx.drawString(mc.font, title, x, y, 0xFF7FD4FF, true);
        y += 11;

        String info = "\u65b9\u5757 " + plane.blockCount() + "  \u63a7\u4ef6 " + plane.widgets.size()
                + (plane.isIncomplete() ? "  \u4e0d\u5b8c\u6574\u9762" : "");
        gfx.drawString(mc.font, info, x, y, 0xFFB0B0C8, true);
        y += 11;

        String hint = "[U] \u6253\u5f00\u5bf9\u8bdd\u6846   [I] \u53d6\u6d88\u5708\u9009";
        gfx.drawString(mc.font, hint, x, y, 0xFF9090A8, true);
        y += 11;

        // 控件拾取是射线检测，没必要每帧做：约 5 Hz 足够跟上准心移动，开销可以忽略
        long tick = mc.level.getGameTime();
        if (cachedPickTick != tick) {
            cachedPick = top.hmjmfabc.projector.client.ClientInputHandler.pickWidget(mc, plane);
            cachedPickTick = tick;
        }
        if (cachedPick != null) {
            gfx.drawString(mc.font, "\u53f3\u952e\uff1a\u7f16\u8f91\u300c" + cachedPick.widget().label() + "\u300d",
                    x, y, 0xFFFFD54A, true);
        }
    }
}

package top.hmjmfabc.projector.fabric.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.hmjmfabc.projector.compat.neo.common.NeoForge;
import top.hmjmfabc.projector.compat.neo.client.event.ScreenEvent;

/**
 * 【移植层】{@code ScreenEvent.Opening} 的触发点（{@code Minecraft.setScreen}）。
 *
 * <p>用途只有一个：<b>Esc 取消圈选</b>。原模组只在"已选中平面 / 正在等待圈选结果"时
 * 拦下 {@code PauseScreen}，其它情况一律放行 —— 这条判断留在 {@code ClientInputHandler}
 * 里（本类只负责递事件，不含任何业务判断）。</p>
 */
@Mixin(Minecraft.class)
public abstract class MinecraftSetScreenMixin {

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void projector$setScreen(Screen screen, CallbackInfo ci) {
        // 关界面（null）不触发：原版 Opening 事件只在真的"打开某个界面"时有用
        if (screen == null) return;
        ScreenEvent.Opening event = new ScreenEvent.Opening(screen);
        NeoForge.EVENT_BUS.post(event);
        if (event.isCanceled()) {
            ci.cancel();
        }
    }
}

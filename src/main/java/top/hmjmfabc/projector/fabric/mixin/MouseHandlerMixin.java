package top.hmjmfabc.projector.fabric.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.hmjmfabc.projector.compat.neo.common.NeoForge;
import top.hmjmfabc.projector.compat.neo.client.event.InputEvent;

/**
 * 【移植层】{@code InputEvent.MouseButton.Pre} 与 {@code InputEvent.MouseScrollingEvent} 的触发点。
 *
 * <p>两个都发生在原版处理<b>之前</b>：</p>
 * <ul>
 *   <li>{@code onPress}：本模组只"记一笔"最近按下的键，从不取消；</li>
 *   <li>{@code onScroll}：滚轮转给网页之后<b>必须取消</b>，否则会顺带把快捷栏切一格。</li>
 * </ul>
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

    @Inject(method = "onPress", at = @At("HEAD"), cancellable = true)
    private void projector$onPress(long window, int button, int action, int modifiers,
                                   CallbackInfo ci) {
        InputEvent.MouseButton.Pre event = new InputEvent.MouseButton.Pre(button, action);
        NeoForge.EVENT_BUS.post(event);
        if (event.isCanceled()) {
            ci.cancel();
        }
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void projector$onScroll(long window, double xOffset, double yOffset, CallbackInfo ci) {
        InputEvent.MouseScrollingEvent event = new InputEvent.MouseScrollingEvent(xOffset, yOffset);
        NeoForge.EVENT_BUS.post(event);
        if (event.isCanceled()) {
            ci.cancel();
        }
    }
}

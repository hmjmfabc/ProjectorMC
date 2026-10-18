package top.hmjmfabc.projector.fabric.mixin;

import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.hmjmfabc.projector.compat.neo.common.NeoForge;
import top.hmjmfabc.projector.compat.neo.client.event.InputEvent;

/**
 * 【移植层】{@code InputEvent.Key} 的触发点。
 *
 * <p>NeoForge 在 {@code KeyboardHandler.keyPress} 里、原版处理<b>之前</b>分发这个事件
 * （可取消）。这里在 HEAD 注入，语义一致。</p>
 *
 * <p>本模组不取消按键（只做"转发给网页"），但保留可取消能力以免将来漏掉。</p>
 */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void projector$keyPress(long window, int key, int scanCode, int action, int modifiers,
                                    CallbackInfo ci) {
        InputEvent.Key event = new InputEvent.Key(key, scanCode, action, modifiers);
        NeoForge.EVENT_BUS.post(event);
        if (event.isCanceled()) {
            ci.cancel();
        }
    }
}

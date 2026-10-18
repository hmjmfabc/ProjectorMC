package top.hmjmfabc.projector.fabric.mixin;

import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.hmjmfabc.projector.compat.neo.common.NeoForge;
import top.hmjmfabc.projector.compat.neo.client.event.ClientChatEvent;

/**
 * 【移植层】{@code ClientChatEvent} 的触发点（{@code ChatScreen.handleChatInput}）。
 *
 * <p>玩家在聊天框按回车、消息<b>还没发出去 / 还没进历史</b>时触发，可取消。
 * 本模组用它把"刚打完的那段话"送进网页控件的输入框（I 键输入法主路）：
 * 取消 = 这条发到网页、不发到公屏。</p>
 */
@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin {

    @Inject(method = "handleChatInput", at = @At("HEAD"), cancellable = true)
    private void projector$handleChatInput(String message, boolean addToRecent, CallbackInfo ci) {
        ClientChatEvent event = new ClientChatEvent(message);
        NeoForge.EVENT_BUS.post(event);
        if (event.isCanceled()) {
            ci.cancel();
        }
    }
}

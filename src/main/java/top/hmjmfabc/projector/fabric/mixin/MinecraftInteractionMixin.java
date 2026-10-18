package top.hmjmfabc.projector.fabric.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.hmjmfabc.projector.compat.neo.common.NeoForge;
import top.hmjmfabc.projector.compat.neo.client.event.InputEvent;

/**
 * 【移植层】{@code InputEvent.InteractionKeyMappingTriggered} 的触发点。
 *
 * <p>NeoForge 在 {@code Minecraft.startAttack()} 与 {@code startUseItem()} 里分发它：
 * 世界内"点视频播放键 / 点棋盘 / 点网页"全部走这条事件。这里在 HEAD 注入，语义一致：</p>
 * <ul>
 *   <li>{@code startAttack}：原版对旁观者直接 return，NeoForge 也就不会 fire ⇒
 *       这里同样先判旁观者，保证"旁观者点网页"的行为与原版一致；</li>
 *   <li>取消 = 这次攻击/使用整体不发生（原版那一步被跳过）。</li>
 * </ul>
 *
 * <p>⚠ {@code isPickBlock}（中键选取）<b>没有接</b>：读遍调用链后确认它的值只进日志，
 * 不改变任何分支（{@code WebInput.press(...)} 的第 4 个参数），所以不注入也不影响功能；
 * 写在这里是为了让下一个人知道"这里少一根线，是故意的"。</p>
 */
@Mixin(Minecraft.class)
public abstract class MinecraftInteractionMixin {

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void projector$startAttack(CallbackInfoReturnable<Boolean> cir) {
        Minecraft mc = (Minecraft) (Object) this;
        if (mc.player != null && mc.player.isSpectator()) {
            // 原版在这里就 return 了，NeoForge 也就不会分发事件
            return;
        }
        InputEvent.InteractionKeyMappingTriggered event =
                new InputEvent.InteractionKeyMappingTriggered(true, false, false);
        NeoForge.EVENT_BUS.post(event);
        if (event.isCanceled()) {
            cir.setReturnValue(false);   // 与原版 "被取消 ⇒ 不攻击" 一致
        }
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void projector$startUseItem(CallbackInfo ci) {
        InputEvent.InteractionKeyMappingTriggered event =
                new InputEvent.InteractionKeyMappingTriggered(false, true, false);
        NeoForge.EVENT_BUS.post(event);
        if (event.isCanceled()) {
            ci.cancel();
        }
    }
}

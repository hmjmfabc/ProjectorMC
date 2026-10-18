package top.hmjmfabc.projector.fabric.mixin;

import net.minecraft.CrashReport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.hmjmfabc.projector.compat.fml.CrashReportCallables;

/**
 * 【移植层】崩溃报告的额外段落（替身 {@code CrashReportCallables} 的出口）。
 *
 * <p>本模组用它放"缺件嘲讽"：只有真的缺了兼容模组（安卓 + 装了 WaterMedia/MCEF 却没装
 * 对应的安卓兼容层）时才出现，正常崩溃的报告一个字都不会被污染。</p>
 */
@Mixin(CrashReport.class)
public abstract class CrashReportMixin {

    @Inject(method = "getDetails(Ljava/lang/StringBuilder;)V", at = @At("TAIL"))
    private void projector$appendExtraDetails(StringBuilder builder, CallbackInfo ci) {
        if (builder == null) return;
        for (String section : CrashReportCallables.activeSections()) {
            builder.append('\n').append(section).append('\n');
        }
    }
}

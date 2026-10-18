package top.hmjmfabc.projector.compat.fml;

import java.util.function.Supplier;

/**
 * 【Fabric 移植层】{@code top.hmjmfabc.projector.compat.fml.ISystemReportExtender} 的替身。
 *
 * <p>崩溃报告里额外加的一段（本模组用它放"缺件嘲讽"）。</p>
 */
public interface ISystemReportExtender extends Supplier<String> {

    /** 这一段的小标题。 */
    String getLabel();

    /** 什么时候才出现（false = 这一段不写进崩溃报告）。 */
    default boolean isActive() {
        return true;
    }
}

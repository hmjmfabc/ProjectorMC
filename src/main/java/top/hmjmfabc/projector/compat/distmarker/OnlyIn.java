package top.hmjmfabc.projector.compat.distmarker;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 【Fabric 移植层】{@code top.hmjmfabc.projector.compat.distmarker.OnlyIn} 的替身。
 *
 * <p>纯标记：Fabric 侧不需要它（方法可见性由调用方自己保证）。保留只是为了让上游源码
 * 一字不改地编译通过。</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.CONSTRUCTOR})
public @interface OnlyIn {
    Dist value();
}

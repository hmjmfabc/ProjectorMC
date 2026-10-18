package top.hmjmfabc.projector.compat.event;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 【Fabric 移植层】{@code top.hmjmfabc.projector.compat.event.SubscribeEvent} 的替身。
 *
 * <p>本模组原先挂在 NeoForge 的事件总线上。移植到 Fabric 后，由
 * {@link EventBus} 扫描带本注解的方法（必须是<b>恰好一个参数</b>），
 * 在 {@link IEventBus#post} 时按参数类型分发 —— 语义与原版一致。</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SubscribeEvent {
}

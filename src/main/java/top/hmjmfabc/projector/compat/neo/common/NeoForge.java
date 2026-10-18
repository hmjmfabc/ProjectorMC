package top.hmjmfabc.projector.compat.neo.common;

import top.hmjmfabc.projector.compat.event.EventBus;
import top.hmjmfabc.projector.compat.event.IEventBus;

/**
 * 【Fabric 移植层】{@code top.hmjmfabc.projector.compat.neo.common.NeoForge} 的替身。
 *
 * <p>{@link #EVENT_BUS} 是"游戏事件总线"（登录/登出/每刻/渲染/输入……）。
 * Fabric 侧没有总线，由 {@code top.hmjmfabc.projector.fabric.FabricEvents} 把
 * Fabric API 的回调（以及少数 Mixin 钩子）转成总线上的事件。</p>
 */
public final class NeoForge {

    /** 游戏事件总线（对应 NeoForge 的 {@code NeoForge.EVENT_BUS}）。 */
    public static final IEventBus EVENT_BUS = new EventBus("game");

    private NeoForge() {
    }
}

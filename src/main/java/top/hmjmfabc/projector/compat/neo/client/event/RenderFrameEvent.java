package top.hmjmfabc.projector.compat.neo.client.event;

/**
 * 【Fabric 移植层】{@code top.hmjmfabc.projector.compat.neo.client.event.RenderFrameEvent} 的替身。
 *
 * <p>本模组用 {@link Pre}：<b>每个渲染帧、渲染之前、客户端线程上</b>触发一次，
 * 用来把"准心正对着网页控件"的悬停转发给页面（世界里没有单独的鼠标移动钩子）。
 * Fabric 侧对应 {@code WorldRenderEvents.START}。</p>
 */
public final class RenderFrameEvent {

    private RenderFrameEvent() {
    }

    /** 一帧的渲染即将开始。 */
    public static final class Pre {
        public Pre() {
        }
    }
}

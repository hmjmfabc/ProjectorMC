package top.hmjmfabc.projector.compat.neo.client.event;

/**
 * 【Fabric 移植层】{@code top.hmjmfabc.projector.compat.neo.client.event.ClientTickEvent} 的替身。
 *
 * <p>本模组只监听 {@link Post}（每刻结束），Fabric 侧对应
 * {@code ClientTickEvents.END_CLIENT_TICK}。</p>
 */
public final class ClientTickEvent {

    private ClientTickEvent() {
    }

    /** 客户端每刻的结束阶段。 */
    public static final class Post {
        public Post() {
        }
    }
}

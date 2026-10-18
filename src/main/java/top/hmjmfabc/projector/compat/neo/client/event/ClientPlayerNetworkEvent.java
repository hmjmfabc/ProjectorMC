package top.hmjmfabc.projector.compat.neo.client.event;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;

/**
 * 【Fabric 移植层】{@code top.hmjmfabc.projector.compat.neo.client.event.ClientPlayerNetworkEvent} 的替身。
 *
 * <p>进世界 / 出世界两个时机：本模组要在这里清缓存、停音乐、释放视频与网页会话、
 * 补 {@code .nomedia}、做后台缓存清理。Fabric 侧对应
 * {@code ClientPlayConnectionEvents.JOIN / DISCONNECT}。</p>
 */
public class ClientPlayerNetworkEvent {

    private final ClientPacketListener listener;

    private ClientPlayerNetworkEvent(ClientPacketListener listener) {
        this.listener = listener;
    }

    /** 原始连接（Fabric 侧可能为 null，本模组没有用到）。 */
    public Connection getConnection() {
        return listener == null ? null : listener.getConnection();
    }

    /** 刚进入世界。 */
    public static final class LoggingIn extends ClientPlayerNetworkEvent {
        public LoggingIn(ClientPacketListener listener) {
            super(listener);
        }
    }

    /** 即将离开世界。 */
    public static final class LoggingOut extends ClientPlayerNetworkEvent {
        public LoggingOut(ClientPacketListener listener) {
            super(listener);
        }
    }
}

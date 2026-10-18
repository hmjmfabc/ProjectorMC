package top.hmjmfabc.projector.compat.neo.event.tick;

import net.minecraft.server.MinecraftServer;

/**
 * 【Fabric 移植层】{@code top.hmjmfabc.projector.compat.neo.event.tick.ServerTickEvent} 的替身。
 *
 * <p>本模组只监听 {@link Post}（服务端每刻结束之后），Fabric 侧对应
 * {@code ServerTickEvents.END_SERVER_TICK}。</p>
 */
public class ServerTickEvent {

    private final MinecraftServer server;

    private ServerTickEvent(MinecraftServer server) {
        this.server = server;
    }

    public MinecraftServer getServer() {
        return server;
    }

    /** 每刻的结束阶段。 */
    public static final class Post extends ServerTickEvent {
        public Post(MinecraftServer server) {
            super(server);
        }
    }
}

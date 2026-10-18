package top.hmjmfabc.projector.compat.neo.event.server;

import net.minecraft.server.MinecraftServer;

/** 【Fabric 移植层】{@code top.hmjmfabc.projector.compat.neo.event.server.ServerStoppingEvent} 的替身。 */
public final class ServerStoppingEvent {

    private final MinecraftServer server;

    public ServerStoppingEvent(MinecraftServer server) {
        this.server = server;
    }

    public MinecraftServer getServer() {
        return server;
    }
}

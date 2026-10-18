package top.hmjmfabc.projector.compat.neo.event.server;

import net.minecraft.server.MinecraftServer;

/** 【Fabric 移植层】{@code top.hmjmfabc.projector.compat.neo.event.server.ServerStartedEvent} 的替身。 */
public final class ServerStartedEvent {

    private final MinecraftServer server;

    public ServerStartedEvent(MinecraftServer server) {
        this.server = server;
    }

    public MinecraftServer getServer() {
        return server;
    }
}

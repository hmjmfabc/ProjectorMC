package top.hmjmfabc.projector.fabric;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import top.hmjmfabc.projector.compat.neo.network.handling.IPayloadContext;

/**
 * 服务端侧的 {@link IPayloadContext} 实现（来自 {@code ServerPlayNetworking.Context}）。
 *
 * <p>Fabric 的 payload 处理器在网络线程上被调用，所以 {@code enqueueWork} 必须真的切线程；
 * 已经在服务端主线程时就地执行（避免白白延后一个 tick，改变原模组的时序）。</p>
 */
public final class ServerPayloadContext implements IPayloadContext {

    private final ServerPlayNetworking.Context context;

    public ServerPayloadContext(ServerPlayNetworking.Context context) {
        this.context = context;
    }

    @Override
    public Player player() {
        return context.player();
    }

    @Override
    public void enqueueWork(Runnable runnable) {
        if (runnable == null) return;
        MinecraftServer server = context.server();
        if (server == null) {
            runnable.run();
            return;
        }
        if (server.isSameThread()) {
            runnable.run();
        } else {
            server.execute(runnable);
        }
    }
}

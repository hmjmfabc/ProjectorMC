package top.hmjmfabc.projector.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import top.hmjmfabc.projector.compat.neo.network.handling.IPayloadContext;

/**
 * 客户端侧的 {@link IPayloadContext} 实现。
 *
 * <p>⚠ <b>客户端专用类</b>：只在 {@code ProjectorFabricClient} 里被触碰，
 * 专用服务端永远不会加载它（dist 隔离，见 {@code FabricNetworking} 的说明）。</p>
 */
public final class ClientPayloadContext implements IPayloadContext {

    @Override
    public Player player() {
        return Minecraft.getInstance().player;
    }

    @Override
    public void enqueueWork(Runnable runnable) {
        if (runnable == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            runnable.run();
            return;
        }
        if (mc.isSameThread()) {
            runnable.run();
        } else {
            mc.execute(runnable);
        }
    }
}

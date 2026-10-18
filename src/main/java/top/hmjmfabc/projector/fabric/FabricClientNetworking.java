package top.hmjmfabc.projector.fabric;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import top.hmjmfabc.projector.Projector;
import top.hmjmfabc.projector.compat.neo.network.handling.IPayloadHandler;
import top.hmjmfabc.projector.compat.neo.network.registration.PayloadRegistrar;

/**
 * 客户端方向的网络接收器登记（<b>客户端专用类</b>）。
 *
 * <p>为什么单独成类：它引用 {@code ClientPlayNetworking} 与 {@link ClientPayloadContext}
 * （后者又引用 {@code Minecraft}）。如果写在公共类 {@link FabricNetworking} 里，
 * 专用服务端加载那一份公共 bytecode 时就可能解析到客户端类 —— 本项目踩过两次同类坑
 * （27.1-rc-73 专用服务端起不来、dist 隔离回归），所以这里按"谁需要谁持有"拆开。</p>
 */
public final class FabricClientNetworking {

    private FabricClientNetworking() {
    }

    /** 只允许在物理客户端调用。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void applyReceivers(PayloadRegistrar registrar) {
        for (PayloadRegistrar.Registration<?> reg : registrar.toClient()) {
            ClientPlayNetworking.registerGlobalReceiver(
                    (CustomPacketPayload.Type) reg.type(),
                    (payload, context) -> ((IPayloadHandler) reg.handler())
                            .handle(payload, new ClientPayloadContext()));
        }
        Projector.LOGGER.info("[Projector] Fabric 客户端接收器登记完成：{} 个", registrar.toClient().size());
    }
}

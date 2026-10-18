package top.hmjmfabc.projector.compat.neo.network.handling;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 【Fabric 移植层】{@code top.hmjmfabc.projector.compat.neo.network.handling.IPayloadHandler} 的替身。
 */
@FunctionalInterface
public interface IPayloadHandler<T extends CustomPacketPayload> {

    void handle(T payload, IPayloadContext context);
}

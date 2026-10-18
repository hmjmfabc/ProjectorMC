package top.hmjmfabc.projector.fabric;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import top.hmjmfabc.projector.Projector;
import top.hmjmfabc.projector.compat.neo.network.registration.PayloadRegistrar;

/**
 * 把 {@code compat} 层收集到的网络登记落到 Fabric 的注册表上。
 *
 * <h2>口径（与 NeoForge 版严格一致）</h2>
 * <ul>
 *   <li><b>两个方向的类型与编解码器放在公共侧登记</b>：服务端要能编码 S2C、客户端要能解码 S2C，
 *       少一端的登记就会在"服务端发第一条包"时炸；</li>
 *   <li><b>客户端方向的接收器只在物理客户端登记</b>（{@code FabricClientNetworking#applyReceivers}），
 *       这样专用服务端永远不会碰到 {@code client.*} 的处理函数 —— 这正是
 *       {@code ProjectorNetwork} 里那段 dist 隔离要守住的不变量；</li>
 *   <li>C2S 的接收器在公共侧登记（物理客户端上的集成服务端也要收）。</li>
 * </ul>
 */
public final class FabricNetworking {

    private FabricNetworking() {
    }

    /** 公共侧：登记类型/编解码器 + C2S 接收器。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void applyCommon(PayloadRegistrar registrar) {
        for (PayloadRegistrar.Registration<?> reg : registrar.toServer()) {
            PayloadTypeRegistry.playC2S().register((net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type) reg.type(),
                    reg.codec());
            ServerPlayNetworking.registerGlobalReceiver(
                    (net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type) reg.type(),
                    (payload, context) -> ((top.hmjmfabc.projector.compat.neo.network.handling.IPayloadHandler) reg.handler())
                            .handle(payload, new ServerPayloadContext(context)));
        }
        // S2C：**只登记类型与编解码器**（接收器在客户端单独登记）
        for (PayloadRegistrar.Registration<?> reg : registrar.toClient()) {
            PayloadTypeRegistry.playS2C().register((net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type) reg.type(),
                    reg.codec());
        }
        Projector.LOGGER.info("[Projector] Fabric 网络登记完成 (协议 v{})：C2S {} 个 / S2C {} 个",
                registrar.version(), registrar.toServer().size(), registrar.toClient().size());
    }

}

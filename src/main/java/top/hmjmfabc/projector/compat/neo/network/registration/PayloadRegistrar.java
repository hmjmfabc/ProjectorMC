package top.hmjmfabc.projector.compat.neo.network.registration;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import top.hmjmfabc.projector.compat.neo.network.handling.IPayloadHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 【Fabric 移植层】{@code top.hmjmfabc.projector.compat.neo.network.registration.PayloadRegistrar} 的替身。
 *
 * <p>只做"登记"，不真的注册到 Fabric —— 登记收集完之后由
 * {@code top.hmjmfabc.projector.fabric.FabricNetworking} 统一转给
 * {@code PayloadTypeRegistry} 与 {@code Server/ClientPlayNetworking}。
 * 这样 {@code ProjectorNetwork}（含其中的 dist 隔离逻辑）可以<b>一字不改</b>地复用。</p>
 */
public final class PayloadRegistrar {

    /** 一条登记。 */
    public record Registration<T extends CustomPacketPayload>(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            IPayloadHandler<T> handler) {
    }

    private final List<Registration<?>> toServer = new ArrayList<>();
    private final List<Registration<?>> toClient = new ArrayList<>();
    private String version;

    public PayloadRegistrar(String version) {
        this.version = version;
    }

    /** 协议版本（只用于日志；Fabric 的通道协商没有版本号这一层）。 */
    public String version() {
        return version;
    }

    /** 由 {@code RegisterPayloadHandlersEvent.registrar(version)} 写入（只影响日志）。 */
    public void version(String version) {
        this.version = version;
    }

    public <T extends CustomPacketPayload> PayloadRegistrar playToServer(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            IPayloadHandler<T> handler) {
        toServer.add(new Registration<>(type, codec, handler));
        return this;
    }

    public <T extends CustomPacketPayload> PayloadRegistrar playToClient(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            IPayloadHandler<T> handler) {
        toClient.add(new Registration<>(type, codec, handler));
        return this;
    }

    public List<Registration<?>> toServer() {
        return Collections.unmodifiableList(toServer);
    }

    public List<Registration<?>> toClient() {
        return Collections.unmodifiableList(toClient);
    }
}

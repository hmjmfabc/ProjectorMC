package top.hmjmfabc.projector.compat.neo.network.event;

import top.hmjmfabc.projector.compat.neo.network.registration.PayloadRegistrar;

/**
 * 【Fabric 移植层】{@code top.hmjmfabc.projector.compat.neo.network.event.RegisterPayloadHandlersEvent} 的替身。
 *
 * <p>由 {@code ProjectorFabric} 在初始化时 post 一次；{@code ProjectorNetwork.register} 收到的
 * 就是它，登记结果随后交给 Fabric 的网络注册表。</p>
 */
public final class RegisterPayloadHandlersEvent {

    private final PayloadRegistrar registrar;

    public RegisterPayloadHandlersEvent(String version) {
        this.registrar = new PayloadRegistrar(version);
    }

    /** 与 NeoForge 同名同义：声明协议版本。 */
    public PayloadRegistrar registrar(String version) {
        registrar.version(version);
        return registrar;
    }

    /** 本次登记出来的东西（供 Fabric 侧取用）。 */
    public PayloadRegistrar registrar() {
        return registrar;
    }
}

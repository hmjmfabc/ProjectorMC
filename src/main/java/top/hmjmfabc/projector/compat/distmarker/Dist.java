package top.hmjmfabc.projector.compat.distmarker;

/**
 * 【Fabric 移植层】{@code top.hmjmfabc.projector.compat.distmarker.Dist} 的替身。
 */
public enum Dist {
    CLIENT,
    DEDICATED_SERVER;

    /** 是否物理客户端（= NeoForge 的 {@code Dist.isClient()}）。 */
    public boolean isClient() {
        return this == CLIENT;
    }

    /** 是否专用服务端（= NeoForge 的 {@code Dist.isDedicatedServer()}）。 */
    public boolean isDedicatedServer() {
        return this == DEDICATED_SERVER;
    }
}

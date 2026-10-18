package top.hmjmfabc.projector.compat.fml;

import top.hmjmfabc.projector.compat.neo.common.ModConfigSpec;

/**
 * 【Fabric 移植层】{@code top.hmjmfabc.projector.compat.fml.ModContainer} 的替身。
 *
 * <p>原模组只用它做两件事：读模组版本（打日志）与登记配置。Fabric 没有"容器"概念，
 * 这里给一个最小的实现，版本号从 {@code fabric.mod.json} 的 {@code version} 读。
 */
public final class ModContainer {

    private final String modId;
    private final String version;

    public ModContainer(String modId, String version) {
        this.modId = modId;
        this.version = version;
    }

    public ModInfo getModInfo() {
        return new ModInfo(modId, version);
    }

    /**
     * 登记配置。
     *
     * <p>Fabric 侧没有 FML 的配置系统：{@link ModConfigSpec} 在它自己的
     * {@code configure(...)} 里就已经读过/写过 {@code config/projector-common.toml}。
     * 这里只做一次幂等确认，避免"登记了却没加载"这种静默失败。</p>
     */
    public void registerConfig(top.hmjmfabc.projector.compat.fml.config.ModConfig.Type type,
                               ModConfigSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("[Projector] 配置规格为 null");
        }
        spec.loadIfNeeded();
    }

    /** 模组元信息（只用到版本号）。 */
    public record ModInfo(String modId, String version) {
        public String getVersion() {
            return version;
        }

        public String getModId() {
            return modId;
        }
    }
}

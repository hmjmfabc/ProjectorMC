package top.hmjmfabc.projector.compat.fml.config;

/**
 * 【Fabric 移植层】{@code top.hmjmfabc.projector.compat.fml.config.ModConfig} 的替身。
 *
 * <p>只用得到 {@link Type#COMMON}：本模组的配置是"每台机器一份"的通用配置，
 * 落在 {@code config/projector-common.toml}（与 NeoForge 版的 COMMON 配置同一个路径）。</p>
 */
public final class ModConfig {

    private ModConfig() {
    }

    /** 配置类型（Fabric 侧只用来选文件名，行为上等价）。 */
    public enum Type {
        /** 通用：客户端与专用服务端各读各的 {@code config/projector-common.toml}。 */
        COMMON("projector-common.toml"),
        /** 客户端专用（本模组没用到）。 */
        CLIENT("projector-client.toml"),
        /** 服务端专用（本模组没用到）。 */
        SERVER("projector-server.toml");

        private final String fileName;

        Type(String fileName) {
            this.fileName = fileName;
        }

        public String fileName() {
            return fileName;
        }
    }
}

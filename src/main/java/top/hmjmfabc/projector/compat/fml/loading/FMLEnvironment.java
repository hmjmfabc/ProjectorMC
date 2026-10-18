package top.hmjmfabc.projector.compat.fml.loading;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import top.hmjmfabc.projector.compat.distmarker.Dist;

/**
 * 【Fabric 移植层】{@code top.hmjmfabc.projector.compat.fml.loading.FMLEnvironment} 的替身。
 *
 * <p>{@link #dist} 是<b>整个网络层 dist 隔离的判据</b>：专用服务端上它必须是
 * {@link Dist#DEDICATED_SERVER}，否则 {@code ProjectorNetwork} 会去碰客户端处理函数
 * ⇒ 专用服务端在注册阶段直接起不来（27.1-rc-73 的实际事故，见 AGENTS.md §2.3）。</p>
 */
public final class FMLEnvironment {

    /** 本端。取值为 Fabric Loader 的真实环境。 */
    public static final Dist dist = FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT
            ? Dist.CLIENT
            : Dist.DEDICATED_SERVER;

    private FMLEnvironment() {
    }
}

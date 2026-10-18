package top.hmjmfabc.projector.compat.betterlogin.client.init;

/**
 * 【27.2.2】原版 BetterLogin 用它把配置页挂到 cloth-config 上。
 * <p>投影仪**不依赖 cloth-config**（少一个必须的前置、也少一份负载），
 * 所以这里不再注册配置页：音质等级由投影仪自己的配置
 * {@code music.neteaseLevel} 管，登录页与命令都能读写它。</p>
 */
public final class CompatRegistry {
    private CompatRegistry() {
    }
}

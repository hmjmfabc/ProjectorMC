package top.hmjmfabc.projector.compat.betterlogin;

import top.hmjmfabc.projector.compat.betterlogin.api.NeteaseApi;
import top.hmjmfabc.projector.compat.betterlogin.compat.netmusic.NeteaseVIPResolver;
import top.hmjmfabc.projector.compat.netmusic.api.resolver.MusicPlayResolverManager;
import org.slf4j.Logger;

/**
 * 【27.2.2】Net Music: Better Login（MIT）——**已并入投影仪**。
 *
 * <p>原版是独立模组（modid {@code netmusicbetterlogin}），这里改成投影仪的一部分：</p>
 * <ul>
 *   <li>不再是 {@code @Mod} 主类 —— 由投影仪在客户端 setup / 公共 setup 里调用
 *       {@link #init()} 完成初始化，少一个要玩家自己装的模组；</li>
 *   <li>配置并入投影仪（Cookie ⇒ {@code music.neteaseCookie}、
 *       音质等级 ⇒ {@code music.neteaseLevel}），登录一次立刻对音乐模块生效；</li>
 *   <li>去掉了对 cloth-config 的依赖（原来只是拿它画配置页）。</li>
 * </ul>
 *
 * <p>许可证：MIT（见 NOTICE）。包名已改为 {@code top.hmjmfabc.projector.compat.betterlogin}
 * 以免与真品冲突。</p>
 */
public final class NetMusicBetterLogin {

    /** 保留原 modid 常量（语言键、纹理路径仍以它为准）。 */
    public static final String MODID = "netmusicbetterlogin";

    public static final Logger LOGGER = org.slf4j.LoggerFactory.getLogger("Projector/BetterLogin");

    public static NeteaseApi NETEASE_API;

    private static boolean initialized;

    private NetMusicBetterLogin() {
    }

    /** 由投影仪调用一次（幂等）。要在客户端与公共端都安全。 */
    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        NETEASE_API = new NeteaseApi();
        // 把「VIP 直链解析器」注册进镜像的解析器管理器：
        // 音乐模块解析网易云地址前会先问它（拿到高码率），没人接才走内置直链。
        try {
            MusicPlayResolverManager.registerResolver(new NeteaseVIPResolver());
        } catch (Throwable t) {
            LOGGER.warn("注册 VIP 解析器失败（登录仍可用）：{}", String.valueOf(t));
        }
        LOGGER.info("网易云登录（Better Login，MIT）已并入投影仪：/netmusicbetterlogin login 或界面登录");
    }
}

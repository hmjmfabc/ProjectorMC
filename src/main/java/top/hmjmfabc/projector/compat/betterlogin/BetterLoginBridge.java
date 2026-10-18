package top.hmjmfabc.projector.compat.betterlogin;

import top.hmjmfabc.projector.compat.neo.event.RegisterCommandsEvent;
import top.hmjmfabc.projector.compat.betterlogin.command.ServerLoginCommand;
import top.hmjmfabc.projector.compat.betterlogin.config.GeneralConfig;

/**
 * 【27.2.2】把并入投影仪的 Better Login 接到 NeoForge 事件总线上的**唯一入口**。
 *
 * <p>原版它是独立模组，靠 {@code @EventBusSubscriber} 自己挂；现在它是投影仪的一部分，
 * 由投影仪在初始化时调 {@link #init()}。**两条命令分两侧注册**：</p>
 * <ul>
 *   <li>{@code /nmbl server cookie <cookie> | clear | level set <等级>}（**服务端命令**）——
 *       由本类在**公共侧**注册（见 {@link Projector#Projector} 构造里的 EVENT_BUS），
 *       所以专用服务端上也有；</li>
 *   <li>{@code /nmbl login}（客户端命令，打开登录界面）—— 由**客户端专用**的
 *       {@code compat.betterlogin.client.ClientCommandBridge} 注册。</li>
 * </ul>
 *
 * <p>⚠ <b>为什么客户端命令要拆出去</b>：这个类是公共代码（构造期在两端都会被加载），
 * 而 {@code RegisterClientCommandsEvent} 是**客户端专属**事件类型。以前两件事都写在这里，
 * 公共类就带上了客户端类型的引用，且服务端命令的注册挂在客户端入口里 ——
 * 结果是**专用服务端上根本没有 {@code /nmbl server}**。这是本项目踩过两次的
 * 「dist 隔离」坑型（AGENTS §5.5 第 69 条），所以按「最小改动」把注册点各自归位。</p>
 */
public final class BetterLoginBridge {

    private BetterLoginBridge() {
    }

    /** 客户端与公共端各调一次（幂等）。 */
    public static void init() {
        NetMusicBetterLogin.init();
        // 配置里可能已经有 Cookie（玩家手填过）⇒ 开局就把它交给 API
        NetMusicBetterLogin.NETEASE_API.setCookie(GeneralConfig.getCookie());
    }

    /** 服务端命令（只有 OP 能用，见命令实现里的权限判定）。 */
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(ServerLoginCommand.get());
        NetMusicBetterLogin.LOGGER.info("已注册命令 /nmbl server …（网易云 Cookie 与音质等级，仅 OP）");
    }

}

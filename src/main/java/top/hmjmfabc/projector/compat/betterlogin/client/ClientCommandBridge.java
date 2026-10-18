package top.hmjmfabc.projector.compat.betterlogin.client;

import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import top.hmjmfabc.projector.compat.betterlogin.client.command.ClientLoginCommand;

/**
 * 【27.2.2】**客户端专用**的命令注册入口（只有 {@code /nmbl login}）。
 *
 * <p>单独成类的原因见 {@code BetterLoginBridge} 的类注释：那边是公共代码，
 * 一旦 import 客户端专属的事件类型，专用服务端就有加载风险；而把服务端命令的注册
 * 留在客户端入口里，又会变成「专用服务端上没有那条命令」。两边都归位才是最省事的修法。</p>
 */
public final class ClientCommandBridge {

    private ClientCommandBridge() {
    }

    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(ClientLoginCommand.get());
    }
}

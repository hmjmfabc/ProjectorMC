package top.hmjmfabc.projector.compat.betterlogin.client;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import top.hmjmfabc.projector.compat.betterlogin.NetMusicBetterLogin;
import top.hmjmfabc.projector.compat.betterlogin.client.gui.LoginScreen;
import top.hmjmfabc.projector.compat.neo.client.event.RegisterClientCommandsEvent;

/**
 * 【27.2.2】**客户端专用**的命令注册入口（只有 {@code /nmbl login}）。
 *
 * <p>单独成类的原因见 {@code BetterLoginBridge} 的类注释：那边是公共代码，
 * 一旦 import 客户端专属的事件类型，专用服务端就有加载风险；而把服务端命令的注册
 * 留在客户端入口里，又会变成「专用服务端上没有那条命令」。两边都归位才是最省事的修法。</p>
 *
 * <p>【Fabric 移植】客户端命令的"源"类型不同：NeoForge 沿用 {@code CommandSourceStack}，
 * Fabric 用 {@code FabricClientCommandSource}，而 {@code Commands.literal(...)} 只能生成前者
 * ⇒ 这里改用 {@code ClientCommandManager.literal(...)} 直接建树。原来的
 * {@code ClientLoginCommand} 因此没有人再调用，已随移植删除（留着就是死代码）。
 * 命令行为一字未变：执行后打开网易云登录界面。</p>
 */
public final class ClientCommandBridge {

    private ClientCommandBridge() {
    }

    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        CommandDispatcher<FabricClientCommandSource> dispatcher = event.getDispatcher();
        dispatcher.register(ClientCommandManager.literal("nmbl")
                .then(ClientCommandManager.literal("login").executes(context -> {
                    Minecraft.getInstance().setScreen(new LoginScreen());
                    return Command.SINGLE_SUCCESS;
                })));
        NetMusicBetterLogin.LOGGER.info("已注册客户端命令 /nmbl login（打开网易云登录界面）");
    }
}

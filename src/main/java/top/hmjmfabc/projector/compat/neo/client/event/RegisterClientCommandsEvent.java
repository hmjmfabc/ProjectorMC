package top.hmjmfabc.projector.compat.neo.client.event;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

/**
 * 【Fabric 移植层】{@code top.hmjmfabc.projector.compat.neo.client.event.RegisterClientCommandsEvent} 的替身。
 *
 * <p><b>客户端命令</b>的注册点（本模组的 {@code /nmbl login}）。Fabric 侧对应
 * {@code ClientCommandRegistrationCallback.EVENT}。</p>
 */
public final class RegisterClientCommandsEvent {

    private final CommandDispatcher<FabricClientCommandSource> dispatcher;

    public RegisterClientCommandsEvent(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        this.dispatcher = dispatcher;
    }

    public CommandDispatcher<FabricClientCommandSource> getDispatcher() {
        return dispatcher;
    }
}

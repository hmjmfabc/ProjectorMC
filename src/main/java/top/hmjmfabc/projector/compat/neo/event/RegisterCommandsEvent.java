package top.hmjmfabc.projector.compat.neo.event;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;

/**
 * 【Fabric 移植层】{@code top.hmjmfabc.projector.compat.neo.event.RegisterCommandsEvent} 的替身。
 *
 * <p><b>服务端命令</b>的注册点（本模组的 {@code /nmbl server …}）。Fabric 侧对应
 * {@code CommandRegistrationCallback.EVENT}。</p>
 */
public final class RegisterCommandsEvent {

    private final CommandDispatcher<CommandSourceStack> dispatcher;

    public RegisterCommandsEvent(CommandDispatcher<CommandSourceStack> dispatcher) {
        this.dispatcher = dispatcher;
    }

    public CommandDispatcher<CommandSourceStack> getDispatcher() {
        return dispatcher;
    }
}

package top.hmjmfabc.projector.compat.betterlogin.client.command;

import top.hmjmfabc.projector.compat.betterlogin.client.gui.LoginScreen;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

public class ClientLoginCommand {
    private static final String ROOT = "nmbl";
    private static final String LOGIN = "login";

    public static LiteralArgumentBuilder<CommandSourceStack> get() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(ROOT);
        LiteralArgumentBuilder<CommandSourceStack> login = Commands.literal(LOGIN);

        root.then(login.executes(context -> {
            Minecraft.getInstance().setScreen(new LoginScreen());
            return Command.SINGLE_SUCCESS;
        }));

        return root;
    }

}

package top.hmjmfabc.projector.compat.betterlogin.command;

import top.hmjmfabc.projector.compat.betterlogin.NetMusicBetterLogin;
import top.hmjmfabc.projector.compat.betterlogin.api.NeteaseMusicLevel;
import top.hmjmfabc.projector.compat.betterlogin.config.GeneralConfig;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.server.command.EnumArgument;

public class ServerLoginCommand {
    private static final String ROOT = "nmbl";
    private static final String SERVER = "server";
    private static final String COOKIE = "cookie";
    private static final String CLEAR = "clear";
    private static final String LEVEL = "level";
    private static final String SET = "set";

    public static LiteralArgumentBuilder<CommandSourceStack> get() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(ROOT);
        LiteralArgumentBuilder<CommandSourceStack> server = Commands.literal(SERVER);
        LiteralArgumentBuilder<CommandSourceStack> cookie = Commands.literal(COOKIE);
        LiteralArgumentBuilder<CommandSourceStack> clear = Commands.literal(CLEAR);
        LiteralArgumentBuilder<CommandSourceStack> level = Commands.literal(LEVEL);
        LiteralArgumentBuilder<CommandSourceStack> set = Commands.literal(SET);
        RequiredArgumentBuilder<CommandSourceStack, NeteaseMusicLevel> levelArgument = Commands.argument(LEVEL, EnumArgument.enumArgument(NeteaseMusicLevel.class));

        server.requires(source -> source.hasPermission(2));

        cookie.then(clear.executes(context -> {
            NetMusicBetterLogin.NETEASE_API.setCookie("");
            GeneralConfig.setCookie("");
            context.getSource().sendSystemMessage(Component.translatable("command.netmusicbetterlogin.cookie.clear.success"));
            return Command.SINGLE_SUCCESS;
        }));

        level.then(set.then(levelArgument.executes(context -> {
            NeteaseMusicLevel levelValue = context.getArgument(LEVEL, NeteaseMusicLevel.class);
            GeneralConfig.setLevel(levelValue);
            context.getSource().sendSystemMessage(Component.translatable("command.netmusicbetterlogin.level.set.success", Component.translatable(levelValue.getTranslationKey())));
            return Command.SINGLE_SUCCESS;
        })));

        level.executes(context -> {
            NeteaseMusicLevel currentLevel = GeneralConfig.getLevel();
            context.getSource().sendSystemMessage(Component.translatable("command.netmusicbetterlogin.level.current", Component.translatable(currentLevel.getTranslationKey())));
            return Command.SINGLE_SUCCESS;
        });

        server.then(cookie);
        server.then(level);
        root.then(server);

        return root;
    }
}

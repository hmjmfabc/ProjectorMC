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
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.SharedSuggestionProvider;

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
        // 【Fabric 移植】原来用 NeoForge 的 EnumArgument（自定义命令参数类型）。Fabric 侧没有等价物，
        // 而自定义参数类型必须注册进 ArgumentTypeInfos 才能随命令树同步到客户端（1.21.1 里那个
        // 注册入口是私有的）—— 为一个 5 个取值的参数去做注册不划算。改用 StringArgumentType：
        // 参数从输入解析成枚举，出错就 sendFailure 说明可选取值，功能与提示都不缺。
        RequiredArgumentBuilder<CommandSourceStack, String> levelArgument =
                Commands.argument(LEVEL, StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                java.util.Arrays.stream(NeteaseMusicLevel.values())
                                        .map(Enum::name).toList(),
                                builder));

        server.requires(source -> source.hasPermission(2));

        cookie.then(clear.executes(context -> {
            NetMusicBetterLogin.NETEASE_API.setCookie("");
            GeneralConfig.setCookie("");
            context.getSource().sendSystemMessage(Component.translatable("command.netmusicbetterlogin.cookie.clear.success"));
            return Command.SINGLE_SUCCESS;
        }));

        level.then(set.then(levelArgument.executes(context -> {
            String raw = StringArgumentType.getString(context, LEVEL);
            NeteaseMusicLevel levelValue;
            try {
                levelValue = NeteaseMusicLevel.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // 不许静默拒绝：把可选值列出来（AGENTS.md §1.8 第 4 条）
                context.getSource().sendFailure(Component.literal("未知音质等级：" + raw + "（可选 "
                        + java.util.Arrays.toString(NeteaseMusicLevel.values()) + "）"));
                return 0;
            }
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

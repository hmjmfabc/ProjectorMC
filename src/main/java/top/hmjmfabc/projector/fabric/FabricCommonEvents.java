package top.hmjmfabc.projector.fabric;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import top.hmjmfabc.projector.compat.neo.common.NeoForge;
import top.hmjmfabc.projector.compat.neo.event.RegisterCommandsEvent;
import top.hmjmfabc.projector.compat.neo.event.entity.player.PlayerEvent;
import top.hmjmfabc.projector.compat.neo.event.level.BlockEvent;
import top.hmjmfabc.projector.compat.neo.event.server.ServerStartedEvent;
import top.hmjmfabc.projector.compat.neo.event.server.ServerStoppingEvent;
import top.hmjmfabc.projector.compat.neo.event.tick.ServerTickEvent;

/**
 * <b>公共侧</b>的事件桥：把 Fabric API 的"服务端发生的事"转成模组总线上的事件。
 *
 * <p>对照表（NeoForge → Fabric）：</p>
 * <ul>
 *   <li>{@code RegisterCommandsEvent} → {@code CommandRegistrationCallback.EVENT}</li>
 *   <li>{@code ServerStartedEvent / ServerStoppingEvent} → {@code ServerLifecycleEvents.*}</li>
 *   <li>{@code ServerTickEvent.Post} → {@code ServerTickEvents.END_SERVER_TICK}</li>
 *   <li>{@code PlayerEvent.PlayerLoggedInEvent / LoggedOut} → {@code ServerPlayConnectionEvents.JOIN / DISCONNECT}</li>
 *   <li>{@code PlayerEvent.PlayerChangedDimensionEvent} → {@code ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD}</li>
 *   <li>{@code BlockEvent.BreakEvent} → {@code PlayerBlockBreakEvents.BEFORE}（返回 false = 取消）</li>
 * </ul>
 *
 * <p>⚠ 本类只碰公共类型：专用服务端加载它绝不会有 dist 问题。</p>
 */
public final class FabricCommonEvents {

    private FabricCommonEvents() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                NeoForge.EVENT_BUS.post(new RegisterCommandsEvent(dispatcher)));

        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                NeoForge.EVENT_BUS.post(new ServerStartedEvent(server)));

        ServerLifecycleEvents.SERVER_STOPPING.register(server ->
                NeoForge.EVENT_BUS.post(new ServerStoppingEvent(server)));

        ServerTickEvents.END_SERVER_TICK.register(server ->
                NeoForge.EVENT_BUS.post(new ServerTickEvent.Post(server)));

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedInEvent(handler.getPlayer())));

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(handler.getPlayer())));

        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) ->
                NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerChangedDimensionEvent(player)));

        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(world, pos, player);
            NeoForge.EVENT_BUS.post(event);
            // 返回 false = 这个方块挖不动（平面保护 / 误挖掘警告）
            return !event.isCanceled();
        });
    }
}

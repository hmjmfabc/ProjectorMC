package top.hmjmfabc.projector.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import top.hmjmfabc.projector.Projector;
import top.hmjmfabc.projector.client.ProjectorClient;
import top.hmjmfabc.projector.compat.fml.event.lifecycle.FMLClientSetupEvent;
import top.hmjmfabc.projector.compat.neo.common.NeoForge;
import top.hmjmfabc.projector.compat.neo.client.event.ClientPlayerNetworkEvent;
import top.hmjmfabc.projector.compat.neo.client.event.ClientTickEvent;
import top.hmjmfabc.projector.compat.neo.client.event.RegisterClientCommandsEvent;
import top.hmjmfabc.projector.compat.neo.client.event.RegisterKeyMappingsEvent;
import top.hmjmfabc.projector.compat.neo.client.event.RenderFrameEvent;
import top.hmjmfabc.projector.compat.neo.client.event.RenderGuiEvent;
import top.hmjmfabc.projector.compat.neo.client.event.RenderLevelStageEvent;
import top.hmjmfabc.projector.compat.neo.network.PacketDistributor;

/**
 * Fabric 客户端入口（对应 NeoForge 版里 {@code @Mod(dist = CLIENT)} 的 {@link ProjectorClient}）。
 *
 * <p>同样只做"接线"：把 Fabric 的客户端回调转成模组总线上的事件，然后构造
 * {@link ProjectorClient}（它的构造函数里登记了渲染 / 输入 / 登录登出 / 音乐 tick 等监听器）。</p>
 *
 * <p>对照表（NeoForge → Fabric）：</p>
 * <ul>
 *   <li>{@code ClientTickEvent.Post} → {@code ClientTickEvents.END_CLIENT_TICK}</li>
 *   <li>{@code ClientPlayerNetworkEvent.LoggingIn/Out} → {@code ClientPlayConnectionEvents.JOIN/DISCONNECT}</li>
 *   <li>{@code RenderLevelStageEvent(AFTER_ENTITIES)} → {@code WorldRenderEvents.AFTER_ENTITIES}</li>
 *   <li>{@code RenderFrameEvent.Pre} → {@code WorldRenderEvents.START}</li>
 *   <li>{@code RenderGuiEvent.Post} → {@code HudRenderCallback}</li>
 *   <li>{@code RegisterKeyMappingsEvent} → {@code KeyBindingHelper.registerKeyBinding}</li>
 *   <li>{@code RegisterClientCommandsEvent} → {@code ClientCommandRegistrationCallback.EVENT}</li>
 *   <li>{@code InputEvent.*} / {@code ScreenEvent.Opening} / {@code ClientChatEvent} → 见 mixin 包</li>
 * </ul>
 */
public final class ProjectorFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // ① 客户端 → 服务端的发包通道（公共类 PacketDistributor 靠它工作，见那里的 dist 说明）
        PacketDistributor.installServerSender(ClientPlayNetworking::send);

        // ② 构造客户端主类（登记渲染 / 输入 / 网络回调等监听器）
        new ProjectorClient(ProjectorFabric.MOD_BUS);

        // ③ 客户端初始化阶段（建目录、装字体、注册动态纹理、迁移旧按键…）
        ProjectorFabric.MOD_BUS.post(new FMLClientSetupEvent());

        // ④ 按键
        RegisterKeyMappingsEvent keyEvent = new RegisterKeyMappingsEvent();
        ProjectorFabric.MOD_BUS.post(keyEvent);
        for (net.minecraft.client.KeyMapping mapping : keyEvent.pending()) {
            KeyBindingHelper.registerKeyBinding(mapping);
        }

        // ⑤ 网络：客户端方向的接收器（只在物理客户端登记）
        FabricClientNetworking.applyReceivers(ProjectorFabric.payloads());

        // ⑥ 客户端事件桥
        ClientTickEvents.END_CLIENT_TICK.register(mc ->
                NeoForge.EVENT_BUS.post(new ClientTickEvent.Post()));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                NeoForge.EVENT_BUS.post(new ClientPlayerNetworkEvent.LoggingIn(handler)));

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                NeoForge.EVENT_BUS.post(new ClientPlayerNetworkEvent.LoggingOut(handler)));

        WorldRenderEvents.AFTER_ENTITIES.register(context ->
                NeoForge.EVENT_BUS.post(new RenderLevelStageEvent(
                        RenderLevelStageEvent.Stage.AFTER_ENTITIES,
                        context.matrixStack(),
                        context.camera(),
                        context.tickCounter())));

        // RenderFrameEvent.Pre：每个渲染帧、渲染之前（世界里转发"准心对着网页"）
        WorldRenderEvents.START.register(context ->
                NeoForge.EVENT_BUS.post(new RenderFrameEvent.Pre()));

        HudRenderCallback.EVENT.register((guiGraphics, deltaTracker) ->
                NeoForge.EVENT_BUS.post(new RenderGuiEvent.Post(guiGraphics)));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                NeoForge.EVENT_BUS.post(new RegisterClientCommandsEvent(dispatcher)));

        Projector.LOGGER.info("[Projector] 客户端入口就绪（版本 {}）", ProjectorFabric.modVersion);
    }
}

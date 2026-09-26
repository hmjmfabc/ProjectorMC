package top.hmjmfabc.projector.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import top.hmjmfabc.projector.Projector;
import top.hmjmfabc.projector.client.ClientPayloadHandlers;

import java.util.List;
import java.util.function.Function;

/**
 * 网络通道注册。
 *
 * <p>只注册 {@code play} 阶段的包。本模组的网络协议是<b>双向必需</b>的：
 * 客户端与服务端都要安装，否则功能不可用（与服务端权威的数据模型一致，
 * 服务端必须能校验权限与内容）。</p>
 *
 * <h2>⚠ 专用服务端（DEDICATED_SERVER）的 dist 隔离 —— 这里踩过一次大坑</h2>
 * <p>本方法在<b>两端都会执行</b>。原先「服务端 → 客户端」的 5 个包直接把
 * {@code ClientNetHandler::onOpenDialog} 这类<b>客户端类的方法引用</b>传给了
 * {@code playToClient}：方法引用是 {@code invokedynamic}，登记时就会去解析
 * {@code ClientNetHandler}，而它引用 {@code PlaneDialogScreen}（继承
 * {@code net.minecraft.client.gui.screens.Screen}）——
 * 于是专用服务端在 {@code NetworkRegistry.setup()} 阶段就抛
 * {@code BootstrapMethodError: Attempted to load class net/minecraft/client/gui/screens/Screen
 * for invalid dist DEDICATED_SERVER}，<b>模组加载直接失败、服务器起不来</b>
 * （实测于 {@code 27.1-rc-73}；该缺陷从最初版本就在，只是以前只测过单人/联机房间）。</p>
 *
 * <p>现在：</p>
 * <ul>
 *   <li><b>包的类型与版本两端都要登记</b>（否则通道协商两边对不上，客户端连不上），
 *       表见 {@link #CLIENTBOUND} —— <b>唯一来源</b>，两端登记的就是这一张表；</li>
 *   <li><b>处理函数按 dist 选择</b>：物理客户端用 {@code client.ClientPayloadHandlers}
 *       里的真实处理函数，专用服务端用一个「不该被调用」的占位函数
 *       （客户端包永远不会到达专用服务端）。</li>
 * </ul>
 *
 * <p>因此{@code top.hmjmfabc.projector.client.*} 只在
 * {@link #registerClientbound} 的客户端分支里被触碰，专用服务端永不加载它们。
 * 这条不变量由 {@code tmp/v17}（{@code T17}）用「禁用 client 类的类加载器」
 * 真实执行注册来守护 —— 它能直接复现上面那个崩溃。</p>
 */
public final class ProjectorNetwork {

    /** 网络协议版本，用于版本不匹配时快速定位问题。 */
    public static final String VERSION = "4";   // rc-83：整段文件改用帧号 -1（以前与第 0 帧混淆）

    private ProjectorNetwork() {
    }

    /**
     * 所有「服务端 → 客户端」的包：类型 + 编解码器。
     *
     * <p><b>唯一来源</b>：两端登记的都是这张表，避免「客户端加了新包、服务端忘了加」
     * 这种只在联机时才炸的漂移。处理函数不在这里（它必须是客户端专用的类）。</p>
     */
    private static final List<Clientbound> CLIENTBOUND = List.of(
            new Clientbound(Payloads.OpenDialog.TYPE, Payloads.OpenDialog.CODEC),
            new Clientbound(Payloads.SyncPlanes.TYPE, Payloads.SyncPlanes.CODEC),
            new Clientbound(Payloads.MediaData.TYPE, Payloads.MediaData.CODEC),
            new Clientbound(Payloads.ServerInfo.TYPE, Payloads.ServerInfo.CODEC),
            new Clientbound(Payloads.FontStored.TYPE, Payloads.FontStored.CODEC),
            // 【rc-77】上传流控：服务端逐片确认（也是「已有同哈希，别再传」的通知通道）
            new Clientbound(Payloads.MediaAck.TYPE, Payloads.MediaAck.CODEC));

    /**
     * 专用服务端上「客户端包」的占位处理函数。
     *
     * <p>专用服务端不可能收到客户端方向的包，所以它永远不该被执行；
     * 真的被执行了说明协议或 dist 判定出了问题，必须留下日志（不许静默）。
     * 注意它是<b>本类</b>的方法引用，不含任何 client 包引用。</p>
     */
    private static final IPayloadHandler<CustomPacketPayload> IGNORED_HANDLER = (payload, context) ->
            Projector.LOGGER.warn("[Projector] 专用服务端收到了客户端包 {}（本不该发生，已忽略）",
                    payload.type().id());

    /** 一个客户端方向的包（类型 + 编解码器）。 */
    private record Clientbound(CustomPacketPayload.Type<?> type,
                               StreamCodec<? super RegistryFriendlyByteBuf, ?> codec) {
        ResourceLocation id() {
            return type.id();
        }
    }

    /** 注册入口（模组总线调用）。 */
    public static void register(RegisterPayloadHandlersEvent event) {
        registerForDist(event, FMLEnvironment.dist.isClient());
    }

    public static void registerForDist(RegisterPayloadHandlersEvent event, boolean client) {
        PayloadRegistrar registrar = event.registrar(VERSION);

        // ---- 客户端 → 服务端：两端都要登记（客户端要发、服务端要收）----
        registrar.playToServer(Payloads.CreatePlane.TYPE, Payloads.CreatePlane.CODEC,
                ServerNetHandler::onCreatePlane);
        registrar.playToServer(Payloads.PlaneEdit.TYPE, Payloads.PlaneEdit.CODEC,
                ServerNetHandler::onPlaneEdit);
        registrar.playToServer(Payloads.MediaUpload.TYPE, Payloads.MediaUpload.CODEC,
                ServerNetHandler::onMediaUpload);
        registrar.playToServer(Payloads.MediaRequest.TYPE, Payloads.MediaRequest.CODEC,
                ServerNetHandler::onMediaRequest);
        registrar.playToServer(Payloads.FontUpload.TYPE, Payloads.FontUpload.CODEC,
                ServerNetHandler::onFontUpload);
        registrar.playToServer(Payloads.WidgetAction.TYPE, Payloads.WidgetAction.CODEC,
                ServerNetHandler::onWidgetAction);

        // ---- 服务端 → 客户端：两端都要登记（否则通道协商对不上），但处理函数按 dist 选 ----
        registerClientbound(registrar, client);

        Projector.LOGGER.info("[Projector] 网络通道注册完成 (v{}, 本端={}, 客户端包 {} 个)",
                VERSION, client ? "物理客户端" : "专用服务端", CLIENTBOUND.size());
    }

    /**
     * 登记全部客户端方向的包。
     *
     * @param client true = 物理客户端（用真实处理函数）；false = 专用服务端（用占位函数）
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void registerClientbound(PayloadRegistrar registrar, boolean client) {
        // 关键一行：只有物理客户端才会执行 ClientPayloadHandlers.lookup()。
        // 专用服务端走 id -> IGNORED_HANDLER，全程不碰 client 包（也绝不加载它们）。
        Function<ResourceLocation, IPayloadHandler<CustomPacketPayload>> lookup = client
                ? ClientPayloadHandlers.lookup()
                : id -> IGNORED_HANDLER;
        for (Clientbound cb : CLIENTBOUND) {
            IPayloadHandler<CustomPacketPayload> handler = lookup.apply(cb.id());
            if (handler == null) {
                // 客户端漏登记处理函数是 Bug，必须吼出来（不许静默收包不处理）
                Projector.LOGGER.error("[Projector] 客户端包 {} 没有处理函数，已按忽略处理", cb.id());
                handler = IGNORED_HANDLER;
            }
            registrar.playToClient((CustomPacketPayload.Type) cb.type(), (StreamCodec) cb.codec(),
                    (IPayloadHandler) handler);
        }
    }

    /** 客户端方向包的 id 列表（测试用：两端登记必须完全一致）。 */
    public static List<ResourceLocation> clientboundIds() {
        return CLIENTBOUND.stream().map(Clientbound::id).toList();
    }

    /** 专用服务端占位处理函数（客户端查表失败时的兜底，测试用）。 */
    public static IPayloadHandler<CustomPacketPayload> ignoredHandler() {
        return IGNORED_HANDLER;
    }
}

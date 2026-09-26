package top.hmjmfabc.projector.client;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import top.hmjmfabc.projector.Projector;
import top.hmjmfabc.projector.network.ClientNetHandler;
import top.hmjmfabc.projector.network.Payloads;
import top.hmjmfabc.projector.network.ProjectorNetwork;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 「客户端方向的包 → 真正的处理函数」查表器。
 *
 * <h2>⚠ 这个类只允许在物理客户端上被加载</h2>
 * <p>它引用 {@link ClientNetHandler}，而后者引用 {@code PlaneDialogScreen}
 * （继承 {@code net.minecraft.client.gui.screens.Screen}）。专用服务端一旦加载到
 * 这条链上的任何类，NeoForge 的 {@code RuntimeDistCleaner} 就会抛
 * {@code Attempted to load class ... for invalid dist DEDICATED_SERVER}，
 * <b>整个模组加载失败、服务器起不来</b>（{@code 27.1-rc-73} 实测崩溃）。</p>
 *
 * <p>所以 {@link ProjectorNetwork#register} 只在
 * {@code FMLEnvironment.dist.isClient()} 为真时才调用这里的 {@link #lookup()}；
 * 包的类型与版本仍由 {@code ProjectorNetwork.CLIENTBOUND} 这张<b>唯一来源</b>的表
 * 在两端登记（只用处理函数区分 dist）。</p>
 *
 * <p>不变量由 {@code tmp/v17}（{@code T17}）守护：用「禁用 client 类的类加载器」
 * 真的执行一遍专用服务端注册，并断言这里为每一个客户端包都提供了<b>非占位</b>的处理函数。</p>
 */
public final class ClientPayloadHandlers {

    private ClientPayloadHandlers() {
    }

    /** 客户端方向的包 id → 真实处理函数。 */
    private static final Map<ResourceLocation, IPayloadHandler<CustomPacketPayload>> TABLE = build();

    private static Map<ResourceLocation, IPayloadHandler<CustomPacketPayload>> build() {
        Map<ResourceLocation, IPayloadHandler<CustomPacketPayload>> m = new HashMap<>();
        m.put(Payloads.OpenDialog.TYPE.id(), wrap(ClientNetHandler::onOpenDialog));
        m.put(Payloads.SyncPlanes.TYPE.id(), wrap(ClientNetHandler::onSyncPlanes));
        m.put(Payloads.MediaData.TYPE.id(), wrap(ClientNetHandler::onMediaData));
        m.put(Payloads.ServerInfo.TYPE.id(), wrap(ClientNetHandler::onServerInfo));
        m.put(Payloads.FontStored.TYPE.id(), wrap(ClientNetHandler::onFontStored));
        // 【rc-77】上传流控的确认包
        m.put(Payloads.MediaAck.TYPE.id(), wrap(ClientNetHandler::onMediaAck));
        return Map.copyOf(m);
    }

    /**
     * 把「某个具体包的处理器」适配成统一的 {@code IPayloadHandler<CustomPacketPayload>}。
     *
     * <p>转换是安全的：NeoForge 只会用该包的真实类型调用它，而方法引用生成的桥接方法
     * 自己会 {@code checkcast} 回具体类型。这里只是为了能放进同一张表。</p>
     */
    @SuppressWarnings("unchecked")
    private static <T extends CustomPacketPayload> IPayloadHandler<CustomPacketPayload> wrap(
            IPayloadHandler<T> handler) {
        return (IPayloadHandler<CustomPacketPayload>) handler;
    }

    /**
     * 返回查表器：命中就给出真实处理函数，未命中则报错并退回占位函数
     * （宁可打日志也不静默丢包 —— {@code AGENTS.md §1.8} 第 4 条）。
     */
    public static Function<ResourceLocation, IPayloadHandler<CustomPacketPayload>> lookup() {
        return id -> {
            IPayloadHandler<CustomPacketPayload> h = TABLE.get(id);
            if (h != null) return h;
            Projector.LOGGER.error("[Projector] 客户端没有为客户端包 {} 登记处理函数（已忽略）", id);
            return ProjectorNetwork.ignoredHandler();
        };
    }

    /** 客户端已登记处理函数的包 id 集合（测试用）。 */
    public static java.util.Set<ResourceLocation> handledIds() {
        return TABLE.keySet();
    }
}

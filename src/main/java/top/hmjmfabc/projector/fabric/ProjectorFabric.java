package top.hmjmfabc.projector.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import top.hmjmfabc.projector.Projector;
import top.hmjmfabc.projector.compat.event.EventBus;
import top.hmjmfabc.projector.compat.event.IEventBus;
import top.hmjmfabc.projector.compat.fml.ModContainer;
import top.hmjmfabc.projector.compat.fml.event.lifecycle.FMLCommonSetupEvent;
import top.hmjmfabc.projector.compat.neo.network.event.RegisterPayloadHandlersEvent;
import top.hmjmfabc.projector.compat.neo.network.registration.PayloadRegistrar;
import top.hmjmfabc.projector.network.ProjectorNetwork;

/**
 * Fabric 主入口（对应 NeoForge 版的 {@code @Mod} 主类 {@link Projector}）。
 *
 * <h2>为什么这里只有"接线"</h2>
 * <p>模组本身的逻辑一行没动：{@link Projector} 仍在它的构造函数里注册配置、网络与
 * 服务端事件（{@code NeoForge.EVENT_BUS.register(new ServerEvents())} 等），
 * 只是那些 NeoForge 类型换成了 {@code compat} 包里的替身。本类负责按 NeoForge 的
 * <b>阶段顺序</b>把这些阶段跑一遍：</p>
 * <ol>
 *   <li>公共事件桥（Fabric 回调 → 模组总线）；</li>
 *   <li>构造主类（配置读写、登记监听器）；</li>
 *   <li>分发 {@code RegisterPayloadHandlersEvent} 并把登记结果交给 Fabric 网络层；</li>
 *   <li>分发 {@code FMLCommonSetupEvent}。</li>
 * </ol>
 */
public final class ProjectorFabric implements ModInitializer {

    /** 模组总线（对应 NeoForge 传给主类构造的 {@code IEventBus}）。 */
    public static final IEventBus MOD_BUS = new EventBus("mod");

    /** 从 {@code fabric.mod.json} 读出来的版本号（日志与 HUD 用）。 */
    public static String modVersion = "?";

    /** 本次登记出来的网络表（客户端入口要用它登记 S2C 接收器）。 */
    private static PayloadRegistrar payloads;

    @Override
    public void onInitialize() {
        modVersion = readVersion();
        // ① 公共事件桥（两端都要；只碰公共类型）
        FabricCommonEvents.register();
        // ② 主类：配置、网络与事件的登记都在它的构造函数里（与 NeoForge 版完全一致）
        new Projector(MOD_BUS, new ModContainer(Projector.MODID, modVersion));
        // ③ 网络：先让 ProjectorNetwork 把表填好，再交给 Fabric
        RegisterPayloadHandlersEvent payloadEvent =
                new RegisterPayloadHandlersEvent(ProjectorNetwork.VERSION);
        MOD_BUS.post(payloadEvent);
        payloads = payloadEvent.registrar();
        FabricNetworking.applyCommon(payloads);
        // ④ 通用初始化阶段
        MOD_BUS.post(new FMLCommonSetupEvent());
        Projector.LOGGER.info("[Projector] 事件总线登记完成：模组总线 {} 个监听器 / 游戏总线 {} 个监听器",
                MOD_BUS.size(),
                top.hmjmfabc.projector.compat.neo.common.NeoForge.EVENT_BUS.size());
    }

    /** 供客户端入口取用（客户端入口在 main 之后执行）。 */
    public static PayloadRegistrar payloads() {
        return payloads;
    }

    private static String readVersion() {
        try {
            return FabricLoader.getInstance().getModContainer(Projector.MODID)
                    .map(c -> c.getMetadata().getVersion().getFriendlyString())
                    .orElse("?");
        } catch (Throwable t) {
            return "?";
        }
    }
}

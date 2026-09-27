package top.hmjmfabc.projector;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * 投影仪 / Projector —— 万物皆屏幕！
 *
 * <p>主类。这里只做「注册」这一件事：网络通道、配置、公共事件。
 * 具体的平面数据、渲染、界面分别位于 {@code top.hmjmfabc.projector.server}、
 * {@code top.hmjmfabc.projector.client} 与 {@code ...client.gui}。</p>
 */
@Mod(Projector.MODID)
public class Projector {

    /** 命名空间 / 模组 ID。 */
    public static final String MODID = "projector";

    /** 构建标识（显示在 HUD 上，用于确认加载的是哪一版）。 */
    public static final String BUILD_TAG = "27.1.2";

    /** 全模组共用的日志器。 */
    public static final Logger LOGGER = LogUtils.getLogger();

    public Projector(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(top.hmjmfabc.projector.network.ProjectorNetwork::register);

        modContainer.registerConfig(ModConfig.Type.COMMON, ProjectorConfig.SPEC);

        NeoForge.EVENT_BUS.register(new top.hmjmfabc.projector.server.ServerEvents());
        // 顺带把「本端」打出来：dist 相关的问题（例如专用服务端加载到客户端类）
        // 光看日志很难判断，这一行能让下一次排查省很多时间。
        LOGGER.info("[Projector] 投影仪已装载：万物皆屏幕！ 版本={} 构建={} 本端={}",
                modContainer.getModInfo().getVersion(), BUILD_TAG,
                net.neoforged.fml.loading.FMLEnvironment.dist);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("[Projector] common setup done.");
    }

    /** 便捷方法：生成 {@code projector:xxx} 形式的资源位置。 */
    public static net.minecraft.resources.ResourceLocation id(String path) {
        return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}

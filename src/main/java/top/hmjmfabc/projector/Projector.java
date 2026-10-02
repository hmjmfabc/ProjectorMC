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
    public static final String BUILD_TAG = "27.2";

    /** 全模组共用的日志器。 */
    public static final Logger LOGGER = LogUtils.getLogger();

    public Projector(IEventBus modEventBus, ModContainer modContainer) {
        // 第一步就检查安卓链路，保证这些警告排在日志最前面（见方法注释）。
        warnIfAndroidVideoStackIncomplete();
        warnIfAndroidWebStackIncomplete();
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

    /** 安卓兼容模组 WATERMeDIA: Android Bridge 的 modid（我们只按 id 认它，不引用它的代码）。 */
    private static final String ANDROID_BRIDGE_MODID = "watermedia_android_bridge";

    /** MCEF 的安卓兼容层 modid（MCEF: Android Bridge，同样只按 id 认、不引用其代码）。 */
    private static final String MCEF_ANDROID_BRIDGE_MODID = "mcefdroid";

    /**
     * 【27.1.3】安卓视频链路缺件提示。
     *
     * <p>背景：WaterMedia 的桌面实现要靠 LWJGL/FFmpeg 的原生库，安卓上跑不起来；它的作者另发了
     * 一个安卓兼容层 <b>WATERMeDIA: Android Bridge</b>（modid {@code watermedia_android_bridge}）。
     * 三者齐了视频才能解码：<b>本模组 + WaterMedia + Android Bridge</b>。
     * 少了最后一环的典型表现是「装了 WaterMedia，视频控件却一直放不出来」，很难自己想到。
     *
     * <p>检测挂在主类<b>构造期</b>（游戏启动的最早阶段，早于资源加载、界面与网络），
     * 所以这几行会排在整个日志的最前面，玩家从上往下看第一眼就能看到。
     * 任何一步失败都只当作「不确定」处理，绝不影响启动。</p>
     *
     * <p>按用户要求用 <b>ERROR</b> 级别：缺这个兼容层不是「可能不好用」，
     * 而是<b>一旦要播视频就有很大概率直接崩掉游戏</b>，必须让它在日志里最显眼。</p>
     */
    private static void warnIfAndroidVideoStackIncomplete() {
        try {
            if (!isAndroidRuntime()) return;            // 不是安卓：与本次提示无关
            if (!isModLoaded("watermedia")) return;     // 没装 WaterMedia：视频本来就不由它负责
            if (isModLoaded(ANDROID_BRIDGE_MODID)) return; // 三件套齐全：正常
            LOGGER.error("================================================================");
            LOGGER.error("[Projector] 在 Android 上检测到 WaterMedia，但没有检测到它的安卓兼容模组：");
            LOGGER.error("[Projector]   WATERMeDIA: Android Bridge（modid={}）", ANDROID_BRIDGE_MODID);
            LOGGER.error("[Projector] ⚠ 不装它会导致游戏崩溃：WaterMedia 在安卓上必须靠这个兼容层做解码，");
            LOGGER.error("[Projector] ⚠ 缺了它，只要画面里出现要播放的视频（本模组的视频控件也算），游戏就可能直接崩掉。");
            LOGGER.error("[Projector] 请立刻安装该兼容模组后重启游戏（三个要一起装：本模组 + WaterMedia + 兼容模组）。");
            LOGGER.error("================================================================");
        } catch (Throwable t) {
            // 纯提示逻辑，任何异常都不该影响启动。
        }
    }

    /**
     * 【27.2】安卓网页链路缺件提示。
     *
     * <p>背景：网页控件靠可选的 <b>MCEF</b>（modid {@code mcef}）渲染。MCEF 的桌面包依赖
     * glibc 版 Chromium 原生库，安卓上没有；装了 MCEF 却没装它的安卓兼容层
     * <b>MCEF: Android Bridge</b>（modid {@code mcefdroid}）时，MCEF 自己会在初始化阶段
     * 去加载那份原生库并失败 —— 它的初始化调用**没有异常保护**，抛出的是
     * {@code UnsatisfiedLinkError}（是 {@code Error}，不是 {@code Exception}）⇒ <b>直接把游戏带走</b>。
     *
     * <p>这不是本模组的 bug，但玩家几乎不可能自己想到，所以在这里用 ERROR 级点名。</p>
     */
    private static void warnIfAndroidWebStackIncomplete() {
        try {
            if (!isAndroidRuntime()) return;                    // 不是安卓：与本次提示无关
            if (!isModLoaded("mcef")) return;                   // 没装 MCEF：网页控件本来就不用它
            if (isModLoaded(MCEF_ANDROID_BRIDGE_MODID)) return; // 有兼容层：交给它兜底
            LOGGER.error("================================================================");
            LOGGER.error("[Projector] 在 Android 上检测到 MCEF，但没有检测到它的安卓兼容模组：");
            LOGGER.error("[Projector]   MCEF: Android Bridge（modid={}）", MCEF_ANDROID_BRIDGE_MODID);
            LOGGER.error("[Projector] ⚠ 不装它会导致游戏崩溃：MCEF 在安卓上加载 Chromium 原生库会失败，");
            LOGGER.error("[Projector] ⚠ 而 MCEF 自己的初始化没有异常保护，失败时会把整个游戏一起带走。");
            LOGGER.error("[Projector] 请安装该兼容模组，或先把 MCEF 移出 mods 目录，然后重启游戏。");
            LOGGER.error("================================================================");
        } catch (Throwable t) {
            // 纯提示逻辑，任何异常都不该影响启动。
        }
    }

    /** 是否为安卓上的 Java 版运行环境（FCL / PojavLauncher / 原生 ART）。 */
    private static boolean isAndroidRuntime() {
        // ① 最强判据：ART/Dalvik 上 android.os.Build 一定在，桌面 JVM 上一定不在。
        try {
            Class.forName("android.os.Build");
            return true;
        } catch (Throwable ignored) {
            // 换下一条判据
        }
        // ② 系统环境变量（Android 上恒有）。
        try {
            if (System.getenv("ANDROID_ROOT") != null || System.getenv("ANDROID_DATA") != null) return true;
            // ③ 启动器特有：PojavLauncher 会设它；FCL 用同一内核。
            if (System.getenv("POJAV_NATIVEDIR") != null || System.getenv("POJAV_LAUNCHER") != null) return true;
        } catch (Throwable ignored) {
            // 环境变量读不到就继续
        }
        // ④ 虚拟机名字兜底（Dalvik/ART 上 java.vm.name=Dalvik）。
        try {
            String vm = System.getProperty("java.vm.name", "") + " " + System.getProperty("java.runtime.name", "");
            if (vm.toLowerCase(java.util.Locale.ROOT).contains("dalvik")) return true;
        } catch (Throwable ignored) {
            // 继续
        }
        // ⑤ 最后看一眼安卓特有的文件。
        try {
            return java.nio.file.Files.isRegularFile(java.nio.file.Path.of("/system/build.prop"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 某个 modid 是否已加载；启动早期读不到就按「没装」算（只会少打一条提示）。 */
    private static boolean isModLoaded(String modId) {
        try {
            return net.neoforged.fml.ModList.get().isLoaded(modId);
        } catch (Throwable t) {
            return false;
        }
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("[Projector] common setup done.");
    }

    /** 便捷方法：生成 {@code projector:xxx} 形式的资源位置。 */
    public static net.minecraft.resources.ResourceLocation id(String path) {
        return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}

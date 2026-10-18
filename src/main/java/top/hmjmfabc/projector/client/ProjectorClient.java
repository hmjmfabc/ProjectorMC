package top.hmjmfabc.projector.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import top.hmjmfabc.projector.compat.distmarker.Dist;
import top.hmjmfabc.projector.compat.event.IEventBus;
import top.hmjmfabc.projector.compat.fml.common.Mod;
import top.hmjmfabc.projector.compat.fml.event.lifecycle.FMLClientSetupEvent;
import top.hmjmfabc.projector.compat.neo.client.event.ClientPlayerNetworkEvent;
import top.hmjmfabc.projector.compat.neo.client.event.ClientTickEvent;
import top.hmjmfabc.projector.compat.neo.client.event.RegisterClientCommandsEvent;
import top.hmjmfabc.projector.compat.neo.client.event.RenderGuiEvent;
import top.hmjmfabc.projector.compat.neo.client.event.RegisterKeyMappingsEvent;
import top.hmjmfabc.projector.compat.neo.client.event.RenderLevelStageEvent;
import top.hmjmfabc.projector.compat.neo.common.NeoForge;
import top.hmjmfabc.projector.Projector;
import top.hmjmfabc.projector.ProjectorConfig;
import top.hmjmfabc.projector.client.font.FontManager;
import top.hmjmfabc.projector.client.media.LocalMedia;
import top.hmjmfabc.projector.client.media.MediaCache;
import top.hmjmfabc.projector.client.render.QuadCollector;
import top.hmjmfabc.projector.client.render.SelectionRenderer;
import top.hmjmfabc.projector.client.render.WidgetRenderer;
import top.hmjmfabc.projector.client.render.WorldPlaneRenderer;

/**
 * 客户端入口。
 *
 * <p>负责：加载内置字体、注册按键、注册世界渲染阶段、注册「纯白纹理」
 * 以及监听玩家进出世界以清理缓存。</p>
 */
@Mod(value = Projector.MODID, dist = Dist.CLIENT)
public final class ProjectorClient {

    private static final WorldPlaneRenderer PLANE_RENDERER = new WorldPlaneRenderer();
    private static final SelectionRenderer SELECTION_RENDERER = new SelectionRenderer();

    /** 纯白纹理（用于颜色块、描边、占位框）。 */
    public static final ResourceLocation WHITE_TEXTURE = Projector.id("dynamic/white");

    public ProjectorClient(IEventBus modBus) {
        // 【Fabric 移植】addListener 多一个显式事件类型参数（Java 的 lambda 类只有 accept(Object)，
        // NeoForge 靠泛型元数据猜类型的那套在 Fabric 侧不成立，详见 compat.event.IEventBus 注释）。
        modBus.addListener(FMLClientSetupEvent.class, this::clientSetup);
        modBus.addListener(RegisterKeyMappingsEvent.class, ProjectorKeys::register);
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, this::onRenderLevel);
        NeoForge.EVENT_BUS.register(new ClientInputHandler());
        NeoForge.EVENT_BUS.addListener(RenderGuiEvent.Post.class,
                top.hmjmfabc.projector.client.gui.ProjectorHud::onRenderGui);
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingIn.class, this::onLogin);
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, this::onLogout);
        // 【27.1.1】音乐控件的每 tick 调度（该不该出声、续播、音量跟随）
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class,
                (ClientTickEvent.Post event) -> top.hmjmfabc.projector.client.music.MusicManager.tick());
        // 【27.2.2】登录命令：客户端侧 /nmbl login 在这里注册；
        // 服务端侧 /nmbl server … 由公共侧（Projector 构造）注册 —— 见 BetterLoginBridge 的说明。
        NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class,
                top.hmjmfabc.projector.compat.betterlogin.client.ClientCommandBridge::onRegisterClientCommands);
    }

    private void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            LocalMedia.ensureDirectories();
            registerMediaProber();
            // 【27.2-pre-136】排版缓存必须在字体装载**之后**清一次：
            // 装载过程中字体实例可能被新建，旧缓存键指向的对象已经不是当前字体。
            top.hmjmfabc.projector.client.render.TextRenderer.clearCache();
            FontManager.loadBuiltins();
            top.hmjmfabc.projector.client.render.TextRenderer.clearCache();
            registerWhiteTexture();
            registerGlowTexture();
            verifyAtlasTexture();
            migrateStaleKeyBindings();
            // 【27.2.2】并入投影仪的「网易云登录（Better Login，MIT）」：
            // 客户端初始化一次；命令在下面两条事件里注册（客户端命令 + 服务端命令）。
            top.hmjmfabc.projector.compat.betterlogin.BetterLoginBridge.init();
            Projector.LOGGER.info("[Projector] 客户端就绪（字体 {} 个）", FontManager.count());
        });
    }

    /**
     * 【27.2.1-hotfix-144】把**存档里残留的旧按键绑定**迁到新默认值。
     *
     * <h2>为什么必须有这一步</h2>
     * Minecraft 把按键绑定写进 {@code options.txt}，而**存档里的值优先于代码里的新默认值**。
     * ⑥.3 把「取消圈选」的默认键从 I 改成 Esc，但玩家 {@code options.txt} 里那句
     * {@code key_key.projector.cancel:key.keyboard.i} 一直留着 —— 于是：
     * 游戏里显示的是新的语言标签（改成 Esc 之前显示的是「取消圈选 (I)」），
     * 而**实际绑的还是 I** ⇒ 玩家按 I 会取消圈选，怎么改代码都「修不掉」。
     *
     * <p>这里只在「确实是那个旧默认值」时迁移（I 号键 = 73），不动玩家自己改过的绑定，
     * 并且只在 I 已经被本模组「重播流程」占用时才迁移（否则未来改了默认键又会误伤）。</p>
     */
    private static void migrateStaleKeyBindings() {
        try {
            var cancel = top.hmjmfabc.projector.client.ProjectorKeys.KEY_CANCEL;
            var replay = top.hmjmfabc.projector.client.ProjectorKeys.KEY_SEQ_REPLAY;
            // 【Fabric 移植】原版 KeyMapping 没有 getKey()（那是 NeoForge 的补丁方法），
            // 改用原版自带的 saveString()（例如 "key.keyboard.i"）做同样的判定。
            boolean cancelOnOldDefault = "key.keyboard.i".equals(cancel.saveString());
            boolean replayTookOldKey = "key.keyboard.i".equals(replay.saveString());
            if (cancelOnOldDefault && replayTookOldKey) {
                cancel.setKey(com.mojang.blaze3d.platform.InputConstants.getKey(
                        "key.keyboard.escape"));
                Projector.LOGGER.warn("[Projector] 按键迁移：把「取消圈选」从旧默认键 I 改回 Esc"
                        + "（I 现在用于「重播流程」；options.txt 里的旧绑定会覆盖代码里的新默认值，"
                        + "所以这一步必须做，否则按 I 会取消圈选）");
            }
        } catch (Throwable t) {
            Projector.LOGGER.debug("[Projector] 按键迁移跳过：{}", String.valueOf(t));
        }
    }

    /**
     * 注册媒体元数据探测器。
     *
     * <p>图片/视频的尺寸与帧数只有客户端能算（要用 stb 解码与容器扫描），
     * 但界面代码与会话状态在公共包里，因此通过这个回调注入。
     * <b>没有这一步，视频的帧数会永远被当成 1，看起来就是一张静止图。</b></p>
     */
    private static void registerMediaProber() {
        top.hmjmfabc.projector.common.platform.MediaMeta.setProber(path -> {
            try {
                String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                // 【27.2.4-201】视频只认外部解码器（WaterMedia）能放的容器：
                // mjpg / mjpeg / zip 这些内置帧序列格式已随 Build 112 的内置解码器下线，
                // 在这里就当作「不是视频」，绝不再退回逐帧解码。
                boolean video = name.endsWith(".avi") || name.endsWith(".mov")
                        || name.endsWith(".mp4") || name.endsWith(".m4v")
                        || name.endsWith(".webm") || name.endsWith(".mkv")
                        || name.endsWith(".flv") || name.endsWith(".wmv")
                        || name.endsWith(".3gp") || name.endsWith(".mpg")
                        || name.endsWith(".mpeg");
                if (video) {
                    // 统一入口：只读容器头拿宽高（画面比例才不会错），帧数交给解码器自己管。
                    var ext = top.hmjmfabc.projector.client.media.MediaUploader.probeVideo(path);
                    if (ext != null) {
                        return new top.hmjmfabc.projector.common.platform.MediaMeta(
                                ext.width(), ext.height(), Math.max(1, ext.frames()),
                                ext.fpsOrDefault());
                    }
                    return top.hmjmfabc.projector.common.platform.MediaMeta.empty();
                }
                byte[] data = java.nio.file.Files.readAllBytes(path);
                int[] size = top.hmjmfabc.projector.client.media.ImageCodec.size(data, 0, data.length);
                if (size == null) {
                    return top.hmjmfabc.projector.common.platform.MediaMeta.empty();
                }
                return new top.hmjmfabc.projector.common.platform.MediaMeta(size[0], size[1], 1, 10);
            } catch (Throwable t) {
                return top.hmjmfabc.projector.common.platform.MediaMeta.empty();
            }
        });
    }

    /**
     * 自检：字形图集的纹理必须已经注册在纹理管理器里。
     *
     * <p>渲染类型在 {@code setupRenderState} 阶段按名字去纹理管理器取纹理，
     * 取不到就会绑定一个无效 ID，结果是<b>整批</b>内容一个像素都画不出来。
     * 这里在渲染开始前主动检查，缺了就补注册一张空白页顶上。</p>
     */
    private static void verifyAtlasTexture() {
        try {
            ResourceLocation loc = FontManager.atlasTexture(null);
            if (loc == null) {
                Projector.LOGGER.error("[Projector] 字形图集纹理为空，文字将无法渲染");
                return;
            }
            var tm = Minecraft.getInstance().getTextureManager();
            if (tm.getTexture(loc) == null) {
                int size = ProjectorConfig.INSTANCE.atlasPageSize.get();
                Projector.LOGGER.warn("[Projector] 字形图集纹理 {} 未注册，正在补注册", loc);
                var img = new com.mojang.blaze3d.platform.NativeImage(
                        com.mojang.blaze3d.platform.NativeImage.Format.RGBA, size, size, false);
                tm.register(loc, new DynamicTexture(img));
            } else {
                Projector.LOGGER.info("[Projector] 字形图集纹理自检通过: {}", loc);
            }
        } catch (Throwable t) {
            Projector.LOGGER.error("[Projector] 字形图集纹理自检失败", t);
        }
    }

    /** 生成一张 1×1 的纯白纹理，供实心矩形使用。 */
    private static void registerWhiteTexture() {
        try {
            com.mojang.blaze3d.platform.NativeImage img =
                    new com.mojang.blaze3d.platform.NativeImage(1, 1, false);
            img.setPixelRGBA(0, 0, 0xFFFFFFFF);
            Minecraft.getInstance().getTextureManager().register(WHITE_TEXTURE, new DynamicTexture(img));
            WidgetRenderer.WHITE = WHITE_TEXTURE;
        } catch (Exception ex) {
            Projector.LOGGER.warn("[Projector] 生成白色纹理失败: {}", ex.toString());
        }
    }

    /** 【27.2.2】展示框光球用的「径向渐变白色光斑」纹理坐标。 */
    public static final ResourceLocation GLOW_TEXTURE = Projector.id("dynamic/glow");

    /**
     * 【27.2.2】生成 32×32 的径向渐变光斑（中心白、边缘透明），给展示框的光球用。
     *
     * <p>为什么不用「叠几层小方块」凑一个圆：那在墙上是一圈套一圈的方框，很难看。
     * 一张 32×32 的贴图只占 4 KB，而且整个光球只要 <b>一个四边形</b>
     * （对比：用方块凑圆至少 8 个），对安卓的绘制开销也友好。</p>
     *
     * <p>像素值沿用字形图集那一套：<b>高字节是 alpha</b>、低 24 位是白色
     * （{@code NativeImage} 的打包顺序是 ABGR，R=G=B 时两种解释结果一致，
     * 所以这里不会踩通道顺序的坑）。</p>
     */
    private static void registerGlowTexture() {
        try {
            final int size = 32;
            com.mojang.blaze3d.platform.NativeImage img =
                    new com.mojang.blaze3d.platform.NativeImage(size, size, false);
            double half = (size - 1) / 2.0;
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    double dx = (x - half) / half;
                    double dy = (y - half) / half;
                    double r = Math.min(1.0, Math.sqrt(dx * dx + dy * dy));
                    // 1 - r^2 的平方：中心实、边缘软，看起来像一团光而不是一个圆盘
                    double f = 1.0 - r * r;
                    int a = (int) Math.round(255.0 * f * f);
                    img.setPixelRGBA(x, y, (a << 24) | 0x00FFFFFF);
                }
            }
            Minecraft.getInstance().getTextureManager().register(GLOW_TEXTURE, new DynamicTexture(img));
            top.hmjmfabc.projector.client.render.ItemWidgetRenderer.GLOW = GLOW_TEXTURE;
        } catch (Throwable ex) {
            // 装饰性纹理：失败也不能影响启动（安卓上 GL 相关异常有可能是 Error）
            Projector.LOGGER.warn("[Projector] 生成光斑纹理失败（展示框光球将不显示）: {}", ex.toString());
        }
    }

    private void onRenderLevel(RenderLevelStageEvent event) {
        // 【hotfix-106】改到「半透明方块之前」：水面/玻璃是**写深度**的，我们的内容
        // 画在它们之后就会被它们的深度挡掉 —— 表现就是「站在水上看不见水下的控件」。
        // 提前到实体之后，水就会像盖在内容上一样正常混色（水下、水下看水上也都对）。
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        watchdog();
        try {
            // 图集在上一帧分配过字形，这里把改动上传到 GPU。
            FontManager.uploadDirty();
            long tRender = System.nanoTime();
            // 【hotfix-98】水下（以及洞穴/岩浆里）雾很浓：我们的四边形用的是
            // rendertype_text，它的顶点着色器会算 fog_distance、片元里做线性雾混合，
            // 于是整块屏幕被染成水色 ⇒ 玩家报「水下什么都渲染不出来」。
            // 这些内容本质是「屏幕」，不该被场景雾吃掉，所以画的时候把雾推到无穷远，画完还原。
            float fogStart = com.mojang.blaze3d.systems.RenderSystem.getShaderFogStart();
            float fogEnd = com.mojang.blaze3d.systems.RenderSystem.getShaderFogEnd();
            com.mojang.blaze3d.systems.RenderSystem.setShaderFogStart(1.0e6f);
            com.mojang.blaze3d.systems.RenderSystem.setShaderFogEnd(2.0e6f);
            try {
                PLANE_RENDERER.render(event.getPoseStack(), event.getCamera(),
                        event.getPartialTick().getGameTimeDeltaPartialTick(false));
                SELECTION_RENDERER.render(event.getPoseStack(), event.getCamera(),
                        event.getPartialTick().getGameTimeDeltaPartialTick(false));
            } finally {
                com.mojang.blaze3d.systems.RenderSystem.setShaderFogStart(fogStart);
                com.mojang.blaze3d.systems.RenderSystem.setShaderFogEnd(fogEnd);
            }
            RENDER_NANOS += System.nanoTime() - tRender;
        } catch (Throwable t) {
            // 渲染里的任何异常都不应该让游戏崩溃；打印一次并继续
            Projector.LOGGER.error("[Projector] 平面渲染异常", t);
        }
    }

    // ------------------------------------------------------------------
    // 【rc-85】卡顿看门狗：把「卡」变成数字
    // ------------------------------------------------------------------
    /** 上一次渲染帧的时刻 / 上一次打日志的时刻 / 上一秒的帧上传计数。 */
    private static long lastFrameMs;
    private static long lastStallLogMs;
    private static long lastUploadedFrames;
    private static long lastRenderNanos, lastMainMs, lastUploadMs, lastDecodeMs, lastFillMs;
    private static int stallCount;
    /** 【rc-86】本模组在渲染阶段占用的累计时间（用来判断卡顿是不是我们造成的）。 */
    private static long RENDER_NANOS;
    /** 超过这个间隔就认为「卡了一下」（正常一帧 16.7 ms，手机 30 fps 是 33 ms）。 */
    private static final long STALL_MS = 120L;

    /**
     * 每帧测一次「距上一帧过了多久」，超过 {@link #STALL_MS} 就打一行，
     * 并带上媒体流水线的现场（这段日志就是「视频导致卡顿」这类问题的第一手证据）。
     *
     * <p>为什么要这么多字段：玩家报「视频的存在会使得客户端间歇性卡顿（每 3 秒卡一次）」时，
     * 光看「卡了 400 ms」什么也定不了位 —— 必须同时知道
     * 「这一秒上传了多少帧 / 建了几个槽位 / 有几个媒体在传 / 堆用了多少 / GC 跑了几次」，
     * 才能分辨是「帧上传太密」还是「GC 被分配拖住」。</p>
     */
    private static void watchdog() {
        long now = System.currentTimeMillis();
        long prev = lastFrameMs;
        lastFrameMs = now;
        if (prev == 0L) return;
        long gap = now - prev;
        if (gap < STALL_MS) return;
        stallCount++;
        if (now - lastStallLogMs < 1000L) return;      // 硬限流：一秒最多一行
        lastStallLogMs = now;
        long[] st = top.hmjmfabc.projector.client.media.MediaCache.stats();
        long[] tm = top.hmjmfabc.projector.client.media.MediaCache.timings();
        long myRenderMs = (RENDER_NANOS - lastRenderNanos) / 1_000_000L;
        lastRenderNanos = RENDER_NANOS;
        long mainMs = tm[0] - lastMainMs;
        long upMs = tm[1] - lastUploadMs;
        long decMs = tm[2] - lastDecodeMs;
        long fillMs = tm[3] - lastFillMs;
        lastMainMs = tm[0];
        lastUploadMs = tm[1];
        lastDecodeMs = tm[2];
        lastFillMs = tm[3];
        long uploaded = st[0];
        // 【rc-86】改用 MXBean 读堆：Runtime.totalMemory()-freeMemory() 在 G1 上会随
        // 「已提交区域」剧烈摆动（实测能在 2 秒里来回 ±1GB），据此判断「分配速率」会误判。
        long heapUsedMb = 0L, heapMaxMb = 0L;
        try {
            java.lang.management.MemoryUsage hu = java.lang.management.ManagementFactory
                    .getMemoryMXBean().getHeapMemoryUsage();
            heapUsedMb = hu.getUsed() / (1024L * 1024L);
            heapMaxMb = hu.getMax() / (1024L * 1024L);
        } catch (Throwable ignored) {
        }
        StringBuilder gc = new StringBuilder();
        try {
            for (java.lang.management.GarbageCollectorMXBean b
                    : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
                if (gc.length() > 0) gc.append(' ');
                gc.append(b.getName()).append('=').append(b.getCollectionCount())
                        .append("次/").append(b.getCollectionTime()).append("ms");
            }
        } catch (Throwable ignored) {
        }
        Projector.LOGGER.warn("[Projector][客户端][卡顿] 主线程停顿 {} ms（本局第 {} 次）"
                        + "｜【本模组占用】渲染={}ms 上传={}ms 最久单次={}｜工作线程 解码={}ms 填帧={}ms"
                        + "｜这一秒上传帧={} 填帧={} 丢弃过时帧={} 待上传={} 保护窗跳过={}"
                        + "｜槽位={}(累计建 {}) 保护窗={}ms 帧索引={} 在途下载={} 视频数={}"
                        + "｜素材扫描[{}]｜内存[{}]｜堆={}MB/{}MB GC[{}]",
                gap, stallCount,
                myRenderMs, mainMs, top.hmjmfabc.projector.client.media.MediaCache.maxMainOp(),
                decMs, fillMs,
                uploaded - lastUploadedFrames, st[6], st[1], st[7], st[8],
                st[3], st[2],
                top.hmjmfabc.projector.client.media.MediaCache.slotHoldMsForTest(),
                st[4], st[5], top.hmjmfabc.projector.client.media.MediaCache.videoMediaCount(),
                // 【rc-88】扫描耗时必须出现在卡顿行里：rc-86/87 两轮里「渲染=1013ms」
                // 把所有注意力都引到了渲染代码上，而时间其实花在渲染调用里的一次目录扫描上。
                top.hmjmfabc.projector.client.media.LocalMedia.scanReport(),
                // 【27.1.1】把「谁在吃内存」拆开写进卡顿行（图片纹理 / 视频帧槽位 / 下载缓冲）
                top.hmjmfabc.projector.client.media.MediaCache.memoryReport(),
                heapUsedMb, heapMaxMb, gc);
        lastUploadedFrames = uploaded;
    }

    private void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        PlaneCache.clear();
        SelectionState.clear();
        // 【①②⑫】新的一局：清单与预取状态全部重来（服务端登录后马上会重发）
        top.hmjmfabc.projector.client.ClientServerInfo.clear();
        top.hmjmfabc.projector.client.media.CachePrefetcher.reset();
        // 【⑬】每次进世界都补一次 .nomedia：玩家可能在上一局里把它删掉过，
        // 直接补回来比让他下次「清缓存清出流量」要划算得多。
        LocalMedia.ensureNoMediaFiles();
        LocalMedia.rescan();
        // 重进世界是一个全新的时机：把上一局的「媒体加载失败」状态清掉，
        // 否则存档里的图片/视频会一直显示成「媒体未就绪」，
        // 玩家只能靠重新编辑控件、重选一次素材来救。
        MediaCache.resetFailures();
        // 【27.1.1】进世界时记一条内存基线：以后对比「这一局涨了多少」不用猜
        Projector.LOGGER.info("[Projector][客户端] 媒体内存基线：{}｜音乐[{}]",
                top.hmjmfabc.projector.client.media.MediaCache.memoryReport(),
                top.hmjmfabc.projector.client.music.MusicManager.describeCache());
        // 【27.1.3】缓存清理（放后台，别卡住进世界）：先清在线视频目录，再清本地缓存
        //（本地缓存**优先清视频**，见 CacheCleaner）
        Thread sweep = new Thread(() -> {
            top.hmjmfabc.projector.client.media.net.OnlineVideoCache.sweep();
            top.hmjmfabc.projector.client.media.CacheCleaner.sweep();
        }, "Projector-Cache-Sweep");
        sweep.setDaemon(true);
        sweep.start();
    }

    private void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        // 【27.1.1】退出世界必须停音乐并清缓存：否则声道会被一直占着，下一局还在响
        top.hmjmfabc.projector.client.music.MusicManager.clearAll();
        // 【27.1.2】把交给 WaterMedia 的视频会话全部释放（否则换世界后解码器还挂着）
        top.hmjmfabc.projector.client.media.wm.WaterMediaVideos.releaseAll();
        top.hmjmfabc.projector.client.media.VideoControls.clear();
        // 【27.2】网页控件：把浏览器会话全部释放（换世界后不该还挂着渲染进程），
        // 并清掉世界内按钮栏的显隐状态（它纯属客户端显示状态）
        top.hmjmfabc.projector.client.web.WebSessions.releaseAll();
        top.hmjmfabc.projector.client.web.WebControls.clear();
        // 【27.1.3】在线视频：状态表清掉（**本地文件保留**，下次进世界直接命中缓存）
        top.hmjmfabc.projector.client.media.net.OnlineVideoCache.clear();
        PlaneCache.clear();
        SelectionState.clear();
        MediaCache.clearAll();
        // 【①②⑫】登出时把服务端清单与预取队列清空，避免把上一台的哈希表留在内存里
        top.hmjmfabc.projector.client.ClientServerInfo.clear();
        top.hmjmfabc.projector.client.media.CachePrefetcher.reset();
        QuadCollector.resetSource();
    }

    public static WorldPlaneRenderer planeRenderer() {
        return PLANE_RENDERER;
    }
}

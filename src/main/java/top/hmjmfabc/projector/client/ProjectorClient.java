package top.hmjmfabc.projector.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.TextureAtlasStitchedEvent;
import net.neoforged.neoforge.common.NeoForge;
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
        modBus.addListener(this::clientSetup);
        modBus.addListener(ProjectorKeys::register);
        NeoForge.EVENT_BUS.addListener(this::onRenderLevel);
        NeoForge.EVENT_BUS.register(new ClientInputHandler());
        NeoForge.EVENT_BUS.addListener(top.hmjmfabc.projector.client.gui.ProjectorHud::onRenderGui);
        NeoForge.EVENT_BUS.addListener(this::onLogin);
        NeoForge.EVENT_BUS.addListener(this::onLogout);
        // 【27.1.1】音乐控件的每 tick 调度（该不该出声、续播、音量跟随）
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientTickEvent.Post event) ->
                top.hmjmfabc.projector.client.music.MusicManager.tick());
    }

    private void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            LocalMedia.ensureDirectories();
            registerMediaProber();
            FontManager.loadBuiltins();
            registerWhiteTexture();
            verifyAtlasTexture();
            Projector.LOGGER.info("[Projector] 客户端就绪（字体 {} 个）", FontManager.count());
        });
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
                boolean video = name.endsWith(".mjpg") || name.endsWith(".mjpeg")
                        || name.endsWith(".avi") || name.endsWith(".mov")
                        || name.endsWith(".mp4") || name.endsWith(".webm")
                        || name.endsWith(".zip");
                if (video) {
                    var src = top.hmjmfabc.projector.client.media.VideoSource.open(path);
                    if (src == null) {
                        return top.hmjmfabc.projector.common.platform.MediaMeta.empty();
                    }
                    var meta = new top.hmjmfabc.projector.common.platform.MediaMeta(
                            src.width, src.height, src.frameCount, 10);
                    src.close();
                    return meta;
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

    private void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        watchdog();
        try {
            // 图集在上一帧分配过字形，这里把改动上传到 GPU。
            FontManager.uploadDirty();
            long tRender = System.nanoTime();
            PLANE_RENDERER.render(event.getPoseStack(), event.getCamera(),
                    event.getPartialTick().getGameTimeDeltaPartialTick(false));
            SELECTION_RENDERER.render(event.getPoseStack(), event.getCamera(),
                    event.getPartialTick().getGameTimeDeltaPartialTick(false));
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
    }

    private void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        // 【27.1.1】退出世界必须停音乐并清缓存：否则声道会被一直占着，下一局还在响
        top.hmjmfabc.projector.client.music.MusicManager.clearAll();
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

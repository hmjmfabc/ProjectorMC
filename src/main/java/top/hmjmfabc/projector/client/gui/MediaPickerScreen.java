package top.hmjmfabc.projector.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import top.hmjmfabc.projector.client.media.LocalMedia;
import top.hmjmfabc.projector.client.media.MediaCache;
import top.hmjmfabc.projector.client.media.MediaUploader;
import top.hmjmfabc.projector.common.platform.MediaMeta;
import top.hmjmfabc.projector.common.widget.ImageWidget;
import top.hmjmfabc.projector.common.widget.VideoWidget;
import top.hmjmfabc.projector.common.widget.Widget;

import java.util.List;

/**
 * 图片 / 视频选择器。
 *
 * <p>列出 {@code .minecraft/projector/media/} 下的文件（也兼容设计稿里提到的
 * {@code .minecraft/versions/<版本>/medias/} 目录）。选中后点击「确认」：
 * 单人存档直接写入；服务器上分块上传给服务端，普通玩家也能传（配额 4 MB / 64 MB，
 * 管理员 16 MB / 256 MB；服务端可用 media.onlyAdminCanUpload 锁回管理员）。</p>
 */
public class MediaPickerScreen extends ProjectorScreen {

    private final Screen parent;
    private final top.hmjmfabc.projector.common.Plane plane;
    private final boolean video;
    private final Widget target;

    @Nullable
    private ScrollList<LocalMedia.MediaFile> list;
    @Nullable
    private LocalMedia.MediaFile selected;
    private String status = "";
    /** 【②】是否勾选了「原画上传」（仅在服务端启用该配置项时才有意义）。 */
    private boolean originalQuality;

    public MediaPickerScreen(Screen parent, top.hmjmfabc.projector.common.Plane plane,
                             boolean video, Widget target) {
        super(Component.literal(video ? "\u9009\u62e9\u89c6\u9891" : "\u9009\u62e9\u56fe\u7247"));
        this.parent = parent;
        this.plane = plane;
        this.video = video;
        this.target = target;
    }

    @Override
    protected void init() {
        LocalMedia.rescan();
        List<LocalMedia.MediaFile> files = LocalMedia.byKind(video);

        int pad = 12;
        int listW = Math.min(320, this.width / 3);
        int listH = this.height - pad * 2 - 40;

        ScrollList<LocalMedia.MediaFile> l = new ScrollList<>(pad, pad + 12, listW, listH,
                LocalMedia.MediaFile::name,
                f -> (f.video() ? "\u89c6\u9891  " : "\u56fe\u7247  ") + humanSize(f.size()),
                (f, idx) -> {
                    selected = f;
                    // 解不了的文件提前说一声，并指出转换按钮在哪（不涉及任何后端名词）
                    status = needsConvert(f)
                            ? "\u8fd9\u4e2a\u89c6\u9891\u76f4\u63a5\u653e\u4e0d\u4e86\uff0c"
                              + "\u53ef\u4ee5\u7528\u53f3\u4fa7\u7684\u300c\u89c6\u9891\u683c\u5f0f\u8f6c\u6362\u2026\u300d"
                            : "";
                });
        l.setItems(files);
        this.list = addRenderableWidget(l);

        int bx = pad + listW + 8;
        int bw = this.width - bx - pad;
        button("\u786e\u8ba4", bx, this.height - pad - 24, Math.max(60, bw / 2 - 4), 20, b -> confirm());
        button("\u8fd4\u56de", bx + Math.max(60, bw / 2 - 4) + 8, this.height - pad - 24,
                Math.max(60, bw / 2 - 4), 20, b -> Minecraft.getInstance().setScreen(parent));
        button("\u5237\u65b0\u5217\u8868", bx, this.height - pad - 48, Math.max(60, bw), 20, b -> {
            LocalMedia.rescan();
            if (list != null) list.setItems(LocalMedia.byKind(video));
        });
        button("\u6253\u5f00\u7d20\u6750\u76ee\u5f55", bx, this.height - pad - 72, Math.max(60, bw), 20, b -> {
            LocalMedia.ensureDirectories();
            status = LocalMedia.mediaDir().toString();
        });
        // 视频格式转换：直接放不了的格式，在这里转成能放的（判定见 needsConvert）
        button("\u89c6\u9891\u683c\u5f0f\u8f6c\u6362\u2026", bx, this.height - pad - 96, Math.max(60, bw), 20, b -> {            if (selected == null) {
                status = "\u8bf7\u5148\u5728\u5de6\u4fa7\u9009\u4e2d\u4e00\u4e2a\u89c6\u9891\u6587\u4ef6";
                return;
            }
            Minecraft.getInstance().setScreen(new ConvertScreen(this, selected.path(), () -> {
                if (list != null) list.setItems(LocalMedia.byKind(video));
                status = "\u8f6c\u6362\u5b8c\u6210\uff0c\u5df2\u5237\u65b0\u5217\u8868";
            }));
        }).active = video;

        // 【②】「原画上传」勾选框：只有服务端启用了该配置项时才能勾。
        // 服务端没开时把它显示成灰色并说明原因，免得玩家以为是坏的。
        boolean serverAllows = top.hmjmfabc.projector.client.ClientServerInfo.allowOriginalUpload();
        var orig = button((originalQuality ? "\u2611 " : "\u2610 ")
                        + "\u539f\u753b\u4e0a\u4f20\uff08\u4e0d\u538b\u753b\u8d28\uff09",
                bx, this.height - pad - 120, Math.max(60, bw), 20, b -> {
                    originalQuality = !originalQuality;
                    rebuildWidgets();
                });
        orig.active = video && serverAllows;
    }

    /**
     * 该文件是不是「本模组直接放不了、需要先转换」的格式。
     *
     * <p>判据只有一条：{@link MediaUploader#probeVideo} 认不认它
     * （内置能解，或者外部解码器能放，都算认）。</p>
     */
    private static boolean needsConvert(LocalMedia.MediaFile f) {
        if (f == null || !f.video()) return false;
        if (MediaUploader.externalVideo(f.path())) {
            return false;      // 能直接放：不必转、也不必抽帧压缩
        }
        return MediaUploader.probeVideo(f.path()) == null;
    }

    private void confirm() {
        final LocalMedia.MediaFile picked = selected;
        if (picked == null) {
            status = "\u8bf7\u5148\u5728\u5de6\u4fa7\u9009\u4e2d\u4e00\u4e2a\u6587\u4ef6";
            return;
        }
        // 权限校验：默认放行（普通玩家也能上传，只是配额更小 4 MB / 64 MB）；
        // 只有服务端把 media.onlyAdminCanUpload 打开时才需要权限等级 4。
        if (!MediaUploader.canUpload()) {
            status = "\u670d\u52a1\u5668\u5df2\u9650\u5236\uff1a\u4ec5\u6743\u9650\u7b49\u7ea7 4 \u7684 OP \u53ef\u4ee5\u6dfb\u52a0\u56fe\u7247/\u89c6\u9891"
                    + "\uff08\u53ef\u5728\u670d\u52a1\u7aef media.onlyAdminCanUpload \u5173\u6389\uff09";
            return;
        }
        status = "\u6b63\u5728\u51c6\u5907\u2026";
        final boolean isVideo = video;
        final boolean original = originalQuality;
        // 【②】整个「配额检查 -> 必要时抽帧压缩 -> 算哈希 -> 探测尺寸 -> 上传」
        // 都放在工作线程：几十 MB 的文件在渲染线程上读两遍，Android 上就是一次 ANR。
        // 注意顺序：**先 prepare 再读文件**，这样哈希/尺寸/本地纹理与真正发出去的
        // 那一份永远是同一个文件（否则控件记录的哈希会指向压缩前的旧文件，永远加载不出来）。
        // 【rc-79】提交时就留一行日志：下次若「卡在正在准备…」，日志里能立刻区分
        // 「任务根本没跑起来（线程被占）」还是「任务跑了但卡在中间」。
        top.hmjmfabc.projector.Projector.LOGGER.info(
                "[Projector][客户端][上传] 已提交后台任务 文件={} 视频={} 原画={}",
                picked.name(), isVideo, original);
        MediaUploader.worker().execute(() -> {
            MediaUploader.Prepared prep = MediaUploader.prepare(picked.path(), isVideo, original);
            if (!prep.ok()) {
                Minecraft.getInstance().execute(() -> status = prep.message());
                return;
            }
            final java.nio.file.Path sendPath = prep.path();
            byte[] data;
            try {
                data = java.nio.file.Files.readAllBytes(sendPath);
            } catch (Exception ex) {
                Minecraft.getInstance().execute(() -> status = "\u8bfb\u53d6\u6587\u4ef6\u5931\u8d25");
                return;
            }
            final String hash = LocalMedia.sha1(data);
            int width;
            int height;
            int frames = 1;
            long[] frameTable = new long[0];
            if (isVideo) {
                MediaUploader.VideoMeta meta = MediaUploader.probeVideo(sendPath);
                if (meta == null) {
                    Minecraft.getInstance().execute(() -> status =
                            "\u8fd9\u4e2a\u89c6\u9891\u6253\u4e0d\u5f00\uff08\u6587\u4ef6\u53ef\u80fd"
                            + "\u5df2\u635f\u574f\uff0c\u6216\u683c\u5f0f\u4e0d\u652f\u6301\uff09");
                    return;
                }
                width = meta.width();
                height = meta.height();
                frames = Math.max(1, meta.frames());
                frameTable = meta.frameTable();
            } else {
                int[] sz = top.hmjmfabc.projector.client.media.ImageCodec.size(data, 0, data.length);
                if (sz == null) {
                    Minecraft.getInstance().execute(() -> status = "\u65e0\u6cd5\u89e3\u7801\u8be5\u56fe\u7247");
                    return;
                }
                width = sz[0];
                height = sz[1];
            }
            // 先让本地就绪，玩家不必等服务端往返。
            // 交给外部解码器放的格式没有内置帧索引，只登记文件本身
            //（走 finish 会因为它「不是 MJPEG/ZIP」而被标成解码失败）。
            if (isVideo && MediaUploader.externalVideo(sendPath)) {
                MediaCache.registerExternal(hash, sendPath);
            } else {
                MediaCache.finish(hash, data, sendPath, isVideo, 0);
            }
            final int fW = width, fH = height, fFrames = frames;
            final long[] fTable = frameTable;
            final String displayName = picked.name();
            // 【P0 修复 rc-76】sendPrepared 是**阻塞式**发送循环（分片数 × 节奏 + 真实上行带宽，
            // 实测 24 MB 要 15~20 秒）。以前它被塞进 Minecraft.execute(...) ⇒ **冻结客户端主线程**
            // ⇒ 答不上服务端的 keep-alive ⇒ 服务器按 30 秒判超时踢人
            //（服务端日志：`lost connection: Timed out`，且那两次上传都没有「完成」行），
            // 于是表现为「大视频上传有概率被踢」「第一次添加大概率不成功、点替换视频偶尔能成」。
            // 现在：发送留在**工作线程**（PacketDistributor 走 netty channel，非主线程调用安全），
            // 只有状态回调切回主线程（sendPrepared 内部已用 Minecraft.execute 包装）。
            Minecraft.getInstance().execute(() -> status = "\u6b63\u5728\u4e0a\u4f20\u2026");
            MediaUploader.sendPrepared(sendPath, displayName, isVideo, data,
                    fW, fH, fFrames, 10, fTable, new MediaUploader.Callback() {
                        @Override
                        public void onProgress(float fraction, String message) {
                            status = message;
                        }

                        @Override
                        public void onDone(boolean success, String message) {
                            if (!success) {
                                status = message;
                                return;
                            }
                            applyMedia(hash, displayName, fW, fH);
                        }
                    });
        });
    }

    private void applyMedia(String hash, String name, int w, int h) {
        if (target instanceof ImageWidget img) {
            img.mediaId = hash;
            img.mediaName = name;
            img.srcW = w;
            img.srcH = h;
            img.u0 = 0;
            img.v0 = 0;
            img.u1 = 1;
            img.v1 = 1;
            // 一律按「素材真实宽高比」重算尺寸，而不是只在 w/h 未初始化时才算：
            // 新建控件时的默认 64x64 是正方形，套在竖图/宽图上会明显变形。
            // 目标宽度取「画布短边的 1/3」，这样在小平面和大平面上都有合适的存在感
            //（固定 64 单位放在 720 宽的平面上只占 9%，预览里几乎看不见）。
            double aspect = h <= 0 ? 1 : (double) w / h;
            double target = Math.max(24, Math.min(plane.width, plane.height) / 3.0);
            if (aspect >= 1) {
                img.w = target;
                img.h = target / Math.max(0.05, aspect);
            } else {
                img.h = target;
                img.w = target * aspect;
            }
        } else if (target instanceof VideoWidget vid) {
            vid.mediaId = hash;
            vid.mediaName = name;
            vid.srcW = w;
            vid.srcH = h;
            MediaMeta meta = MediaMeta.probe(selected == null ? null : selected.path(), video);
            vid.frameCount = Math.max(1, meta.frames());
            vid.fps = 10;
            vid.startTimeMs = 0;
            double vAspect = meta.width() <= 0 ? 1 : (double) meta.width() / Math.max(1, meta.height());
            double vTarget = Math.max(24, Math.min(plane.width, plane.height) / 3.0);
            if (vAspect >= 1) {
                vid.w = vTarget;
                vid.h = vTarget / Math.max(0.05, vAspect);
            } else {
                vid.h = vTarget;
                vid.w = vTarget * vAspect;
            }
        }
        CompoundTag t = new CompoundTag();
        t.putUUID("widget", target.id);
        // 控件数据用独立的键，避免覆盖上面的 UUID（否则媒体的哈希永远存不到服务端）
        t.put("data", target.save());
        // 控件在「新增控件」阶段已经建立，这里只是补上媒体信息，因此用 update
        PlaneDialogScreen.sendFor(plane, "updateWidget", t);
        // 上传是异步的，可能数秒后才完成；只有玩家还停在本界面时才自动跳转，
        // 否则会把编辑器强行顶到玩家当前的界面（甚至游戏画面上）。
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == this) {
            mc.setScreen(new WidgetEditorScreen(plane, target, parent));
        }
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        super.render(gfx, mouseX, mouseY, partialTick);
        int pad = 12;
        labelShadow(gfx, video ? "\u9009\u62e9\u89c6\u9891" : "\u9009\u62e9\u56fe\u7247", pad, pad, TEXT_ACCENT);
        if (list != null) {
            label(gfx, "\u76ee\u5f55\uff1a" + LocalMedia.mediaDir(), list.getX(), list.getY() - 10, TEXT_DIM);
            gfx.fill(list.getX(), list.getY() - 2, list.getX() + list.getWidth(), list.getY(), PANEL_BORDER);
        }
        if (selected != null) {
            int bx = pad + Math.min(320, this.width / 3) + 8;
            label(gfx, "\u5df2\u9009\uff1a" + selected.name(), bx, pad + 16, TEXT_GREEN);
            label(gfx, humanSize(selected.size()), bx, pad + 28, TEXT_DIM);
        }
        if (!status.isEmpty()) {
            label(gfx, status, pad + 4, this.height - pad - 84, TEXT_ACCENT);
        }
    }

    @Override
    public void onClose() {
        // 这个控件是在「新增控件」阶段就先建好的；用户直接返回时把它删掉，
        // 避免平面上残留一个永远没有媒体的空图片/视频控件。
        boolean empty = (target instanceof ImageWidget iw && (iw.mediaId == null || iw.mediaId.isEmpty()))
                || (target instanceof VideoWidget vw && (vw.mediaId == null || vw.mediaId.isEmpty()));
        if (empty) {
            CompoundTag t = new CompoundTag();
            t.putUUID("widget", target.id);
            PlaneDialogScreen.sendFor(plane, "removeWidget", t);
        }
        Minecraft.getInstance().setScreen(parent);
    }
}

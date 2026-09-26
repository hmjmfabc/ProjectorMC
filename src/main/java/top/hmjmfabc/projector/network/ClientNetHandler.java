package top.hmjmfabc.projector.network;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import top.hmjmfabc.projector.Projector;
import top.hmjmfabc.projector.client.PlaneCache;
import top.hmjmfabc.projector.client.SelectionState;
import top.hmjmfabc.projector.client.gui.PlaneDialogScreen;
import top.hmjmfabc.projector.client.media.MediaCache;
import top.hmjmfabc.projector.common.Plane;

/**
 * 所有「服务端 → 客户端」包的处理逻辑。
 *
 * <p>全部通过 {@code ctx.enqueueWork} 切回主线程执行，
 * 避免在网络线程里碰渲染与界面对象。</p>
 */
public final class ClientNetHandler {

    private ClientNetHandler() {
    }

    /** 平面数据全量/增量同步。 */
    public static void onSyncPlanes(Payloads.SyncPlanes payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (payload.data() == null) return;
            PlaneCache.handleSync(payload.dimension(), payload.data());

            // 只有「刚刚发起过圈选、正在等待服务端确认」时才自动改选，
            // 否则任何一次无关的同步包都可能把选中状态改到别的平面上。
            if (SelectionState.isPendingCapture()) {
                var pos = SelectionState.lastPickPos();
                var face = SelectionState.lastPickFace();
                if (pos != null && face != null) {
                    Plane found = PlaneCache.findAt(payload.dimension(), pos, face);
                    if (found != null) {
                        SelectionState.select(found);
                    }
                }
            }
            // 选中的平面已经不存在（被删除 / 切维度）时清空选中状态
            if (SelectionState.hasSelection() && SelectionState.plane() == null) {
                SelectionState.clear();
            }
        });
    }

    /** 打开平面对话框。 */
    public static void onOpenDialog(Payloads.OpenDialog payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            Plane plane = PlaneCache.byId(payload.planeId());
            if (plane == null) {
                Projector.LOGGER.warn("[Projector] 收到打开对话框请求，但本地没有该平面: {}", payload.planeId());
                return;
            }
            SelectionState.select(plane);
            if (mc.screen == null) {
                mc.setScreen(new PlaneDialogScreen(plane));
            }
        });
    }

    /** 服务端下发的媒体数据分片。 */
    public static void onMediaData(Payloads.MediaData payload, IPayloadContext ctx) {
        // 【rc-82】记住服务端声明的真实帧数：客户端的帧号越界时就不必再去问
        //（以前会一直问不存在的帧，服务端一直回「没有」，两边一起刷屏）
        if (payload.video()) {
            MediaCache.noteFrameCount(payload.hash(), payload.frameCount());
        }
        // 写文件与解码放在工作线程里做，避免卡住主线程
        MediaCache.onMediaChunk(payload.hash(), payload.frame(), payload.offset(), payload.total(),
                payload.video(), payload.data(), payload.last(), payload.missing());
    }

    /**
     * 【①②⑫】服务端环境清单：媒体哈希表 + 字体清单 + 各种开关。
     *
     * <p>收到最后一片之后才启动后台预缓存——否则队列会按「不完整的清单」
     * 排一遍，等于白跑一轮。</p>
     */
    public static void onServerInfo(Payloads.ServerInfo payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (payload.data() == null) return;
            top.hmjmfabc.projector.client.ClientServerInfo.accept(payload.data(), payload.last());
            if (payload.last()) {
                var info = top.hmjmfabc.projector.client.ClientServerInfo.media();
                Projector.LOGGER.info("[Projector] 收到服务端清单：{} 条媒体 / {} 个字体 / 原画上传={} / 后台缓存视频={} / 当日流量已用={}",
                        info.size(),
                        top.hmjmfabc.projector.client.ClientServerInfo.fonts().size(),
                        top.hmjmfabc.projector.client.ClientServerInfo.allowOriginalUpload(),
                        top.hmjmfabc.projector.client.ClientServerInfo.allowBackgroundVideoCache(),
                        top.hmjmfabc.projector.client.ClientServerInfo.dailyUsed());
                // 【⑫】按档位开始后台缓存（保守档会直接返回）
                try {
                    top.hmjmfabc.projector.client.media.CachePrefetcher.onServerInfo();
                } catch (Throwable t) {
                    Projector.LOGGER.warn("[Projector] 启动后台预缓存失败：{}", t.toString());
                }
            }
        });
    }

    /** 【①】服务端确认字体已保存并核验通过。 */
    /**
     * 【rc-77】上传分片确认：交给 {@code MediaUploader} 的流控窗口。
     *
     * <p>注意这里**不能** {@code enqueueWork} 到主线程再处理：上传线程正在等这个确认，
     * 而主线程可能正忙（渲染/界面），确认晚到只会平白拉低速度。状态本身是并发的。</p>
     */
    public static void onMediaAck(Payloads.MediaAck payload, IPayloadContext ctx) {
        top.hmjmfabc.projector.client.media.MediaUploader.onAck(
                payload.session(), payload.received(), payload.done(),
                payload.alreadyHave(), payload.reason());
    }

    public static void onFontStored(Payloads.FontStored payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (payload.ok()) {
                Projector.LOGGER.info("[Projector] 字体已上传并核验通过：{}（{}，sha1={}）",
                        payload.fileName(), payload.fontId(), payload.sha1());
            } else {
                Projector.LOGGER.warn("[Projector] 字体上传失败：{}（{}）", payload.fileName(), payload.message());
            }
        });
    }
}

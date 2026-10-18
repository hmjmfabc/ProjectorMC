package top.hmjmfabc.projector.compat.neo.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import top.hmjmfabc.projector.Projector;

/**
 * 【Fabric 移植层】{@code top.hmjmfabc.projector.compat.neo.network.PacketDistributor} 的替身。
 *
 * <h2>为什么要绕一层（dist 隔离）</h2>
 * <p>本类是<b>公共类</b>（服务端处理器与客户端都 import 它）。"发给服务端"在 Fabric 上只有
 * 客户端专有的 {@code ClientPlayNetworking.send} 能做 —— 如果这里直接引用它，
 * 专用服务端加载本类时就会碰到客户端类（本项目在 27.1-rc-73 已经因为同类问题起不来过，
 * 见 AGENTS.md §2.3）。所以：</p>
 * <ul>
 *   <li>{@link #sendToPlayer}：直接用 {@code ServerPlayNetworking.send}（两端都有）；</li>
 *   <li>{@link #sendToServer}：走一个由<b>客户端入口</b>安装进来的函数引用，
 *       专用服务端上它永远是 null ⇒ 真的被调用就说明 dist 判定出了问题，必须留痕。</li>
 * </ul>
 *
 * <p>⚠ 与原版一致：这两个方法<b>不要求在主线程调用</b>（上传/下载都从工作线程发包，
 * 见 {@code UploadWindow} 的说明）。</p>
 */
public final class PacketDistributor {

    /** 客户端 → 服务端的发送器（只在物理客户端上安装）。 */
    public interface ToServerSender {
        void send(CustomPacketPayload payload);
    }

    private static volatile ToServerSender serverSender;

    private PacketDistributor() {
    }

    /** 由客户端入口安装（物理客户端每次启动只调一次）。 */
    public static void installServerSender(ToServerSender sender) {
        serverSender = sender;
    }

    /** 服务端 → 某个玩家。 */
    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        if (player == null || payload == null) return;
        try {
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, payload);
        } catch (Throwable t) {
            // 玩家刚掉线 / 通道未就绪：丢掉这个包即可，绝不能拖垮服务端 tick
            Projector.LOGGER.warn("[Projector] 向玩家 {} 发送 {} 失败：{}",
                    player.getName().getString(), payload.type().id(), String.valueOf(t));
        }
    }

    /** 客户端 → 服务端。 */
    public static void sendToServer(CustomPacketPayload payload) {
        if (payload == null) return;
        ToServerSender sender = serverSender;
        if (sender == null) {
            Projector.LOGGER.warn("[Projector] 试图在非客户端环境发送 {}（已忽略）", payload.type().id());
            return;
        }
        try {
            sender.send(payload);
        } catch (Throwable t) {
            // 连接已断开（登出瞬间仍可能有工作线程在发包），丢掉即可
            Projector.LOGGER.debug("[Projector] 发送 {} 失败（连接可能已关闭）：{}",
                    payload.type().id(), String.valueOf(t));
        }
    }
}

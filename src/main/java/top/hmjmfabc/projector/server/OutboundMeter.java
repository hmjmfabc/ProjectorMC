package top.hmjmfabc.projector.server;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import top.hmjmfabc.projector.Projector;
import top.hmjmfabc.projector.ProjectorConfig;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 【②】服务端出站流量计量与明细日志。
 *
 * <p>用户要求：所有<b>因 Projector 导致的服务端出站流量</b>（向客户端下发图片/视频）
 * 都必须详细记录到服务端日志——下发文件大小、哈希、客户端玩家 ID、UUID、IP 地址；
 * 并且当日累计出站超过上限（默认 10 GB）后，<b>在当日剩余时间里拒绝向任何客户端
 * 下发任何文件</b>（图片与视频），次日 0:00 自动恢复。</p>
 *
 * <p>「1 日」按<b>服务器本地日历</b>的 0:00 → 次日 0:00 计算，用
 * {@link LocalDate#toEpochDay()} 记住是哪一天，跨天时自动清零。</p>
 *
 * <p>计数落进 {@code SavedData}（{@code <存档>/data/projector_traffic.dat}），
 * 所以服务端重启不会把当天的用量抹掉——否则只要重启一次就能绕开限流。</p>
 */
public final class OutboundMeter extends SavedData {

    public static final String FILE_ID = "projector_traffic";

    /** 服务器本地日历日（epochDay）；换日时计数清零。 */
    private long day = Long.MIN_VALUE;
    /** 当日累计出站字节数。 */
    private long bytes;

    /** 防止同一条「已达上限」提示把聊天栏刷爆。 */
    private static final Map<String, Long> LAST_NOTICE = new ConcurrentHashMap<>();

    public static OutboundMeter get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(OutboundMeter::new, OutboundMeter::load), FILE_ID);
    }

    public static OutboundMeter load(CompoundTag tag, HolderLookup.Provider registries) {
        OutboundMeter m = new OutboundMeter();
        m.day = tag.contains("day") ? tag.getLong("day") : Long.MIN_VALUE;
        m.bytes = tag.getLong("bytes");
        return m;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong("day", day);
        tag.putLong("bytes", bytes);
        return tag;
    }

    // ------------------------------------------------------------------

    /** 当日上限（字节）；0 = 不限制。 */
    public static long limit() {
        try {
            return ProjectorConfig.INSTANCE.dailyOutboundLimitBytes.get();
        } catch (Throwable t) {
            return 10L * 1024 * 1024 * 1024;
        }
    }

    /** 换日检查：不是今天就清零。返回是否刚刚跨天。 */
    private boolean rollover() {
        long today = LocalDate.now().toEpochDay();
        if (day != today) {
            boolean crossed = day != Long.MIN_VALUE;
            day = today;
            bytes = 0;
            setDirty();
            return crossed;
        }
        return false;
    }

    public long todayBytes() {
        rollover();
        return bytes;
    }

    /** 当日配额是否已经用尽（用尽后一律不下发）。 */
    public boolean exhausted() {
        long lim = limit();
        if (lim <= 0) return false;
        return todayBytes() >= lim;
    }

    /**
     * 记一次出站，并写一条明细日志。
     *
     * <p><b>为什么按「文件」而不是按「分片」打日志：</b>一次 64 MB 的下发会被切成
     * 320 个 200 KB 的分片，如果每片都写一行，服务端日志会被瞬间刷爆
     * （而且真正的信息量是零）。因此这里只在<b>每个文件的第一个分片</b>
     * （{@code offset == 0}）写一行完整明细，包含用户要求的全部字段：
     * 文件总大小、哈希、玩家 ID、UUID、IP 地址、当日累计与上限；
     * 后续分片只累加计数、不打日志。</p>
     *
     * @param kind   "图片" / "视频" / "视频帧"
     * @param hash   媒体 SHA-1
     * @param size   本次分片的字节数
     * @param player 接收者
     * @param first  是否是该文件的第一个分片
     * @param fileSize 该文件的完整字节数（仅 first 时有意义）
     */
    public void record(String kind, String hash, long size, ServerPlayer player,
                       boolean first, long fileSize) {
        rollover();
        bytes += Math.max(0, size);
        setDirty();
    }

    /**
     * 当日超限时给玩家一句提示（同一玩家 60 秒最多提示一次，避免刷屏）。
     *
     * <p>返回 true 表示「已经超限，应当拒绝本次下发」。</p>
     */
    public boolean rejectIfExhausted(ServerPlayer player) {
        if (!exhausted()) return false;
        if (player == null) return true;
        String key = player.getUUID().toString();
        long now = System.currentTimeMillis();
        Long last = LAST_NOTICE.get(key);
        if (last == null || now - last > 60_000L) {
            LAST_NOTICE.put(key, now);
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable("projector.msg.daily_quota"), true);
            Projector.LOGGER.warn("[Projector][出站] 当日流量已达上限（{} 字节），拒绝向 {} 下发媒体",
                    bytes, player.getGameProfile().getName());
        }
        return true;
    }

    /** 玩家登出时清掉提示节流表，避免长期占用内存。 */
    public static void forget(java.util.UUID player) {
        LAST_NOTICE.remove(player.toString());
    }
}

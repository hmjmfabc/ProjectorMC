package top.hmjmfabc.projector.server;

import net.minecraft.server.level.ServerPlayer;
import top.hmjmfabc.projector.Projector;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 【②】服务端媒体的每一次**上传 / 下发**明细日志。
 *
 * <p>用户要求：<b>每一次由 Projector 导致的媒体文件上传/下载都要详细记录</b>，
 * 服务端与客户端两侧都要有。这个类负责服务端侧，客户端侧见
 * {@code top.hmjmfabc.projector.client.media.TransferLog}。</p>
 *
 * <h2>为什么按「文件」而不是按「分片」打一行</h2>
 * <p>一次 64 MB 的下发会被切成 320 个 200 KB 分片，逐片打日志会瞬间刷爆日志、
 * 而且信息量为零（AGENTS.md §5.5 第 29 条）。所以：</p>
 * <ul>
 *   <li><b>上传</b>：会话建立时一行「开始」、结束时一行「完成 / 失败」——每个文件两行；</li>
 *   <li><b>下发</b>：一份媒体（同一玩家 + 同一哈希）的一次连续下发，
 *       开头一行「开始」、之后累计分片，最后结算一行「合计」
 *       （空闲超过 {@link #IDLE_MS} 毫秒、玩家登出、服务端关闭时结算）。
 *       视频逐帧播放会被合并成一条「共 N 帧 / M 字节」，不会一帧一行。</li>
 * </ul>
 *
 * <p>日志行统一以 {@code [Projector][服务端][上传]} / {@code [Projector][服务端][下发]}
 * 开头，方便一条 grep 取全部传输记录（旧版的 {@code [Projector][出站]} 已并入下发，
 * 只保留「当日超限」那条警告）。</p>
 */
public final class TransferLog {

    /** 同一份媒体的两次下发间隔超过它，就结算一行「合计」。 */
    private static final long IDLE_MS = 2000L;

    /** 正在进行中的下发会话：key = 玩家 UUID + "|" + 哈希。 */
    private static final Map<String, Dl> ACTIVE = new ConcurrentHashMap<>();

    /** 当日上传累计（仅用于日志展示，不参与任何限制）。 */
    private static long uploadDay = Long.MIN_VALUE;
    private static long uploadBytes;

    private TransferLog() {
    }

    /** 一次「连续下发」的累计状态。 */
    private static final class Dl {
        final ServerPlayer player;
        final String hash;
        final String name;
        final long fileSize;
        final boolean video;
        long bytes;
        int chunks;
        int frames;
        long firstMs;
        long lastMs;
        /** 「开始」那行是否已经打过（同一次连续下发只打一行）。 */
        boolean announced;

        Dl(ServerPlayer player, String hash, String name, long fileSize, boolean video) {
            this.player = player;
            this.hash = hash;
            this.name = name;
            this.fileSize = fileSize;
            this.video = video;
            this.firstMs = System.currentTimeMillis();
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static String nameOf(ServerPlayer p) {
        return p == null ? "-" : p.getGameProfile().getName();
    }

    private static String uuidOf(ServerPlayer p) {
        return p == null ? "-" : p.getUUID().toString();
    }

    private static String ipOf(ServerPlayer p) {
        if (p == null) return "-";
        try {
            return p.getIpAddress();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 下发明细是否写日志（沿用既有配置项，默认开）。 */
    private static boolean outboundLogging() {
        try {
            return top.hmjmfabc.projector.ProjectorConfig.INSTANCE.logOutboundTransfers.get();
        } catch (Throwable t) {
            return true;
        }
    }

    /** 人话速率：{@code 1.62MB/s}。 */
    private static String rateText(long bytes, long ms) {
        if (ms <= 0 || bytes <= 0) return "-";
        double perSec = bytes * 1000.0 / ms;
        if (perSec < 1024) return String.format(java.util.Locale.ROOT, "%.0fB/s", perSec);
        if (perSec < 1048576) return String.format(java.util.Locale.ROOT, "%.1fKB/s", perSec / 1024.0);
        return String.format(java.util.Locale.ROOT, "%.2fMB/s", perSec / 1048576.0);
    }

    /**
     * 【rc-76】上传 / 下发的**哈希校验**结果。
     *
     * <p>上传：服务端把收到的字节重新算一遍 SHA-1，与客户端声明的哈希比对——
     * 这是「内容有没有传坏」的唯一权威判定（以前直接信任客户端声明）。
     * 不一致的内容<b>不会入库</b>，玩家会收到失败提示并能在日志里看到两个哈希。</p>
     */
    public static void hashCheck(ServerPlayer player, String phase, String declared, String actual,
                                 boolean ok, long size) {
        if (ok) {
            Projector.LOGGER.info(
                    "[Projector][服务端][{}] 哈希校验 结果=一致 ✅ 声明={} 实算={} 大小={} 玩家={} UUID={} IP={}",
                    phase, declared, actual, sizeText(size), nameOf(player), uuidOf(player), ipOf(player));
        } else {
            Projector.LOGGER.warn(
                    "[Projector][服务端][{}] 哈希校验 结果=不一致 ❌ 声明={} 实算={} 大小={}"
                            + "（内容与声明不符，已丢弃、不入库）玩家={} UUID={} IP={}",
                    phase, declared, actual == null ? "?" : actual, sizeText(size),
                    nameOf(player), uuidOf(player), ipOf(player));
        }
    }

    /** 字节数的可读写法：{@code 1.2MB(1258291字节)}，方便肉眼与脚本解析兼得。 */
    private static String sizeText(long bytes) {
        long b = Math.max(0, bytes);
        if (b < 1024) return b + "字节";
        if (b < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1fKB(%d字节)", b / 1024.0, b);
        return String.format(java.util.Locale.ROOT, "%.2fMB(%d字节)", b / 1048576.0, b);
    }

    private static void rolloverUploadDay() {
        long today = LocalDate.now().toEpochDay();
        if (uploadDay != today) {
            uploadDay = today;
            uploadBytes = 0;
        }
    }

    /** 当日上传累计（字节）。 */
    public static synchronized long todayUploadBytes() {
        rolloverUploadDay();
        return uploadBytes;
    }

    private static synchronized long addUpload(long bytes) {
        rolloverUploadDay();
        uploadBytes += Math.max(0, bytes);
        return uploadBytes;
    }

    // ------------------------------------------------------------------
    // 上传
    // ------------------------------------------------------------------

    /** 上传会话建立（收到第一个分片、并通过全部校验）。 */
    public static void uploadStart(ServerPlayer player, String hash, String fileName,
                                   long declaredSize, boolean video, long limit, int chunkBytes) {
        // chunkBytes <= 0 表示「单包直传」（例如字体上传），分片数就写 1
        String chunks = chunkBytes <= 0 ? "1（单包）"
                : String.valueOf((declaredSize + chunkBytes - 1) / chunkBytes);
        Projector.LOGGER.info(
                "[Projector][服务端][上传] 开始 玩家={} UUID={} IP={} 文件={} 哈希={} 大小={} 视频={} 分片≈{} 上限={}",
                nameOf(player), uuidOf(player), ipOf(player), fileName, hash, sizeText(declaredSize),
                video ? "是" : "否", chunks, limit <= 0 || limit == Long.MAX_VALUE ? "不限" : sizeText(limit));
    }

    /**
     * 【rc-77】重复上传：服务端已有同哈希且大小一致，已让客户端立刻停手。
     *
     * <p>这一行是「流量爆炸」的直接证据：每次重复都曾经白收一整份文件。</p>
     */
    public static void uploadSkip(ServerPlayer player, String hash, String fileName, long size) {
        Projector.LOGGER.info("[Projector][服务端][上传] 跳过 重复传输 玩家={} UUID={} IP={} 文件={}"
                        + " 哈希={} 大小={}（服务端已有同哈希，已通知客户端停止发送）",
                nameOf(player), uuidOf(player), ipOf(player), fileName, hash, sizeText(size));
    }

    /** 上传成功落盘。 */
    public static void uploadDone(ServerPlayer player, String hash, String fileName, long size,
                                  int chunks, long ms, boolean isNew, long limit) {
        long total = addUpload(size);
        Projector.LOGGER.info(
                "[Projector][服务端][上传] 完成 玩家={} UUID={} IP={} 文件={} 哈希={} 大小={} 分片={} 用时={}ms"
                        + " 新文件={} 速率={} 上限={} 当日上传累计={}",
                nameOf(player), uuidOf(player), ipOf(player), fileName, hash, sizeText(size), chunks, ms,
                isNew ? "是" : "否（服务端已有同哈希）", rateText(size, ms),
                limit <= 0 || limit == Long.MAX_VALUE ? "不限" : sizeText(limit), sizeText(total));
    }

    /**
     * 上传失败（或被拒绝）。
     *
     * <p>凡是服务端拒绝的路径<b>必须</b>留下这一行：静默 {@code return} 只会让玩家看到
     * 「没反应」，排查时无处下手（AGENTS.md §1.8 第 4 条）。</p>
     *
     * @param reason 中文原因，例如「超过配额」「分片越界」「分片不完整」
     */
    public static void uploadFail(ServerPlayer player, String reason, String hash, String fileName,
                                  long declaredSize, int receivedBytes) {
        Projector.LOGGER.info(
                "[Projector][服务端][上传] 失败 原因={} 玩家={} UUID={} IP={} 文件={} 哈希={} 声明大小={} 已收={}",
                reason, nameOf(player), uuidOf(player), ipOf(player),
                fileName == null || fileName.isEmpty() ? "-" : fileName,
                hash == null || hash.isEmpty() ? "-" : hash,
                sizeText(declaredSize), sizeText(receivedBytes));
    }

    // ------------------------------------------------------------------
    // 下发
    // ------------------------------------------------------------------

    /**
     * 记一次下发分片（并在合适的时候打「开始」/「合计」行）。
     *
     * @param kind      "图片" / "视频" / "视频帧"
     * @param fileSize  该文件的完整字节数
     * @param frame     帧号（整段/图片由调用方给值，只用于日志展示）
     * @param chunkSize 本次分片字节数
     */
    public static void download(ServerPlayer player, String kind, String hash, String fileName,
                                long fileSize, boolean video, int frame, int chunkSize) {
        // 下发明细沿用既有开关 media.logOutboundTransfers（默认开）；
        // 关掉它只是不打日志，出站字节照样计入限额（OutboundMeter.record 不受影响）。
        if (!outboundLogging()) return;
        flushIdle();   // 顺手结算已经凉了的其它会话，日志不会一直挂着
        String key = uuidOf(player) + "|" + hash;
        Dl dl = ACTIVE.computeIfAbsent(key, k -> new Dl(player, hash, fileName, fileSize, video));
        dl.bytes += Math.max(0, chunkSize);
        dl.chunks++;
        if (video && frame > 0) dl.frames++;
        dl.lastMs = System.currentTimeMillis();
        if (dl.announced) return;
        dl.announced = true;
        long lim = OutboundMeter.limit();
        long today = -1;
        try {
            today = OutboundMeter.get(player.getServer()).todayBytes();
        } catch (Throwable ignored) {
        }
        Projector.LOGGER.info(
                "[Projector][服务端][下发] 开始 类型={} 玩家={} UUID={} IP={} 文件={} 哈希={} 文件大小={}"
                        + " 起始帧={} 当日出站={} 上限={}",
                kind, nameOf(player), uuidOf(player), ipOf(player),
                fileName == null ? "-" : fileName, hash, sizeText(fileSize), frame,
                today < 0 ? "?" : sizeText(today), lim <= 0 ? "不限" : sizeText(lim));
    }

    /** 服务端没有这份媒体 / 当日配额用尽 / 读取失败 —— 下发的失败路径也要留痕。 */
    public static void downloadFail(ServerPlayer player, String reason, String hash, int frame) {
        if (!outboundLogging()) return;
        Projector.LOGGER.info(
                "[Projector][服务端][下发] 失败 原因={} 玩家={} UUID={} IP={} 哈希={} 帧={}",
                reason, nameOf(player), uuidOf(player), ipOf(player), hash, frame);
    }

    /**
     * 结算某个玩家名下所有会话（登出时调用）。
     *
     * <p>身份缺省（{@code null}）时按 {@code -} 处理，与该身份登记会话时用的前缀一致；
     * 生产路径永远是真实玩家，这里不特殊对待只是为了「登出结算」这条路径可被测试。</p>
     */
    public static void flush(ServerPlayer player) {
        String uuid = uuidOf(player);
        for (Map.Entry<String, Dl> e : ACTIVE.entrySet()) {
            if (e.getKey().startsWith(uuid + "|")) settle(e.getKey(), e.getValue(), "玩家登出");
        }
    }

    /**
     * 当前登记中的下发会话数（测试用）。
     *
     * <p>它守住的核心不变量是：<b>同一玩家 + 同一哈希的一次连续下发只登记一份</b>——
     * 否则「开始」那行会被每一个分片重复打印（就是日志被刷爆的老毛病）。</p>
     */
    public static int activeCount() {
        return ACTIVE.size();
    }

    /** 结算所有会话（服务端关闭时调用，保证日志完整）。 */
    public static void flushAll() {
        for (Map.Entry<String, Dl> e : ACTIVE.entrySet()) {
            settle(e.getKey(), e.getValue(), "服务端关闭");
        }
    }

    /** 结算空闲超过 {@link #IDLE_MS} 的会话。 */
    private static void flushIdle() {
        if (ACTIVE.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Dl> e : ACTIVE.entrySet()) {
            Dl d = e.getValue();
            if (now - d.lastMs >= IDLE_MS) settle(e.getKey(), d, "空闲");
        }
    }

    private static void settle(String key, Dl d, String why) {
        if (!ACTIVE.remove(key, d)) return;
        if (d.chunks <= 0) return;
        long today = -1;
        try {
            if (d.player != null && d.player.getServer() != null) {
                today = OutboundMeter.get(d.player.getServer()).todayBytes();
            }
        } catch (Throwable ignored) {
        }
        Projector.LOGGER.info(
                "[Projector][服务端][下发] 合计 类型={} 玩家={} UUID={} IP={} 文件={} 哈希={} 分片={} 帧数={}"
                        + " 共={} 用时={}ms 速率={} 结束原因={} 当日出站={}",
                d.video ? "视频" : "图片", nameOf(d.player), uuidOf(d.player), ipOf(d.player),
                d.name == null ? "-" : d.name, d.hash, d.chunks, d.frames, sizeText(d.bytes),
                Math.max(0, d.lastMs - d.firstMs), rateText(d.bytes, Math.max(0, d.lastMs - d.firstMs)),
                why, today < 0 ? "?" : sizeText(today));
    }
}

package top.hmjmfabc.projector.client.media;

import org.jetbrains.annotations.Nullable;
import top.hmjmfabc.projector.Projector;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 【②】客户端侧的媒体**上传 / 下载**明细日志。
 *
 * <p>用户要求：<b>客户端与服务端的每一次由 Projector 导致的媒体文件上传/下载都详细记录</b>。
 * 服务端侧见 {@code top.hmjmfabc.projector.server.TransferLog}，这里负责客户端侧：
 * 上传（把本机文件送到服务端）与下载（从服务端取回并写进缓存目录）。</p>
 *
 * <h2>粒度：一次操作一行</h2>
 * <ul>
 *   <li><b>上传</b>：开始一行、结束（完成/失败）一行 —— 含本机路径、显示名、哈希、大小、分片数、用时；</li>
 *   <li><b>下载</b>：开始一行（含触发原因：按需渲染 / 后台预缓存）、结束一行
 *       （含落盘路径、分片数、字节数、用时）；失败与重试各一行。
 *       分片只累加、不打日志（一次 64 MB 会切成几百片，逐片打会刷爆日志）。</li>
 * </ul>
 *
 * <p>日志统一以 {@code [Projector][客户端][上传]} / {@code [Projector][客户端][下载]} 开头，
 * 一条 grep 就能取出这台机器上的全部传输记录。</p>
 */
public final class TransferLog {

    /** 正在进行中的下载：key = 哈希 + "#" + 帧号。 */
    private static final Map<String, Dl> ACTIVE = new ConcurrentHashMap<>();

    private TransferLog() {
    }

    private static final class Dl {
        final String hash;
        final int frame;
        final String reason;
        int chunks;
        long bytes;
        /** 【rc-83】这次下载期间被抑制掉的重复请求次数（> 0 说明有调用方在反复要同一份媒体）。 */
        int suppressed;
        /** 【rc-83】上一次「重复请求被抑制」日志的时间（限流用）。 */
        long lastDupLogMs;
        final long startMs = System.currentTimeMillis();

        Dl(String hash, int frame, String reason) {
            this.hash = hash;
            this.frame = frame;
            this.reason = reason;
        }
    }

    private static String shortHash(String hash) {
        return hash == null ? "-" : (hash.length() > 8 ? hash.substring(0, 8) : hash);
    }

    private static String sizeText(long bytes) {
        long b = Math.max(0, bytes);
        if (b < 1024) return b + "字节";
        if (b < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1fKB(%d字节)", b / 1024.0, b);
        return String.format(java.util.Locale.ROOT, "%.2fMB(%d字节)", b / 1048576.0, b);
    }

    /** 人话速率：{@code 1.62MB/s}；用时为 0 时不显示。 */
    private static String rateText(long bytes, long ms) {
        if (ms <= 0 || bytes <= 0) return "-";
        double perSec = bytes * 1000.0 / ms;
        if (perSec < 1024) return String.format(java.util.Locale.ROOT, "%.0fB/s", perSec);
        if (perSec < 1048576) return String.format(java.util.Locale.ROOT, "%.1fKB/s", perSec / 1024.0);
        return String.format(java.util.Locale.ROOT, "%.2fMB/s", perSec / 1048576.0);
    }

    private static String key(String hash, int frame) {
        return hash + "#" + frame;
    }

    // ------------------------------------------------------------------
    // 上传
    // ------------------------------------------------------------------

    /** 上传开始（本机文件 → 服务端）。 */
    public static void uploadStart(@Nullable Path source, String displayName, String hash,
                                   long size, boolean video, int chunks) {
        Projector.LOGGER.info(
                "[Projector][客户端][上传] 开始 文件={} 本机路径={} 哈希={} 大小={} 视频={} 分片≈{}",
                displayName, source == null ? "-" : source.toAbsolutePath(), shortHash(hash),
                sizeText(size), video ? "是" : "否", chunks);
    }

    /**
     * 【rc-77】跳过上传（服务端已有同哈希 / 本次会话已传成功过）——**一个字节都没发**。
     *
     * <p>这是「爆上传」的正面对策：重复上传以前会把整份文件再传一遍
     * （实测同一份 24 MB 视频被传了 3 次 = 72 MB），现在直接跳过并留下这一行。</p>
     */
    public static void uploadSkip(String hash, long size, String reason) {
        Projector.LOGGER.info("[Projector][客户端][上传] 跳过 原因={} 哈希={} 大小={}（省下整份文件的流量）",
                reason, shortHash(hash), sizeText(size));
    }

    /** 上传结束（成功或失败）。 */
    public static void uploadEnd(String hash, long size, long ms, boolean ok, @Nullable String reason) {
        if (ok) {
            Projector.LOGGER.info("[Projector][客户端][上传] 完成 哈希={} 大小={} 用时={}ms",
                    shortHash(hash), sizeText(size), ms);
        } else {
            Projector.LOGGER.warn("[Projector][客户端][上传] 失败 原因={} 哈希={} 大小={} 用时={}ms",
                    reason == null ? "未知" : reason, shortHash(hash), sizeText(size), ms);
        }
    }

    // ------------------------------------------------------------------
    // 下载
    // ------------------------------------------------------------------

    /** 下载开始（服务端 → 本机缓存）。同一份媒体只打一次「开始」。 */
    public static void downloadStart(String reason, String hash, int frame, boolean video) {
        String k = key(hash, frame);
        Dl old = ACTIVE.putIfAbsent(k, new Dl(hash, frame, reason));
        if (old != null) return;
        Projector.LOGGER.info(
                "[Projector][客户端][下载] 开始 原因={} 哈希={} 帧={} 类型={}",
                reason, shortHash(hash), frame < 0 ? "整段" : frame, video ? "视频" : "图片");
    }

    /** 收到一个分片：只累加计数。 */
    public static void downloadChunk(String hash, int frame, int bytes) {
        Dl d = ACTIVE.get(key(hash, frame));
        if (d == null) return;
        d.chunks++;
        d.bytes += Math.max(0, bytes);
    }

    /**
     * 【rc-83】一次「重复的下载请求被抑制」。
     *
     * <p>这是本轮延迟风暴的<b>判据</b>：正常情况下这个数字应该始终为 0
     * （渲染线程不会再每帧重发起下载）。同一份媒体 10 秒最多打一行；
     * 抑制次数达到 {@code DUP_WARN_AT} 时升级成 WARN —— 说明又有调用方在反复要同一份媒体，
     * 此时服务端正在把同一段内容重复下发，必须当场看见而不是等玩家报延迟。</p>
     */
    public static void downloadSuppressed(String hash, int frame, int count, long received, long total) {
        Dl d = ACTIVE.get(key(hash, frame));
        if (d != null) d.suppressed = count;
        long now = System.currentTimeMillis();
        if (d != null && now - d.lastDupLogMs < 10_000L) return;
        if (d != null) d.lastDupLogMs = now;
        String text = "[Projector][客户端][下载] 重复请求已被抑制 第{}次 哈希={} 帧={} 已收={}/{}";
        Object[] args = {count, shortHash(hash), frame < 0 ? "整段" : frame,
                sizeText(received), total > 0 ? sizeText(total) : "?"};
        if (count >= DUP_WARN_AT) {
            Projector.LOGGER.warn(text + "（服务端正在重复下发同一份内容，检查调用方）", args);
        } else {
            Projector.LOGGER.debug(text, args);
        }
    }

    /** 抑制次数到这个量级就该警告了（正常值应为 0）。 */
    private static final int DUP_WARN_AT = 20;

    /**
     * 下载完成（已落盘/已解码）。
     *
     * <p>【rc-82 重要修复】只有**登记过「开始」**的那一次才算一次传输。
     * 以前这里无条件打日志，于是「同一帧被重复投递/重复请求」会把每一份重复都打一遍 ——
     * 玩家实测 4.5 分钟刷出 3.1 MB 日志（同一帧打了 30~83 次「完成 原因=-」）。
     * 重复不再打 INFO（只进 DEBUG），所以日志里「开始 ↔ 完成」永远一一对应。</p>
     *
     * @return true = 这是一次**真的**传输（登记过开始）；false = 重复投递
     */
    public static boolean downloadDone(String hash, int frame, long bytes, @Nullable Path cachePath) {
        Dl d = ACTIVE.remove(key(hash, frame));
        if (d == null) {
            Projector.LOGGER.debug("[Projector][客户端][下载] 重复完成（没有对应的开始）哈希={} 帧={}",
                    shortHash(hash), frame);
            return false;
        }
        long ms = System.currentTimeMillis() - d.startMs;
        int chunks = d == null ? 1 : Math.max(1, d.chunks);
        Projector.LOGGER.info(
                "[Projector][客户端][下载] 完成 原因={} 哈希={} 帧={} 分片={} 大小={} 用时={}ms 速率={} 抑制重复={} 落盘={}",
                d.reason, shortHash(hash), frame < 0 ? "整段" : frame, chunks,
                sizeText(bytes), ms, rateText(bytes, ms), d.suppressed,
                cachePath == null ? "-" : cachePath.toAbsolutePath());
        return true;
    }

    /**
     * 下载失败 / 服务端没有这份媒体。
     *
     * <p>【rc-82】同样只在**登记过开始**时才写 INFO：失败会被反复重试，
     * 每次重试都打一行的话就是刷屏（实测同一帧的失败被打了 70~90 次）。
     * 调用方拿到 false 时应当自己**退避**（见 {@code MediaCache} 的缺失退避）。</p>
     *
     * @return true = 这次失败对应一次真实传输；false = 重复的失败（未登记）
     */
    public static boolean downloadFail(String hash, int frame, String reason) {
        Dl d = ACTIVE.remove(key(hash, frame));
        if (d == null) {
            Projector.LOGGER.debug("[Projector][客户端][下载] 重复失败（没有对应的开始）哈希={} 帧={} 原因={}",
                    shortHash(hash), frame, reason);
            return false;
        }
        Projector.LOGGER.warn("[Projector][客户端][下载] 失败 原因={} 哈希={} 帧={} 已收={}",
                reason, shortHash(hash), frame < 0 ? "整段" : frame, sizeText(d.bytes));
        return true;
    }

    /**
     * 【rc-76】下载后的哈希校验结果。
     *
     * <p>整段文件到手后重算 SHA-1，与请求的哈希比对：不一致说明传输/落盘坏了，
     * 表现是控件一直显示占位块 —— 这两行日志是唯一的第一手证据。</p>
     */
    public static void hashCheck(String hash, @Nullable String actual, boolean ok, long size) {
        if (ok) {
            Projector.LOGGER.info("[Projector][客户端][下载] 哈希校验 结果=一致 ✅ 期望={} 实算={} 大小={}",
                    hash, actual, sizeText(size));
        } else {
            Projector.LOGGER.warn("[Projector][客户端][下载] 哈希校验 结果=不一致 ❌ 期望={} 实算={} 大小={}"
                            + "（内容与哈希不符，已丢弃并等待重新下载）",
                    hash, actual == null ? "?" : actual, sizeText(size));
        }
    }

    /** 下载重试（第 N 次）。 */
    public static void downloadRetry(String hash, int frame, int attempt) {
        Projector.LOGGER.info("[Projector][客户端][下载] 重试 第{}次 哈希={} 帧={}",
                attempt, shortHash(hash), frame < 0 ? "整段" : frame);
    }

    /** 当前登记中的下载数（测试用）：同一哈希 + 同一帧只应登记一份。 */
    public static int activeCount() {
        return ACTIVE.size();
    }

    /** 玩家离开服务器时清空进行中的记录，避免残留。 */
    public static void reset() {
        ACTIVE.clear();
    }
}

package top.hmjmfabc.projector.client.media;

import top.hmjmfabc.projector.Projector;
import top.hmjmfabc.projector.ProjectorConfig;
import top.hmjmfabc.projector.client.ClientServerInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 【27.1.3】客户端磁盘缓存的清理（用户要求：**清缓存时优先清视频**）。
 *
 * <p>目录：{@code <gamedir>/projector/cache/}。里面混着三类东西，占用差着数量级：</p>
 * <ul>
 *   <li><b>视频帧</b>（{@code <hash>_fN.bin}）与<b>整段视频</b>（{@code <hash>.bin}）—— 几十~几百 MB；</li>
 *   <li><b>在线视频</b>（{@code online/} 子目录）—— 由 {@code OnlineVideoCache.sweep()} 管，
 *       这里只管它以外的文件；</li>
 *   <li><b>图片</b>（{@code <hash>.bin}）—— 通常几十 KB~几 MB。</li>
 * </ul>
 *
 * <p>所以清理顺序固定为「**先视频、后图片**」，同类里「最旧的先删」，
 * 并且：①**正在用的哈希绝不删**（{@link MediaCache#inUseHashes()} 做白名单，
 * 避免把正在播的视频从脚下抽掉）；②**在途下载的半成品不动**（{@code .part} 十分钟内的留着，
 * 更早的当垃圾清掉）；③只按「天数」和「总上限」两个条件清理，两个都在配置里。</p>
 *
 * <p>什么时候跑：进世界时在后台线程跑一次（和在线视频缓存的清理同一个线程）。</p>
 */
public final class CacheCleaner {
    private CacheCleaner() {
    }

    /** 视频帧的文件名（{@code <hash>_f12.bin}）。 */
    private static final Pattern FRAME = Pattern.compile("^([0-9a-fA-F]{8,})_f\\d+\\.bin$");
    /** 整段媒体（视频或图片，靠服务端清单区分）。 */
    private static final Pattern WHOLE = Pattern.compile("^([0-9a-fA-F]{8,})\\.bin$");
    /** 在途下载的半成品；这么久还没动静就是垃圾。 */
    private static final long PART_GRACE_MS = 10 * 60_000L;
    /** 摘要行里最多列出几个被删的文件名。 */
    private static final int MAX_LISTED = 3;

    /**
     * 测试用：覆盖总上限（字节）。{@code null} = 用配置里的值。
     *
     * <p>「先清视频」这条规则只有在**超上限**时才看得出来，而默认上限是 2 GB ——
     * 测试不可能真造 2 GB 文件，所以留这个钩子（同 {@code LocalMedia.ROOT_FOR_TEST} 的路子）。</p>
     */
    static Long CAP_FOR_TEST;

    private record Item(Path path, long size, long modified, boolean video) {
    }

    /** 清一次（阻塞；调用方放后台线程）。 */
    public static void sweep() {
        try {
            Path dir = LocalMedia.cacheDir();
            if (!Files.isDirectory(dir)) {
                return;
            }
            long now = System.currentTimeMillis();
            long keepDays = keepDays();
            long cap = capBytes();
            Set<String> inUse = new HashSet<>(MediaCache.inUseHashes());

            List<Item> videos = new ArrayList<>();
            List<Item> images = new ArrayList<>();
            long videoBytes = 0L;
            long imageBytes = 0L;
            int staleParts = 0;

            try (var stream = Files.list(dir)) {
                for (Path p : (Iterable<Path>) stream::iterator) {
                    if (!Files.isRegularFile(p)) {
                        continue;
                    }
                    String name = p.getFileName().toString();
                    long modified;
                    long size;
                    try {
                        modified = Files.getLastModifiedTime(p).toMillis();
                        size = Files.size(p);
                    } catch (IOException e) {
                        continue;
                    }
                    // ① 半成品：在途的留着，放太久的清掉
                    if (name.endsWith(".part")) {
                        if (now - modified > PART_GRACE_MS && Files.deleteIfExists(p)) {
                            staleParts++;
                        }
                        continue;
                    }
                    // ② 只认 <hash>.bin 与 <hash>_fN.bin
                    boolean frame = FRAME.matcher(name).matches();
                    Matcher whole = WHOLE.matcher(name);
                    if (!frame && !whole.matches()) {
                        continue;
                    }
                    String hash = frame ? FRAME.matcher(name).replaceAll("$1") : whole.group(1);
                    if (inUse.contains(hash)) {
                        continue;                          // 正在用，别碰
                    }
                    boolean video = frame || manifestSaysVideo(hash);
                    if (video) {
                        videos.add(new Item(p, size, modified, true));
                        videoBytes += size;
                    } else {
                        images.add(new Item(p, size, modified, false));
                        imageBytes += size;
                    }
                }
            }

            // ③ 先按天数过期：视频先、图片后
            long keepMs = keepDays * 86_400_000L;
            long delVideoBytes = 0L;
            long delImageBytes = 0L;
            int delVideos = 0;
            int delImages = 0;
            List<String> listed = new ArrayList<>();
            if (keepMs > 0) {
                for (Item it : videos) {
                    if (now - it.modified() > keepMs && delete(it, listed)) {
                        delVideos++;
                        delVideoBytes += it.size();
                        videoBytes -= it.size();
                    }
                }
                for (Item it : images) {
                    if (now - it.modified() > keepMs && delete(it, listed)) {
                        delImages++;
                        delImageBytes += it.size();
                        imageBytes -= it.size();
                    }
                }
            }

            // ④ 再按总上限：仍然「先视频、后图片」，同类最旧先删
            long total = videoBytes + imageBytes;
            if (cap > 0 && total > cap) {
                videos.sort(Comparator.comparingLong(Item::modified));
                for (Item it : videos) {
                    if (total <= cap) {
                        break;
                    }
                    if (delete(it, listed)) {
                        delVideos++;
                        delVideoBytes += it.size();
                        total -= it.size();
                    }
                }
                if (total > cap) {
                    images.sort(Comparator.comparingLong(Item::modified));
                    for (Item it : images) {
                        if (total <= cap) {
                            break;
                        }
                        if (delete(it, listed)) {
                            delImages++;
                            delImageBytes += it.size();
                            total -= it.size();
                        }
                    }
                }
            }

            if (delVideos > 0 || delImages > 0 || staleParts > 0) {
                Projector.LOGGER.info(
                        "[Projector][客户端][缓存] 清理完成：视频 -{} 个/-{}，图片 -{} 个/-{}，"
                                + "半成品 -{} 个，剩余 {}（上限 {}，保留 {} 天）{}{}",
                        delVideos, mb(delVideoBytes), delImages, mb(delImageBytes), staleParts,
                        mb(total), cap > 0 ? mb(cap) : "不限", keepDays,
                        listed.isEmpty() ? "" : "｜例：" + String.join("、", listed),
                        listed.size() < (delVideos + delImages) ? "…" : "");
            } else {
                Projector.LOGGER.info("[Projector][客户端][缓存] 无需清理：{}{}（上限 {}，保留 {} 天）",
                        mb(total), inUse.isEmpty() ? "" : "，正在使用 " + inUse.size() + " 份",
                        cap > 0 ? mb(cap) : "不限", keepDays);
            }
        } catch (Throwable t) {
            Projector.LOGGER.warn("[Projector][客户端][缓存] 清理缓存失败", t);
        }
    }

    // ------------------------------------------------------------------ 小工具

    private static boolean delete(Item it, List<String> listed) {
        try {
            if (Files.deleteIfExists(it.path())) {
                if (listed.size() < MAX_LISTED) {
                    listed.add(it.path().getFileName().toString());
                }
                return true;
            }
        } catch (IOException ignored) {
            // 删不掉就留着，下次再说
        }
        return false;
    }

    /** 服务端清单里这个哈希是不是视频（拿不到清单时按图片处理，宁可不删）。 */
    private static boolean manifestSaysVideo(String hash) {
        try {
            ClientServerInfo.Media m = ClientServerInfo.media().get(hash);
            return m != null && m.video();
        } catch (Throwable t) {
            return false;
        }
    }

    private static long capBytes() {
        if (CAP_FOR_TEST != null) {
            return CAP_FOR_TEST;
        }
        try {
            return ProjectorConfig.INSTANCE.mediaClientCacheMb.get() * 1024L * 1024L;
        } catch (Throwable t) {
            return 2048L * 1024L * 1024L;
        }
    }

    private static long keepDays() {
        try {
            return ProjectorConfig.INSTANCE.mediaClientCacheKeepDays.get();
        } catch (Throwable t) {
            return 14L;
        }
    }

    private static String mb(long bytes) {
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
        }
        return String.format(Locale.ROOT, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0);
    }
}

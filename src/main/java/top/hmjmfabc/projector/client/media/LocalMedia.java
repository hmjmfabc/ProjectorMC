package top.hmjmfabc.projector.client.media;

import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import top.hmjmfabc.projector.Projector;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 客户端媒体库。
 *
 * <p>负责扫描并索引玩家本地的图片/视频文件，同时提供 SHA-1 计算与解码工具。
 * 扫描目录（按优先级）：</p>
 * <ol>
 *   <li>{@code .minecraft/projector/media/} —— 模组主目录（推荐）</li>
 *   <li>{@code .minecraft/projector/media/subdir/} —— 支持一层子目录</li>
 *   <li>{@code .minecraft/versions/&lt;版本&gt;/medias/} —— 设计稿里提到的旧位置，兼容读取</li>
 * </ol>
 */
public final class LocalMedia {

    /** 客户端缓存目录：.minecraft/projector/cache */
    public static final String CACHE_DIR = "cache";

    /**
     * 媒体索引。
     *
     * <p>必须线程安全：渲染线程/界面线程会遍历它（媒体选择器），
     * 而网络线程会在收到服务端媒体分片时写入（{@code registerCache}）。
     * 用普通 HashMap 会偶发 {@code ConcurrentModificationException} 而崩溃。</p>
     */
    /**
     * 「界面列表」索引：**键一律是 `"local:<绝对路径>"`**，只由 {@link #rescan()} 填充。
     *
     * <p>【重要】不要把 SHA-1 键塞进这个表。历史上 `byHash` 的缓存回退与
     * {@code register}/{@code registerCache} 都往这里塞过 SHA-1 键，
     * 导致同一份文件在 {@link #all()} 里出现两次（列表重复），
     * 而且 {@code rescan()} 的 clear+putAll 会把它们与真实素材混在一起。
     * SHA-1 → 文件 的映射统一放 {@link #BY_SHA1}。</p>
     */
    private static final Map<String, MediaFile> INDEX = new ConcurrentHashMap<>();
    private static long lastScan;

    private LocalMedia() {
    }

    /** 一条本地媒体。 */
    public record MediaFile(String hash, String name, Path path, boolean video, long size) {
    }

    /** 支持导入的图片扩展名。 */
    public static final List<String> IMAGE_EXT = List.of("png", "jpg", "jpeg", "bmp", "gif", "tga");
    /** 支持导入的视频扩展名。 */
    /**
     * 会被列进「视频」列表的扩展名。
     *
     * <p>前三个（mjpg / mjpeg / zip）是模组<b>能直接播放</b>的格式；
     * 其余是常见但模组解不了的格式，列出来是为了让玩家能在选择器里选中它们、
     * 用「视频格式转换…」转成受支持的格式。</p>
     */
    public static final List<String> VIDEO_EXT = List.of(
            "mjpg", "mjpeg", "zip",
            "mov", "avi", "mp4", "m4v", "mkv", "webm", "flv", "wmv", "3gp", "mpg", "mpeg", "gif");

    public static Path gameDir() {
        return Minecraft.getInstance().gameDirectory.toPath();
    }

    /**
     * 【rc-84】测试用的根目录覆盖：无头环境（验证脚本）没有 Minecraft 实例，
     * {@link #gameDir()} 会 NPE，于是「下载缓存能不能被复用」这条链根本没法验证。
     */
    static Path ROOT_FOR_TEST;

    /** 模组主目录 .minecraft/projector */
    public static Path rootDir() {
        return ROOT_FOR_TEST != null ? ROOT_FOR_TEST : gameDir().resolve(Projector.MODID);
    }

    /** 用户放置图片/视频的目录。 */
    public static Path mediaDir() {
        return rootDir().resolve("media");
    }

    /** 用户放置自定义字体的目录。 */
    public static Path fontDir() {
        return rootDir().resolve("fonts");
    }

    /** 从服务端下载回来的媒体缓存。 */
    public static Path cacheDir() {
        return rootDir().resolve(CACHE_DIR);
    }

    /** 旧版设计稿中提到的目录：.minecraft/versions/&lt;版本&gt;/medias */
    public static Path legacyMediaDir() {
        // 【rc-84】测试覆盖时也要落在测试根目录下，否则无头环境照样 NPE
        return ROOT_FOR_TEST != null ? ROOT_FOR_TEST.resolve("versions").resolve("medias")
                : gameDir().resolve("versions").resolve("medias");
    }

    /** 确保目录存在（模组初始化时调用）。 */
    public static void ensureDirectories() {
        try {
            Files.createDirectories(mediaDir());
            Files.createDirectories(fontDir());
            Files.createDirectories(cacheDir());
        } catch (IOException ex) {
            Projector.LOGGER.warn("[Projector] 无法创建媒体目录: {}", ex.toString());
        }
        ensureNoMediaFiles();
    }

    /**
     * 【用户要求 ⑬】在媒体目录与缓存目录下各放一个空的 {@code .nomedia} 文件。
     *
     * <p>作用有两条，都是为了让玩家「别手贱清掉缓存」：</p>
     * <ul>
     *   <li>Android 的相册 / 文件管理器扫描到 {@code .nomedia} 就会跳过该目录，
     *       缓存里成百上千个 {@code <sha1>.bin} 不会跑到相册里去，玩家也就不会
     *       顺手「清理图片缓存」；</li>
     *   <li>目录里出现一个显眼的说明性文件，玩家至少知道这里是模组在管。</li>
     * </ul>
     * <p>纯客户端行为，失败只记日志——绝不能因为一个空文件写不出来就让游戏进不去。</p>
     */
    public static void ensureNoMediaFiles() {
        writeNoMedia(mediaDir());
        writeNoMedia(cacheDir());
    }

    private static void writeNoMedia(Path dir) {
        try {
            Files.createDirectories(dir);
            Path marker = dir.resolve(".nomedia");
            if (Files.exists(marker)) {
                return;
            }
            // 内容留一句人话：这个文件本来就不该被任何工具读取
            Files.write(marker, ("Projector 缓存目录，请勿手动清理。\n"
                    + "Deleting files here forces the client to re-download them "
                    + "from the server and wastes bandwidth.\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable t) {
            Projector.LOGGER.debug("[Projector] 无法写入 .nomedia（{}）：{}", dir, t.toString());
        }
    }

    /** 重新扫描媒体目录（最多每 2 秒一次，避免频繁 IO）。 */
    public static void rescanIfStale() {
        long now = System.currentTimeMillis();
        if (now - lastScan < 2000 && !INDEX.isEmpty()) {
            return;
        }
        rescan();
    }

    /**
     * 【rc-88】「渲染线程不许做文件 IO」的入口：索引过期时<b>立刻返回</b>，
     * 把重新扫描丢给后台线程。
     *
     * <p>玩家实测的「一画视频就每隔两秒卡一秒」正是这条链：渲染线程在
     * {@code MediaCache.videoFrame} 里问「本地有没有整段视频」→ {@link #hasWhole}
     * → {@link #byHash} → {@link #rescanIfStale} → <b>每 2 秒在渲染线程上重扫一次素材目录</b>，
     * 并把目录里每个文件重算一遍 SHA-1（玩家目录里那个 685 MB 的 mp4 单独就要 <b>1013 ms</b>）。
     * 暂停播放时 {@code videoFrame} 直接命中帧缓存、走不到这里，所以「暂停就不卡」。</p>
     */
    public static void rescanAsyncIfStale() {
        long now = System.currentTimeMillis();
        if (now - lastScan < 2000 && !INDEX.isEmpty()) return;
        if (!SCAN_PENDING.compareAndSet(false, true)) return;   // 已有一个在排队/执行
        SCAN_EXECUTOR.execute(() -> {
            try {
                rescan();
            } catch (Throwable t) {
                Projector.LOGGER.warn("[Projector] 后台扫描素材目录失败: {}", t.toString());
            } finally {
                SCAN_PENDING.set(false);
            }
        });
    }

    /** 【rc-88】测试用：把「上次扫描时间」置零，让下一次 rescanIfStale 一定认为索引过期。 */
    static void markScanStaleForTest() {
        lastScan = 0L;
    }

    /** 后台扫描是否在排队/进行中（诊断与测试用）。 */
    public static boolean scanPending() {
        return SCAN_PENDING.get();
    }

    /**
     * 【rc-88】只查内存索引的「本地有没有这份媒体的整段文件」——<b>绝不触发任何文件 IO</b>。
     *
     * <p>渲染路径只能用这一个，理由见 {@link #rescanAsyncIfStale()}。
     * 索引由「登录时的扫描 / 后台扫描 / 下载完成时的登记」维护，晚一两秒无妨：
     * 这里返回 false 的最坏后果只是多发一次「整段请求」，服务端会用清单告诉客户端不必传。</p>
     */
    public static boolean hasWholeCached(String hash) {
        if (hash == null || hash.isEmpty()) return false;
        // 只认 BY_SHA1：它同时收录「玩家自己的素材文件（按键=文件 SHA-1）」与
        // 「下载缓存 <hash>.bin（按键=服务端哈希）」，且从来不碰文件系统。
        return BY_SHA1.containsKey(hash);
    }

    /** 强制重新扫描。 */
    public static synchronized void rescan() {
        long t0 = System.nanoTime();
        long digestsBefore = DIGESTS_COMPUTED.get();
        lastScan = System.currentTimeMillis();
        Map<String, MediaFile> found = new LinkedHashMap<>();
        Map<String, MediaFile> cachedByHash = new LinkedHashMap<>();
        // 【rc-88】不要在这里 `BY_SHA1.clear()`：清空到重建之间隔着一两百毫秒的文件 IO，
        // 这期间任何查询都会误判「本地没有这份媒体」（rc-84 那类「本地有还去要一次」的复现路径）。
        // 改成先把结果攒在 nextBySha1 里，最后再一次性换上去（窗口只有微秒级）。
        Map<String, MediaFile> nextBySha1 = new LinkedHashMap<>();
        List<Path> roots = new ArrayList<>();
        roots.add(mediaDir());
        roots.add(legacyMediaDir());
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> stream = Files.walk(root, 2)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> !p.getFileName().toString().startsWith("."))
                        .sorted(Comparator.comparing(Path::toString))
                        .forEach(p -> {
                            String name = p.getFileName().toString();
                            String ext = extension(name);
                            boolean video = VIDEO_EXT.contains(ext);
                            if (!video && !IMAGE_EXT.contains(ext)) return;
                            try {
                                if (Files.size(p) == 0) return;
                            } catch (IOException e) {
                                return;
                            }
                            // 目录内的相对路径作为显示名，避免同名冲突
                            String display = root.relativize(p).toString().replace('\\', '/');
                            String hash = "local:" + p.toAbsolutePath();
                            long size = sizeOf(p);
                            MediaFile mf = new MediaFile(hash, display, p, video, size);
                            found.put(hash, mf);
                            // 【关键】同时按「文件内容的 SHA-1」建一份索引。
                            // 以前只按 "local:<路径>" 索引，于是控件里存的哈希
                            // 永远查不到玩家自己的素材文件，只能退到下载缓存
                            // cache/<hash>.bin —— 重启后图片「纹理丢失」就是这么来的。
                            //
                            // 【rc-88】大于 INLINE_HASH_MAX_BYTES 的文件**不在扫描里现算**：
                            // 玩家素材目录里有一个 685 MB 的 mp4，实测单纯算它的 SHA-1 就要
                            // 1013 ms（FUSE 读 + SHA-1），放在扫描里等于每次扫描都卡一秒。
                            // 大文件改成后台补算（digestLater），算完照样进 BY_SHA1。
                            if (size > INLINE_HASH_MAX_BYTES.get() && DIGEST_CACHE.get(digestKey(p, size)) == null) {
                                digestLater(p, mf);
                                return;
                            }
                            String sha1 = sha1Of(p);
                            if (sha1 != null) nextBySha1.put(sha1, mf);
                        });
            } catch (IOException ex) {
                Projector.LOGGER.warn("[Projector] 扫描媒体目录失败 {}: {}", root, ex.toString());
            }
        }
        INDEX.clear();
        INDEX.putAll(found);
        // 服务端下载缓存：**必须在本表清空之后再登记**。
        // 【rc-84 重要修复】以前这一句写在扫描缓存目录**之前**（那时 cachedByHash 还是空表），
        // 于是每次重扫都会把「下载缓存」的索引整个丢掉：客户端明明有 cache/<hash>.bin，
        // byHash() 却查不到，只能再去向服务端要一次 —— 玩家报的
        // 「服务端会给客户端下发客户端已有的视频，而不复用客户端缓存」就是这条。
        Path cache = cacheDir();
        if (Files.isDirectory(cache)) {
            try (Stream<Path> stream = Files.list(cache)) {
                stream.filter(Files::isRegularFile).forEach(p -> {
                    String name = p.getFileName().toString();
                    if (!name.endsWith(".bin")) return;
                    String key = name.substring(0, name.length() - 4);
                    // 单帧缓存（<hash>_fN.bin）装的是文件里的一小段，**不是整份媒体**，
                    // 不能按媒体哈希登记，否则会「以为本地有整段」而解码失败。
                    if (frameSuffix(key) >= 0) return;
                    // 缓存文件不参与界面列表，只登记到 SHA-1 索引
                    if (Files.isRegularFile(p)) {
                        cachedByHash.put(key, new MediaFile(key, key, p, false, sizeOf(p)));
                    }
                });
            } catch (IOException ignored) {
            }
        }
        // 用 putIfAbsent 而不是 putAll：byHash 的注释写着「优先命中玩家自己的素材文件」，
        // 同一哈希既在素材目录又在下载缓存里时，必须仍然返回素材目录那一份。
        cachedByHash.forEach(nextBySha1::putIfAbsent);
        // 换表：被删除/替换的文件不会残留（旧表整体丢弃），而查询方几乎看不到空窗。
        BY_SHA1.clear();
        BY_SHA1.putAll(nextBySha1);
        pruneDigestCache(found.values());
        // 【rc-88】**扫描必须留下耗时证据**：这次的「一画视频就每 2 秒卡 1 秒」
        // 就是扫描在渲染线程上跑了一秒多，而日志里一个字都没有。
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        LAST_SCAN_MS = ms;
        if (ms > MAX_SCAN_MS) {
            MAX_SCAN_MS = ms;
            MAX_SCAN_THREAD = Thread.currentThread().getName();
        }
        long digests = DIGESTS_COMPUTED.get() - digestsBefore;
        LAST_SCAN_INLINE_DIGESTS = (int) digests;   // 供诊断/回归：这次扫描内联算了几个摘要
        if (ms >= SLOW_SCAN_LOG_MS) {
            Projector.LOGGER.warn("[Projector] 素材目录扫描耗时 {} ms（文件 {} 个，合计 {}，"
                            + "本次重算摘要 {} 个，线程={}）—— 扫描期间该线程不能做别的事",
                    ms, found.size(), humanBytes(totalSizeOf(found.values())), digests,
                    Thread.currentThread().getName());
        } else {
            Projector.LOGGER.debug("[Projector] 素材目录扫描 {} ms（文件 {} 个，重算摘要 {} 个）",
                    ms, found.size(), digests);
        }
    }

    /** 已缓存的 SHA-1 是否还在（诊断/测试用）。 */
    static int digestCacheSize() {
        return DIGEST_CACHE.size();
    }

    /** 真正做了多少次 SHA-1 计算（诊断/测试用：命中缓存不算）。 */
    static long digestsComputed() {
        return DIGESTS_COMPUTED.get();
    }

    /** 测试用：把「内联算摘要」的体积上限调小，好在几 MB 的文件上验证延迟补算。 */
    static void setInlineHashMaxForTest(long bytes) {
        INLINE_HASH_MAX_BYTES.set(bytes);
    }

    private static long totalSizeOf(java.util.Collection<MediaFile> files) {
        long n = 0;
        for (MediaFile f : files) n += Math.max(0, f.size());
        return n;
    }

    /** 摘要缓存的键：路径 + 大小 + 修改时间（三者任一变化都要重算）。 */
    private static String digestKey(Path p, long size) {
        long mtime;
        try {
            mtime = Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            mtime = -1L;
        }
        return p.toAbsolutePath() + "|" + size + "|" + mtime;
    }

    /**
     * 【rc-88】大文件的 SHA-1 放到后台算：扫描本身不做重活，算完再补进 {@link #BY_SHA1}。
     *
     * <p>同一个文件在算完之前可能被多次排队，用 {@code IN_FLIGHT_DIGEST} 去重。</p>
     */
    private static void digestLater(Path p, MediaFile mf) {
        String abs = p.toAbsolutePath().toString();
        if (!IN_FLIGHT_DIGEST.add(abs)) return;
        SCAN_EXECUTOR.execute(() -> {
            try {
                String sha1 = sha1Of(p);
                if (sha1 != null) {
                    BY_SHA1.put(sha1, mf);
                    Projector.LOGGER.info("[Projector] 大文件摘要补算完成：{}（{}，sha1={}）",
                            mf.name(), humanBytes(mf.size()), sha1.substring(0, 8));
                }
            } catch (Throwable t) {
                Projector.LOGGER.debug("[Projector] 大文件摘要补算失败 {}: {}", mf.name(), t.toString());
            } finally {
                IN_FLIGHT_DIGEST.remove(abs);
            }
        });
    }

    /** 人类可读的字节数（日志用）。 */
    static String humanBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) return (bytes / 1024L) + " KB";
        if (bytes < 1024L * 1024L * 1024L) return (bytes / (1024L * 1024L)) + " MB";
        return String.format(java.util.Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    /** {@code <hash>_fN} 里的 N；不是单帧缓存返回 -1。 */
    private static int frameSuffix(String key) {
        int i = key.lastIndexOf("_f");
        if (i <= 0 || i + 2 >= key.length()) return -1;
        try {
            return Integer.parseInt(key.substring(i + 2));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }

    public static String extension(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase(java.util.Locale.ROOT);
    }

    public static List<MediaFile> all() {
        rescanIfStale();
        return new ArrayList<>(INDEX.values());
    }

    /** 只列出图片或只列出视频。 */
    public static List<MediaFile> byKind(boolean video) {
        List<MediaFile> out = new ArrayList<>();
        for (MediaFile f : all()) {
            if (f.video() == video) out.add(f);
        }
        return out;
    }

    /** 把「服务端下发并写入缓存」的文件登记进索引。 */
    public static void registerCache(String hash, Path path) {
        BY_SHA1.put(hash, new MediaFile(hash, hash, path, false, sizeOf(path)));
    }

    /** 取某个视频帧的缓存文件（服务端按需下发）。 */
    @Nullable
    public static Path frameCache(String hash, int frame) {
        Path p = cacheDir().resolve(hash + "_f" + frame + ".bin");
        return Files.isRegularFile(p) ? p : null;
    }

    /** 用 hash 取本地文件；服务端 hash（纯 SHA-1 十六进制）会在缓存目录里找。 */
    /** 「文件 SHA-1 -> 素材文件」索引（与 INDEX 的 "local:" 键并存）。 */
    private static final Map<String, MediaFile> BY_SHA1 = new java.util.concurrent.ConcurrentHashMap<>();
    /** 摘要缓存：避免每次重扫都重新读一遍文件。键 = 路径|大小|修改时间。 */
    private static final Map<String, String> DIGEST_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    /** 【rc-88】真正做过多少次 SHA-1（命中缓存不算）——诊断与回归用。 */
    private static final java.util.concurrent.atomic.AtomicLong DIGESTS_COMPUTED =
            new java.util.concurrent.atomic.AtomicLong();
    /** 【rc-88】后台扫描线程：大文件摘要补算、异步重扫都走它（与游戏线程完全隔离）。 */
    private static final java.util.concurrent.ExecutorService SCAN_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "Projector-Scan");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
    /** 是否有后台扫描在排队/执行（去重用）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean SCAN_PENDING =
            new java.util.concurrent.atomic.AtomicBoolean();
    /** 正在后台算摘要的文件（绝对路径），避免同一个文件重复排队。 */
    private static final java.util.Set<String> IN_FLIGHT_DIGEST =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * 【rc-88】扫描时<b>内联</b>算摘要的体积上限：超过它的文件交给后台补算。
     *
     * <p>取 32 MB 是因为实测 685 MB 的 mp4 单独算一次摘要要 1013 ms —— 扫描绝不能等它。
     * 手机上改成 0 就等于「扫描只登记文件名，摘要全部后台补」，也是可接受的配置方向。</p>
     */
    private static final java.util.concurrent.atomic.AtomicLong INLINE_HASH_MAX_BYTES =
            new java.util.concurrent.atomic.AtomicLong(32L * 1024L * 1024L);
    /** 扫描慢过这个值就打一行 WARN（默认 150 ms：正常扫描只有几十毫秒）。 */
    private static final long SLOW_SCAN_LOG_MS = 150L;
    /** 本次扫描**内联**算过几个摘要（大文件的后台补算不算）；诊断与回归用。 */
    private static volatile int LAST_SCAN_INLINE_DIGESTS;

    /** 上一次扫描内联算摘要的个数（回归用：超过阈值的文件必须走后台，不能占扫描）。 */
    static int lastScanInlineDigests() {
        return LAST_SCAN_INLINE_DIGESTS;
    }

    /** 【rc-88】扫描耗时概况，会带在卡顿日志里（下次再卡，一眼能看出是不是扫描干的）。 */
    private static volatile long LAST_SCAN_MS;
    private static volatile long MAX_SCAN_MS;
    private static volatile String MAX_SCAN_THREAD = "-";

    /**
     * 【rc-88】扫描概况：{@code 上次=42ms 最慢=1180ms(Render thread) 后台=否}。
     *
     * <p>加它的原因很直接：rc-86/rc-87 两轮里，卡顿日志的「渲染=1013ms」把所有人都
     * 引到了渲染代码上，而真正的时间花在**渲染调用里的一次目录扫描**上。
     * 现在扫描耗时直接出现在卡顿行里，不用再靠推。</p>
     */
    public static String scanReport() {
        return "上次=" + LAST_SCAN_MS + "ms 最慢=" + MAX_SCAN_MS + "ms(" + MAX_SCAN_THREAD + ")"
                + " 后台=" + (SCAN_PENDING.get() ? "是" : "否");
    }

    /** 计算文件 SHA-1；失败返回 null。结果按 (路径,大小,修改时间) 缓存。 */
    /**
     * 计算文件 SHA-1；失败返回 null。结果按 (路径,大小,修改时间) 缓存。
     *
     * <p><b>必须流式读</b>：早期实现用 {@code Files.readAllBytes()} 把整个文件读进堆里算哈希，
     * 一个 24 MB 的视频就产生 24 MB 垃圾，而 {@link #rescan()} 是在客户端线程上跑的
     *（项目硬约束是 RAM ≤ 3500 MB）。这里用 64 KB 缓冲区边读边算，内存恒定为常数。</p>
     */
    @Nullable
    private static String sha1Of(Path p) {
        try {
            long size = Files.size(p);
            String key = digestKey(p, size);
            String hit = DIGEST_CACHE.get(key);
            if (hit != null) return hit;
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[65536];
            try (var in = Files.newInputStream(p)) {
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            String sha1 = hex(md.digest());
            DIGEST_CACHE.put(key, sha1);
            DIGESTS_COMPUTED.incrementAndGet();
            return sha1;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 摘要缓存只保留仍然存在的文件对应的条目（键前缀 = 绝对路径）。
     *
     * <p>【rc-88 血泪】这里原来写的是 {@code lastIndexOf('|')}，而键是
     * {@code 路径|大小|修改时间}（<b>两个</b>竖线）⇒ 取出来的是 {@code 路径|大小}，
     * 永远匹配不上 {@code paths} ⇒ <b>每次扫描都把整个摘要缓存清空</b>，
     * 于是每 2 秒把素材目录里每个文件重算一遍 SHA-1（685 MB 的 mp4 = 1013 ms），
     * 而这条链是从渲染线程走进来的 —— 玩家看到的「画视频就每 2 秒卡 1 秒」。
     * 修法：取<b>第一个</b>竖线之前的部分。</p>
     */
    private static void pruneDigestCache(java.util.Collection<MediaFile> live) {
        java.util.Set<String> paths = new java.util.HashSet<>();
        for (MediaFile f : live) paths.add(f.path().toAbsolutePath().toString());
        DIGEST_CACHE.keySet().removeIf(k -> {
            int i = k.indexOf('|');
            return i <= 0 || !paths.contains(k.substring(0, i));
        });
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    public static MediaFile byHash(String hash) {
        if (hash == null || hash.isEmpty()) return null;
        // 【rc-88】这里**不许**再调同步的 rescanIfStale()：byHash 会在渲染线程上被调用
        //（例如「本地有还是重下一份」的判断），而同步重扫要读目录 + 重算摘要，
        // 玩家素材里一个 685 MB 的 mp4 就让渲染线程卡 1 秒。索引过期就丢给后台线程刷新，
        // 本次先用现有的（可能略旧的）索引回答。
        rescanAsyncIfStale();
        // 优先命中「玩家自己的素材文件」，而不是下载缓存
        MediaFile own = BY_SHA1.get(hash);
        if (own != null && Files.isRegularFile(own.path())) return own;
        Path cached = cacheDir().resolve(hash + ".bin");
        if (Files.isRegularFile(cached)) {
            MediaFile mf = new MediaFile(hash, hash, cached, false, sizeOf(cached));
            BY_SHA1.put(hash, mf);
            return mf;
        }
        // 【rc-84】旧版（rc-83 之前）整段视频写的是 <hash>_f0.bin，而单帧也用同一个名字，
        // 只能用「大小与服务端清单里声明的媒体大小一致」来区分 —— 一致才认作整份媒体。
        // 玩家报的「服务端还在下发我本地已有的视频」就是这条：本地有 _f0.bin，
        // 而 byHash 只认 <hash>.bin，于是又去要了一遍（24 MB × N）。
        Path legacy = cacheDir().resolve(hash + "_f0.bin");
        long want = top.hmjmfabc.projector.client.ClientServerInfo.mediaSize(hash);
        if (want > 0 && Files.isRegularFile(legacy) && sizeOf(legacy) == want) {
            MediaFile mf = new MediaFile(hash, hash, legacy, true, want);
            BY_SHA1.put(hash, mf);
            return mf;
        }
        return null;
    }

    /**
     * 【rc-84】本地是否已经有这份媒体的<b>整段文件</b>（素材目录，或下载缓存里的
     * {@code <hash>.bin} / 旧命名 {@code <hash>_f0.bin}）。
     *
     * <p>用途：凡是「要不要向服务端要这份媒体」的判断，都必须先问这里 ——
     * 玩家报的「服务端会给客户端下发客户端已有的视频」就是因为有些地方只查了
     * {@code cacheDir/<hash>.bin} 一个名字。</p>
     */
    public static boolean hasWhole(String hash) {
        MediaFile f = byHash(hash);
        return f != null && Files.isRegularFile(f.path());
    }

    /** 读取整个文件。 */
    public static byte[] read(String hash) {
        MediaFile f = byHash(hash);
        if (f == null) return null;
        try {
            return Files.readAllBytes(f.path());
        } catch (IOException ex) {
            Projector.LOGGER.warn("[Projector] 读取本地媒体失败 {}: {}", f.path(), ex.toString());
            return null;
        }
    }

    /** 读取文件的一段。 */
    public static byte[] readRange(String hash, long offset, int length) {
        MediaFile f = byHash(hash);
        if (f == null) return null;
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f.path().toFile(), "r")) {
            raf.seek(Math.max(0, offset));
            byte[] buf = new byte[length];
            int read = raf.read(buf);
            if (read <= 0) return new byte[0];
            if (read == buf.length) return buf;
            byte[] out = new byte[read];
            System.arraycopy(buf, 0, out, 0, read);
            return out;
        } catch (IOException ex) {
            return null;
        }
    }

    /** 把服务端下发的数据写入缓存目录，返回其 hash。 */
    public static boolean store(String hash, byte[] data) {
        try {
            Files.createDirectories(cacheDir());
            Path target = cacheDir().resolve(hash + ".bin");
            Files.write(target, data);
            BY_SHA1.put(hash, new MediaFile(hash, hash, target, false, data.length));
            return true;
        } catch (IOException ex) {
            Projector.LOGGER.warn("[Projector] 写入媒体缓存失败 {}: {}", hash, ex.toString());
            return false;
        }
    }

    public static InputStream open(String hash) throws IOException {
        MediaFile f = byHash(hash);
        if (f == null) throw new IOException("媒体不存在: " + hash);
        return Files.newInputStream(f.path());
    }

    public static String sha1(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 不可用", e);
        }
    }

    /** 复制一份索引快照（供界面使用）。 */
    public static Map<String, MediaFile> snapshot() {
        rescanIfStale();
        return new ConcurrentHashMap<>(INDEX);
    }
}

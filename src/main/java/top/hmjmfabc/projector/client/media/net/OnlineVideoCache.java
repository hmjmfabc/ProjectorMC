package top.hmjmfabc.projector.client.media.net;

import top.hmjmfabc.projector.Projector;
import top.hmjmfabc.projector.ProjectorConfig;
import top.hmjmfabc.projector.client.media.LocalMedia;
import top.hmjmfabc.projector.client.media.MediaCache;
import top.hmjmfabc.projector.common.OnlineVideoLink;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 在线视频的「解析 + 下载到本地」调度（27.1.3）。
 *
 * <p>为什么一定要下载而不是直接播：B 站的直链是 CDN 地址，<b>必须带 Referer</b> 才拉得动，
 * 而我们的播放管线只吃本地文件（WaterMedia 那条也是给它一个路径）。参考实现
 * （MIT 的 {@code Bilibili-Media-Mod-2-neoforge}）默认也是先下载再播。</p>
 *
 * <p>每个客户端各下各的：链接只是控件里的一个字符串（随平面同步），
 * 所以<b>服务端零流量</b>，也不会把视频塞进存档。</p>
 *
 * <p>落在 {@code <gamedir>/projector/cache/online/<id>.<ext>}；下载中先写 {@code .part}。
 * 下完调用 {@link MediaCache#registerExternal} —— 于是内置后端与 WaterMedia 那条路
 * 都把它当成「本地就有的素材」，播放代码一行不用改。</p>
 */
public final class OnlineVideoCache {
    private OnlineVideoCache() {
    }

    /** 任务状态。 */
    public enum State {
        /** 还没请求过。 */
        IDLE,
        /** 排队等着解析。 */
        QUEUED,
        /** 正在解析链接（联网）。 */
        RESOLVING,
        /** 正在下载。 */
        DOWNLOADING,
        /** 本地已就绪，可以播。 */
        READY,
        /** 失败（原因见 {@link Job#reason}）。 */
        FAILED,
        /** 直播 / HLS 这类无限流：不下载，直接把地址交给解码器拉流。 */
        STREAM
    }

    /** 一个在线源的状态。 */
    public static final class Job {
        public final String sourceUrl;
        /** 本地身份（SHA-1 十六进制）：缓存文件名与 {@code MediaCache} 的键都用它。 */
        public final String id;
        public volatile State state = State.IDLE;
        public volatile String directUrl = "";
        public volatile String referer = "";
        public volatile String ext = "";
        public volatile String name = "";
        public volatile Path path;
        public volatile long got;
        public volatile long total;
        public volatile String reason = "";
        /** 这一轮请求是「要下载」还是「流式」（见 {@link #request(String, boolean)}）。 */
        public volatile boolean downloadWanted = true;
        /** 是不是**无限流**（直播/HLS）：那种永远下不完，只能流式。 */
        public volatile boolean infinite;
        public volatile long retryAtMs;
        public volatile long lastUseMs;
        /** 失败次数（退避用）。 */
        public volatile int failures;

        Job(String sourceUrl) {
            this.sourceUrl = sourceUrl;
            this.id = idFor(sourceUrl);
        }

        public double progress() {
            if (total > 0) {
                return Math.max(0.0, Math.min(1.0, (double) got / total));
            }
            return state == State.READY ? 1.0 : 0.0;
        }
    }

    /** 同一个链接 20 秒内不重复请求（渲染路径每帧都会问一次）。 */
    private static final long REQUEST_THROTTLE_MS = 20_000L;
    /** 失败后的最短重试间隔。 */
    private static final long RETRY_MIN_MS = 60_000L;

    private static final Map<String, Job> JOBS = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST_REQUEST_MS = new ConcurrentHashMap<>();

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Projector-Online");
        t.setDaemon(true);
        return t;
    });

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    // ------------------------------------------------------------------ 查询

    /** 在线源的本地身份（纯 SHA-1 十六进制：要能当纹理名用，不能带冒号）。 */
    public static String idFor(String sourceUrl) {
        String norm = OnlineVideoLink.normalize(sourceUrl);
        return sha1("projector-online-v1|" + norm);
    }

    public static Job job(String sourceUrl) {
        return sourceUrl == null ? null : JOBS.get(OnlineVideoLink.normalize(sourceUrl));
    }

    /** 本地就绪了吗（流式播放不算「本地就绪」，那是另一条路）。 */
    public static boolean ready(String sourceUrl) {
        Job j = job(sourceUrl);
        return j != null && j.state == State.READY && j.path != null && Files.isRegularFile(j.path);
    }

    /** 【27.1.3】这个在线源现在是不是「流式播放」（直播/HLS，或玩家选了流式）。 */
    public static boolean streaming(String sourceUrl) {
        Job j = job(sourceUrl);
        return j != null && j.state == State.STREAM;
    }

    /** 【27.1.3】是不是**天生**只能流式的（直播/HLS）——进度条要不要画就看它（无限流没有总时长）。 */
    public static boolean infinite(String sourceUrl) {
        Job j = job(sourceUrl);
        return j != null && j.state == State.STREAM && j.infinite;
    }

    /** 流地址（没解析出来就是空串）。 */
    public static String streamUrl(String sourceUrl) {
        Job j = job(sourceUrl);
        return j != null && j.state == State.STREAM ? j.directUrl : "";
    }

    public static Path fileFor(String sourceUrl) {
        Job j = job(sourceUrl);
        return ready(sourceUrl) ? j.path : null;
    }

    /**
     * 【27.1.3】按**本地身份（哈希）**取在线视频文件。
     *
     * <p>播放端（{@code WaterMediaVideos.playablePath}）手上只有哈希，没有链接 ——
     * 在线文件又在 {@code cache/online/} 下（既不在素材目录、也不叫 {@code <hash>.bin}），
     * 所以必须由这里回答「这个哈希对应哪个文件」。少了这条，
     * 就会出现「下载完成却一直说本地没有这份媒体」。</p>
     */
    public static Path fileForId(String hash) {
        if (hash == null || hash.isEmpty()) {
            return null;
        }
        for (Job j : JOBS.values()) {
            if (hash.equals(j.id) && j.path != null && Files.isRegularFile(j.path)) {
                return j.path;
            }
        }
        return null;
    }

    /**
     * 【27.1.3】把这份源改成「下载」而不是流式（流式失败时的自动退路）。
     *
     * <p>流式要解码器能直接拉远程地址；有些环境/格式就是拉不动，这时不能一直卡着 ——
     * 退回老办法：整段下载到本地再播。</p>
     */
    public static void forceDownload(String sourceUrl) {
        if (sourceUrl == null || sourceUrl.isEmpty()) {
            return;
        }
        String norm = OnlineVideoLink.normalize(sourceUrl);
        Job j = JOBS.computeIfAbsent(norm, Job::new);
        if (j.state == State.STREAM) {
            Projector.LOGGER.warn("[Projector][在线视频] {} 流式播放起不来，改为整段下载后再播",
                    shortUrl(norm));
        }
        j.downloadWanted = true;
        j.state = State.IDLE;
        j.reason = "";
        LAST_REQUEST_MS.remove(norm);
    }

    /**
     * 给控件用的文件名（带正确扩展名）。
     *
     * <p>解析出来之前先用链接上猜到的扩展名，好让上层能立刻判断「这个扩展名要不要交给
     * 外部解码器」；解析完就换成直链的真实扩展名。</p>
     */
    public static String nameFor(String sourceUrl) {
        String norm = OnlineVideoLink.normalize(sourceUrl);
        Job j = JOBS.get(norm);
        if (j != null && !j.name.isEmpty()) {
            return j.name;
        }
        OnlineVideoLink.Target t = OnlineVideoLink.parse(sourceUrl);
        return OnlineVideoLink.displayName(t, t.ext());
    }

    /** 一行状态说明（日志与界面提示共用）。 */
    public static String describe(String sourceUrl) {
        Job j = job(sourceUrl);
        if (j == null) {
            return "未开始";
        }
        return switch (j.state) {
            case QUEUED -> "排队中";
            case RESOLVING -> "解析链接中…";
            case DOWNLOADING -> String.format(Locale.ROOT, "下载中 %d%%（%s / %s）",
                    Math.round(j.progress() * 100), mb(j.got), j.total > 0 ? mb(j.total) : "?");
            case READY -> "已就绪（" + mb(j.got) + "）";
            case FAILED -> "失败：" + j.reason;
            case STREAM -> j.infinite ? "流式播放（" + j.reason + "，不下载）"
                    : "流式播放（未下载，交给解码器）";
            default -> "未开始";
        };
    }

    // ------------------------------------------------------------------ 请求

    /**
     * 确保这个在线源在下载（渲染路径每帧调一次也没关系：内有节流与去重）。
     *
     * <p>只有在**玩家看得见这个控件**时才会被调到（渲染路径本来就按距离剔除），
     * 所以远处平面上的在线视频不会白白吃流量。</p>
     */
    public static void request(String sourceUrl) {
        request(sourceUrl, true);
    }

    /**
     * 确保这个在线源被处理（渲染路径每帧调一次也没关系：内有节流与去重）。
     *
     * <p>只有在**玩家看得见这个控件**时才会被调到（渲染路径本来就按距离剔除），
     * 而且调用方还要先过「玩家点过播放」那道门（{@code VideoControls.requested}）。</p>
     *
     * @param download {@code true} = 老办法：先整段下载到本地再播；
     *                 {@code false} = **流式**：只解析出直链，交给解码器边下边播（默认，省磁盘省等待）。
     *                 直播/HLS 是无限流，无论这里传什么都不会下载。
     */
    public static void request(String sourceUrl, boolean download) {
        if (sourceUrl == null || sourceUrl.isEmpty()) {
            return;
        }
        String norm = OnlineVideoLink.normalize(sourceUrl);
        OnlineVideoLink.Target t = OnlineVideoLink.parse(norm);
        if (!t.resolvable()) {
            return;
        }
        Job j = JOBS.computeIfAbsent(norm, Job::new);
        long now = System.currentTimeMillis();
        j.lastUseMs = now;
        if (j.state == State.READY || j.state == State.STREAM) {
            return;
        }
        if (j.state == State.QUEUED || j.state == State.RESOLVING || j.state == State.DOWNLOADING) {
            return;
        }
        if (now < j.retryAtMs) {
            return;
        }
        Long last = LAST_REQUEST_MS.get(norm);
        if (last != null && now - last < REQUEST_THROTTLE_MS) {
            return;
        }
        LAST_REQUEST_MS.put(norm, now);
        j.state = State.QUEUED;
        j.reason = "";
        j.downloadWanted = download;
        WORKER.submit(() -> run(j, norm, t));
    }

    /** 让下一次 request 立刻重试（玩家手动点「重试」时用）。 */
    public static void reset(String sourceUrl) {
        Job j = job(sourceUrl);
        if (j != null) {
            j.retryAtMs = 0L;
            j.state = State.IDLE;
            LAST_REQUEST_MS.remove(OnlineVideoLink.normalize(sourceUrl));
        }
    }

    // ------------------------------------------------------------------ 干活

    private static void run(Job j, String norm, OnlineVideoLink.Target t) {
        long t0 = System.currentTimeMillis();
        try {
            j.state = State.RESOLVING;
            OnlineVideoResolver.Resolved r = OnlineVideoResolver.resolve(norm);
            j.directUrl = r.url();
            j.referer = r.referer();
            j.ext = r.ext().isEmpty() ? "mp4" : r.ext();
            j.name = OnlineVideoLink.displayName(t, j.ext);
            Projector.LOGGER.info("[Projector][在线视频] 解析成功 {} -> {}（{}，扩展名={}）",
                    shortUrl(norm), shortUrl(r.url()), r.note(), j.ext);

            if (r.live() || isStreaming(j.ext)) {
                // 【27.1.3】直播 / HLS 是无限流：**不下载**，把地址交给解码器边下边播。
                // 没装解码器时上层会显示「当前环境放不了流」，但解析结果本身是有价值的证据。
                j.state = State.STREAM;
                j.infinite = true;
                j.reason = r.live() ? "直播" : ("HLS/DASH " + j.ext);
                Projector.LOGGER.info("[Projector][在线视频] {} 是{}流，改为流式播放（不下载）：{}",
                        shortUrl(norm), r.live() ? "直播" : "HLS", shortUrl(r.url()));
                return;
            }

            if (!j.downloadWanted) {
                // 【27.1.3】普通在线视频也能**流式**播（编辑器里的开关，默认开）：
                // 只把直链记下来交给解码器，不落盘 —— 省磁盘、也不用等整段下完。
                j.state = State.STREAM;
                j.infinite = false;
                j.reason = "按设置流式";
                Projector.LOGGER.info("[Projector][在线视频] {} 按设置流式播放（不下载）：{}",
                        shortUrl(norm), shortUrl(r.url()));
                return;
            }

            Path dir = onlineDir();
            Files.createDirectories(dir);
            Path part = dir.resolve(j.id + ".part");
            Path finalPath = dir.resolve(fileName(j, norm));

            long cap = maxDownloadBytes();
            // 已经在本地（上次下到一半/下完了）就直接用
            if (Files.isRegularFile(finalPath)) {
                long size = Files.size(finalPath);
                if (size > 0) {
                    j.path = finalPath;
                    j.got = size;
                    j.total = size;
                    j.state = State.READY;
                    MediaCache.registerExternal(j.id, finalPath);
                    Projector.LOGGER.info("[Projector][在线视频] 本地已有，直接用：{}（{}）",
                            finalPath.getFileName(), mb(size));
                    return;
                }
            }

            j.state = State.DOWNLOADING;
            long free = 0L;
            try {
                free = Files.getFileStore(dir).getUsableSpace();
            } catch (Throwable ignored) {
                // 拿不到就算了，后面按实际写入失败处理
            }
            if (free > 0 && free < 96L * 1024 * 1024) {
                throw new IOException("磁盘剩余空间不足（" + mb(free) + "）");
            }

            HttpResponse<InputStream> resp = openStream(j.directUrl, j.referer);
            int code = resp.statusCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }
            long len = resp.headers().firstValueAsLong("content-length").orElse(-1L);
            if (len > 0 && cap > 0 && len > cap) {
                throw new IOException("文件太大（" + mb(len) + " > 上限 " + mb(cap) + "）");
            }
            j.total = Math.max(0L, len);

            long written = 0L;
            byte[] buf = new byte[65536];
            try (InputStream in = resp.body();
                 java.io.OutputStream out = Files.newOutputStream(part)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    written += n;
                    j.got = written;
                    if (cap > 0 && written > cap) {
                        throw new IOException("超过上限 " + mb(cap));
                    }
                }
            }
            if (written <= 0) {
                throw new IOException("下载到 0 字节");
            }
            Files.move(part, finalPath, StandardCopyOption.REPLACE_EXISTING);
            j.path = finalPath;
            j.got = written;
            if (j.total <= 0) {
                j.total = written;
            }
            j.state = State.READY;
            MediaCache.registerExternal(j.id, finalPath);
            Projector.LOGGER.info("[Projector][在线视频] 下载完成 {}（{}，用时 {}ms）",
                    finalPath.getFileName(), mb(written), System.currentTimeMillis() - t0);
        } catch (Throwable e) {
            try {
                Files.deleteIfExists(onlineDir().resolve(j.id + ".part"));
            } catch (Throwable ignored) {
                // 删不掉就算了，下次覆盖
            }
            j.failures++;
            j.state = State.FAILED;
            j.reason = e.getMessage() == null ? e.toString() : e.getMessage();
            j.retryAtMs = System.currentTimeMillis() + RETRY_MIN_MS;
            Projector.LOGGER.warn("[Projector][在线视频] 失败 {}：{}（{}ms 后重试）",
                    shortUrl(norm), j.reason, RETRY_MIN_MS);
        }
    }

    private static HttpResponse<InputStream> openStream(String url, String referer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", OnlineVideoLink.UA)
                .GET();
        if (referer != null && !referer.isEmpty()) {
            b.header("Referer", referer);
            b.header("Origin", referer.endsWith("/") ? referer.substring(0, referer.length() - 1) : referer);
        }
        String cookie = cookie();
        if (!cookie.isEmpty()) {
            b.header("Cookie", cookie);
        }
        return CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    private static String fileName(Job j, String norm) {
        return j.id + "." + (j.ext.isEmpty() ? "mp4" : j.ext);
    }

    private static boolean isStreaming(String ext) {
        return "m3u8".equals(ext) || "mpd".equals(ext) || "ts".equals(ext);
    }

    // ------------------------------------------------------------------ 目录与清理

    /** 在线视频的缓存目录（与服务器素材分开，方便整体清理）。 */
    public static Path onlineDir() {
        return LocalMedia.cacheDir().resolve("online");
    }

    private static long maxDownloadBytes() {
        try {
            return ProjectorConfig.INSTANCE.onlineMaxDownloadMb.get() * 1024L * 1024L;
        } catch (Throwable t) {
            return 512L * 1024L * 1024L;
        }
    }

    private static long cacheTotalBytes() {
        try {
            return ProjectorConfig.INSTANCE.onlineCacheMb.get() * 1024L * 1024L;
        } catch (Throwable t) {
            return 2048L * 1024L * 1024L;
        }
    }

    private static int keepDays() {
        try {
            return ProjectorConfig.INSTANCE.onlineKeepDays.get();
        } catch (Throwable t) {
            return 7;
        }
    }

    private static String cookie() {
        try {
            String c = ProjectorConfig.INSTANCE.onlineBilibiliCookie.get();
            return c == null ? "" : c.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 清理：先按天数删旧文件，再按总大小从最旧的删到不超上限。
     *
     * <p>登录/退出时各跑一次就够。参考实现把「自动清理」当卖点，这里同样默认开
     * （{@code online.keepDays=7}、{@code online.cacheMb=2048}）。</p>
     */
    public static void sweep() {
        try {
            Path dir = onlineDir();
            if (!Files.isDirectory(dir)) {
                return;
            }
            long days = keepDays();
            long now = System.currentTimeMillis();
            long total = 0;
            java.util.List<Path> files = new java.util.ArrayList<>();
            try (var s = Files.list(dir)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    if (!Files.isRegularFile(p)) {
                        continue;
                    }
                    long age = now - Files.getLastModifiedTime(p).toMillis();
                    if (days > 0 && age > days * 86_400_000L) {
                        Files.deleteIfExists(p);
                        Projector.LOGGER.info("[Projector][在线视频] 清理过期缓存 {}（{} 天未用）",
                                p.getFileName(), age / 86_400_000L);
                        continue;
                    }
                    if (p.getFileName().toString().endsWith(".part")) {
                        // 半截文件：启动时一律丢掉（下次会重下）
                        Files.deleteIfExists(p);
                        continue;
                    }
                    files.add(p);
                    total += Files.size(p);
                }
            }
            long cap = cacheTotalBytes();
            if (cap > 0 && total > cap && !files.isEmpty()) {
                files.sort(java.util.Comparator.comparingLong(p -> {
                    try {
                        return Files.getLastModifiedTime(p).toMillis();
                    } catch (IOException e) {
                        return Long.MAX_VALUE;
                    }
                }));
                for (Path p : files) {
                    if (total <= cap) {
                        break;
                    }
                    long size = Files.size(p);
                    if (Files.deleteIfExists(p)) {
                        total -= size;
                        Projector.LOGGER.info("[Projector][在线视频] 清理缓存（超出总上限）{}", p.getFileName());
                    }
                }
            }
            if (!files.isEmpty()) {
                Projector.LOGGER.info("[Projector][在线视频] 缓存目录 {} 个文件 / {}（上限 {}）",
                        files.size(), mb(total), mb(cap));
            }
        } catch (Throwable t) {
            Projector.LOGGER.warn("[Projector][在线视频] 清理缓存失败", t);
        }
    }

    /** 断开连接时调用：状态留着没意义（本地文件保留，下次直接命中）。 */
    public static void clear() {
        JOBS.clear();
        LAST_REQUEST_MS.clear();
    }

    // ------------------------------------------------------------------ 小工具

    private static String mb(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
        }
        return String.format(Locale.ROOT, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0);
    }

    private static String shortUrl(String url) {
        int q = url.indexOf('?');
        String s = q > 0 ? url.substring(0, q) : url;
        return s.length() > 100 ? s.substring(0, 100) + "…" : s;
    }

    private static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Throwable t) {
            // 理论上不会发生；退化成 hashCode 也要能跑
            return String.format(Locale.ROOT, "%08x%08x", s.hashCode(), s.length());
        }
    }
}

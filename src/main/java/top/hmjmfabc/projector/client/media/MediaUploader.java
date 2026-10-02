package top.hmjmfabc.projector.client.media;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import top.hmjmfabc.projector.Projector;
import top.hmjmfabc.projector.ProjectorConfig;
import top.hmjmfabc.projector.network.Payloads;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 媒体上传器：把本地文件分块发送给服务端。
 *
 * <p>上传在后台线程做（避免卡住渲染线程），每次发送一块（默认 20 KB，
 * 可配置）。之所以要分块：Minecraft 的单个自定义包过大会导致网络层报错
 * （以及 Android 端内存抖动），分块后单包体积恒定、可随时取消。</p>
 *
 * <h2>【②】本轮的配额与压缩逻辑</h2>
 * <ol>
 *   <li>上限按身份取（普通玩家 4 MB / 64 MB，管理员 16 MB / 256 MB，
 *       单人存档不限）——服务端在环境清单里下发，客户端只做提前提示，服务端仍然是权威；</li>
 *   <li>视频超过硬性上限（默认 256 MB）<b>任何人不允许上传</b>；</li>
 *   <li>视频超过自动压缩阈值（默认 32 MB）且<b>没有</b>「服务端启用原画上传 + 客户端勾选原画上传」
 *       时，先抽帧压到目标大小再上传；</li>
 *   <li>压缩发生在<b>读文件之前</b>（{@link #prepare}），这样哈希、尺寸、
 *       本地纹理与真正发出去的那一份永远是同一个文件。</li>
 * </ol>
 */
public final class MediaUploader {

    /** 上传状态回调。 */
    public interface Callback {
        void onProgress(float fraction, String message);

        void onDone(boolean success, String message);
    }

    private MediaUploader() {
    }

    /** 上传一个本地媒体文件（不带「原画上传」）。返回是否成功排队。 */
    public static boolean upload(Path path, boolean video, @Nullable Callback callback) {
        return upload(path, video, false, callback);
    }

    /**
     * 【②】带「原画上传」开关的上传入口。
     *
     * @param originalQuality 客户端是否勾选了「原画上传」。只有在服务端也启用了
     *                        该配置项时才会真正生效，否则一律按自动压缩阈值处理。
     */
    public static boolean upload(Path path, boolean video, boolean originalQuality,
                                 @Nullable Callback callback) {
        UPLOAD_WORKER.execute(() -> uploadBlocking(path, video, originalQuality, callback));
        return true;
    }

    /**
     * 【②】上传前的准备结果：做完配额/阻断检查与（必要时）自动压缩之后，
     * 真正要发送的那份文件。
     */
    public record Prepared(boolean ok, Path path, long size, String message) {
    }

    /**
     * 【②】上传前处理：配额检查 + 超大视频阻断 + 超阈视频自动压缩。
     *
     * <p>抽成单独一步，是因为「选择媒体并建立控件」的界面
     * （{@code MediaPickerScreen}）必须在<b>读文件之前</b>就知道要发哪一份：
     * 它要按最终字节算哈希、探测尺寸并建立本地纹理。如果压缩发生在更后面，
     * 控件记录的哈希就会指向压缩前的文件，控件永远加载不出来。</p>
     */
    public static Prepared prepare(Path path, boolean video, boolean originalQuality) {
        try {
            long size = Files.size(path);
            if (size <= 0) return new Prepared(false, null, 0, "\u6587\u4ef6\u4e3a\u7a7a");
            // 【27.2】音频按视频档：一首 FLAC 十几 MB，走图片那 4 MB 的档会在本地就被拦下
            boolean audio = !video && top.hmjmfabc.projector.server.Sanitize
                    .isAudioName(path.getFileName().toString());
            long limit = limitFor(video || audio);
            if (video) {
                long block = top.hmjmfabc.projector.client.ClientServerInfo.blockVideoBytes();
                if (block > 0 && size > block) {
                    return new Prepared(false, null, size, "\u89c6\u9891\u8d85\u8fc7\u786c\u6027\u4e0a\u9650 "
                            + (block / 1024 / 1024) + " MB\uff0c\u4e0d\u5141\u8bb8\u4efb\u4f55\u4eba\u4e0a\u4f20");
                }
            } else if (size > limit) {
                return new Prepared(false, null, size, (audio
                        ? "\u97f3\u9891\u8fc7\u5927\uff08\u4e0a\u9650 " : "\u56fe\u7247\u8fc7\u5927\uff08\u4e0a\u9650 ")
                        + (limit / 1024 / 1024) + " MB\uff09");
            }
            Path sendPath = path;
            if (video) {
                long auto = top.hmjmfabc.projector.client.ClientServerInfo.autoCompressVideoBytes();
                boolean useOriginal = originalQuality
                        && top.hmjmfabc.projector.client.ClientServerInfo.allowOriginalUpload();
                // 【27.1.2】装了 WaterMedia 且是它能直接放的格式：**不要**抽帧压缩
                //（压出来的是 MJPEG，反而丢掉画质；原样上传后由 WaterMedia 解码）
                if (!useOriginal && size > auto
                        && top.hmjmfabc.projector.client.media.wm.WaterMediaBridge.available()
                        && top.hmjmfabc.projector.client.media.wm.WaterMediaBridge
                                .isNativeVideo(path.getFileName().toString())) {
                    useOriginal = true;
                }
                // 【27.1.3】内置压缩（MJPEG 复制 / ZIP 重打包）随内置视频后端一起移除：
                // 视频一律原样上传，播放交给可选前置模组 WaterMedia；超限就按下面的规则拒绝。
            }
            if (size > limit) {
                return new Prepared(false, null, size, "\u538b\u7f29\u540e\u4ecd\u8d85\u8fc7\u4e0a\u9650 "
                        + (limit / 1024 / 1024) + " MB\uff08\u5f53\u524d "
                        + String.format(java.util.Locale.ROOT, "%.1f", size / 1048576.0) + " MB\uff09");
            }
            return new Prepared(true, sendPath, size, "ok");
        } catch (Exception ex) {
            Projector.LOGGER.error("[Projector] 读取媒体失败", ex);
            return new Prepared(false, null, 0, "\u8bfb\u53d6\u6587\u4ef6\u5931\u8d25");
        }
    }

    /**
     * 这个文件是不是「我们自己不解码、直接原样交给外部解码器放」的视频。
     *
     * <p>【27.1.2】对玩家只表现为「它就是个能放的视频」——界面里不提后端、不提格式限制。
     * 内部判据两条：装了能解码它的模组，且扩展名属于那类容器。</p>
     */
    public static boolean externalVideo(java.nio.file.Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        String name = path.getFileName().toString();
        return top.hmjmfabc.projector.client.media.wm.WaterMediaBridge.available()
                && top.hmjmfabc.projector.client.media.wm.WaterMediaBridge.isNativeVideo(name);
    }

    /**
     * 视频元数据探测的**唯一入口**（界面与上传都走这里）。
     *
     * <p>内置解得了（MJPEG / ZIP 帧序列）就按老办法拿尺寸+帧表；
     * 内置解不了但在外部解码器的射程内，就只读容器头拿宽高、不建帧表
     * （帧由外部解码器自己管）。两条都失败才返回 {@code null}。</p>
     */
    public record VideoMeta(int width, int height, int frames, long[] frameTable) {
        public boolean hasFrameTable() {
            return frames > 0 && frameTable.length > 0;
        }

        /** 帧率只对内置帧序列有意义；其它一律按 10 报（界面只拿它做进度换算）。 */
        public double fpsOrDefault() {
            return 10.0;
        }
    }

    @Nullable
    public static VideoMeta probeVideo(java.nio.file.Path path) {
        if (externalVideo(path)) {
            int[] sz = VideoProbe.size(path);
            return new VideoMeta(sz == null ? 0 : sz[0], sz == null ? 0 : sz[1], 0, new long[0]);
        }
        VideoSource src = VideoSource.open(path);
        if (src == null) {
            return null;
        }
        VideoMeta meta = new VideoMeta(src.width, src.height, src.frameCount, src.exportFrameTable());
        src.close();
        return meta;
    }

    private static void uploadBlocking(Path path, boolean video, boolean originalQuality,
                                       @Nullable Callback callback) {
        Prepared prep = prepare(path, video, originalQuality);
        if (!prep.ok()) {
            report(callback, false, prep.message());
            return;
        }
        Path sendPath = prep.path();
        byte[] data;
        try {
            data = Files.readAllBytes(sendPath);
        } catch (Exception ex) {
            Projector.LOGGER.error("[Projector] 读取媒体失败", ex);
            report(callback, false, "\u8bfb\u53d6\u6587\u4ef6\u5931\u8d25");
            return;
        }
        int width = 0;
        int height = 0;
        int frames = 1;
        double fps = 10;
        long[] frameTable = new long[0];
        if (video) {
            VideoMeta meta = probeVideo(sendPath);
            if (meta == null) {
                report(callback, false, "\u8fd9\u4e2a\u89c6\u9891\u6253\u4e0d\u5f00\uff08"
                        + "\u6587\u4ef6\u53ef\u80fd\u5df2\u635f\u574f\uff0c\u6216\u683c\u5f0f\u4e0d\u652f\u6301\uff09");
                return;
            }
            width = meta.width();
            height = meta.height();
            if (meta.hasFrameTable()) {
                frames = meta.frames();
                frameTable = meta.frameTable();
            }
        } else {
            int[] sz = ImageCodec.size(data, 0, data.length);
            if (sz == null) {
                report(callback, false, "\u65e0\u6cd5\u89e3\u7801\u7684\u56fe\u7247\u683c\u5f0f");
                return;
            }
            width = sz[0];
            height = sz[1];
        }
        // 名字沿用**原文件**：媒体列表里显示「我的视频.mjpg」比
        // 「我的视频.compressed.mjpg」更符合玩家预期。
        sendPrepared(sendPath, path.getFileName().toString(), video, data,
                width, height, frames, fps, frameTable, callback);
    }

    /**
     * 【②】发送已经准备好的字节。
     *
     * <p>调用方负责：{@code prepare} → 读字节 → 算 SHA-1 → 探测尺寸/帧表
     * → 建本地纹理 → 调本方法。本方法只做「分片发出去 + 结束后刷新缓存」。</p>
     *
     * @param source      实际数据来源（压缩后的临时文件时，展示名仍用 displayName）
     * @param displayName 展示给玩家的文件名
     */
    public static void sendPrepared(Path source, String displayName, boolean video, byte[] data,
                                    int width, int height, int frameCount, double fps, long[] frameTable,
                                    @Nullable Callback callback) {
        if (data == null || data.length == 0) {
            report(callback, false, "\u6587\u4ef6\u4e3a\u7a7a");
            TransferLog.uploadEnd("", 0, 0, false, "文件为空");
            return;
        }
        try {
            final String hash = LocalMedia.sha1(data);
            final int chunk = Math.max(1024, ProjectorConfig.INSTANCE.uploadChunkBytes.get());
            final int session = (int) (System.nanoTime() & 0x7FFFFFFF);
            final byte[] payload = data;
            final String fHash = hash;
            final String fName = displayName == null || displayName.isEmpty()
                    ? source.getFileName().toString() : displayName;
            final boolean fVideo = video;
            final int fW = width, fH = height, fFrames = Math.max(1, frameCount);
            final double fFps = fps;
            final long[] fTable = frameTable == null ? new long[0] : frameTable;
            TransferLog.uploadStart(source, fName, hash, payload.length, fVideo,
                    (payload.length + chunk - 1) / chunk);
            long startedAt = System.currentTimeMillis();
            // 【rc-77 预检 1】服务端环境清单里已有同哈希且大小一致 ⇒ 一个字节都不发。
            // 这一条直接掐掉「重试一次就再传 24 MB」的浪费（实测同一份视频被传了 3 遍）。
            try {
                if (top.hmjmfabc.projector.client.ClientServerInfo.hasMedia(fHash)
                        && top.hmjmfabc.projector.client.ClientServerInfo.mediaSize(fHash) == payload.length) {
                    TransferLog.uploadSkip(fHash, payload.length, "服务端环境清单里已有同哈希");
                    MediaCache.finish(fHash, payload, source, fVideo, 0);
                    Minecraft.getInstance().execute(() -> {
                        if (callback != null) callback.onDone(true, fHash);
                    });
                    return;
                }
            } catch (Throwable ignored) {
            }
            // 【rc-77 预检 2】本次游戏已经成功传过同一份文件 ⇒ 也不重传
            //（防的是「界面重复提交 / 玩家连点」这种同一次会话里的重复）
            if (UPLOADED.contains(fHash)) {
                TransferLog.uploadSkip(fHash, payload.length, "本次游戏已成功上传过同一哈希");
                MediaCache.finish(fHash, payload, source, fVideo, 0);
                Minecraft.getInstance().execute(() -> {
                    if (callback != null) callback.onDone(true, fHash);
                });
                return;
            }
            try {
                // 【rc-77】窗口式发送：最多领先「窗口」个未确认分片，靠服务端的 MediaAck 推进。
                // 这样 socket 里永远只有一小段数据在排队，保活包不会被几十 MB 堵住
                //（rc-76 只把发送挪出主线程，队头阻塞仍在 ⇒ 服务端照样判 Timed out）。
                Ack ack = new Ack();
                ACTIVE_ACKS.put(session, ack);
                int offset = 0;
                boolean first = true;
                long paceSince = System.nanoTime();
                long paceBytes = 0L;
                long lastReportMs = 0L;
                final long paceRate = paceBytesPerSec();
                String failure = null;
                while (offset < payload.length && failure == null && !ack.alreadyHave) {
                    // 1) 窗口内尽量发
                    while (offset < payload.length && ack.window.canSend(offset - ack.window.acked())) {
                        if (Minecraft.getInstance().getConnection() == null) {
                            failure = "连接已断开（上传中断；服务端会丢弃未完成的分片）";
                            break;
                        }
                        int len = Math.min(chunk, payload.length - offset);
                        byte[] part = new byte[len];
                        System.arraycopy(payload, offset, part, 0, len);
                        boolean last = offset + len >= payload.length;
                        try {
                            PacketDistributor.sendToServer(new Payloads.MediaUpload(
                                    session,
                                    first ? fHash : "",
                                    first ? fName : "",
                                    fVideo,
                                    fW, fH, fFrames, fFps,
                                    payload.length,
                                    first ? fTable : new long[0],
                                    offset, part, last));
                        } catch (Throwable t) {
                            // 被踢之后这里以前直接 NPE 冒到最外层（日志里那条 NullPointerException）
                            failure = "发送失败：" + t.getClass().getSimpleName()
                                    + "（多半是连接已断开）";
                            break;
                        }
                        offset += len;
                        first = false;
                        paceBytes += len;
                        final int sent = offset;
                        long nowMs = System.currentTimeMillis();
                        if (nowMs - lastReportMs >= 150L || sent >= payload.length) {
                            lastReportMs = nowMs;
                            Minecraft.getInstance().execute(() -> report(callback, true,
                                    "\u4e0a\u4f20\u4e2d " + sent + "/" + payload.length
                                            + "（已确认 " + ack.window.acked() + "）"));
                        }
                        sleepForPace(paceRate, paceBytes, paceSince);
                        if (ack.alreadyHave) break;
                    }
                    if (failure != null || ack.alreadyHave) break;
                    if (offset >= payload.length) break;      // 发完了，等最终确认
                    // 2) 窗口满了：等确认推进；连续超时就放弃（不再无限重试，避免继续烧流量）
                    if (!waitForAck(ack, 15_000L)) {
                        ack.window.onTimeout();
                        if (ack.window.timeouts() > 6) {
                            failure = "服务端长时间未确认分片（网络太差或连接已断），已停止上传";
                        }
                    }
                }
                if (failure == null && !ack.alreadyHave && !waitForDone(ack, 20_000L)) {
                    failure = "服务端未确认上传完成（" + (ack.reason == null ? "超时" : ack.reason) + "）";
                }
                ACTIVE_ACKS.remove(session);
                if (failure != null) {
                    TransferLog.uploadEnd(fHash, payload.length, System.currentTimeMillis() - startedAt,
                            false, failure);
                    final String msg = failure;
                    Minecraft.getInstance().execute(() -> {
                        if (callback != null) callback.onDone(false, msg);
                    });
                    return;
                }
                if (ack.alreadyHave) {
                    TransferLog.uploadSkip(fHash, payload.length, "服务端已有同哈希（服务端让客户端停手）");
                } else {
                    UPLOADED.add(fHash);
                    TransferLog.uploadEnd(fHash, payload.length, System.currentTimeMillis() - startedAt,
                            true, null);
                }
                Minecraft.getInstance().execute(() ->
                        report(callback, true, "\u4e0a\u4f20\u5b8c\u6210"));
                MediaCache.finish(fHash, payload, source, fVideo, 0);
                Minecraft.getInstance().execute(() -> {
                    if (callback != null) {
                        callback.onDone(true, fHash);
                    }
                });
            } catch (Throwable t) {
                TransferLog.uploadEnd(fHash, payload.length, System.currentTimeMillis() - startedAt,
                        false, t.toString());
                Projector.LOGGER.error("[Projector] 媒体上传失败", t);
                Minecraft.getInstance().execute(() -> {
                    if (callback != null) {
                        callback.onDone(false, "\u4e0a\u4f20\u5931\u8d25\uff1a" + t.getMessage());
                    }
                });
            }
        } catch (Exception ex) {
            TransferLog.uploadEnd("", data == null ? 0 : data.length, 0, false, ex.toString());
            Projector.LOGGER.error("[Projector] 媒体上传失败", ex);
            report(callback, false, "\u4e0a\u4f20\u5931\u8d25");
        }
    }

    /** 【rc-76】上传节奏（字节/秒）。0 = 不限速；本地网络（单人/联机房间）自动不限速。 */
    private static long paceBytesPerSec() {
        try {
            if (top.hmjmfabc.projector.client.ClientServerInfo.unlimited()) return 0L;
            return Math.max(0L, ProjectorConfig.INSTANCE.uploadPaceBytesPerSec.get());
        } catch (Throwable t) {
            return 4L * 1024 * 1024;
        }
    }

    /**
     * 【rc-76】按目标速率让出时间片。
     *
     * <p>为什么必须限速：{@code PacketDistributor.sendToServer} 是**非阻塞**的，
     * 一旦发送速度远超真实上行带宽，未发出去的分片会堆在客户端 netty 的写缓冲里
     * （24 MB 的文件能堆十几 MB），既吃内存，也容易被对端当成洪水。</p>
     */
    private static void sleepForPace(long rate, long bytes, long sinceNanos) {
        long sleepMs;
        if (rate <= 0) {
            sleepMs = 2L;                                     // 本地网络：只做轻微让出
        } else {
            long elapsedMs = (System.nanoTime() - sinceNanos) / 1_000_000L;
            sleepMs = bytes * 1000L / rate - elapsedMs;       // 目标时刻 - 已用时间
        }
        if (sleepMs <= 0) return;
        try {
            Thread.sleep(Math.min(sleepMs, 1000L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void report(@Nullable Callback callback, boolean success, String message) {
        if (callback == null) return;
        // 早退路径（空文件 / 超限 / 格式不识别）都发生在工作线程上，
        // 而调用方会直接改界面状态，因此这里统一切回主线程。
        Minecraft.getInstance().execute(() -> {
            if (success) {
                callback.onProgress(1f, message);
            } else {
                // 失败必须走 onDone，否则调用方永远等不到结束事件
                callback.onDone(false, message);
            }
        });
    }

    /**
     * 【rc-79】上传专用线程。
     *
     * <p>一次上传会**等**服务端的逐片确认（可达几十秒），绝不能占用
     * {@code MediaCache.worker()}（那个池还要处理媒体解码与下载分片）。
     * 以前上传跑在媒体池上：上传期间下载全部停摆，而下载分片处理又排在
     * 同一个池里 —— 这类「长任务 + 单线程池」的组合正是死锁与假死的温床。</p>
     */
    private static final java.util.concurrent.ExecutorService UPLOAD_WORKER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "Projector-Upload");
                t.setDaemon(true);
                return t;
            });

    /** 上传专用线程池（调用方用它提交上传任务）。 */
    public static java.util.concurrent.ExecutorService worker() {
        return UPLOAD_WORKER;
    }

    /** 【rc-77】正在等待服务端确认的上传会话：session -> 流控状态。 */
    private static final java.util.Map<Integer, Ack> ACTIVE_ACKS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 【rc-77】本次游戏里已经**成功上传过**的哈希：同样的文件不再重传第二遍。 */
    private static final java.util.Set<String> UPLOADED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 一次上传的确认状态。字段都被网络线程写、上传线程读，所以用 volatile。 */
    private static final class Ack {
        final UploadWindow window = new UploadWindow();
        volatile boolean done;
        volatile boolean alreadyHave;
        volatile String reason;
    }

    /**
     * 【rc-77】服务端确认（**网络线程**调用，只碰并发安全的状态，不切主线程）。
     *
     * @param received    服务端已确认收到的字节数（可能跳跃）
     * @param done        服务端已落盘并通过哈希校验
     * @param alreadyHave 服务端已有同哈希文件，客户端必须立刻停手
     */
    public static void onAck(int session, int received, boolean done, boolean alreadyHave, String reason) {
        Ack a = ACTIVE_ACKS.get(session);
        if (a == null) return;
        a.window.onAck(received);
        if (alreadyHave) a.alreadyHave = true;
        if (done) a.done = true;
        if (reason != null && !reason.isEmpty()) a.reason = reason;
    }

    /** 等窗口推进（或服务端喊停）。 */
    private static boolean waitForAck(Ack a, long timeoutMs) {
        int before = a.window.acked();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (a.alreadyHave || a.window.acked() > before) return true;
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** 等「服务端已落盘并通过校验」的最终确认。 */
    private static boolean waitForDone(Ack a, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (a.done || a.alreadyHave) return true;
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** 是否允许添加媒体。
     *
     * <p>【② 用户要求】<b>默认放开</b>：普通玩家也能上传，只是配额更小
     * （普通 4 MB / 64 MB，管理员 16 MB / 256 MB，见 {@link #limitFor(boolean)}）。
     * 只有当服务端把 {@code media.onlyAdminCanUpload} 打开时，才要求权限等级 4。</p>
     *
     * <p>真正的判定在服务端（{@code PlanePermissions.canAddMedia}）——这里只是提前拦一下、
     * 免得白跑一次上传；<b>客户端拦不住作弊客户端，配额与权限永远由服务端裁定</b>。</p>
     */
    public static boolean canUpload() {
        var player = Minecraft.getInstance().player;
        if (player == null) return false;
        if (Minecraft.getInstance().hasSingleplayerServer()) return true;
        if (top.hmjmfabc.projector.client.ClientServerInfo.unlimited()) return true;
        // 服务端把上传锁回管理员时才拦人；默认（放开）直接放行
        if (top.hmjmfabc.projector.client.ClientServerInfo.onlyAdminCanUpload()
                && player.getPermissionLevel() < 4) {
            player.displayClientMessage(Component.translatable("projector.msg.only_op4"), true);
            return false;
        }
        return true;
    }

    /** 【②】本次上传适用的字节上限（供界面显示）。 */
    public static long limitFor(boolean video) {
        if (top.hmjmfabc.projector.client.ClientServerInfo.unlimited()) return Long.MAX_VALUE;
        return video ? top.hmjmfabc.projector.client.ClientServerInfo.videoLimit()
                : top.hmjmfabc.projector.client.ClientServerInfo.imageLimit();
    }

    /** 【②】把上限写成人话（MB）。 */
    public static String limitText(boolean video) {
        long l = limitFor(video);
        return l == Long.MAX_VALUE ? "\u4e0d\u9650" : (l / 1024 / 1024) + " MB";
    }
}

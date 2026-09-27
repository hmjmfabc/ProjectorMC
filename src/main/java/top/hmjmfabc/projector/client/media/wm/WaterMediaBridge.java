package top.hmjmfabc.projector.client.media.wm;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import top.hmjmfabc.projector.Projector;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 与 <b>WaterMedia</b> 模组的可选桥接（27.1.2）。
 *
 * <p>装了 WaterMedia 时，视频交给它解码播放（因此能放 MP4 / WebM / MKV 等常见格式）；
 * 没装时一切照旧走本项目自己的 MJPEG / ZIP 后端。</p>
 *
 * <p><b>为什么全部用反射</b>（刻意为之）：</p>
 * <ol>
 *   <li><b>许可</b>：WaterMedia 是 <i>PolyForm Strict 1.0.0</i>（源码可见但禁止使用/分发），
 *       与本项目 Apache-2.0 不兼容。我们**不引用它的源码、不编译链接它的类、不打包它的 jar**，
 *       只按它公开 API 的**类名/方法名字符串**在运行时反射调用 —— 两边是各自独立的程序。</li>
 *   <li><b>可选依赖</b>：反射让「没装」成为一条完全不触发的分支，连类加载都不会发生。</li>
 *   <li><b>它有两代互不兼容的 API</b>（27.1.2 实测踩到）：工作区那份源码是
 *       <b>v3（3.0.0.23）</b>，而玩家装的是 <b>v2（2.1.37）</b>：
 *       v3 是 {@code MediaAPI.getMrl/createPlayer}，v2 是
 *       {@code new VideoPlayer(Executor).start(URI)} + {@code preRender()/getTime()/seekTo()}。
 *       所以这里**同时支持两代**，按「哪个类真的存在」自动选。</li>
 * </ol>
 */
public final class WaterMediaBridge {
    private WaterMediaBridge() {
    }

    public static final String MOD_ID = "watermedia";

    /** 「WaterMedia 能直接放、我们自己放不了」的常见本地视频格式。 */
    private static final Set<String> NATIVE_EXT = Set.of(
            "mp4", "m4v", "webm", "mkv", "mov", "avi", "flv", "wmv", "3gp", "mpg", "mpeg", "ts", "m2ts");

    /** 它的大版本方言（两代 API 互不兼容）。 */
    public enum Dialect {
        /** 没装 / 反射不到。 */
        NONE("无"),
        /** v3.x：{@code org.watermedia.api.media.MediaAPI}。 */
        V3("v3"),
        /** v2.x：{@code org.watermedia.api.player.videolan.VideoPlayer}。 */
        V2("v2");

        private final String label;

        Dialect(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private static Dialect dialect;
    private static boolean warned;

    /** 装了 WaterMedia 且能反射到它某一代的 API 吗（结果缓存）。 */
    public static boolean available() {
        return dialect() != Dialect.NONE;
    }

    /** 当前识别到的是哪一代（NONE = 不可用）。 */
    public static synchronized Dialect dialect() {
        if (dialect != null) {
            return dialect;
        }
        boolean loaded;
        try {
            loaded = ModList.get() != null && ModList.get().isLoaded(MOD_ID);
        } catch (Throwable ignored) {
            loaded = false;
        }
        if (!loaded) {
            dialect = Dialect.NONE;
            return dialect;
        }
        if (bindV3()) {
            dialect = Dialect.V3;
        } else if (bindV2()) {
            dialect = Dialect.V2;
        } else {
            dialect = Dialect.NONE;
            warned = true;
            Projector.LOGGER.warn("[Projector][视频] 装了 WaterMedia 但两代 API 都反射不到，回退内置后端");
        }
        if (!warned) {
            Projector.LOGGER.info("[Projector][视频] WaterMedia 兼容层就绪：识别为 {} 代 API", dialect.label());
        }
        return dialect;
    }

    /** 供诊断。 */
    public static String describeDialect() {
        return dialect().label();
    }

    /** 名字（含扩展名）是不是 WaterMedia 能直接放的格式。 */
    public static boolean isNativeVideo(String fileName) {
        if (fileName == null) {
            return false;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return false;
        }
        return NATIVE_EXT.contains(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** 渲染线程（v2 的播放器要它来上传帧；由渲染路径记下来）。 */
    private static volatile Thread renderThread;

    /** 由渲染路径调用（{@code Minecraft.getRunningThread()} 是 protected）。 */
    public static void noteRenderThread(Thread thread) {
        if (thread != null) {
            renderThread = thread;
        }
    }

    // ------------------------------------------------------------------ v3 反射句柄

    private static Method v3GetMrl;
    private static Method v3CreatePlayer;
    private static Method v3GlEngine;

    private static synchronized boolean bindV3() {
        if (v3GetMrl != null) {
            return true;
        }
        try {
            Class<?> api = Class.forName("org.watermedia.api.media.MediaAPI");
            v3GetMrl = api.getMethod("getMrl", URI.class);
            v3CreatePlayer = api.getMethod("createPlayer", Class.forName("org.watermedia.api.media.MRL"),
                    Supplier.class, Supplier.class);
            v3GlEngine = api.getMethod("glEngine", Thread.class, Executor.class);
            return true;
        } catch (Throwable t) {
            v3GetMrl = null;
            return false;
        }
    }

    // ------------------------------------------------------------------ v2 反射句柄

    private static Constructor<?> v2Ctor;
    private static Method v2Start;
    private static Method v2PreRender;
    private static Method v2Texture;
    private static Method v2Width;
    private static Method v2Height;
    private static Method v2Time;
    private static Method v2Duration;
    private static Method v2SeekTo;
    private static Method v2SetPauseMode;
    private static Method v2IsPaused;
    private static Method v2IsPlaying;
    private static Method v2SetRepeat;
    private static Method v2Release;

    private static synchronized boolean bindV2() {
        if (v2Ctor != null) {
            return true;
        }
        try {
            Class<?> player = Class.forName("org.watermedia.api.player.videolan.VideoPlayer");
            Class<?> base = Class.forName("org.watermedia.api.player.videolan.BasePlayer");
            v2Ctor = player.getConstructor(Executor.class);
            v2Start = base.getMethod("start", URI.class);
            v2PreRender = player.getMethod("preRender");
            v2Texture = player.getMethod("texture");
            v2Width = player.getMethod("width");
            v2Height = player.getMethod("height");
            v2Time = base.getMethod("getTime");
            v2Duration = base.getMethod("getDuration");
            v2SeekTo = base.getMethod("seekTo", long.class);
            v2SetPauseMode = base.getMethod("setPauseMode", boolean.class);
            v2IsPaused = base.getMethod("isPaused");
            v2IsPlaying = base.getMethod("isPlaying");
            v2SetRepeat = base.getMethod("setRepeatMode", boolean.class);
            v2Release = base.getMethod("release");
            return true;
        } catch (Throwable t) {
            v2Ctor = null;
            return false;
        }
    }

    // ------------------------------------------------------------------ 打开

    /**
     * 打开一个视频文件（阻塞，必须在工作线程调用）。
     *
     * @return 会话；失败返回 {@code null}（调用方回退内置后端）
     */
    public static Session open(Path file, boolean loop) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        Dialect d = dialect();
        try {
            if (d == Dialect.V3) {
                return openV3(file, loop);
            }
            if (d == Dialect.V2) {
                return openV2(file, loop);
            }
        } catch (Throwable t) {
            Projector.LOGGER.warn("[Projector][视频] WaterMedia({}) 打开失败，回退内置后端：{}（{}）",
                    d.label(), file, t.toString());
        }
        return null;
    }

    private static Session openV3(Path file, boolean loop) throws Exception {
        Object mrl = v3GetMrl.invoke(null, file.toUri());
        if (!awaitLoaded(mrl, 6000L)) {
            Projector.LOGGER.warn("[Projector][视频] WaterMedia v3 打不开这个文件（MRL 未就绪）：{}", file);
            return null;
        }
        final Thread glThread = renderThread != null ? renderThread : Thread.currentThread();
        Supplier<Object> gfx = () -> {
            try {
                return v3GlEngine.invoke(null, glThread, Minecraft.getInstance());
            } catch (Throwable t) {
                Projector.LOGGER.warn("[Projector][视频] 创建 GL 引擎失败：{}", t.toString());
                return null;
            }
        };
        Object player = v3CreatePlayer.invoke(null, mrl, gfx, null);   // sfx = null：只要画面
        if (player == null) {
            return null;
        }
        Session session = new Session(Dialect.V3, player, file);
        session.setLooping(loop);
        session.start();
        logOpened(session);
        return session;
    }

    private static Session openV2(Path file, boolean loop) throws Exception {
        Object player = newV2Player();
        Session session = new Session(Dialect.V2, player, file);
        session.setLooping(loop);
        v2Start.invoke(player, file.toUri());   // v2 自己异步起播，稍后 ready()
        logOpened(session);
        return session;
    }

    /**
     * 构造 v2 的播放器 —— <b>必须在渲染线程</b>。
     *
     * <p>对着玩家装的 v2 jar 反编译确认过：{@code VideoPlayer.<init>} 里就调了
     * {@code RenderAPI.createTexture()}（= {@code GL11.glGenTextures}），
     * 而我们的打开动作在工作线程上跑 ⇒ 不上渲染线程就是「没有 GL 上下文」。</p>
     *
     * <p>（它的 {@code release()} 反而不用管：内部把 {@code deleteTexture} 丢给了我们传进去的
     * 渲染执行器，从任何线程调都行。）</p>
     */
    private static Object newV2Player() throws Exception {
        Thread rt = renderThread;
        if (rt == null || Thread.currentThread() == rt) {
            return v2Ctor.newInstance(renderExecutor());
        }
        final Object[] holder = new Object[1];
        final Throwable[] error = new Throwable[1];
        final CountDownLatch latch = new CountDownLatch(1);
        Minecraft.getInstance().execute(() -> {
            try {
                holder[0] = v2Ctor.newInstance(renderExecutor());
            } catch (Throwable t) {
                error[0] = t;
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(10L, TimeUnit.SECONDS)) {
            throw new IllegalStateException("渲染线程 10 秒没响应");
        }
        if (error[0] != null) {
            throw new IllegalStateException("渲染线程构造播放器失败：" + error[0], error[0]);
        }
        return holder[0];
    }

    /** v2 要一个「渲染线程执行器」（上传帧、删纹理都用它）。 */
    private static Executor renderExecutor() {
        return task -> Minecraft.getInstance().execute(task);
    }

    private static void logOpened(Session session) {
        Projector.LOGGER.info("[Projector][视频] 已交给 WaterMedia {} 播放：{}",
                session.dialect().label(), session.name());
    }

    /** 轮询 v3 的 MRL 状态直到 LOADED（或超时）。 */
    private static boolean awaitLoaded(Object mrl, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String status = mrlStatus(mrl);
            if ("LOADED".equals(status)) {
                return true;
            }
            if ("FAILED".equals(status) || "INVALID".equals(status) || "UNKNOWN".equals(status)) {
                return false;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static String mrlStatus(Object mrl) {
        try {
            return String.valueOf(mrl.getClass().getMethod("status").invoke(mrl));
        } catch (Throwable t) {
            try {
                return String.valueOf(mrl.getClass().getField("status").get(mrl));
            } catch (Throwable t2) {
                return "UNKNOWN";
            }
        }
    }

    // ------------------------------------------------------------------ 会话

    /** 一个 WaterMedia 播放会话（两代 API 的差异都收在这里）。 */
    public static final class Session {
        private static final AtomicInteger NEXT_ID = new AtomicInteger();

        private final Dialect dialect;
        private final Object player;
        private final Path file;
        private final ResourceLocation textureLocation;
        private boolean paused;
        private boolean released;
        private Boolean loopState;
        private int preRenderFailures;

        Session(Dialect dialect, Object player, Path file) {
            this.dialect = dialect;
            this.player = player;
            this.file = file;
            this.textureLocation = Projector.id("dynamic/watermedia_" + NEXT_ID.incrementAndGet());
            // 把它的 GL 纹理包成 MC 能认的贴图：我们的渲染管线只认 ResourceLocation
            try {
                Minecraft.getInstance().getTextureManager()
                        .register(textureLocation, new ForwardTexture(this));
            } catch (Throwable t) {
                Projector.LOGGER.warn("[Projector][视频] 注册纹理失败：{}", t.toString());
            }
        }

        public Dialect dialect() {
            return dialect;
        }

        public String name() {
            return file == null ? "?" : file.getFileName().toString();
        }

        public ResourceLocation location() {
            return textureLocation;
        }

        /**
         * 每帧渲染前调一次（**必须在渲染线程**）。
         *
         * <p>v2 的 {@code preRender()} 负责把解码好的那一帧上传到它的 GL 纹理；
         * v3 由引擎自己上传，这里是空操作。</p>
         */
        public void preRender() {
            if (dialect != Dialect.V2 || released) {
                return;
            }
            try {
                v2PreRender.invoke(player);
            } catch (Throwable t) {
                if (preRenderFailures++ < 3) {
                    Projector.LOGGER.debug("[Projector][视频] WaterMedia v2 preRender 失败：{}", t.toString());
                }
            }
        }

        /** GL 纹理 id（未就绪时为 0）。 */
        public long texture() {
            try {
                Object value = call("texture");
                return value instanceof Number n ? n.longValue() : 0L;
            } catch (Throwable t) {
                return 0L;
            }
        }

        public int width() {
            return intOf(v2Width, "width");
        }

        public int height() {
            return intOf(v2Height, "height");
        }

        public long timeMs() {
            return longOf(v2Time, "time");
        }

        public long durationMs() {
            return longOf(v2Duration, "duration");
        }

        /** 纹理有了、尺寸也有了 = 可以画了。 */
        public boolean ready() {
            return !released && texture() != 0L && width() > 1 && height() > 1;
        }

        public boolean playing() {
            try {
                Object value = v2IsPlaying != null ? v2IsPlaying.invoke(player) : call("playing");
                return value instanceof Boolean b && b;
            } catch (Throwable t) {
                return false;
            }
        }

        /** 当前状态的文字（诊断用）。 */
        public String state() {
            try {
                return String.valueOf(call(dialect == Dialect.V2 ? "getStateName" : "status"));
            } catch (Throwable t) {
                return "?";
            }
        }

        /** v3 需要显式 start；v2 在 open 时已经 {@code start(URI)} 了。 */
        public void start() {
            if (dialect == Dialect.V3) {
                invoke("start");
            }
        }

        public void setPaused(boolean value) {
            if (paused == value || released) {
                return;
            }
            paused = value;
            if (dialect == Dialect.V2) {
                // v2：先设暂停模式，再按目标状态 play/pause（与 v3 的语义对齐）
                invokeRaw(v2SetPauseMode, value);
                invoke(value ? "pause" : "play");
            } else {
                invoke("pause", value);
            }
        }

        /** 是否处于暂停（v2 直接问它，v3 用我们记的状态）。 */
        public boolean paused() {
            if (v2IsPaused != null) {
                try {
                    Object value = v2IsPaused.invoke(player);
                    return value instanceof Boolean b && b;
                } catch (Throwable ignored) {
                    // 落到本地记录的状态
                }
            }
            return paused;
        }

        public void seek(long ms) {
            if (released) {
                return;
            }
            long target = Math.max(0L, ms);
            if (v2SeekTo != null) {
                invokeRaw(v2SeekTo, target);
            } else {
                invoke("seek", target);
            }
        }

        /** 循环播放（v2 有 setRepeatMode；v3 交给调用方按时间重播）。 */
        public void setLooping(boolean value) {
            if (loopState != null && loopState == value) {
                return;   // 每帧都会调，值没变就别劳烦反射
            }
            loopState = value;
            if (v2SetRepeat != null) {
                invokeRaw(v2SetRepeat, value);
            }
        }

        public void release() {
            if (released) {
                return;
            }
            released = true;
            try {
                Minecraft.getInstance().getTextureManager().release(textureLocation);
            } catch (Throwable ignored) {
                // 贴图没注册成功也无所谓
            }
            if (v2Release != null) {
                invokeRaw(v2Release);
            } else {
                invoke("release");
            }
        }

        // ---- 反射小工具 ----

        private Object call(String method) throws Exception {
            return player.getClass().getMethod(method).invoke(player);
        }

        private int intOf(Method v2Method, String v3Name) {
            try {
                Object value = v2Method != null ? v2Method.invoke(player) : call(v3Name);
                return value instanceof Number n ? n.intValue() : 0;
            } catch (Throwable t) {
                return 0;
            }
        }

        private long longOf(Method v2Method, String v3Name) {
            try {
                Object value = v2Method != null ? v2Method.invoke(player) : call(v3Name);
                return value instanceof Number n ? n.longValue() : 0L;
            } catch (Throwable t) {
                return 0L;
            }
        }

        private void invoke(String method, Object arg) {
            try {
                player.getClass().getMethod(method, boolean.class).invoke(player, arg);
            } catch (Throwable t) {
                Projector.LOGGER.debug("[Projector][视频] WaterMedia {} 调用失败：{}", method, t.toString());
            }
        }

        private void invoke(String method) {
            try {
                player.getClass().getMethod(method).invoke(player);
            } catch (Throwable t) {
                Projector.LOGGER.debug("[Projector][视频] WaterMedia {} 调用失败：{}", method, t.toString());
            }
        }

        private void invoke(String method, long arg) {
            try {
                player.getClass().getMethod(method, long.class).invoke(player, arg);
            } catch (Throwable t) {
                Projector.LOGGER.debug("[Projector][视频] WaterMedia {} 调用失败：{}", method, t.toString());
            }
        }

        private void invokeRaw(Method method) {
            try {
                method.invoke(player);
            } catch (Throwable t) {
                Projector.LOGGER.debug("[Projector][视频] WaterMedia 调用失败：{}", t.toString());
            }
        }

        private void invokeRaw(Method method, Object arg) {
            try {
                method.invoke(player, arg);
            } catch (Throwable t) {
                Projector.LOGGER.debug("[Projector][视频] WaterMedia 调用失败：{}", t.toString());
            }
        }
    }

    /** 只做一件事：把我方的 {@code getId()} 转发给 WaterMedia 的 GL 纹理 id。 */
    private static final class ForwardTexture extends AbstractTexture {
        private final Session session;

        ForwardTexture(Session session) {
            this.session = session;
        }

        @Override
        public int getId() {
            return (int) session.texture();
        }

        @Override
        public void load(net.minecraft.server.packs.resources.ResourceManager manager) {
            // 纹理由 WaterMedia 自己创建，这里不需要加载任何资源
        }
    }
}

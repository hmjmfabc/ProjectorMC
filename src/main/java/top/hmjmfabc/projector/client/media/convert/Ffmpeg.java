package top.hmjmfabc.projector.client.media.convert;

import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import top.hmjmfabc.projector.Projector;
import top.hmjmfabc.projector.ProjectorConfig;
import top.hmjmfabc.projector.client.media.LocalMedia;
import top.hmjmfabc.projector.client.media.MediaCache;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * ffmpeg 定位器。
 *
 * <p><b>为什么需要外部程序：</b>H.264 / VP9 / AV1 这些常见编码的解码器都是几十万行的
 * C 代码。纯 Java 实现（例如 JCodec）需要引入第三方依赖，而本项目的构建环境是离线的，
 * 也不允许打包任何 {@code .so} 文件（Android 上没法用）。
 * 因此「把 MP4 转成本模组支持的格式」这件事交给 ffmpeg 来做——
 * 它解码质量最好、支持格式最全，而且不增加模组体积。</p>
 *
 * <p><b>两条铁律（都踩过坑）：</b></p>
 * <ol>
 *   <li><b>绝不能在渲染线程上探测。</b>启动进程、等它退出都是阻塞操作，
 *       放在 {@code Screen.init()} 里会让整个界面卡死。界面请用
 *       {@link #locateAsync(Runnable)}。</li>
 *   <li><b>绝不能先读进程输出再等超时。</b>{@code readNBytes()} 会一直阻塞到读满
 *       或流关闭为止——如果进程启动了却不输出、也不退出（在 Android 上从
 *       游戏进程拉起 Termux 的二进制很容易这样），渲染线程会<b>永久</b>卡住。
 *       这里的做法是把输出重定向到临时文件，<b>先 waitFor 带超时</b>，再去读文件。</li>
 * </ol>
 */
public final class Ffmpeg {

    private enum State { IDLE, SEARCHING, FOUND, MISSING }

    private static volatile State state = State.IDLE;
    @Nullable
    private static volatile Path found;
    private static volatile String version = "";

    private Ffmpeg() {
    }

    /** Windows 上可执行文件带 .exe 后缀。 */
    private static String exeName() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "ffmpeg.exe" : "ffmpeg";
    }

    // ------------------------------------------------------------------
    // 界面用的非阻塞接口
    // ------------------------------------------------------------------

    /** 是否正在后台探测。 */
    public static boolean isSearching() {
        return state == State.SEARCHING;
    }

    /** 已确认可用（不阻塞，未探测完返回 false）。 */
    public static boolean isReady() {
        return state == State.FOUND && found != null;
    }

    /** 已探测完且没找到。 */
    public static boolean isMissing() {
        return state == State.MISSING;
    }

    /**
     * 界面专用：<b>只做文件存在性检查，绝不启动进程。</b>
     *
     * <p>界面初始化跑在渲染线程上，只要可能阻塞就不能碰。之前的异步探测方案
     * 因为回调会重新触发界面重建，绕成无限递归，反而把界面卡成了空屏。
     * 现在界面只问这一句「有没有这个文件」，启动进程的验证推迟到真正点
     * 「开始转换」之后（那时已经在后台线程上）。</p>
     *
     * @return 第一个存在且可执行的候选路径；没有则 null
     */
    @Nullable
    public static Path quickPath() {
        if (found != null) return found;
        for (Path p : candidates()) {
            try {
                if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                    return p;
                }
            } catch (Throwable ignored) {
                // 单个候选查不动就跳过
            }
        }
        return null;
    }

    /** 探测是否已经有结果（成功或失败都算）。 */
    public static boolean isResolved() {
        return state == State.FOUND || state == State.MISSING;
    }

    @Nullable
    public static Path path() {
        return found;
    }

    public static String versionString() {
        return version;
    }

    /** 强制重新探测（玩家改了配置里的路径之后调用）。 */
    public static synchronized void invalidate() {
        state = State.IDLE;
        found = null;
        version = "";
    }

    /**
     * 在后台线程探测 ffmpeg，完成后回到主线程调用 {@code onDone}。
     *
     * <p>可以重复调用：已有结果时立即回调；正在探测时把回调排进队列。</p>
     */
    public static void locateAsync(@Nullable Runnable onDone) {
        if (state == State.FOUND || state == State.MISSING) {
            runOnMain(onDone);
            return;
        }
        if (state == State.SEARCHING) {
            // 已经在查了：把回调挂到轮询上，避免重复起进程
            pollUntilDone(onDone);
            return;
        }
        state = State.SEARCHING;
        // 用独立守护线程：不能占用 MediaCache 的工作线程（媒体下载会把它占满），
        // 也不能有任何可能阻塞主线程的路径。
        Thread t = new Thread(() -> {
            Path p = null;
            try {
                Projector.LOGGER.info("[Projector] 开始探测 ffmpeg…（后台线程）");
                p = search();
            } catch (Throwable e) {
                Projector.LOGGER.warn("[Projector] 探测 ffmpeg 时出错", e);
            } finally {
                found = p;
                state = p == null ? State.MISSING : State.FOUND;
                runOnMain(onDone);
            }
        }, "Projector-ffmpeg-locate");
        t.setDaemon(true);
        t.start();
    }

    /** 等待正在进行的探测结束（不阻塞主线程，只是排队轮询）。 */
    private static void pollUntilDone(@Nullable Runnable onDone) {
        if (onDone == null) return;
        MediaCache.worker().execute(() -> {
            long deadline = System.currentTimeMillis() + 20_000L;
            while (state == State.SEARCHING && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            runOnMain(onDone);
        });
    }

    /**
     * 把回调排到主线程执行。
     *
     * <p><b>绝不能内联执行。</b>界面在 {@code rebuildWidgets()} 里请求探测，
     * 探测完成后又要回调 {@code rebuildWidgets()}——如果 {@code execute()}
     * 在当前线程上直接 run，就会变成无限递归（栈溢出 -> 界面只剩一个标题）。
     * 这里统一走 {@code tell()}，保证至少延后一帧。</p>
     */
    private static void runOnMain(@Nullable Runnable r) {
        if (r == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.tell(r);
    }

    // ------------------------------------------------------------------
    // 实际探测（只在后台线程调用）
    // ------------------------------------------------------------------

    /** 阻塞式查找；找不到返回 null。 */
    @Nullable
    public static synchronized Path locateBlocking() {
        if (state == State.FOUND) return found;
        if (state == State.MISSING) return null;
        state = State.SEARCHING;
        Path p = search();
        found = p;
        state = p == null ? State.MISSING : State.FOUND;
        return p;
    }

    @Nullable
    private static Path search() {
        for (Path p : candidates()) {
            String v = probe(p);
            if (v != null) {
                version = v;
                Projector.LOGGER.info("[Projector] 找到 ffmpeg: {} -> {}", p, v);
                return p;
            }
        }
        Projector.LOGGER.info("[Projector] 未找到可用的 ffmpeg，视频转换功能不可用"
                + "（Android/Termux 上可执行 pkg install ffmpeg）");
        return null;
    }

    /** 候选路径，按优先级排列。 */
    private static List<Path> candidates() {
        List<Path> out = new ArrayList<>();
        addCandidate(out, ProjectorConfig.INSTANCE.ffmpegPath.get());
        addCandidate(out, LocalMedia.gameDir().resolve("ffmpeg").resolve(exeName()).toString());
        addCandidate(out, LocalMedia.gameDir().resolve("projector").resolve(exeName()).toString());
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
                if (!dir.isBlank()) addCandidate(out, Path.of(dir).resolve(exeName()).toString());
            }
        }
        addCandidate(out, "/data/data/com.termux/files/usr/bin/ffmpeg");
        addCandidate(out, "/usr/bin/ffmpeg");
        addCandidate(out, "/usr/local/bin/ffmpeg");
        addCandidate(out, "/opt/homebrew/bin/ffmpeg");
        addCandidate(out, "/opt/local/bin/ffmpeg");
        addCandidate(out, "C:\\ffmpeg\\bin\\ffmpeg.exe");
        return out;
    }

    private static void addCandidate(List<Path> out, String s) {
        if (s == null || s.isBlank()) return;
        try {
            Path p = Path.of(s.trim());
            if (!out.contains(p)) out.add(p);
        } catch (Throwable ignored) {
            // 非法路径（例如 Windows 路径出现在 Linux 上）：跳过
        }
    }

    /**
     * 尝试执行 {@code ffmpeg -version}。
     *
     * <p><b>输出重定向到临时文件</b>而不是管道：管道必须先读再等超时，
     * 读操作本身就可能永久阻塞；写文件则可以先 {@code waitFor(timeout)}，
     * 超时就杀掉进程。</p>
     */
    @Nullable
    private static String probe(Path exe) {
        Path tmp = null;
        Process p = null;
        try {
            if (!Files.isRegularFile(exe) || !Files.isExecutable(exe)) {
                return null;
            }
            tmp = Files.createTempFile("projector-ffmpeg-probe", ".txt");
            ProcessBuilder pb = new ProcessBuilder(exe.toString(), "-version")
                    .redirectErrorStream(true)
                    .redirectOutput(tmp.toFile())
                    .redirectInput(ProcessBuilder.Redirect.DISCARD);
            p = pb.start();
            // 先带超时等待，再读输出——顺序不能反
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0) return null;
            String first = "";
            for (String line : Files.readAllLines(tmp, java.nio.charset.StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    first = line;
                    break;
                }
            }
            if (!first.toLowerCase(Locale.ROOT).contains("ffmpeg")) return null;
            int sp = first.indexOf(" Copyright");
            return sp > 0 ? first.substring(0, sp) : first;
        } catch (Throwable t) {
            // 在 Android 上从游戏进程执行 Termux 的二进制可能直接失败：忽略即可
            return null;
        } finally {
            if (p != null && p.isAlive()) p.destroyForcibly();
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (Exception ignored) {
                    // 临时文件删不掉无所谓
                }
            }
        }
    }
}

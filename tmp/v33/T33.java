package top.hmjmfabc.projector.client.media;

import top.hmjmfabc.projector.client.media.net.OnlineVideoCache;
import top.hmjmfabc.projector.client.media.wm.WaterMediaVideos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 【27.1.3】在线视频「下载完了却放不了」的**联网回归测试**（T33）。
 *
 * <p>这一套需要联网，所以**不在 `run-all.sh` 里**（和 `tmp/v31` 一样手动跑）：</p>
 * <pre>bash tmp/run-verify.sh tmp/v33 T33   # 需带包名的那套 classpath，见文件末尾说明</pre>
 *
 * <p>它复现的是玩家实测那条 bug 的完整链路：</p>
 * <ol>
 *   <li>真的去 B 站解析 + 下载一个视频到 {@code cache/online/}（用临时目录，不碰玩家的缓存）；</li>
 *   <li>调一次 {@link LocalMedia#rescan()} —— <b>这正是游戏里每 2 秒会跑一次的后台重扫</b>，
 *       而它最后会 {@code BY_SHA1.clear(); putAll(重扫结果)}；</li>
 *   <li>重扫之后 {@link LocalMedia#byHash} 还找不找得到那份在线文件 ——
 *       找不到的话，{@link WaterMediaVideos#playablePath} 就会返回 null，
 *       于是「下载完成」但永远显示「本地还没有这份媒体」= 玩家看到的「能下载但放不了」。</li>
 * </ol>
 */
public class T33 {
    private static int passed;
    private static final List<String> failed = new ArrayList<>();

    /** 一个短小的公开投稿视频（av170001 / BV17x411w7KC 是同一个）。 */
    private static final String URL = "https://www.bilibili.com/video/BV17x411w7KC";
    /** 下载最多等多久（毫秒）。 */
    private static final long WAIT_MS = 150_000L;

    public static void main(String[] args) {
        Path root;
        try {
            root = Files.createTempDirectory("projector-t33-");
        } catch (Exception e) {
            System.out.println("== FAILED ==  无法建临时目录：" + e);
            System.exit(1);
            return;
        }
        LocalMedia.ROOT_FOR_TEST = root;
        try {
            String id = OnlineVideoCache.idFor(URL);
            String name = OnlineVideoCache.nameFor(URL);
            System.out.println("      本地身份=" + id + "  文件名=" + name);

            // ① 真的下（下载模式；流式不需要文件）
            OnlineVideoCache.request(URL, true);
            long t0 = System.currentTimeMillis();
            while (System.currentTimeMillis() - t0 < WAIT_MS && !OnlineVideoCache.ready(URL)) {
                OnlineVideoCache.Job j = OnlineVideoCache.job(URL);
                if (j != null && j.state == OnlineVideoCache.State.FAILED) {
                    break;
                }
                Thread.sleep(500L);
            }
            OnlineVideoCache.Job job = OnlineVideoCache.job(URL);
            String state = job == null ? "null" : job.state.name();
            System.out.println("      最终状态=" + state + "  " + OnlineVideoCache.describe(URL));
            check("能下载到本地（状态 " + state + "）", OnlineVideoCache.ready(URL));
            if (!OnlineVideoCache.ready(URL)) {
                report();
                return;
            }

            Path file = OnlineVideoCache.fileForId(id);
            check("按哈希能取到文件（" + (file == null ? "null" : file.getFileName() + " " + size(file)) + "）",
                    file != null && Files.size(file) > 0);
            check("缓存目录就是 project/cache/online/ 下（不写进素材目录）",
                    file != null && file.getParent().getFileName().toString().equals("online"));

            // ② ★ 回归点：重扫（游戏里每 2 秒一次）之后还要找得到
            LocalMedia.rescan();
            check("**重扫之后 byHash 仍然找得到**（回归：重扫冲掉登记 ⇒ 下载了放不了）",
                    LocalMedia.byHash(id) != null);
            check("重扫之后 hasWholeCached 仍为真（否则会再去要一遍整段）",
                    LocalMedia.hasWholeCached(id));

            // ③ 解码器真正调用的那一步
            Path playable = WaterMediaVideos.playablePath(id, name);
            check("playablePath 返回这份文件（外部解码器就是拿它去开的）",
                    playable != null && playable.equals(file));
        } catch (Throwable t) {
            check("测试自身没崩（" + t + "）", false);
        } finally {
            LocalMedia.ROOT_FOR_TEST = null;
            deleteTree(root);
        }
        report();
    }

    private static String size(Path p) {
        try {
            return "(" + (Files.size(p) / 1024) + " KB)";
        } catch (Exception e) {
            return "";
        }
    }

    private static void deleteTree(Path root) {
        try (var s = Files.walk(root)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // 清不掉就算了
                }
            });
        } catch (Exception ignored) {
            // 同上
        }
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  \u2705 " + name);
        } else {
            failed.add(name);
            System.out.println("  \u274c " + name);
        }
    }

    private static void report() {
        System.out.println();
        for (String f : failed) {
            System.out.println("  \u274c " + f);
        }
        System.out.println("== " + (failed.isEmpty() ? "ALL PASS ==" : "FAILED ==") + "  "
                + passed + " passed, " + failed.size() + " failed");
        if (!failed.isEmpty()) {
            System.exit(1);
        }
    }
}

import top.hmjmfabc.projector.client.media.MediaCache;
import top.hmjmfabc.projector.client.media.MediaUploader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * T19 ——【rc-79】「上传卡在正在准备…」= 单线程媒体池的**互等死锁**。
 *
 * <p>玩家实测：无法上传任何媒体，界面永远停在「正在准备…」，客户端日志里连
 * {@code [客户端][上传] 开始} 都没有。根因是线程结构：</p>
 *
 * <pre>
 * CachePrefetcher.start()  →  MediaCache.worker()（**单线程**）里跑 loop()
 * loop() → awaitDownload() → **阻塞等待**「本地出现该哈希」
 * 而让哈希出现的那一步（onMediaChunk → handleChunk）**也被提交到同一个池**
 * ⇒ 预取占着唯一线程等自己的后续任务 ⇒ 永久互等
 * ⇒ 上传任务排在后面，永远跑不到（界面停在「正在准备…」）
 * </pre>
 *
 * <p>所以这个测试断言的是**结构不变量**（而不是某个公式）：</p>
 * <ol>
 *   <li>媒体池必须能**并行**跑至少两个任务（单线程 ⇒ 长任务互相饿死）；</li>
 *   <li>「任务 A 等一个也提交到同一个池的任务 B」这种情况必须能在 3 秒内完成
 *       （这正是死锁的形状，单线程必超时）；</li>
 *   <li>上传必须用**自己的**执行器，不能与媒体池是同一个（上传会等几十秒的确认）；</li>
 *   <li>源码静态检查：{@code CachePrefetcher} 绝不能再调用 {@code MediaCache.worker()}
 *       ——等待循环放回共享池就是这次死锁的直接原因；</li>
 *   <li>看门狗提交 {@code MediaCache.submit} 存在且真的会执行任务。</li>
 * </ol>
 */
public final class T19 {

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        // ---------- 1) 媒体池能并行跑两个任务 ----------
        ExecutorService media = MediaCache.worker();
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < 2; i++) {
            media.execute(() -> {
                bothStarted.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        check("媒体池能同时跑 2 个任务（单线程池会卡住第二个）",
                bothStarted.await(3, TimeUnit.SECONDS), "3 秒内没等到两个任务同时开始");
        release.countDown();

        // ---------- 2) 死锁形状：A 等 B，而 B 也在同一个池里 ----------
        CountDownLatch bDone = new CountDownLatch(1);
        AtomicBoolean aFinished = new AtomicBoolean(false);
        AtomicLong aWaited = new AtomicLong();
        media.execute(() -> {                     // A：等 B
            long t0 = System.currentTimeMillis();
            try {
                bDone.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            aWaited.set(System.currentTimeMillis() - t0);
            aFinished.set(true);
        });
        media.execute(bDone::countDown);          // B：也在同一个池里
        long deadline = System.currentTimeMillis() + 4000L;
        while (!aFinished.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        check("「任务 A 等同一个池里的任务 B」不会死锁（这就是玩家那次卡住的形状）",
                aFinished.get(), "A 在 4 秒内没有拿到结果（等待了 " + aWaited.get() + " ms）");

        // ---------- 3) 上传用自己的执行器 ----------
        ExecutorService upload = MediaUploader.worker();
        check("上传执行器与媒体池不是同一个（上传等确认时不能占住解码线程）",
                upload != media, "两者是同一个 ExecutorService 实例");

        // ---------- 4) 上传任务确实提交到上传执行器 ----------
        CountDownLatch ran = new CountDownLatch(1);
        upload.execute(ran::countDown);
        check("上传执行器可用", ran.await(3, TimeUnit.SECONDS), "3 秒内没跑起来");

        // ---------- 5) 源码静态检查：预取循环不许再占媒体池 ----------
        String prefetcher = read("src/main/java/top/hmjmfabc/projector/client/media/CachePrefetcher.java");
        check("CachePrefetcher 不再把等待循环放进 MediaCache.worker()",
                prefetcher != null && !prefetcher.contains("MediaCache.worker()"),
                "源码里仍然出现 MediaCache.worker()（等待循环 + 单线程池 = 死锁）");
        String uploader = read("src/main/java/top/hmjmfabc/projector/client/media/MediaUploader.java");
        check("MediaUploader 自己持有上传线程（Projector-Upload）",
                uploader != null && uploader.contains("Projector-Upload"), "没找到上传线程名");
        String picker = read("src/main/java/top/hmjmfabc/projector/client/gui/MediaPickerScreen.java");
        check("界面提交上传任务时用的是上传执行器（不是媒体池）",
                picker != null && picker.contains("MediaUploader.worker()"),
                "MediaPickerScreen 里没有 MediaUploader.worker()");

        // ---------- 6) 看门狗提交可用 ----------
        CountDownLatch ran2 = new CountDownLatch(1);
        MediaCache.submit("T19 自检任务", ran2::countDown);
        check("MediaCache.submit（看门狗提交）会真的执行任务", ran2.await(3, TimeUnit.SECONDS), "");

        System.out.println();
        System.out.println("== " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " ==  " + pass + " passed");
        if (fail > 0) System.exit(1);
    }

    /** 读源码并**去掉注释**：静态检查必须看代码，不能被说明性注释误判。 */
    private static String read(String rel) {
        try {
            Path p = Path.of(rel);
            if (!Files.isRegularFile(p)) return null;
            List<String> lines = new ArrayList<>(Files.readAllLines(p));
            StringBuilder sb = new StringBuilder();
            boolean block = false;
            for (String line : lines) {
                String l = line;
                if (block) {
                    int e = l.indexOf("*/");
                    if (e < 0) continue;
                    l = l.substring(e + 2);
                    block = false;
                }
                int bs = l.indexOf("/*");
                if (bs >= 0) {
                    int be = l.indexOf("*/", bs + 2);
                    if (be < 0) {
                        l = l.substring(0, bs);
                        block = true;
                    } else {
                        l = l.substring(0, bs) + l.substring(be + 2);
                    }
                }
                int ls = l.indexOf("//");
                if (ls >= 0) l = l.substring(0, ls);
                sb.append(l).append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void check(String what, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("PASS " + what);
        } else {
            fail++;
            System.out.println("FAIL " + what + (detail.isEmpty() ? "" : "   ← " + detail));
        }
    }

    private T19() {
    }
}

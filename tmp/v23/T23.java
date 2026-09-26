package top.hmjmfabc.projector.client.media;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import top.hmjmfabc.projector.client.ClientServerInfo;
import top.hmjmfabc.projector.network.Payloads;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * T23 ——【rc-84】「服务端会给客户端下发客户端已有的视频，而不复用客户端缓存」。
 *
 * <p>两个原因，都在 {@link LocalMedia#rescan()} / {@link LocalMedia#byHash}:</p>
 * <ol>
 *   <li><b>重扫会把「下载缓存」的索引整个丢掉</b>：{@code BY_SHA1.putAll(cachedByHash)}
 *       写在扫描 cache 目录<b>之前</b>（那时还是空表），于是每次重扫之后
 *       {@code cache/<hash>.bin} 都查不到 —— 客户端明明有这份文件，还是去问服务端要。</li>
 *   <li><b>旧命名不认</b>：rc-83 之前整段视频写的是 {@code <hash>_f0.bin}
 *       （{@code cachePath(hash, 0, video)} 的老行为），而 {@code byHash} 的兜底只认
 *       {@code <hash>.bin} ⇒ 命不中 ⇒ 24 MB 的视频被重新下发一遍（每次都白烧流量）。</li>
 * </ol>
 *
 * <p>断言的是行为：本地有文件时 <b>一个字节都不该向服务端要</b>。</p>
 */
public final class T23 {

    private static int pass;
    private static int fail;

    private static final String H_WHOLE = "1111111111111111111111111111111111111111";   // cache/<hash>.bin
    private static final String H_LEGACY = "2222222222222222222222222222222222222222";  // cache/<hash>_f0.bin
    private static final String H_MISSING = "3333333333333333333333333333333333333333"; // 本地没有
    /** 清单里声明的「整段」大小；缓存文件必须与它一致才算一整份（24 MB 太大，测试用 4 KB 等价）。 */
    private static final long VIDEO_SIZE = 4096L;

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("projector-t23");
        Path cache = root.resolve("cache");
        Files.createDirectories(cache);
        LocalMedia.ROOT_FOR_TEST = root;

        // 一份「整段视频」的缓存，两种命名各来一个
        Files.write(cache.resolve(H_WHOLE + ".bin"), new byte[(int) VIDEO_SIZE]);
        Files.write(cache.resolve(H_LEGACY + "_f0.bin"), new byte[(int) VIDEO_SIZE]);
        // 一个「单帧」缓存：名字与旧整段同名规则，但大小与媒体大小不符 ⇒ 不能当成整段
        Files.write(cache.resolve(H_MISSING + "_f0.bin"), new byte[512]);

        // 服务端清单：这三份都是视频，大小 24 MB（用真实 payload 走一遍 accept）
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (String h : new String[]{H_WHOLE, H_LEGACY, H_MISSING}) {
            CompoundTag m = new CompoundTag();
            m.putString("h", h);
            m.putLong("s", VIDEO_SIZE);
            m.putBoolean("v", true);
            list.add(m);
        }
        tag.put("media", list);
        ClientServerInfo.accept(tag, true);
        check("服务端清单已注入（3 份视频）", ClientServerInfo.hasMedia(H_LEGACY), "");
        check("清单里的大小可查", ClientServerInfo.mediaSize(H_LEGACY) == VIDEO_SIZE,
                "实得=" + ClientServerInfo.mediaSize(H_LEGACY));

        LocalMedia.rescan();

        // ---- ① 重扫之后，缓存目录里的整段媒体必须仍能被 byHash 找到 ----
        check("重扫后 byHash 能命中 cache/<hash>.bin（整段）",
                LocalMedia.byHash(H_WHOLE) != null,
                "byHash 命中不了 ⇒ 会再去问服务端要一遍");
        check("byHash 把 cache/<hash>.bin 当成「整段」", LocalMedia.hasWhole(H_WHOLE), "");
        check("落盘路径正确", LocalMedia.byHash(H_WHOLE) != null
                        && LocalMedia.byHash(H_WHOLE).path().getFileName().toString().equals(H_WHOLE + ".bin"),
                "");

        // ---- ② 旧命名 <hash>_f0.bin 也要认作整段（大小与服务端清单一致才认） ----
        check("旧命名 cache/<hash>_f0.bin 被认作整段视频（大小一致）",
                LocalMedia.hasWhole(H_LEGACY),
                "旧缓存命中不了 ⇒ 服务端把这份 24 MB 的视频又下发一遍（玩家报的正是这条）");

        // ---- ③ 单帧缓存不能冒充整段 ----
        check("大小不符的 <hash>_f0.bin 不当成整段",
                !LocalMedia.hasWhole(H_MISSING),
                "把单帧当整段 ⇒ 解码失败、反复重下");

        // ---- ④ 端到端：本地有整段时，视频帧渲染不该发出任何请求 ----
        MediaCache.SENDER = req -> SENT.add(req);
        SENT.clear();
        for (int i = 0; i < 80; i++) {
            MediaCache.videoFrame(H_WHOLE, i % 5);
        }
        Thread.sleep(300L);
        check("本地已有整段视频：渲染 80 帧后仍然 0 个请求",
                SENT.isEmpty(), "实发=" + SENT.size() + " 个请求（每个请求都会让服务端下发媒体！）");

        // ---- ⑤ 旧命名（用户实际那份缓存）同样一个字节都不该要 ----
        SENT.clear();
        for (int i = 0; i < 80; i++) {
            MediaCache.videoFrame(H_LEGACY, i % 5);
        }
        Thread.sleep(300L);
        check("本地只有旧命名 <hash>_f0.bin 时：渲染 80 帧后仍然 0 个请求",
                SENT.isEmpty(), "实发=" + SENT.size() + " 个请求（rc-82 的老缓存就是这种情况）");

        // ---- ⑤b 【rc-88】改成「整段下载一次」的判断现在跑在工作线程上（渲染线程不许碰文件系统）：
        //          直接调那个入口，验证它同样认得出「本地已有整段」（含旧命名）⇒ 不升级、不请求。
        //          这一段是本条路径的**真正**覆盖：上面 ⑤ 只是「渲染线程自己不会发请求」。
        SENT.clear();
        MediaCache.escalateToWholeIfNeeded(H_WHOLE);
        MediaCache.escalateToWholeIfNeeded(H_LEGACY);
        Thread.sleep(200L);
        check("工作线程的升级判断：本地已有整段（新命名 / 旧命名）时都不改走整段下载",
                SENT.isEmpty(), "实发=" + SENT.size() + " 个请求 ⇒ 又变成「明明有还去要一遍」");

        SENT.clear();
        MediaCache.videoFrame(H_MISSING, 0);
        // 轮询而不是固定等待：媒体池是异步的，手机上 200ms 可能还没轮到（曾因此假失败）
        long deadline = System.currentTimeMillis() + 3000L;
        while (SENT.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25L);
        }
        check("本地没有的媒体：照常请求（不能把功能修没了）",
                !SENT.isEmpty(), "一个请求都没发 ⇒ 该下载的没下载");

        // ---- ⑤c 本地真的没有时：流式计数过阈值后，**工作线程会自动**把「整段下载」发出去 ----
        //（rc-88 起这个判断从渲染线程搬到了工作线程：渲染线程只打候选标记，
        //  所以这里必须让工作线程真的跑起来 —— 喂帧 + 让出 CPU，然后轮询整段请求。）
        SENT.clear();
        for (int i = 0; i < 90; i++) {
            MediaCache.videoFrame(H_MISSING, 100 + (i % 5));
            Thread.sleep(5L);
        }
        MediaCache.escalateToWholeIfNeeded(H_MISSING);    // 再叫一次：不许重复升级
        long wholeReqs = 0;
        long dl2 = System.currentTimeMillis() + 3000L;
        while (System.currentTimeMillis() < dl2) {
            wholeReqs = SENT.stream().filter(r -> r.frame() < 0).count();
            if (wholeReqs > 0) break;
            Thread.sleep(25L);
        }
        check("本地没有的媒体：过阈值后（工作线程）自动请求整段下载（frame=-1）",
                wholeReqs >= 1, "实发整段请求=" + wholeReqs);
        Thread.sleep(300L);
        long wholeReqs2 = SENT.stream().filter(r -> r.frame() < 0).count();
        check("同一份媒体只升级一次（重复触发不会再发整段请求）",
                wholeReqs2 == 1, "实发整段请求=" + wholeReqs2 + " 次");

        // （「本地没有的媒体照常请求」这条用例在 ⑤c 之前，见上）

        System.out.println();
        System.out.println("== " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " ==  " + pass + " passed");
        if (fail > 0) System.exit(1);
    }

    /** 客户端实际发出的请求。 */
    private static final List<Payloads.MediaRequest> SENT =
            Collections.synchronizedList(new ArrayList<>());

    private static void check(String what, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  ✅ " + what);
        } else {
            fail++;
            System.out.println("  ❌ " + what + (detail.isEmpty() ? "" : "  —— " + detail));
        }
    }
}

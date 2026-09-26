package top.hmjmfabc.projector.client.media;

/**
 * T24 —— 【rc-85】「视频的存在会让客户端间歇性卡顿（每 3 秒卡一次）」的回归。
 *
 * <p>查明的机制（读字节码 + 读代码，非猜测）：视频每显示一帧，都要把整帧像素写进
 * {@code NativeImage}（{@code setPixelRGBA} 逐像素，1024x576 一帧 <b>59 万次调用</b>），
 * 而这段代码以前<b>跑在主线程</b>上；同时每帧还 {@code new NativeImage} +
 * {@code new DynamicTexture}（glTexImage2D）并把旧纹理 {@code release} 掉。
 * 于是播放期间：主线程每帧啃十几毫秒、每秒几十 MB 分配 + GL 对象增删/删除 ⇒
 * 周期性 GC 与驱动停顿 ⇒ 玩家看到的「每 3 秒卡一次」。</p>
 *
 * <p>修法：①槽位<b>建一次永久复用</b>（不再每帧新建/销毁纹理）
 * ②像素填充<b>搬到工作线程</b>，主线程只做 {@code texture.upload()}
 * ③播放头已经越过的「过时帧」直接丢弃。</p>
 *
 * <p>本测试断言的是可验证的部分（NativeImage/GL 在这台机器上无法无头分配：
 * 缓存里只有 x64 的 LWJGL native，Termux 是 aarch64）：槽位挑选纯逻辑、
 * 结构不变量、统计接线、以及看门狗日志确实存在。</p>
 */
public final class T24 {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        // ---- ① 槽位挑选：纯逻辑 ----
        check("有空槽就用空槽", MediaCache.pickSlot(new int[]{-1}, new long[]{1}, 12) == 0, "");
        check("没满就新建（返回 slots.length）",
                MediaCache.pickSlot(new int[]{5, 6}, new long[]{1, 2}, 12) == 2, "");
        check("满了换掉最久未用的（LRU）",
                MediaCache.pickSlot(new int[]{5, 6, 7}, new long[]{30, 10, 20}, 3) == 1,
                "应当选 lastUsed 最小的那个（下标 1）");
        check("正在等 GPU 上传的槽位不许被选中",
                MediaCache.pickSlot(new int[]{5, 6, 7}, new long[]{1, Long.MAX_VALUE, 2}, 3) == 0,
                "选中了 pending 槽位 ⇒ 像素会被改、上传的内容就错了");
        check("上限为 1 时只用一个槽",
                MediaCache.pickSlot(new int[]{9}, new long[]{7}, 1) == 0, "");

        // ---- ② 统计接线（看门狗要靠它给数字）----
        long[] st = MediaCache.stats();
        check("MediaCache.stats() 给出 9 个数字（…/待上传/保护窗跳过）",
                st.length == 9, "实得 " + st.length);
        check("空载时统计全 0", st[0] == 0 && st[2] == 0 && st[3] == 0 && st[7] == 0 && st[8] == 0,
                java.util.Arrays.toString(st));

        // ---- ③ 结构不变量：不许回到「每帧新建纹理 + 主线程逐像素」 ----
        String mc = read("src/main/java/top/hmjmfabc/projector/client/media/MediaCache.java");
        check("能读到 MediaCache 源码", mc != null, "");
        check("存在 stageFrame（工作线程填像素）", mc != null && mc.contains("static void stageFrame("), "");
        check("存在 commitFrame（主线程只做 upload）",
                mc != null && mc.contains("private static void commitFrame("), "");
        // 图片路径（uploadImage）本来就会新建一次纹理 —— 那是每个媒体一次，不是每帧。
        // 这里只要求：**帧路径里只有 newSlot 新建纹理**，逐帧推进的 stageFrame/commitFrame 里一个都没有。
        String framePath = slice(mc, "static void stageFrame(", "private static String sanitize(");
        int texNew = count(framePath, "new DynamicTexture(");
        check("帧路径里只有 newSlot 新建过纹理（逐帧推进的那两个方法一个都没有）",
                texNew == 1, "实得 " + texNew + " 处（>1 说明又在每帧新建纹理了）");
        check("不再出现旧的每帧构造 `new Frame(loc, img)`",
                mc != null && !mc.contains("new Frame(loc, img)"), "");
        String stage = slice(mc, "static void stageFrame(", "private static void commitFrame(");
        String commit = slice(mc, "private static void commitFrame(", "/** 建一个新的帧槽位");
        check("像素填充在 stageFrame 里（工作线程）",
                stage.contains("ImageCodec.fillNativeImage("), "");
        check("commitFrame 里没有任何像素填充（主线程只剩 upload）",
                !commit.contains("fillNativeImage"), "主线程又在逐像素写了 ⇒ 必卡");
        check("commitFrame 里做的是 texture.upload()",
                commit.contains(".upload()"), "");
        check("Frames.pick 走的是纯函数 pickSlot（一份判定，不许抄第二份）",
                mc != null && mc.contains("return pickSlot(framesArr, usedArr, max);"), "");
        // rc-87 把保护窗做成「等着上传 + 刚上传完」两条 busy，并让 busy 槽位同时被
        // 「不许当空槽」和「不许进 LRU」两处排除 —— 断言改成对这两条一起校验，
        // 并补一条行为断言（全部在保护窗里时必须返回 -1 = 宁可掉帧也不复写）。
        check("槽位保护窗的两条都算 busy（等着上传 + 刚上传完、显卡可能还在用）",
                mc != null && mc.contains("f.pendingUpload >= 0 || now - f.lastUploadMs < hold"),
                "少任何一条都会去改显卡正在用的纹理");
        check("busy 槽位被排除在 LRU 之外（帧号填满 + lastUsed 拉满）",
                mc != null && mc.contains("framesArr[i] = busy ? Integer.MAX_VALUE : f.frameIndex")
                        && mc.contains("usedArr[i] = busy ? Long.MAX_VALUE : f.lastUsed"), "");
        check("全部槽位都在保护窗里时返回 -1（宁可掉帧，也不复写在用纹理）",
                MediaCache.pickSlot(new int[]{5, 6}, new long[]{Long.MAX_VALUE, Long.MAX_VALUE}, 2) == -1,
                "返回了槽位 ⇒ 会复写显卡还在用的纹理");

        // ---- ④ 看门狗：卡顿必须能落到日志上 ----
        String pc = read("src/main/java/top/hmjmfabc/projector/client/ProjectorClient.java");
        check("客户端有卡顿看门狗并把现场写进日志",
                pc != null && pc.contains("[Projector][客户端][卡顿]") && pc.contains("ManagementFactory"),
                "没有这行日志 ⇒ 下次再报「卡」还是只有主观描述，定位不了");
        check("看门狗带硬限流（1 秒最多一行）", pc != null && pc.contains("lastStallLogMs"), "");

        System.out.println();
        System.out.println("== " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " ==  " + pass + " passed");
        if (fail > 0) System.exit(1);
    }

    private static int count(String s, String needle) {
        if (s == null) return -1;
        int n = 0, i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    /** 取两个标记之间的代码（去注释后），用于断言「某段代码里没有/有某调用」。 */
    private static String slice(String s, String from, String to) {
        if (s == null) return "";
        int a = s.indexOf(from);
        if (a < 0) return "";
        int b = s.indexOf(to, a);
        return b < 0 ? s.substring(a) : s.substring(a, b);
    }

    private static String read(String rel) {
        try {
            return new String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(rel)),
                    java.nio.charset.StandardCharsets.UTF_8)
                    .replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
        } catch (Exception e) {
            return null;
        }
    }

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

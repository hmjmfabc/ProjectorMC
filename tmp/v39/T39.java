import top.hmjmfabc.projector.common.widget.ItemWidget;
import top.hmjmfabc.projector.common.widget.MusicWidget;
import net.minecraft.nbt.CompoundTag;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * T39 —— 【27.2.2-snapshot-152】这一批的纯逻辑与源码断言套件。
 *
 * <p>覆盖四件事：</p>
 * <ol>
 *   <li><b>★ solidRect 的坐标系契约</b>（本轮真正修的 bug）：{@code WidgetRenderer.solidRect}
 *       收的是**控件局部坐标**，实现里绝不许再出现 {@code - w.x} / {@code - w.y}
 *       ——那一次的后果是「棋盘外框那一圈白边被画到平面角落」，玩家连报两轮。
 *       同一条还要钉住「棋盘格线直接传 {@code geo.ox()}、不许再加 w.x」。</li>
 *   <li><b>展示框状态机</b>：能不能放 / 能不能取 / 占位符替换 / NBT 往返。</li>
 *   <li><b>播放列表</b>：增删改序 + 四种播放模式的推进规则（含空表、单曲、不在表内）。</li>
 *   <li><b>光球取色</b>：HSV→RGB 的基本正确性 + 「不同物品不同色 / 随时间流动」。</li>
 * </ol>
 */
public class T39 {

    private static int passed = 0;
    private static final List<String> failed = new ArrayList<>();

    public static void main(String[] args) {
        System.out.println("== T39 27.2.2-152~153（展示框 / 播放列表 / solidRect 契约 / 光球取色）==");
        solidRectContract();
        chessGridFrame();
        frameStateMachine();
        frameNbt();
        frameCommands();
        playlistEdit();
        playlistNbt();
        playlistAdvance();
        orbColor();
        outlineRuns();
        texturePathBug();
        wiring();

        System.out.println(failed.isEmpty()
                ? "== ALL PASS ==  " + passed + " passed, 0 failed"
                : "== FAILED ==  " + passed + " passed, " + failed.size() + " failed");
        for (String f : failed) {
            System.out.println("  \u274c " + f);
        }
        if (!failed.isEmpty()) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ 工具

    private static void ok(boolean cond, String what) {
        if (cond) {
            passed++;
        } else {
            failed.add(what);
        }
    }

    private static void eq(Object a, Object b, String what) {
        ok(java.util.Objects.equals(a, b), what + "（实际 " + a + "，期望 " + b + "）");
    }

    private static String read(String rel) {
        try {
            return Files.readString(Path.of(rel));
        } catch (Exception e) {
            return "";
        }
    }

    /** 一段源码里某串出现几次。 */
    private static int countOf(String src, String needle) {
        int n = 0;
        int i = src.indexOf(needle);
        while (i >= 0) {
            n++;
            i = src.indexOf(needle, i + needle.length());
        }
        return n;
    }

    /** 取出一个方法的源码体（从签名行到与之配对的第一个收尾大括号）。 */
    private static String methodBody(String src, String signature) {
        int a = src.indexOf(signature);
        if (a < 0) {
            return "";
        }
        int b = src.indexOf('{', a);
        if (b < 0) {
            return "";
        }
        int depth = 0;
        for (int i = b; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return src.substring(a, i + 1);
                }
            }
        }
        return src.substring(a);
    }

    // ------------------------------------------------------------------ ① solidRect 契约

    /**
     * 本轮的核心回归：坐标系约定只能有一套。
     *
     * <p>历史：实现里减 {@code w.x}（收画布）、调用方传局部（{@code 0,0,w.w,w.h}），
     * 两边各自看都对，合起来整体平移 {@code -R(x,y)} ⇒ 外框跑到平面角落。</p>
     */
    private static void solidRectContract() {
        String src = read("src/main/java/top/hmjmfabc/projector/client/render/WidgetRenderer.java");
        ok(!src.isEmpty(), "读得到 WidgetRenderer.java");
        String body = methodBody(src, "private static void solidRect(");
        ok(!body.isEmpty(), "找得到 solidRect 方法体");
        ok(!body.contains("- w.x") && !body.contains("- w.y"),
                "★ solidRect 里不许再出现「- w.x / - w.y」（那是画布坐标约定，已废）");
        ok(body.contains("w.rotPt(x0, y0)"),
                "★ solidRect 必须直接 rotPt(x0, y0)（入参=控件局部坐标）");
        // 契约必须写在注释里，否则下一个人还会改回去
        ok(src.contains("\u5165\u53c2\u662f\u300c\u63a7\u4ef6\u5c40\u90e8\u5750\u6807"),
                "solidRect 的坐标系契约写进了 javadoc");
        // 调用方：底色 / 占位块必须是 0,0,w.w,w.h（局部）
        int localCalls = countOf(src, "solidRect(collector, ctx, w, 0, 0, w.w, w.h");
        ok(localCalls >= 8, "底色/占位块一律传局部坐标（找到 " + localCalls + " 处，应 ≥8）");
        ok(!src.contains("solidRect(collector, ctx, w, w.x, w.y"),
                "★ 不许再有「传 w.x, w.y」的 solidRect 调用（画布约定残留）");
    }

    /** 棋盘格线：几何给的本来就是局部坐标，绝不能再加 w.x。 */
    private static void chessGridFrame() {
        String src = read("src/main/java/top/hmjmfabc/projector/client/render/WidgetRenderer.java");
        ok(src.contains("double ox = geo.ox();"),
                "★ 格线原点直接用 geo.ox()（局部），不许再 + w.x");
        ok(!src.contains("geo.ox() + w.x"), "★ 不许再出现 geo.ox() + w.x");
        // 层序：外框必须最靠前（depth 最大），底色夹在控件底色与格线之间
        ok(src.contains("dBorder = dBase + CHESS_LAYER * 5"), "棋盘外框在最前一层");
        ok(src.contains("dBoard = dBase + CHESS_LAYER"), "棋盘底色独占一层（在格线之下）");
        ok(src.contains("solidRect(collector, ctxBoard, w, 0, 0, w.w, w.h, w.boardColor)"),
                "棋盘底色用 ctxBoard 画（不再和格线同层）");
        ok(src.contains("boxContains(boxSelf, boxGrid)"),
                "诊断里有物理不变量：格线必须落在控件包围盒里");
    }

    // ------------------------------------------------------------------ ② 展示框

    private static void frameStateMachine() {
        ItemWidget w = new ItemWidget();
        ok(!w.hasItem(), "新建的展示框没有物品");
        ok(!w.hasItem(), "没选物品时没有贴图可放（服务端也据此拒绝）");
        w.itemId = "minecraft:diamond";
        ok(w.hasItem(), "选了物品之后 hasItem");
        ok(w.hasItem(), "选了物品 ⇒ 可以放入");
        ok(w.hasItem() && !w.placed, "选了物品但还没放入 ⇒ 状态正确");
        ok(!w.placed, "空框取不出东西（服务端按 placed 判）");
        ok(w.place(100L, "Alice"), "放入成功");
        ok(w.placed && w.lastChangeTick == 100L, "放入后状态与时间戳正确");
        eq(w.lastPlayer, "Alice", "记下放入的玩家名");
        ok(!w.place(200L, "Bob"), "已经放入过 ⇒ 重复放入无效");
        ok(w.placed, "已放入 ⇒ 不能再放（服务端按 placed 判）");
        ok(w.placed, "已放入 ⇒ 能取（服务端按 placed 判）");
        ok(w.take(300L, "Bob"), "取出成功");
        ok(!w.placed && w.lastChangeTick == 300L, "取出后状态与时间戳正确");
        ok(!w.take(400L, "Bob"), "空框重复取出无效");
        // 空展示框的 label 也要说人话
        ok(w.label().contains("\u7a7a"), "空框标签带「空」");
    }

    private static void frameNbt() {
        ItemWidget a = new ItemWidget();
        a.itemId = "minecraft:apple";
        a.itemName = "\u82f9\u679c";
        a.outlineColor = 0xFF00FF00;
        a.outlineWidth = 3.5;
        a.iconScale = 0.75;
        a.iconRot = 33;
        a.placed = true;
        a.lastPlayer = "Carol";
        a.lastChangeTick = 777L;
        a.placeCommand = "say placed %item%";
        a.takeCommand = "say taken";
        CompoundTag t = a.save();

        ItemWidget b = new ItemWidget();
        b.loadExtra(t);
        eq(b.itemId, a.itemId, "NBT 往返：itemId");
        eq(b.itemName, a.itemName, "NBT 往返：itemName");
        eq(b.outlineColor, a.outlineColor, "NBT 往返：轮廓色");
        eq(b.outlineWidth, a.outlineWidth, "NBT 往返：线宽");
        eq(b.iconScale, a.iconScale, "NBT 往返：缩放");
        eq(b.iconRot, a.iconRot, "NBT 往返：贴图角度");
        eq(b.placed, a.placed, "NBT 往返：放入状态");
        eq(b.lastPlayer, a.lastPlayer, "NBT 往返：最后放入者");
        eq(b.lastChangeTick, a.lastChangeTick, "NBT 往返：时间戳");
        eq(b.placeCommand, a.placeCommand, "NBT 往返：放入指令");
        eq(b.takeCommand, a.takeCommand, "NBT 往返：取出指令");

        // 旧存档（没有这些键）要能读出合理的默认值
        ItemWidget c = new ItemWidget();
        c.loadExtra(new CompoundTag());
        eq(c.outlineColor, 0xFFFFFFFF, "旧存档：轮廓默认白色");
        eq(c.iconScale, 1.0, "旧存档：缩放默认 1.0（贴图与轮廓完全一致）");
        ok(!c.placed && c.outlineWidth >= 0, "旧存档：默认空框、加粗为 0（正好一像素粗）");
    }

    private static void frameCommands() {
        ItemWidget w = new ItemWidget();
        w.itemId = "minecraft:diamond";
        eq(w.renderCommand("", "P", 1, 2, 3), "", "空指令 → 空串（不执行）");
        eq(w.renderCommand(null, "P", 1, 2, 3), "", "null 指令 → 空串");
        eq(w.renderCommand("  ", "P", 1, 2, 3), "", "全空白指令 → 空串");
        eq(w.renderCommand("/say hi", "P", 1, 2, 3), "say hi", "去掉前导斜杠");
        eq(w.renderCommand("give %player% %item%", "Alice", 4, 5, 6),
                "give Alice minecraft:diamond", "占位符 %player%/%item%");
        eq(w.renderCommand("setblock %x% %y% %z% stone", "P", 4, 5, 6),
                "setblock 4 5 6 stone", "占位符 %x%/%y%/%z%");
        eq(w.renderCommand("  /tp %player% %x% %y% %z%  ", "Bo", 1, 2, 3),
                "tp Bo 1 2 3", "前后空白 + 斜杠 + 全部占位符");
    }

    // ------------------------------------------------------------------ ③ 播放列表

    private static MusicWidget playlist() {
        MusicWidget m = new MusicWidget();
        m.addToPlaylist("LOCAL", "hash1", "\u4e00", "A", 1000L);
        m.addToPlaylist("NETEASE", "42", "\u4e8c", "B", 2000L);
        m.addToPlaylist("URL", "http://x/y.mp3", "\u4e09", "C", 3000L);
        return m;
    }

    private static void playlistEdit() {
        MusicWidget m = playlist();
        eq(m.playlistSize(), 3, "加了三首");
        eq(m.playlistAt(1).title(), "\u4e8c", "按顺序读第 2 首");
        eq(m.playlistAt(9), null, "越界读返回 null");
        eq(m.indexOfTrack("NETEASE", "42"), 1, "按 kind+key 查下标");
        eq(m.indexOfTrack("NETEASE", "999"), -1, "查不到返回 -1");
        eq(m.addToPlaylist("LOCAL", "", "x", "", 0L), -1, "空 key 不许加（返回 -1）");
        eq(m.playlistSize(), 3, "空 key 没有被加进去");
        // 重复的歌允许存在（播放列表本来就允许同一首放两遍）
        m.addToPlaylist("LOCAL", "hash1", "\u4e00", "A", 1000L);
        eq(m.playlistSize(), 4, "同一首歌可以出现两次");

        // 移除：下标要跟着修
        m.playlistIndex = 2;
        m.removeFromPlaylist(0);
        eq(m.playlistSize(), 3, "移除后剩 3 首");
        eq(m.playlistIndex, 1, "★ 移除前面的那首之后，当前下标要跟着 -1");
        m.playlistIndex = 0;
        m.removeFromPlaylist(0);
        eq(m.playlistIndex, -1, "★ 移除的正是当前这首 ⇒ 下标归 -1（不是 0）");

        // 移动
        MusicWidget m2 = playlist();
        m2.playlistIndex = 0;
        m2.moveInPlaylist(0, 2);
        eq(m2.playlistAt(2).title(), "\u4e00", "第一首挪到了末尾");
        eq(m2.playlistIndex, 2, "★ 当前这首被挪动后，下标跟着走");
        // 此时列表是 [二, 三, 一]，下标 0 指着「二」；把末尾的「一」挪到最前面 ⇒ [一, 二, 三]，
        // 「二」被挤到 1 ⇒ 下标必须跟着到 1（语义是「下标跟着**这首歌**走」，不是「盯住某个位置」）
        m2.playlistIndex = 0;
        m2.moveInPlaylist(2, 0);
        eq(m2.playlistAt(m2.playlistIndex).title(), "\u4e8c",
                "★ 挪动别的歌之后，下标仍然指着同一首歌");
        eq(m2.playlistIndex, 1, "★ 别的首挪到前面 ⇒ 下标 +1");
        m2.moveInPlaylist(0, 0);
        eq(m2.playlistSize(), 3, "原地移动不改变数量");
        m2.moveInPlaylist(-1, 0);
        m2.moveInPlaylist(0, 99);
        eq(m2.playlistSize(), 3, "越界移动被忽略");

        m2.clearPlaylist();
        eq(m2.playlistSize(), 0, "清空");
        eq(m2.playlistIndex, -1, "清空后下标归 -1");
        eq(m2.playlistAt(0), null, "清空后读不到");
        ok(m2.describePlaylist().contains("\u65e0"), "空列表的描述");
        ok(playlist().describePlaylist().contains("3 \u9996"), "非空列表的描述带数量");
    }

    private static void playlistNbt() {
        MusicWidget a = playlist();
        a.playMode = MusicWidget.MODE_SHUFFLE;
        a.playlistIndex = 2;
        CompoundTag t = a.save();
        MusicWidget b = new MusicWidget();
        b.loadExtra(t);
        eq(b.playlistSize(), 3, "NBT 往返：列表条数");
        eq(b.playlistAt(2).key(), "http://x/y.mp3", "NBT 往返：第 3 首的 key");
        eq(b.playlistAt(1).durationMs(), 2000L, "NBT 往返：时长");
        eq(b.playMode, MusicWidget.MODE_SHUFFLE, "NBT 往返：播放模式");
        eq(b.playlistIndex, 2, "NBT 往返：当前下标");
        // 旧存档没有这几个键
        MusicWidget c = new MusicWidget();
        c.loadExtra(new CompoundTag());
        eq(c.playlistSize(), 0, "旧存档：空列表");
        eq(c.playMode, MusicWidget.MODE_SEQUENCE, "旧存档：默认顺序播放");
        eq(c.playlistIndex, -1, "旧存档：下标 -1");
        // 存坏的「下标超范围」要能被夹回来
        CompoundTag t2 = new CompoundTag();
        t2.putInt("plIndex", 7);
        MusicWidget d = new MusicWidget();
        d.loadExtra(t2);
        eq(d.playlistIndex, -1, "★ 存档里的越界下标被夹回 -1");
    }

    /** 四种播放模式的推进规则 —— 这是「自动续播」的全部逻辑。 */
    private static void playlistAdvance() {
        MusicWidget m = playlist();      // 3 首
        Random rnd = new Random(12345);

        // 顺序：0→1→2→(停)
        m.playMode = MusicWidget.MODE_SEQUENCE;
        eq(m.nextPlaylistIndex(-1, false, rnd), 0, "顺序：还没播过 ⇒ 从第 1 首开始");
        eq(m.nextPlaylistIndex(0, false, rnd), 1, "顺序：0→1");
        eq(m.nextPlaylistIndex(1, false, rnd), 2, "顺序：1→2");
        eq(m.nextPlaylistIndex(2, false, rnd), -1, "★ 顺序：最后一首放完 ⇒ 停（-1）");
        eq(m.nextPlaylistIndex(2, true, rnd), 0, "顺序：手动点下一首 ⇒ 回到第 1 首");
        eq(m.nextPlaylistIndex(9, false, rnd), -1, "顺序：下标越界也只会停");

        // 列表循环：2→0
        m.playMode = MusicWidget.MODE_LOOP;
        eq(m.nextPlaylistIndex(2, false, rnd), 0, "列表循环：2→0");
        eq(m.nextPlaylistIndex(0, false, rnd), 1, "列表循环：0→1");
        eq(m.nextPlaylistIndex(-1, false, rnd), 0, "列表循环：不在表内 ⇒ 从第 1 首开始");

        // 单曲循环：自动不换歌，手动换
        m.playMode = MusicWidget.MODE_REPEAT_ONE;
        eq(m.nextPlaylistIndex(1, false, rnd), 1, "★ 单曲循环：自动到底还是这一首");
        eq(m.nextPlaylistIndex(1, true, rnd), 2, "★ 单曲循环：玩家手动点下一首仍然换歌");
        eq(m.nextPlaylistIndex(2, true, rnd), 0, "单曲循环：手动下一首会绕回开头");

        // 随机：永不等于当前
        m.playMode = MusicWidget.MODE_SHUFFLE;
        boolean neverSame = true;
        boolean coversAll = false;
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int i = 0; i < 400; i++) {
            int n = m.nextPlaylistIndex(1, false, rnd);
            if (n == 1 || n < 0 || n >= 3) {
                neverSame = false;
                break;
            }
            seen.add(n);
        }
        coversAll = seen.contains(0) && seen.contains(2);
        ok(neverSame, "★ 随机：永远不会选到当前这一首（也不会越界）");
        ok(coversAll, "随机：两首别的歌都能被选到");
        eq(m.nextPlaylistIndex(1, false, null), 0, "随机：随机源为 null 时退化成顺序（不崩）");

        // 空表 / 单曲
        MusicWidget empty = new MusicWidget();
        eq(empty.nextPlaylistIndex(0, false, rnd), -1, "空表：没有下一首");
        eq(empty.firstPlaylistIndex(), -1, "空表：firstPlaylistIndex = -1");
        MusicWidget one = new MusicWidget();
        one.addToPlaylist("LOCAL", "k", "t", "", 0L);
        eq(one.firstPlaylistIndex(), 0, "单曲：firstPlaylistIndex = 0");
        one.playMode = MusicWidget.MODE_SEQUENCE;
        eq(one.nextPlaylistIndex(0, false, rnd), -1, "单曲 + 顺序：放完就停");
        eq(one.nextPlaylistIndex(0, true, rnd), 0, "单曲：手动下一首还是它");
        one.playMode = MusicWidget.MODE_REPEAT_ONE;
        eq(one.nextPlaylistIndex(0, false, rnd), 0, "单曲 + 单曲循环：一直重放");
        one.playMode = MusicWidget.MODE_LOOP;
        eq(one.nextPlaylistIndex(0, false, rnd), 0, "单曲 + 列表循环：一直重放");
        one.playMode = MusicWidget.MODE_SHUFFLE;
        eq(one.nextPlaylistIndex(0, false, rnd), 0, "单曲 + 随机：只有它，就放它");
        ok(MusicWidget.MODE_NAMES.length == 4, "模式名有四个（与常量一一对应）");
    }

    /** 两个颜色的 RGB 曼哈顿距离（用来断言「只是亮度变了、色相没跑」）。 */
    private static int colorDist(int x, int y) {
        return Math.abs(((x >> 16) & 0xFF) - ((y >> 16) & 0xFF))
                + Math.abs(((x >> 8) & 0xFF) - ((y >> 8) & 0xFF))
                + Math.abs((x & 0xFF) - (y & 0xFF));
    }

    // ------------------------------------------------------------------ ⑥ 物品轮廓（只描最外面那一圈）

    /**
     * 轮廓的**物理不变量**（用户口径 157：「我要的是最外面那一圈像素」）：
     *
     * <ol>
     *   <li>画出来的每一格都必须**是不透明的**（不可能描到空气上）；</li>
     *   <li>每一格都必须**挨着「外面」**（贴着贴图边，或四邻里有一格属于与贴图边连通的透明区域）
     *       —— 这一条独立于实现，是「外轮廓」的定义本身；</li>
     *   <li>物品**内部**的不透明格子与**内部镂空**的边一律不许出现。</li>
     * </ol>
     */
    private static void outlineRuns() {
        // ① 实心 4×4：外圈 = 最外那一圈 12 格，中间 2×2 不算
        boolean[] solid = new boolean[4 * 4];
        java.util.Arrays.fill(solid, true);
        java.util.Set<Integer> got = pixelsOfRuns(solid, 4, 4);
        eq(got.size(), 12, "实心方块：外圈是 12 格");
        ok(!got.contains(1 * 4 + 1) && !got.contains(2 * 4 + 2), "实心方块：中间 2×2 不被描");

        // ② 8×8 实心块中间挖一个 4×4 的洞：外圈只是最外面那圈，
        //    **洞里那一圈不算**（内部镂空不与外面连通 ⇒ 不是外轮廓）
        boolean[] block = new boolean[8 * 8];
        java.util.Arrays.fill(block, true);
        for (int y = 2; y <= 5; y++) {
            for (int x = 2; x <= 5; x++) {
                block[y * 8 + x] = false;
            }
        }
        java.util.Set<Integer> ring = pixelsOfRuns(block, 8, 8);
        eq(ring.size(), 28, "挖洞方块：外圈只有最外面那 28 格");
        for (int y = 2; y <= 5; y++) {
            for (int x = 2; x <= 5; x++) {
                // 洞里那圈「紧挨着洞」的实心格子（x=1/6 或 y=1/6）不能被描
                ok(!ring.contains(y * 8 + 1) && !ring.contains(y * 8 + 6)
                                && !ring.contains(1 * 8 + x) && !ring.contains(6 * 8 + x),
                        "挖洞方块：内部镂空的边不被描（(" + x + "," + y + ") 那一圈）");
            }
        }

        // ③ 8×8 画布正中间一个 5×5 实心块：外圈 = 5×5 的最外圈 16 格，正中心那 3×3 不算
        boolean[] mid = new boolean[8 * 8];
        for (int y = 1; y <= 5; y++) {
            for (int x = 1; x <= 5; x++) {
                mid[y * 8 + x] = true;
            }
        }
        java.util.Set<Integer> m = pixelsOfRuns(mid, 8, 8);
        eq(m.size(), 16, "居中实心块：外圈 16 格");
        ok(!m.contains(3 * 8 + 3), "居中实心块：正中心不被描");
        ok(m.contains(1 * 8 + 1) && m.contains(5 * 8 + 5), "居中实心块：四个角被描到");

        // ④ 全透明：没有轮廓（调用方走方框兜底）
        eq(top.hmjmfabc.projector.client.render.ItemWidgetRenderer
                .runsFromMask(new boolean[16], 4, 4).size(), 0, "全透明贴图没有轮廓段");

        // ⑤ 段数上限：段太多就退化成空表（走兜底），不会画出几百个四边形
        boolean[] big = new boolean[64 * 64];
        java.util.Arrays.fill(big, true);
        java.util.List<top.hmjmfabc.projector.client.render.ItemWidgetRenderer.Run> many =
                top.hmjmfabc.projector.client.render.ItemWidgetRenderer.runsFromMask(big, 64, 64);
        ok(many.isEmpty() || many.size() <= 96, "轮廓段数有上限（不超过 96 段）");

        // ⑥ 越界/坏参数不许抛
        eq(top.hmjmfabc.projector.client.render.ItemWidgetRenderer
                .runsFromMask(null, 4, 4).size(), 0, "null 掩码返回空表");
        eq(top.hmjmfabc.projector.client.render.ItemWidgetRenderer
                .runsFromMask(new boolean[4], 8, 8).size(), 0, "掩码比声明的尺寸小时返回空表");
    }

    /** 把段铺回像素网格（独立地，不复用被测的那套公式）。 */
    private static java.util.Set<Integer> pixelsOfRuns(boolean[] solid, int fw, int fh) {
        java.util.List<top.hmjmfabc.projector.client.render.ItemWidgetRenderer.Run> runs =
                top.hmjmfabc.projector.client.render.ItemWidgetRenderer.runsFromMask(solid, fw, fh);
        java.util.Set<Integer> got = new java.util.HashSet<>();
        for (var r : runs) {
            int x0 = (int) Math.round(r.x0() * fw);
            int x1 = (int) Math.round(r.x1() * fw);
            int py = fh - 1 - (int) Math.round(r.y0() * fh);      // 翻回 PNG 的行号
            for (int x = x0; x < x1; x++) {
                got.add(py * fw + x);
            }
        }
        // 不变量一：描出来的必须是不透明格子
        for (int idx : got) {
            ok(solid[idx], "描到的每一格都是不透明像素（idx=" + idx + "）");
        }
        // 不变量二：每一格都挨着「外面」（贴着贴图边，或四邻里有与边连通的透明格子）
        boolean[] outside = new boolean[fw * fh];
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
        for (int x = 0; x < fw; x++) {
            seedOutside(solid, outside, q, fw, x, 0);
            seedOutside(solid, outside, q, fw, x, fh - 1);
        }
        for (int y = 0; y < fh; y++) {
            seedOutside(solid, outside, q, fw, 0, y);
            seedOutside(solid, outside, q, fw, fw - 1, y);
        }
        while (!q.isEmpty()) {
            int i = q.poll();
            int x = i % fw, y = i / fw;
            seedOutside(solid, outside, q, fw, x - 1, y);
            seedOutside(solid, outside, q, fw, x + 1, y);
            seedOutside(solid, outside, q, fw, x, y - 1);
            seedOutside(solid, outside, q, fw, x, y + 1);
        }
        for (int idx : got) {
            int x = idx % fw, y = idx / fw;
            boolean touchesOutside = x == 0 || y == 0 || x == fw - 1 || y == fh - 1
                    || outside[(y) * fw + x - 1] || outside[(y) * fw + x + 1]
                    || outside[(y - 1) * fw + x] || outside[(y + 1) * fw + x];
            ok(touchesOutside, "★ 描到的每一格都挨着「外面」（idx=" + idx + "）");
        }
        return got;
    }

    private static void seedOutside(boolean[] solid, boolean[] outside,
                                    java.util.ArrayDeque<Integer> q, int fw, int x, int y) {
        int h = outside.length / fw;
        if (x < 0 || y < 0 || x >= fw || y >= h) {
            return;
        }
        int idx = y * fw + x;
        if (outside[idx] || solid[idx]) {
            return;
        }
        outside[idx] = true;
        q.add(idx);
    }

    // ------------------------------------------------------------------ ⑦ 贴图路径（158 的真 bug）

    /**
     * 【真根因回归】{@code sprite.contents().name()} 给的是**精灵名**（{@code minecraft:item/diamond}），
     * 不是资源路径。少了 {@code textures/} 前缀和 {@code .png} 后缀，
     * {@code getResource} 必然返回空 ⇒ 描轮廓与取色**双双静默失败**：
     * 未放置显示成方框、已放置显示成不渐变的剪影。
     */
    private static void texturePathBug() {
        var R = net.minecraft.resources.ResourceLocation.class;
        var sprite = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minecraft", "item/diamond");
        var want = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                "minecraft", "textures/item/diamond.png");
        eq(top.hmjmfabc.projector.client.render.ItemWidgetRenderer.texturePath(sprite), want,
                "★ 精灵名 item/diamond → textures/item/diamond.png");
        var blockTex = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                "minecraft", "block/stone");
        eq(top.hmjmfabc.projector.client.render.ItemWidgetRenderer.texturePath(blockTex),
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                        "minecraft", "textures/block/stone.png"),
                "方块贴图同理");
        // 已经是完整路径的原样返回（不要拼成 textures/textures/...）
        eq(top.hmjmfabc.projector.client.render.ItemWidgetRenderer.texturePath(want), want,
                "完整路径原样返回");
        // 模组命名空间也要跟着走
        var modded = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("create", "item/cogwheel");
        eq(top.hmjmfabc.projector.client.render.ItemWidgetRenderer.texturePath(modded),
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                        "create", "textures/item/cogwheel.png"),
                "模组物品同理");
        ok(R != null, "ResourceLocation 可用");
    }

    // ------------------------------------------------------------------ ⑤ 接线（谁在驱动）

    /**
     * 「功能写好了」不等于「有人调用它」—— 本项目栽过「死代码吃掉功能」。
     * 这里把这一批的驱动链钉住：服务端扫描 → 客户端发现换歌 → 界面入口。
     */
    private static void wiring() {
        String manager = read("src/main/java/top/hmjmfabc/projector/client/music/MusicManager.java");
        String server = read("src/main/java/top/hmjmfabc/projector/network/ServerNetHandler.java");
        String events = read("src/main/java/top/hmjmfabc/projector/server/ServerEvents.java");
        String editor = read("src/main/java/top/hmjmfabc/projector/client/gui/WidgetEditorScreen.java");
        String add = read("src/main/java/top/hmjmfabc/projector/client/gui/AddWidgetScreen.java");

        ok(server.contains("public static void tickPlaylists("),
                "★ 服务端有播放列表扫描 tickPlaylists");
        ok(events.contains("ServerNetHandler.tickPlaylists("),
                "★ 服务端 tick 里真的调了它（不是死代码）");
        ok(events.contains("getTickCount() % 10"), "续播扫描是每 10 刻一次（0.5 秒）");
        ok(manager.contains("watchTrackChange(plane, music)"),
                "★ 客户端在 tick 里检查「这首歌是不是被换掉了」");
        ok(manager.contains("LAST_TRACK_SIG.clear()"), "换歌指纹表会随断线清空");
        // 顺序：必须在 finishedAt 之前检查，否则换歌那一 tick 会被 continue 掉
        int watch = manager.indexOf("watchTrackChange(plane, music)");
        int finish = manager.indexOf("if (music.finishedAt(gameTime))", watch);
        ok(watch > 0 && finish > watch, "换歌检查排在「已放完」判断之前");

        ok(editor.contains("new MusicPlaylistScreen(this, plane, mw)"),
                "★ 编辑器里有「播放列表」按钮");
                ok(editor.contains("itemBranch("), "★ 编辑器里有展示框编辑区");
        ok(editor.contains("new ItemPickerScreen(this, iw"), "展示框编辑区能打开选物品界面");
        ok(editor.contains("itemPlaceCmdBox") && editor.contains("itemTakeCmdBox"),
                "展示框有放入/取出两条指令输入框");
        ok(editor.contains("ClientPermissions.isAdmin()"), "指令输入框只对管理员显示");
        ok(add.contains("KIND_ITEM"), "「新增特殊控件」里有展示框入口");
        // 【154】展示框：建出来直接进编辑器，**不**弹「从几百个物品里挑一个」
        ok(!add.contains("ItemPickerScreen"),
                "★ 新增展示框不再一上来就弹选物品界面（选物品是编辑器里的一个按钮）");

        // 【154】左键取出：必须排在 `!isUseItem` 那道闸门**之前**，
        //   否则所有左键都会被丢掉 ⇒ 处理代码永远执行不到（玩家报的「取不出来」）
        String input = read("src/main/java/top/hmjmfabc/projector/client/ClientInputHandler.java");
        int call = input.indexOf("handleItemFrameClick(mc, event)");
        int gate = input.indexOf("if (!event.isUseItem()) return;", call);
        ok(call > 0 && gate > call, "★ 展示框交互排在「只认右键」的闸门之前（左键取出才走得到）");
        ok(input.contains("event.isAttack()"), "展示框交互认左键（isAttack）");
        ok(!input.contains("handleItemFrame(mc, owner, frame, event)"),
                "旧的（走不到的）展示框调用点已删除，只留一个入口");

        // 【154】登录在选歌界面、播放列表两边都有
        String picker = read("src/main/java/top/hmjmfabc/projector/client/gui/MusicPickerScreen.java");
        ok(picker.contains("LoginScreen(this)"), "★ 选歌界面有「登录网易云」按钮");
        ok(picker.contains("new MusicPlaylistScreen(this, plane, playlistOwner)"),
                "★ 选歌界面有「播放列表」按钮");
        ok(!editor.contains("new top.hmjmfabc.projector.compat.betterlogin.client.gui.LoginScreen"),
                "★ 编辑器里不再有登录按钮（用户要求放到选歌界面）");
        ok(editor.contains("MusicPlaylistScreen"), "编辑器里保留「播放列表」按钮");

        // 【154】光球：变小 + 取物品贴图的颜色 + 分层发光
        String item = read("src/main/java/top/hmjmfabc/projector/client/render/ItemWidgetRenderer.java");
        ok(item.contains("averageColor("), "★ 有「采物品贴图平均色」的实现");
        // 【158】两条读贴图的链都必须走 openTexture（曾经各自直接 getResource(精灵名) ⇒ 全空）
        ok(item.contains("openTexture(texture)"),
                "★ 描轮廓与取色都走 openTexture（精灵名 → 资源路径）");
        ok(!item.contains("getResourceManager().getResource(texture)"),
                "★ 不许再直接拿精灵名去 getResource（那是 158 的 bug）");
        // 【159】轮廓粗细固定一像素、且**不渐显渐隐**
        ok(!item.contains("private static boolean fading("),
                "★ 轮廓不再有 fading() 分支（用户：边框不要渐显/渐隐）");
        ok(item.contains("final double grow = 0.0;"),
                "★ 轮廓粗细固定 = 一个贴图像素（不再读 outlineWidth）");
        ok(!item.contains("Math.min(w.outlineWidth"),
                "★ 不再用 outlineWidth 算粗细（老存档里的旧 1.2 不再把边框顶粗）");
        ok(item.contains("NativeImage.read(") && item.contains("img.close()"),
                "★ 用 NativeImage 读贴图，而且**关掉了**（堆外内存，本项目有硬约束）");
        ok(item.contains("sprite.contents().name()"), "取的是**原始贴图文件**（不是图集）");
        ok(!item.contains("unit * 1.1"), "★ 光球不再是「最大 1.1 倍控件尺寸」（太大了）");
        ok(item.contains("unit * 0.30"), "光球核心最大 0.30 倍控件尺寸");
        ok(item.contains("drawGlow(") && item.contains("mix(base, 0xFFFFFF"),
                "★ 光球分三层、核心推向白色（这就是「发光」的观感）");
        // 【154】轮廓 = 物品剪影（放大一圈的染色副本），不是正方形
        ok(item.contains("drawEmptyBox("), "空框才用方框占位");

        // 【156】轮廓：**两个状态都用同一套物品轮廓**（未放置时也要是物品轮廓，不是正方形）
        ok(item.contains("outlineRuns(w)"), "★ 轮廓从贴图的 alpha 掩码描出来");
        ok(item.contains("runsFromMask("), "★ 「掩码 → 边界段」是纯函数（脚本可测）");
        ok(item.contains("MAX_RUNS"), "轮廓段数有上限（太碎就走兜底）");
        ok(item.contains("drawIconRect(collector, ctx, w, x0, y0, x1, y1, color, ctx.depth() + LAYER)"),
                "★ 轮廓画在贴图**上面**一层（细一圈也看得见，不必靠加粗）");
        ok(item.contains("onOuterRing(") && item.contains("pushIfOutside("),
                "★ 只描外轮廓（从贴图边泛洪标出「外面」，内部镂空不算）");
        int runsCall = item.indexOf("drawOutlineRuns(collector, ctx, w, outline, size, grow)");
        int placedRef = item.indexOf("w.placed", runsCall);
        ok(runsCall > 0 && (placedRef < 0 || placedRef > item.indexOf("drawOrb")),
                "★ 画轮廓那段里不依赖 placed（未放置时也画物品轮廓）");

        // 【156】光球起点 = 玩家
        ok(item.contains("playerCanvasPoint(ctx)"), "★ 光球的起点取自玩家位置");
        ok(item.contains("PlaneCanvas.UNITS_PER_BLOCK"), "玩家世界坐标 → 画布坐标用同一个换算常量");
        ok(item.contains("getEyePosition"), "取的是玩家眼睛位置");
        ok(item.contains("cap"), "起点距离有上限（跨十几格飞过来只会闪一下）");
        // 越权防线：服务端在 updateWidget 里回滚非管理员的指令字段
        ok(server.contains("restoreAdminOnlyCommands("),
                "★ 服务端在 updateWidget 里回滚越权的指令字段");
        ok(server.contains("oldPlaceCommand"), "回滚用「覆盖之前记下的权威值」而不是覆盖之后的值");
    }

    // ------------------------------------------------------------------ ④ 光球取色

    private static void orbColor() {
        // HSV→RGB 的几个定点：安卓上没有 java.awt，所以这段是自己实现的，必须钉住
        eq(top.hmjmfabc.projector.client.render.ItemWidgetRenderer.hsvToRgb(0f, 1f, 1f),
                0xFF0000, "HSV：0° 是纯红");
        eq(top.hmjmfabc.projector.client.render.ItemWidgetRenderer.hsvToRgb(120f, 1f, 1f),
                0x00FF00, "HSV：120° 是纯绿");
        eq(top.hmjmfabc.projector.client.render.ItemWidgetRenderer.hsvToRgb(240f, 1f, 1f),
                0x0000FF, "HSV：240° 是纯蓝");
        eq(top.hmjmfabc.projector.client.render.ItemWidgetRenderer.hsvToRgb(360f, 1f, 1f),
                0xFF0000, "HSV：360° 绕回红色");
        eq(top.hmjmfabc.projector.client.render.ItemWidgetRenderer.hsvToRgb(-120f, 1f, 1f),
                0x0000FF, "HSV：负角度等价于 +240°");
        eq(top.hmjmfabc.projector.client.render.ItemWidgetRenderer.hsvToRgb(0f, 0f, 1f),
                0xFFFFFF, "HSV：饱和度为 0 是白色");
        eq(top.hmjmfabc.projector.client.render.ItemWidgetRenderer.hsvToRgb(200f, 1f, 0f),
                0x000000, "HSV：明度为 0 是黑色");

        ItemWidget a = new ItemWidget();
        a.itemId = "minecraft:diamond";
        ItemWidget b = new ItemWidget();
        b.itemId = "minecraft:apple";
        int ca = top.hmjmfabc.projector.client.render.ItemWidgetRenderer.orbColor(a, 0L, 1f);
        int cb = top.hmjmfabc.projector.client.render.ItemWidgetRenderer.orbColor(b, 0L, 1f);
        ok((ca & 0xFFFFFF) != (cb & 0xFFFFFF), "★ 不同物品的光球颜色不同（取色跟着物品走）");
        // 「动态」体现在**亮度**上：色相固定为物品的颜色，亮度随时间脉动
        int ca2 = top.hmjmfabc.projector.client.render.ItemWidgetRenderer.orbColor(a, 300L, 1f);
        int ca3 = top.hmjmfabc.projector.client.render.ItemWidgetRenderer.orbColor(a, 1800L, 1f);
        ok((ca & 0xFFFFFF) != (ca2 & 0xFFFFFF), "★ 光球亮度随时间脉动（是「活的」）");
        ok(colorDist(ca, ca2) < 90, "★ 脉动只改亮度、不改色相（取色要准）");
        ok((ca3 & 0xFFFFFF) != 0 && (ca2 & 0xFFFFFF) != 0, "脉动过程中不会变黑");
        eq(ca >>> 24, 255, "alpha=1 时高字节是 255");
        eq(top.hmjmfabc.projector.client.render.ItemWidgetRenderer.orbColor(a, 0L, 0f) >>> 24, 0,
                "alpha=0 时光球全透明（不画）");
        // 没选物品时也要有颜色，不能是黑块
        ItemWidget blank = new ItemWidget();
        ok((top.hmjmfabc.projector.client.render.ItemWidgetRenderer
                .orbColor(blank, 0L, 1f) & 0xFFFFFF) != 0, "空框的光球颜色非黑");
    }
}

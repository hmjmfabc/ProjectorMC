import net.minecraft.nbt.CompoundTag;
import top.hmjmfabc.projector.common.widget.WebWidget;
import top.hmjmfabc.projector.common.widget.Widget;
import top.hmjmfabc.projector.common.widget.Widgets;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * T34 —— 【27.2】网页控件的纯逻辑套件。
 *
 * <p>覆盖四类：①地址合法性（安全口径：只认 http/https/about:blank）②世界内按钮栏几何
 * （绘制与点击必须同源）③NBT 往返与字段夹取 ④**许可边界**（我们源码里不许出现 MCEF/CEF 的 import、
 * build.gradle 不许有它的依赖）⑤契约签名（两个反射桥文件的公开签名必须与规范一致）
 * ⑥界面接线是否落地（门禁文案、编辑器分支、语言键、配置段）。</p>
 */
public class T34 {

    private static int passed = 0;
    private static final List<String> failed = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        System.out.println("== T34 网页控件（27.2）==");
        urlRules();
        geometry();
        rotationContract();
        nbt();
        kindRegistry();
        licenseBoundary();
        contractSignatures();
        uiWiring();
        lifecycle();

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

    // ------------------------------------------------------------------ 地址口径

    private static void urlRules() {
        check("https 直链放行", WebWidget.acceptable("https://www.bilibili.com/video/BV1x"));
        check("http 直链放行（含端口与路径）", WebWidget.acceptable("http://127.0.0.1:8080/a?b=1"));
        check("about:blank 放行（新建控件的占位页）", WebWidget.acceptable(WebWidget.BLANK));
        check("file: 一律拒绝（不能让公共屏幕读本机文件）", !WebWidget.acceptable("file:///etc/passwd"));
        check("javascript: 一律拒绝", !WebWidget.acceptable("javascript:alert(1)"));
        check("data: 一律拒绝", !WebWidget.acceptable("data:text/html,<b>x</b>"));
        check("我们的内部协议也被拒绝", !WebWidget.acceptable("projector:plane/1"));
        check("空值与 null 判非法",
                !WebWidget.acceptable("") && !WebWidget.acceptable("   ") && !WebWidget.acceptable(null));
        check("夹带控制字符判非法", !WebWidget.acceptable("https://a.com/\u0001x"));
        check("超长地址判非法而不是悄悄截断",
                !WebWidget.acceptable("https://a.com/" + "a".repeat(WebWidget.MAX_URL)));

        check("裸域名补 https", "https://www.bilibili.com".equals(WebWidget.normalize("  www.bilibili.com  ")));
        check("已有协议不动", "https://a.com/x".equals(WebWidget.normalize("https://a.com/x")));
        check("about:blank 不被补协议", WebWidget.BLANK.equals(WebWidget.normalize("about:blank")));
        check("normalize 有长度上限",
                WebWidget.normalize("https://a.com/" + "a".repeat(4000)).length() <= WebWidget.MAX_URL);

        WebWidget w = new WebWidget();
        check("没设地址时加载空白页", WebWidget.BLANK.equals(w.targetUrl()));
        w.url = "https://a.com";
        w.homeUrl = "";
        check("主页缺省回落到当前地址", "https://a.com".equals(w.homeTarget()));
        w.homeUrl = "https://home.example";
        check("设了主页就用主页", "https://home.example".equals(w.homeTarget()));
        check("显示用地址去掉协议前缀并截断",
                !w.displayUrl().startsWith("https://") && w.displayUrl().length() <= 42);
    }

    // ------------------------------------------------------------------ 几何

    private static void geometry() {
        WebWidget w = new WebWidget();
        w.w = 96;
        w.h = 54;
        check("按钮边长夹在可点范围里（3~7 画布单位）",
                w.buttonSize() >= 3.0 && w.buttonSize() <= 7.0);
        boolean inside = true;
        for (int i = 0; i < WebWidget.BUTTON_COUNT; i++) {
            double[] b = w.buttonBox(i);
            if (b[0] < 0 || b[1] < 0 || b[0] + b[2] > w.w || b[1] + b[3] > w.h) {
                inside = false;
            }
        }
        check("每个按钮都在控件框内（点得到）", inside);
        boolean disjoint = true;
        for (int i = 0; i < WebWidget.BUTTON_COUNT; i++) {
            for (int j = i + 1; j < WebWidget.BUTTON_COUNT; j++) {
                double[] a = w.buttonBox(i);
                double[] b = w.buttonBox(j);
                if (a[0] < b[0] + b[2] && b[0] < a[0] + a[2]
                        && a[1] < b[1] + b[3] && b[1] < a[1] + a[3]) {
                    disjoint = false;
                }
            }
        }
        check("四个按钮互不重叠", disjoint);
        boolean centersHit = true;
        for (int i = 0; i < WebWidget.BUTTON_COUNT; i++) {
            double[] b = w.buttonBox(i);
            if (w.hitButton(b[0] + b[2] / 2, b[1] + b[3] / 2) != i) {
                centersHit = false;
            }
        }
        check("按钮中心命中自己（绘制与点击同源）", centersHit);
        check("控件中部不算按到按钮", w.hitButton(w.w / 2, w.h / 2) == -1);
        check("底部是按钮栏区域", w.inBar(1.0, 0.5) && !w.inBar(w.w / 2, w.h / 2));
        w.showControls = false;
        check("关掉按钮栏后一律不命中", w.hitButton(1.0, 0.5) == -1 && !w.inBar(1.0, 0.5));
    }

    // ------------------------------------------------------------------ 旋转（27.2.1-hotfix-144）

    /**
     * 旋转契约：**绕控件中心**转、框跟着转、转完还能拖到画布各处。
     *
     * <p>这三条对应玩家报的祖传 bug：①转完位置就错（老实现绕左下角甩出去）
     * ②控件框不跟着转（渲染绕左下角、选中框绕中心，两份轴心）
     * ③|rot| 大了以后某些位置拖不到（夹取用未旋转的 w/h，180° 时往右卡住）。</p>
     */
    private static void rotationContract() {
        top.hmjmfabc.projector.common.widget.WebWidget w =
                new top.hmjmfabc.projector.common.widget.WebWidget();
        w.x = 40;
        w.y = 30;
        w.w = 20;
        w.h = 10;

        // ① 轴心 = 中心：四角旋转后的质心必须还在控件中心
        for (double deg : new double[]{0, 30, 90, 179, 180, -90, -135, 270}) {
            w.rot = deg;
            double[][] c = w.rotCorners();
            double mx = 0, my = 0;
            for (double[] p : c) {
                mx += p[0] / 4.0;
                my += p[1] / 4.0;
            }
            // ⚠ 比的是**画布坐标**的控件中心（x + w/2）。rotX()/rotY() 是「局部坐标系里的
            //    轴心」（渲染原语拿它当轴心用），不是画布坐标 —— 别在这里混用。
            double ccx = w.x + w.w / 2.0, ccy = w.y + w.h / 2.0;
            check("rot=" + deg + "：四角质心 = 控件中心（绕中心旋转）",
                    Math.abs(mx - ccx) < 1.0e-6 && Math.abs(my - ccy) < 1.0e-6);
        }

        // 0° 时四角就是未旋转的矩形
        w.rot = 0;
        double[][] c0 = w.rotCorners();
        check("0° 时四角 = 未旋转矩形",
                Math.abs(c0[0][0] - 40) < 1.0e-9 && Math.abs(c0[0][1] - 30) < 1.0e-9
                        && Math.abs(c0[2][0] - 60) < 1.0e-9 && Math.abs(c0[2][1] - 40) < 1.0e-9);

        // ② 180°：包围盒与未旋转时**完全一样**（老实现会甩到左下 ⇒ 位置看着就错）
        w.rot = 0;
        double[] b0 = w.rotatedAabb();
        w.rot = 180;
        double[] b180 = w.rotatedAabb();
        check("180° 的包围盒与 0° 完全重合（位置不会跑）",
                Math.abs(b0[0] - b180[0]) < 1.0e-9 && Math.abs(b0[1] - b180[1]) < 1.0e-9
                        && Math.abs(b0[2] - b180[2]) < 1.0e-9 && Math.abs(b0[3] - b180[3]) < 1.0e-9);

        // 90°：包围盒换成「高×宽」，仍然以中心对称
        w.rot = 90;
        double[] b90 = w.rotatedAabb();
        check("90° 的包围盒宽高互换",
                Math.abs((b90[2] - b90[0]) - w.h) < 1.0e-6
                        && Math.abs((b90[3] - b90[1]) - w.w) < 1.0e-6);
        check("90° 的包围盒仍然以控件中心为中心",
                Math.abs((b90[0] + b90[2]) / 2 - (w.x + w.w / 2.0)) < 1.0e-6
                        && Math.abs((b90[1] + b90[3]) / 2 - (w.y + w.h / 2.0)) < 1.0e-6);

        // ③ 命中检测与渲染同源：旋转后的四角位置必须能命中；中心必须命中
        for (double deg : new double[]{0, 45, 90, 180, -60}) {
            w.rot = deg;
            boolean ok = w.hitTest(w.x + w.w / 2.0, w.y + w.h / 2.0, 0.0);
            // 取「局部四角的质心附近」与「局部 (5,5) 映射过去」都该命中
            double[] p = w.rotPt(5, 5);
            ok = ok && w.hitTest(p[0], p[1], 0.001);
            // 离中心远一点（画布局部 (-5,-5) 映射）必须打不中
            double[] q = w.rotPt(-5, -5);
            ok = ok && !w.hitTest(q[0], q[1], 0.001);
            check("rot=" + deg + "：命中检测与渲染同源（局部点映射过去能打中）", ok);
        }

        // ④ 旋转不改数据：转一圈回来，四角与位置都还原
        w.rot = 0;
        double[] before = w.rotPt(0, 0);
        w.rot = 360;
        double[] after = w.rotPt(0, 0);
        check("转 360° 等价于没转",
                Math.abs(before[0] - after[0]) < 1.0e-6 && Math.abs(before[1] - after[1]) < 1.0e-6);
        check("旋转不动 x/y/w/h（数据语义没变）",
                w.x == 40 && w.y == 30 && w.w == 20 && w.h == 10);

        // ⑤ 夹取：180° 时必须还能往右拖到画布右缘附近（用户报的「卡住」）
        w.rot = 180;
        double[] cRight = top.hmjmfabc.projector.client.gui.CanvasWidgetView
                .clampPos(w, 999, 30, 64, 64);
        check("180° 时往右拖不会被卡在左边（x 能到画布右缘附近）", cRight[0] > 64 - 20);
        double[] cLeft = top.hmjmfabc.projector.client.gui.CanvasWidgetView
                .clampPos(w, -999, 30, 64, 64);
        check("180° 时往左拖有下界（不会跑到画布外几百单位）", cLeft[0] > -80);
        // 夹取前后：包围盒至少有一半落在画布内
        double[] box = w.rotatedAabb();
        double lox = box[0] - w.x, loy = box[1] - w.y;
        double bw = box[2] - box[0], bh = box[3] - box[1];
        double bx = cRight[0] + lox, by = cRight[1] + loy;
        check("夹取后包围盒仍在画布内（至少一半）",
                bx + bw / 2 >= 0 && bx + bw / 2 <= 64 && by + bh / 2 >= 0 && by + bh / 2 <= 64);

        // ⑥ 轴心只有一个真源：渲染里不许再出现「用 w.x/w.y 当轴心」的写法
        try {
            int offenders = 0;
            for (String rel : new String[]{
                    "client/render/WidgetRenderer.java",
                    "client/render/MusicWidgetRenderer.java",
                    "client/render/WebWidgetRenderer.java",
                    "client/render/SelectionRenderer.java",
                    "client/web/WebOverlayRenderer.java"}) {
                String src = new String(Files.readAllBytes(Path.of(
                        "src/main/java/top/hmjmfabc/projector/" + rel)),
                        java.nio.charset.StandardCharsets.UTF_8);
                // 注释里可以提，代码里不许有：TextRenderer.rot(w.x, w.y, …) / new Frame(w.x, w.y, …)
                for (String line : src.split("\n")) {
                    String code = line.trim();
                    if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")) continue;
                    if (code.contains("TextRenderer.rot(w.x, w.y,")
                            || code.contains("new Frame(w.x, w.y,")) {
                        offenders++;
                    }
                }
            }
            check("渲染层不再有「绕左下角」的旋转（轴心唯一）", offenders == 0);
        } catch (Exception e) {
            check("读不到渲染源码：" + e, false);
        }

        // ⑦ 坐标系不许混：音乐/网页那套绘制原语的入参是**控件局部坐标**
        //    （原语内部统一「减半宽/半高再绕中心转」）。这一条就是防本次的偏移 bug：
        //    谁再往原语里塞画布坐标（或把代理控件的原点设成 w.x/w.y），断言立刻红。
        try {
            String music = new String(Files.readAllBytes(Path.of(
                    "src/main/java/top/hmjmfabc/projector/client/render/MusicWidgetRenderer.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            check("音乐底/边框传的是局部坐标（0..w / 0..h）",
                    music.contains("double x0 = 0;") && music.contains("double x1 = w.w;"));
            check("音乐原语内部按局部坐标绕中心转（用 rotPt，不再手动减 w.x）",
                    music.contains("w.rotPt(x0, y0)") && !music.contains("x0 - w.x"));
            check("音乐播放键/波形/文字区域都传局部坐标（不再先 offset 再喂原语）",
                    music.contains("double[] box = w.buttonBox();")
                            && music.contains("double[] mid = w.waveBox();"));
            String video = new String(Files.readAllBytes(Path.of(
                    "src/main/java/top/hmjmfabc/projector/client/render/VideoOverlayRenderer.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            check("视频浮层全程用局部坐标（不许再有「局部→画布」的 offset 加法）",
                    !video.contains("w.x + local[0]")
                            && video.contains("double[] box = w.progressBox();")
                            && video.contains("double[] b = w.controlBox();"));
            // 只在**去掉注释**后的代码里查（注释里刻意写了「这里以前有 offset(...)，别加回来」）
            StringBuilder codeOnly = new StringBuilder();
            for (String line : video.split("\n")) {
                String tr = line.trim();
                if (tr.startsWith("*") || tr.startsWith("//") || tr.startsWith("/*")) continue;
                codeOnly.append(line).append('\n');
            }
            check("视频浮层的代码里没有残留的 offset(...) 调用",
                    !codeOnly.toString().contains("offset(w,"));
            String web = new String(Files.readAllBytes(Path.of(
                    "src/main/java/top/hmjmfabc/projector/client/web/WebOverlayRenderer.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            check("网页浮层的轴心是控件中心（Frame.of 用 rotX/rotY）",
                    web.contains("new Frame(w.rotX(), w.rotY(), w.rot)"));
        } catch (Exception e) {
            check("读不到渲染源码：" + e, false);
        }

        // ⑧ 棋类：命中必须**逆旋转**（旋转后落子不准就是这个漏了）
        try {
            String chess = new String(Files.readAllBytes(Path.of(
                    "src/main/java/top/hmjmfabc/projector/common/widget/ChessWidget.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            int cellAt = chess.indexOf("public int[] cellAt(");
            // 必须**先绕画布中心逆旋转**，再按几何原点（画布坐标）算格子；
            // 少这一步或换成 toLocal（会再减左下角）都会让每一格整体偏 (x, y) ——
            // 那就是「棋子/格子/判定区在三个不一样的地方」。
            int toLocal = chess.indexOf("toLocal(canvasX, canvasY)", cellAt);
            check("棋类 cellAt 把画布坐标换算回局部再算格子（与 centerOf/rotPt 严格互逆）",
                    cellAt >= 0 && toLocal > cellAt);
            // 几何原点必须与 centerOf / 格线 / 高亮同一个坐标系（**局部**）——
            // 用画布坐标会让 solidRect（局部）与 drawInBox（画布）至少一条偏 (x, y)
            check("棋类几何原点是局部坐标（与 centerOf/格线/高亮同源）",
                    chess.contains("ox = (w - spacing * (cw - 1)) * 0.5;")
                            || chess.contains("ox = (w - spacing * cw) * 0.5;"));
            // 棋盘本体的 solidRect 必须传局部坐标
            String renderer = new String(Files.readAllBytes(Path.of(
                    "src/main/java/top/hmjmfabc/projector/client/render/WidgetRenderer.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            check("棋盘底/边框的 solidRect 传局部坐标（不再传画布 w.x/w.y）",
                    !renderer.contains("solidRect(collector, ctx, w, w.x,"));
        } catch (Exception e) {
            check("读不到棋类源码：" + e, false);
        }

        // ⑨ 预览图必须画成**旋转后**的样子（否则玩家看到的是「变形的未旋转矩形」）
        try {
            String canvas = new String(Files.readAllBytes(Path.of(
                    "src/main/java/top/hmjmfabc/projector/client/gui/CanvasWidgetView.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            check("预览里控件本体带旋转矩阵（pose.mulPose + rotationZ）",
                    canvas.contains("gfx.pose()") && canvas.contains("rotationZ")
                            && canvas.contains("Math.toRadians(-w.rot)"));
            check("预览用 try/finally 还原 pose（不然网格与后续控件全跟着转）",
                    canvas.contains("pose.popPose()") && canvas.contains("finally"));
        } catch (Exception e) {
            check("读不到预览源码：" + e, false);
        }

        // ⑩ I 键重播流程：三态（没选平面 / 没流程 / 有流程）
        try {
            String input = new String(Files.readAllBytes(Path.of(
                    "src/main/java/top/hmjmfabc/projector/client/ClientInputHandler.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            String keys = new String(Files.readAllBytes(Path.of(
                    "src/main/java/top/hmjmfabc/projector/client/ProjectorKeys.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            check("I 键有独立键位绑定（可改键，属于 projector 分类）",
                    keys.contains("KEY_SEQ_REPLAY") && keys.contains("InputConstants.KEY_I"));
            check("I 键处理已挂到主循环", input.contains("KEY_SEQ_REPLAY"));
            check("没流程时提示「尚未添加流程」（走语言键）",
                    input.contains("projector.msg.seq_empty"));
            check("有流程时走服务端权威的 seqPlay",
                    input.contains("\"seqPlay\""));
            check("没选中平面时不动手（I 仍归网页输入）",
                    input.contains("plane == null || mc.player == null"));
            check("I 键优先级写死：选中平面时不给网页输入让路（避免一次按键两个响应）",
                    input.contains("SelectionState.plane() == null"));
            String zh = new String(Files.readAllBytes(Path.of(
                    "src/main/resources/assets/projector/lang/zh_cn.json")),
                    java.nio.charset.StandardCharsets.UTF_8);
            check("中文语言文件里有「尚未添加流程」", zh.contains("尚未添加流程"));
        } catch (Exception e) {
            check("读不到输入/I 键源码：" + e, false);
        }

        // ⑪ 本地音乐必须进哈希索引（否则重启后播放键失灵：要重新选歌才恢复）
        try {
            String local = new String(Files.readAllBytes(Path.of(
                    "src/main/java/top/hmjmfabc/projector/client/media/LocalMedia.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            check("扫描根目录包含音乐目录（musicDir）", local.contains("roots.add(musicDir());"));
            check("扫描放行音频扩展名（mp3/flac/wav/m4a/aac）",
                    local.contains("AUDIO_EXT") && local.contains("\"mp3\"") && local.contains("\"flac\""));
            check("音频标成「不进图片/视频列表」（复用 video 位）",
                    local.contains("if (audio) video = true;"));
        } catch (Exception e) {
            check("读不到 LocalMedia 源码：" + e, false);
        }

        // ⑫ 两套「中心」的语义不许再被改坏（本轮把 rotX 改成局部值，导致音乐标题/时间整体偏移）
        try {
            String widget = new String(Files.readAllBytes(Path.of(
                    "src/main/java/top/hmjmfabc/projector/common/widget/Widget.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            check("rotX/rotY 是**画布**中心（x + w/2）—— drawInBox 的轴心要的就是它",
                    widget.contains("return x + w / 2.0;") && widget.contains("return y + h / 2.0;"));
            check("另有 localRotX/localRotY 给局部↔画布换算用",
                    widget.contains("public double localRotX()") && widget.contains("public double localRotY()"));
            check("rotPt 用局部轴心换算", widget.contains("rotAround(localRotX(), localRotY(), rot, lx, ly)"));
            String renderer = new String(Files.readAllBytes(Path.of(
                    "src/main/java/top/hmjmfabc/projector/client/render/WidgetRenderer.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            check("棋类的 drawInBox 用画布中心当轴心（不是局部）",
                    renderer.contains("w.rot, w.alpha, dPiece, w.rotX(), w.rotY());"));
        } catch (Exception e) {
            check("读不到 Widget/渲染源码：" + e, false);
        }

        // ⑬ 键位说明必须与绑定一致（「取消圈选 (I)」那句错了很久）
        try {
            String zh = new String(Files.readAllBytes(Path.of(
                    "src/main/resources/assets/projector/lang/zh_cn.json")),
                    java.nio.charset.StandardCharsets.UTF_8);
            String keys = new String(Files.readAllBytes(Path.of(
                    "src/main/java/top/hmjmfabc/projector/client/ProjectorKeys.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            check("取消圈选绑定的是 Esc（不是 I）", keys.contains("InputConstants.KEY_ESCAPE"));
            check("中文键位说明写的是 Esc（与绑定一致）",
                    zh.contains("\"key.projector.cancel\": \"取消圈选 (Esc)\""));
            check("中文键位说明不再声称 cancel 是 I)",
                    !zh.contains("取消圈选 (I)"));
        } catch (Exception e) {
            check("读不到键位/语言文件：" + e, false);
        }

        // ⑬ 未旋转时的夹取行为与老实现一致（不许把没旋转的控件弄丢）
        w.rot = 0;
        double[] c0r = top.hmjmfabc.projector.client.gui.CanvasWidgetView
                .clampPos(w, 999, 999, 64, 64);
        check("未旋转：右下夹取 = 画布内（与老行为一致）",
                c0r[0] <= 64 - 20 * 0.5 + 1.0e-6 && c0r[1] <= 64 - 10 * 0.5 + 1.0e-6);
    }

    // ------------------------------------------------------------------ 存档

    private static void nbt() {
        WebWidget w = new WebWidget();
        w.w = 96;
        w.h = 54;
        w.url = "https://example.com/a";
        w.homeUrl = "https://home.example";
        w.zoom = 1.5;
        w.pixelPerUnit = 12;
        w.showControls = false;
        w.publicControls = false;
        w.autoRefreshSec = 30;
        w.transparent = true;

        CompoundTag t = w.save();
        Widget back = Widgets.load(t);
        check("存档往返后仍是网页控件", back instanceof WebWidget);
        WebWidget b = (WebWidget) back;
        check("地址往返一致", "https://example.com/a".equals(b.url) && "https://home.example".equals(b.homeUrl));
        check("缩放与密度往返一致", Math.abs(b.zoom - 1.5) < 1e-6 && Math.abs(b.pixelPerUnit - 12) < 1e-6);
        check("开关与自动刷新往返一致",
                !b.showControls && !b.publicControls && b.autoRefreshSec == 30 && b.transparent);

        CompoundTag bad = w.save();
        bad.putString("url", "file:///etc/passwd");
        bad.putString("homeUrl", "javascript:alert(1)");
        WebWidget bb = (WebWidget) Widgets.load(bad);
        check("存档里的非法地址读回来被丢弃",
                bb.url.isEmpty() && bb.homeUrl.isEmpty() && WebWidget.BLANK.equals(bb.targetUrl()));

        WebWidget c = new WebWidget();
        c.url = "file:///x";
        c.homeUrl = "data:text/html,x";
        c.zoom = 99;
        c.pixelPerUnit = 0.1;
        c.autoRefreshSec = -5;
        c.sanitize();
        check("sanitize 清掉非法地址", c.url.isEmpty() && c.homeUrl.isEmpty());
        check("sanitize 夹住数值范围",
                c.zoom == 5 && c.pixelPerUnit == 1 && c.autoRefreshSec == 0);

        CompoundTag empty = new CompoundTag();
        WebWidget d = new WebWidget();
        d.loadExtra(empty);
        check("空存档 = 默认值（有按钮栏、允许他人操作、不透明）",
                d.showControls && d.publicControls && !d.transparent && d.url.isEmpty());
    }

    // ------------------------------------------------------------------ 类型注册

    private static void kindRegistry() {
        check("新类型号 = 10", Widget.KIND_WEB == 10);
        check("类型名表里有 web 且长度对得上",
                Widget.KIND_IDS.length > 10 && "web".equals(Widget.KIND_IDS[Widget.KIND_WEB]));
        Widget w = Widget.create(Widget.KIND_WEB);
        check("工厂能造出网页控件", w instanceof WebWidget);
        if (w != null) {
            check("kindId() 是 web", "web".equals(w.kindId()) && w.kind() == Widget.KIND_WEB);
        }
        Widget def = Widgets.createDefault(Widget.KIND_WEB);
        check("默认尺寸按 16:9 起手",
                def instanceof WebWidget && Math.abs(def.w / def.h - 96.0 / 54.0) < 0.02);
        check("网页控件不是「媒体素材」控件（不需要上传/预取）", !def.isMedia());
    }

    // ------------------------------------------------------------------ 许可边界

    private static void licenseBoundary() {
        List<String> javaFiles = listJava(Path.of("src/main/java"));
        check("能扫到源码文件（自检没瞎）", javaFiles.size() > 100);

        List<String> forbiddenImports = new ArrayList<>();
        for (String f : javaFiles) {
            String s = readFile(f);
            if (s.contains("import com.cinemamod") || s.contains("import org.cef")
                    || s.contains("import org.jcef")) {
                forbiddenImports.add(f);
            }
        }
        check("没有任何文件 import MCEF / CEF 的类（LGPL 边界）" + (forbiddenImports.isEmpty() ? ""
                : " -> " + forbiddenImports), forbiddenImports.isEmpty());

        String gradle = readFile("build.gradle");
        check("build.gradle 里没有 mcef / jcef / cef 依赖",
                !gradle.toLowerCase(java.util.Locale.ROOT).contains("mcef")
                        && !gradle.toLowerCase(java.util.Locale.ROOT).contains("jcef"));

        String bridge = readFile("src/main/java/top/hmjmfabc/projector/client/web/McefBridge.java");
        check("反射桥里类名只以字符串出现（Class.forName 的那一份）",
                bridge.contains("com.cinemamod.mcef.MCEF") && !bridge.contains("import com.cinemamod"));
        check("反射桥用的是 forName/getMethod（不是直接调用）",
                bridge.contains("Class.forName") && bridge.contains("getMethod"));

        String sessions = readFile("src/main/java/top/hmjmfabc/projector/client/web/WebSessions.java");
        check("会话管理器也不 import MCEF", !sessions.contains("import com.cinemamod") && !sessions.contains("import org.cef"));
        // 裸的 org.cef.CefClient 内部只存单个 handler 字段：对它挂 handler 会顶掉 MCEF 自己的。
        // ⚠ 只匹配**真实的链式调用**（`getHandle().xxx`）——我们自己在注释里反复提醒"别这么干"，
        //   光看有没有出现 getHandle() 会把注释也算进去（这条断言第一版就踩了这个坑）。
        check("没有真的对裸 CefClient 挂 handler（注释里提到不算）",
                !bridge.matches("(?s).*getHandle\\(\\)\\s*\\..*"));
        check("标题只走 MCEFClient 的聚合 API（addDisplayHandler 在 MCEFClient 上）",
                bridge.contains("MCEFClient") && bridge.contains("addDisplayHandler"));
        check("页面标题走动态代理实现 org.cef 的接口（无需 import）",
                bridge.contains("Proxy.newProxyInstance") && bridge.contains("CefDisplayHandler"));
    }

    // ------------------------------------------------------------------ 契约签名

    private static void contractSignatures() {
        try {
            Class<?> bridge = Class.forName("top.hmjmfabc.projector.client.web.McefBridge");
            check("McefBridge.available() 存在",
                    bridge.getMethod("available").getReturnType() == boolean.class);
            check("McefBridge.reason() 返回字符串",
                    bridge.getMethod("reason").getReturnType() == String.class);
            check("McefBridge.create(String,boolean,int,int) 存在",
                    bridge.getMethod("create", String.class, boolean.class, int.class, int.class) != null);

            Class<?> session = Class.forName("top.hmjmfabc.projector.client.web.McefBridge$Session");
            String[] need = {"textureId", "valid", "load", "reload", "reloadIgnoreCache", "back", "forward",
                    "canGoBack", "canGoForward", "loading", "currentUrl", "setZoom", "resize",
                    "mouseMove", "mousePress", "mouseRelease", "mouseWheel", "setFocus", "close"};
            List<String> missing = new ArrayList<>();
            for (String m : need) {
                boolean any = false;
                for (Method mm : session.getMethods()) {
                    if (mm.getName().equals(m)) {
                        any = true;
                        break;
                    }
                }
                if (!any) {
                    missing.add(m);
                }
            }
            check("Session 的公开方法齐全（缺：" + missing + "）", missing.isEmpty());
            check("textureId() 返回 int（GL 纹理 id）",
                    session.getMethod("textureId").getReturnType() == int.class);

            Class<?> ws = Class.forName("top.hmjmfabc.projector.client.web.WebSessions");
            Class<?> web = WebWidget.class;
            check("WebSessions.textureFor(WebWidget) 存在",
                    ws.getMethod("textureFor", web) != null);
            check("WebSessions.action(WebWidget,int) 存在",
                    ws.getMethod("action", web, int.class) != null);
            check("WebSessions.navigate(WebWidget,String) 存在",
                    ws.getMethod("navigate", web, String.class) != null);
            // 规范里写的是 discard(WebWidget)；实现里叫 releaseWidget(UUID)（同一件事）。
            // 两者任一存在即算契约成立 —— 断言的是「有办法丢掉某个控件的会话」，不是名字。
            boolean canDrop;
            try {
                ws.getMethod("discard", web);
                canDrop = true;
            } catch (NoSuchMethodException e) {
                canDrop = ws.getMethod("releaseWidget", java.util.UUID.class) != null;
            }
            check("能丢掉某个控件的会话（discard(WebWidget) 或等价的 releaseWidget(UUID)）", canDrop);
            check("WebSessions.hasSession(WebWidget) 存在",
                    ws.getMethod("hasSession", web) != null);
            check("WebSessions.status(WebWidget) 返回字符串",
                    ws.getMethod("status", web).getReturnType() == String.class);
        } catch (ClassNotFoundException e) {
            failed.add("找不到反射桥/会话管理器的类（网页控件还没接线完）：" + e.getMessage());
        } catch (NoSuchMethodException e) {
            failed.add("契约签名缺失：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 界面上线检查

    private static void uiWiring() {
        String picker = readFile("src/main/java/top/hmjmfabc/projector/client/gui/AddWidgetScreen.java");
        check("「新增控件」里有网页入口", picker.contains("KIND_WEB"));
        check("入口用 McefBridge.available() 做门禁", picker.contains("McefBridge.available()"));
        check("缺模组时有可操作提示（文案含 MCEF）", picker.contains("MCEF"));

        String renderer = readFile("src/main/java/top/hmjmfabc/projector/client/render/WidgetRenderer.java");
        check("渲染器有网页分支", renderer.contains("KIND_WEB"));
        check("渲染器把网页纹理交给专门的渲染器", renderer.contains("WebWidgetRenderer"));

        String editor = readFile("src/main/java/top/hmjmfabc/projector/client/gui/WidgetEditorScreen.java");
        check("编辑器有网页分支", editor.contains("WebWidget"));
        check("编辑器里刷新/后退/前进/主页都在（第一行）",
                editor.contains("WebSessions.navigate") && editor.contains("\u5237\u65b0")
                        && editor.contains("\u540e\u9000") && editor.contains("\u524d\u8fdb"));

        String controls = readFile("src/main/java/top/hmjmfabc/projector/client/web/WebControls.java");
        check("世界内按钮栏有「5 秒自动隐藏」那套", controls.contains("5000") || controls.contains("AUTO_HIDE"));

        String lang = readFile("src/main/resources/assets/projector/lang/zh_cn.json");
        String en = readFile("src/main/resources/assets/projector/lang/en_us.json");
        check("中文语言文件里有网页控件", lang.contains("web"));
        check("英文语言文件里有网页控件", en.contains("web"));

        String config = readFile("src/main/java/top/hmjmfabc/projector/ProjectorConfig.java");
        check("配置里有 web 段（会话上限/释放/无帧超时）",
                config.contains("\"maxSessions\"") && config.contains("\"idleReleaseSec\"")
                        && config.contains("\"noFrameTimeoutSec\""));
        String server = readFile("src/main/java/top/hmjmfabc/projector/network/ServerNetHandler.java");
        check("服务端清洗网页控件字段", server.contains("WebWidget"));

        // 安卓 + 装了 MCEF 却没装安卓兼容层 ⇒ MCEF 自己会在初始化时崩游戏，必须在日志最前面点名
        String main = readFile("src/main/java/top/hmjmfabc/projector/Projector.java");
        check("主类里检测 MCEF 的安卓兼容层（modid mcefdroid）",
                main.contains("mcefdroid") && main.contains("warnIfAndroidWebStackIncomplete"));
        check("该提示也在构造期第一步（排在装载信息之前）",
                main.indexOf("warnIfAndroidWebStackIncomplete();")
                        < main.indexOf("\u6295\u5f71\u4eea\u5df2\u88c5\u8f7d"));
        check("提示用 ERROR 级并写明会崩游戏",
                main.contains("LOGGER.error(\"[Projector] \u26a0 \u4e0d\u88c5\u5b83\u4f1a\u5bfc\u81f4\u6e38\u620f\u5d29\u6e83"));
    }

    // ------------------------------------------------------------------ 生命周期硬约束

    private static void lifecycle() {
        String s = readFile("src/main/java/top/hmjmfabc/projector/client/web/WebSessions.java");
        check("有并发上限常量（默认 2 个浏览器）", s.contains("MAX_SESSIONS"));
        check("有离开视野释放的时间常量（约 15 秒）", s.contains("IDLE_RELEASE_MS"));
        check("有「无帧超时降级」的时间常量（约 10 秒）", s.contains("NO_FRAME_TIMEOUT_MS"));
        // 【27.2】这三个量曾经是写死的常量、配置里那三个键没人读 ⇒ 死键。
        // 断言「配置真的被读」+「读不到会退回默认值」（渲染路径每帧都走，绝不许因配置塌掉）。
        check("三个 web 配置键真的被读（不是死键）",
                s.contains("webMaxSessions") && s.contains("webIdleReleaseSec")
                        && s.contains("webNoFrameTimeoutSec"));
        // 【真机事故】编辑器里拖尺寸会每帧触发 resize，而 resize 要 stop+重启 CDP 流
        // ⇒ 流刚起就被打断、画面永远只有第一帧。必须「稳定一小段时间」才下发。
        check("尺寸变化有去抖（不是每帧 resize）",
                s.contains("RESIZE_SETTLE_MS") && s.contains("pendingSinceMs"));
        check("去抖窗口不小于 300ms", s.matches("(?s).*RESIZE_SETTLE_MS\\s*=\\s*(3|4|5|6|7|8|9)\\d{2}L.*"));
        check("读配置失败会退回默认值",
                s.contains("return MAX_SESSIONS;") && s.contains("return IDLE_RELEASE_MS;")
                        && s.contains("return NO_FRAME_TIMEOUT_MS;"));
        check("纹理尺寸被夹在 1920x1080 以内", s.contains("1920") && s.contains("1080"));
        check("释放时把纹理从纹理管理器注销（release）", s.contains("release"));
        check("日志带前缀且限流（不是每次调用都打）", s.contains("[Projector][\u7f51\u9875]"));
    }

    // ------------------------------------------------------------------ 工具

    private static List<String> listJava(Path root) {
        List<String> out = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(p -> p.toString().endsWith(".java")).forEach(p -> out.add(p.toString()));
        } catch (Exception e) {
            failed.add("遍历源码失败：" + e);
        }
        return out;
    }

    private static String readFile(String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (Exception e) {
            failed.add("读不到 " + path);
            return "";
        }
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  \u2705 " + name);
        } else {
            failed.add(name);
        }
    }
}

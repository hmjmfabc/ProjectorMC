import top.hmjmfabc.projector.client.web.WebControls;
import top.hmjmfabc.projector.client.web.WebInput;
import top.hmjmfabc.projector.client.web.WebPointerSink;
import top.hmjmfabc.projector.client.web.WebTextSink;
import top.hmjmfabc.projector.common.Plane;
import top.hmjmfabc.projector.common.widget.WebWidget;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * T35 —— 【27.2】网页控件的**输入转发**（世界里用鼠标/键盘操作网页画面）纯逻辑套件。
 *
 * <p>覆盖十类：①**页面坐标换算**（两轴翻转之后的显示方向，最容易写成上下颠倒）
 * ②越界钳制 ③悬停节流 ④点击的 press→(下一 tick)release 队列
 * ⑤权限口径（与四个导航按钮**同一份**判据）⑥<b>左键才转发、右键打开编辑器</b>
 * （snapshot-127 用户口径，真机日志定稿）⑦**坐标口径不变**
 * （送进桥的是「浏览器视口像素」，桥再折成 CSS 像素 —— 源码断言挡「顺手改成 CSS 像素」）
 * ⑧键盘转发与「输入给网页」（含 {@code insertText} 的转发/降级分支，用**假 Session 计数**）
 * ⑨接线与源码级断言 ⑩<b>点击转发链</b>：用**假会话**证明
 * 「左键 + 有会话 + 有权限 ⇒ 真的调用 {@code mousePress}/{@code mouseRelease}」，
 * 并钉住「权限不足不转发但提示 / 无会话返回 false 放行 / 判据只有一份」。</p>
 *
 * <p>全是纯逻辑 + 读源码：不需要客户端、不需要前置模组、可以无头跑。</p>
 */
public class T35 {

    private static int passed = 0;
    private static final List<String> failed = new ArrayList<>();

    public static void main(String[] args) {
        System.out.println("== T35 网页控件输入转发（27.2）==");
        pageCoords();
        uvMatchesClick();
        coordinateBasis();
        clamping();
        throttle();
        clickQueue();
        permission();
        buttonMapping();
        pageClickChain();
        keyboard();
        textInput();
        sourceLevel();
        wiring();
        cursor();

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

    // ------------------------------------------------------------------ ① 页面坐标换算

    private static void pageCoords() {
        WebWidget w = widget(96, 54);
        final int texW = 768;
        final int texH = 432;

        check("中点：局部 (48,27) → 页面 (384,216)（中心点翻不翻都一样）",
                WebInput.pageX(w, 48, texW) == 384 && WebInput.pageY(w, 27, texH) == 216);
        // ★【rc-139，玩家真机实测】原先写的是「两轴都翻」，结果是**墙面上的网页整体倒过来**
        //   （同一面墙上的文字控件是正的）。现在改为「左上对左上」：
        //   控件左上角 = 页面的左上角 —— 一眼能读的那种对法，也是 T35 里最好写的一条不变量。
        check("左上角：局部 (0,54) → 页面 (0,0)（控件左上 = 页面左上）",
                WebInput.pageX(w, 0, texW) == 0 && WebInput.pageY(w, 54, texH) == 0);
        check("左下角：局部 (0,0) → 页面 (0,432)（页面的左下角）",
                WebInput.pageX(w, 0, texW) == 0 && WebInput.pageY(w, 0, texH) == texH);
        check("右上角：局部 (96,54) → 页面 (768,0)（页面的右上角）",
                WebInput.pageX(w, 96, texW) == texW && WebInput.pageY(w, 54, texH) == 0);
        check("右下角：局部 (96,0) → 页面 (768,432)",
                WebInput.pageX(w, 96, texW) == texW && WebInput.pageY(w, 0, texH) == texH);
        check("对角对称性：中心对称的两点之和恒等于视口尺寸",
                WebInput.pageX(w, 0, texW) + WebInput.pageX(w, 96, texW) == texW
                        && WebInput.pageY(w, 0, texH) + WebInput.pageY(w, 54, texH) == texH);

        boolean linearX = true;
        for (int i = 0; i <= 96; i += 8) {
            if (Math.abs(WebInput.pageX(w, i, texW) - i * 8) > 1) {
                linearX = false;
            }
        }
        check("x 方向线性 = 8i（控件往右 = 页面往右）", linearX);

        boolean linearY = true;
        for (int j = 0; j <= 54; j += 6) {
            if (Math.abs(WebInput.pageY(w, j, texH) - (texH - j * 8)) > 1) {
                linearY = false;
            }
        }
        check("y 方向线性 = 432 - 8j（控件往上 = 页面往上：局部 y 向上、页面 y 向下）", linearY);

        check("texW/texH 非法时不炸（一律 0，不除零、不 NaN）",
                WebInput.pageX(w, 48, 0) == 0 && WebInput.pageY(w, 27, 0) == 0);
        WebWidget zero = widget(0, 0);
        check("控件尺寸为 0 时也不炸（回 0）",
                WebInput.pageX(zero, 10, 768) == 0 && WebInput.pageY(zero, 10, 432) == 0);
        check("视口尺寸随控件走（1x1 单位的控件也不会去乘同一个 texW 就算对）",
                WebInput.pageX(widget(48, 27), 24, 384) == 192);
    }

    /**
     * ①a 【rc-139】渲染 UV 与点击映射必须是**同一个朝向**。
     *
     * <p>这是本轮最要紧的一条断言：`QuadCollector.canvasQuad` 的角约定是
     * <b>a=左下→(u0,v1)、d=左上→(u0,v0)</b>，而纹理 (u,v) 对应的页面像素是
     * (u·W, v·H)（页面 y 向下、v=0 是页面顶行）。把这两件事合起来：
     * </p>
     * <pre>
     *   控件左上角(0, h) 采样 (texU0, texV0) ⇒ pageX(0)=texU0·W、pageY(h)=texV0·H
     *   控件左下角(0, 0) 采样 (texU0, texV1) ⇒ pageX(0)=texU0·W、pageY(0)=texV1·H
     * </pre>
     * <p>于是「渲染怎么画」和「点击算哪里」被绑在一起：谁单独改了都会被这条抓住。</p>
     */
    private static void uvMatchesClick() {
        WebWidget w = widget(96, 54);
        final int texW = 768;
        final int texH = 432;
        check("控件左上角采到的纹理坐标 = 点击映射算出的页面点（texU0/texV0）",
                Math.abs(WebInput.pageX(w, 0, texW) - WebWidget.texU0() * texW) <= 1
                        && Math.abs(WebInput.pageY(w, w.h, texH) - WebWidget.texV0() * texH) <= 1);
        check("控件左下角采到的纹理坐标 = 点击映射算出的页面点（texU0/texV1）",
                Math.abs(WebInput.pageX(w, 0, texW) - WebWidget.texU0() * texW) <= 1
                        && Math.abs(WebInput.pageY(w, 0, texH) - WebWidget.texV1() * texH) <= 1);
        check("控件右上角采到的纹理坐标 = 点击映射算出的页面点（texU1/texV0）",
                Math.abs(WebInput.pageX(w, w.w, texW) - WebWidget.texU1() * texW) <= 1
                        && Math.abs(WebInput.pageY(w, w.h, texH) - WebWidget.texV0() * texH) <= 1);
        // 朝向本体：rc-139 的定稿是「左上对左上」（四角都不翻转）
        check("本版朝向 = (0,0,1,1)：网页与图片/视频控件一致（改了必须真机实测）",
                WebWidget.texU0() == 0f && WebWidget.texV0() == 0f
                        && WebWidget.texU1() == 1f && WebWidget.texV1() == 1f);
    }

    // ------------------------------------------------------------------ ①b 坐标口径：浏览器视口像素（不是 CSS 像素）

    private static void coordinateBasis() {
        WebWidget w = widget(96, 54);
        // 口径本体：换算就是「局部 / 控件尺寸 × 视口尺寸」——视口尺寸由调用方（WebSessions）给，
        // 代码里不许出现任何写死的「排版视口」数字（1280x720 那种）。
        check("x 换算就是 localX / w.w * texW（没有第二层缩放）",
                Math.abs(WebInput.pageX(w, 24, 192) - 48) < 1.0e-9
                        && Math.abs(WebInput.pageX(w, 24, 768) - 192) < 1.0e-9);
        check("换一对视口数字结果跟着比例走（若代码里写死了某个视口，这里必红）",
                WebInput.pageX(w, 48, 480) == 240 && WebInput.pageY(w, 27, 270) == 135
                        && WebInput.pageX(w, 48, 1280) == 640);

        String web = readFile(
                "src/main/java/top/hmjmfabc/projector/client/web/WebInput.java");
        check("注释写明口径 = **浏览器视口像素**（抓帧/浏览器那份），不是 CSS 像素",
                web.contains("\u6d4f\u89c8\u5668\u89c6\u53e3\u50cf\u7d20") && web.contains("CSS \u50cf\u7d20"));
        check("注释写明「CSS 像素由桥折」（一条换算两份代码就会有一份忘改）",
                web.contains("\u7531\u6865\u53bb\u505a") || web.contains("\u7531\u6865\u53bb"));
        check("注释写明这里是**二次换算**风险点，并点名不许在这层改成 CSS 像素",
                web.contains("\u4e8c\u6b21\u6362\u7b97"));
        // ★【rc-139】换算不再是「手写翻哪个轴」，而是**由渲染用的 UV 常量算出来**：
        //   这样「画面朝向」与「点到哪」只有一个真源，改一处另一处自动跟着走。
        check("x 换算由 texU0()/texU1() 推出（不再手写 1 - localX / w.w）",
                web.contains("WebWidget.texU0()")
                        && !web.contains("(1.0 - localX / w.w)"));
        check("y 换算由 texV1()/texV0() 推出（不再手写 localY / w.h）",
                web.contains("WebWidget.texV1() + (WebWidget.texV0() - WebWidget.texV1()) * t"));
        check("换算仍取「已下发的 Entry 尺寸」（WebSessions.viewportFor），没有另算一份",
                web.contains("WebSessions.viewportFor(") && web.contains("int[] size = WebSessions.viewportFor"));
    }

    // ------------------------------------------------------------------ ② 越界钳制

    private static void clamping() {
        WebWidget w = widget(96, 54);
        int[] in = WebInput.pagePoint(w, 48, 27, 768, 432);
        check("框内不动：中点还是中点", in[0] == 384 && in[1] == 216);
        int[] low = WebInput.pagePoint(w, -5, -3, 768, 432);
        check("负方向越界 → 夹进 (0, texH-1) = (0,431)", low[0] == 0 && low[1] == 431);
        int[] high = WebInput.pagePoint(w, 200, 300, 768, 432);
        check("正方向越界 → 夹进 (texW-1, 0) = (767,0)", high[0] == 767 && high[1] == 0);
        int[] edge = WebInput.pagePoint(w, 96, 54, 768, 432);
        check("贴边（控件右上角 ⇒ 页面右上角）也被钳进视口内",
                edge[0] == 767 && edge[1] == 0);

        double[] l = WebInput.clampLocal(w, -3, 99);
        check("局部坐标也夹进控件框（页面光标不会画到控件外）", l[0] == 0 && l[1] == 54);
        double[] l2 = WebInput.clampLocal(w, 50, 20);
        check("框内的局部坐标原样保留", l2[0] == 50 && l2[1] == 20);
    }

    // ------------------------------------------------------------------ ③ 悬停节流

    private static void throttle() {
        WebInput.HoverThrottle t = new WebInput.HoverThrottle();
        UUID id = UUID.randomUUID();
        check("第一次一定发", t.shouldSend(id, 10, 20, 1000));
        t.note(id, 10, 20, 1000);
        check("同一像素不重复发（隔多久问都不发）",
                !t.shouldSend(id, 10, 20, 1010)
                        && !t.shouldSend(id, 10, 20, 2000)
                        && !t.shouldSend(id, 10, 20, 9000));
        check("位置变了但间隔不足 " + WebInput.HOVER_MIN_INTERVAL_MS + " ms 也不发",
                !t.shouldSend(id, 11, 20, 1005));
        check("位置变了且过了一个间隔才发",
                t.shouldSend(id, 11, 20, 1000 + WebInput.HOVER_MIN_INTERVAL_MS));
        t.note(id, 11, 20, 1000 + WebInput.HOVER_MIN_INTERVAL_MS);
        UUID other = UUID.randomUUID();
        check("换成另一个控件立刻发（不让别的控件被前一个的节流饿死）",
                t.shouldSend(other, 10, 20, 1001));
        t.note(other, 10, 20, 1001);
        check("换回来也算「换了控件」⇒ 立刻发", t.shouldSend(id, 11, 20, 1002));
        t.reset();
        check("准心离开再回来：同一个像素也要重发一次", t.shouldSend(id, 11, 20, 1003));

        // 【27.2-pre-136】悬停转发从 30 Hz 降到 20 Hz（50 ms）：鼠标移动只是给页面看的
        // 「光标在哪」，安卓上每次转发都要过一次 JNI，30 Hz 纯属白烧 CPU。
        check("节流频率是 ~20 Hz（" + WebInput.HOVER_MIN_INTERVAL_MS + " ms），不是每帧",
                WebInput.HOVER_MIN_INTERVAL_MS >= 45 && WebInput.HOVER_MIN_INTERVAL_MS <= 60);
    }

    // ------------------------------------------------------------------ ④ 点击队列（press → 下一 tick release）

    private static void clickQueue() {
        WebInput.ClickQueue q = new WebInput.ClickQueue();
        UUID id = UUID.randomUUID();
        q.press(id, 100, 200, WebInput.MC_BUTTON_LEFT, 5000);
        check("按下之后**同一帧**不发松开（页面会把同一帧的 press/release 当成抖动）",
                q.poll(5000) == null && q.size() == 1);
        WebInput.Pending released = q.poll(5000 + WebInput.PRESS_SETTLE_MS);
        check("等够 " + WebInput.PRESS_SETTLE_MS + " ms（≈ 下一 tick）才发出松开",
                released != null && released.pageX() == 100 && released.pageY() == 200);
        check("发完队列就空了", q.size() == 0 && q.poll(999999) == null);

        WebInput.Pending p = q.press(id, 7, 8, WebInput.MC_BUTTON_LEFT, 6000);
        check("松开带着按下时的页面坐标 / 按键 / 到期时刻",
                p.widgetId().equals(id) && p.pageX() == 7 && p.pageY() == 8
                        && p.button() == WebInput.MC_BUTTON_LEFT
                        && p.dueMs() == 6000 + WebInput.PRESS_SETTLE_MS);
        q.clear();
        check("clear 之后是空的", q.size() == 0);

        for (int i = 0; i < WebInput.MAX_PENDING + 4; i++) {
            q.press(id, i, i, WebInput.MC_BUTTON_LEFT, 7000);
        }
        check("连点不会让队列无限长（上限 " + WebInput.MAX_PENDING + "）",
                q.size() == WebInput.MAX_PENDING);

        WebInput.ClickQueue q2 = new WebInput.ClickQueue();
        q2.press(id, 1, 1, WebInput.MC_BUTTON_LEFT, 8000);
        q2.press(id, 2, 2, WebInput.MC_BUTTON_LEFT, 8000);
        WebInput.Pending first = q2.poll(9000);
        WebInput.Pending second = q2.poll(9000);
        check("先进先出（两次点击的松开顺序不乱）",
                first != null && second != null && first.pageX() == 1 && second.pageX() == 2);
        check("按钮编码是 MC / GLFW 口径（左 0 / 右 1；MCEF 的 sendMousePress 会自己 1/2 互换）",
                WebInput.MC_BUTTON_RIGHT == 1 && WebInput.MC_BUTTON_LEFT == 0);
    }

    // ------------------------------------------------------------------ ⑤ 权限口径

    private static void permission() {
        // 判据只有一份：WebControls.usable —— 与世界里那四个导航按钮同一个方法
        check("作者允许他人操作 → 能用", WebControls.usable(true, false));
        check("作者禁止但我是能改内容的人 → 能用（作者自己仍然用得动）",
                WebControls.usable(false, true));
        check("作者禁止 + 我也改不了内容 → **不能用**（这就是 click/scroll 不转发的条件）",
                !WebControls.usable(false, false));
        check("两条都满足当然能用", WebControls.usable(true, true));

        WebWidget open = widget(96, 54);
        open.publicControls = true;
        WebWidget closed = widget(96, 54);
        closed.publicControls = false;
        check("拿不到所属平面时按最严处理：publicControls=true 才放行",
                WebControls.usable((Plane) null, open));
        check("拿不到所属平面 + 禁止他人操作 → 不放行（失败方向是「拒绝」）",
                !WebControls.usable((Plane) null, closed));
        check("控件为 null 一律不放行", !WebControls.usable((Plane) null, null));

        // 行为层：被拒时**一定不转发**。没有会话的情况下两种结果可以区分：
        //   权限被拒       ⇒ 返回 true（这次交互被吃掉，免得左键变成挖方块）
        //   有权限但没会话 ⇒ 返回 false（没吃掉、也没有任何转发发生）
        check("publicControls=false ⇒ 左键 click 被拒（返回「已吃掉」，没有任何转发）",
                WebInput.click((Plane) null, closed, 40, 20, WebInput.MC_BUTTON_LEFT));
        check("publicControls=true ⇒ 同一条路走到「没有会话」⇒ 返回 false（不转发、也不乱吃）",
                !WebInput.click((Plane) null, open, 40, 20, WebInput.MC_BUTTON_LEFT));
        check("publicControls=false ⇒ scroll 被拒（滚轮被吃掉）",
                WebInput.scroll((Plane) null, closed, 40, 20, 1.0));
        check("publicControls=true ⇒ scroll 走到「没有会话」⇒ 返回 false",
                !WebInput.scroll((Plane) null, open, 40, 20, 1.0));
        check("滚轮 delta = 0 不算一次交互", !WebInput.scroll((Plane) null, open, 40, 20, 0.0));
        check("滚轮 delta = NaN 也不转发", !WebInput.scroll((Plane) null, open, 40, 20, Double.NaN));
    }

    // ---------------------------------------- ⑥ 左键才转发、右键打开编辑器（snapshot-127 用户口径）

    private static void buttonMapping() {
        WebWidget open = widget(96, 54);
        open.publicControls = true;
        WebWidget closed = widget(96, 54);
        closed.publicControls = false;

        // 判据：**左键**走到「权限」那道闸门（被拒时返回 true = 吃掉 + 明文提示），
        // 右键/中键在**第一行**就被放行（返回 false，不转发也不吃）——
        // 右键要落回「打开这个网页控件的编辑器」那条既有路径（与全模组惯例一致）。
        check("左键（0）会走到权限闸门 ⇒ 被拒时返回 true（这就是「转发路径存在」的证据）",
                WebInput.click((Plane) null, closed, 40, 20, 0));
        check("右键（1）在闸门第一行就返回 false —— 不转发（上层只提示「请用左键」）",
                !WebInput.click((Plane) null, closed, 40, 20, 1)
                        && !WebInput.click((Plane) null, open, 40, 20, 1));
        check("中键（2）同样不转发", !WebInput.click((Plane) null, closed, 40, 20, 2));

        check("按钮编码是 MC / GLFW 口径（左 0 / 右 1；MCEF 的 sendMousePress 会自己 1/2 互换）",
                WebInput.MC_BUTTON_LEFT == 0 && WebInput.MC_BUTTON_RIGHT == 1);

        // 判据本体（纯函数）：useItem（右键那条路）一票否决；attack 或 GLFW 左键 ⇒ 转发
        check("判据：认成 use（右键）⇒ 转发",
                WebInput.isWebClick(false, true, WebInput.NO_MOUSE_BUTTON));
        check("判据：GLFW 层按下的是左键 ⇒ 转发（attack 缺失时那条兜底证据）",
                WebInput.isWebClick(false, false, WebInput.MC_BUTTON_LEFT));
        check("判据：右键（认成 use）⇒ 绝不转发，哪怕 attack 也在",
                !WebInput.isWebClick(true, true, WebInput.MC_BUTTON_LEFT));
        check("判据：没有证据 ⇒ 不转发",
                !WebInput.isWebClick(false, false, WebInput.NO_MOUSE_BUTTON));

        String in = readFile(
                "src/main/java/top/hmjmfabc/projector/client/ClientInputHandler.java");
        String web = readFile(
                "src/main/java/top/hmjmfabc/projector/client/web/WebInput.java");

        String clickBody = after(web, "public static boolean click(Plane plane");
        int btn = indexOr(clickBody, "isWebClick(false, false, mcButton)");
        int gate = indexOr(clickBody, "WebControls.usable(plane, w)");
        check("click 里第一道真正的闸门就是「只认左键」（在权限与会话之前）", btn > 0 && gate > btn);

        // 转发链只有一份：handleWebPress（交互事件那条路 + 鼠标层补发都调它）
        String pressBody = after(in, "private static boolean handleWebPress");
        int bar = indexOr(pressBody, "WebControls.handleWorldClick");
        int pageClick = indexOr(pressBody, "WebInput.click(");
        check("按钮栏命中优先于页面点击（命中按钮时绝不转发给网页）", bar > 0 && pageClick > bar);
        check("转发链只有一处：ClientInputHandler 里只有 handleWebPress 调 WebInput.click",
                count(in, "WebInput.click(") == 1 && pressBody.contains("mcButton"));
        check("两个入口都走同一条转发链（交互事件那条 + 鼠标层补发），且都传左键",
                count(in, "handleWebPress(mc, ") == 2
                        && count(in, "WebInput.MC_BUTTON_LEFT)") == 2);

        String handleBody = after(in, "private static boolean handleWebControl");
        int pageBranch = indexOr(handleBody, "if (pageClick)");
        int editor = indexOr(handleBody, "WidgetEditorScreen.create(");
        check("左键那一支转发给页面（handleWebPress）+ 吃掉事件",
                pageBranch > 0 && handleBody.contains("handleWebPress(mc,")
                        && handleBody.contains("event.setCanceled(true)")
                        && handleBody.contains("event.setSwingHand(false)"));
        check("左键那一支 = 先按钮栏、再转发给页面（编辑器只在「没画面 + 右键」时打开）",
                editor > 0 && handleBody.contains("mc.setScreen(WidgetEditorScreen.create(pick.plane(), web))")
                        && handleBody.contains("handleWebPress(mc, top.hmjmfabc.projector.client.web.WebInput.MC_BUTTON_LEFT)"));
        check("右键那一支 = 只提示「请用左键」（没画面时才开编辑器，不许把玩家卡死）",
                handleBody.contains("WebControls.notifyLeftNeeded()")
                        && handleBody.contains("WebControls.notifyNoPage()"));
        check("左键被整个吃掉：事件取消 + 不挥手（免得顺手挖掉平面背后的方块）",
                handleBody.contains("event.setCanceled(true)")
                        && handleBody.contains("event.setSwingHand(false)"));
        check("点击现场诊断打在 handleWebControl 入口（准心落在网页控件上就一定有一行）",
                handleBody.contains("WebInput.logScene("));
        check("WebInput 里写清了最终口径（左键＝点击操作、右键＝只提示），避免以后有人对调回去",
                web.contains("\u5de6\u952e\uff1d\u70b9\u51fb\u64cd\u4f5c")
                        && web.contains("\u53f3\u952e\uff1d\u4e0d\u64cd\u4f5c"));
    }

    // --------------------------- ⑥b 转发链：假会话证明「左键 + 会话 + 权限 ⇒ 真的调 mousePress」
    private static final class FakePointer implements WebPointerSink {
        final java.util.List<String> log = new java.util.ArrayList<>();
        boolean alive = true;

        @Override
        public boolean valid() {
            return alive;
        }

        @Override
        public void mouseMove(int x, int y) {
            log.add("move " + x + "," + y);
        }

        @Override
        public void mousePress(int x, int y, int button) {
            log.add("press " + x + "," + y + " b" + button);
        }

        @Override
        public void mouseRelease(int x, int y, int button) {
            log.add("release " + x + "," + y + " b" + button);
        }

        @Override
        public void mouseWheel(int x, int y, double amount, int modifiers) {
            log.add("wheel " + amount);
        }
    }

    private static void pageClickChain() {
        WebWidget open = widget(96, 54);
        open.publicControls = true;
        WebWidget closed = widget(96, 54);
        closed.publicControls = false;

        // 左键 + 有会话 + 有权限 ⇒ 真的转发。视口 768x432、控件 96x54：
        // 局部 (40,20) ⇒ 页面 (40/96*768, (1-20/54)*432) = (320, 272)
        FakePointer sink = new FakePointer();
        check("左键 + 有会话 + 有权限 ⇒ 真的调用 mousePress",
                WebInput.click((Plane) null, open, 40, 20, WebInput.MC_BUTTON_LEFT,
                        sink, 768, 432, 1000));
        check("先 mouseMove 再 mousePress（顺序不能反）",
                sink.log.size() >= 2 && sink.log.get(0).startsWith("move ")
                        && sink.log.get(1).startsWith("press "));
        check("★ 发给页面的按钮永远是左键（浏览器里只有左键会激活链接/按钮）",
                sink.log.get(1).endsWith("b0"));
        // ★【rc-139】朝向定稿「左上对左上」：40/96*768=320，(1-20/54)*432=272
        check("页面坐标（rc-139 口径）：(40,20) ⇒ 320,272",
                sink.log.get(0).contains("320,272") && sink.log.get(1).contains("320,272"));

        // 右键 ⇒ 一步都不转发（它要走「打开编辑器」那条路）
        sink.log.clear();
        check("右键 ⇒ 一步都不转发（只提示「请用左键」）",
                !WebInput.click((Plane) null, open, 40, 20, WebInput.MC_BUTTON_RIGHT,
                        sink, 768, 432, 1000) && sink.log.isEmpty());

        // 权限不足 ⇒ 不转发（但吃掉这次点击，返回 true）
        sink.log.clear();
        check("权限不足 ⇒ 不转发、但返回 true（免得左键顺手去挖方块）",
                WebInput.click((Plane) null, closed, 40, 20, WebInput.MC_BUTTON_LEFT,
                        sink, 768, 432, 1000) && sink.log.isEmpty());

        // 会话已死 ⇒ 返回 false（不假装成功）
        FakePointer dead = new FakePointer();
        dead.alive = false;
        check("会话已失效 ⇒ 返回 false 且什么都不发",
                !WebInput.click((Plane) null, open, 40, 20, WebInput.MC_BUTTON_LEFT,
                        dead, 768, 432, 1000) && sink.log.isEmpty());
    }

    // ------------------------------------------------------------------ ⑦ 键盘转发

    private static void keyboard() {
        check("要转发的键：退格 / 回车 / Tab / 四个方向键 / Delete",
                WebInput.forwardedKey(WebInput.KEY_BACKSPACE)
                        && WebInput.forwardedKey(WebInput.KEY_ENTER)
                        && WebInput.forwardedKey(WebInput.KEY_TAB)
                        && WebInput.forwardedKey(WebInput.KEY_DELETE)
                        && WebInput.forwardedKey(WebInput.KEY_LEFT)
                        && WebInput.forwardedKey(WebInput.KEY_RIGHT)
                        && WebInput.forwardedKey(WebInput.KEY_UP)
                        && WebInput.forwardedKey(WebInput.KEY_DOWN));
        check("键码就是 GLFW 的那几个（桥用 KeyMap 按它翻成 DOM key/code）",
                WebInput.KEY_ENTER == 257 && WebInput.KEY_TAB == 258
                        && WebInput.KEY_BACKSPACE == 259 && WebInput.KEY_DELETE == 261
                        && WebInput.KEY_RIGHT == 262 && WebInput.KEY_LEFT == 263
                        && WebInput.KEY_DOWN == 264 && WebInput.KEY_UP == 265);
        check("字母/数字/空格/功能键**一律不转发**（不许影响走路、开背包、打字聊天）",
                !WebInput.forwardedKey(65)      // A（走路）
                        && !WebInput.forwardedKey(69)      // E（背包）
                        && !WebInput.forwardedKey(84)      // T（聊天）
                        && !WebInput.forwardedKey(85)      // U（本模组圈选）
                        && !WebInput.forwardedKey(32)      // 空格（跳跃）
                        && !WebInput.forwardedKey(256)     // Esc
                        && !WebInput.forwardedKey(290));   // F1
        check("「输入给网页」界面用 I 打开，而且 I **不是**转发键（Enter 留给页面提交）",
                WebInput.KEY_INPUT_TEXT == 73 && !WebInput.forwardedKey(WebInput.KEY_INPUT_TEXT)
                        && WebInput.KEY_INPUT_TEXT != WebInput.KEY_ENTER);

        WebWidget open = widget(96, 54);
        open.publicControls = true;
        WebWidget closed = widget(96, 54);
        closed.publicControls = false;
        check("权限被拒 ⇒ key 返回 true（算「归页面」，但不转发）",
                WebInput.key((Plane) null, closed, WebInput.KEY_BACKSPACE, 0, 0, true));
        check("有权限但没会话 ⇒ key 返回 false（安静放行，这个键照旧归原版）",
                !WebInput.key((Plane) null, open, WebInput.KEY_BACKSPACE, 0, 0, true)
                        && !WebInput.key((Plane) null, open, WebInput.KEY_BACKSPACE, 0, 0, false));
        check("不是要转发的键 ⇒ 连权限都不问（返回 false）",
                !WebInput.key((Plane) null, closed, 65, 0, 0, true));
    }

    // ------------------------------------------------------------------ ⑦b 文本输入：转发与降级（假 Session 计数）

    /** 假会话：只数「被要求转发几次」，并在需要时扮演「环境不支持」。 */
    private static final class FakeSink implements WebTextSink {
        private final boolean supported;
        final AtomicInteger inserts = new AtomicInteger();
        final List<String> seen = new ArrayList<>();

        FakeSink(boolean supported) {
            this.supported = supported;
        }

        @Override
        public boolean supportsText() {
            return supported;
        }

        @Override
        public String textMethod() {
            return supported ? "fake" : "\u65e0";
        }

        @Override
        public boolean insert(String text) {
            inserts.incrementAndGet();
            seen.add(text);
            return true;
        }
    }

    /** 会抛的假会话：桥那边抛 Throwable（安卓上是 Error）也不许把游戏带崩。 */
    private static final class ThrowingSink implements WebTextSink {
        final AtomicInteger inserts = new AtomicInteger();

        @Override
        public boolean supportsText() {
            return true;
        }

        @Override
        public String textMethod() {
            return "throwing";
        }

        @Override
        public boolean insert(String text) {
            inserts.incrementAndGet();
            throw new UnsatisfiedLinkError("\u5047\u88c5\u539f\u751f\u5e93\u7f3a\u5931");
        }
    }

    private static void textInput() {
        WebWidget open = widget(96, 54);
        open.publicControls = true;
        WebWidget closed = widget(96, 54);
        closed.publicControls = false;

        FakeSink ok = new FakeSink(true);
        check("支持的会话 ⇒ 整段文本原样转发一次（假 Session 计数 = 1）",
                WebInput.insertText((Plane) null, open, "\u4f60\u597d Minecraft \ud83d\ude00", ok)
                        && ok.inserts.get() == 1
                        && ok.seen.get(0).equals("\u4f60\u597d Minecraft \ud83d\ude00"));

        FakeSink no = new FakeSink(false);
        check("**降级分支**：环境不支持 ⇒ 不转发（计数 = 0）且返回 false（界面据此写明原因）",
                !WebInput.insertText((Plane) null, open, "\u4f60\u597d", no) && no.inserts.get() == 0);

        FakeSink denied = new FakeSink(true);
        check("权限被拒 ⇒ 不转发（计数 = 0）",
                !WebInput.insertText((Plane) null, closed, "\u4f60\u597d", denied)
                        && denied.inserts.get() == 0);

        FakeSink empty = new FakeSink(true);
        check("空串不算一次输入（不转发、不报成功）",
                !WebInput.insertText((Plane) null, open, "", empty)
                        && !WebInput.insertText((Plane) null, open, null, empty)
                        && empty.inserts.get() == 0);

        FakeSink longText = new FakeSink(true);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < WebInput.MAX_TEXT + 40; i++) {
            sb.append('a');
        }
        WebInput.insertText((Plane) null, open, sb.toString(), longText);
        check("超长文本被截到 " + WebInput.MAX_TEXT + " 个字符（不把一兆字节塞进桥）",
                longText.inserts.get() == 1 && longText.seen.get(0).length() == WebInput.MAX_TEXT);

        ThrowingSink boom = new ThrowingSink();
        check("桥抛 Throwable（安卓上是 Error）⇒ 吞掉、返回 false、不把游戏带崩",
                !WebInput.insertText((Plane) null, open, "\u4f60\u597d", boom)
                        && boom.inserts.get() == 1);

        check("sink 为 null / 控件为 null 一律安全返回 false",
                !WebInput.insertText((Plane) null, open, "\u4f60\u597d", null)
                        && !WebInput.insertText((Plane) null, null, "\u4f60\u597d", new FakeSink(true)));

        // 真实入口（没有会话）时的判定结果：界面拿它当降级依据
        check("没有画面时状态 = NO_SESSION（不是 OK，也不是「成功」）",
                WebInput.textInputStatus(open) == WebInput.TextInputStatus.NO_SESSION
                        && WebInput.textInputStatus(null) == WebInput.TextInputStatus.NO_SESSION);
        check("三种状态齐备（OK / NO_SESSION / UNSUPPORTED —— 降级分支就在 UNSUPPORTED）",
                WebInput.TextInputStatus.values().length == 3
                        && WebInput.TextInputStatus.valueOf("UNSUPPORTED") != null);

        String web = readFile(
                "src/main/java/top/hmjmfabc/projector/client/web/WebInput.java");
        String screen = readFile(
                "src/main/java/top/hmjmfabc/projector/client/gui/WebInputScreen.java");
        String zh = readFile("src/main/resources/assets/projector/lang/zh_cn.json");
        check("降级文案一字不差：当前环境不支持向网页输入文本（语言文件里）",
                zh.contains("\u5f53\u524d\u73af\u5883\u4e0d\u652f\u6301\u5411\u7f51\u9875\u8f93\u5165\u6587\u672c"));
        check("界面把 UNSUPPORTED 翻成那句明文提示（关闭键始终可用，不会被锁在界面里）",
                screen.contains("case UNSUPPORTED")
                        && screen.contains("projector.msg.web_input_unsupported")
                        && screen.contains("closeButton.active = true"));
        check("降级分支一定留痕（不静默失败）",
                web.contains("text-unsupported"));
    }

    // ------------------------------------------------------------------ ⑧ 源码级：尺寸来源 / 判据唯一 / 不广播 / 桥侧入口

    private static void sourceLevel() {
        String web = readFile(
                "src/main/java/top/hmjmfabc/projector/client/web/WebInput.java");
        String sessions = readFile(
                "src/main/java/top/hmjmfabc/projector/client/web/WebSessions.java");
        String controls = readFile(
                "src/main/java/top/hmjmfabc/projector/client/web/WebControls.java");
        String bridge = readFile(
                "src/main/java/top/hmjmfabc/projector/client/web/McefBridge.java");
        String sink = readFile(
                "src/main/java/top/hmjmfabc/projector/client/web/WebTextSink.java");

        check("换算用的尺寸来自「已经下发的 Entry 尺寸」（WebSessions.viewportFor）",
                web.contains("WebSessions.viewportFor("));
        check("绝不在输入换算里重算一个可能还没生效的尺寸（不调 pixelSize）",
                !web.contains("pixelSize("));
        check("viewportFor 读的就是 Entry 的 width/height（resize 去抖之后的那一份）",
                sessions.contains("static int[] viewportFor(UUID widgetId)")
                        && sessions.contains("e.width <= 0 || e.height <= 0")
                        && sessions.contains("return new int[]{e.width, e.height};"));
        check("输入交互会给会话续命（别在看/点时被「离开视野」判据放掉）",
                sessions.contains("static void markUsed(UUID widgetId)") && web.contains("markUsed("));

        check("权限判据没有新写第二份：WebInput 里不出现 canEditContent",
                !web.contains("canEditContent"));
        check("WebInput 调的就是按钮栏那份判据（WebControls.usable(plane, w)）",
                web.contains("WebControls.usable(plane, w)"));
        check("判据本体只在一处取权限（ClientPermissions.canEditContent 在 WebControls 里只出现一次）",
                count(controls, "ClientPermissions.canEditContent") == 1);

        check("页面点击是纯客户端行为：不广播、不落库（没有 PacketDistributor / sendToServer）",
                !web.contains("PacketDistributor") && !web.contains("sendToServer"));
        check("转发走 MCEF 的公开方法名（mouseMove / mousePress / mouseRelease / mouseWheel）",
                web.contains(".mouseMove(") && web.contains(".mousePress(")
                        && web.contains(".mouseRelease(") && web.contains(".mouseWheel("));
        check("按钮编码的结论写进了注释（证据指向 MCEF 自己换 1/2 的那段）",
                web.contains("middle and right are swapped in MC") && web.contains("MCEFBrowser.java"));
        check("朝向的「唯一真源」写进了 WebWidget.texU0() 的注释里（并说明为什么不用常量）",
                readFile("src/main/java/top/hmjmfabc/projector/common/widget/WebWidget.java")
                        .contains("\u552f\u4e00\u771f\u6e90")
                        && readFile("src/main/java/top/hmjmfabc/projector/common/widget/WebWidget.java")
                        .contains("\u5185\u8054")
                        && web.contains("WebWidget.texU0()"));
        // ★【rc-139】渲染端不许自己写字面量 UV：它必须用同一组常量，
        //   否则「画面朝向」与「点击映射」又有两份判定（131/133 的翻车模式）。
        String webRenderer = readFile(
                "src/main/java/top/hmjmfabc/projector/client/render/WebWidgetRenderer.java");
        // ⚠ 不能要求整文件都没有「0f,0f,1f,1f」——画白块（solidRect）本来就用它当 UV。
        //   这里只钉住「网页那张贴图」的 UV 来自常量、且不再是那个 180° 的组合。
        check("渲染端网页贴图用的是同一组方法（不再写死 1f,1f,0f,0f）",
                webRenderer.contains("WebWidget.texU0(), WebWidget.texV0(), WebWidget.texU1(), WebWidget.texV1()")
                        && !webRenderer.contains("1f, 1f, 0f, 0f"));
        check("常量本身写明了「红点验不出朝向、要靠字的朝向判断」",
                readFile("src/main/java/top/hmjmfabc/projector/common/widget/WebWidget.java")
                        .contains("\u9a8c\u4e0d\u51fa"));
        check("注释写明了 texW/texH 必须是 Entry 里「已下发」的尺寸",
                web.contains("Entry.width/height") || web.contains("RESIZE_SETTLE_MS"));
        check("转发都走 catch (Throwable)（安卓上失败是 Error：UnsatisfiedLinkError 之类）",
                web.contains("catch (Throwable"));

        // 可诊断日志：真机上一眼看出坐标算得对不对
        String coreClick = after(web, "send(w.id, \"点击\"");
        check("发给页面的按钮只有一处：pageButton = PAGE_BUTTON（常量 0 = 左键），sink 拿不到别的键",
                count(web, "int pageButton = PAGE_BUTTON;") == 1
                        && web.contains("public static final int PAGE_BUTTON = 0;")
                        && coreClick.contains("sink.mousePress(p[0], p[1], pageButton)")
                        && coreClick.contains("QUEUE.press(w.id, p[0], p[1], pageButton, nowMs)")
                        && !web.contains("mousePress(p[0], p[1], mcButton)"));
        check("每次转发点击打一行「局部 → 视口（世界按钮=… → 页面按钮=…）」的诊断日志（限流）",
                web.contains("转发点击\uff1a\u5c40\u90e8=(")
                        && web.contains("\u89c6\u53e3=(")
                        && web.contains("\u4e16\u754c\u6309\u94ae={}")
                        && web.contains("mcButton == MC_BUTTON_RIGHT ? \"\u53f3\u952e\"")
                        && web.contains("infoThrottled(\"click\""));

        // 键盘 / 文本：桥侧入口与降级
        check("McefBridge 写明「桥侧方法名待确认」，并按顺序试三个候选",
                bridge.contains("\u6865\u4fa7\u65b9\u6cd5\u540d\u5f85\u786e\u8ba4")
                        && bridge.contains("\"insertText\", String.class")
                        && bridge.contains("\"sendTextInput\", String.class")
                        && bridge.contains("\"sendKeyTyped\", char.class, int.class"));
        check("文本入口的兜底那条有确凿依据（MCEF 公开 API + 安卓桥把它翻成 CDP 的 char 事件）",
                bridge.contains("sendKeyTyped") && bridge.contains("TYPE_CHAR")
                        && bridge.contains("CefBrowser_N_N_SendKeyEvent"));
        check("Session 实现 WebTextSink（判据与转发分离 ⇒ 可以用假会话无头验）",
                bridge.contains("implements WebTextSink") && sink.contains("interface WebTextSink"));
        check("键盘转发走 sendKeyPress / sendKeyRelease（GLFW 码，桥用 KeyMap 翻）",
                bridge.contains("sendKeyPress") && bridge.contains("sendKeyRelease")
                        && bridge.contains("client/KeyMap"));
        check("会话内的每一次转发都是 catch (Throwable)（安卓上失败是 Error）",
                bridge.contains("catch (Throwable"));
        check("McefBridge 的会话注释里留着「进来的是 GLFW 编码、掩码 MCEF 自己算」这条结论",
                bridge.contains("GLFW") && bridge.contains("sendMousePress"));
    }

    // ------------------------------------------------------------------ ⑨ 接线

    private static void wiring() {
        String in = readFile(
                "src/main/java/top/hmjmfabc/projector/client/ClientInputHandler.java");
        String renderer = readFile(
                "src/main/java/top/hmjmfabc/projector/client/render/WebWidgetRenderer.java");
        String overlay = readFile(
                "src/main/java/top/hmjmfabc/projector/client/web/WebOverlayRenderer.java");
        String editor = readFile(
                "src/main/java/top/hmjmfabc/projector/client/gui/WidgetEditorScreen.java");
        String screen = readFile(
                "src/main/java/top/hmjmfabc/projector/client/gui/WebInputScreen.java");
        String controls = readFile(
                "src/main/java/top/hmjmfabc/projector/client/web/WebControls.java");
        String zh = readFile("src/main/resources/assets/projector/lang/zh_cn.json");
        String en = readFile("src/main/resources/assets/projector/lang/en_us.json");

        check("悬停挂在每帧钩子（RenderFrameEvent.Pre）上",
                in.contains("RenderFrameEvent.Pre") && in.contains("WebInput.hover("));
        check("界面开着时不转发（钩子有 mc.screen != null 保护）",
                in.contains("if (mc.player == null || mc.level == null || mc.screen != null)"));
        check("滚轮挂在 InputEvent.MouseScrollingEvent 上，并且**吃掉**这次滚轮"
                        + "（否则滚页面会顺手换快捷栏）",
                in.contains("MouseScrollingEvent") && in.contains("event.setCanceled(true)")
                        && in.contains("WebInput.scroll("));
        check("松开在每 tick 的钩子里结算，而且排在所有提前 return 之前",
                in.contains("WebInput.pump()")
                        && in.indexOf("WebInput.pump()") < in.indexOf("ChessAiDriver.pump()"));
        // 【snapshot-127】左键判据的第二条证据 + 「MC 没认领」时的补发
        check("鼠标按下挂 InputEvent.MouseButton.Pre，把「哪个键」记成证据",
                in.contains("InputEvent.MouseButton.Pre") && in.contains("WebInput.noteMouseButton("));
        check("鼠标钩子也守着「界面开着时不碰」（在那里取消事件会把界面点击整个吃掉）",
                after(in, "public void onMouseButton").contains("mc.screen != null"));
        check("补发挂在每 tick 的钩子里，而且在 pump 之后、界面判断之前",
                in.indexOf("WebInput.pump()") < in.indexOf("handleWebPageFallback(mc)")
                        && after(in, "public void onClientTick").contains("handleWebPageFallback(mc)"));
        check("补发前先问「这次按下有没有人认领」（同一次按下最多转发一次）",
                in.contains("WebInput.pendingMousePress()") && in.contains("WebInput.claimMousePress()"));
        check("交互事件那条路也要认领（否则补发会和它重复转发）",
                between(in, "private static boolean handleWebControl",
                        "private static boolean handleWebPress")
                        .contains("WebInput.claimMousePress()"));
        check("网页输入一律走拾取路径（不自己另做射线）",
                in.contains("private static double[] webLocal(")
                        && in.contains("intersectPlane(plane, eye, dir,"));

        // 键盘钩子
        check("键盘挂在 InputEvent.Key 上（世界里没有 KeyMapping 抢占键位）",
                in.contains("InputEvent.Key") && in.contains("public void onKeyInput"));
        check("I 键打开「输入给网页」界面，且只在没开界面时（mc.screen == null）",
                in.contains("WebInput.KEY_INPUT_TEXT") && in.contains("openWebInputScreen(mc)")
                        && in.contains("new top.hmjmfabc.projector.client.gui.WebInputScreen("));
        check("其余键先过 forwardedKey 闸门（不转发就一行都不做，不影响走路/背包/聊天）",
                in.contains("WebInput.forwardedKey(key)") && in.contains("WebInput.key("));
        check("打开输入界面前先过权限判据（与按钮栏同一份 WebControls.usable）",
                after(in, "private static void openWebInputScreen").contains("WebInput.canUse("));

        // 输入界面
        check("输入界面是**单行 EditBox**（输入法/中文天然可用），标题写明「输入给网页」",
                screen.contains("EditBox") && screen.contains("projector.gui.web_input_title")
                        && zh.contains("\u8f93\u5165\u7ed9\u7f51\u9875"));
        check("**逐字实时**送进页面（charTyped → WebInput.typeChar），不再要求「打完再发送」",
                screen.contains("public boolean charTyped(char c, int modifiers)")
                        && screen.contains("WebInput.typeChar(plane, widget, c)"));
        check("回车/退格转给页面、Esc 关界面（都排在 super.keyPressed 之前）",
                screen.contains("WebInput.key(plane, widget, 257")
                        && screen.contains("WebInput.key(plane, widget, keyCode")
                        && screen.indexOf("if (keyCode == 256)") > 0
                        // 注意用 lastIndexOf：javadoc 里也提到了 super.keyPressed（第一次出现是注释）
                        && screen.indexOf("if (keyCode == 256)") < screen.lastIndexOf("super.keyPressed"));
        check("关界面时留一行「实时送入 N 个字符」的诊断（N=0 就说明输入法没喂到 MC）",
                screen.contains("实时送入 {} 个字符"));
        check("失败/不支持在界面里写明（不静默失败）：判据用 WebInput.textInputStatus 那一份",
                screen.contains("WebInput.textInputStatus(") && screen.contains("noticeBad"));
        check("两份语言都有输入界面与提示文案",
                zh.contains("projector.msg.web_input_unsupported")
                        && en.contains("projector.msg.web_input_unsupported")
                        && zh.contains("projector.msg.web_input_ok")
                        && en.contains("projector.msg.web_input_ok"));
        check("「当前环境不支持向网页输入文本」两份语言都写全了",
                line(zh, "web_input_unsupported").contains("\u5f53\u524d\u73af\u5883\u4e0d\u652f\u6301\u5411\u7f51\u9875\u8f93\u5165\u6587\u672c")
                        && line(en, "web_input_unsupported").contains("cannot send text"));

        check("编辑器里两行说明都在（左键操作页面 / 右键打开编辑器；按 I 打字）",
                editor.contains("projector.msg.web_world_click")
                        && editor.contains("projector.msg.web_world_input"));
        check("说明文案两份语言都在",
                zh.contains("projector.msg.web_world_click") && en.contains("projector.msg.web_world_click")
                        && zh.contains("projector.msg.web_world_input")
                        && en.contains("projector.msg.web_world_input"));
        check("这两行说明不出现后端名字（MCEF / CDP / Chromium）",
                !line(zh, "web_world_click").contains("MCEF")
                        && !line(zh, "web_world_click").contains("CDP")
                        && !line(zh, "web_world_click").contains("Chromium")
                        && !line(zh, "web_world_input").contains("MCEF")
                        && !line(zh, "web_world_input").contains("CDP")
                        && !line(zh, "web_world_input").contains("Chromium")
                        && !line(en, "web_world_click").contains("MCEF")
                        && !line(en, "web_world_click").contains("Chromium")
                        && !line(en, "web_world_input").contains("MCEF")
                        && !line(en, "web_world_input").contains("Chromium"));
        // ⚠ 这条以前只查「四个词都在」，换个方向照样过 —— 属于无效断言（本项目的老教训）。
        //   现在钉住**顺序与配对**：左键…操作页面、右键…打开这个编辑器。
        check("说明文案里的左右键口径是**新的**（左键＝操作页面、右键＝打开编辑器）",
                line(zh, "web_world_click")
                        .contains("\u5de6\u952e\u70b9\u7f51\u9875\u753b\u9762\uff1d\u64cd\u4f5c\u9875\u9762")
                        && line(zh, "web_world_click")
                        .contains("\u53f3\u952e\uff1d\u6253\u5f00\u8fd9\u4e2a\u7f16\u8f91\u5668")
                        && line(en, "web_world_click").contains("right-click opens this editor"));
        check("两行说明的标题措辞也跟上了新口径",
                line(zh, "web_world_click").contains("\u4e16\u754c\u91cc\u5de6\u952e\u70b9\u7f51\u9875\u753b\u9762"));

        check("退出世界时连网页输入的运行期状态一起清（WebControls.clear 里调 WebInput.clear）",
                controls.contains("WebInput.clear();"));

        check("页面光标由 WebWidgetRenderer 每帧画，且在按钮栏那个 if **之后**（各自判显隐）",
                indexOr(renderer, "WebControls.visible(w)") > 0
                        && indexOr(renderer, "WebOverlayRenderer.drawCursor(")
                        > indexOr(renderer, "WebControls.visible(w)"));
        check("光标的视觉与项目设计语言一致：白色细圆环 + 环宽 max(0.22, r*0.09) + 分层 0.015",
                overlay.contains("public static void drawCursor")
                        && overlay.contains("Math.max(0.22, r * 0.09)")
                        && overlay.contains("private static final double LAYER = 0.015;"));
        check("光标半径取控件高度的 2%、点中时收缩（「点到了」的反馈）",
                overlay.contains("WebInput.cursorRadius(") && overlay.contains("c.scale()")
                        && WebInput.CURSOR_RADIUS_RATIO == 0.02
                        && WebInput.CLICK_SHRINK < 1.0
                        && WebInput.cursorRadiusScale(1000, 1100) == WebInput.CLICK_SHRINK
                        && WebInput.cursorRadiusScale(1200, 1100) == 1.0);
    }

    // ------------------------------------------------------------------ ⑩ 光标状态的显隐窗口 / 半径

    private static void cursor() {
        WebWidget w = widget(96, 54);
        long now = System.currentTimeMillis();
        check("没有交互过时不画光标", WebInput.cursor(w, now) == null);
        check("半径比例是 **2%**（用户 2026-10-02 口径：上一版 4% 太大）",
                WebInput.CURSOR_RADIUS_RATIO == 0.02);
        check("★ 四个按钮：**图形黑色、圈白色**（白底网页上白图形看不见）",
                WebOverlayRenderer_src().contains("0xFF000000")
                        && WebOverlayRenderer_src().contains("glyph(collector, glyphCtx, f, i, cx, cy, size, glyphColor)"));
        // ★【rc-139】提示的朝向**跟着网页走**：网页画面翻正之后，这行字也回到正常朝向。
        //   两种东西必须同源 —— 否则玩家会看到「网页是正的、提示是倒的」。
        check("★ 操作提示画在**控件外面**（不盖网页内容）且朝向与网页一致（w.rot，不再 +180）",
                WebOverlayRenderer_src().contains("w.y - pad - lineH")
                        && WebOverlayRenderer_src().contains("w.rot, w.alpha, ctx.depth()")
                        && !WebOverlayRenderer_src().contains("w.rot + 180.0"));

        check("光标半径 = 控件高度 × 2%（96x54 → 1.08）",
                Math.abs(WebInput.cursorRadius(w) - 1.08) < 1.0e-9);
        check("下限从 0.7 降到 **0.45**",
                WebInput.CURSOR_RADIUS_MIN == 0.45
                        && Math.abs(WebInput.cursorRadius(widget(8, 4)) - 0.45) < 1.0e-9);
        check("极扁的控件也留一个看得见的半径（= 下限 0.45，不再大到盖住内容）",
                WebInput.cursorRadius(widget(8, 4)) >= 0.45
                        && WebInput.cursorRadius(widget(8, 4)) < 0.7);
        check("大控件仍有上限（不会画成一个盖住整页的巨圈）",
                Math.abs(WebInput.cursorRadius(widget(200, 400)) - WebInput.CURSOR_RADIUS_MAX) < 1.0e-9
                        && WebInput.CURSOR_RADIUS_MAX == 4.0);
        check("半径随高度线性（60 高 → 1.2；下限之上、上限之下都按比例）",
                Math.abs(WebInput.cursorRadius(widget(96, 60)) - 1.2) < 1.0e-9
                        && Math.abs(WebInput.cursorRadius(widget(96, 100)) - 2.0) < 1.0e-9);
        check("诊断接口可用（不抛异常）", WebInput.cursorCount() >= 0);
    }

    // ------------------------------------------------------------------ 工具

    private static WebWidget widget(double width, double height) {
        WebWidget w = new WebWidget();
        w.w = width;
        w.h = height;
        return w;
    }

    /** 子串第一次出现的位置；没有返回 -1。 */
    private static int indexOr(String haystack, String needle) {
        return haystack == null ? -1 : haystack.indexOf(needle);
    }

    /** marker 之后的那一段（用来把断言限制在某个方法体里）。 */
    private static String after(String haystack, String marker) {
        int i = indexOr(haystack, marker);
        return i < 0 ? "" : haystack.substring(i);
    }

    /** a 与 b 之间那一段（把断言严格限制在一个方法体里）。 */
    private static String between(String haystack, String a, String b) {
        int i = indexOr(haystack, a);
        if (i < 0) {
            return "";
        }
        int j = indexOr(haystack.substring(i), b);
        return j < 0 ? haystack.substring(i) : haystack.substring(i, i + j);
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        int i = 0;
        while (haystack != null && (i = haystack.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    /** 语言文件里包含某个 key 的那一行。 */
    private static String line(String json, String key) {
        for (String s : json.split("\n")) {
            if (s.contains(key)) {
                return s;
            }
        }
        return "";
    }

    /** 浮层渲染器源码（下面几条 UI 断言要读它）。 */
    private static String WebOverlayRenderer_src() {
        return readFile("src/main/java/top/hmjmfabc/projector/client/web/WebOverlayRenderer.java");
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

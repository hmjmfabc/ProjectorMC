package top.hmjmfabc.projector.client.web;

import net.minecraft.client.Minecraft;
import top.hmjmfabc.projector.Projector;
import top.hmjmfabc.projector.client.PlaneCache;
import top.hmjmfabc.projector.common.Plane;
import top.hmjmfabc.projector.common.widget.WebWidget;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 网页控件的**输入转发**（27.2）：准心对着网页控件时，把鼠标移动 / 右键 / 滚轮 /
 * 键盘交给那一个浏览器会话（{@link WebSessions}），于是网页里的链接、按钮、滚动条、
 * 输入框都能用。
 *
 * <p>画面早就能显示了，缺的一直是「能用鼠标操作它」——这个类就是补这一段。
 * 入口：{@link #hover} / {@link #click} / {@link #scroll} / {@link #key} /
 * {@link #insertText}。</p>
 *
 * <h2>① 坐标换算：送进桥的是**浏览器视口像素**（不是 CSS 像素，别「顺手」改）</h2>
 * <p>【rc-139 起】换算<b>不再手写「翻哪个轴」</b>，而是由渲染用的那组 UV 常量
 * （{@link WebWidget#texU0()} 等四个方法）算出来：
 * 画布角约定是「左下→(u0,v1)、左上→(u0,v0)」，于是</p>
 * <pre>
 *   u = texU0() + (texU1() - texU0()) * (localX / w.w)
 *   v = texV1() + (texV0() - texV1()) * (localY / w.h)
 * </pre>
 * <p>当前常量是 {@code (0,0,1,1)}，代入即：</p>
 * <pre>
 *   pageX = localX / w.w * texW
 *   pageY = (1 - localY / w.h) * texH     // 控件局部 (0,0) 在左下、局部 y 向上；
 *                                         // 页面 (0,0) 在左上、页面 y 向下 ⇒ 必须翻一次
 * </pre>
 * <p>用具体数字钉住（{@code tmp/v35/T35.java} 里就是这几条断言，别改公式不改测试）：</p>
 * <ul>
 *   <li>控件 {@code 96x54}、视口 {@code 768x432}：局部 (48,27) → 页面 (384,216)（中点）；</li>
 *   <li>局部 (0,54)（<b>左上</b>）→ 页面 <b>(0,0)</b> —— 页面的左上角（一眼能读的那种对法）；</li>
 *   <li>局部 (0,0)（左下）→ 页面 <b>(0,432)</b> —— 页面的左下角；</li>
 *   <li>局部 (96,54)（右上）→ 页面 <b>(768,0)</b> —— 页面的右上角。</li>
 * </ul>
 *
 * <p>⚠⚠ <b>这里给的 {@code pageX/pageY} 是「浏览器视口像素」= 抓帧/浏览器的像素尺寸</b>
 * （例子里的 768x432，dsf=0.6），<b>不是</b>页面排版的 <b>CSS 像素</b>（那一份是 1280x720）。
 * 两者差一个缩放比，<b>换算由桥去做</b>：桥按「排版视口 / 抓帧视口」的比例把这里给的点
 * 折成 CDP 的 CSS 像素再发（真机实测「点链接没反应」的根因就是这个比例，
 * 桥侧已修）。</p>
 * <p>⇒ <b>这一层绝不许改成 CSS 像素</b>：那会让桥再乘一次比例 ⇒ 二次换算 ⇒ 越靠边越偏，
 * 表现是「点了没反应或点到别处」。要动这条口径必须**先动桥**（Source3）——
 * 换算只有一份，不许两边各写一份（本项目的复发病：同一件事两份判定，总有一份忘改）。
 * {@code T35} 用源码断言把这条钉住了（出现 {@code 1280} 之类的「CSS 视口尺寸」
 * 或去掉 {@code / w.w * texW} 的形状都会红）。</p>
 *
 * <p>⚠ <b>{@code texW/texH} 只能取「已经下发给浏览器的那份尺寸」</b>
 * （{@link WebSessions#viewportFor(UUID)}，即 {@code Entry.width/height}）：
 * MCEF 那边的 resize 有 500ms 去抖（{@code WebSessions.RESIZE_SETTLE_MS}），
 * 编辑器里刚拖过尺寸时「按控件现算出来的尺寸」和浏览器里真实的视口<b>还不是一回事</b>，
 * 用后者算出来的点会整体偏移。这里**绝不**按控件现算一个尺寸。</p>
 *
 * <h2>② 按钮编码：MCEF 要的是 MC/GLFW 的编码（0=左 1=右 2=中），不是 CEF 的 flag</h2>
 * <p>证据（隔壁 {@code Source3} 的源码，只作查资料、不 import、不链接）：</p>
 * <ul>
 *   <li>{@code Source3/mcef-1.21.1/common/src/main/java/com/cinemamod/mcef/MCEFBrowser.java}
 *       第 305~316 行 {@code sendMousePress(int mouseX, int mouseY, int button)} 里第一件事就是
 *       {@code if (button == 1) button = 2; else if (button == 2) button = 1;}
 *       （原注释：{@code "for some reason, middle and right are swapped in MC"}），
 *       之后才把 0/1/2 映射成 {@code CefMouseEvent.BUTTON1_MASK / BUTTON2_MASK / BUTTON3_MASK}；
 *       它自己的示例 {@code ExampleScreen.mouseClicked} 也是把 MC 的 {@code button} 原样传进去。
 *       {@code sendMouseRelease} 里是同一段交换 + 反掩码。</li>
 *   <li>安卓兼容层 {@code Source3/src/main/java/top/hmjmfabc/mcefdroid/bridge/McefDroidJniBridge.java}
 *       的 {@code CefBrowser_N_N_SendMouseEvent}（第 1651~1680 行）先按
 *       {@code CefMouseEvent.BUTTON1_MASK/BUTTON2_MASK/BUTTON3_MASK} 判「哪个键」，
 *       再翻成 CDP 的 {@code buttons} 位（left=1 / middle=2 / right=4）——
 *       也就是说 <b>掩码是 MCEF 自己算出来的</b>，我们这一层只负责给「哪个键」。</li>
 * </ul>
 * <p>⇒ 结论：<b>我们传 MC 的原始 button 值</b>（右键 = 1），掩码由 MCEF 自己算，
 * 不要在这里预先互换。</p>
 *
 * <h2>③ 左键 = 操作页面，右键 = 打开这个网页控件的编辑器（snapshot-127 真机定稿）</h2>
 * <p>这一版是<b>按 127 那局日志改回来的</b>，前两版都被真机否掉了：</p>
 * <ul>
 *   <li>第一版「左键转发页面 + 右键留给编辑器」；</li>
 *   <li>第二版（126）对调成「右键转发页面 + 左键进编辑器」——<b>日志证明这条路走不通</b>：
 *       53 次右键都转发到了 Chromium（{@code mousePressed right}），
 *       但<b>浏览器里右键 = 弹右键菜单</b>，页面不会因为右键而跳转 ⇒ 玩家看到的是
 *       「右键没反应」，同时还多出一个「左键错误地开了编辑页」。</li>
 *   <li><b>第三版（本版，定稿）：左键 = 操作网页（点链接/按钮、聚焦输入框），
 *       右键 = 打开编辑器</b> —— 右键开编辑器也正好与全模组惯例一致
 *       （其它控件都是右键打开编辑/设置界面）。</li>
 * </ul>
 * <p>所以 {@link #click} 的第一个真正的闸门判的是 {@code !isWebClick(false, false, mcButton)}
 * ⇒ 右键在这里**直接返回 false**（不转发、也不吃掉），由
 * {@code ClientInputHandler.handleWebControl} 接住并打开
 * {@code WidgetEditorScreen}（那条路确实存在，见 {@code T35.wiring()} 的源码断言）。</p>
 * <p>⚠ 网页显示不出来时（没装前置模组 / 还没出画面 ⇒ 没有会话）左键也会返回 false，
 * 于是原版行为照旧 —— 编辑器由右键进，任何情况下都进得去。</p>
 *
 * <h2>④ 键盘与文本（27.2 用户要求「在网页里用输入法打字」）</h2>
 * <p>键盘事件只能来自 MC 自己的输入链：世界里与页面相关的键只有两类 ——</p>
 * <ol>
 *   <li><b>打开「输入给网页」界面</b>：{@link #KEY_INPUT_TEXT}（<b>I</b>）。
 *       用 I 而不是 Enter，是因为 Enter 要留给页面本身（搜索框/聊天框按回车提交）。
 *       I 现在空着：⑥.3 已把「取消圈选」从 I 改成 Esc（{@code ProjectorKeys.KEY_CANCEL}）。</li>
 *   <li><b>转发给页面</b>：Backspace / Enter / Tab / 方向键 / Delete
 *       （{@link #forwardedKey(int)}，GLFW 编码；桥用 {@code client/KeyMap} 翻成 DOM key/code/VK）。</li>
 * </ol>
 * <p><b>只有「正在操作这个网页」时才动</b>（准心对着控件 + 有权用 + 有活会话），
 * 其余时候一个键都不拦：走路、开背包、打字聊天照旧。⚠ 顺带一个不会拦的键：
 * Tab 转发给页面的同时原版仍会开玩家列表 —— NeoForge 的 {@code InputEvent.Key}
 * **不可取消**（`javap` 查过：它没有 setCanceled），拦不住，如实记在这里。</p>
 *
 * <h2>⑤ 权限口径：点网页是「用一下」不是「改内容」</h2>
 * <p>与网页按钮栏（⟳ ← ⌂ →）<b>同一份判据</b>：{@link WebControls#usable(Plane, WebWidget)}
 * —— 作者开着 {@code publicControls}，或者我本来就能改这个平面的内容。
 * 判据只有那一份，这里不新写一份（本项目复发的坑：同一件事两份判定，总有一份忘改）。</p>
 * <p>点击<b>是纯客户端行为</b>：不广播、不落库、不新增服务端动作
 * （{@code ServerNetHandler} 一行都不用改）——网页画面在每个客户端各自的浏览器里。</p>
 *
 * <h2>⑥ 线程</h2>
 * <p>所有入口都<b>必须在渲染线程调用</b>（MCEF 的输入最终走到它自己在
 * {@code GameRenderer.render} 头部泵的 CEF 消息循环，见 {@link McefBridge} 的线程约束）。
 * 不在渲染线程时内部 {@code mc.execute(...)} 切回去（{@code Minecraft.execute} 在主线程上是
 * 同步跑的）。{@link #pump()} 由每 tick 的钩子调（界面开着也要调，否则松开永远发不出去）。</p>
 *
 * <h2>⑦ 右键判据只有一份 + 失败必须留痕（27.2-snapshot-126，真机「点了没反应」的对策）</h2>
 * <p>玩家报了「右键点网页画面 → 页面毫无反应，而桥那边只收到悬停」。当时所有失败分支都是
 * <b>静默 return</b>，查不出是哪一道闸门。现在：</p>
 * <ul>
 *   <li><b>判据只有一份</b>：{@link #isWebClick(boolean, int)} ——
 *       「MC 没认成 use」{@code &&}（「MC 认成 attack」{@code ||}「鼠标层按的是左键」）。
 *       调用方（{@code ClientInputHandler}）不许再自己写 {@code isAttack()} /
 *       {@code button == 0} 的判定；证据见 {@link #noteMouseButton} + {@link MouseButtonEvidence}。</li>
 *   <li><b>每一条早退都打一行带原因</b>：{@code [Projector][网页] 未转发：原因=…}
 *       （控件为空 / 不是左键 / 权限 / 没有会话 / 会话已失效 / 坐标越界-已钳制）。</li>
 *   <li><b>只有「真的没有会话 / 右键」才放行</b>给 {@code ClientInputHandler} 那条编辑器路径；
 *       权限不足给玩家明文提示（复用 {@link WebControls#notifyDenied()}）；坐标越界钳制后继续。</li>
 *   <li><b>点击现场</b>：{@link #logScene} 在准心落在网页控件上时打一行
 *       {@code 左键现场：isUseItem=… isAttack=… isPickBlock=… 局部=… 有会话=… 可用=…}。</li>
 * </ul>
 */
public final class WebInput {
    private WebInput() {
    }

    // ================================================================== 常量

    /**
     * 右键在 MC / GLFW 里的编码（= {@code Screen.mouseClicked} 的第三个参数）。
     *
     * <p><b>snapshot-127 起它不再被转发</b>：右键 = 打开这个网页控件的编辑器
     * （用户口径见类注释 ③）。常量留着是因为点击队列 / 测试与 MC 的编码同一套口径。</p>
     */
    public static final int MC_BUTTON_RIGHT = 1;

    /**
     * 左键编码（= 0）。<b>snapshot-127 起只有它会被转发</b>（用户口径：左键 = 操作网页，
     * 见类注释 ③）。MCEF 的 {@code sendMousePress} 会自己把 1/2 互换再映射成 CEF 的掩码，
     * 所以我们传 <b>MC 的原始值</b>。
     */
    public static final int MC_BUTTON_LEFT = 0;

    /**
     * <b>发给页面的按钮永远是它</b>（0 = 左键）。
     *
     * <p>理由：浏览器里只有左键会激活链接/按钮，右键只会弹右键菜单 —— 127/128 两版就栽在这里
     * （桥老老实实把 {@code mousePressed right} 发出去了，页面却不可能有任何反应）。
     * 有了这个常量，「哪个键发给页面」这件事全项目只有一处，不会再随世界口径漂。</p>
     */
    public static final int PAGE_BUTTON = 0;

    // ---- 鼠标键的「证据」（{@link #isWebClick} 的第二条来源）----

    /** 还没有任何鼠标键的证据（从没收到过鼠标按下事件 / 证据已过期）。 */
    public static final int NO_MOUSE_BUTTON = Integer.MIN_VALUE;

    /** 鼠标事件的动作码：按下（= GLFW {@code GLFW_PRESS} / {@code InputConstants.PRESS}）。 */
    public static final int ACTION_PRESS = 1;

    /** 鼠标事件的动作码：松开（= GLFW {@code GLFW_RELEASE}）。 */
    public static final int ACTION_RELEASE = 0;

    /**
     * 鼠标键证据的有效期（毫秒）：按下之后这么久之内，算「这次的交互事件来自这个鼠标键」。
     *
     * <p>为什么需要有效期：GLFW 的按下（{@code InputEvent.MouseButton}）发生在**帧末**的
     * {@code Window.updateDisplay()} 里，而 MC 的交互事件在**下一帧**的
     * {@code Minecraft.tick() → handleKeybinds() → startUseItem()} 里才产生
     * （1.21.1 源码核对过）⇒ 两者差一帧，低帧率安卓上可能差 100 ms 以上。
     * 但也不能无限有效：否则很久以前的按下会被当成「这次的右键」。
     * 250 ms 覆盖「一帧 + 一点点」，又远短于人类两次点击的间隔。</p>
     */
    public static final long MOUSE_EVIDENCE_MS = 250L;

    // ---- 键盘（GLFW 编码，与 {@code InputEvent.Key#getKey()} 同一口径）----

    /** 回车（GLFW 257）：**转发给页面**（搜索框/表单提交）。 */
    public static final int KEY_ENTER = 257;
    /** Tab（GLFW 258）：转发给页面（切换输入框）。⚠ 原版也会开玩家列表，见类注释 ④。 */
    public static final int KEY_TAB = 258;
    /** 退格（GLFW 259）。 */
    public static final int KEY_BACKSPACE = 259;
    /** Delete（GLFW 261）。 */
    public static final int KEY_DELETE = 261;
    public static final int KEY_RIGHT = 262;
    public static final int KEY_LEFT = 263;
    public static final int KEY_DOWN = 264;
    public static final int KEY_UP = 265;

    /**
     * 打开「输入给网页」界面的键：<b>I</b>（GLFW 73）。
     *
     * <p>为什么不是 Enter：Enter 要转发给页面本身（搜索框按回车提交）。
     * 为什么 I 现在空着：⑥.3 已把「取消圈选」由 I 改成 Esc（{@code ProjectorKeys.KEY_CANCEL}）。</p>
     */
    public static final int KEY_INPUT_TEXT = 73;

    /** 一次能送进页面的最大字符数（{@code EditBox} 也按它限长）。 */
    public static final int MAX_TEXT = 512;

    /** 悬停转发的最高频率（毫秒）：约 30 Hz 足够，页面不需要每帧都收到 mousemove。 */
    // 20 Hz（原 33ms=30Hz）：网页的 hover 效果不需要 30Hz，
    // 而降频直接省下「CDP 消息 + 页面 mousemove 处理」这两笔开销（27.2-pre-135 性能）
    public static final long HOVER_MIN_INTERVAL_MS = 50L;

    /**
     * 按下之后至少隔这么久才发「松开」（毫秒）。
     *
     * <p>= 大约一个 tick。同一个 tick 里 press+release 连着发，页面会把它当成抖动/双击处理
     * （实测过的老毛病：点击进不去）。所以 {@link #click} 只负责按下，
     * 松开交给 {@link #pump()} 在下一个 tick 发。</p>
     */
    public static final long PRESS_SETTLE_MS = 40L;

    /** 待发「松开」队列的上限（正常最多 1 条；连点也不会无限长）。 */
    public static final int MAX_PENDING = 8;

    /** 点中之后圆环收缩多久（毫秒）——「点到了」的视觉反馈。 */
    public static final long CLICK_PULSE_MS = 140L;

    /** 点中时圆环缩到多少（1.0 = 不缩）。 */
    public static final double CLICK_SHRINK = 0.6;

    /** 滚轮事件不带修饰键。 */
    private static final int NO_MODIFIERS = 0;

    /**
     * 页面光标的圆环半径占控件高度的比例（<b>用户 2026-10-02 口径：2%</b>）。
     *
     * <p>上一版是 4%，玩家实测「圆圈半径太大、挡住内容」，所以砍半。</p>
     */
    public static final double CURSOR_RADIUS_RATIO = 0.02;

    /** 半径下限（画布单位）：控件再扁也留一个看得见的圈（上一版 0.7，用户要求降到 0.45）。 */
    public static final double CURSOR_RADIUS_MIN = 0.45;

    /** 半径上限（画布单位）：控件再大也别画成一个盖住整页的巨圈。 */
    public static final double CURSOR_RADIUS_MAX = 4.0;

    // ================================================================== 状态

    /** 悬停节流：同一个控件、同一个像素不重复发。 */
    private static final HoverThrottle THROTTLE = new HoverThrottle();

    /** 待发「松开」的队列（只在渲染线程读写）。 */
    private static final ClickQueue QUEUE = new ClickQueue();

    /** 控件 UUID → 页面光标位置（渲染线程写、渲染线程读；用并发表防跨线程读）。 */
    private static final Map<UUID, Cursor> CURSORS = new ConcurrentHashMap<>();

    /** 日志限流用的表（同一 key 最多 3 行、至少隔 3 秒）。 */
    private static final Map<String, AtomicInteger> LOG_COUNTS = new ConcurrentHashMap<>();
    private static final Map<String, Long> LOG_TIMES = new ConcurrentHashMap<>();

    private static final long LOG_MIN_INTERVAL_MS = 3_000L;

    /** 最近一次鼠标按下的证据（见 {@link MouseButtonEvidence}）。 */
    private static final MouseButtonEvidence MOUSE = new MouseButtonEvidence();

    /** 页面光标的运行期状态。 */
    private record Cursor(double localX, double localY, long untilMs, long pulseUntilMs) {
    }

    // ================================================================== ⓪ 页面点击判据（**全项目唯一一份**）

    /**
     * <b>「这次输入要不要转发给页面」的判据 —— 只有这一份，别再写第二处。</b>
     *
     * <p>【27.2-snapshot-130，玩家最终口径】**左键＝点击操作网页；右键＝不操作，只提示「需要左键操作」**。</p>
     *
     * <p>两条证据，<b>任一条成立就算「要转发」</b>（且必须不是右键）：</p>
     * <ol>
     *   <li>{@code attack}：MC 把这次输入认成「攻击」（左键按下）。</li>
     *   <li>{@code mouseButton == MC_BUTTON_LEFT}：GLFW 那一层的按下确实是左键 ——
     *       这是<b>第二条证据</b>：{@code startAttack()} 前面还有闸门，玩家的「攻击」键
     *       也可能没绑在鼠标左键上，光看 {@code attack} 会漏。</li>
     * </ol>
     * <p>{@code useItem}（右键）一票否决：右键在 {@code ClientInputHandler} 里被吃掉并提示，
     * 一个字节都不发给页面。</p>
     *
     * <p>⚠ <b>发给页面的按钮永远是左键</b>（见 {@link #PAGE_BUTTON}）：浏览器里只有左键会
     * 激活链接/按钮，右键只会弹菜单 —— 127/128 两版就栽在这里。</p>
     */
    public static boolean isWebClick(boolean useItem, boolean attack, int mouseButton) {
        return !useItem && (attack || mouseButton == MC_BUTTON_LEFT);
    }

    /**
     * 「最近一次鼠标按下是哪个键」的证据（{@code InputEvent.MouseButton} 那条路）。
     *
     * <p>它做两件事：①给 {@link #isWebClick} 当第二条证据；
     * ②给「MC 没把这次右键认成 use」时的补发当凭据 ——
     * 交互事件那条路认领过（{@link #claim()}）就不再补发，保证<b>同一次按下最多转发一次</b>。</p>
     *
     * <p>⚠ <b>这里不判「是不是右键」</b>（那也是 {@link #isWebClick} 的活）：
     * 它只回答「最近按下的是哪个键 / 有人认领过没有」。两份判定的老毛病就是这么来的。</p>
     */
    public static final class MouseButtonEvidence {
        private volatile int button = NO_MOUSE_BUTTON;
        private volatile long pressMs = Long.MIN_VALUE;
        private volatile boolean claimed = true;

        /** 记一次鼠标事件；只有「按下」（{@link #ACTION_PRESS}）才算证据。 */
        public void note(int button, int action, long nowMs) {
            if (action != ACTION_PRESS) {
                return;      // 松开不改变证据：一次快速点按的按下/松开可能落在同一批事件里
            }
            this.button = button;
            this.pressMs = nowMs;
            this.claimed = false;
        }

        /**
         * 有效期内那次按下用的是哪个键；没有证据 / 已过期返回 {@link #NO_MOUSE_BUTTON}。
         *
         * <p>⚠ 这是<b>取值</b>不是判定：判「是不是右键」只许用 {@link #isWebClick}。</p>
         */
        public int button(long nowMs) {
            if (pressMs == Long.MIN_VALUE || nowMs < pressMs || nowMs - pressMs > MOUSE_EVIDENCE_MS) {
                return NO_MOUSE_BUTTON;
            }
            return button;
        }

        /** 有人认领了这一次按下（交互事件那条路已经处理过它了）。 */
        public void claim() {
            claimed = true;
        }

        /** 这一次按下还没人认领吗（补发路径用它；也不看是哪个键）。 */
        public boolean pending(long nowMs) {
            return !claimed && button(nowMs) != NO_MOUSE_BUTTON;
        }

        /** 退出世界 / 关浮层时清掉。 */
        public void reset() {
            button = NO_MOUSE_BUTTON;
            pressMs = Long.MIN_VALUE;
            claimed = true;
        }
    }

    // ---------------------------------------------------------------- 证据的静态入口（给 ClientInputHandler）

    /** 【鼠标钩子】记一次鼠标事件（见 {@link MouseButtonEvidence#note}）。 */
    public static void noteMouseButton(int button, int action, long nowMs) {
        MOUSE.note(button, action, nowMs);
    }

    /** 现在那次按下的鼠标键（{@link #NO_MOUSE_BUTTON} = 没证据）。 */
    public static int mouseButton() {
        return MOUSE.button(System.currentTimeMillis());
    }

    /** 带显式时刻的取值版本（测试用）。 */
    public static int mouseButton(long nowMs) {
        return MOUSE.button(nowMs);
    }

    /** 交互事件那条路处理过这次按下了 ⇒ 别再补发。 */
    public static void claimMousePress() {
        MOUSE.claim();
    }

    /** 还有一次「没人认领的」鼠标按下吗（补发路径用）。 */
    public static boolean pendingMousePress() {
        return MOUSE.pending(System.currentTimeMillis());
    }

    /**
     * 【真机诊断】「这次点击落在按钮栏区域，只叫出按钮栏、**没有转发给页面**」的留痕（限流）。
     *
     * <p>为什么单独有它：{@code WebControls.click} 里那条 {@code inBar} 分支会
     * <b>吃掉点击却什么都不转发</b>（设计如此：底边是「叫出按钮栏」的区域），
     * 而它以前是静默的 —— 真机上看起来就是「右键点网页没反应」。
     * 这一行能直接把这种情形与「权限/会话/判据」区分开。</p>
     */
    public static void logBarSwallowed(double localX, double localY, WebWidget w) {
        infoThrottled("bar-swallow",
                "[Projector][网页] 点击未转发：原因=落在按钮栏区域（只叫出按钮栏）"
                        + " 局部=({},{}) 栏高={} 控件={}",
                trim(localX), trim(localY), w == null ? "-" : trim(w.barHeight()),
                WebSessions.shortId(w == null ? null : w.id));
    }

    /**
     * 【真机诊断】把「这次点击的现场」打一行（限流：同一现场最多 3 行、至少隔 3 秒）。
     *
     * <p><b>为什么必须有它：</b>玩家报「右键点网页画面毫无反应」时，只看得到
     * 「桥收到了悬停」而看不到「为什么没有转发」——因为失败的分支全是静默的。
     * 这一行把 MC 的分类（三个布尔）与本地事实（局部坐标 / 有没有会话 / 能不能用）
     * 一起打出来，一眼就能定死是哪一道闸门。</p>
     *
     * <p>限流键里带上四个布尔值：<b>不同的现场各占自己的 3 行配额</b> ——
     * 否则「按住左键挖方块」每 tick 都来一条（{@code Minecraft.continueAttack} 每 tick
     * 发一次攻击型交互事件），会把真正想看的那一行挤掉。</p>
     *
     * <p>只在「准心落在网页控件上」时调（{@code ClientInputHandler.handleWebControl} 的入口），
     * 所以它天然不刷屏。</p>
     */
    public static void logScene(int mcButton, boolean useItem, boolean attack, boolean pickBlock,
                               double[] local, Plane plane, WebWidget w) {
        long now = System.currentTimeMillis();
        boolean hasSession = w != null && liveSession(w.id) != null;
        boolean usable = WebControls.usable(plane, w);
        // 键里带上四个布尔 + 鼠标键：现场不同 ⇒ 各自的配额，互不挤占
        String key = "scene-" + (mcButton == MC_BUTTON_RIGHT ? 1 : 0) + (useItem ? 1 : 0)
                + (attack ? 1 : 0) + (pickBlock ? 1 : 0) + "-" + mouseButton(now);
        infoThrottled(key, "[Projector][网页] {}现场：isUseItem={} isAttack={} isPickBlock={}"
                        + " 局部={} 有会话={} 可用={}（鼠标键={}，平面={}，控件={}）",
                mcButton == MC_BUTTON_RIGHT ? "右键" : "左键", useItem, attack, pickBlock,
                local == null ? "-" : "(" + trim(local[0]) + "," + trim(local[1]) + ")",
                hasSession, usable, mouseButtonName(now),
                WebSessions.shortId(plane == null ? null : plane.id),
                WebSessions.shortId(w == null ? null : w.id));
    }

    /**
     * 「MC 没认领这次左键，由鼠标层补发」的留痕（限流）。
     *
     * <p>出现这一行说明：{@code isAttack()} 那条路<b>确实没走到</b>
     * （玩家键位没绑在鼠标左键上 / {@code startAttack} 被前面的闸门截断），
     * 而 {@link #isWebClick} 的第二条证据（GLFW 层的左键按下）救了它。</p>
     */
    public static void logPageFallback() {
        infoThrottled("fallback",
                "[Projector][网页] 左键补发：MC 的交互事件没有认领这次左键（isAttack 那条路没走到）"
                        + "⇒ 已按鼠标层证据直接转发给页面");
    }

    // ================================================================== ① 坐标换算（纯逻辑，可无头验证）

    /**
     * 控件局部 X → 页面 X（像素）。<b>纯线性换算、不夹取</b>（夹取见 {@link #pagePoint}）。
     *
     * <p>左右方向不翻：控件局部 x 向右 = 页面向右。</p>
     *
     * <p>⚠ 结果是<b>浏览器视口像素</b>（{@code texW} = 抓帧尺寸）：桥会按
     * 「排版视口 / 抓帧视口」的比例再折成 CDP 的 CSS 像素，<b>这一层不要预先折</b>
     * （见类注释 ①）。</p>
     */
    public static int pageX(WebWidget w, double localX, int texW) {
        if (w == null || !(w.w > 0) || texW <= 0) {
            return 0;
        }
        // ★【rc-139】**由渲染用的 UV 常量算出来**，不再手写「翻/不翻」：
        //   画布角约定 a=左下→(u0,v1)、d=左上→(u0,v0)（见 QuadCollector.canvasQuad），
        //   所以局部横坐标 t=localX/w 在纹理上是 u = u0 + (u1-u0)*t。
        //   这样「画面朝向」与「点到哪个像素」永远同步 —— 想整体调转 180°，
        //   只改 WebWidget.texU0()/texV0()/texU1()/texV1() 四个返回值即可（另一处会自动跟着走）。
        //   ⚠ 本值 = 浏览器**视口像素**（抓帧尺寸），不是 CSS 像素，别再折一次。
        double t = localX / w.w;
        return (int) Math.round((WebWidget.texU0() + (WebWidget.texU1() - WebWidget.texU0()) * t) * texW);
    }

    /**
     * 控件局部 y（0 = 控件底边）→ 页面像素 y。
     *
     * <p>与 {@link #pageX} 同源：控件底边采到的是纹理 {@code v1}，顶边是 {@code v0}
     * （{@code QuadCollector.canvasQuad} 的角约定），而页面像素
     * {@code y = v * texH}（页面 y 向下、v=0 是页面顶行）。</p>
     *
     * <p>当前 {@code texV0()=0 / texV1()=1} ⇒ {@code pageY = (1 - localY / h) * texH}：
     * 控件<b>左上角</b>对应页面<b>左上角</b>、控件左下角对应页面左下角。
     * rc-139 之前是反的（网页整体倒过来），真机实测见 {@link WebWidget#texU0()}。</p>
     */
    public static int pageY(WebWidget w, double localY, int texH) {
        if (w == null || !(w.h > 0) || texH <= 0) {
            return 0;
        }
        double t = localY / w.h;
        return (int) Math.round((WebWidget.texV1() + (WebWidget.texV0() - WebWidget.texV1()) * t) * texH);
    }

    /**
     * 局部坐标 → 页面像素点（两轴各算一次，并夹进视口内）。
     *
     * <p>转发给桥的每一个坐标都走这里 —— 于是「翻哪个轴」这件事只有一份实现。</p>
     */
    public static int[] pagePoint(WebWidget w, double localX, double localY, int texW, int texH) {
        int x = clampInt(pageX(w, localX, texW), 0, Math.max(0, texW - 1));
        int y = clampInt(pageY(w, localY, texH), 0, Math.max(0, texH - 1));
        return new int[]{x, y};
    }

    public static double[] clampLocal(WebWidget w, double localX, double localY) {
        if (w == null) {
            return new double[]{0, 0};
        }
        return new double[]{clampD(localX, 0, Math.max(0, w.w)), clampD(localY, 0, Math.max(0, w.h))};
    }

    /**
     * 页面光标的圆环半径（画布单位）：控件高度的 {@link #CURSOR_RADIUS_RATIO}（2%），
     * 夹在 [{@link #CURSOR_RADIUS_MIN}, {@link #CURSOR_RADIUS_MAX}]（画布单位）里。
     */
    public static double cursorRadius(WebWidget w) {
        if (w == null) {
            return 0.0;
        }
        return clampD(w.h * CURSOR_RADIUS_RATIO, CURSOR_RADIUS_MIN, CURSOR_RADIUS_MAX);
    }

    /** 点中时的收缩系数（纯逻辑：{@code now < pulseUntil} 时缩到 {@link #CLICK_SHRINK}）。 */
    public static double cursorRadiusScale(long nowMs, long pulseUntilMs) {
        return nowMs < pulseUntilMs ? CLICK_SHRINK : 1.0;
    }

    private static int clampInt(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    private static double clampD(double v, double lo, double hi) {
        if (Double.isNaN(v)) {
            return lo;
        }
        return v < lo ? lo : Math.min(v, hi);
    }

    /** 坐标进日志时留一位小数（`48.0` 这种满屏的数字看久了眼睛累）。 */
    private static String trim(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "-";
        }
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    // ================================================================== 悬停节流（纯逻辑）

    /**
     * 悬停节流器：**同一个控件、同一个像素不重复发**，并且最多 ~30 Hz。
     *
     * <p>为什么要「同像素不重复」：准心对着不动时，每帧都会算出一模一样的页面坐标，
     * 每帧发一次 mousemove 会让页面不停地重算 hover（CPU 白烧，日志也刷）。
     * 「最多 30 Hz」则是为了准心缓慢移动时不至于把每一帧都发出去。</p>
     *
     * <p>准心离开控件时调 {@link #reset()}：下次再看回来，哪怕是同一个像素也要重发一次
     * （页面那边中途收到过别的位置，状态已经不是「鼠标在这」了）。</p>
     */
    public static final class HoverThrottle {
        private UUID widget;
        private int x = Integer.MIN_VALUE;
        private int y = Integer.MIN_VALUE;
        private long lastMs = Long.MIN_VALUE;

        /** 这一次该不该发。 */
        public boolean shouldSend(UUID id, int x, int y, long nowMs) {
            if (id != null && id.equals(widget)) {
                if (x == this.x && y == this.y) {
                    return false;                      // 同一个像素：不重复发
                }
                if (lastMs != Long.MIN_VALUE && nowMs - lastMs < HOVER_MIN_INTERVAL_MS) {
                    return false;                      // 太快了
                }
            }
            return true;
        }

        /** 记下「刚发过这个位置」。 */
        public void note(UUID id, int x, int y, long nowMs) {
            this.widget = id;
            this.x = x;
            this.y = y;
            this.lastMs = nowMs;
        }

        /** 准心离开控件：忘掉上一次的位置（下次一定重发）。 */
        public void reset() {
            widget = null;
            x = Integer.MIN_VALUE;
            y = Integer.MIN_VALUE;
            lastMs = Long.MIN_VALUE;
        }
    }

    // ================================================================== 松开队列（纯逻辑）

    /** 一条「待发出的松开」。 */
    public record Pending(UUID widgetId, int pageX, int pageY, int button, long dueMs) {
    }

    /**
     * 「按下之后下一 tick 再松开」的队列（纯逻辑，可无头验证）。
     *
     * <p>{@link #poll(long)} 只取<b>到点</b>（{@code nowMs >= dueMs}）的条目 ⇒
     * 同一个 tick 里按下的松开最早也要等到 {@link #PRESS_SETTLE_MS} 之后才发得出去，
     * 页面就不会把这一下当成抖动。</p>
     */
    public static final class ClickQueue {
        private final List<Pending> items = new ArrayList<>();

        /** 记一次按下；返回入队的那条（队列满时先丢最旧的）。 */
        public Pending press(UUID id, int x, int y, int button, long nowMs) {
            Pending p = new Pending(id, x, y, button, nowMs + PRESS_SETTLE_MS);
            while (items.size() >= MAX_PENDING) {
                items.remove(0);
            }
            items.add(p);
            return p;
        }

        /** 取出一条到点的松开（先进先出）；没有则返回 null。 */
        public Pending poll(long nowMs) {
            for (int i = 0; i < items.size(); i++) {
                if (nowMs >= items.get(i).dueMs()) {
                    return items.remove(i);
                }
            }
            return null;
        }

        public int size() {
            return items.size();
        }

        public void clear() {
            items.clear();
        }
    }

    // ================================================================== 三个入口

    /**
     * 鼠标移动 → 页面（准心命中控件时）。
     *
     * <p>静默返回的四种情况：控件为空 / 权限不够（{@code publicControls=false} 且我不能改内容）/
     * 没有活着的会话（离屏、还没创建、超并发上限）/ 这一帧的位置没必要发（节流）。
     * <b>悬停不做任何提示</b>：不能因为「看了一眼」就弹字。</p>
     */
    public static void hover(WebWidget w, double localX, double localY) {
        hover(planeOf(w), w, localX, localY);
    }

    /** 悬停（调用方手上已经有拾取结果时用这个，少扫一遍平面列表）。 */
    public static void hover(Plane plane, WebWidget w, double localX, double localY) {
        if (w == null || w.id == null || !WebControls.usable(plane, w)) {
            return;
        }
        McefBridge.Session s = liveSession(w.id);
        int[] size = WebSessions.viewportFor(w.id);
        if (s == null || size == null) {
            return;
        }
        int[] p = pagePoint(w, localX, localY, size[0], size[1]);
        long now = System.currentTimeMillis();
        // 页面光标跟着准心走：**在节流之前**记（准心对着不动时 mousemove 不必重发，
        // 但「我正在看这个网页」这件事仍然成立 ⇒ 光标该一直亮着，离开 5 秒后才消失）。
        rememberCursor(w, localX, localY, now, 0L);
        if (!THROTTLE.shouldSend(w.id, p[0], p[1], now)) {
            return;
        }
        THROTTLE.note(w.id, p[0], p[1], now);
        WebSessions.markUsed(w.id);
        send(w.id, "hover", s, () -> s.mouseMove(p[0], p[1]));
    }

    /**
     * <b>右键</b>点击 → 页面：{@code mouseMove} → {@code mousePress} →（**下一 tick**）{@code mouseRelease}。
     *
     * <p>先补一个 move：页面上的 hover 状态与按下位置要是同一个点（不补的话，
     * 「鼠标没动过就按下」在某些页面上会被当成别处按下）。松开交给 {@link #pump()}。</p>
     *
     * @param mcButton MC/GLFW 的按键编码（见类注释 ②/③）：**只有右键会转发**
     *                 （判据 {@link #isWebClick(boolean, int)}）
     * @return true = 这次点击已经被吃掉（调用方直接 {@code event.setCanceled(true)}）；
     *         false = 没吃掉（控件为空 / 不是右键 / 没有会话，交给原版与后续流程——
     *         左键就是靠它落到「打开这个网页控件的编辑器」那条路上）
     */
    public static boolean click(WebWidget w, double localX, double localY, int mcButton) {
        return click(planeOf(w), w, localX, localY, mcButton);
    }

    /**
     * <b>右键点击（真实入口，带拾取结果的版本）</b>。
     *
     * <p>每一道闸门都<b>必须留痕</b>（真机排查纪律：不许静默 {@code return false}）：
     * 被拒时的原因会打一行 {@code [Projector][网页] 右键未转发：原因=…}。
     * 这几条原因就是「点网页没反应」的全部可能：</p>
     * <ul>
     *   <li>{@code 控件为空} —— 没拾取到控件（不该发生，拾取已经过了）；</li>
     *   <li>{@code 不是左键（按钮=N）} —— 调用方传错键（右键要走「打开编辑器」那条路）；</li>
     *   <li>{@code 权限…} —— 作者关了 {@code publicControls} 且我改不了这个平面：
     *       <b>吃掉这次点击 + 给玩家明文提示</b>（复用按钮栏那句，不新写文案）；</li>
     *   <li>{@code 没有会话（…）} —— <b>唯一允许放行</b>的早退；</li>
     *   <li>{@code 坐标越界（已钳制…）} —— <b>不是早退</b>：钳进视口后继续发。</li>
     * </ul>
     *
     * @return true = 这次点击已经被吃掉（调用方 {@code event.setCanceled(true)}）；
     *         false = 没吃掉（<b>只有「控件为空 / 不是左键 / 没有会话」这三种</b>）
     */
    public static boolean click(Plane plane, WebWidget w, double localX, double localY, int mcButton) {
        if (w == null || w.id == null) {
            logNotForwarded("empty", "控件为空");
            return false;
        }
        if (!isWebClick(false, false, mcButton)) {
            // 【27.2-snapshot-127 用户口径】只有**左键**操作网页；右键在这里就返回，
            // 交给 ClientInputHandler.handleWebControl 打开编辑器（见类注释 ③）。
            // 这里连「吃掉」都不做：原样放行，让那条既有路径照旧生效。
            // 这里连「吃掉」都不做：原样放行，让那条既有路径照旧生效。
            logNotForwarded("not-left", "不是左键（按钮={}）", mcButton);
            return false;
        }
        if (!WebControls.usable(plane, w)) {
            // 「用一下」的权限：与按钮栏同一份判据（作者可以关掉它）
            // 【本轮要求】权限不足**不静默**：明文提示（复用 WebControls.notifyDenied）＋留痕。
            // 仍然返回 true（吃掉）：否则这次右键会顺手变成「放方块 / 用物品」。
            WebControls.notifyDenied();
            logNotForwarded("denied", "权限（publicControls=false 且我改不了这个平面）");
            return true;
        }
        McefBridge.Session s = liveSession(w.id);
        int[] size = WebSessions.viewportFor(w.id);
        if (s == null || size == null) {
            // 【唯一放行给编辑器的早退】真的没有会话：不打字、不假装成功
            logNotForwarded("no-session", "没有会话（{}，url={}）",
                    WebSessions.status(w), w.targetUrl());
            return false;
        }
        return click(plane, w, localX, localY, mcButton, s, size[0], size[1],
                System.currentTimeMillis());
    }

    /**
     * <b>转发核心</b>：权限 → 换算 → 视口钳制 → 转发（{@code mouseMove} + {@code mousePress}）
     * → 排一条「松开」。
     *
     * <p>参数里的 {@code sink / viewW / viewH / nowMs} 是**注入点**：
     * 真实路径传 {@link McefBridge.Session} 与 {@link WebSessions#viewportFor} 的结果，
     * 无头验证传假会话与假视口（{@code tmp/v35/T35.java} 用它证明
     * 「右键 + 有会话 + 有权限 ⇒ 真的调用 {@code mousePress}/{@code mouseRelease}」）。
     * 判定与换算本体<b>只有这一份</b>，不许在别处再写一遍。</p>
     */
    public static boolean click(Plane plane, WebWidget w, double localX, double localY, int mcButton,
                               WebPointerSink sink, int viewW, int viewH, long nowMs) {
        if (w == null || w.id == null) {
            logNotForwarded("empty", "控件为空");
            return false;
        }
        if (sink == null || !sink.valid()) {
            logNotForwarded("dead-session", "会话已失效（浏览器可能刚被释放）");
            return false;
        }
        if (!isWebClick(false, false, mcButton)) {
            // 同一个判据（真实路径在会话之前已经判过一次）：核心自己也是完整的，
            // 否则「核心 + 假会话」这种验证方式会漏掉「左键也照发」这种漏洞。
            logNotForwarded("not-left", "不是左键（按钮={}）", mcButton);
            return false;
        }
        if (!WebControls.usable(plane, w)) {
            // 同一个人判据（真实路径在会话之前已经判过一次、并且提示过同一句话）：
            // 放在这里是为了「转发核心自己就是完整的」——无头验证可以直接钉住
            // 「权限不足 ⇒ 一次都不转发」（见 T35）。提示走 notifyDenied 的 1 秒限流，
            // 玩家不会看到两遍。
            WebControls.notifyDenied();
            logNotForwarded("denied", "权限（publicControls=false 且我改不了这个平面）");
            return true;
        }
        // 坐标：先按显示方向换算（**浏览器视口像素**，见类注释 ①），越界就钳制后继续 ——
        // 准心命中带 0.6 单位容差，局部坐标可能略微出框，而浏览器的越界像素是未定义行为。
        int rawX = pageX(w, localX, viewW);
        int rawY = pageY(w, localY, viewH);
        int[] p = pagePoint(w, localX, localY, viewW, viewH);
        if (rawX != p[0] || rawY != p[1]) {
            infoThrottled("clamped", "[Projector][网页] 未转发：原因=坐标越界（已钳制）"
                            + " 原始=({},{}) → 视口=({},{})（视口 {}x{}）",
                    rawX, rawY, p[0], p[1], viewW, viewH);
        }
        WebSessions.markUsed(w.id);
        // 这一下移动已经发出去了：同步节流器，免得下一帧 hover 再补发一次同一个点
        THROTTLE.note(w.id, p[0], p[1], nowMs);
        rememberCursor(w, localX, localY, nowMs, CLICK_PULSE_MS);
        // ★★ snapshot-129：**世界按的是右键，但发给页面的是左键**。
        //    理由（真机日志 + 浏览器语义）：世界里右键＝「操作」（与其它控件的惯例一致），
        //    可**浏览器里右键只会弹右键菜单，永远不会"点中"链接或按钮** ——
        //    127/128 两版都卡在这里：桥老老实实把 `mousePressed right` 发出去了（日志里 53 次成对），
        //    页面却不可能有任何反应。所以这里做**按钮翻译**：世界右键 → 页面左键。
        int pageButton = PAGE_BUTTON;
        send(w.id, "点击", sink, () -> {
            sink.mouseMove(p[0], p[1]);
            sink.mousePress(p[0], p[1], pageButton);
        });
        QUEUE.press(w.id, p[0], p[1], pageButton, nowMs);
        // 真机上「点了没反应」时第一眼要看的就是这一行：局部坐标算得对不对、
        // 有没有被夹到边界、视口尺寸是不是下发给浏览器的那一份。
        // 限流同全类规矩（同一 key 最多 3 行、至少隔 3 秒），别刷屏。
        infoThrottled("click", "[Projector][网页] 转发点击：局部=({},{}) → 视口=({},{})"
                        + "（视口 {}x{}，世界按钮={} → 页面按钮={}）",
                trim(localX), trim(localY), p[0], p[1], viewW, viewH,
                mcButton == MC_BUTTON_RIGHT ? "右键" : (mcButton == MC_BUTTON_LEFT ? "左键" : String.valueOf(mcButton)),
                pageButton == MC_BUTTON_LEFT ? "左键" : "右键");
        return true;
    }

    /**
     * 滚轮 → 页面。
     *
     * <p><b>符号约定</b>：MCEF 的 {@code sendMouseWheel(int x, int y, double amount, int modifiers)}
     * 要的就是 MC {@code Screen.mouseScrolled} 的那个 {@code scrollY}
     * （它自己的示例 {@code ExampleScreen.mouseScrolled} 里原样传下去），
     * 正数 = 滚轮向上。所以这里把 {@code event.getScrollDeltaY()} 原样往下传，**不取反**。</p>
     *
     * <p>⚠ 备注（别在这里改）：安卓兼容层把它翻成 CDP 的
     * {@code deltaY = getWheelRotation() * 100}（{@code McefDroidJniBridge} 第 1692 行），
     * 而 CDP 里 deltaY 正数是「内容向下」——两边符号口径可能不一致。真机上若发现滚动方向相反，
     * 只能在这里取反（那是它的翻译层问题，不是我们的调用问题）。</p>
     *
     * @return true = 这次滚轮已经被吃掉（调用方要 {@code event.setCanceled(true)}，
     *         否则原版会顺手把快捷栏换一格）
     */
    public static boolean scroll(WebWidget w, double localX, double localY, double delta) {
        return scroll(planeOf(w), w, localX, localY, delta);
    }

    /** 滚轮（带拾取结果的版本）。 */
    public static boolean scroll(Plane plane, WebWidget w, double localX, double localY, double delta) {
        if (w == null || w.id == null) {
            return false;
        }
        if (Double.isNaN(delta) || delta == 0.0) {
            return false;
        }
        if (!WebControls.usable(plane, w)) {
            WebControls.notifyDenied();
            return true;
        }
        McefBridge.Session s = liveSession(w.id);
        int[] size = WebSessions.viewportFor(w.id);
        if (s == null || size == null) {
            return false;      // 没会话：静默（滚轮会照常换快捷栏 —— 与「没命中网页控件」完全一致）
        }
        int[] p = pagePoint(w, localX, localY, size[0], size[1]);
        long now = System.currentTimeMillis();
        WebSessions.markUsed(w.id);
        THROTTLE.note(w.id, p[0], p[1], now);
        rememberCursor(w, localX, localY, now, 0L);
        send(w.id, "滚轮", s, () -> {
            s.mouseMove(p[0], p[1]);
            s.mouseWheel(p[0], p[1], delta, NO_MODIFIERS);
        });
        return true;
    }

    // ================================================================== 「我能不能操作它」的对外入口

    /**
     * 「我能不能操作这个网页」的对外入口：{@link WebControls#usable} 放行就 true；
     * 被拒时顺手提示一句（「作者不允许他人操作这个网页」）并返回 false。
     *
     * <p>给 {@code ClientInputHandler}（世界里按键/点击的入口）用：那边在 {@code client} 包，
     * 够不到包内的提示方法；<b>判据本体仍然只有 {@code WebControls} 那一份</b>，
     * 这里只是「判据 + 提示」的打包，不新写判定。</p>
     */
    public static boolean canUse(Plane plane, WebWidget w) {
        if (WebControls.usable(plane, w)) {
            return true;
        }
        WebControls.notifyDenied();
        return false;
    }

    // ================================================================== 键盘转发（纯逻辑判据 + 转发）

    /**
     * 这个 GLFW 键码要不要转发给页面（<b>纯逻辑真值表，见 T35</b>）。
     *
     * <p>只认这几个「网页里打字/移动光标要用」的键：<b>Enter / Tab / Backspace / Delete /
     * 四个方向键</b>。别的键一律不转发 —— 尤其是字母数字（那是 {@link #insertText} 与
     * 「输入给网页」界面的活）与 T / E / U / O（聊天 / 背包 / 本模组自己的键）。</p>
     */
    public static boolean forwardedKey(int glfwKey) {
        return glfwKey == KEY_ENTER || glfwKey == KEY_TAB || glfwKey == KEY_BACKSPACE
                || glfwKey == KEY_DELETE
                || glfwKey == KEY_LEFT || glfwKey == KEY_RIGHT
                || glfwKey == KEY_UP || glfwKey == KEY_DOWN;
    }

    /**
     * 键盘 → 页面（Backspace / Enter / Tab / 方向键 / Delete）。
     *
     * <p><b>只在「正在操作这个网页」时转发</b>：准心对着控件（调用方已经拾取过）、
     * {@link WebControls#usable} 放行、并且有活着的会话。三者缺一个就<b>什么都不做</b>
     * （走路 / 开背包 / 打字聊天一律不受影响）。</p>
     *
     * <p>⚠ 这里<b>不做「吃掉原版按键」</b>：NeoForge 的 {@code InputEvent.Key}
     * 本身不可取消（javap 查过，没有 setCanceled）⇒ 拦不住；其中只有 Tab 在原版里有含义
     * （玩家列表），会同时弹一下。如实记在类注释 ④。</p>
     *
     * <p>⚠ 真机若发现「按回车页面不提交」：那是桥把按下翻成 CDP 的 {@code rawKeyDown}
     * （不产生 keypress 事件），页面上的表单默认动作要 {@code keyDown + text} 才会触发。
     * 修法在桥那边（不是这里）：这层只负责把 GLFW 的 257 原样递下去。</p>
     *
     * @return true = 这次按键归页面（已转发，或被权限拦下并提示）；
     *         false = 没转发（不是要转发的键 / 没有活会话）
     */
    public static boolean key(Plane plane, WebWidget w, int glfwKey, int scanCode,
                              int modifiers, boolean press) {
        if (w == null || w.id == null || !forwardedKey(glfwKey)) {
            return false;
        }
        if (!WebControls.usable(plane, w)) {
            WebControls.notifyDenied();
            return true;
        }
        McefBridge.Session s = liveSession(w.id);
        if (s == null) {
            return false;      // 没会话：静默（这个键照旧归原版）
        }
        WebSessions.markUsed(w.id);
        send(w.id, "键盘", s, () -> {
            if (press) {
                s.keyPress(glfwKey, scanCode, modifiers);
            } else {
                s.keyRelease(glfwKey, scanCode, modifiers);
            }
        });
        return true;
    }

    // ================================================================== 文本输入（输入法/中文的最后一段路）

    /**
     * 「现在能不能往这个网页里输入文本」的判定结果。
     *
     * <p><b>判据只在这一处</b>；给人看的文案在语言文件里
     * （{@code WebInputScreen} 按状态取 {@code projector.msg.web_input_*}）——
     * 这样英文界面也是英文，而且不会出现「两份判定」。</p>
     */
    public enum TextInputStatus {
        /** 可以输入。 */
        OK,
        /** 没有活着的浏览器会话（没装前置模组 / 页面还没出画面 / 已经释放）：先等画面。 */
        NO_SESSION,
        /** 有会话，但这个环境没有可用的文本入口 ⇒ <b>降级</b>（界面要写明，不许静默失败）。 */
        UNSUPPORTED
    }

    /** 现在能不能往这个网页里输入文本（界面用它判降级）。 */
    public static TextInputStatus textInputStatus(WebWidget w) {
        if (w == null || w.id == null) {
            return TextInputStatus.NO_SESSION;
        }
        McefBridge.Session s = liveSession(w.id);
        if (s == null) {
            return TextInputStatus.NO_SESSION;
        }
        return s.supportsText() ? TextInputStatus.OK : TextInputStatus.UNSUPPORTED;
    }

    /** 把整段文本送进页面（手上只有控件时用这个）。 */
    public static boolean insertText(WebWidget w, String text) {
        return insertText(planeOf(w), w, text);
    }

    /**
     * 把整段文本送进页面 —— <b>「输入给网页」界面的提交入口</b>。
     *
     * <p>为什么要有它：MC 没给第三方模组「输入法合成串」的通道，唯一可靠的做法是
     * 借 MC 自己的文本框（{@code EditBox} 天然支持中文/输入法），拿到整段文本之后
     * 由桥一次性送进浏览器里当前聚焦的输入框。</p>
     *
     * @return true = 文本已经交给浏览器；false = 没送（没权限 / 没会话 / 环境不支持 /
     *         桥那边抛了）——调用方（界面）必须把原因显示出来
     */
    public static boolean insertText(Plane plane, WebWidget w, String text) {
        if (w == null || w.id == null) {
            return false;
        }
        // 必须从渲染线程提交：真正碰浏览器的那一下在里面（MCEF 的输入最终走到它自己
        // 在 GameRenderer.render 头部泵的 CEF 消息循环）。不是渲染线程就**明确拒绝**
        // （拒绝比在别人的崩点旁边再踩一脚好；界面提交按钮本来就在渲染线程上）。
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && !mc.isSameThread()) {
            warnThrottled("text-thread",
                    "[Projector][网页] 「输入给网页」必须从渲染线程提交，这次已忽略（控件={}）",
                    WebSessions.shortId(w.id));
            return false;
        }
        if (!WebControls.usable(plane, w)) {
            WebControls.notifyDenied();
            return false;
        }
        McefBridge.Session s = liveSession(w.id);
        if (s == null) {
            logNoSession(w, "输入文本");
            return false;
        }
        return insertText(plane, w, text, s);
    }

    /**
     * 纯逻辑核心（<b>可注入假会话做无头验证</b>，见 T35）：权限 → 支持性 → 转发 → 日志。
     *
     * <p>要求调用方在渲染线程（真实路径由界面提交按钮调，那本来就在渲染线程；
     * {@link McefBridge.Session} 自己还有一层线程护栏）。</p>
     */
    /** 逐字实时输入已经送进页面的字符数（诊断用）。 */
    private static int typedChars;
    private static long lastTypedLogMs;

    /**
     * <b>把一个字符实时送进页面</b>（「输入给网页」界面的逐字实时通道）。
     *
     * <p>为什么要它：NeoForge 21.1 <b>世界里没有字符事件</b>（{@code InputEvent} 只有
     * Key / MouseButton / MouseScrolling / InteractionKeyMappingTriggered 四种），
     * 所以「在世界里直接敲键盘让页面收到汉字」这条路根本不存在；玩家敲键盘时只有
     * Enter/退格那些<b>按键</b>能转发 ⇒ 上一版的表现就是「只有 Enter 有反应」。
     * 现在由输入界面的 {@code charTyped} 逐字调这里，页面才是真正的接收方。</p>
     *
     * <p>换行不从这里走（它要按「回车键」发，页面上的表单/搜索才会提交）。
     * 失败一律返回 false 并留痕（限流：前 8 个字符各一行，之后每秒最多一行）。</p>
     */
    public static boolean typeChar(Plane plane, WebWidget w, char c) {
        if (c == 0 || c == '\n' || c == '\r') {
            return false;
        }
        if (w == null || w.id == null) {
            return false;
        }
        McefBridge.Session s = liveSession(w.id);
        if (s == null) {
            logNotForwarded("no-session-text", "没有会话（{}）", WebSessions.status(w));
            return false;
        }
        if (!WebControls.usable(plane, w)) {
            WebControls.notifyDenied();
            return false;
        }
        boolean ok = s.insert(String.valueOf(c));
        typedChars++;
        long now = System.currentTimeMillis();
        if (typedChars <= 8 || now - lastTypedLogMs >= 1000L) {
            lastTypedLogMs = now;
            infoThrottledUnlimited("[Projector][网页] 键入 '{}'（U+{}）→ 页面"
                            + "（本次累计 {} 个字符，入口={}，结果={}）",
                    c, Integer.toHexString(c).toUpperCase(java.util.Locale.ROOT),
                    typedChars, s.textMethod(), ok ? "已送" : "失败");
        }
        return ok;
    }

    /** 每次调用都打一行（逐字输入时由调用方自己限流）。 */
    private static void infoThrottledUnlimited(String fmt, Object... args) {
        top.hmjmfabc.projector.Projector.LOGGER.info(fmt, args);
    }

    /** 网页出画面了没有（转发给 {@link WebSessions#pictureReady}；判据只有那一处）。 */
    public static boolean pictureReady(WebWidget w) {
        return WebSessions.pictureReady(w);
    }

    /** 这个控件当前用的文本入口名（界面/日志诊断用；没有会话则「无会话」）。 */
    public static String textMethodOf(WebWidget w) {
        McefBridge.Session s = w == null ? null : liveSession(w.id);
        return s == null ? "无会话" : s.textMethod();
    }

    /** 本局一共实时送进页面多少个字符（诊断：0 = 输入法没把字符送到 MC）。 */
    public static int typedChars() {
        return typedChars;
    }

    public static boolean insertText(Plane plane, WebWidget w, String text, WebTextSink sink) {
        if (w == null || w.id == null || sink == null) {
            return false;
        }
        String t = text == null ? "" : text;
        if (t.isEmpty()) {
            return false;      // 空串：不当一次输入（界面自己提示「先输入内容」）
        }
        if (t.length() > MAX_TEXT) {
            t = t.substring(0, MAX_TEXT);
        }
        if (!WebControls.usable(plane, w)) {
            WebControls.notifyDenied();
            return false;
        }
        if (!sink.supportsText()) {
            // 【降级分支】环境没有文本入口：不转发、不当成功，日志留痕 + 界面显示明文原因
            infoThrottled("text-unsupported",
                    "[Projector][网页] 控件={} 环境不支持向页面输入文本（丢掉了 {} 个字符）",
                    WebSessions.shortId(w.id), t.length());
            return false;
        }
        boolean ok;
        try {
            ok = sink.insert(t);
        } catch (Throwable th) {
            // 安卓上桥的失败方式是 Error（UnsatisfiedLinkError 之类），所以抓 Throwable
            warnThrottled("text-fail", "[Projector][网页] 控件={} 输入文本失败（{}）",
                    WebSessions.shortId(w.id), why(th));
            ok = false;
        }
        infoThrottled("text", "[Projector][网页] 输入文本：{} 个字符 → 控件={}（方式={}，{}）",
                t.length(), WebSessions.shortId(w.id), sink.textMethod(), ok ? "已送出" : "失败");
        return ok;
    }

    // ================================================================== tick 结算

    /**
     * 每 tick 结算待发的「松开」。<b>必须每 tick 都调，哪怕界面开着</b>
     * （挂在 {@code ClientInputHandler.onClientTick} 的第一行）——
     * 否则按下的那一下会永远停在「按住」状态（页面上的按钮会一直显示被按住）。
     */
    public static void pump() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        if (!mc.isSameThread()) {
            mc.execute(WebInput::pumpNow);
            return;
        }
        pumpNow();
    }

    private static void pumpNow() {
        flushReleases(System.currentTimeMillis(), WebInput::liveSession);
    }

    /**
     * 结算所有到点的「松开」（**唯一一份**；真实路径见 {@link #pump()}，无头验证传假会话）。
     *
     * @param sessions 控件 id → 下游（真实路径就是 {@link #liveSession}）
     * @return 真的发出去的条数
     */
    public static int flushReleases(long nowMs, java.util.function.Function<UUID, WebPointerSink> sessions) {
        int n = 0;
        Pending p;
        while ((p = QUEUE.poll(nowMs)) != null) {
            WebPointerSink s = sessions == null ? null : sessions.apply(p.widgetId());
            if (s == null || !s.valid()) {
                continue;      // 会话没了：松开没有意义（浏览器已经处理掉了）
            }
            if (release(p, s)) {
                n++;
            }
        }
        return n;
    }

    /** 发一条「松开」（唯一一份，任何 {@link WebPointerSink} 都接）。 */
    public static boolean release(Pending p, WebPointerSink sink) {
        if (p == null || sink == null || !sink.valid()) {
            return false;
        }
        try {
            sink.mouseRelease(p.pageX(), p.pageY(), p.button());
            return true;
        } catch (Throwable t) {
            warnThrottled("release", "[Projector][网页] 松开鼠标失败（{}）", why(t));
            return false;
        }
    }

    // ================================================================== 页面光标（浮层用）

    /**
     * 现在该画的「页面光标」（控件局部坐标 + 收缩系数）；不该画时返回 null。
     *
     * <p>显示窗口与按钮栏同一条规矩（{@link WebControls#HIDE_MS}）：最后一次交互之后 5 秒消失。
     * 转发的悬停位置是<b>准心在控件上的落点</b>——世界里没有真正的鼠标指针，
     * 画这个小圆环是为了让玩家知道「页面认为鼠标在哪」。</p>
     */
    public static CursorAt cursor(WebWidget w) {
        return cursor(w, System.currentTimeMillis());
    }

    /** 带显式时刻的版本（测试用）。 */
    public static CursorAt cursor(WebWidget w, long nowMs) {
        if (w == null || w.id == null) {
            return null;
        }
        Cursor c = CURSORS.get(w.id);
        if (c == null || nowMs >= c.untilMs()) {
            return null;
        }
        return new CursorAt(c.localX(), c.localY(), cursorRadiusScale(nowMs, c.pulseUntilMs()));
    }

    /** 页面光标（控件局部坐标 + 圆环收缩系数）。 */
    public record CursorAt(double localX, double localY, double scale) {
    }

    /**
     * 准心离开网页控件时调：复位节流，下次看回来一定重发一次悬停。
     *
     * <p><b>不动页面光标</b>：光标按 5 秒窗口自然消失（页面那边鼠标还停在最后那个位置，
     * 立刻抹掉反而看不懂）。</p>
     */
    public static void leaveWidget() {
        THROTTLE.reset();
    }

    /** 退出世界 / 关浮层时全清（由 {@link WebControls#clear()} 统一调，只有那一个入口）。 */
    public static void clear() {
        THROTTLE.reset();
        QUEUE.clear();
        CURSORS.clear();
        MOUSE.reset();
    }

    /** 诊断用：现在有几个控件记着页面光标。 */
    public static int cursorCount() {
        return CURSORS.size();
    }

    private static void rememberCursor(WebWidget w, double localX, double localY,
                                       long nowMs, long pulseMs) {
        double[] l = clampLocal(w, localX, localY);
        CURSORS.put(w.id, new Cursor(l[0], l[1], nowMs + WebControls.HIDE_MS,
                pulseMs > 0 ? nowMs + pulseMs : Long.MIN_VALUE));
        if (CURSORS.size() > 32) {
            CURSORS.clear();   // 兜底：控件被删掉时没人来清，别让它无限长
        }
    }

    // ================================================================== 工具

    /** 有活着的会话才算（{@code Session.valid()} 在 {@code close()} 之后一律 false）。 */
    private static McefBridge.Session liveSession(UUID widgetId) {
        McefBridge.Session s = WebSessions.sessionFor(widgetId);
        return s != null && s.valid() ? s : null;
    }

    /**
     * 把这个控件属于哪个平面找出来（只给不带 Plane 的重载用）。
     *
     * <p>世界内的调用方（{@code ClientInputHandler}）本来就已经从拾取结果里拿到平面了，
     * 走的是带 Plane 的重载；这里只服务「手上只有控件」的调用方。
     * 找不到平面 ⇒ 交给 {@link WebControls#usable} 判成「只有 publicControls 才放行」。</p>
     */
    private static Plane planeOf(WebWidget w) {
        if (w == null) {
            return null;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) {
            return null;
        }
        for (Plane p : PlaneCache.planesIn(mc.level.dimension().location())) {
            if (p != null && p.widgets != null && p.widgets.contains(w)) {
                return p;
            }
        }
        return null;
    }

    /**
     * 交给下游（会话）发一次输入。
     *
     * <p><b>必须在渲染线程</b>（见类注释 ⑥）：不在就 {@code mc.execute} 切回去。
     * 任何异常都吞掉并限流记一行 —— 第三方桥的失败方式包含 {@code Error}（安卓缺原生库），
     * 所以这里也是 {@code catch (Throwable)}。</p>
     *
     * <p>拿不到客户端（{@code mc == null}，只可能出现在无头验证里）时**直接跑**：
     * 真实路径永远有客户端，而 T35 要靠注入的假会话证明「这一下真的发出去了」。</p>
     */
    private static void send(UUID widgetId, String what, WebPointerSink s, Runnable task) {
        Runnable guarded = () -> {
            if (!s.valid()) {
                return;      // 会话在这之间被释放了：静默（控件离开视野是正常事）
            }
            try {
                task.run();
            } catch (Throwable t) {
                warnThrottled(what, "[Projector][网页] 控件={} 转发{}失败（{}）",
                        WebSessions.shortId(widgetId), what, why(t));
            }
        };
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.isSameThread()) {
            guarded.run();
        } else {
            mc.execute(guarded);
        }
    }

    /**
     * 「这一下没有转发」的留痕（<b>不许静默 return</b>，见类注释 ⑦）。
     *
     * <p>统一前缀 {@code [Projector][网页] 右键未转发：原因=…}，真机上一眼看出是哪道闸门；
     * 限流按原因分键（同一原因最多 3 行、至少隔 3 秒）。</p>
     */
    private static void logNotForwarded(String reasonKey, String reasonFmt, Object... args) {
        String reason = args.length == 0 ? reasonFmt : String.format(java.util.Locale.ROOT, reasonFmt, args);
        infoThrottled("not-forwarded-" + reasonKey,
                "[Projector][网页] 未转发：原因={}", reason);
    }

    /**
     * 鼠标键的中文名（只是排版，**不是判定**：判定只有 {@link #isWebClick} 一份 ——
     * 所以这里写成 {@code switch} 标签而不是比较表达式，别让它看起来像第二份判据）。
     */
    private static String mouseButtonName(long nowMs) {
        return switch (mouseButton(nowMs)) {
            case MC_BUTTON_LEFT -> "左";
            case MC_BUTTON_RIGHT -> "右";
            case NO_MOUSE_BUTTON -> "无证据";
            default -> String.valueOf(mouseButton(nowMs));
        };
    }

    /** 没有会话时的可操作提示（点击才说，悬停不说）。 */
    private static void logNoSession(WebWidget w, String what) {
        infoThrottled("no-session", "[Projector][网页] 控件={} {}暂时无效：{}（url={}）",
                WebSessions.shortId(w.id), what, WebSessions.status(w), w.targetUrl());
    }

    private static String why(Throwable t) {
        Throwable cause = t == null ? null : (t.getCause() != null ? t.getCause() : t);
        if (cause == null) {
            return "?";
        }
        String msg = cause.getMessage();
        return cause.getClass().getSimpleName() + (msg == null || msg.isEmpty() ? "" : ": " + msg);
    }

    private static void infoThrottled(String key, String fmt, Object... args) {
        log(false, key, fmt, args);
    }

    private static void warnThrottled(String key, String fmt, Object... args) {
        log(true, key, fmt, args);
    }

    /** 同一 key 最多 3 行、且至少隔 {@link #LOG_MIN_INTERVAL_MS}（与 WebSessions.Entry.note 同规则）。 */
    private static void log(boolean warn, String key, String fmt, Object... args) {
        long now = System.currentTimeMillis();
        Long last = LOG_TIMES.get(key);
        if (last != null && now - last < LOG_MIN_INTERVAL_MS) {
            return;
        }
        int n = LOG_COUNTS.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        if (n > 3) {
            return;
        }
        LOG_TIMES.put(key, now);
        if (warn) {
            Projector.LOGGER.warn(fmt, args);
        } else {
            Projector.LOGGER.info(fmt, args);
        }
    }
}

package top.hmjmfabc.projector.client.web;

/**
 * 鼠标输入的下游：{@code WebInput} 只认这几个动作，谁去碰浏览器由实现决定。
 *
 * <p>{@link McefBridge.Session}（真实会话）实现它；无头验证可以注入一个假的来**数**
 * 「到底转发了几次、按键传的是哪个」（见 {@code tmp/v35/T35.java}）。</p>
 *
 * <p>这与 {@link WebTextSink} 是同一条设计：<b>判据与转发在 {@code WebInput}，
 * 真正碰浏览器的那一下在实现类里</b>。这样「右键 + 有会话 + 有权限 ⇒ 真的会
 * {@code mousePress}/{@code mouseRelease}」这件事才能被脚本证明，而不用靠肉眼推理
 * （本项目在「墙/天花板偏移」上连猜五版的教训）。</p>
 *
 * <p>⚠ <b>为什么不能直接用 {@code McefBridge.Session}：</b>它是 {@code final} 且构造期要
 * 真的创建浏览器，没法造假；而 {@code WebInput} 又必须能脱离客户端跑。</p>
 *
 * <p>⚠ 线程：实现类里的方法<b>由渲染线程调用</b>（见 {@code WebInput} 类注释 ⑥）；
 * 实现自己也要吞掉 {@code Throwable}（安卓上缺原生库抛的是 {@code Error}）。</p>
 */
public interface WebPointerSink {

    /** 这个会话还能用吗（关掉 / 已释放 ⇒ false，输入一律不发）。 */
    boolean valid();

    /** 鼠标移动到页面坐标 {@code (x, y)}。 */
    void mouseMove(int x, int y);

    /** 在页面坐标 {@code (x, y)} 按下 {@code button}（MC/GLFW 口径：0=左 1=右 2=中）。 */
    void mousePress(int x, int y, int button);

    /** 在页面坐标 {@code (x, y)} 松开 {@code button}。 */
    void mouseRelease(int x, int y, int button);

    /** 在页面坐标 {@code (x, y)} 滚轮：{@code amount} 正数 = 向上，{@code modifiers} 是 GLFW 位域。 */
    void mouseWheel(int x, int y, double amount, int modifiers);
}

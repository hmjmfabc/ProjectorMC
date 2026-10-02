package top.hmjmfabc.projector.client.web;

/**
 * 「能把整段文本送进页面」的东西（27.2 用户要求：在网页里用输入法打字）。
 *
 * <p><b>为什么要抽这一层</b>：真实实现是 {@link McefBridge.Session}（浏览器对象在它手里），
 * 但输入转发的那套判据（权限 → 支持性 → 转发 → 日志）是<b>纯逻辑</b>，
 * 应该在无头环境里就能验（见 {@code tmp/v35/T35.java} 用假 Session 计数）。
 * 所以 {@link WebInput#insertText(net.hmjmfabc.projector.common.Plane,
 * net.hmjmfabc.projector.common.widget.WebWidget, String, WebTextSink)} 只依赖这个接口。</p>
 *
 * <p>实现方必须<b>自己保证线程</b>（真实实现内部还有一层渲染线程护栏）并且把失败
 * 变成 {@code false}／异常，绝不允许静默吞掉。</p>
 */
public interface WebTextSink {

    /**
     * 现在有没有可用的文本入口。
     *
     * <p>false ⇒ 调用方走<b>降级</b>：界面上写明「当前环境不支持向网页输入文本」，
     * 不转发、不当成功。</p>
     */
    boolean supportsText();

    /** 用的是哪一种入口（只给日志/诊断看，例如 {@code "sendKeyTyped"}）。 */
    String textMethod();

    /**
     * 把整段文本送进页面里当前聚焦的输入框。
     *
     * @return true = 已经交给浏览器
     */
    boolean insert(String text);
}

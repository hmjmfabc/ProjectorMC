package top.hmjmfabc.projector.client.web;

import net.neoforged.fml.ModList;
import top.hmjmfabc.projector.Projector;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 与 <b>MCEF</b>（Minecraft Chromium Embedded Framework）模组的可选桥接（27.2 网页控件）。
 *
 * <p>装了 MCEF 时，网页控件的画面由它渲染：它天生是 <b>OSR（离屏渲染）</b>，
 * {@code MCEF.createBrowser} 不需要 GUI，浏览器把网页画进一张 GL 纹理，
 * 我们用 {@code getRenderer().getTextureID()} 拿到那个 int 纹理名，套一层
 * {@link WebSessions} 里的 {@code AbstractTexture} 适配器就能当普通纹理贴到平面上
 * （与 WaterMedia 视频控件同一套做法，渲染管线一行不用改）。</p>
 *
 * <h2>为什么全部用反射（刻意为之，不许改）</h2>
 * <ol>
 *   <li><b>许可</b>：MCEF 是 <i>LGPL-2.1</i>。本项目 Apache-2.0，按用户明确要求：
 *       <b>不 import、不编译链接、不打包它的任何类，也不抄它的实现</b>。
 *       这里只出现「类名 / 方法名的字符串」，运行期用 {@code Class.forName + getMethod} 调它的公开 API，
 *       两边是各自独立的程序。{@code grep -n "cinemamod\|org.cef"} 本文件应当**只命中字符串字面量**。</li>
 *   <li><b>可选依赖</b>：没装 MCEF 时这段代码一行都不会执行到，连类加载都不会发生
 *       （所以它没有出现在 {@code build.gradle} 的依赖里，{@code libs/} 下那个 jar 只是给人 javap 看的）。</li>
 *   <li><b>平台降级</b>：MCEF 官方 README 明写 <i>“This mod will not work on Android”</i>
 *       （它要 glibc，安卓是 bionic），安卓上它的原生库加载会抛 {@code UnsatisfiedLinkError}
 *       —— 那是 {@link Error} 不是 {@link Exception}，所以本文件**所有**反射调用都
 *       {@code catch (Throwable)}。漏一个 catch (Exception) 就会把游戏带崩。</li>
 * </ol>
 *
 * <h2>线程约束（已对着 MCEF 2.1.6-1.21.1 的源码 / 字节码逐条确认，结论：全部从客户端渲染线程调）</h2>
 * <ul>
 *   <li><b>构造浏览器</b>：{@code MCEFBrowser.<init>} 里是
 *       {@code Minecraft.getInstance().submit(renderer::initialize)} ——
 *       <b>它自己把 {@code glGenTextures} 丢给渲染线程</b>，所以构造本身不要求调用方持有 GL 上下文；
 *       但 {@code createBrowser} 紧接着会 {@code createImmediately()}（JCEF 原生建浏览器），
 *       而 CEF 的消息循环是它自己的 mixin 在 {@code GameRenderer.render} 头部用
 *       {@code N_DoMessageLoopWork()} 泵的 ⇒ **那也只在渲染线程上跑**。
 *       结论：从渲染线程调是最稳的（MCEF 自己的示例也在 {@code Screen.init} 里建浏览器）。</li>
 *   <li><b>取纹理</b>：{@code MCEFRenderer.getTextureID()} 只是读一个 {@code int[1]}，
 *       但返回值要被 GL 绑定，所以只在渲染线程用。</li>
 *   <li><b>像素上传</b>：{@code MCEFBrowser.onPaint} → {@code glTexImage2D/glTexSubImage2D}
 *       由 CEF 回调驱动，而消息循环在渲染线程上泵 ⇒ **上传也发生在渲染线程**。
 *       这也意味着<b>我们不需要每帧驱动它</b>（没有 {@code preRender()} 这类必须每帧调的东西，
 *       这是它和 WaterMedia v2 最大的区别）。</li>
 *   <li><b>关闭</b>：{@code MCEFBrowser.close()} → {@code renderer.cleanup()} → {@code glDeleteTextures}
 *       **在调用线程上执行** ⇒ <b>释放必须在渲染线程</b>，否则要么删不掉（显存泄漏）要么删错上下文。
 *       因此 {@link WebSessions} 的 tick / 释放路径也只能挂在客户端 tick 或渲染路径上，
 *       <b>绝不能塞进后台线程</b>（视频那边 WaterMedia 有「渲染线程构造函数」的前科，见 LESSONS 第十六部分）。</li>
 * </ul>
 * <p>本类因此<b>假定调用方始终在客户端渲染线程（= MC 主线程）</b>，
 * 与 {@code WorldPlaneRenderer}、{@code WaterMediaVideos.textureFor} 同一条线程。</p>
 *
 * <h2>公开接口（规范 §3 的契约，别改签名）</h2>
 * <pre>
 *   available() / reason()
 *   create(String url, boolean transparent, int w, int h)   // 失败返回 null
 *   Session { textureId valid load reload reloadIgnoreCache back forward
 *             canGoBack canGoForward loading currentUrl setZoom resize
 *             mouseMove mousePress mouseRelease mouseWheel setFocus close }
 * </pre>
 * <p>唯一的额外方法：{@code Session.title()} —— {@code WebWidget.title} 那个 transient 字段
 * 总得有人填，而标题只能靠 MCEFClient 的标题监听（见下）。{@code status()} 不依赖它。</p>
 *
 * <h2>哪些是「按公开签名保守估计」</h2>
 * <ul>
 *   <li>用了 4 参的 {@code createBrowser(String, boolean, int, int)}（javap 已确认存在），
 *       同时保留 2 参 + {@code resize} 的兜底路径 —— 万一以后它删掉 4 参重载，也只是退化成先建后 resize。</li>
 *   <li>{@code available()} 要求 {@code ModList.isLoaded("mcef") && MCEF.isInitialized()}。
 *       MCEF 的初始化是**异步**的（它在 {@code Minecraft.setScreen} 的 mixin 里才第一次尝试，
 *       还可能弹「下载 CEF 运行库」的界面），所以<b>这个布尔值不缓存</b>：
 *       缓存会把「还没初始化」永久固化（同 §5.5 第 76 条「判定别在构造期取快照」）。
 *       只缓存 {@code Class}/{@code Method} 句柄。</li>
 *   <li><b>注册监听只有一条合法路径：{@code MCEFClient} 的聚合 API</b>。
 *       {@code MCEFClient.addDisplayHandler(...)} 是往它自己的 {@code displayHandlers}
 *       <b>列表里追加</b>，安全；而**绝不要**对裸 {@code org.cef.CefClient}
 *       （{@code MCEF.getClient().getHandle()}）调 {@code addLoadHandler/addDisplayHandler/
 *       addContextMenuHandler} —— 那个类内部每个类型只存**单个** handler 字段，
 *       一挂就<b>顶掉 MCEF 自己注册的处理器</b>（它的加载/光标/菜单行为全没了）。
 *       本类只用前者，且**全局只挂一个**（那层只有 add 没有 remove）。
 *       其余一切状态<b>靠轮询</b>：{@code isLoading()} / {@code getTextureID() != 0} / {@code getURL()} /
 *       {@code canGoBack()} / {@code canGoForward()}。</li>
 *   <li><b>标题</b>：MCEF 2.1.6 没有 {@code getTitle()}，只能通过
 *       {@code CefDisplayHandler.onTitleChange} 拿（{@code executeJavaScript(String,String,int)}
 *       也没有带 {@code CefStringVisitor} 回调的重载）⇒ 我们按上面那条**走 MCEFClient 的聚合 API**
 *       挂一个全局代理，把标题按浏览器身份记进 {@link #TITLES}，会话 {@code close()} 时删条目。
 *       拿不到（版本没有这个方法、或安卓桥那条路不派发）就<b>退回显示地址</b>
 *       （{@code currentUrl()} / {@code WebWidget.displayUrl()}）；{@code status()} 不依赖标题。</li>
 * </ul>
 *
 * <p><b>运行期可能踩的坑</b>（WebSessions 里各有一条兜底）：
 * 安卓 / 缺 CEF 运行库时 {@code createBrowser} 可能<b>成功但永远没有一帧</b>
 * （原生层在、Chromium 不在）⇒ 我们靠「10 秒纹理 id 还是 0」判死并降级，
 * 见 {@code WebSessions.NO_FRAME_TIMEOUT_MS}。</p>
 *
 * <h2>按 mcef-api.md（二进制证据）定下的六条硬规矩</h2>
 * <ol>
 *   <li><b>处处 {@code catch (Throwable)}</b>：安卓上 {@code MCEFPlatform.getPlatform()} 把
 *       {@code Linux + aarch64} 判成 {@code LINUX_ARM64}，照样去下载 glibc 版原生库 ⇒ 失败点是
 *       {@code UnsatisfiedLinkError} / {@code ExceptionInInitializerError} / {@code LinkageError} /
 *       {@code NoClassDefFoundError} —— <b>全是 {@code Error}，{@code catch (Exception)} 一个都抓不到</b>，
 *       漏一个就把装了 MCEF 的安卓玩家直接搞崩。本类与 {@link WebSessions} 的每个 try 都是
 *       {@code catch (Throwable)}。</li>
 *   <li><b>{@code getTextureID() == 0} 只表示「还没上传过任何一帧」</b>，不代表没初始化。
 *       <b>绝对不要</b>因为拿到 0 就去调 {@code MCEFRenderer.initialize()}（重复 {@code glGenTextures}
 *       是否安全未证实）⇒ 我们的做法是「等 + 超时」（{@code WebSessions} 的 10 秒无帧降级）。
 *       本类连 {@code "initialize"} 这个方法名都不解析。</li>
 *   <li><b>不需要每帧调任何东西</b>：CEF 心跳（{@code CefRenderUpdateMixin} 注入
 *       {@code GameRenderer.render} 头部泵 {@code N_DoMessageLoopWork}）与纹理上传
 *       （{@code MCEFBrowser.onPaint}）都是 MCEF 自己的事。每帧相关的只有
 *       <b>尺寸变化时</b>调一次 {@code resize} —— 注意 {@code MCEFBrowser.resize} 内部
 *       <b>没有去重</b>（{@code lastWidth/lastHeight} 是给 {@code onPaint} 判断整帧/脏矩形用的），
 *       所以**必须由我们**判断「尺寸真的变了」（{@code WebSessions} 用 Entry 里的 width/height 判）。</li>
 *   <li><b>懒加载 + 缓存</b>：探测不在 mod 构造期 / 静态块里跑；{@code Class.forName(name,false,loader)}
 *       连初始化类都不触发；{@code available()} 的结果会被缓存（正的永久、负的 2 秒）。</li>
 *   <li><b>创建只在渲染线程</b>（§4.4：构造函数本身线程安全，但 {@code createImmediately()} 与之后
 *       的所有 GL 操作都不安全）；<b>{@code close()} 也必须在渲染线程</b>（§4.5：里面是裸
 *       {@code glDeleteTextures}，非渲染线程会触发 MC 的「wrong thread」断言）。</li>
 *   <li><b>安卓口径</b>：安卓 + 没装 {@code mcefdroid} ⇒ 短路（连 {@code Class.forName} 都不做，
 *       见 {@link #platformUnsupported()} / {@link #available()}）；装了兼容层照常走正常流程。
 *       给玩家看的文案里不出现 CDP / Chromium / jcef（安卓那句是
 *       「本设备（安卓）需要额外的兼容模组才能显示网页」）。</li>
 * </ol>
 */
public final class McefBridge {
    private McefBridge() {
    }

    /** MCEF 的 modid（只用于 {@code ModList}，不引用它的类）。 */
    public static final String MOD_ID = "mcef";

    /** 同一个错误最多打几次日志（多了就是刷屏，见 §5.5 第 78 条）。 */
    private static final int MAX_SAME_LOG = 3;

    /** GLFW 的「回车」键码（文本里的换行按它发）。与 {@code WebInput.KEY_ENTER} 同一口径。 */
    private static final int KEY_ENTER = 257;

    /** 不带修饰键（Shift/Ctrl/Alt）。 */
    private static final int NO_MODIFIERS = 0;

    // ------------------------------------------------------------------ 反射句柄

    /** 所有句柄都从这一个静态表里取（{@code Class.forName} + {@code getMethod} 各只做一次）。 */
    private static final Map<String, Method> METHODS = new ConcurrentHashMap<>();

    private static volatile boolean bound;
    private static volatile boolean bindOk;

    private static Method mIsInitialized;
    private static Method mCreate4;
    private static Method mCreate2;
    private static Method mGetClient;
    private static Method mAddDisplayHandler;

    private static Method bGetRenderer;
    private static Method bResize;
    private static Method bLoadUrl;
    private static Method bReload;
    private static Method bReloadIgnoreCache;
    private static Method bCanGoBack;
    private static Method bGoBack;
    private static Method bCanGoForward;
    private static Method bGoForward;
    private static Method bIsLoading;
    private static Method bGetUrl;
    private static Method bSetZoom;
    private static Method bSetFocus;
    /** MCEF 自带的键盘快捷键开关（CTRL+R / CTRL± / ALT+←→ / CTRL+滚轮），我们建好就关掉。 */
    private static Method bUseBrowserControls;
    private static Method bMouseMove;
    private static Method bMousePress;
    private static Method bMouseRelease;
    private static Method bMouseWheel;
    private static Method bClose;

    /**
     * 文本 / 键盘入口（27.2 用户要求「在网页里用输入法打字」）。
     *
     * <p>⚠ <b>桥侧方法名待确认</b>：桥（`mcefdroid`）那边正在补一个「一次性把整段文本送过去」的
     * 入口，名字还没落到我这里。所以这里<b>按顺序试</b>，谁存在用谁（全是字符串反射，
     * 没有编译期链接，缺了只是退一档）：</p>
     * <ol>
     *   <li>{@code insertText(String)} —— 桥侧新入口的**首选可能名**（待确认）；</li>
     *   <li>{@code sendTextInput(String)} —— 同一件事的备选命名（待确认）；</li>
     *   <li>{@code sendKeyTyped(char,int)} —— <b>MCEF 2.1.6 确有的公开 API</b>
     *       （{@code MCEFBrowser} 第 279 行，`javap` 确证签名 {@code (CI)V}）；
     *       它发的是 CEF 的 {@code TYPE_CHAR} 事件，而安卓桥把这个分支翻成 CDP 的
     *       {@code Input.dispatchKeyEvent(type=char, text=字符)}
     *       （Source3 {@code McefDroidJniBridge.CefBrowser_N_N_SendKeyEvent} 的 {@code KEY_TYPE} 分支）
     *       ⇒ 汉字/符号走这条路本来就是通的，它是**一定能用的**那一档。</li>
     * </ol>
     */
    /**
     * <b>我们自己的安卓兼容层（mcefdroid）给第三方留的一次性入口</b>：
     * {@code top.hmjmfabc.mcefdroid.bridge.McefDroidJniBridge.insertText(Object browser, String text)}。
     *
     * <p>为什么优先用它：MCEF 2.1.6 的浏览器对象上<b>没有</b> insertText（javap 实证，整个 jar 0 处），
     * 只有 {@code sendKeyTyped(char,int)} 这个逐字符通道；而桥的这条入口是
     * <b>一次 CDP {@code Input.insertText}</b>（中文/emoji 都整段进去，自检 54 项里验过），
     * 少一层「逐字符能不能过」的不确定性。桥没装时返回 null，自动降级到下面的几条路。</p>
     */
    private static Method bridgeInsertText;
    private static boolean bridgeInsertProbed;
    private static Method bInsertText;
    private static Method bSendTextInput;
    private static Method bSendKeyTyped;
    private static Method bSendKeyPress;
    private static Method bSendKeyRelease;

    private static Method rTextureId;

    /**
     * 懒加载 + 只做一次的方法解析（规范 §3 的那组签名一一对应）。
     *
     * <p><b>为什么用 {@code Class.forName(name, false, loader)}</b>（initialize=false）：
     * {@code mcef-api.md §4.6} 确证 MCEF 的 {@code CefUtil} / {@code MCEFSettings} 的**静态初始化**
     * 就会取 {@code Minecraft.getInstance().gameDirectory} ⇒ 类一旦被初始化就要求 MC 客户端已就绪。
     * 这里整个探测都是**懒加载**（第一次 {@link #available()} 才跑，绝不在 mod 构造期 / 静态块里），
     * 而且连链接阶段都刻意不让它触发初始化类；真正触发是第一次 {@code invoke}，那时已经在游戏里了。</p>
     *
     * @return 全部必需方法都在 = true
     */
    private static boolean bind() {
        if (bound) {
            return bindOk;
        }
        synchronized (McefBridge.class) {
            if (bound) {
                return bindOk;
            }
            try {
                // ② 参数 false = 不初始化这个类（见上）
                Class.forName("com.cinemamod.mcef.MCEF", false, McefBridge.class.getClassLoader());
            } catch (Throwable t) {
                bound = true;
                bindOk = false;
                return false;
            }
            mIsInitialized = lookup("com.cinemamod.mcef.MCEF", "isInitialized");
            mCreate4 = lookup("com.cinemamod.mcef.MCEF", "createBrowser",
                    String.class, boolean.class, int.class, int.class);
            mCreate2 = lookup("com.cinemamod.mcef.MCEF", "createBrowser", String.class, boolean.class);
            // 标题只能靠它：走 MCEFClient 的**聚合列表** API（安全）；
            // 绝不对裸 org.cef.CefClient（getHandle()）挂 handler —— 那会顶掉 MCEF 自己的
            mGetClient = lookup("com.cinemamod.mcef.MCEF", "getClient");
            mAddDisplayHandler = lookup("com.cinemamod.mcef.MCEFClient", "addDisplayHandler",
                    safeClass("org.cef.handler.CefDisplayHandler"));

            final String b = "com.cinemamod.mcef.MCEFBrowser";
            bGetRenderer = lookup(b, "getRenderer");
            bResize = lookup(b, "resize", int.class, int.class);
            bLoadUrl = lookup(b, "loadURL", String.class);
            bReload = lookup(b, "reload");
            bReloadIgnoreCache = lookup(b, "reloadIgnoreCache");
            bCanGoBack = lookup(b, "canGoBack");
            bGoBack = lookup(b, "goBack");
            bCanGoForward = lookup(b, "canGoForward");
            bGoForward = lookup(b, "goForward");
            bIsLoading = lookup(b, "isLoading");
            bGetUrl = lookup(b, "getURL");
            bSetZoom = lookup(b, "setZoomLevel", double.class);
            bSetFocus = lookup(b, "setFocus", boolean.class);
            bUseBrowserControls = lookup(b, "useBrowserControls", boolean.class);
            bMouseMove = lookup(b, "sendMouseMove", int.class, int.class);
            bMousePress = lookup(b, "sendMousePress", int.class, int.class, int.class);
            bMouseRelease = lookup(b, "sendMouseRelease", int.class, int.class, int.class);
            bMouseWheel = lookup(b, "sendMouseWheel", int.class, int.class, double.class, int.class);
            // 文本 / 键盘（可选项：少了只是「输入给网页」界面走降级，网页照样能显示）
            // ⚠ 桥侧方法名待确认 ⇒ 三个候选按顺序试，见字段注释
            bInsertText = lookup(b, "insertText", String.class);
            bSendTextInput = lookup(b, "sendTextInput", String.class);
            bSendKeyTyped = lookup(b, "sendKeyTyped", char.class, int.class);
            bSendKeyPress = lookup(b, "sendKeyPress", int.class, long.class, int.class);
            bSendKeyRelease = lookup(b, "sendKeyRelease", int.class, long.class, int.class);
            bClose = lookup(b, "close");
            rTextureId = lookup("com.cinemamod.mcef.MCEFRenderer", "getTextureID");

            // 必需项：少了任何一个，网页控件就没法显示（其余可选，缺了只是某个按钮失效）
            bindOk = mIsInitialized != null && (mCreate4 != null || mCreate2 != null)
                    && bGetRenderer != null && rTextureId != null && bClose != null;
            bound = true;
            if (!bindOk) {
                note("bind-fail", "[Projector][网页] MCEF 的公开 API 反射失败（版本不认识？）："
                        + "createBrowser={} getRenderer={} getTextureID={} close={}，网页控件将不可用",
                        mCreate4 != null || mCreate2 != null, bGetRenderer != null,
                        rTextureId != null, bClose != null);
            } else {
                Projector.LOGGER.info("[Projector][网页] MCEF 兼容层就绪（反射绑定，未链接它的任何类；"
                        + "状态全轮询，标题监听={}）", mAddDisplayHandler != null);
            }
            return bindOk;
        }
    }

    /** 反射解析一个公开方法（带静态 Map 缓存，同一个「类#方法/参数」只找一次）。 */
    /**
     * 反射解析一个公开方法（带静态 Map 缓存，同一个「类#方法/参数」只找一次）。
     *
     * <p>{@code initialize=false}：**解析方法不需要初始化那个类**，而 MCEF/CEF 的静态初始化
     * 可能要求 MC 客户端已就绪（{@code mcef-api.md §4.6}）⇒ 能不触发就不触发。</p>
     */
    private static Method lookup(String owner, String name, Class<?>... params) {
        String key = owner + '#' + name + '/' + Arrays.toString(params);
        Method cached = METHODS.get(key);
        if (cached != null) {
            return cached;
        }
        Method found = null;
        try {
            found = Class.forName(owner, false, McefBridge.class.getClassLoader()).getMethod(name, params);
        } catch (Throwable ignored) {
            found = null;   // 这个方法在这个版本里不存在：调用点各自判空降级
        }
        if (found == null) {
            return null;
        }
        METHODS.put(key, found);
        return found;
    }

    private static Class<?> safeClass(String name) {
        try {
            return Class.forName(name, false, McefBridge.class.getClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 可用性 / 原因

    /** 装了 MCEF 模组吗（只看 modid，不碰它的类）。**结果缓存**：mod 列表加载后不再变。 */
    private static boolean modLoaded() {
        Boolean cached = modLoadedCache;
        if (cached != null) {
            return cached;
        }
        boolean loaded;
        try {
            if (ModList.get() == null) {
                return false;        // 太早（mod 列表还没就绪）：先不缓存，下次再问
            }
            loaded = ModList.get().isLoaded(MOD_ID);
        } catch (Throwable t) {
            return false;
        }
        modLoadedCache = loaded;
        return loaded;
    }

    private static volatile Boolean modLoadedCache;

    /**
     * MCEF 现在能用吗 = <b>平台允许 + 装了 + 已初始化</b>。
     *
     * <p><b>① 平台闸门</b>：<b>安卓 + 没装兼容层（{@code mcefdroid}）⇒ 直接 false</b>，
     * 而且<b>在此之前不碰 MCEF 的任何类</b>（不 {@code Class.forName}、不 {@code isInitialized()}）。
     * 理由：MCEF 官方 README 明写不支持安卓 —— 它会把 {@code Linux + aarch64} 判成
     * {@code LINUX_ARM64} 去下载 <b>glibc 版</b>原生库，加载失败抛 {@code UnsatisfiedLinkError}
     * （{@code Error}），而那个失败发生在 <b>MCEF 自己的 {@code Minecraft.setScreen} mixin</b> 里
     * （在我们的代码之外，拦不住也修不了）⇒ 我们能做的就是「不在别人的崩点旁边再加一脚」。
     * <b>装了兼容层的安卓机照常走正常流程</b>（那时是兼容层把 MCEF 的原生层顶起来的）。</p>
     *
     * <p><b>② 懒加载 + 缓存</b>：探测只在第一次调用时发生（绝不在 mod 构造期 / 静态块里 ——
     * MCEF 的 {@code CefUtil}/{@code MCEFSettings} 静态初始化就要 {@code Minecraft.getInstance()}）。
     * 结果缓存策略：<b>成功永久缓存</b>；失败只记 {@link UNAVAILABLE_RECHECK_MS} 秒
     * （不把「初始化还没跑完」永久固化：MCEF 自己是在 {@code setScreen} 里
     * {@code execute(() -> { sleep(1000); initialize(); })} 才初始化的，
     * 玩家进世界那一刻它很可能还没就绪；而一旦玩家真的下载完运行库，
     * 永久缓存的 false 会让功能整局都用不了）。</p>
     */
    public static boolean available() {
        // ① 平台闸门：安卓 + 没装兼容层 ⇒ 直接 false（**在此之前不碰 MCEF 的任何类**）
        if (platformUnsupported()) {
            return false;
        }
        // ② 缓存：正的永久、负的短 TTL
        long now = System.currentTimeMillis();
        if (availableCache != null) {
            if (availableCache) {
                return true;
            }
            if (now - lastCheckMs < UNAVAILABLE_RECHECK_MS) {
                return false;
            }
        }
        if (!modLoaded() || !bind()) {
            availableCache = false;
            lastCheckMs = now;
            return false;
        }
        boolean ok;
        try {
            Object value = mIsInitialized.invoke(null);
            ok = value instanceof Boolean b && b;
        } catch (Throwable t) {
            // 这里抓的是 Error 级的失败：UnsatisfiedLinkError / LinkageError /
            // ExceptionInInitializerError / NoClassDefFoundError（安卓上的典型签名）
            note("init-check", "[Projector][网页] MCEF.isInitialized() 调用失败（当成不可用）：{}", why(t));
            ok = false;
        }
        availableCache = ok;
        lastCheckMs = now;
        return ok;
    }

    private static volatile Boolean availableCache;
    private static volatile long lastCheckMs;

    /** 「不可用」结果的复查间隔（毫秒）：MCEF 的初始化是异步的，不能把 false 永久固化。 */
    private static final long UNAVAILABLE_RECHECK_MS = 2_000L;

    /**
     * 不可用时给界面看的人话原因（可用时返回「可用」）。
     *
     * <p><b>给玩家看的文案不许出现 CDP / Chromium / jcef</b>（规范 §5.6），
     * 只有「没装模组」这一条可以点名 MCEF（那是唯一需要玩家动手的情况）。</p>
     *
     * <p>最后那条「还没准备好」覆盖的是**正常态**：前置模组首次启动要自己下载运行库，
     * 下载期间它会把玩家的界面换成它自己的下载界面 —— 这时候玩家点我们按钮没反应是正常的，
     * 我们只要如实说「还没准备好」并保持可用性 2 秒复查一次（见 {@link #UNAVAILABLE_RECHECK_MS}），
     * 它一就绪功能自己就回来了。</p>
     */
    public static String reason() {
        if (platformUnsupported()) {
            return "本设备（安卓）需要额外的兼容模组才能显示网页";
        }
        if (!modLoaded()) {
            return "未安装 MCEF 模组（可选前置）";
        }
        if (!bind()) {
            return "网页功能不可用（前置模组版本不匹配）";
        }
        if (available()) {
            return "可用";
        }
        return "网页功能还没准备好（首次使用可能要下载运行库，完成后重启游戏）";
    }

    /**
     * 平台闸门：<b>安卓 + 没装兼容层 ⇒ 短路</b>（连 MCEF 的类都不碰）。
     *
     * <p>判据（自己写一份小的，<b>不引用</b> {@code Projector} 的私有方法）：<br>
     * {@code android.os.Build} 能加载 / {@code ANDROID_ROOT}、{@code ANDROID_DATA}、
     * {@code POJAV_NATIVEDIR}、{@code POJAV_LAUNCHER} 任一环境变量在 / {@code java.vm.name} 含 dalvik /
     * {@code /system/build.prop} 存在 —— 任一命中即算安卓。</p>
     *
     * <p>只看 {@code ModList.isLoaded("mcefdroid")} 决定放不放行：有兼容层就当作正常环境
     * （它会把 MCEF 的原生层放好），没有就短路。mod 列表还没就绪时**保守当没有**，
     * 而且不缓存这个结果（下次再问）。</p>
     */
    private static boolean platformUnsupported() {
        if (!isAndroid()) {
            return false;          // 桌面：正常路径
        }
        // 安卓：**只有装了兼容层（mcefdroid）才敢碰 MCEF**。
        // 缺原生库时崩的是 MCEF 自己的 Minecraft.setScreen mixin（在我们代码之外，拦不住也修不了），
        // 所以没有兼容层就在这里短路 —— 而且**在此之前一个 MCEF 的类都没碰、isInitialized() 也没调**
        // （不在别人的崩点旁边再加一脚）。
        Boolean bridge = bridgeLoaded;
        if (bridge == null) {
            try {
                if (ModList.get() == null) {
                    return true;   // mod 列表还没就绪：保守当「没有兼容层」，先别碰 MCEF
                }
                bridge = ModList.get().isLoaded(MCEF_ANDROID_BRIDGE_MODID);
            } catch (Throwable t) {
                bridge = Boolean.FALSE;
            }
            bridgeLoaded = bridge;
        }
        return !bridge;
    }

    /** 兼容层 modid（只按 id 认它，不引用它的代码）。 */
    private static final String MCEF_ANDROID_BRIDGE_MODID = "mcefdroid";

    private static volatile Boolean bridgeLoaded;

    /** 是否为安卓上的 Java 版运行环境（与 {@code Projector.isAndroidRuntime} 同一套判据）。 */
    private static boolean isAndroid() {
        Boolean cached = androidCache;
        if (cached != null) {
            return cached;
        }
        boolean android = false;
        try {
            Class.forName("android.os.Build");           // ① ART/Dalvik 上一定在
            android = true;
        } catch (Throwable ignored) {
            // 换下一条判据
        }
        if (!android) {
            try {
                android = System.getenv("ANDROID_ROOT") != null
                        || System.getenv("ANDROID_DATA") != null
                        || System.getenv("POJAV_NATIVEDIR") != null
                        || System.getenv("POJAV_LAUNCHER") != null;
            } catch (Throwable ignored) {
                // 环境变量读不到就继续
            }
        }
        if (!android) {
            try {
                String vm = System.getProperty("java.vm.name", "") + ' '
                        + System.getProperty("java.runtime.name", "");
                android = vm.toLowerCase(java.util.Locale.ROOT).contains("dalvik");
            } catch (Throwable ignored) {
                // 继续
            }
        }
        if (!android) {
            try {
                android = java.nio.file.Files.isRegularFile(
                        java.nio.file.Path.of("/system/build.prop"));
            } catch (Throwable ignored) {
                // 连文件都看不了就认命：当成桌面（宁可多说一句不可用，也别误判成安卓）
            }
        }
        androidCache = android;
        return android;
    }

    private static volatile Boolean androidCache;

    /**
     * 交给浏览器之前的**协议闸门**：只允许 {@code http} / {@code https} / {@code about:blank}。
     *
     * <p>数据层已经有一份判定（{@code WebWidget.acceptable()}，编辑器 / 服务端 / 存档共用），
     * 这里再拦一次是因为**我们是在把一个字符串交给第三方浏览器**：
     * 安卓桥那条路上 MCEF 的自定义方案（{@code mod://}）不可用，而 {@code file:} /
     * {@code javascript:} / {@code data:} 从来不该有机会进去（跨机器共享的公共屏幕）。</p>
     */
    private static boolean isAllowedScheme(String url) {
        if (url == null) {
            return false;
        }
        String u = url.trim().toLowerCase(java.util.Locale.ROOT);
        return u.equals("about:blank") || u.startsWith("http://") || u.startsWith("https://");
    }

    // ------------------------------------------------------------------ 创建

    /**
     * 建一个浏览器。
     *
     * <p><b>必须从客户端渲染线程调用</b>（理由见类注释：消息循环与 GL 上传都在那条线程上）。</p>
     *
     * @param url         起始地址（{@code null}/空 = {@code about:blank}）；
     *                    <b>只接受 {@code http(s)} 与 {@code about:blank}</b>，别的方案一律拒绝（返回 null）
     * @param transparent 是否透明背景
     * @param width       初始视口宽（像素）
     * @param height      初始视口高（像素）
     * @return 会话；失败返回 {@code null}（调用方画占位并稍后重试）
     */
    public static Session create(String url, boolean transparent, int width, int height) {
        String target = url == null || url.isEmpty() ? "about:blank" : url;
        if (!isAllowedScheme(target)) {
            // 交给第三方浏览器之前拦一道：安卓桥那条路上自定义方案（mod:// 之类）不可用，
            // 也不该让 file: / javascript: / data: 有机会进到浏览器里（mcef-bridge 报告第 3 条）
            note("create-scheme", "[Projector][网页] 拒绝加载这个地址（只允许 http/https/about:blank）：{}", target);
            return null;
        }
        if (!available()) {
            note("create-unavailable", "[Projector][网页] 无法创建浏览器（{}）：{}", target, reason());
            return null;
        }
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        try {
            Object browser;
            if (mCreate4 != null) {
                browser = mCreate4.invoke(null, target, transparent, w, h);
            } else {
                browser = mCreate2.invoke(null, target, transparent);
                if (browser != null && bResize != null) {
                    bResize.invoke(browser, w, h);
                }
            }
            if (browser == null) {
                note("create-null", "[Projector][网页] createBrowser 返回 null（{}），本帧不重试", target);
                return null;
            }
            Session session = new Session(browser, w, h);
            // 关掉 MCEF 自带的键盘快捷键（默认开：CTRL+R 刷新、CTRL±/0 缩放、ALT+←/→ 前进后退、
            // CTRL+滚轮缩放）。它不是「画按钮」，而是**输入层的快捷键**——
            // 我们自己画了 ← → ⟳ ⌂ 四个按钮，留着它只会和键盘转发打架。
            session.disableBrowserControls();
            session.installTitleWatcher();
            Projector.LOGGER.info("[Projector][网页] 已创建浏览器：url={} 视口={}x{} 透明={}",
                    target, w, h, transparent);
            return session;
        } catch (Throwable t) {
            // 安卓上这里是 Error（UnsatisfiedLinkError / LinkageError），catch (Exception) 抓不到
            note("create-error", "[Projector][网页] 创建浏览器失败（{}）：{}", target, why(t));
            return null;
        }
    }

    // ------------------------------------------------------------------ 会话

    /**
     * 一个浏览器会话（所有方法内部各自 try/catch，失败返回安全默认值）。
     *
     * <p>实现 {@link WebTextSink} 与 {@link WebPointerSink}：输入（文本 / 鼠标 / 键盘）的
     * 判据与转发在 {@code WebInput}，真正碰浏览器的那一下在这里
     * （这样那套判据能在无头环境里用假会话来验）。</p>
     */
    public static final class Session implements WebTextSink, WebPointerSink {
        /** 反射拿到的那个 MCEFBrowser 实例（我们不认识它的类型，只存 Object）。 */
        private final Object browser;
        /** 我们这边记的视口尺寸（读真值要反射，没必要；resize 成功才更新）。 */
        private volatile int width;
        private volatile int height;
        private volatile boolean closed;
        private final Map<String, AtomicInteger> logCounts = new ConcurrentHashMap<>();

        Session(Object browser, int width, int height) {
            this.browser = browser;
            this.width = width;
            this.height = height;
        }

        /**
         * GL 纹理 id（0 = 还没有上传过任何一帧；渲染方用 0 表示「还不该画」）。
         *
         * <p><b>每次都现问、绝不缓存数值</b>（mcef-bridge 报告第 2 条）：
         * 刚构造出来时它是 0，要等 MCEF 自己把第一帧上传后才非零；
         * 而且浏览器内部销毁重建后 id 会变 ⇒ 缓存的数值会指向一张已经不存在的纹理。
         * 所以调用方（{@code WebSessions.textureFor} 与纹理适配器的 {@code getId()}）
         * 一律**每帧重新问一次**。</p>
         */
        public int textureId() {
            if (closed) {
                return 0;
            }
            try {
                Object renderer = bGetRenderer.invoke(browser);
                if (renderer == null) {
                    return 0;
                }
                Object id = rTextureId.invoke(renderer);
                return id instanceof Number n ? n.intValue() : 0;
            } catch (Throwable t) {
                note("texture", "[Projector][网页] getTextureID 失败：{}", why(t));
                return 0;
            }
        }

        public void load(String url) {
            invokeOne(bLoadUrl, "loadURL", url == null || url.isEmpty() ? "about:blank" : url);
        }

        public void reload() {
            invokeOne(bReload, "reload");
        }

        public void reloadIgnoreCache() {
            invokeOne(bReloadIgnoreCache, "reloadIgnoreCache");
        }

        public void back() {
            invokeOne(bGoBack, "goBack");
        }

        public void forward() {
            invokeOne(bGoForward, "goForward");
        }

        public boolean canGoBack() {
            return invokeBool(bCanGoBack, "canGoBack");
        }

        public boolean canGoForward() {
            return invokeBool(bCanGoForward, "canGoForward");
        }

        /**
         * 正在加载吗 —— <b>纯轮询 {@code CefBrowser.isLoading()}</b>。
         *
         * <p>⚠ 不要改成「靠加载完成回调」：mcef-bridge 报告第 1 条确证，安卓桥那条路上
         * {@code onLoadEnd} <b>永不触发</b>（只有 {@code onLoadingStateChange} 会来），
         * 谁依赖回调谁的状态就永远停在「加载中」。轮询是唯一两边都可靠的读法。</p>
         */
        public boolean loading() {
            return invokeBool(bIsLoading, "isLoading");
        }

        /** 当前地址（取不到返回空串）。 */
        public String currentUrl() {
            try {
                if (bGetUrl == null || closed) {
                    return "";
                }
                Object v = bGetUrl.invoke(browser);
                return v instanceof String s ? s : "";
            } catch (Throwable t) {
                note("url", "[Projector][网页] getURL 失败：{}", why(t));
                return "";
            }
        }

        /**
         * 网页标题（拿不到 = 空串）。
         *
         * <p>这是规范 §3 之外的**唯一额外方法**：{@code WebWidget.title} 那个 transient 字段
         * 要求有人填。来源是全局标题监听（{@link #registerTitleWatcher}）；
         * 拿不到时退回显示地址（{@code currentUrl()} / {@code WebWidget.displayUrl()}），
         * {@code WebSessions.status} 不依赖它。</p>
         */
        public String title() {
            try {
                String t = TITLES.get(browser);
                return t == null ? "" : t;
            } catch (Throwable t) {
                return "";
            }
        }

        /**
         * 关掉 MCEF 自带的浏览器快捷键（由 {@link McefBridge#create} 自动调一次，不对外暴露）。
         *
         * <p>它管的是 CTRL+R / CTRL±/0 / ALT+←→ / CTRL+滚轮 这类**键盘**快捷方式，
         * 不是画按钮；网页控件的导航由我们自己那排按钮（{@code WebWidget.BTN_*}）负责，
         * 两套并存只会在以后接键盘转发时互相打架。</p>
         */
        private void disableBrowserControls() {
            try {
                if (bUseBrowserControls != null && !closed) {
                    bUseBrowserControls.invoke(browser, false);
                }
            } catch (Throwable t) {
                note("controls", "[Projector][网页] 关闭自带快捷键失败（不影响使用）：{}", why(t));
            }
        }

        private void installTitleWatcher() {
            McefBridge.registerTitleWatcher();
        }

        public void setZoom(double zoomLevel) {
            try {
                if (bSetZoom != null && !closed) {
                    bSetZoom.invoke(browser, zoomLevel);
                }
            } catch (Throwable t) {
                note("zoom", "[Projector][网页] setZoomLevel 失败：{}", why(t));
            }
        }

        /** 改变视口尺寸（只有尺寸真的变了才值得调；MCEF 内部会通知 Chromium 重排）。 */
        public void resize(int w, int h) {
            if (closed) {
                return;
            }
            int nw = Math.max(1, w);
            int nh = Math.max(1, h);
            if (nw == width && nh == height) {
                return;
            }
            try {
                if (bResize != null) {
                    bResize.invoke(browser, nw, nh);
                    width = nw;
                    height = nh;
                }
            } catch (Throwable t) {
                note("resize", "[Projector][网页] resize({},{}) 失败：{}", nw, nh, why(t));
            }
        }

        /** 鼠标事件：坐标是**浏览器视口内的像素坐标**（不是画布、不是屏幕坐标）。 */
        @Override
        public void mouseMove(int x, int y) {
            invokeTwoInts(bMouseMove, "sendMouseMove", x, y);
        }

        /** 第三参 = 按键掩码（MCEF 用的是 GLFW 的 0/1/2 掩码，不是 CEF 的 flag）。 */
        @Override
        public void mousePress(int x, int y, int button) {
            invokeThreeInts(bMousePress, "sendMousePress", x, y, button);
        }

        @Override
        public void mouseRelease(int x, int y, int button) {
            invokeThreeInts(bMouseRelease, "sendMouseRelease", x, y, button);
        }

        @Override
        public void mouseWheel(int x, int y, double amount, int modifiers) {
            try {
                if (bMouseWheel != null && !closed) {
                    bMouseWheel.invoke(browser, x, y, amount, modifiers);
                }
            } catch (Throwable t) {
                note("wheel", "[Projector][网页] sendMouseWheel 失败：{}", why(t));
            }
        }

        // ============================================================== 键盘 / 文本（27.2）

        /**
         * 一个按键按下。{@code glfwKey} 是 <b>GLFW 键码</b>（MC 的
         * {@code InputEvent.Key#getKey()} 原样传下来），{@code scanCode} 同源；
         * 安卓桥用 {@code client/KeyMap} 把它翻成 DOM 的 {@code key}/{@code code}/VK
         * （Enter/Tab/Backspace/方向键都在那张表里）。
         */
        public void keyPress(int glfwKey, int scanCode, int modifiers) {
            invokeKey(bSendKeyPress, "sendKeyPress", glfwKey, scanCode, modifiers);
        }

        /** 一个按键松开（同一套编码）。 */
        public void keyRelease(int glfwKey, int scanCode, int modifiers) {
            invokeKey(bSendKeyRelease, "sendKeyRelease", glfwKey, scanCode, modifiers);
        }

        /**
         * 现在有没有可用的文本入口（实现 {@link WebTextSink}）。
         *
         * <p>false ⇒ 上层走降级（界面写「当前环境不支持向网页输入文本」），不静默失败。</p>
         */
        @Override
        public boolean supportsText() {
            return !closed && (bridgeInsertTextEntry() != null
                    || bInsertText != null || bSendTextInput != null || bSendKeyTyped != null);
        }

        /** 用的是哪一个文本入口（日志/诊断用；没有则「无」）。 */
        @Override
        public String textMethod() {
            if (bridgeInsertTextEntry() != null) {
                return "bridge.insertText";
            }
            if (bInsertText != null) {
                return "insertText";
            }
            if (bSendTextInput != null) {
                return "sendTextInput";
            }
            if (bSendKeyTyped != null) {
                return "sendKeyTyped";
            }
            return "无";
        }

        /**
         * 把整段文本送进页面里<b>当前聚焦的输入框</b>（网页里已经点进去的那个）。
         *
         * <p>三条路按 {@link #textMethod()} 的顺序试：桥侧新的一次性入口 → 备选命名 →
         * <b>逐码点</b>发 {@code sendKeyTyped}（CEF 的 {@code TYPE_CHAR}，安卓桥翻成 CDP 的
         * char 事件）。逐码点走的是 UTF-16 {@code char}（与 MC 的 {@code charTyped} 同口径），
         * 所以 BMP 内的汉字/符号都在这一条路上；emoji 这类代理对会被拆成两个 char 发
         * （CEF 那边按 UTF-16 收，仍能拼回来）。换行按「回车键」发（单行输入框里就是提交）。</p>
         *
         * <p>失败一律返回 false 并留一行日志（最多 3 次），<b>不抛</b>：安卓上这里的失败方式
         * 包含 {@code Error}（原生库），抛出去会把游戏带崩。</p>
         *
         * <p>⚠ 成本：走最后的逐码点那条路时，<b>每个字符一次 CDP 消息</b>
         * （{@code WebInput.MAX_TEXT} = 512 ⇒ 最长 512 次），在渲染线程上是同步发出去的。
         * 常见用途（搜索框几十个字符）没问题；真机上若发现提交长文本时卡一下，
         * 首选是把桥的一次性入口接上（{@link #textMethod()} 会变成 {@code insertText}），
         * 次选是把 {@code MAX_TEXT} 调小。</p>
         */
        @Override
        public boolean insert(String text) {
            if (closed || text == null || text.isEmpty()) {
                return false;
            }
            if (!supportsText()) {
                note("text-unsupported", "[Projector][网页] 这个 MCEF 版本没有可用的文本入口"
                        + "（试过 insertText / sendTextInput / sendKeyTyped），不支持向页面输入文本");
                return false;
            }
            try {
                // 保险：CEF 只在浏览器持有焦点时把键盘/文本事件交给页面里那个聚焦元素。
                // 我们是「点过网页里的输入框 → 打开界面打字 → 回来提交」，中间 MC 的界面
                // 会拿走上层焦点，所以提交前再要一次（幂等，拿不到就跳过）。
                invokeBoolean(bSetFocus, "setFocus", true);
                Method viaBridge = bridgeInsertTextEntry();
                if (viaBridge != null) {
                    // 一次 CDP Input.insertText：整段文本（含中文/emoji）直接进当前聚焦元素
                    Object ok = viaBridge.invoke(null, browser, text);
                    if (Boolean.FALSE.equals(ok)) {
                        note("insert-text-bridge-false",
                                "[Projector][网页] 兼容层的一次性文本入口返回 false（页面可能没有聚焦元素），"
                                        + "改走逐字符通道：{} 个字符", text.length());
                    } else {
                        return true;
                    }
                }
                if (bInsertText != null) {
                    bInsertText.invoke(browser, text);
                    return true;
                }
                if (bSendTextInput != null) {
                    bSendTextInput.invoke(browser, text);
                    return true;
                }
                for (int i = 0; i < text.length(); i++) {
                    char c = text.charAt(i);
                    if (c == '\n' || c == '\r') {
                        invokeKey(bSendKeyPress, "sendKeyPress", KEY_ENTER, 0, NO_MODIFIERS);
                        invokeKey(bSendKeyRelease, "sendKeyRelease", KEY_ENTER, 0, NO_MODIFIERS);
                    } else {
                        bSendKeyTyped.invoke(browser, c, NO_MODIFIERS);
                    }
                }
                return true;
            } catch (Throwable t) {
                note("insert-text", "[Projector][网页] 输入文本失败（{} 个字符，方式={}）：{}",
                        text.length(), textMethod(), why(t));
                return false;
            }
        }

        /** {@code sendKeyPress/sendKeyRelease} 共用：缺席或失败都不抛（静默降级 + 限流日志）。 */
        private void invokeKey(Method m, String name, int glfwKey, int scanCode, int modifiers) {
            if (m == null || closed) {
                return;
            }
            try {
                m.invoke(browser, glfwKey, (long) scanCode, modifiers);
            } catch (Throwable t) {
                note(name, "[Projector][网页] {} 失败：{}", name, why(t));
            }
        }

        public void setFocus(boolean focused) {
            invokeBoolean(bSetFocus, "setFocus", focused);
        }

        /** 会话还在吗（{@code close()} 之后一律 false）。 */
        @Override
        public boolean valid() {
            return !closed && browser != null;
        }

        /**
         * 关闭浏览器。
         *
         * <p><b>必须在渲染线程调</b>：{@code MCEFBrowser.close()} 内部是
         * {@code renderer.cleanup()}（{@code glDeleteTextures}）+ {@code super.close(true)}。</p>
         */
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            // 把本浏览器在标题表里的条目清掉：代理只持有「浏览器对象 -> 标题」这张表，
            // 不持有 Session；清掉条目后关掉的浏览器不会被这张表留住（详见 registerTitleWatcher）
            try {
                TITLES.remove(browser);
            } catch (Throwable ignored) {
                // 标题表清不掉无所谓
            }
            try {
                if (bClose != null) {
                    bClose.invoke(browser);
                }
            } catch (Throwable t) {
                note("close", "[Projector][网页] close 失败（可能有显存残留）：{}", why(t));
            }
        }

        // ---- 反射小工具（每个都各自 try/catch）----

        private void invokeOne(Method m, String name, Object... args) {
            if (m == null || closed) {
                return;
            }
            try {
                m.invoke(browser, args);
            } catch (Throwable t) {
                note(name, "[Projector][网页] {} 失败：{}", name, why(t));
            }
        }

        private boolean invokeBool(Method m, String name) {
            if (m == null || closed) {
                return false;
            }
            try {
                Object v = m.invoke(browser);
                return v instanceof Boolean b && b;
            } catch (Throwable t) {
                note(name, "[Projector][网页] {} 失败：{}", name, why(t));
                return false;
            }
        }

        private void invokeBoolean(Method m, String name, boolean value) {
            if (m == null || closed) {
                return;
            }
            try {
                m.invoke(browser, value);
            } catch (Throwable t) {
                note(name, "[Projector][网页] {} 失败：{}", name, why(t));
            }
        }

        private void invokeTwoInts(Method m, String name, int a, int b) {
            if (m == null || closed) {
                return;
            }
            try {
                m.invoke(browser, a, b);
            } catch (Throwable t) {
                note(name, "[Projector][网页] {} 失败：{}", name, why(t));
            }
        }

        private void invokeThreeInts(Method m, String name, int a, int b, int c) {
            if (m == null || closed) {
                return;
            }
            try {
                m.invoke(browser, a, b, c);
            } catch (Throwable t) {
                note(name, "[Projector][网页] {} 失败：{}", name, why(t));
            }
        }

        /** 同一个错误最多打 {@link #MAX_SAME_LOG} 次，别刷屏。 */
        private void note(String key, String fmt, Object... args) {
            int n = logCounts.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
            if (n <= MAX_SAME_LOG) {
                Projector.LOGGER.warn(fmt, args);
            }
        }
    }

    // ------------------------------------------------------------------ 标题监听（全局只挂一个代理）

    /**
     * 浏览器对象 → 页面标题。
     *
     * <p>用**身份**表（不是 equals）：只认 MCEFBrowser 实例本身；
     * 会话 {@code close()} 时删掉自己的条目（{@code Session.close()}），
     * 所以关掉的浏览器不会被这张表留住。</p>
     */
    private static final Map<Object, String> TITLES =
            Collections.synchronizedMap(new IdentityHashMap<>());

    /** 挂过就不再挂（MCEFClient 只有 add、没有 remove ⇒ 挂多个就是泄漏）。 */
    private static volatile boolean titleWatcherTried;

    /**
     * 往 <b>{@code MCEFClient}</b> 上挂**一个全局的**标题监听（动态代理实现
     * {@code org.cef.handler.CefDisplayHandler}，不 import 它）。
     *
     * <p><b>为什么必须走 MCEFClient 这一层</b>：{@code MCEFClient.addDisplayHandler} 内部是往
     * 自己的 {@code displayHandlers} <b>列表里追加</b>，不会影响 MCEF 自己注册的处理器；
     * 而直接对裸 {@code org.cef.CefClient}（{@code MCEF.getClient().getHandle()}）
     * 调 {@code addDisplayHandler/addLoadHandler} 会<b>顶掉 MCEF 自己的处理器</b>
     * —— 那个类内部每个类型只存**单个** handler 字段。所以：**只走 MCEFClient 的聚合 API，
     * 永远不碰 getHandle()**。</p>
     *
     * <p><b>为什么是全局唯一一个</b>：{@code MCEFClient} 只有 add 没有 remove，
     * 每个会话挂一个会随会话反复创建而无限增长；所以这里只挂一次，
     * 用 {@link #TITLES} 按浏览器对象身份分发标题。代理本身**不持有任何 Session/浏览器强引用**
     * （它只在回调里把参数写进那张表），会话关闭后条目会被删掉。</p>
     *
     * <p>挂不上（版本没有这个类/方法）就退化成「标题留空」，不影响任何其它功能。</p>
     */
    private static void registerTitleWatcher() {
        if (titleWatcherTried) {
            return;
        }
        synchronized (McefBridge.class) {
            if (titleWatcherTried) {
                return;
            }
            titleWatcherTried = true;
            if (mGetClient == null || mAddDisplayHandler == null) {
                Projector.LOGGER.info("[Projector][网页] 这个 MCEF 版本没有可挂的标题监听，网页标题将留空");
                return;
            }
            try {
                Object client = mGetClient.invoke(null);
                if (client == null) {
                    return;
                }
                Class<?> itf = mAddDisplayHandler.getParameterTypes()[0];
                Object handler = Proxy.newProxyInstance(itf.getClassLoader(), new Class<?>[]{itf},
                        (proxy, method, args) -> {
                            String name = method.getName();
                            // ⚠ CEF 的回调跑在 **MC 主/渲染线程**上（它的消息循环挂在
                            // GameRenderer.render 的 HEAD）⇒ 这里**只许记一个字符串**，别做任何重活
                            if ("onTitleChange".equals(name) && args != null && args.length == 2
                                    && args[0] != null) {
                                String title = args[1] instanceof String s ? s : "";
                                TITLES.put(args[0], title.length() > 256 ? title.substring(0, 256) : title);
                                if (TITLES.size() > 32) {
                                    TITLES.clear();   // 兜底：不指望它一定不涨
                                }
                                return null;
                            }
                            if ("toString".equals(name)) {
                                return "projector-web-title-watcher";
                            }
                            if ("hashCode".equals(name)) {
                                return System.identityHashCode(proxy);
                            }
                            if ("equals".equals(name)) {
                                return args != null && args.length == 1 && args[0] == proxy;
                            }
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) {
                                return Boolean.FALSE;   // 「我没处理」——不抢 MCEF 自己的行为
                            }
                            if (rt == int.class) {
                                return 0;
                            }
                            if (rt == long.class) {
                                return 0L;
                            }
                            return null;
                        });
                mAddDisplayHandler.invoke(client, handler);
                Projector.LOGGER.info("[Projector][网页] 已挂上全局标题监听（走 MCEFClient 的聚合 API，"
                        + "动态代理，不引用它的类）");
            } catch (Throwable t) {
                Projector.LOGGER.info("[Projector][网页] 挂标题监听失败（{}），网页标题将留空", why(t));
            }
        }
    }

    // ------------------------------------------------------------------ 日志限流

    private static final Map<String, AtomicInteger> BRIDGE_LOGS = new ConcurrentHashMap<>();

    /** —— 与 Session.note 同规则，供静态路径用。 */
    /**
     * <b>退世界/断服时把外部 Chromium 清干净</b>（反射调兼容层的公开入口
     * {@code McefDroidJniBridge.purgeAll()}，不链接它的任何类）。
     *
     * <p>为什么要有它：外部 Chromium 的标签页会攒（玩家点开的 {@code target=_blank} 新页、
     * 历次残留的页面），每个标签页都是一个渲染进程，玩家实测「有时候占内存非常大」。
     * 兼容层没装时返回 null（那是**正常情况**，不是错误）。</p>
     *
     * @return 兼容层给的一行摘要（调用方打进日志）；拿不到就返回 null
     */
    public static String purgeExternal() {
        try {
            Class<?> c = Class.forName("top.hmjmfabc.mcefdroid.bridge.McefDroidJniBridge",
                    false, McefBridge.class.getClassLoader());
            java.lang.reflect.Method m = c.getMethod("purgeAll");
            Object r = m.invoke(null);
            return r == null ? null : String.valueOf(r);
        } catch (Throwable t) {
            return null;      // 没装兼容层 / 版本太老：静默跳过
        }
    }

    /**
     * 探测兼容层（mcefdroid）的一次性文本入口，只探一次。
     *
     * <p>没有装兼容层（PC 端 / 安卓没装）时返回 {@code null} —— 这是**正常降级**，
     * 不是错误，所以这里一个日志都不打（由 {@code textMethod()} 如实汇报用了哪条路）。</p>
     */
    private static java.lang.reflect.Method bridgeInsertTextEntry() {
        if (bridgeInsertProbed) {
            return bridgeInsertText;
        }
        bridgeInsertProbed = true;
        try {
            Class<?> c = Class.forName("top.hmjmfabc.mcefdroid.bridge.McefDroidJniBridge",
                    false, McefBridge.class.getClassLoader());
            bridgeInsertText = c.getMethod("insertText", Object.class, String.class);
        } catch (Throwable t) {
            bridgeInsertText = null;
        }
        return bridgeInsertText;
    }

    private static void note(String key, String fmt, Object... args) {
        int n = BRIDGE_LOGS.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        if (n <= MAX_SAME_LOG) {
            Projector.LOGGER.warn(fmt, args);
        }
    }

    /**
     * 异常变一行短描述（{@code InvocationTargetException} 要拆出 cause，否则只看到包装）。
     */
    private static String why(Throwable t) {
        Throwable cause = t == null ? null : (t.getCause() != null ? t.getCause() : t);
        if (cause == null) {
            return "?";
        }
        String msg = cause.getMessage();
        return cause.getClass().getSimpleName() + (msg == null || msg.isEmpty() ? "" : ": " + msg);
    }
}

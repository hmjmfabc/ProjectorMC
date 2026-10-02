package top.hmjmfabc.projector.common.widget;

import net.minecraft.nbt.CompoundTag;

import java.util.Locale;

/**
 * 【27.2】网页控件（第 11 种控件）：在平面的一块画布区域里显示一个网页。
 *
 * <p>渲染由**客户端**的可选前置模组 <b>MCEF</b>（Minecraft Chromium Embedded Framework）
 * 负责——它给出一个 GL 纹理 id，我们把它当普通纹理贴在平面上（与视频控件同一套画法）。
 * 服务端只保存「看什么」：控件里只有一个 URL 和几个显示参数，
 * 每个客户端各自创建自己的浏览器实例（与在线视频「只存链接」是同一个思路）。</p>
 *
 * <p><b>坐标约定</b>（与渲染、点击判定共用，避免「画在一处、点在另一处」）：
 * 局部 (0,0) = 控件左下角，局部 y 轴向上（画布 y 轴向上），局部坐标要再过一次
 * {@code rot(x, y, rot, …)} 才是画布坐标。</p>
 */
public class WebWidget extends Widget {

    /** 默认地址：留一个能被 MCEF 接受的空白页，避免新建即报错。 */
    public static final String BLANK = "about:blank";

    /** URL 长度上限（服务端也会按这个夹）。 */
    public static final int MAX_URL = 1024;

    /**
     * 【27.2-rc-139】网页画面的四个 UV 角 —— <b>朝向的唯一真源</b>。
     *
     * <p>渲染端（{@code WebWidgetRenderer.drawWebQuad}）直接用这四个数；
     * 点击/悬停的换算（{@code WebInput.pageX/pageY}）也<b>由这四个数算出来</b>。
     * 这样「画面朝向」与「点到的位置」永远不可能各改一半 ——
     * 本项目最贵的一类 bug 就是「同一件事两份判定，改了一份忘了另一份」
     * （131 的左右键、133 的坐标翻转都是这么来的）。</p>
     *
     * <h3>为什么要翻（以及什么时候要改回）</h3>
     * <p>网页画面不是我们上传的：它由外部 Chromium 抓帧、由兼容层直接
     * {@code glTexImage2D} 上传，<b>行/列序由那条链决定</b>，与 MC 自己的
     * {@code TextureManager} 那条链不一定一致。若它把画面存成「相对我们画布转了 180°」，
     * 就得在这里把两个轴都翻过来（{@code (1,1,0,0)}）；若它存的是标准朝向，
     * 就必须保持 {@code (0,0,1,1)}。</p>
     * <p><b>真机实测记录（别再凭推理改）</b>：2026-10-02（27.2-pre-133~138）用
     * {@code (1,1,0,0)}，玩家报告<b>墙面上网页整体倒过来</b>（同墙的文字控件是正的）；
     * rc-139 起改回 {@code (0,0,1,1)}。判据永远是「同一面墙上，网页里的字与文字控件的字
     * 哪一头朝上」—— 只看红点落点<b>验不出</b>这件事（渲染与点击一起翻时红点照样准）。</p>
     */
    // ⚠ 故意写成**方法**而不是 `static final float` 常量：编译期常量会被 javac 内联进
    //   每一个调用方，万一哪次只重编了一部分类，就会出现「渲染用新值、点击用旧值」——
    //   正是这条注释上面说的那类 bug。方法调用不会被内联，改了就是处处都改。
    public static float texU0() {
        return 0f;
    }

    /** 见 {@link #texU0()}。 */
    public static float texV0() {
        return 0f;
    }

    /** 见 {@link #texU0()}。 */
    public static float texU1() {
        return 1f;
    }

    /** 见 {@link #texU0()}。 */
    public static float texV1() {
        return 1f;
    }

    /** 世界内那条小按钮栏上的按钮（顺序即绘制 / 点击顺序）。 */
    public static final int BTN_BACK = 0;
    public static final int BTN_FORWARD = 1;
    public static final int BTN_REFRESH = 2;
    public static final int BTN_HOME = 3;
    public static final int BUTTON_COUNT = 4;

    /** 要显示的网页地址（空 = 还没设置）。 */
    public String url = "";

    /** 主页地址：点「⌂」回到这里（空则回到 {@link #BLANK}）。 */
    public String homeUrl = "";

    /** 页面缩放（Chromium 的 zoom level，0 = 100%），范围 -3 ~ 5。 */
    public double zoom = 0;

    /**
     * 渲染密度：画布 1 单位对应多少像素。
     *
     * <p>画布单位很小（16 单位 = 1 方块），一个 2x1 方块的控件在画布上是 32x16 单位；
     * 取 8 表示渲染 256x128 像素。密度越大越清晰、显存与 Chromium 侧开销越大。</p>
     */
    public double pixelPerUnit = 8;

    /** 是否显示世界内那条按钮栏（← → ⟳ ⌂）。 */
    public boolean showControls = true;

    /** 是否允许「非创建者」也按这些按钮（网页控件只有导航，改内容仍需权限）。 */
    public boolean publicControls = true;

    /** 自动刷新间隔（秒，0 = 不自动刷新）。 */
    public int autoRefreshSec = 0;

    /** 页面背景是否透明（透明时能看见平面本身的底色）。 */
    public boolean transparent = false;

    // ------------------------------------------------------------------
    // 运行期状态（不存档：每个客户端各算各的）
    // ------------------------------------------------------------------

    /** 上一次已知的标题（客户端从 MCEF 读到后写回，仅用于显示）。 */
    public transient String title = "";
    /** 【客户端】当前正在加载。 */
    public transient boolean loading;

    @Override
    public int kind() {
        return KIND_WEB;
    }

    @Override
    public String label() {
        String u = displayUrl();
        return u.isEmpty() ? "\u7f51\u9875" : u;
    }

    /** 显示用的地址：去掉协议前缀，太长就截断。 */
    public String displayUrl() {
        String u = url == null ? "" : url.trim();
        if (u.isEmpty()) {
            return "";
        }
        if (u.startsWith("https://")) {
            u = u.substring(8);
        } else if (u.startsWith("http://")) {
            u = u.substring(7);
        }
        return u.length() > 42 ? u.substring(0, 41) + "\u2026" : u;
    }

    /** 真正要加载的地址（没设置 = 空白页）。 */
    public String targetUrl() {
        String u = url == null ? "" : url.trim();
        return u.isEmpty() ? BLANK : u;
    }

    /** 点「⌂」要去的地址。 */
    public String homeTarget() {
        String h = homeUrl == null ? "" : homeUrl.trim();
        if (!h.isEmpty()) {
            return h;
        }
        String u = url == null ? "" : url.trim();
        return u.isEmpty() ? BLANK : u;
    }

    // ------------------------------------------------------------------
    // 地址合法性（与在线视频同一套「只认 http/https」的安全口径）
    // ------------------------------------------------------------------

    /**
     * 地址是否可用：只允许 {@code http://}、{@code https://} 与 {@code about:blank}。
     *
     * <p>别的协议（{@code file:}、{@code javascript:}、{@code data:}、{@code projector:}…）
     * 一律拒绝——网页控件是**所有人共享的公共屏幕**，不能让某个玩家借它读本机文件
     * 或者在我们自己的页面上跑脚本。</p>
     */
    public static boolean acceptable(String raw) {
        if (raw == null || raw.trim().length() > MAX_URL) {
            // 过长的地址直接判非法，而不是悄悄截断：截断后的地址多半打不开，玩家会更迷惑
            return false;
        }
        String u = normalize(raw);
        if (u.isEmpty()) {
            return false;
        }
        if (u.length() > MAX_URL) {
            return false;
        }
        for (int i = 0; i < u.length(); i++) {
            char c = u.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return false;
            }
        }
        return u.equals(BLANK) || u.startsWith("https://") || u.startsWith("http://");
    }

    /**
     * 规整地址：去掉首尾空白；没写协议但看起来是域名时补 {@code https://}。
     *
     * <p>补协议是为了省事：玩家粘贴 {@code www.bilibili.com} 也能用。
     * 非法输入返回空串（由调用方决定提示什么）。</p>
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String u = raw.trim();
        if (u.isEmpty()) {
            return "";
        }
        String lower = u.toLowerCase(Locale.ROOT);
        if (lower.equals(BLANK)) {
            return BLANK;
        }
        if (!lower.startsWith("http://") && !lower.startsWith("https://") && !lower.contains(":")) {
            // 没有协议头、也没有别的协议：当成域名补 https（本地地址与 IP 也照此处理）
            if (!u.contains(" ")) {
                u = "https://" + u;
            }
        }
        return u.length() > MAX_URL ? u.substring(0, MAX_URL) : u;
    }

    // ------------------------------------------------------------------
    // 世界内小按钮栏的几何（本地坐标；绘制与点击同源）
    // ------------------------------------------------------------------

    /** 单个按钮的边长（画布单位）：随控件大小缩放，但夹在一个能点得到的范围里。 */
    public double buttonSize() {
        double base = Math.min(w, h) * 0.20;
        return Math.max(3.0, Math.min(7.0, base));
    }

    /** 按钮栏的总高度（画布单位），按钮上下各留一点边距。 */
    public double barHeight() {
        return buttonSize() * 1.6;
    }

    /**
     * 第 {@code i} 个按钮的方框（本地坐标 {@code {x, y, w, h}}）。
     *
     * <p>按钮从控件左下角起横向排开（画布 y 轴向上 ⇒ 这条栏贴在控件**底部**），
     * 与视频控件的播放键同一条边、同一个视觉位置。</p>
     */
    public double[] buttonBox(int i) {
        double s = buttonSize();
        double pad = s * 0.3;
        double x = pad + i * (s + pad);
        double y = pad;
        return new double[]{x, y, s, s};
    }

    /**
     * 命中哪个按钮：{@code localX/localY} 是控件局部坐标，返回按钮下标，没命中返回 -1。
     *
     * <p>判定用**按钮外接方框**（不是圆的方程）：手指点触时方框更好按。</p>
     */
    public int hitButton(double localX, double localY) {
        if (!showControls) {
            return -1;
        }
        for (int i = 0; i < BUTTON_COUNT; i++) {
            double[] b = buttonBox(i);
            if (localX >= b[0] && localX <= b[0] + b[2] && localY >= b[1] && localY <= b[1] + b[3]) {
                return i;
            }
        }
        return -1;
    }

    /** 按钮栏是否盖住了某个局部点（用于「点画面 = 转发给网页」与「点按钮 = 我们自己处理」分流）。 */
    public boolean inBar(double localX, double localY) {
        return showControls && localY >= 0 && localY <= barHeight()
                && localX >= 0 && localX <= w;
    }

    // ------------------------------------------------------------------
    // 存档
    // ------------------------------------------------------------------

    @Override
    protected void saveExtra(CompoundTag t) {
        t.putString("url", url == null ? "" : url);
        t.putString("homeUrl", homeUrl == null ? "" : homeUrl);
        t.putDouble("zoom", zoom);
        t.putDouble("ppu", pixelPerUnit);
        t.putBoolean("showControls", showControls);
        t.putBoolean("publicControls", publicControls);
        t.putInt("autoRefreshSec", autoRefreshSec);
        t.putBoolean("transparent", transparent);
    }

    @Override
    public void loadExtra(CompoundTag t) {
        // 只认合法地址：非法的一律当没设置（旧存档 / 手改过的存档都不会把坏 URL 带进来）
        String u = t.getString("url");
        url = acceptable(u) ? normalize(u) : "";
        String h = t.getString("homeUrl");
        homeUrl = acceptable(h) ? normalize(h) : "";
        zoom = clamp(t.contains("zoom") ? t.getDouble("zoom") : 0, -3, 5);
        pixelPerUnit = clamp(t.contains("ppu") ? t.getDouble("ppu") : 8, 1, 24);
        showControls = !t.contains("showControls") || t.getBoolean("showControls");
        publicControls = !t.contains("publicControls") || t.getBoolean("publicControls");
        autoRefreshSec = (int) clamp(t.getInt("autoRefreshSec"), 0, 3600);
        transparent = t.getBoolean("transparent");
    }

    /** 把 URL / 缩放等字段夹到合法范围（服务端清洗与编辑器都用它，判定只有一份）。 */
    public void sanitize() {
        String u = normalize(url);
        url = acceptable(u) ? u : "";
        String h = normalize(homeUrl);
        homeUrl = acceptable(h) ? h : "";
        zoom = clamp(zoom, -3, 5);
        pixelPerUnit = clamp(pixelPerUnit, 1, 24);
        autoRefreshSec = (int) clamp(autoRefreshSec, 0, 3600);
    }

    private static double clamp(double v, double lo, double hi) {
        if (Double.isNaN(v)) {
            return lo;
        }
        return Math.max(lo, Math.min(hi, v));
    }
}

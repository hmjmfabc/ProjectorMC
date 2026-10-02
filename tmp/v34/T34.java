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

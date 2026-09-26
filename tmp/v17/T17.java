import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import top.hmjmfabc.projector.network.ClientNetHandler;
import top.hmjmfabc.projector.network.Payloads;
import top.hmjmfabc.projector.ProjectorConfig;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * T17 ——【P0】专用服务端 dist 隔离回归。
 *
 * <p>背景（{@code 27.1-rc-73} 玩家实测）：专用服务端在
 * {@code NetworkRegistry.setup()} 阶段崩溃，模组加载失败：</p>
 * <pre>
 * java.lang.BootstrapMethodError: java.lang.RuntimeException:
 *   Attempted to load class net/minecraft/client/gui/screens/Screen
 *   for invalid dist DEDICATED_SERVER
 *   at top.hmjmfabc.projector.network.ProjectorNetwork.register(ProjectorNetwork.java:38)
 * </pre>
 * <p>根因：{@code playToClient(..., ClientNetHandler::onOpenDialog)} 里的<b>方法引用</b>
 * 在登记时就要解析 {@code ClientNetHandler}，而它引用 {@code PlaneDialogScreen}
 * （继承客户端专用类 {@code Screen}）。</p>
 *
 * <h2>这个测试为什么能真的抓到它</h2>
 * <p>不是「公式 A 比公式 B」，而是<b>照 NeoForge 的方式真的执行一遍</b>：</p>
 * <ol>
 *   <li>用一个<b>子优先</b>的类加载器定义本模组的类（必须子优先，否则父加载器的旧副本
 *       会让「解析」绕过本加载器，测试就变成永远通过的假自检）；</li>
 *   <li>该加载器对 {@code net.minecraft.client.*} 与
 *       {@code top.hmjmfabc.projector.client.*} 的加载请求<b>直接抛异常</b>，
 *       完全复刻 {@code RuntimeDistCleaner}；</li>
 *   <li>反射调用 {@code ProjectorNetwork.register(event, false)}（= 专用服务端路径），
 *       记录它请求过哪些被禁类；</li>
 *   <li>再用一个宽松的加载器跑 {@code client=true}，比较两侧实际登记到
 *       {@code NetworkRegistry} 的包（id / flow / 协议 / 版本 / 处理函数）。</li>
 * </ol>
 *
 * <p>断言的是<b>不变量</b>：①服务端路径不碰任何 client 类；②两端登记的包必须完全一致
 * （否则联机时通道协商对不上）；③服务端侧的处理函数全是占位、客户端侧全是真货。</p>
 */
public final class T17 {

    /** 专用服务端上不允许加载的类前缀（和 RuntimeDistCleaner 的判据同义）。 */
    private static final String[] FORBIDDEN = {
            "net.minecraft.client.",
            "top.hmjmfabc.projector.client.",
    };

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        File classes = new File("build/classes/java/main");
        if (!classes.isDirectory()) {
            System.out.println("找不到 " + classes.getAbsolutePath() + "，请先在 Source2 里构建");
            System.exit(2);
        }
        URL[] urls = {classes.toURI().toURL(), new File("tmp/v17").getAbsoluteFile().toURI().toURL()};

        // ---------------------------------------------------------------
        // 1) 专用服务端路径：严格加载器（碰到 client 类就抛，等同 RuntimeDistCleaner）
        // ---------------------------------------------------------------
        Guard strict = new Guard(urls, T17.class.getClassLoader(), true);
        Class<?> pnServer = strict.loadClass("top.hmjmfabc.projector.network.ProjectorNetwork");
        Method registerFn = pnServer.getMethod("registerForDist", RegisterPayloadHandlersEvent.class, boolean.class);
        Object ignoredHandler = unwrap(pnServer.getMethod("ignoredHandler").invoke(null));
        String version = (String) pnServer.getField("VERSION").get(null);
        List<?> declaredIds = (List<?>) pnServer.getMethod("clientboundIds").invoke(null);

        clearRegistrations();
        Throwable boom = null;
        try {
            registerFn.invoke(null, new RegisterPayloadHandlersEvent(), false);
        } catch (Throwable t) {
            boom = t.getCause() == null ? t : t.getCause();
        }
        check("【复现点】专用服务端路径注册不再抛异常（rc-73 会 BootstrapMethodError）",
                boom == null, boom == null ? "" : String.valueOf(boom));
        check("【核心不变量】服务端路径全程没有请求过任何一个 client 类",
                strict.denied.isEmpty(), "被请求过：" + strict.denied);

        Map<String, Reg> serverSide = readRegistrations();
        List<String> serverClientbound = new ArrayList<>();
        List<String> serverServerbound = new ArrayList<>();
        for (Reg r : serverSide.values()) {
            if (isClientbound(r.flow)) serverClientbound.add(r.id);
            else serverServerbound.add(r.id);
        }
        // 期望值从「包表」推导，不要硬编码数字：加一个客户端包时这里自动跟上
        //（rc-77 加 MediaAck 时正是这条断言先把问题指出来的）
        check("服务端路径登记的客户端包数量 = 包表大小（" + declaredIds.size() + " 个）",
                serverClientbound.size() == declaredIds.size() && declaredIds.size() >= 5,
                "实际 " + serverClientbound.size() + " 个：" + serverClientbound);
        check("【自检】本次分类不是空的（防止空集合相等导致的假通过）",
                !serverSide.isEmpty() && serverSide.size() == declaredIds.size() + 6,
                "共登记 " + serverSide.size() + " 个：" + serverSide.keySet());
        check("服务端路径登记的 id 与 ProjectorNetwork 的包表一致",
                new TreeSet<>(serverClientbound).equals(new TreeSet<>(strIds(declaredIds))),
                "表=" + strIds(declaredIds) + " 登记=" + new TreeSet<>(serverClientbound));

        boolean allIgnored = true;
        boolean flowOk = true;
        for (Reg r : serverSide.values()) {
            if (isClientbound(r.flow) && r.handler != ignoredHandler) allIgnored = false;
            if (!r.protocols.contains("PLAY")) flowOk = false;
            if (!version.equals(r.version)) flowOk = false;
        }
        check("服务端路径的客户端包全部用占位处理函数（不引用任何 client 类）",
                allIgnored, "有包没有用占位函数");
        check("所有登记的包都是 PLAY 阶段、版本为 " + version, flowOk, "");
        check("服务端方向的包也登记了 6 个（两端一致）",
                serverServerbound.size() == 6, "实际 " + serverServerbound.size() + " 个：" + serverServerbound);

        // ---------------------------------------------------------------
        // 2) 物理客户端路径：宽松加载器
        // ---------------------------------------------------------------
        Guard lax = new Guard(urls, T17.class.getClassLoader(), false);
        Class<?> pnClient = lax.loadClass("top.hmjmfabc.projector.network.ProjectorNetwork");
        Method registerFnClient = pnClient.getMethod("registerForDist", RegisterPayloadHandlersEvent.class, boolean.class);

        clearRegistrations();
        Throwable boom2 = null;
        try {
            registerFnClient.invoke(null, new RegisterPayloadHandlersEvent(), true);
        } catch (Throwable t) {
            boom2 = t.getCause() == null ? t : t.getCause();
        }
        check("物理客户端路径注册成功（真的加载了 client 类）",
                boom2 == null, boom2 == null ? "" : String.valueOf(boom2));
        check("物理客户端路径确实加载过 ClientPayloadHandlers（证明上面的宽松加载器有效）",
                lax.loaded.contains("top.hmjmfabc.projector.client.ClientPayloadHandlers"),
                "已加载：" + lax.loaded);

        Map<String, Reg> clientSide = readRegistrations();
        List<String> clientClientbound = new ArrayList<>();
        Set<String> realHandlers = new LinkedHashSet<>();
        for (Reg r : clientSide.values()) {
            if (isClientbound(r.flow)) {
                clientClientbound.add(r.id);
                if (r.handler != null && r.handler != ignoredHandler) realHandlers.add(r.id);
            }
        }
        check("【防漂移】客户端与服务端登记的客户端包 id 集合完全相同",
                new TreeSet<>(clientClientbound).equals(new TreeSet<>(serverClientbound)),
                "客户端=" + new TreeSet<>(clientClientbound) + " 服务端=" + new TreeSet<>(serverClientbound));
        check("客户端路径的每个客户端包都有真实处理函数（一个占位都没有）",
                realHandlers.size() == clientClientbound.size(),
                "有真货 " + realHandlers.size() + " / 共 " + clientClientbound.size());

        // ---------------------------------------------------------------
        // 3) 客户端处理函数表本身
        // ---------------------------------------------------------------
        Class<?> cph = lax.loadClass("top.hmjmfabc.projector.client.ClientPayloadHandlers");
        Set<?> handled = (Set<?>) cph.getMethod("handledIds").invoke(null);
        check("ClientPayloadHandlers 覆盖了 ProjectorNetwork 表里的每一个客户端包",
                handled.size() == declaredIds.size() && strIds(new ArrayList<>(handled)).stream()
                        .allMatch(s -> strIds(declaredIds).contains(s)),
                "处理表=" + strIds(new ArrayList<>(handled)) + " 包表=" + strIds(declaredIds));

        java.util.function.Function<ResourceLocation, Object> lookup =
                castLookup(cph.getMethod("lookup").invoke(null));
        Object unknown = lookup.apply(ResourceLocation.fromNamespaceAndPath("projector", "definitely_not_a_payload"));
        Object ignoredOfClientLoader = unwrap(pnClient.getMethod("ignoredHandler").invoke(null));
        check("查表遇到未知 id 时退回占位函数（不抛异常、不静默崩溃）",
                unwrap(unknown) == ignoredOfClientLoader, "拿到了 " + unknown);

        // ---------------------------------------------------------------
        // 3b) 配置项范围：日 10GB 上限曾经被 int 上限「纠正」成 2GB
        //     （玩家服务端日志实测：Incorrect key media.dailyOutboundLimitBytes
        //       was corrected from 10737418240 to its default, 2147483647）
        // ---------------------------------------------------------------
        long def = ProjectorConfig.INSTANCE.dailyOutboundLimitBytes.getDefault();
        check("日 10GB 出站上限的默认值没有被配置范围夹掉（曾经被夹成 2GB）",
                def == 10L * 1024 * 1024 * 1024, "默认值=" + def);

        // 3c) ② 上传配额（用户原文：普通玩家图片 4 MB / 视频 64 MB，管理员 16 MB / 256 MB，
        //     且**放开普通玩家上传**）。这几个数字是需求，改动必须让断言先失败。
        long img = ProjectorConfig.INSTANCE.maxImageBytes.getDefault();
        long vid = ProjectorConfig.INSTANCE.maxVideoBytes.getDefault();
        long aImg = ProjectorConfig.INSTANCE.adminMaxImageBytes.getDefault();
        long aVid = ProjectorConfig.INSTANCE.adminMaxVideoBytes.getDefault();
        check("普通玩家图片上限 = 4 MB", img == 4L * 1024 * 1024, "实际=" + (img / 1024 / 1024) + " MB");
        check("普通玩家视频上限 = 64 MB", vid == 64L * 1024 * 1024, "实际=" + (vid / 1024 / 1024) + " MB");
        check("管理员图片上限 = 16 MB", aImg == 16L * 1024 * 1024, "实际=" + (aImg / 1024 / 1024) + " MB");
        check("管理员视频上限 = 256 MB", aVid == 256L * 1024 * 1024, "实际=" + (aVid / 1024 / 1024) + " MB");
        check("默认放开上传：普通玩家无需权限等级 4（onlyAdminCanUpload 默认 false）",
                !ProjectorConfig.INSTANCE.onlyAdminCanUpload.getDefault(),
                "默认=" + ProjectorConfig.INSTANCE.onlyAdminCanUpload.getDefault());

        // ---------------------------------------------------------------
        // 4) 有效性自证：把 rc-73 的写法原样复刻成探针，同一个严格加载器必须抓住它。
        //    少了这一段，上面的 PASS 可能只是「加载器根本没生效」的假绿。
        // ---------------------------------------------------------------
        Guard strict2 = new Guard(urls, T17.class.getClassLoader(), true);
        Class<?> oldStyle = strict2.loadClass("T17$OldStyle");
        Throwable probe = null;
        try {
            oldStyle.getMethod("register", RegisterPayloadHandlersEvent.class)
                    .invoke(null, new RegisterPayloadHandlersEvent());
        } catch (Throwable t) {
            probe = t.getCause() == null ? t : t.getCause();
        }
        check("【有效性自证】复刻 rc-73 写法（playToClient + 客户端方法引用）必须被同一个加载器抓住",
                probe != null && String.valueOf(probe).contains("invalid dist"),
                "探针没报错，说明本测试是假绿。denied=" + strict2.denied);
        boolean onClientChain = false;
        for (String d : strict2.denied) {
            if (d.equals("net.minecraft.client.gui.screens.Screen")
                    || d.equals("top.hmjmfabc.projector.client.gui.PlaneDialogScreen")) onClientChain = true;
        }
        check("【有效性自证】被拒的类正是玩家崩溃日志里的那条链（PlaneDialogScreen / Screen）",
                onClientChain, "被拒：" + strict2.denied);

        System.out.println();
        System.out.println("== " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " ==  " + pass + " passed");
        if (fail > 0) System.exit(1);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 一次登记的摘要。 */
    private static final class Reg {
        String id;
        String flow;
        List<String> protocols = new ArrayList<>();
        String version;
        Object handler;   // 已拆掉 MainThreadPayloadHandler 包装

        Reg(String id) {
            this.id = id;
        }
    }

    private static boolean isClientbound(String flow) {
        return "CLIENTBOUND".equals(flow);
    }

    private static List<String> strIds(List<?> ids) {
        List<String> out = new ArrayList<>();
        for (Object o : ids) out.add(String.valueOf(o));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static java.util.function.Function<ResourceLocation, Object> castLookup(Object f) {
        return (java.util.function.Function<ResourceLocation, Object>) f;
    }

    /** 反射读取 NetworkRegistry 已登记的包。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Reg> readRegistrations() throws Exception {
        Field f = NetworkRegistry.class.getDeclaredField("PAYLOAD_REGISTRATIONS");
        f.setAccessible(true);
        Map<ConnectionProtocol, Map<ResourceLocation, Object>> all =
                (Map<ConnectionProtocol, Map<ResourceLocation, Object>>) f.get(null);
        Map<String, Reg> out = new TreeMap<>();
        for (Map.Entry<ConnectionProtocol, Map<ResourceLocation, Object>> e : all.entrySet()) {
            for (Map.Entry<ResourceLocation, Object> e2 : e.getValue().entrySet()) {
                Object pr = e2.getValue();
                Reg r = new Reg(e2.getKey().toString());
                Object flowObj = pr.getClass().getMethod("flow").invoke(pr);
                r.flow = flowObj instanceof Optional<?> o
                        ? (o.isPresent() ? String.valueOf(o.get()) : "BIDIRECTIONAL")
                        : String.valueOf(flowObj);
                List<ConnectionProtocol> protos = (List<ConnectionProtocol>)
                        pr.getClass().getMethod("protocols").invoke(pr);
                for (ConnectionProtocol p : protos) r.protocols.add(String.valueOf(p));
                r.version = String.valueOf(pr.getClass().getMethod("version").invoke(pr));
                r.handler = unwrap(pr.getClass().getMethod("handler").invoke(pr));
                out.put(r.id, r);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void clearRegistrations() throws Exception {
        Field f = NetworkRegistry.class.getDeclaredField("PAYLOAD_REGISTRATIONS");
        f.setAccessible(true);
        Map<ConnectionProtocol, Map<ResourceLocation, Object>> all =
                (Map<ConnectionProtocol, Map<ResourceLocation, Object>>) f.get(null);
        for (Map<ResourceLocation, Object> m : all.values()) m.clear();
    }

    /** 拆掉 NeoForge 的 MainThreadPayloadHandler 包装，拿到我们自己传进去的处理函数。 */
    private static Object unwrap(Object handler) throws Exception {
        if (handler == null) return null;
        for (Class<?> c = handler.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getSimpleName().equals("MainThreadPayloadHandler")) {
                Method m = c.getMethod("handler");
                m.setAccessible(true);
                return m.invoke(handler);
            }
        }
        return handler;
    }

    private static void check(String what, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("PASS " + what);
        } else {
            fail++;
            System.out.println("FAIL " + what + (detail.isEmpty() ? "" : "   ← " + detail));
        }
    }

    /**
     * 模拟 RuntimeDistCleaner 的类加载器。
     *
     * <p><b>子优先</b>：本模组的类必须由它自己定义，否则父加载器里的旧副本会让
     * 「解析 client 类」这一步绕过本加载器 —— 那样测试就成了永远通过的假自检。</p>
     */
    private static final class Guard extends URLClassLoader {

        private final boolean strict;
        /** 被拒绝加载的类名（严格模式下应当为空）。 */
        final List<String> denied = new ArrayList<>();
        /** 实际由本加载器定义的类名。 */
        final Set<String> loaded = new LinkedHashSet<>();

        Guard(URL[] urls, ClassLoader parent, boolean strict) {
            super(urls, parent);
            this.strict = strict;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("top.hmjmfabc.projector.") || name.startsWith("T17")) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> c = findLoadedClass(name);
                    if (c == null) {
                        try {
                            c = findClass(name);
                            loaded.add(name);
                        } catch (ClassNotFoundException e) {
                            return super.loadClass(name, resolve);   // 资源/第三方包交给父加载器
                        }
                    }
                    if (resolve) resolveClass(c);
                    return c;
                }
            }
            if (isForbidden(name)) {
                denied.add(name);
                if (strict) {
                    throw new RuntimeException("Attempted to load class " + name.replace('.', '/')
                            + " for invalid dist DEDICATED_SERVER");
                }
            }
            return super.loadClass(name, resolve);
        }

        private static boolean isForbidden(String name) {
            for (String p : FORBIDDEN) {
                if (name.startsWith(p)) return true;
            }
            return false;
        }
    }

    /**
     * 【探针】rc-73 的注册写法原样复刻：把客户端类的方法引用直接交给 {@code playToClient}。
     *
     * <p>它只用于「有效性自证」：必须由 {@link Guard} 加载，并且必须被拒。
     * 永远不要在生产代码里这么写。</p>
     */
    public static final class OldStyle {
        public static void register(RegisterPayloadHandlersEvent event) {
            PayloadRegistrar registrar = event.registrar("2");
            registrar.playToClient(Payloads.OpenDialog.TYPE, Payloads.OpenDialog.CODEC,
                    ClientNetHandler::onOpenDialog);
        }
    }

    private T17() {
    }

    /** 让 javac 满意：Optional 只用于反射读到的类型。 */
    @SuppressWarnings("unused")
    private static void unusedTypeAnchors(Optional<PacketFlow> a, LinkedHashMap<String, Reg> b) {
    }
}

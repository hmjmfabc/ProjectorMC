package top.hmjmfabc.projector;

import top.hmjmfabc.projector.compat.fml.CrashReportCallables;
import top.hmjmfabc.projector.compat.fml.ISystemReportExtender;
import top.hmjmfabc.projector.compat.fml.ModList;

import java.util.ArrayList;
import java.util.List;

/**
 * 【27.2.2-hotfix-141 · 娱乐更新】缺件时的那段「嘲讽」—— 中英双语，
 * <b>写进日志</b>（latest.log / debug.log），能进崩溃报告时也顺手进去。
 *
 * <h2>为什么主要靠日志（用户 2026-10-03 实测反馈）</h2>
 * 「起步阶段就崩」这一类崩溃<b>根本不生成 Crash Report</b> ——
 * 报告是 {@code Minecraft.crash(...)} 那一刻才写盘的，而 MCEF / WaterMedia 在
 * 原生库上失败时往往死在更早的地方（游戏还没走到那一步就没了）。
 * 所以只往崩溃报告里塞是不够的：<b>玩家事后唯一能看到的东西是日志</b>。
 * 现在是在<b>主类构造期</b>（日志最前面、崩溃之前）就把这段话打出来 ——
 * 那时 latest.log / debug.log 都已经在写盘了，所以它一定在文件里。
 *
 * <h2>为什么放在投影仪里（而不是放在那两个兼容层里）</h2>
 * 会崩的场景是<b>「装了 MCEF / WaterMedia，却没装它们的安卓兼容层」</b>——
 * 也就是说，那两份兼容层<b>恰好不在 mods 里</b>，它们自己的代码<b>根本不会加载</b>，
 * 写在那儿等于白写（这一版我第一稿就写错了位置）。而投影仪是<b>一定在场</b>的那个。
 *
 * <h2>红字免责</h2>
 * 本类只贡献文字：<b>不拦异常、不改异常、不改退出码、不碰存档</b>。
 * 所有生成入口全程 try/catch，任何异常都当作「没缺件」——崩溃的时候再去惹事，
 * 是世界上最蠢的事。往文件里补写那一步（{@link #flushToLogFiles}）也是尽力而为。
 */
public final class CrashTaunt {

    /** WATERMeDIA: Android Bridge 的 modid。 */
    public static final String WATERMEDIA_BRIDGE_MODID = "watermedia_android_bridge";

    /** MCEF: Android Bridge 的 modid。 */
    public static final String MCEF_BRIDGE_MODID = "mcefdroid";

    /** 崩溃报告里那一行的标题（在报告里搜「嘲讽」或 "taunt" 就能找到）。 */
    public static final String LABEL = "投影仪 · 缺件嘲讽 / Projector · Missing-piece taunt";

    /** WATERMeDIA: Android Bridge 的 MC 百科页。 */
    public static final String WATERMEDIA_BRIDGE_WIKI = "https://www.mcmod.cn/class/31206.html";

    /** 日志里那段话的边框。 */
    private static final String RULE =
            "================================================================";

    private static boolean registered;
    private static boolean logged;

    private CrashTaunt() {
    }

    /**
     * 往日志里打那段话 + 注册崩溃报告条目。在<b>主类构造期</b>调用一次
     * （与那两行 ERROR 引导同一个时机、同一套判据）。
     * <p>日志是主要出口，崩溃报告是顺手加的（真生成了报告时多一份）。
     */
    public static void register() {
        try {
            List<String> missing = missingPieces();
            if (missing.isEmpty()) return;          // 没缺件：一个字都不说

            String block = logBlock(missing);

            // ① 日志（主要出口：起步崩溃时玩家唯一能看到的东西）
            if (!logged) {
                logged = true;
                Projector.LOGGER.error("\n{}", block);
                flushToLogFiles(block);
            }

            // ② 崩溃报告（次要出口）
            if (!registered) {
                registered = true;
                CrashReportCallables.registerCrashCallable(new ISystemReportExtender() {
                    @Override
                    public String getLabel() {
                        return LABEL;
                    }

                    /** 只在「真的缺件」时出现 —— 正常崩溃的报告不会被我们污染。 */
                    @Override
                    public boolean isActive() {
                        return !missingPieces().isEmpty();
                    }

                    @Override
                    public String get() {
                        return tauntText();
                    }
                });
            }
        } catch (Throwable t) {
            Projector.LOGGER.warn("[Projector] 缺件提示输出失败（已忽略，仅影响娱乐功能）：{}",
                    String.valueOf(t));
        }
    }

    /**
     * 缺了哪些兼容层（空的 = 没缺件）。
     * <p>判据与 {@link Projector} 里那两行 ERROR 完全一致：安卓 + 装了原模组 + 没装它的兼容层。
     */
    public static List<String> missingPieces() {
        return missingPieces(isAndroid(), isLoaded("watermedia"),
                isLoaded(WATERMEDIA_BRIDGE_MODID), isLoaded("mcef"), isLoaded(MCEF_BRIDGE_MODID));
    }

    /**
     * 判定本体（**纯函数**，不读任何全局状态）。
     * <p>单独抽出来是为了让验证套件能在没有游戏类路径的纯 JVM 里直接考它
     * （见 {@code tmp/v38/T38.java}）——「娱乐功能也不许靠肉眼推理」。
     */
    public static List<String> missingPieces(boolean android, boolean watermediaLoaded,
                                            boolean watermediaBridgeLoaded, boolean mcefLoaded,
                                            boolean mcefBridgeLoaded) {
        List<String> missing = new ArrayList<>(2);
        try {
            if (!android) return missing;                        // 桌面：与本次提示无关
            if (watermediaLoaded && !watermediaBridgeLoaded) {
                missing.add(WATERMEDIA_BRIDGE_MODID);
            }
            if (mcefLoaded && !mcefBridgeLoaded) {
                missing.add(MCEF_BRIDGE_MODID);
            }
        } catch (Throwable t) {
            return new ArrayList<>(0);
        }
        return missing;
    }

    // ------------------------------------------------------------------
    // 文案
    // ------------------------------------------------------------------

    /** 中文文案（用户 2026-10-03 亲手改写的那几句原样保留）。 */
    public static String tauntZh(List<String> missing) {
        StringBuilder sb = new StringBuilder();
        sb.append("恭喜你，成功把游戏搞崩了。\n");
        sb.append("而且在你看到这段话之前，启动日志最前面那几行 ERROR 已经警告过你了：\n");
        sb.append("「⚠ 不装它会导致游戏崩溃……缺了它，你就等着美美崩溃吧！」\n");
        sb.append("你显然没看。现在，如约而至：\n");
        sb.append('\n');
        sb.append("你这是典型的安卓环境，安卓设备，安卓报错，安卓崩溃！！！！！！！！\n");
        sb.append('\n');
        sb.append("真正的原因：你装的东西缺了配套的安卓兼容层。它们撞上原生库失败时，\n");
        sb.append("是把整个游戏一起带走的 —— 报错内容跟真正的原因八竿子打不着，\n");
        sb.append("而且起步阶段就崩的话，连 Crash Report 都不会生成，你只能看日志。\n");
        sb.append('\n');
        sb.append("缺的是：");
        for (String m : missing) {
            sb.append("\n  ✗ ").append(displayName(m)).append("（modid=").append(m).append("）");
        }
        sb.append('\n');
        sb.append('\n');
        sb.append("怎么把嘲讽关掉（按顺序做）：\n");
        sb.append("  1. 把上面缺的兼容模组装进 mods/ —— 要成对装：原模组 + 它的安卓兼容层；\n");
        sb.append("  2. 或者把对应的原模组从 mods/ 里移出去（用不到就别装）；\n");
        sb.append("  3. 然后重启游戏。\n");
        if (missing.contains(WATERMEDIA_BRIDGE_MODID)) {
            sb.append('\n');
            sb.append("WATERMeDIA: Android Bridge 的 MC 百科：").append(WATERMEDIA_BRIDGE_WIKI).append('\n');
        }
        sb.append('\n');
        sb.append("（这段嘲讽由投影仪自动生成，只在「确实缺件」时出现；\n");
        sb.append("  它不拦异常、不改退出码、更没碰你的存档 —— 存档是安全的，别慌。）");
        return sb.toString();
    }

    /** 英文版（同样的话，给不看中文的玩家）。 */
    public static String tauntEn(List<String> missing) {
        StringBuilder sb = new StringBuilder();
        sb.append("Congratulations, you crashed the game.\n");
        sb.append("And before you got here, the very first ERROR lines in your startup log\n");
        sb.append("already warned you: \"install it, or enjoy a lovely crash.\"\n");
        sb.append("You didn't read them. So here we are:\n");
        sb.append('\n');
        sb.append("This is a textbook Android environment: Android device, Android error, Android crash!\n");
        sb.append('\n');
        sb.append("The actual cause: what you installed is missing its companion Android compatibility layer.\n");
        sb.append("When those mods hit a native-library failure they take the whole game down with them --\n");
        sb.append("and the error message has nothing to do with the real cause. If it dies during startup,\n");
        sb.append("no Crash Report is even generated, so the log is all you get.\n");
        sb.append('\n');
        sb.append("Missing:");
        for (String m : missing) {
            sb.append("\n  x ").append(displayName(m)).append(" (modid=").append(m).append(")");
        }
        sb.append('\n');
        sb.append('\n');
        sb.append("How to stop the taunting (in order):\n");
        sb.append("  1. put the missing companion mod(s) into mods/ -- these come in pairs:\n");
        sb.append("     the original mod + its Android compatibility layer;\n");
        sb.append("  2. or remove the original mod from mods/ (don't install what you don't use);\n");
        sb.append("  3. then restart the game.\n");
        if (missing.contains(WATERMEDIA_BRIDGE_MODID)) {
            sb.append('\n');
            sb.append("WATERMeDIA: Android Bridge (MCMod wiki, Chinese): ")
                    .append(WATERMEDIA_BRIDGE_WIKI).append('\n');
        }
        sb.append('\n');
        sb.append("(Auto-generated by Projector, and only when something really is missing.\n");
        sb.append(" It does not catch or alter exceptions, does not change the exit code,\n");
        sb.append(" and did not touch your save -- your world is safe.)");
        return sb.toString();
    }

    /**
     * 日志里那一整段（含边框与语言选择）。语言由客户端配置
     * {@code taunt.lang} 决定（zh / en / both，默认 both）。
     */
    public static String logBlock(List<String> missing) {
        String lang = langSetting();
        StringBuilder sb = new StringBuilder();
        sb.append(RULE).append('\n');
        sb.append("[Projector] ⚠ 缺件警告 / MISSING COMPANION MOD ⚠ —— 娱乐向，但内容是认真的\n");
        sb.append("[Projector] 缺的是 / Missing: ").append(String.join(", ", missing)).append('\n');
        if ("zh".equals(lang) || "both".equals(lang)) {
            sb.append('\n').append(tauntZh(missing)).append('\n');
        }
        if ("en".equals(lang) || "both".equals(lang)) {
            sb.append('\n').append(tauntEn(missing)).append('\n');
        }
        sb.append(RULE);
        return sb.toString();
    }

    /** 崩溃报告里的那一行。 */
    public static String tauntText() {
        try {
            List<String> missing = missingPieces();
            return missing.isEmpty() ? "" : tauntTextFor(missing);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 崩溃报告文案（**纯函数**：只依赖传入的缺失清单）。
     * <p>报告是按 {@code 标签: 值} 对齐的，多行会被折得很难看，所以这里用
     * 「中文… || ENGLISH || English…」一行流。同样抽出来是为了让 {@code tmp/v38} 逐句断言。
     *
     * @param missing 缺失的兼容层 modid 列表；空 ⇒ 返回空串
     */
    public static String tauntTextFor(List<String> missing) {
        try {
            if (missing == null || missing.isEmpty()) return "";

            StringBuilder sb = new StringBuilder();
            sb.append("恭喜你，成功把游戏搞崩了。 | ");
            sb.append("而且在你看到这段话之前，启动日志最前面那几行 ERROR 已经警告过你了：");
            sb.append("「⚠ 不装它会导致游戏崩溃……缺了它，你就等着美美崩溃吧！」你显然没看。现在，如约而至。 | ");
            sb.append("你这是典型的安卓环境，安卓设备，安卓报错，安卓崩溃！！！！！！！！ | ");
            sb.append("真正的原因：你装的东西缺了配套的安卓兼容层 —— ");
            sb.append("它们撞上原生库失败时会把整个游戏一起带走，报错内容跟真正的原因八竿子打不着。 | ");
            sb.append("缺的是：");
            for (String m : missing) {
                sb.append("「").append(displayName(m)).append("」（modid=").append(m).append("）").append("；");
            }
            sb.append(" | ");
            sb.append("怎么把嘲讽关掉：① 把上面缺的兼容模组装进 mods/（要成对装：原模组 + 它的安卓兼容层）；");
            sb.append("② 或者把对应的原模组从 mods/ 里移出去（用不到就别装）；③ 重启游戏。 | ");
            if (missing.contains(WATERMEDIA_BRIDGE_MODID)) {
                sb.append("WATERMeDIA: Android Bridge 的 MC 百科：").append(WATERMEDIA_BRIDGE_WIKI)
                        .append(" | ");
            }
            sb.append("（这段嘲讽由投影仪自动生成，只在「确实缺件」时出现；");
            sb.append("它不拦异常、不改退出码、更没碰你的存档 —— 存档是安全的，别慌。）");
            sb.append("  || ENGLISH ||  ");
            sb.append("Congratulations, you crashed the game. The actual cause: what you installed is missing "
                    + "its companion Android compatibility layer -- when those mods hit a native-library "
                    + "failure they take the whole game down with them, and the error message has nothing "
                    + "to do with the real cause. Missing: ");
            for (String m : missing) {
                sb.append(displayName(m)).append(" (modid=").append(m).append("); ");
            }
            sb.append("How to stop the taunting: 1) put the missing companion mod(s) into mods/ "
                    + "(the original mod + its Android layer); 2) or remove the original mod; 3) restart. ");
            sb.append("Auto-generated by Projector, only when something really is missing; "
                    + "it does not catch or alter exceptions, does not change the exit code, "
                    + "and did not touch your save.");
            return sb.toString();
        } catch (Throwable t) {
            // 判定失败就当没缺件：宁可不说，也不要在崩溃报告里胡说
            return "";
        }
    }

    // ------------------------------------------------------------------
    // 兜底：直接把这段话补写进 latest.log / debug.log
    // ------------------------------------------------------------------

    /**
     * 把这段话<b>追加</b>进 {@code logs/latest.log} 与 {@code logs/debug.log}。
     *
     * <p>为什么还要这一手：正常路径下 log4j 已经会把它写进这两个文件（我们用的是
     * {@code LOGGER.error}），但起步阶段的崩溃可能发生在最后一次刷盘的间隙里。
     * 直接补写一次，代价是几毫秒 + 一段可能重复的文字，换来的是「玩家一定能看到」。</p>
     *
     * <p>纯 JDK 实现（只在找目录时用一次 FML 的 GAMEDIR），全程 try/catch：写不进去就算了。</p>
     */
    public static void flushToLogFiles(String block) {
        try {
            java.nio.file.Path dir = logDir();
            if (dir == null) return;
            String text = "\n" + block + "\n";
            for (String name : new String[]{"latest.log", "debug.log"}) {
                try {
                    java.nio.file.Path f = dir.resolve(name);
                    if (!java.nio.file.Files.isRegularFile(f)) continue;
                    java.nio.file.Files.write(f, text.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            java.nio.file.StandardOpenOption.APPEND);
                } catch (Throwable ignored) {
                    // 单个文件失败不影响另一个
                }
            }
        } catch (Throwable ignored) {
            // 纯兜底，失败无声
        }
    }

    /** 找 logs 目录：优先 FML 的游戏目录，取不到就用工作目录下的 logs/。 */
    private static java.nio.file.Path logDir() {
        try {
            java.nio.file.Path p = top.hmjmfabc.projector.compat.fml.loading.FMLPaths.GAMEDIR.get().resolve("logs");
            if (java.nio.file.Files.isDirectory(p)) return p;
        } catch (Throwable ignored) {
            // 退回下面那条
        }
        try {
            java.nio.file.Path p = java.nio.file.Paths.get("logs").toAbsolutePath();
            return java.nio.file.Files.isDirectory(p) ? p : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    /**
     * 语言设置：{@code zh} / {@code en} / {@code both}（默认 both）。
     * <p>读客户端配置 {@code taunt.lang}；配置还没加载完（构造期就是那个时机）时
     * 用 {@code -Dprojector.taunt.lang=…} 兜底，都没有就 both。
     */
    private static String langSetting() {
        try {
            String v = System.getProperty("projector.taunt.lang");
            if (v != null && !v.isBlank()) return normalizeLang(v);
        } catch (Throwable ignored) {
            // 往下走
        }
        try {
            String v = ProjectorConfig.INSTANCE.tauntLang.get();
            if (v != null && !v.isBlank()) return normalizeLang(v);
        } catch (Throwable ignored) {
            // 配置没加载：用默认
        }
        return "both";
    }

    private static String normalizeLang(String v) {
        String s = v.trim().toLowerCase(java.util.Locale.US);
        if (s.startsWith("zh") || s.startsWith("cn")) return "zh";
        if (s.startsWith("en")) return "en";
        return "both";
    }

    private static boolean isLoaded(String modid) {
        try {
            return ModList.get().isLoaded(modid);
        } catch (Throwable t) {
            return false;
        }
    }

    /** modid → 玩家认得的名字（报告里两个都给，方便直接去搜）。 */
    private static String displayName(String modid) {
        if (WATERMEDIA_BRIDGE_MODID.equals(modid)) return "WATERMeDIA: Android Bridge";
        if (MCEF_BRIDGE_MODID.equals(modid)) return "MCEF: Android Bridge";
        return modid;
    }

    /**
     * 是不是安卓。判据与主类里那套一致（三条任一命中即算）：
     * 崩溃时 {@code Class.forName} 也可能失败，所以全部包在 try 里。
     */
    private static boolean isAndroid() {
        try {
            Class.forName("android.os.Build");
            return true;
        } catch (Throwable ignored) {
            // 看下一条
        }
        try {
            String v = System.getProperty("java.vm.version", "");
            if (v.contains("ART") || v.contains("Dalvik")) return true;
            return System.getProperty("java.vendor", "").contains("Android");
        } catch (Throwable t) {
            return false;
        }
    }
}

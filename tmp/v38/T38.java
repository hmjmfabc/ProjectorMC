/*
 * 投影仪 / Projector —— 纯逻辑验证（tmp/v38）
 * 覆盖：缺件嘲讽（日志 + 崩溃报告）与平面对话框的彩蛋
 *
 * 为什么单独一套：嘲讽跑在**崩溃路径**上、彩蛋跑在**点击计数**上 ——
 * 两者平时都不执行，出事时又没法调试。所以判定与文案必须在这里逐个场景钉死。
 *
 * 跑法：bash tmp/run-verify.sh tmp/v38 T38
 */

import top.hmjmfabc.projector.CrashTaunt;

import java.util.List;

public final class T38 {
    private static int passed = 0;
    private static final java.util.List<String> failures = new java.util.ArrayList<>();

    public static void main(String[] args) throws Exception {
        section("① 判定：什么情况下才该开口");
        // 桌面：什么都不说（装了什么都不管我们的事）
        check("桌面 + 什么都没有 ⇒ 不开口", CrashTaunt.missingPieces(false, false, false, false, false).isEmpty());
        check("桌面 + 装了 MCEF 没装兼容层 ⇒ 仍然不开口（只管安卓）",
                CrashTaunt.missingPieces(false, false, false, true, false).isEmpty());

        // 安卓：三件套齐全 ⇒ 不开口
        check("安卓 + WaterMedia 与兼容层都在 ⇒ 不开口",
                CrashTaunt.missingPieces(true, true, true, false, false).isEmpty());
        check("安卓 + MCEF 与兼容层都在 ⇒ 不开口",
                CrashTaunt.missingPieces(true, false, false, true, true).isEmpty());
        check("安卓 + 什么都没装 ⇒ 不开口（用不到就别装，不嘲讽）",
                CrashTaunt.missingPieces(true, false, false, false, false).isEmpty());

        // 安卓：缺件 ⇒ 开口，且点名要准
        List<String> a = CrashTaunt.missingPieces(true, false, false, true, false);
        check("安卓 + 装了 MCEF 没装它的兼容层 ⇒ 点名 mcefdroid",
                a.size() == 1 && a.get(0).equals(CrashTaunt.MCEF_BRIDGE_MODID));

        List<String> b = CrashTaunt.missingPieces(true, true, false, false, false);
        check("安卓 + 装了 WaterMedia 没装它的兼容层 ⇒ 点名 watermedia_android_bridge",
                b.size() == 1 && b.get(0).equals(CrashTaunt.WATERMEDIA_BRIDGE_MODID));

        List<String> c = CrashTaunt.missingPieces(true, true, false, true, false);
        check("两个都缺 ⇒ 两条都点名", c.size() == 2
                && c.contains(CrashTaunt.WATERMEDIA_BRIDGE_MODID)
                && c.contains(CrashTaunt.MCEF_BRIDGE_MODID));

        check("只装了兼容层、没装原模组 ⇒ 不开口（兼容层自己会安静地空转）",
                CrashTaunt.missingPieces(true, false, true, false, true).isEmpty());

        section("② 文案：该有的必须有");
        String text = textFor(true, true, false, true, false);
        check("非空", text != null && !text.isEmpty());
        check("含「恭喜你，成功把游戏搞崩了」", text.contains("恭喜你，成功把游戏搞崩了"));
        check("含「安卓环境，安卓设备，安卓报错，安卓崩溃」",
                text.contains("安卓环境，安卓设备，安卓报错，安卓崩溃"));
        check("含「美美崩溃吧」（与日志里那几行同一个口径）", text.contains("美美崩溃吧"));
        check("点名 watermedia_android_bridge", text.contains(CrashTaunt.WATERMEDIA_BRIDGE_MODID));
        check("点名 mcefdroid", text.contains(CrashTaunt.MCEF_BRIDGE_MODID));
        check("含 MC 百科链接（缺 WaterMedia 兼容层时给）",
                text.contains(CrashTaunt.WATERMEDIA_BRIDGE_WIKI));
        check("含三步解决办法", text.contains("怎么把嘲讽关掉"));
        check("写明没碰存档（玩家最担心的）", text.contains("存档是安全的"));
        check("写明不拦异常、不改退出码",
                text.contains("不拦异常") && text.contains("不改退出码"));

        String onlyMcef = textFor(true, false, false, true, false);
        check("只缺 MCEF 兼容层时不硬塞 WaterMedia 的百科链接",
                !onlyMcef.contains(CrashTaunt.WATERMEDIA_BRIDGE_WIKI));

        section("③ 崩溃路径上的安全性");
        check("没缺件时文案为空串（条目 isActive=false，报告里不出现）",
                textFor(true, false, true, false, true).isEmpty());
        check("桌面文案为空串", textFor(false, true, false, true, false).isEmpty());
        // 单行长度：崩溃报告按「标签: 值」对齐，太长会被换行，但绝不能含制表符/换行符
        check("文案不含换行符（写进系统报告是一行）", !text.contains("\n") && !text.contains("\r"));
        check("文案不含制表符", !text.contains("\t"));

        section("④ 日志双语（latest.log / debug.log 里那段）");
        String zh = CrashTaunt.tauntZh(c);
        String en = CrashTaunt.tauntEn(c);
        check("中文含「恭喜你，成功把游戏搞崩了」", zh.contains("恭喜你，成功把游戏搞崩了"));
        check("中文含「安卓崩溃！！！！！！！！」", zh.contains("安卓崩溃！！！！！！！！"));
        check("中文含两个 modid", zh.contains(CrashTaunt.MCEF_BRIDGE_MODID)
                && zh.contains(CrashTaunt.WATERMEDIA_BRIDGE_MODID));
        check("中文含三步解决办法", zh.contains("怎么把嘲讽关掉"));
        check("中文写明存档是安全的", zh.contains("存档是安全的"));
        check("英文含 Congratulations, you crashed the game", en.contains("Congratulations, you crashed the game"));
        check("英文含 Android crash", en.contains("Android crash"));
        check("英文含两个 modid", en.contains(CrashTaunt.MCEF_BRIDGE_MODID)
                && en.contains(CrashTaunt.WATERMEDIA_BRIDGE_MODID));
        check("英文含 How to stop the taunting", en.contains("How to stop the taunting"));
        check("英文写明 your world is safe", en.contains("your world is safe"));

        String block = CrashTaunt.logBlock(c);
        check("日志块含中英两段（默认 both）",
                block.contains("恭喜你，成功把游戏搞崩了") && block.contains("Congratulations"));
        check("日志块含缺件清单那一行", block.contains("Missing:"));
        check("日志块有多行（不是一行流）", block.split("\\n", -1).length > 20);
        check("日志块首尾是边框", block.startsWith("=====") && block.endsWith("====="));

        section("⑤ 彩蛋：用户指定的文案必须一字不差（源码级断言）");
        java.nio.file.Path egg = java.nio.file.Path.of(System.getProperty("mcefdroid.projectDir",
                System.getProperty("user.dir", "."))).resolve(
                "src/main/java/top/hmjmfabc/projector/client/gui/EasterEggScreen.java");
        if (java.nio.file.Files.isReadable(egg)) {
            String src = new String(java.nio.file.Files.readAllBytes(egg),
                    java.nio.charset.StandardCharsets.UTF_8);
            check("彩蛋正文 = Ojang赶紧把The Sift加入原版😡🤚😡🤚😡🤚",
                    src.contains("Ojang赶紧把The Sift加入原版😡🤚😡🤚😡🤚"));
            check("按钮 = 末地：问了吗？", src.contains("末地：问了吗？"));
            check("聊天栏回复 = [Mojang Studios] + 感谢您的反馈。我们暂时不考虑删除末地。",
                    src.contains("感谢您的反馈。我们暂时不考虑删除末地。")
                            && src.contains("[Mojang Studios] "));
            check("点确定会退出所有界面（setScreen(null)）", src.contains("setScreen(null)"));
            check("点确定会用系统默认浏览器打开那个视频",
                    src.contains("https://www.bilibili.com/video/BV1GJ411x7h7")
                            && src.contains("openUri"));
            check("回复只在本机显示（不走服务器）", !src.contains("PacketDistributor")
                    && !src.contains("sendSystemMessage"));
        } else {
            check("找不到 EasterEggScreen.java", false);
        }
        java.nio.file.Path dlg = java.nio.file.Path.of(System.getProperty("mcefdroid.projectDir",
                System.getProperty("user.dir", "."))).resolve(
                "src/main/java/top/hmjmfabc/projector/client/gui/PlaneDialogScreen.java");
        if (java.nio.file.Files.isReadable(dlg)) {
            String src = new String(java.nio.file.Files.readAllBytes(dlg),
                    java.nio.charset.StandardCharsets.UTF_8);
            check("对话框左上角画了版本号 HUD", src.contains("renderVersionHud"));
            check("连点阈值 = 7", src.contains("EGG_CLICKS_NEEDED = 7"));
            check("命中框与画出来的是同一份（record 落点，不重算）",
                    src.contains("hitVersionHud") && src.contains("eggHudX = x"));
            check("点击计数用墙钟（不用会停/会返回 0 的游戏刻）",
                    src.contains("EGG_TIMEOUT_MS = 1500L") && src.contains("System.nanoTime()"));
            check("计数带容差命中框（手机上点得准）",
                    src.contains("EGG_HIT_SLOP") && src.contains("hitVersionHud"));
        } else {
            check("找不到 PlaneDialogScreen.java", false);
        }

        section("⑥ 只依赖 JDK（能在崩溃早期安全跑）");
        // 本套件编译时 classpath 里**没有** Minecraft / NeoForge，能编出来就已经证明：
        // CrashTaunt 的静态初始化与 tauntText/missingPieces 都不触碰游戏类。
        check("CrashTaunt 在本 classpath 下可加载并调用（无 Minecraft/NeoForge 依赖）",
                text != null);

        System.out.println();
        for (String f : failures) {
            System.out.println("  \u274c " + f);
        }
        // 这一行是 tmp/run-all.sh 认的结果行（格式与其它套件保持一致）
        System.out.println("== " + (failures.isEmpty() ? "ALL PASS" : "FAILED") + " ==  "
                + passed + " passed, " + failures.size() + " failed");
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    /** 只关心文案时用这个：直接从判定拿文本。 */
    private static String textFor(boolean android, boolean watermedia, boolean watermediaBridge,
                                  boolean mcef, boolean mcefBridge) {
        List<String> missing = CrashTaunt.missingPieces(android, watermedia, watermediaBridge,
                mcef, mcefBridge);
        if (missing.isEmpty()) return "";
        return CrashTaunt.tauntTextFor(missing);
    }

    private static void section(String name) {
        System.out.println();
        System.out.println("── " + name + " ──");
    }

    private static void check(String what, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failures.add(what);
            System.out.println("  \u2717 " + what);
        }
    }
}

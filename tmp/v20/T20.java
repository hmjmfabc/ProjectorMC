import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * T20 ——【rc-80】「平面内容保护按钮极其不灵敏、几乎点不动」。
 *
 * <p>根因是**权限在构造期取了一次快照**：{@code PlaneDialogScreen} 的
 * {@code admin/creator/canManage} 都是构造时算一次的字段，而 {@code plane.creator}
 * 由服务端**异步同步**。对话框开得早一点就拿到 {@code creator=null}
 * ⇒ {@code canManage} 永久为 false ⇒ 所有「canManage」门控的按钮
 * （内容保护 / 重新圈选 / 转让创建者 / 删除平面）在整个对话框生命周期内都点不动。
 * 数据碰巧先到了就能点，于是表现成「极其不灵敏」。</p>
 *
 * <p>顺带修的第二个问题：内容保护按钮点击后 {@code rebuildWidgets()} 读的是
 * **服务端广播回来之前**的旧值，标签看起来没变 ⇒ 玩家以为没点上 ⇒ 反复点。
 * 现在点击时先**乐观本地翻转**。</p>
 *
 * <p>这个测试断言的是**结构不变量**（源码级，去注释后匹配）：</p>
 * <ol>
 *   <li>门控不许再用快照字段 {@code .active = canManage;}；</li>
 *   <li>必须存在按需重算的 {@code canManageNow()} 与 {@code refreshGates()}；</li>
 *   <li>{@code render()} 里必须调用 {@code refreshGates()}（否则数据后到也不会修好按钮）；</li>
 *   <li>内容保护按钮的点击回调必须做乐观本地翻转；</li>
 *   <li>点击必须留一行日志（下次报「点不动」时能判断点击有没有到按钮）。</li>
 * </ol>
 */
public final class T20 {

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        String src = read("src/main/java/top/hmjmfabc/projector/client/gui/PlaneDialogScreen.java");
        check("能读到 PlaneDialogScreen 源码", src != null, "文件读不到");

        check("门控不再使用构造期快照字段（没有 `.active = canManage;`）",
                src != null && !src.contains(".active = canManage;"),
                "仍然用 canManage 快照门控按钮 —— 平面数据晚到就永久点不动");
        check("存在按需重算的 canManageNow()", src != null && src.contains("private boolean canManageNow()"), "");
        check("存在 refreshGates()", src != null && src.contains("private void refreshGates()"), "");
        check("render() 里调用了 refreshGates()（数据后到能自动修好按钮）",
                src != null && src.contains("refreshGates();"), "");
        check("内容保护按钮点击时做乐观本地翻转",
                src != null && src.contains("plane.protectContent = next;"),
                "没有乐观翻转 ⇒ 标签要等服务端往返，看着像「点了没反应」");
        check("内容保护点击留了日志",
                src != null && src.contains("内容保护：提交"), "");
        check("权限刷新时留了日志（能判断到底是不是权限没到位）",
                src != null && src.contains("平面对话框权限刷新"), "");

        System.out.println();
        System.out.println("== " + (fail == 0 ? "ALL PASS" : (fail + " FAILED")) + " ==  " + pass + " passed");
        if (fail > 0) System.exit(1);
    }

    /** 读源码并去掉注释：静态检查必须看代码，不能被说明性注释误判（T19 踩过）。 */
    private static String read(String rel) {
        try {
            Path p = Path.of(rel);
            if (!Files.isRegularFile(p)) return null;
            List<String> lines = new ArrayList<>(Files.readAllLines(p));
            StringBuilder sb = new StringBuilder();
            boolean block = false;
            for (String line : lines) {
                String l = line;
                if (block) {
                    int e = l.indexOf("*/");
                    if (e < 0) continue;
                    l = l.substring(e + 2);
                    block = false;
                }
                int bs = l.indexOf("/*");
                if (bs >= 0) {
                    int be = l.indexOf("*/", bs + 2);
                    if (be < 0) {
                        l = l.substring(0, bs);
                        block = true;
                    } else {
                        l = l.substring(0, bs) + l.substring(be + 2);
                    }
                }
                int ls = l.indexOf("//");
                if (ls >= 0) l = l.substring(0, ls);
                sb.append(l).append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
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

    private T20() {
    }
}

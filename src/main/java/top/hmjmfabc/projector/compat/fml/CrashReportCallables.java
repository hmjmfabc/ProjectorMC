package top.hmjmfabc.projector.compat.fml;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 【Fabric 移植层】{@code top.hmjmfabc.projector.compat.fml.CrashReportCallables} 的替身。
 *
 * <p>原模组用它往崩溃报告里加一段"缺件嘲讽"。Fabric 没有这套 API，这里自己存一份，
 * 由 {@code top.hmjmfabc.projector.fabric.mixin.CrashReportMixin} 在生成报告时追加。</p>
 */
public final class CrashReportCallables {

    private static final List<ISystemReportExtender> CALLABLES = new CopyOnWriteArrayList<>();

    private CrashReportCallables() {
    }

    public static void registerCrashCallable(ISystemReportExtender callable) {
        if (callable == null) return;
        CALLABLES.add(callable);
    }

    /**
     * 当前"生效中"的那几段。
     *
     * <p>每一段都要单独 try/catch：崩溃报告是最后能写给人看的地方，
     * 里面再抛异常就等于什么都没留下。</p>
     */
    public static List<String> activeSections() {
        List<String> out = new ArrayList<>(2);
        for (ISystemReportExtender callable : CALLABLES) {
            try {
                if (!callable.isActive()) continue;
                String label = callable.getLabel();
                String text = callable.get();
                if (text == null || text.isEmpty()) continue;
                out.add(label == null || label.isEmpty() ? text : label + ": " + text);
            } catch (Throwable ignored) {
                // 嘲讽功能出错不能影响崩溃报告本身
            }
        }
        return out;
    }
}

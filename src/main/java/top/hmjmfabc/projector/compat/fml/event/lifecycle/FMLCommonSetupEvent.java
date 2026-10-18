package top.hmjmfabc.projector.compat.fml.event.lifecycle;

/**
 * 【Fabric 移植层】{@code FMLCommonSetupEvent} 的替身。
 *
 * <p>由 {@code ProjectorFabric} 在模组初始化时立刻分发一次（两端都会）。
 * {@code enqueueWork} 在原版是把任务丢回主线程；这里的语义是"尽快在主线程执行"。</p>
 */
public final class FMLCommonSetupEvent {

    /**
     * 把一个任务排到主线程（Fabric 的模组初始化本身就在主线程上，直接跑即可）。
     *
     * <p>⚠ 与原版的小差别：NeoForge 的 CommonSetup 是并行阶段，enqueueWork 一定会延后；
     * 这里立即执行。<b>对本模组没有影响</b>（它唯一的用法是打一行日志）。</p>
     */
    public void enqueueWork(Runnable runnable) {
        if (runnable != null) runnable.run();
    }
}

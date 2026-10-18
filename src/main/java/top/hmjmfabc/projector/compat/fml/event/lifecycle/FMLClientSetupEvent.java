package top.hmjmfabc.projector.compat.fml.event.lifecycle;

import net.minecraft.client.Minecraft;

/**
 * 【Fabric 移植层】{@code FMLClientSetupEvent} 的替身。
 *
 * <p>原版是"客户端初始化"阶段，{@code enqueueWork} 把任务排到主线程。
 * 本模组在这个任务里做：建媒体目录、装载内置字体、注册纯白/光斑纹理、自检字形图集、
 * 迁移旧按键（都是<b>必须等 Minecraft 实例可用之后</b>的动作）。</p>
 */
public final class FMLClientSetupEvent {

    /**
     * 排到主线程执行。
     *
     * <p>Fabric 的 {@code onInitializeClient} 阶段 Minecraft 实例已经在构造中，
     * 但部分子系统（纹理管理器等）还没就绪 ⇒ 这里统一走 {@code Minecraft.execute}，
     * 让它排到第一个 tick 的主线程任务队列里执行，比"就地跑"更接近 NeoForge 的时机。</p>
     */
    public void enqueueWork(Runnable runnable) {
        if (runnable == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            runnable.run();
            return;
        }
        mc.execute(runnable);
    }
}

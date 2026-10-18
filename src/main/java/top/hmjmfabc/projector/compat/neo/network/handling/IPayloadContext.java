package top.hmjmfabc.projector.compat.neo.network.handling;

import net.minecraft.world.entity.player.Player;

/**
 * 【Fabric 移植层】{@code top.hmjmfabc.projector.compat.neo.network.handling.IPayloadContext} 的替身。
 *
 * <p>本模组只用到两件事：{@link #player()}（服务端侧拿到发包的玩家）与
 * {@link #enqueueWork(Runnable)}（切回主线程再碰界面 / 渲染 / 世界对象）。</p>
 *
 * <p>⚠ <b>线程语义</b>：Fabric 的 payload 处理器在网络线程上被调用，
 * 所以 {@code enqueueWork} 必须真的切线程。若已经在主线程上则就地执行
 * （避免"延迟一个 tick"改变原模组的时序）。</p>
 */
public interface IPayloadContext {

    /** 发这个包的玩家（客户端收到的服务端包，这里是本机玩家；没有则返回 null）。 */
    Player player();

    /** 切到主线程执行。 */
    void enqueueWork(Runnable runnable);
}

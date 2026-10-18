package top.hmjmfabc.projector.compat.event;

import java.util.function.Consumer;

/**
 * 【Fabric 移植层】{@code top.hmjmfabc.projector.compat.event.IEventBus} 的替身。
 *
 * <p>两件事：</p>
 * <ol>
 *   <li>{@link #register(Object)}：登记一个带 {@link SubscribeEvent} 方法的对象（对应
 *       {@code NeoForge.EVENT_BUS.register(new ClientInputHandler())} 这种写法）；</li>
 *   <li>{@link #addListener(Class, Consumer)}：登记一个 lambda / 方法引用。</li>
 * </ol>
 *
 * <p>⚠ <b>与 NeoForge 的唯一差别</b>：NeoForge 的 {@code addListener(Consumer&lt;T&gt;)} 只有一个参数，
 * 靠泛型擦除后的元数据猜事件类型；Java 的 lambda 类里只有 {@code accept(Object)}，
 * <b>猜不出来</b>（实测：{@code getDeclaredMethods()} 里只有一个 {@code accept(Object)}）。
 * 所以这里改成显式传 {@code Class} —— 类型写错会直接编译不过，比"猜"更安全。</p>
 */
public interface IEventBus {

    /** 登记一个对象的全部 {@link SubscribeEvent} 方法（幂等性由调用方保证）。 */
    void register(Object target);

    /** 登记一个监听器。{@code eventType} 必须是 {@code listener} 的参数类型。 */
    <T> void addListener(Class<T> eventType, Consumer<T> listener);

    /** 分发一个事件（内部使用：由 Fabric 的回调转发过来）。 */
    <T> void post(T event);

    /** 当前登记的监听器数量（只用于启动日志自检）。 */
    int size();
}

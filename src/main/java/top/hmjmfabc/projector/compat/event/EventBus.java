package top.hmjmfabc.projector.compat.event;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 【Fabric 移植层】极简事件总线（替身 {@code top.hmjmfabc.projector.compat.event.EventBus}）。
 *
 * <p>只做本项目用得到的两件事：按参数类型分发、监听器异常不外泄。
 * <b>不做</b>优先级、取消传播、并行分发 —— 原模组的取消语义都写在事件对象自己身上
 * （{@code event.setCanceled(true)} 由处理器自己读），不需要总线参与。</p>
 */
public final class EventBus implements IEventBus {

    /** 一条登记：事件类型 + 调用方式。 */
    private record Listener(Class<?> eventType, Object target, Method method, Consumer<Object> consumer) {
        void fire(Object event) {
            if (consumer != null) {
                consumer.accept(event);
                return;
            }
            try {
                method.invoke(target, event);
            } catch (java.lang.reflect.InvocationTargetException e) {
                // 与 NeoForge 一致：监听器自己抛异常不拖垮整条分发链，但要留痕
                org.slf4j.LoggerFactory.getLogger("projector/eventbus")
                        .error("[Projector] 事件处理器异常 {}#{}", target.getClass().getName(),
                                method.getName(), e.getCause());
            } catch (Throwable t) {
                org.slf4j.LoggerFactory.getLogger("projector/eventbus")
                        .error("[Projector] 事件处理器无法调用 {}#{}", target.getClass().getName(),
                                method.getName(), t);
            }
        }
    }

    private final String name;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public EventBus(String name) {
        this.name = name;
    }

    @Override
    public void register(Object target) {
        int found = 0;
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.isAnnotationPresent(SubscribeEvent.class)) continue;
                if (m.getParameterCount() != 1) {
                    org.slf4j.LoggerFactory.getLogger("projector/eventbus").warn(
                            "[Projector] @SubscribeEvent 方法 {}.{} 的参数不是 1 个，已忽略",
                            c.getName(), m.getName());
                    continue;
                }
                if (m.isBridge() || Modifier.isStatic(m.getModifiers())) continue;
                Class<?> type = m.getParameterTypes()[0];
                try {
                    m.setAccessible(true);
                } catch (Throwable ignored) {
                    // 公开方法不需要；私有方法在 Java 21 + 同一模块内一般也能打开
                }
                listeners.add(new Listener(type, target, m, null));
                found++;
            }
        }
        if (found == 0) {
            org.slf4j.LoggerFactory.getLogger("projector/eventbus").warn(
                    "[Projector] {} 里没有任何 @SubscribeEvent 方法（登记是空操作）", target.getClass().getName());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> void addListener(Class<T> eventType, Consumer<T> listener) {
        java.util.Objects.requireNonNull(eventType, "eventType");
        java.util.Objects.requireNonNull(listener, "listener");
        listeners.add(new Listener(eventType, listener, null, (Consumer<Object>) listener));
    }

    @Override
    public <T> void post(T event) {
        if (event == null) return;
        for (Listener l : listeners) {
            if (l.eventType().isInstance(event)) {
                l.fire(event);
            }
        }
    }

    /** 当前登记数（自检/日志用）。 */
    public int size() {
        return listeners.size();
    }

    @Override
    public String toString() {
        return "EventBus[" + name + ", " + listeners.size() + " listeners]";
    }
}

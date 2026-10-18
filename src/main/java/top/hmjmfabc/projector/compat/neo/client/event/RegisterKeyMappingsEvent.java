package top.hmjmfabc.projector.compat.neo.client.event;

import net.minecraft.client.KeyMapping;

import java.util.ArrayList;
import java.util.List;

/**
 * 【Fabric 移植层】{@code top.hmjmfabc.projector.compat.neo.client.event.RegisterKeyMappingsEvent} 的替身。
 *
 * <p>本模组用它登记 U / Esc / O / I 四个键。Fabric 侧对应
 * {@code KeyBindingHelper.registerKeyBinding}。</p>
 */
public final class RegisterKeyMappingsEvent {

    private final List<KeyMapping> pending = new ArrayList<>();

    /** 登记一个按键（真正的注册在事件处理完之后统一做）。 */
    public void register(KeyMapping mapping) {
        if (mapping != null) pending.add(mapping);
    }

    public List<KeyMapping> pending() {
        return pending;
    }
}

package top.hmjmfabc.projector.compat.fml;

import net.fabricmc.loader.api.FabricLoader;

/**
 * 【Fabric 移植层】{@code top.hmjmfabc.projector.compat.fml.ModList} 的替身。
 *
 * <p>本模组只用它做一件事：判断某个可选前置（WaterMedia / MCEF / 两个安卓兼容层）
 * 装了没有。<b>只按 modid 判断，绝不引用它们的类</b>（许可约束见 AGENTS.md §1.6）。</p>
 */
public final class ModList {

    private static final ModList INSTANCE = new ModList();

    private ModList() {
    }

    /** 恒非 null（原代码里有 {@code ModList.get() != null} 的保护性判断，这里保持兼容）。 */
    public static ModList get() {
        return INSTANCE;
    }

    public boolean isLoaded(String modId) {
        if (modId == null || modId.isEmpty()) return false;
        try {
            return FabricLoader.getInstance().isModLoaded(modId);
        } catch (Throwable t) {
            return false;
        }
    }
}

package top.hmjmfabc.projector.compat.fml.loading;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * 【Fabric 移植层】{@code top.hmjmfabc.projector.compat.fml.loading.FMLPaths} 的替身。
 *
 * <p>原模组用到 {@code FMLPaths.GAMEDIR.get()}（缺件嘲讽要把话写进 {@code logs/}）。
 * Fabric 侧就是 {@code FabricLoader.getGameDir()}。</p>
 */
public final class FMLPaths {

    /** 游戏根目录（{@code .minecraft} 那一层）。 */
    public static final Supplier<Path> GAMEDIR = FabricLoader.getInstance()::getGameDir;

    private FMLPaths() {
    }
}

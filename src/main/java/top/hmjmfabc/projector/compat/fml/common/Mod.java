package top.hmjmfabc.projector.compat.fml.common;

import top.hmjmfabc.projector.compat.distmarker.Dist;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 【Fabric 移植层】{@code top.hmjmfabc.projector.compat.fml.common.Mod} 的替身。
 *
 * <p>Fabric 用 {@code fabric.mod.json} 的 entrypoints 声明入口，不需要这个注解；
 * 保留它只是为了让上游的主类（{@code Projector}、{@code ProjectorClient}）保持原样，
 * 由 {@code top.hmjmfabc.projector.fabric.ProjectorFabric} 手动 new 出来。</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Mod {
    /** 模组 id。 */
    String value();

    /** 该入口适用的物理端（NeoForge 语义；Fabric 侧仅供参考）。 */
    Dist[] dist() default {Dist.CLIENT, Dist.DEDICATED_SERVER};
}

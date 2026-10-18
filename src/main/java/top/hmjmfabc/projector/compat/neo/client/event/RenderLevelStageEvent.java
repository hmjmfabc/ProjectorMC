package top.hmjmfabc.projector.compat.neo.client.event;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;

/**
 * 【Fabric 移植层】{@code top.hmjmfabc.projector.compat.neo.client.event.RenderLevelStageEvent} 的替身。
 *
 * <p>本模组只认 {@link Stage#AFTER_ENTITIES}：把平面内容画在"实体之后、半透明方块之前"，
 * 这样水面/玻璃会像盖在上面一样正常混色（hotfix-106 的结论）。Fabric 侧对应
 * {@code WorldRenderEvents.AFTER_ENTITIES}。</p>
 */
public final class RenderLevelStageEvent {

    /** 渲染阶段。本移植层只接了 {@link #AFTER_ENTITIES}。 */
    public enum Stage {
        AFTER_SKY,
        AFTER_SOLID_BLOCKS,
        AFTER_CUTOUT_MIPPED_BLOCKS_BLOCKS,
        AFTER_CUTOUT_BLOCKS,
        AFTER_ENTITIES,
        AFTER_BLOCK_ENTITIES,
        AFTER_TRANSLUCENT_BLOCKS,
        AFTER_TRIPWIRE_BLOCKS,
        AFTER_PARTICLES,
        AFTER_WEATHER,
        AFTER_LEVEL
    }

    private final Stage stage;
    private final PoseStack poseStack;
    private final Camera camera;
    private final DeltaTracker partialTick;

    public RenderLevelStageEvent(Stage stage, PoseStack poseStack, Camera camera,
                                 DeltaTracker partialTick) {
        this.stage = stage;
        this.poseStack = poseStack;
        this.camera = camera;
        this.partialTick = partialTick;
    }

    public Stage getStage() {
        return stage;
    }

    /** 相机相对坐标系的 PoseStack（原版此处是恒等矩阵，相机变换在 ModelViewMat 里）。 */
    public PoseStack getPoseStack() {
        return poseStack;
    }

    public Camera getCamera() {
        return camera;
    }

    public DeltaTracker getPartialTick() {
        return partialTick;
    }
}

package top.hmjmfabc.projector.common;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.Objects;

/**
 * 平面在 3D 世界中的定位信息：从哪个方块的哪个面开始、朝哪个方向铺开。
 *
 * <p>平面自身只保存锚点，构成平面的方块集合存在 {@link Plane#getBlocks()} 中；
 * 所有 2D 坐标（画布坐标）的原点都固定在锚点面的左下角，单位是 1/16 方块
 * （即一个完整方块面 = 16×16 单位）。这样无论平面多大，控件坐标都不会溢出。</p>
 */
public final class PlaneAnchor {

    public final BlockPos anchor;
    public final Direction face;

    public PlaneAnchor(BlockPos anchor, Direction face) {
        this.anchor = anchor.immutable();
        this.face = Objects.requireNonNull(face);
    }

    public static PlaneAnchor of(BlockPos pos, Direction face) {
        return new PlaneAnchor(pos, face);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PlaneAnchor other)) return false;
        return anchor.equals(other.anchor) && face == other.face;
    }

    @Override
    public int hashCode() {
        return anchor.hashCode() * 31 + face.ordinal();
    }

    @Override
    public String toString() {
        return anchor.toShortString() + "/" + face.getName();
    }
}

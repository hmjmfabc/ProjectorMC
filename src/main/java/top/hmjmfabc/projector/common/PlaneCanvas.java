package top.hmjmfabc.projector.common;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * 画布坐标系（canvas space）的几何换算。
 *
 * <p><b>单位</b>：1 方块 = 16 画布单位，与 Minecraft 的方块模型单位一致。
 * 因此「字体大小 8」约等于半个方块高，「图片宽 32」等于 2 个方块宽。</p>
 *
 * <p><b>原点</b>：锚点面内、由 {@link #originU}/{@link #originV} 指定的位置。
 * 对于完整方块面通常是方块面左下角，对于铁砧顶面这种不完整面则是其实际
 * 包围盒的左下角。</p>
 *
 * <p><b>x 轴</b> = {@link BlockFace#right(Direction)} 方向；<b>y 轴</b> =
 * {@link BlockFace#up(Direction)} 方向；两者均以「单位向量」形式直接加到
 * 世界坐标上，因此 y 增大的方向在世界上永远是「向上」。</p>
 */
public final class PlaneCanvas {

    /** 一个完整方块面对应的画布单位数。 */
    public static final int UNITS_PER_BLOCK = 16;

    public final BlockPos anchor;
    public final Direction face;
    /** 面内原点的 u/v 偏移（画布单位，0~16）。 */
    public final double originU;
    public final double originV;
    public final int width;
    public final int height;

    public PlaneCanvas(BlockPos anchor, Direction face, double originU, double originV, int width, int height) {
        this.anchor = anchor;
        this.face = face;
        this.originU = originU;
        this.originV = originV;
        this.width = Math.max(0, width);
        this.height = Math.max(0, height);
    }

    /** 画布原点在世界中的位置。 */
    /**
     * 该平面在世界上「所有方块面中最小的那个角」的世界坐标。
     *
     * <p>注意这里返回的是方块的小角、**不是**面内命中点；面内命中点的位置由
     * {@link Plane#originU}/{@link Plane#originV} 表示（已经减去了覆盖面的最小值），
     * 而命中点就是画布坐标 (0,0)。</p>
     */
    public Vec3 originWorld() {
        Direction r = BlockFace.right(face);
        Direction u = BlockFace.up(face);
        // 【关键】画布原点是「面上 u=0 / v=0 的那个角」，不是「方块最小角」。
        // 当某个画布轴指向世界的负方向时（例如 DOWN 面的 right=WEST、up=NORTH），
        // u=0 对应的其实是方块的最大角 —— 少加这一格，整张平面就会沿该轴偏一格。
        // 这正是「面朝西/北时偏一格」「天花板 xz 各偏一格」而「面朝东/南时正常」的原因。
        double bx = anchor.getX() + (r.getStepX() < 0 ? 1 : 0) + (u.getStepX() < 0 ? 1 : 0);
        double by = anchor.getY() + (r.getStepY() < 0 ? 1 : 0) + (u.getStepY() < 0 ? 1 : 0);
        double bz = anchor.getZ() + (r.getStepZ() < 0 ? 1 : 0) + (u.getStepZ() < 0 ? 1 : 0);
        double ox = bx + originU / UNITS_PER_BLOCK * r.getStepX() + originV / UNITS_PER_BLOCK * u.getStepX();
        double oy = by + originU / UNITS_PER_BLOCK * r.getStepY() + originV / UNITS_PER_BLOCK * u.getStepY();
        double oz = bz + originU / UNITS_PER_BLOCK * r.getStepZ() + originV / UNITS_PER_BLOCK * u.getStepZ();
        return new Vec3(ox, oy, oz);
    }

    /** 画布坐标 -> 世界坐标（含贴面偏移）。 */
    public Vec3 toWorld(double cx, double cy, double offset) {
        Direction r = BlockFace.right(face);
        Direction u = BlockFace.up(face);
        double t = offset / UNITS_PER_BLOCK;
        return new Vec3(
                originWorld().x + cx / UNITS_PER_BLOCK * r.getStepX() + cy / UNITS_PER_BLOCK * u.getStepX() + t * face.getStepX(),
                originWorld().y + cx / UNITS_PER_BLOCK * r.getStepY() + cy / UNITS_PER_BLOCK * u.getStepY() + t * face.getStepY(),
                originWorld().z + cx / UNITS_PER_BLOCK * r.getStepZ() + cy / UNITS_PER_BLOCK * u.getStepZ() + t * face.getStepZ());
    }

    /** 点的画布坐标是否落在画布内。 */
    public boolean contains(double cx, double cy) {
        return cx >= 0 && cy >= 0 && cx <= width && cy <= height;
    }

    @Override
    public String toString() {
        return "Canvas[" + anchor.toShortString() + " " + face + " " + width + "x" + height
                + " @" + originU + "," + originV + "]";
    }
}

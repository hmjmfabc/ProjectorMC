package top.hmjmfabc.projector.common;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

/**
 * 一个方块朝向的「面」。除了方向之外，还记录了该面上真正可用的子矩形
 * （以方块内 0~1 的比例表示），用于支持铁砧顶部、工作台侧面这类不完整平面。
 *
 * <p>坐标约定：面所在平面上取两个正交轴 u/v，u 为「屏幕右」，v 为「屏幕上」，
 * 二者都取自 {@link Direction#getAxis()} 组合，保证同一朝向的面使用完全一致的
 * 2D 坐标系。这样平面上的控件坐标才能跨客户端稳定复现。</p>
 */
public final class BlockFace {

    /**
     * 六种朝向各自对应的「右」轴。
     *
     * <p><b>必须满足 {@code right × up == 外法线}（右手系）。</b>
     * 否则从法线那一侧看过去，画面会整体镜像——而且因为 {@code originU} 也是
     * 沿着 {@code right} 量的，镜像还会顺带表现为「沿该轴偏一格」。</p>
     *
     * <p>DOWN（天花板）以前照抄了 UP 那一行（都是 EAST），但两者法线相反：
     * 与 {@code up=NORTH} 配起来 {@code EAST × NORTH = UP}，是 UP 面的法线而不是
     * DOWN 面的，于是天花板的内容既镜像又偏移。正确取法是 WEST：
     * {@code WEST × NORTH = DOWN} ✅</p>
     */
    public static Direction right(Direction face) {
        return switch (face) {
            case DOWN -> Direction.WEST;   // -X（与 up=NORTH 构成右手系，法线为 DOWN）
            case UP -> Direction.EAST;     // +X
            case NORTH -> Direction.WEST;  // -X
            case SOUTH -> Direction.EAST;  // +X
            // WEST / EAST 原来也是错的（同样是左手系）：
            //   WEST: NORTH × UP = (1,0,0) = EAST ≠ 外法线 WEST
            //   EAST: SOUTH × UP = (-1,0,0) = WEST ≠ 外法线 EAST
            // 正确取法（解 right × UP = 外法线）：
            //   WEST: SOUTH × UP = (-1,0,0) ✅
            //   EAST: NORTH × UP = (1,0,0)  ✅
            case WEST -> Direction.SOUTH;  // +Z
            case EAST -> Direction.NORTH;  // -Z
        };
    }

    /**
     * 面法线（指向方块外部），也就是控件向玩家一侧偏移的方向。
     * 我们直接从面自身取反：{@code getOpposite()} 即外法线。
     */
    public static Direction normal(Direction face) {
        return face;
    }

    /**
     * 「上」轴 = 法线 × 右轴（右手系）。返回值为单位方向向量。
     * 注意这里必须与渲染时使用的 (right, up) 组合保持一致。
     */
    public static Direction up(Direction face) {
        Direction r = right(face);
        // 手工写死，避免运行时叉乘出错
        return switch (face) {
            case DOWN -> Direction.NORTH;  // -Z
            case UP -> Direction.NORTH;    // -Z
            case NORTH -> Direction.UP;    // +Y
            case SOUTH -> Direction.UP;    // +Y
            case WEST -> Direction.UP;     // +Y
            case EAST -> Direction.UP;     // +Y
        };
    }

    /** 面的外法线单位向量。 */
    public static double[] normalVec(Direction face) {
        return new double[]{face.getStepX(), face.getStepY(), face.getStepZ()};
    }

    /** 某个方块朝向的面的世界空间 AABB（完整方块面）。 */
    public static AABB fullFaceBox(BlockPos pos, Direction face) {
        double x = pos.getX(), y = pos.getY(), z = pos.getZ();
        final double e = 0.002; // 略微外扩，避免射线检测漏掉边角
        return switch (face) {
            case DOWN -> new AABB(x, y - e, z, x + 1, y + e, z + 1);
            case UP -> new AABB(x, y + 1 - e, z, x + 1, y + 1 + e, z + 1);
            case NORTH -> new AABB(x, y, z - e, x + 1, y + 1, z + e);
            case SOUTH -> new AABB(x, y, z + 1 - e, x + 1, y + 1, z + 1 + e);
            case WEST -> new AABB(x - e, y, z, x + e, y + 1, z + 1);
            case EAST -> new AABB(x + 1 - e, y, z, x + 1 + e, y + 1, z + 1);
        };
    }

    private BlockFace() {
    }
}

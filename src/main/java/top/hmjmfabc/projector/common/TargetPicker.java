package top.hmjmfabc.projector.common;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 选取「准心所指的平面」。
 *
 * <p>原版 {@code BlockHitResult} 只给出方块本身与朝向，对于铁砧、楼梯这类
 * 不完整方块，同一个朝向可能对应多个互相分离的小面（例如铁砧朝东其实有
 * 左右两条腿）。因此这里不直接使用原版射线结果，而是：</p>
 * <ol>
 *   <li>用原版 clip 找到命中的方块；</li>
 *   <li>再在选定的那个碰撞盒（子形状）上做一次精确的射线-盒求交，得到命中点；</li>
 *   <li>根据命中点落在盒子的哪个面上，确定朝向与这个面在方块内的实际矩形。</li>
 * </ol>
 */
public final class TargetPicker {

    /** 选取结果：方块、面、命中点、以及该面在方块内的实际矩形（0~1 比例）。 */
    public record FaceHit(BlockPos pos, Direction face, Vec3 hit, AABB box, double faceMinU, double faceMinV,
                          double faceMaxU, double faceMaxV, boolean incomplete) {

        /** 该命中是否来自完整方块面（长宽都等于一整格）。 */
        public boolean completeFace() {
            return Math.abs(spanU() - PlaneCanvas.UNITS_PER_BLOCK) < 1.0e-3
                    && Math.abs(spanV() - PlaneCanvas.UNITS_PER_BLOCK) < 1.0e-3;
        }

        /** 面在 u 方向的长度（画布单位，1 方块面 = 16）。 */
        public double spanU() {
            return (faceMaxU - faceMinU) * PlaneCanvas.UNITS_PER_BLOCK;
        }

        /** 面在 v 方向的长度（画布单位）。 */
        public double spanV() {
            return (faceMaxV - faceMinV) * PlaneCanvas.UNITS_PER_BLOCK;
        }

        /** 该面在世界空间中的 4 个角（左下、右下、右上、左上）。 */
        public Vec3[] corners() {
            return faceCorners(pos, face, box, faceMinU, faceMinV, faceMaxU, faceMaxV);
        }
    }

    private TargetPicker() {
    }

    /** 完整方块面的 4 个角，顺序为：左下、右下、右上、左上（从面外侧看）。 */
    public static Vec3[] faceCorners(BlockPos pos, Direction face, AABB box,
                                     double minU, double minV, double maxU, double maxV) {
        Direction r = BlockFace.right(face);
        Direction u = BlockFace.up(face);
        double ox = pos.getX() + minU * r.getStepX() + minV * u.getStepX() + face.getStepX() * 1.0e-3;
        double oy = pos.getY() + minU * r.getStepY() + minV * u.getStepY() + face.getStepY() * 1.0e-3;
        double oz = pos.getZ() + minU * r.getStepZ() + minV * u.getStepZ() + face.getStepZ() * 1.0e-3;
        double du = (maxU - minU);
        double dv = (maxV - minV);
        Vec3 p0 = new Vec3(ox, oy, oz);
        Vec3 p1 = p0.add(r.getStepX() * du, r.getStepY() * du, r.getStepZ() * du);
        Vec3 p3 = p0.add(u.getStepX() * dv, u.getStepY() * dv, u.getStepZ() * dv);
        Vec3 p2 = p1.add(u.getStepX() * dv, u.getStepY() * dv, u.getStepZ() * dv);
        return new Vec3[]{p0, p1, p2, p3};
    }

    /**
     * 主入口：从 {@code from} 沿 {@code dir} 射出一条长度 {@code dist} 的射线，
     * 返回第一个可选取的面。
     */
    @Nullable
    public static FaceHit pick(BlockGetter level, Vec3 from, Vec3 dir, double dist, CollisionContext ctx) {
        Vec3 to = from.add(dir.scale(dist));
        BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, ctx));
        if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos pos = hit.getBlockPos();
        BlockState state = level.getBlockState(pos);
        VoxelShape shape = state.getCollisionShape(level, pos, ctx);
        if (shape.isEmpty()) {
            shape = state.getShape(level, pos, ctx);
        }
        if (shape.isEmpty()) {
            return null;
        }
        return pickFromShape(pos, shape, from, dir, dist);
    }

    /** 在给定形状上做精确射线求交，找出被击中的那个子面。 */
    @Nullable
    public static FaceHit pickFromShape(BlockPos pos, VoxelShape shape, Vec3 from, Vec3 dir, double dist) {
        List<AABB> boxes = new ArrayList<>(shape.toAabbs());
        AABB bestBox = null;
        Vec3 bestHit = null;
        Direction bestFace = null;
        double bestT = Double.MAX_VALUE;
        double limit = dist;
        for (AABB local : boxes) {
            AABB box = local.move(pos);
            Vec3 h = intersect(box, from, dir, limit);
            if (h == null) continue;
            double t = h.subtract(from).dot(dir);
            if (t < bestT) {
                bestT = t;
                bestBox = box;
                bestHit = h;
                bestFace = faceOf(box, h, dir);
            }
        }
        if (bestBox == null || bestHit == null || bestFace == null) {
            return null;
        }
        return describe(pos, bestBox, bestFace, bestHit, shape.bounds());
    }

    /**
     * 服务端复核用：客户端报了一个方块、朝向与命中点，服务端在方块形状里
     * 找出包含该命中点、且朝向一致的那个子面。找不到返回 null（说明客户端在乱报）。
     */
    @Nullable
    public static FaceHit pickFromShapeAt(BlockPos pos, VoxelShape shape, Direction face, Vec3 hit) {
        AABB best = null;
        double bestDist = Double.MAX_VALUE;
        for (AABB local : shape.toAabbs()) {
            AABB box = local.move(pos);
            if (Math.abs(faceDistance(box, face, hit)) > 0.05) continue;
            // 命中点必须落在该面的另外两个轴上
            if (!withinFace(box, face, hit)) continue;
            double d = Math.abs(faceDistance(box, face, hit));
            if (d < bestDist) {
                bestDist = d;
                best = box;
            }
        }
        if (best == null) return null;
        // 把命中点投影到面上
        double cx = Math.max(best.minX, Math.min(best.maxX, hit.x));
        double cy = Math.max(best.minY, Math.min(best.maxY, hit.y));
        double cz = Math.max(best.minZ, Math.min(best.maxZ, hit.z));
        Vec3 projected = switch (face.getAxis()) {
            case X -> new Vec3(face == Direction.EAST ? best.maxX : best.minX, cy, cz);
            case Y -> new Vec3(cx, face == Direction.UP ? best.maxY : best.minY, cz);
            default -> new Vec3(cx, cy, face == Direction.SOUTH ? best.maxZ : best.minZ);
        };
        return describe(pos, best, face, projected, shape.bounds());
    }

    private static double faceDistance(AABB box, Direction face, Vec3 p) {
        return switch (face) {
            case EAST -> p.x - box.maxX;
            case WEST -> box.minX - p.x;
            case UP -> p.y - box.maxY;
            case DOWN -> box.minY - p.y;
            case SOUTH -> p.z - box.maxZ;
            case NORTH -> box.minZ - p.z;
        };
    }

    private static boolean withinFace(AABB box, Direction face, Vec3 p) {
        switch (face.getAxis()) {
            case X:
                return p.y >= box.minY - 0.05 && p.y <= box.maxY + 0.05
                        && p.z >= box.minZ - 0.05 && p.z <= box.maxZ + 0.05;
            case Y:
                return p.x >= box.minX - 0.05 && p.x <= box.maxX + 0.05
                        && p.z >= box.minZ - 0.05 && p.z <= box.maxZ + 0.05;
            default:
                return p.x >= box.minX - 0.05 && p.x <= box.maxX + 0.05
                        && p.y >= box.minY - 0.05 && p.y <= box.maxY + 0.05;
        }
    }

    /** 根据命中点判断落在盒子的哪个面上（取坐标最贴合的那个轴）。 */
    private static Direction faceOf(AABB box, Vec3 p, Vec3 dir) {
        double ex = Math.min(Math.abs(p.x - box.minX), Math.abs(p.x - box.maxX));
        double ey = Math.min(Math.abs(p.y - box.minY), Math.abs(p.y - box.maxY));
        double ez = Math.min(Math.abs(p.z - box.minZ), Math.abs(p.z - box.maxZ));
        if (ey <= ex && ey <= ez) {
            return (p.y - box.minY) < (box.maxY - p.y) ? Direction.DOWN : Direction.UP;
        }
        if (ex <= ez) {
            return (p.x - box.minX) < (box.maxX - p.x) ? Direction.WEST : Direction.EAST;
        }
        return (p.z - box.minZ) < (box.maxZ - p.z) ? Direction.NORTH : Direction.SOUTH;
    }

    /** 组装 FaceHit：把世界坐标的盒子换算成「面内 u/v 矩形」。 */
    private static FaceHit describe(BlockPos pos, AABB box, Direction face, Vec3 hit, AABB fullBounds) {
        Direction r = BlockFace.right(face);
        Direction u = BlockFace.up(face);
        // 方块局部坐标（0~1）
        double lx0 = box.minX - pos.getX(), lx1 = box.maxX - pos.getX();
        double ly0 = box.minY - pos.getY(), ly1 = box.maxY - pos.getY();
        double lz0 = box.minZ - pos.getZ(), lz1 = box.maxZ - pos.getZ();

        double u0, u1, v0, v1;
        switch (r.getAxis()) {
            case X -> {
                u0 = r.getAxisDirection() == Direction.AxisDirection.POSITIVE ? lx0 : lx1;
                u1 = r.getAxisDirection() == Direction.AxisDirection.POSITIVE ? lx1 : lx0;
            }
            case Y -> {
                u0 = r.getAxisDirection() == Direction.AxisDirection.POSITIVE ? ly0 : ly1;
                u1 = r.getAxisDirection() == Direction.AxisDirection.POSITIVE ? ly1 : ly0;
            }
            default -> {
                u0 = r.getAxisDirection() == Direction.AxisDirection.POSITIVE ? lz0 : lz1;
                u1 = r.getAxisDirection() == Direction.AxisDirection.POSITIVE ? lz1 : lz0;
            }
        }
        switch (u2axis(u)) {
            case X -> {
                v0 = u.getAxisDirection() == Direction.AxisDirection.POSITIVE ? lx0 : lx1;
                v1 = u.getAxisDirection() == Direction.AxisDirection.POSITIVE ? lx1 : lx0;
            }
            case Y -> {
                v0 = u.getAxisDirection() == Direction.AxisDirection.POSITIVE ? ly0 : ly1;
                v1 = u.getAxisDirection() == Direction.AxisDirection.POSITIVE ? ly1 : ly0;
            }
            default -> {
                v0 = u.getAxisDirection() == Direction.AxisDirection.POSITIVE ? lz0 : lz1;
                v1 = u.getAxisDirection() == Direction.AxisDirection.POSITIVE ? lz1 : lz0;
            }
        }
        // 归一化：某些朝向的「右/上」轴是负方向（如 NORTH 面的右是 WEST），
        // 直接相减会得到负的长度。统一取 min/max，保证 u1>=u0、v1>=v0。
        if (u1 < u0) {
            double t = u0;
            u0 = u1;
            u1 = t;
        }
        if (v1 < v0) {
            double t = v0;
            v0 = v1;
            v1 = t;
        }
        boolean incomplete = !isFullCube(fullBounds);
        return new FaceHit(pos.immutable(), face, hit, box, u0, v0, u1, v1, incomplete);
    }

    private static Direction.Axis u2axis(Direction d) {
        return d.getAxis();
    }

    /** 方块整体的碰撞盒是否就是一个完整立方体。 */
    public static boolean isFullCube(AABB bounds) {
        return bounds.minX <= 1.0e-4 && bounds.minY <= 1.0e-4 && bounds.minZ <= 1.0e-4
                && bounds.maxX >= 1 - 1.0e-4 && bounds.maxY >= 1 - 1.0e-4 && bounds.maxZ >= 1 - 1.0e-4;
    }

    /** 标准 slab 射线-盒求交（slab method）。返回最近交点或 null。 */
    @Nullable
    public static Vec3 intersect(AABB box, Vec3 from, Vec3 dir, double maxDist) {
        double tmin = 0.0, tmax = maxDist;
        double[] o = {from.x, from.y, from.z};
        double[] d = {dir.x, dir.y, dir.z};
        double[] lo = {box.minX, box.minY, box.minZ};
        double[] hi = {box.maxX, box.maxY, box.maxZ};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1.0e-9) {
                if (o[i] < lo[i] || o[i] > hi[i]) return null;
                continue;
            }
            double inv = 1.0 / d[i];
            double t1 = (lo[i] - o[i]) * inv;
            double t2 = (hi[i] - o[i]) * inv;
            if (t1 > t2) {
                double tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            if (t1 > tmin) tmin = t1;
            if (t2 < tmax) tmax = t2;
            if (tmin > tmax) return null;
        }
        // 起点在盒内时取 tmax（离开点），否则取 tmin（进入点）
        double t = tmin > 0 ? tmin : tmax;
        return from.add(dir.scale(t));
    }
}

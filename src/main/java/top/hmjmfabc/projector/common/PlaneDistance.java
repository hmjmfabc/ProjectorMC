package top.hmjmfabc.projector.common;

import net.minecraft.world.phys.AABB;

/**
 * 平面与玩家之间的距离判定（hotfix-101）。
 *
 * <p>服务端用它决定「这个离线的平面还要不要继续同步给这个玩家」——
 * 以前是登录时把该维度**所有**平面一次性推给客户端，平面一多就是白烧流量。</p>
 *
 * <p>判据是**点到包围盒的最近距离**（不是到锚点的距离）：一个贴在山上的大平面，
 * 玩家站在它旁边但离锚点很远时，锚点距离会误判成「太远」。这与客户端
 * {@code WorldPlaneRenderer} 的剔除口径一致，两边不会打架。</p>
 *
 * <p>做成纯函数（只吃 double）是为了能被无头测试直接跑 ——
 * 「差一格」这类边界错误肉眼看不出来。</p>
 */
public final class PlaneDistance {
    private PlaneDistance() {
    }

    /**
     * 点到轴对齐盒的最近距离。
     *
     * @return 点在盒内时为 0
     */
    public static double distanceToBox(double px, double py, double pz,
                                       double minX, double minY, double minZ,
                                       double maxX, double maxY, double maxZ) {
        double dx = Math.max(0.0, Math.max(minX - px, px - maxX));
        double dy = Math.max(0.0, Math.max(minY - py, py - maxY));
        double dz = Math.max(0.0, Math.max(minZ - pz, pz - maxZ));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** 点是否在这个包围盒的 {@code range} 格之内（{@code range <= 0} = 不限）。 */
    public static boolean withinRange(double px, double py, double pz, AABB box, double range) {
        if (range <= 0.0) {
            return true;
        }
        if (box == null) {
            return false;
        }
        return distanceToBox(px, py, pz, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)
                <= range;
    }

    /** 点是否在这个平面的 {@code range} 格之内（{@code range <= 0} = 不限）。 */
    public static boolean withinRange(Plane plane, double px, double py, double pz, double range) {
        if (range <= 0.0) {
            return true;
        }
        if (plane == null) {
            return false;
        }
        try {
            return withinRange(px, py, pz, plane.bounds(), range);
        } catch (Throwable t) {
            return false;
        }
    }
}

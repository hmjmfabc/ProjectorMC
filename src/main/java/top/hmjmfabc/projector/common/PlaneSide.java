package top.hmjmfabc.projector.common;

/**
 * 平面正反面判定（hotfix-98）。
 *
 * <p>判据必须是**物理量**：法线与「相机 − 平面中心」的点积。这样它与画布轴的正负、
 * 六个朝向、旋转都无关 —— 只要相机在法线指向的那一侧，就是正面。</p>
 *
 * <p><b>【事故记录】</b>第一版写成了 {@code dot(normal, 中心 − 相机) < 0}，
 * 那等价于「相机在正面」，却被当成「反面」用 ⇒ 站在正面时绕序被翻（被 CULL 剔掉）
 * 且内容被推到方块里侧 1 格 ⇒ <b>平面上什么都看不见</b>。
 * 数学本身在两个方向上都对，错的是**命名与用法**，所以这里把函数名写死成
 * {@link #isFront}，让「反面 = !isFront」在调用处一眼可见，并且用具体坐标钉了测试。</p>
 */
public final class PlaneSide {
    private PlaneSide() {
    }

    /**
     * 相机是否在平面的**正面**（法线指向的那一侧）。
     *
     * @param normal 平面外法线（世界坐标，单位向量，来自 {@code BlockFace.normal()}）
     * @param center 平面包围盒中心（世界坐标）
     * @param camPos 相机位置（世界坐标）
     * @return true = 正面（正常渲染）；false = 反面（需要翻绕序 + 镜像深度）
     */
    public static boolean isFront(double[] normal, double[] center, double[] camPos) {
        if (normal == null || center == null || camPos == null
                || normal.length < 3 || center.length < 3 || camPos.length < 3) {
            return true;                       // 参数不全时按「正面」处理（= 保持原有行为）
        }
        double dx = camPos[0] - center[0];
        double dy = camPos[1] - center[1];
        double dz = camPos[2] - center[2];
        double dot = normal[0] * dx + normal[1] * dy + normal[2] * dz;
        // 恰好落在平面上（点积为 0）时按正面处理：那是最靠近的正常视角
        return dot >= 0.0;
    }

    /**
     * 从背面看时，这一帧要不要「透墙」把内容画出来（hotfix-II）。
     *
     * <p>平面是单面的：站在背面时四边形背面朝外、被 {@code CULL} 剔除；就算把绕序翻回来，
     * 方块本体也挡在相机与内容之间 ⇒ 什么都看不到。让它可见只有两条路：</p>
     * <ol>
     *   <li>把内容挪到墙的另一面（Build 102/103 试过）—— <b>玩家实测否决</b>：
     *       「正面在哪背面就显示在哪」，挪一格就会被看成「整体偏了一个方块」；</li>
     *   <li>几何一个像素都不动，只在背面这一帧<b>关掉深度测试</b>（= 透墙）。
     *       代价是离得远时会隔着地形看到内容（穿墙），所以必须配一个距离门槛。</li>
     * </ol>
     *
     * <p>门槛口径：{@code 距离 ≤ range} 才透墙。这里的距离是「相机到平面包围盒的最近距离」
     * ——包围盒自带 1.25 格 padding，所以 {@code range = 0} 的实际含义是
     * 「站在平面旁边（1.25 格以内）」，而不是「永远看不到」。</p>
     *
     * <p><b>⚠ {@code range = 0} 不是「不限」</b>：与 {@link PlaneDistance#withinRange} 的约定相反
     * （那边 0 = 不限）。两者相反是刻意的 —— 这一项描述的是「门开多大」，
     * 0 = 门关到底，只留贴面那一点点。</p>
     *
     * @param backside         相机是否在背面（{@code !isFront(...)}）
     * @param distanceToBounds 相机到平面包围盒的最近距离（格，盒内为 0）
     * @param range            配置的透墙距离（格）
     * @return true = 这一帧关闭深度测试，让内容从背面也能看到
     */
    public static boolean seeThroughFromBack(boolean backside, double distanceToBounds, double range) {
        if (!backside) {
            return false;                       // 正面永远走正常渲染（深度测试照旧）
        }
        if (Double.isNaN(distanceToBounds) || Double.isNaN(range)) {
            return false;                       // 算不出来就按「看不见」处理，绝不因此穿墙
        }
        return distanceToBounds <= Math.max(0.0, range);
    }
}

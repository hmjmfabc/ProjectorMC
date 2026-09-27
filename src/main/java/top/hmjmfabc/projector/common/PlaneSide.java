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
}

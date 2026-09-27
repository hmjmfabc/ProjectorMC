package top.hmjmfabc.projector.client.render;

import top.hmjmfabc.projector.common.widget.Widget;

/**
 * 单个平面的渲染上下文：把「画布坐标」翻译成世界坐标所需的一切。
 *
 * @param axisX  画布 x 轴的世界单位向量
 * @param axisY  画布 y 轴的世界单位向量
 * @param origin 画布原点的世界坐标（不含深度偏移）
 * @param depth  该四边形沿外法线的偏移（方块单位），由方块表面位移 + 控件分层得出
 */
public record PlaneRenderContext(double[] axisX, double[] axisY, double[] normal, double[] origin, double depth) {

    /** 控件沿外法线的分层偏移：每层 0.0035 方块，避免多个控件互相 Z-fighting。 */
    public static final double LAYER_STEP = 0.0035;

    /**
     * 基础贴面偏移（单位：方块）。**正数 = 朝玩家一侧**。
     *
     * <p>取值要在两者之间取平衡：太小会与方块表面 Z-fighting（尤其是远处，
     * 深度缓冲精度下降时会出现闪烁的条纹），太大会在斜视时看出内容「浮」在墙上。
     * 0.006 方块 = 6 毫米，肉眼看不出来，但已经远高于远距离下的深度精度。</p>
     */
    public static final double SURFACE_BIAS = 0.02;

    /**
     * 实际使用的贴面偏移：配置 {@code render.surfaceBias}（默认 {@link #SURFACE_BIAS}）。
     *
     * <p>【hotfix-99】光影包（Iris）有自己的深度预通道与阴影偏移，0.006 格这种
     * 「亚毫米级」的偏移在它眼里可能根本不算分离 ⇒ 内容与墙面互相打架 = 闪烁。
     * 默认值提到 0.02 格（2 厘米，肉眼仍不可辨），并开放给玩家自己微调。</p>
     */
    public static double surfaceBias() {
        try {
            return top.hmjmfabc.projector.ProjectorConfig.INSTANCE.renderSurfaceBias.get();
        } catch (Throwable t) {
            return SURFACE_BIAS;
        }
    }

    /** 计算某个控件的分层深度。 */
    public double depthFor(Widget widget, double blockSurfaceDepth) {
        return blockSurfaceDepth + surfaceBias() + widget.zOff * LAYER_STEP;
    }
}

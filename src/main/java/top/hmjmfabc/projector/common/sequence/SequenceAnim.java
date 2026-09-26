package top.hmjmfabc.projector.common.sequence;

/**
 * 【⑩】流程动画的「种类」与「求值」。
 *
 * <p>这里只做纯数学：给定「流程时间」与一个片段，算出这个控件此刻应该
 * 偏移多少、缩放多少、透明度多少、以及沿法线抬升多少。渲染层拿到的就是这几个量，
 * 因此同一份数据在「世界里渲染」与「编辑器预览」中必然表现一致。</p>
 *
 * <h2>动画种类（按用户 ⑩ 的要求）</h2>
 * <ul>
 *   <li><b>入场</b>：{@link #ANIM_SLIDE 滑动}、{@link #ANIM_FADE 渐显}、
 *       {@link #ANIM_SCALE 放大}、以及富有特色的 {@link #ANIM_DROP 下落}
 *       （在平面<b>前方约 10 格</b>渲染控件，然后快速移动到平面上，过程中带渐显）；</li>
 *   <li><b>出场</b>：{@link #ANIM_FADE 渐隐}、{@link #ANIM_SCALE 缩小}
 *       （与入场共用同一套算子，靠时间方向取反）；</li>
 *   <li><b>循环</b>：{@link #LOOP_SWING 摆动}、{@link #LOOP_PULSE 呼吸}、
 *       {@link #LOOP_FLOAT 浮动}——循环动画只与「绝对时间」有关，
 *       <b>不需要设置显示时长</b>（与用户要求一致）。</li>
 * </ul>
 */
public final class SequenceAnim {

    // ---- 入场 / 出场 ----
    public static final int ANIM_NONE = 0;
    public static final int ANIM_SLIDE = 1;
    public static final int ANIM_FADE = 2;
    public static final int ANIM_SCALE = 3;
    public static final int ANIM_DROP = 4;

    public static final String[] ANIM_NAMES = {
            "\u65e0", "\u6ed1\u52a8", "\u6e10\u663e/\u6e10\u9690",
            "\u653e\u5927/\u7f29\u5c0f", "\u4e0b\u843d"};

    // ---- 循环 ----
    public static final int LOOP_NONE = 0;
    public static final int LOOP_SWING = 1;
    public static final int LOOP_PULSE = 2;
    public static final int LOOP_FLOAT = 3;

    public static final String[] LOOP_NAMES = {
            "\u65e0", "\u6446\u52a8", "\u547c\u5438", "\u6d6e\u52a8"};

    /** 下落动画的起始高度（方块）。用户原文：平面前方大约 10 格。 */
    public static final double DROP_BLOCKS = 10.0;

    private SequenceAnim() {
    }

    /** 取名（越界时回落到「无」）。 */
    public static String animName(int id) {
        return id < 0 || id >= ANIM_NAMES.length ? ANIM_NAMES[0] : ANIM_NAMES[id];
    }

    public static String loopName(int id) {
        return id < 0 || id >= LOOP_NAMES.length ? LOOP_NAMES[0] : LOOP_NAMES[id];
    }

    /**
     * 一个控件在某一时刻的动画状态。
     *
     * @param visible 是否应当绘制
     * @param dx      画布 x 方向的额外偏移（画布单位）
     * @param dy      画布 y 方向的额外偏移（画布单位）
     * @param dDepth  沿平面外法线的额外偏移（<b>方块</b>单位，正 = 朝观察者／平面前方）
     * @param scale   缩放倍率（以控件中心为基准）
     * @param alpha   透明度倍率
     * @param rotDeg  额外旋转（度）
     */
    public record State(boolean visible, double dx, double dy, double dDepth,
                        double scale, double alpha, double rotDeg) {

        /** 不施加任何动画。 */
        public static final State NORMAL = new State(true, 0, 0, 0, 1, 1, 0);
        /** 完全隐藏。 */
        public static final State HIDDEN = new State(false, 0, 0, 0, 1, 0, 0);

        public boolean isNormal() {
            return visible && dx == 0 && dy == 0 && dDepth == 0 && scale == 1
                    && alpha == 1 && rotDeg == 0;
        }
    }

    /** 平滑缓动（smoothstep）：比线性自然得多，且两端导数为 0。 */
    public static double ease(double t) {
        double p = clamp01(t);
        return p * p * (3 - 2 * p);
    }

    public static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}

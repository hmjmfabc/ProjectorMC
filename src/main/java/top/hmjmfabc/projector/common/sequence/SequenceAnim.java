package top.hmjmfabc.projector.common.sequence;

/**
 * 【⑩】流程动画的「种类」与「求值」。
 *
 * <p>本类是<b>动画数学的唯一来源</b>：运行期（{@link SequenceTrack#stateOf}）与
 * 编辑器里的动画选择界面（{@code SequenceAnimPickerScreen} 的实时预览）
 * 调用的都是这里同一组纯函数。这样「预览里看到的样子」与「世界里播放的样子」
 * 不可能分叉——本项目已经吃过「两套几何/两套判定」的亏（见 AGENTS.md §5.5 第 56~69 条）。</p>
 *
 * <h2>三类动画</h2>
 * <ul>
 *   <li><b>入场</b> {@link #inEffect}：进度 {@code p=0} 是「刚开始出现」，{@code p=1} 是「完全就位」；</li>
 *   <li><b>出场</b> {@link #outEffect}：进度 {@code p=0} 是「还在原位」，{@code p=1} 是「完全消失」。
 *       名字见 {@link #OUT_ANIM_NAMES}（编号 4 入场叫「下落」、出场叫「上升」，两者互为镜像）；</li>
 *   <li><b>循环</b> {@link #loopEffect}：由相位 {@code phase}（弧度）驱动，与显示时长无关。</li>
 * </ul>
 *
 * <p>编号 <b>0~4 与旧版本完全一致</b>（无 / 滑动 / 渐显渐隐 / 放大缩小 / 下落），
 * 老存档里的片段不会因为这次扩充而变成别的动画。</p>
 */
public final class SequenceAnim {

    // ---- 入场 / 出场 ----
    public static final int ANIM_NONE = 0;
    public static final int ANIM_SLIDE = 1;
    public static final int ANIM_FADE = 2;
    public static final int ANIM_SCALE = 3;
    public static final int ANIM_DROP = 4;
    /** 【27.2-pre-136】转着出现（绕自身法线转一圈）。 */
    public static final int ANIM_SPIN = 5;
    /** 【27.2-pre-136】从上方落下、弹两下。 */
    public static final int ANIM_BOUNCE = 6;
    /** 【27.2-pre-136】从下方升起 + 渐显。 */
    public static final int ANIM_RISE = 7;
    /** 【27.2-pre-136】弹出（缩到很小再超调回弹到位）。 */
    public static final int ANIM_POP = 8;
    /** 【27.2-pre-136】从平面里飞出来（沿法线从墙内飞出）。 */
    public static final int ANIM_FLY = 9;
    /** 【27.2-pre-136】翻页展开（纵向从 0 拉开）。 */
    public static final int ANIM_FLIP = 10;
    /** 【27.2-pre-136】擦除展开（横向从 0 拉开）。 */
    public static final int ANIM_WIPE = 11;
    /** 【27.2-pre-136】抖着出现（左右快速抖动 + 渐显）。 */
    public static final int ANIM_SHAKE = 12;

    public static final String[] ANIM_NAMES = {
            "\u65e0", "\u6ed1\u52a8", "\u6e10\u663e/\u6e10\u9690",
            "\u653e\u5927/\u7f29\u5c0f", "\u4e0b\u843d",
            "\u65cb\u8f6c\u8fdb\u5165", "\u5f39\u8df3\u843d\u4e0b", "\u4ece\u4e0b\u65b9\u5347\u8d77",
            "\u5f39\u51fa", "\u98de\u5165\uff08\u4ece\u5e73\u9762\u91cc\uff09",
            "\u7ffb\u9875\u5c55\u5f00", "\u64e6\u9664\u5c55\u5f00", "\u6296\u52a8\u8fdb\u5165"};

    /**
     * 【27.2-pre-138】**出场**动画的名字。
     *
     * <p>为什么要和入场分开一张表：编号 4 的入场是「下落」（从平面前方 10 格落到平面上），
     * 它的出场是**正好相反的镜像**（从平面升到前方 10 格）—— 玩家原话
     * 「退出动画『下落』改为『上升』，逻辑与入场动画的『下落』正好相反」。
     * 一个编号两种名字，所以这里必须单独一张表（否则界面会把出场也写成「下落」）。</p>
     */
    public static final String[] OUT_ANIM_NAMES = {
            "\u65e0", "\u6ed1\u52a8", "\u6e10\u663e/\u6e10\u9690",
            "\u653e\u5927/\u7f29\u5c0f",
            "\u4e0a\u5347",
            "\u65cb\u8f6c\u8fdb\u5165", "\u5f39\u8df3\u843d\u4e0b", "\u4ece\u4e0b\u65b9\u5347\u8d77",
            "\u5f39\u51fa", "\u98de\u5165\uff08\u4ece\u5e73\u9762\u91cc\uff09",
            "\u7ffb\u9875\u5c55\u5f00", "\u64e6\u9664\u5c55\u5f00", "\u6296\u52a8\u8fdb\u5165"};

    /** 每一种动画的一句话说明（动画选择界面里显示，方便玩家不用一个个试）。 */
    public static final String[] ANIM_HINTS = {
            "\u4e0d\u505a\u4efb\u4f55\u52a8\u753b",
            "\u4ece\u4e00\u4fa7\u6ed1\u5165\uff0c\u65b9\u5411\u53ef\u9009",
            "\u900f\u660e\u5ea6\u6de1\u5165 / \u6de1\u51fa",
            "\u4ee5\u4e2d\u5fc3\u4e3a\u57fa\u51c6\u653e\u5927\u5230\u4f4d",
            "\u4ece\u5e73\u9762\u524d\u65b9 10 \u683c\u51b2\u5230\u5e73\u9762\u4e0a",
            "\u8f6c\u4e00\u5708\u7684\u540c\u65f6\u653e\u5927\u51fa\u73b0",
            "\u4ece\u4e0a\u65b9\u843d\u4e0b\u5e76\u5f39\u4e24\u4e0b",
            "\u4ece\u4e0b\u65b9\u5347\u8d77\u6765\uff0c\u8fb9\u5347\u8fb9\u6e10\u663e",
            "\u5148\u7f29\u5230\u5f88\u5c0f\uff0c\u518d\u5f39\u8fc7\u5934\u4e00\u70b9\u56de\u5230\u4f4d",
            "\u4ece\u5e73\u9762\u91cc\u9762\u94bb\u51fa\u6765\uff08\u50cf\u4ece\u5899\u91cc\u957f\u51fa\uff09",
            "\u50cf\u7ffb\u724c\u4e00\u6837\u7eb5\u5411\u5c55\u5f00",
            "\u50cf\u62c9\u5e18\u4e00\u6837\u6a2a\u5411\u5c55\u5f00",
            "\u5de6\u53f3\u6296\u52a8\u7740\u51fa\u73b0"};

    /** 出场动画的一句话说明（只有「上升」那条与入场不同）。 */
    public static final String[] OUT_ANIM_HINTS = {
            "\u4e0d\u505a\u4efb\u4f55\u52a8\u753b",
            "\u6ed1\u8d70\u7684\u540c\u65f6\u6de1\u51fa\uff08\u65b9\u5411\u53ef\u9009\uff09",
            "\u900f\u660e\u5ea6\u6de1\u51fa",
            "\u4ee5\u4e2d\u5fc3\u4e3a\u57fa\u51c6\u7f29\u5c0f\u5230\u6d88\u5931",
            "\u4e0e\u5165\u573a\u300c\u4e0b\u843d\u300d\u6b63\u597d\u76f8\u53cd\uff1a\u4ece\u5e73\u9762\u5347\u5230\u524d\u65b9 10 \u683c\u5e76\u6de1\u51fa",
            "\u8f6c\u4e00\u5708\u7684\u540c\u65f6\u7f29\u5c0f\u6d88\u5931",
            "\u5411\u4e0a\u5f39\u8d77\u540e\u6d88\u5931",
            "\u5411\u4e0a\u98d8\u8d70\u5e76\u6de1\u51fa",
            "\u5148\u5f39\u8fc7\u5934\u4e00\u70b9\u518d\u6536\u6210\u4e00\u4e2a\u70b9",
            "\u56de\u5230\u5e73\u9762\u91cc\u9762\uff08\u4ece\u5899\u91cc\u7f29\u56de\u53bb\uff09",
            "\u50cf\u7ffb\u724c\u4e00\u6837\u7ad6\u5411\u6536\u8d77\u6765",
            "\u50cf\u62c9\u5e18\u4e00\u6837\u6a2a\u5411\u6536\u8d77\u6765",
            "\u5de6\u53f3\u6296\u52a8\u7740\u6d88\u5931"};

    // ---- 循环 ----
    public static final int LOOP_NONE = 0;
    public static final int LOOP_SWING = 1;
    public static final int LOOP_PULSE = 2;
    public static final int LOOP_FLOAT = 3;
    /** 【27.2-pre-136】匀速自转。 */
    public static final int LOOP_SPIN = 4;
    /** 【27.2-pre-136】闪烁（透明度脉动）。 */
    public static final int LOOP_BLINK = 5;
    /** 【27.2-pre-136】快速小幅抖动。 */
    public static final int LOOP_SHAKE = 6;
    /** 【27.2-pre-136】原地跳动。 */
    public static final int LOOP_HOP = 7;

    public static final String[] LOOP_NAMES = {
            "\u65e0", "\u6446\u52a8", "\u547c\u5438", "\u6d6e\u52a8",
            "\u81ea\u8f6c", "\u95ea\u70c1", "\u6296\u52a8", "\u8df3\u52a8"};

    public static final String[] LOOP_HINTS = {
            "\u4e0d\u505a\u4efb\u4f55\u52a8\u753b",
            "\u5de6\u53f3\u6446\u52a8\uff08\u5e45\u5ea6=\u5ea6\uff09",
            "\u547c\u5438\u4f3c\u7684\u653e\u5927\u7f29\u5c0f\uff08\u5e45\u5ea6=%\uff09",
            "\u4e0a\u4e0b\u6d6e\u52a8\uff08\u5e45\u5ea6=\u753b\u5e03\u5355\u4f4d\uff09",
            "\u5300\u901f\u81ea\u8f6c\uff08\u5e45\u5ea6\u65e0\u6548\uff09",
            "\u660e\u6697\u95ea\u70c1\uff08\u5e45\u5ea6\u8d8a\u5927\u8d8a\u6697\uff09",
            "\u5feb\u901f\u5c0f\u5e45\u6296\u52a8\uff08\u5e45\u5ea6=\u753b\u5e03\u5355\u4f4d\uff09",
            "\u539f\u5730\u8df3\u52a8\uff08\u5e45\u5ea6=\u753b\u5e03\u5355\u4f4d\uff09"};

    /** 下落动画的起始高度（方块）。用户原文：平面前方大约 10 格。 */
    public static final double DROP_BLOCKS = 10.0;
    /** 「飞入」动画从平面内部钻出来的深度（方块）。 */
    public static final double FLY_BLOCKS = 6.0;

    private SequenceAnim() {
    }

    /** 取名（越界时回落到「无」）。 */
    public static String animName(int id) {
        return id < 0 || id >= ANIM_NAMES.length ? ANIM_NAMES[0] : ANIM_NAMES[id];
    }

    public static String loopName(int id) {
        return id < 0 || id >= LOOP_NAMES.length ? LOOP_NAMES[0] : LOOP_NAMES[id];
    }

    /** **出场**动画取名（只有编号 4 与入场不同）。 */
    public static String outAnimName(int id) {
        return id < 0 || id >= OUT_ANIM_NAMES.length ? OUT_ANIM_NAMES[0] : OUT_ANIM_NAMES[id];
    }

    /** **出场**动画的一句话说明。 */
    public static String outAnimHint(int id) {
        return id < 0 || id >= OUT_ANIM_HINTS.length ? "" : OUT_ANIM_HINTS[id];
    }

    /** 一句话说明（越界时给空串）。 */
    public static String animHint(int id) {
        return id < 0 || id >= ANIM_HINTS.length ? "" : ANIM_HINTS[id];
    }

    public static String loopHint(int id) {
        return id < 0 || id >= LOOP_HINTS.length ? "" : LOOP_HINTS[id];
    }

    /** 这个动画是否吃「滑动方向」这个参数（编辑器据此决定要不要显示方向按钮）。 */
    public static boolean usesSlideAngle(int id) {
        return id == ANIM_SLIDE || id == ANIM_SHAKE;
    }

    /** 这个动画是否吃「滑动距离」这个参数（0 = 按控件尺寸自动取）。 */
    public static boolean usesDistance(int id) {
        return id == ANIM_SLIDE || id == ANIM_SHAKE || id == ANIM_BOUNCE || id == ANIM_RISE;
    }

    /**
     * 一个控件在某一时刻的动画状态。
     *
     * @param visible 是否应当绘制
     * @param dx      画布 x 方向的额外偏移（画布单位）
     * @param dy      画布 y 方向的额外偏移（画布单位）
     * @param dDepth  沿平面外法线的额外偏移（<b>方块</b>单位，正 = 朝观察者／平面前方）
     * @param scale   整体缩放倍率（以控件中心为基准）
     * @param alpha   透明度倍率
     * @param rotDeg  额外旋转（度）
     * @param scaleX  横向缩放倍率（1 = 不变）——「擦除展开」用它把宽度从 0 拉开
     * @param scaleY  纵向缩放倍率（1 = 不变）——「翻页展开」用它把高度从 0 拉开
     */
    public record State(boolean visible, double dx, double dy, double dDepth,
                        double scale, double alpha, double rotDeg,
                        double scaleX, double scaleY) {

        /** 旧口径（无横纵独立缩放）的构造：等价于 scaleX = scaleY = 1。 */
        public State(boolean visible, double dx, double dy, double dDepth,
                     double scale, double alpha, double rotDeg) {
            this(visible, dx, dy, dDepth, scale, alpha, rotDeg, 1, 1);
        }

        /** 不施加任何动画。 */
        public static final State NORMAL = new State(true, 0, 0, 0, 1, 1, 0, 1, 1);
        /** 完全隐藏。 */
        public static final State HIDDEN = new State(false, 0, 0, 0, 1, 0, 0, 1, 1);

        public boolean isNormal() {
            return visible && dx == 0 && dy == 0 && dDepth == 0 && scale == 1
                    && alpha == 1 && rotDeg == 0 && scaleX == 1 && scaleY == 1;
        }

        /** 实际横向缩放 = 整体缩放 × 横向缩放。 */
        public double effScaleX() {
            return scale * scaleX;
        }

        /** 实际纵向缩放 = 整体缩放 × 纵向缩放。 */
        public double effScaleY() {
            return scale * scaleY;
        }
    }

    /** 平滑缓动（smoothstep）：比线性自然得多，且两端导数为 0。 */
    public static double ease(double t) {
        double p = clamp01(t);
        return p * p * (3 - 2 * p);
    }

    /** 回弹缓动（easeOutBack）：末端会超过 1 一点点再收回来，用于「弹出」。 */
    public static double easeOutBack(double t) {
        double p = clamp01(t);
        double c1 = 1.70158;
        double c3 = c1 + 1.0;
        double q = p - 1.0;
        return 1.0 + c3 * q * q * q + c1 * q * q;
    }

    public static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    // ------------------------------------------------------------------
    // 求值（运行期与预览界面共用同一份）
    // ------------------------------------------------------------------

    /**
     * 入场效果。
     *
     * @param anim      {@link #ANIM_NONE}…{@link #ANIM_SHAKE}
     * @param p         进度：0 = 刚开始出现，1 = 完全就位
     * @param slideAngle 滑动/抖动方向（度）
     * @param dist      位移距离（画布单位）；调用方按控件尺寸给出
     */
    public static State inEffect(int anim, double p, double slideAngle, double dist) {
        double t = ease(p);
        double d = dist;
        return switch (anim) {
            // 【27.2-pre-138】滑动**同时渐显**（玩家原话：「滑动动画要带有渐显/渐隐效果，
            // 不然太丑了」）—— 以前只有位移，控件是「凭空出现」再滑过来。
            case ANIM_SLIDE -> slideOffset(slideAngle, d * (1 - t), 1.0, t, 0);
            case ANIM_FADE -> new State(true, 0, 0, 0, 1.0, t, 0);
            case ANIM_SCALE -> new State(true, 0, 0, 0, 0.2 + 0.8 * t, 1.0, 0);
            case ANIM_DROP -> new State(true, 0, 0, DROP_BLOCKS * (1 - t), 1.0, t, 0);
            case ANIM_SPIN -> new State(true, 0, 0, 0, 0.4 + 0.6 * t, clamp01(t * 1.6), 360.0 * (1 - t));
            case ANIM_BOUNCE -> new State(true, 0, d * bounceCurve(t), 0,
                    1.0, clamp01(t * 3.0), 0);
            case ANIM_RISE -> new State(true, 0, -d * (1 - t), 0,
                    0.85 + 0.15 * t, t, 0);
            case ANIM_POP -> new State(true, 0, 0, 0,
                    0.3 + 0.7 * easeOutBack(p), clamp01(p * 2.0), 0);
            case ANIM_FLY -> new State(true, 0, 0, -FLY_BLOCKS * (1 - t),
                    0.35 + 0.65 * t, t, 0);
            case ANIM_FLIP -> new State(true, 0, 0, 0, 1.0, clamp01(t * 2.0), 0,
                    1.0 + 0.18 * (1 - t), Math.max(0.0, t));
            case ANIM_WIPE -> new State(true, 0, 0, 0, 1.0, clamp01(t * 3.0), 0,
                    Math.max(0.0, t), 1.0);
            case ANIM_SHAKE -> new State(true,
                    d * 0.28 * (1 - t) * Math.sin(Math.PI * 6.0 * t), 0, 0,
                    1.0, clamp01(t * 2.0), 0);
            default -> State.NORMAL;
        };
    }

    /**
     * 出场效果。
     *
     * @param p 进度：0 = 还在原位，1 = 完全消失
     */
    public static State outEffect(int anim, double p, double slideAngle, double dist) {
        double t = ease(p);
        double d = dist;
        return switch (anim) {
            // 出场滑动同样带渐隐（与入场互为镜像）
            case ANIM_SLIDE -> slideOffset(slideAngle, d * t, 1.0, 1 - t, 0);
            case ANIM_FADE -> new State(true, 0, 0, 0, 1.0, 1 - t, 0);
            // 缩小到 0（旧版停在 0.2，最后一帧会「卡一下」再消失）
            case ANIM_SCALE -> new State(true, 0, 0, 0, 1 - t, 1.0, 0);
            // 【27.2-pre-138】出场这一条 = 入场「下落」的**镜像**（玩家要求改名「上升」）：
            // 入场是 dDepth +10 → 0（从平面前方落到平面上），出场就是 0 → +10
            // （从平面升到平面前方）并淡出。以前是 -10（沉到平面后面/墙里），
            // 在实心墙上会「钻进方块里」，观感不对。
            case ANIM_DROP -> new State(true, 0, 0, DROP_BLOCKS * t, 1.0, 1 - t, 0);
            case ANIM_SPIN -> new State(true, 0, 0, 0, 1 - 0.6 * t, 1 - t, -360.0 * t);
            case ANIM_BOUNCE -> new State(true, 0,
                    -d * t * (1.0 + 0.35 * Math.abs(Math.sin(Math.PI * 3.0 * p))), 0,
                    1.0, 1 - t, 0);
            case ANIM_RISE -> new State(true, 0, d * t, 0, 1 - 0.2 * t, 1 - t, 0);
            case ANIM_POP -> new State(true, 0, 0, 0,
                    Math.max(0.0, 1 - 1.05 * easeOutBack(p)), 1 - t, 0);
            case ANIM_FLY -> new State(true, 0, 0, -FLY_BLOCKS * t,
                    1 - 0.65 * t, 1 - t, 0);
            case ANIM_FLIP -> new State(true, 0, 0, 0, 1.0, 1 - t, 0,
                    1.0, Math.max(0.0, 1 - t));
            case ANIM_WIPE -> new State(true, 0, 0, 0, 1.0, 1 - clamp01(ease(p) * 1.5), 0,
                    Math.max(0.0, 1 - t), 1.0);
            case ANIM_SHAKE -> new State(true,
                    d * 0.28 * t * Math.sin(Math.PI * 6.0 * p), 0, 0,
                    1.0, 1 - t, 0);
            default -> State.NORMAL;
        };
    }

    /**
     * 循环效果。
     *
     * @param loop  循环种类
     * @param phase 相位（弧度），由「绝对时间 × 速度 × 2π」给出
     * @param amp   幅度（含义随种类不同：摆动=度、呼吸=%、浮动/跳动=画布单位）
     */
    public static State loopEffect(int loop, double phase, double amp) {
        double s = Math.sin(phase);
        return switch (loop) {
            case LOOP_SWING -> new State(true, 0, 0, 0, 1.0, 1.0, amp * s);
            case LOOP_PULSE -> new State(true, 0, 0, 0, 1.0 + (amp / 100.0) * s, 1.0, 0);
            case LOOP_FLOAT -> new State(true, 0, amp * s, 0, 1.0, 1.0, 0);
            case LOOP_SPIN -> new State(true, 0, 0, 0, 1.0, 1.0, Math.toDegrees(phase) % 360.0);
            // 闪烁：幅度 = 「最暗时减掉多少透明度」的百分比（100 = 几乎完全灭掉）
            case LOOP_BLINK -> new State(true, 0, 0, 0, 1.0,
                    1.0 - 0.9 * clamp01(amp / 100.0) * (1.0 - s) * 0.5, 0);
            case LOOP_SHAKE -> new State(true, amp * 0.18 * s, 0, 0, 1.0, 1.0, 0);
            case LOOP_HOP -> new State(true, 0, amp * 0.7 * Math.abs(s), 0, 1.0, 1.0, 0);
            default -> State.NORMAL;
        };
    }

    /**
     * 沿某个方向的位移（dx/dy），其余量原样带入。
     *
     * <p>⚠ 最后一个参数是<b>旋转度</b>不是缩放：这里曾经把 {@code scale, alpha, rot}
     * 写成 {@code 1, 1, 1}，于是「滑动」动画悄悄多转了 1°（看不出来，但求值不干净），
     * 被 T36 的「p=1 必须完全就位」不变量抓住。</p>
     */
    private static State slideOffset(double angleDeg, double distance, double scale,
                                     double alpha, double rot) {
        double rad = Math.toRadians(angleDeg);
        return new State(true, Math.cos(rad) * distance, Math.sin(rad) * distance, 0,
                scale, alpha, rot);
    }

    /**
     * 「弹跳」的位移曲线：{@code t=0} 时等于 1（完全偏移），{@code t=1} 时回到 0。
     *
     * <p>用阻尼余弦：两次反弹 + 逐渐停住，比直线下落自然得多。</p>
     */
    public static double bounceCurve(double t) {
        double p = clamp01(t);
        double damp = (1 - p) * (1 - p);
        return damp * Math.abs(Math.cos(Math.PI * 2.5 * p));
    }
}

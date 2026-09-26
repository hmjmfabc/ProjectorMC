package top.hmjmfabc.projector.common.sequence;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/**
 * 【⑩】流程时间轴上的一个「片段」：某个控件从第几秒显示到第几秒，
 * 以及它进场、出场、循环时用什么动画。
 *
 * <p>时间统一用<b>秒</b>（浮点），时间轴范围 <b>0~999 秒</b>（用户要求）。
 * 片段的 {@code startSec/endSec} 会由编辑器夹在这个范围内。</p>
 */
public final class SequenceClip {

    /** 时间轴总长度（秒）。 */
    public static final double MAX_SECONDS = 999.0;
    /** 默认动画时长（秒）。用户原文：默认 0.5s。 */
    public static final double DEFAULT_ANIM_SECONDS = 0.5;

    public UUID widgetId;
    public double startSec;
    public double endSec;

    public int inAnim = SequenceAnim.ANIM_NONE;
    public int outAnim = SequenceAnim.ANIM_NONE;

    /** 入场 / 出场动画的渲染时长（0 ~ 控件显示时长，默认 0.5s）。 */
    public double inDur = DEFAULT_ANIM_SECONDS;
    public double outDur = DEFAULT_ANIM_SECONDS;

    /** 滑动动画的来向（度）：0=从左、90=从上、180=从右、270=从下。 */
    public double slideAngle = 0;
    /** 滑动距离（画布单位）；0 表示按控件自身尺寸自动取。 */
    public double slideDistance = 0;

    /** 循环动画（无须设置显示时长）。 */
    public int loopAnim = SequenceAnim.LOOP_NONE;
    /** 循环幅度：摆动=度、呼吸=百分比、浮动=画布单位。 */
    public double loopAmp = 8;
    /** 循环速度（Hz）。 */
    public double loopSpeed = 0.5;

    public SequenceClip() {
    }

    public SequenceClip(UUID widgetId, double startSec, double endSec) {
        this.widgetId = widgetId;
        this.startSec = startSec;
        this.endSec = endSec;
    }

    public double duration() {
        return Math.max(0, endSec - startSec);
    }

    /** 把时间与时长夹到合法范围。 */
    public void sanitize() {
        startSec = clamp(startSec, 0, SequenceClip.MAX_SECONDS);
        endSec = clamp(endSec, 0, SequenceClip.MAX_SECONDS);
        if (endSec < startSec) {
            double t = startSec;
            startSec = endSec;
            endSec = t;
        }
        double d = duration();
        inDur = clamp(inDur, 0, d);
        outDur = clamp(outDur, 0, d);
        slideDistance = clamp(slideDistance, 0, 4096);
        loopAmp = clamp(loopAmp, 0, 360);
        loopSpeed = clamp(loopSpeed, 0.02, 8);
        inAnim = validAnim(inAnim);
        outAnim = validAnim(outAnim);
        loopAnim = validLoop(loopAnim);
    }

    public static int validAnim(int id) {
        return id < 0 || id >= SequenceAnim.ANIM_NAMES.length ? SequenceAnim.ANIM_NONE : id;
    }

    public static int validLoop(int id) {
        return id < 0 || id >= SequenceAnim.LOOP_NAMES.length ? SequenceAnim.LOOP_NONE : id;
    }

    public static double clamp(double v, double min, double max) {
        if (Double.isNaN(v)) return min;
        return v < min ? min : (v > max ? max : v);
    }

    /**
     * 把另一份片段的<b>全部字段</b>拷到本对象上（保留本对象的身份）。
     *
     * <p>用于「服务端广播回来时原地更新」：编辑界面持有的是某个片段对象的引用，
     * 若每次同步都换一批新对象，那个引用就变成孤儿——和 ⑤.1 里
     * {@code Plane.applyFrom} 换掉 widgets 是同一类 bug。见 AGENTS.md §5.5 第 25 条。</p>
     */
    public void copyFrom(SequenceClip o) {
        if (o == null) return;
        this.widgetId = o.widgetId;
        this.startSec = o.startSec;
        this.endSec = o.endSec;
        this.inAnim = o.inAnim;
        this.outAnim = o.outAnim;
        this.inDur = o.inDur;
        this.outDur = o.outDur;
        this.slideAngle = o.slideAngle;
        this.slideDistance = o.slideDistance;
        this.loopAnim = o.loopAnim;
        this.loopAmp = o.loopAmp;
        this.loopSpeed = o.loopSpeed;
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        if (widgetId != null) t.putUUID("widget", widgetId);
        t.putDouble("start", startSec);
        t.putDouble("end", endSec);
        t.putInt("inAnim", inAnim);
        t.putInt("outAnim", outAnim);
        t.putInt("loopAnim", loopAnim);
        t.putDouble("inDur", inDur);
        t.putDouble("outDur", outDur);
        t.putDouble("slideAngle", slideAngle);
        t.putDouble("slideDistance", slideDistance);
        t.putDouble("loopAmp", loopAmp);
        t.putDouble("loopSpeed", loopSpeed);
        return t;
    }

    public static SequenceClip load(CompoundTag t) {
        SequenceClip c = new SequenceClip();
        if (t.hasUUID("widget")) c.widgetId = t.getUUID("widget");
        c.startSec = t.getDouble("start");
        c.endSec = t.getDouble("end");
        c.inAnim = t.getInt("inAnim");
        c.outAnim = t.getInt("outAnim");
        c.loopAnim = t.getInt("loopAnim");
        c.inDur = t.contains("inDur") ? t.getDouble("inDur") : DEFAULT_ANIM_SECONDS;
        c.outDur = t.contains("outDur") ? t.getDouble("outDur") : DEFAULT_ANIM_SECONDS;
        c.slideAngle = t.getDouble("slideAngle");
        c.slideDistance = t.getDouble("slideDistance");
        c.loopAmp = t.contains("loopAmp") ? t.getDouble("loopAmp") : 8;
        c.loopSpeed = t.contains("loopSpeed") ? t.getDouble("loopSpeed") : 0.5;
        c.sanitize();
        return c;
    }
}

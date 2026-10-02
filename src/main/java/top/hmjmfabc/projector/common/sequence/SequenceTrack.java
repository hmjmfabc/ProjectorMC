package top.hmjmfabc.projector.common.sequence;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;
import top.hmjmfabc.projector.common.widget.Widget;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 【⑩】一条「流程」：平面上若干控件按时间轴依次登场的编排。
 *
 * <h2>播放语义（务必先读这一段）</h2>
 * <ul>
 *   <li>{@code playing == false} → <b>不套用时间轴</b>：所有控件按常规渲染
 *       （这是编辑态，方便你一次看到全部内容）；</li>
 *   <li>{@code playing == true} → 按 {@code offsetSec + (游戏刻差 ÷ 20)} 求当前时间，
 *       逐个控件算出动画状态；</li>
 *   <li>某个控件<b>不在任何片段里</b> → 一直显示（不受流程影响）；</li>
 *   <li><b>流程里"最后"的那个片段（endSec 最大）不出场</b>：它的控件在流程走完之后
 *       仍然留在平面上——这正是用户举的例子「第 1 秒显示 1，第 2~10 秒显示 2，
 *       10 秒后流程结束，2 会留在平面上」。因此播放不会在末尾自动停止，
 *       时间会继续走，最后一个控件就一直停在那里；</li>
 *   <li>时间轴长度固定 0~999 秒。</li>
 * </ul>
 *
 * <h2>为什么时间用「游戏刻」当锚点</h2>
 * <p>与服务端权威模型一致：所有客户端读同一个 {@code level.getGameTime()}，
 * 因此无论谁在什么时候进服，看到的动画进度完全一致，不需要任何额外同步包；
 * 单人存档按 Esc 暂停时游戏刻不走，动画也会自然暂停。</p>
 */
public final class SequenceTrack {

    /** 时间轴可显示的最大秒数。 */
    public static final double MAX_SECONDS = SequenceClip.MAX_SECONDS;

    private final List<SequenceClip> clips = new ArrayList<>();

    /** 是否正在播放（true 才会套用时间轴）。 */
    public boolean playing;
    /** 播放起点（游戏刻）。 */
    public long startTick;
    /** 起点的秒偏移（暂停/继续时用它把时间接上去）。 */
    public double offsetSec;

    public List<SequenceClip> clips() {
        return clips;
    }

    public boolean isEmpty() {
        return clips.isEmpty();
    }

    public int size() {
        return clips.size();
    }

    /** 当前流程时间（秒）。未播放时返回 {@link #offsetSec}。 */
    public double timeSec(long gameTime) {
        if (!playing) return offsetSec;
        double elapsed = (gameTime - startTick) / 20.0;
        if (elapsed < 0) elapsed = 0;
        return offsetSec + elapsed;
    }

    /** 时间轴上所有片段的共同终点（秒）——也就是「流程结束」的时刻。 */
    public double maxEndSec() {
        double m = 0;
        for (SequenceClip c : clips) {
            m = Math.max(m, c.endSec);
        }
        return m;
    }

    /** 找到承载某个控件的片段；没有返回 null。 */
    @Nullable
    public SequenceClip clipOf(UUID widgetId) {
        if (widgetId == null) return null;
        for (SequenceClip c : clips) {
            if (widgetId.equals(c.widgetId)) return c;
        }
        return null;
    }

    /** 找出 endSec 最大的那个片段（它不出场）。 */
    @Nullable
    public SequenceClip lastClip() {
        SequenceClip best = null;
        for (SequenceClip c : clips) {
            if (best == null || c.endSec > best.endSec) best = c;
        }
        return best;
    }

    public void add(SequenceClip clip) {
        if (clip == null) return;
        clip.sanitize();
        // 一个控件在时间轴上只保留一条（再添加就是改时间，不是叠加）
        clips.removeIf(c -> clip.widgetId != null && clip.widgetId.equals(c.widgetId));
        clips.add(clip);
    }

    public void remove(UUID widgetId) {
        clips.removeIf(c -> widgetId != null && widgetId.equals(c.widgetId));
    }

    public void clearClips() {
        clips.clear();
    }

    /** 丢弃引用了已不存在控件的片段（控件被删掉后调用）。 */
    public void prune(List<Widget> widgets) {
        clips.removeIf(c -> {
            if (c.widgetId == null) return true;
            for (Widget w : widgets) {
                if (c.widgetId.equals(w.id)) return false;
            }
            return true;
        });
    }

    /** 开始播放（从头）。 */
    public void playFromStart(long gameTime) {
        offsetSec = 0;
        startTick = gameTime;
        playing = true;
    }

    /** 继续播放（从 {@link #offsetSec} 接着走）。 */
    public void resume(long gameTime) {
        startTick = gameTime;
        playing = true;
    }

    /** 暂停：把当前时间冻结进 {@link #offsetSec}。 */
    public void pause(long gameTime) {
        offsetSec = timeSec(gameTime);
        playing = false;
    }

    /** 停止并归零。 */
    public void stop() {
        playing = false;
        offsetSec = 0;
    }

    /** 跳到某个时刻（编辑器里拖动播放头用）。 */
    public void seek(double sec, long gameTime) {
        offsetSec = SequenceClip.clamp(sec, 0, MAX_SECONDS);
        startTick = gameTime;
    }

    /**
     * 求出某个控件这一刻的动画状态。
     *
     * <p>规则见类注释。特别地：<b>末尾片段不出场</b>，
     * 而且它在流程结束之后依然可见。</p>
     */
    public SequenceAnim.State stateOf(@Nullable Widget widget, long gameTime) {
        if (widget == null) return SequenceAnim.State.NORMAL;
        // 未播放 = 全部按常规渲染（编辑态）
        if (!playing) return SequenceAnim.State.NORMAL;
        SequenceClip clip = clipOf(widget.id);
        // 不在流程里的控件不受影响
        if (clip == null) return SequenceAnim.State.NORMAL;

        double t = timeSec(gameTime);
        SequenceClip last = lastClip();
        boolean isLast = last != null && last.widgetId != null && last.widgetId.equals(clip.widgetId);

        // 尚未开始 -> 不显示
        if (t < clip.startSec) return SequenceAnim.State.HIDDEN;
        // 已经结束：末尾片段留下，其余消失
        boolean ended = t > clip.endSec;
        if (ended && !isLast) return SequenceAnim.State.HIDDEN;

        double dx = 0, dy = 0, dDepth = 0, scale = 1, alpha = 1, rot = 0;
        double scaleX = 1, scaleY = 1;

        // 位移类动画的「按控件尺寸自动取」距离：与旧版逐字一致（最长边 × 0.8）
        double autoDist = Math.max(widget.w, widget.h) * 0.8;
        double dist = clip.slideDistance > 0 ? clip.slideDistance : autoDist;

        // 下面三段都是「把一份效果状态叠加上去」：位移相加、缩放与透明度相乘。
        // 效果本身由 SequenceAnim 给出 —— 动画选择界面的预览用的是同一份函数。
        // ---- 入场 ----
        double inP = clip.inDur <= 0.0001 ? 1.0 : (t - clip.startSec) / clip.inDur;
        if (!ended || isLast) {
            SequenceAnim.State e = SequenceAnim.inEffect(
                    clip.inAnim, inP, clip.slideAngle, dist);
            dx += e.dx();
            dy += e.dy();
            dDepth += e.dDepth();
            scale *= e.scale();
            scaleX *= e.scaleX();
            scaleY *= e.scaleY();
            alpha *= e.alpha();
            rot += e.rotDeg();
        }

        // ---- 出场（末尾片段不出场）----
        if (!isLast && clip.outDur > 0.0001 && t > clip.endSec - clip.outDur) {
            double outP = (t - (clip.endSec - clip.outDur)) / clip.outDur;
            SequenceAnim.State e = SequenceAnim.outEffect(
                    clip.outAnim, outP, clip.slideAngle, dist);
            dx += e.dx();
            dy += e.dy();
            dDepth += e.dDepth();
            scale *= e.scale();
            scaleX *= e.scaleX();
            scaleY *= e.scaleY();
            alpha *= e.alpha();
            rot += e.rotDeg();
        }

        // ---- 循环动画（与显示时长无关，用绝对时间驱动）----
        if (clip.loopAnim != SequenceAnim.LOOP_NONE && clip.loopSpeed > 0.001) {
            double phase = t * clip.loopSpeed * Math.PI * 2;
            SequenceAnim.State e = SequenceAnim.loopEffect(clip.loopAnim, phase, clip.loopAmp);
            dx += e.dx();
            dy += e.dy();
            dDepth += e.dDepth();
            scale *= e.scale();
            scaleX *= e.scaleX();
            scaleY *= e.scaleY();
            alpha *= e.alpha();
            rot += e.rotDeg();
        }

        return new SequenceAnim.State(true, dx, dy, dDepth, scale,
                SequenceAnim.clamp01(alpha), rot, scaleX, scaleY);
    }

    /**
     * 用服务端广播回来的另一份流程<b>原地</b>更新本对象。
     *
     * <p>同 widgetId 的片段<b>保留原对象引用</b>、只覆盖字段；其余按 incoming 重建。
     * 编辑界面（时间轴）持有的是片段对象的引用，换一批新对象就会让它指向孤儿：
     * 拖完长条之后画面毫无变化，但服务端其实已经收到了——极难排查。
     * 这与 {@code Plane.applyFrom} 里 widgets 的处理完全同理（AGENTS.md §5.5 第 25 条）。</p>
     */
    public void mergeFrom(@Nullable SequenceTrack other) {
        if (other == null) return;
        this.playing = other.playing;
        this.startTick = other.startTick;
        this.offsetSec = other.offsetSec;
        java.util.Map<UUID, SequenceClip> old = new java.util.HashMap<>();
        for (SequenceClip c : clips) {
            if (c.widgetId != null) old.put(c.widgetId, c);
        }
        List<SequenceClip> merged = new ArrayList<>(other.clips.size());
        for (SequenceClip fresh : other.clips) {
            SequenceClip keep = fresh.widgetId == null ? null : old.get(fresh.widgetId);
            if (keep != null && keep != fresh) {
                keep.copyFrom(fresh);
                merged.add(keep);
            } else {
                merged.add(fresh);
            }
        }
        clips.clear();
        clips.addAll(merged);
    }

    // ------------------------------------------------------------------
    // 序列化
    // ------------------------------------------------------------------

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        ListTag list = new ListTag();
        for (SequenceClip c : clips) {
            list.add(c.save());
        }
        t.put("clips", list);
        t.putBoolean("playing", playing);
        t.putLong("start", startTick);
        t.putDouble("offset", offsetSec);
        return t;
    }

    public static SequenceTrack load(CompoundTag t) {
        SequenceTrack track = new SequenceTrack();
        if (t == null) return track;
        ListTag list = t.getList("clips", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            SequenceClip c = SequenceClip.load(list.getCompound(i));
            if (c.widgetId != null) track.clips.add(c);
        }
        track.playing = t.getBoolean("playing");
        track.startTick = t.getLong("start");
        track.offsetSec = SequenceClip.clamp(t.getDouble("offset"), 0, MAX_SECONDS);
        return track;
    }
}

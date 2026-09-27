package top.hmjmfabc.projector.common.widget;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 控件基类。所有坐标/尺寸使用「画布单位」：1 个完整方块面 = 16 单位。
 *
 * <p>控件内容可在平面内任意平移、缩放、旋转，锚点固定为控件外接矩形的左下角。
 * 旋转以「锚点为中心」进行逆时针旋转（从平面外侧看），因为这样命中检测
 * （逆变换）最简单，也不会因旋转而改变控件在画布中的落点。</p>
 */
public abstract class Widget {

    public static final int KIND_TEXT = 0;
    public static final int KIND_IMAGE = 1;
    public static final int KIND_VIDEO = 2;
    public static final int KIND_CLOCK = 3;
    public static final int KIND_WEATHER = 4;
    public static final int KIND_PROGRESS = 5;
    /** 【⑧】计时器（正计时 / 倒计时）。 */
    public static final int KIND_TIMER = 6;
    /** 【③】排行榜（任意计分板项）。 */
    public static final int KIND_LEADERBOARD = 7;
    /** 【⑪】棋类游戏（井字棋/五子棋/象棋/围棋/军棋/国际象棋）。 */
    public static final int KIND_CHESS = 8;

    /** 【27.1.1】音乐控件（本地音频 / 网易云音乐）。 */
    public static final int KIND_MUSIC = 9;

    public static final String[] KIND_IDS = {
            "text", "image", "video", "clock", "weather", "progress", "timer", "leaderboard",
            "chess", "music"};

    public UUID id = UUID.randomUUID();
    /** 锚点（左下角）在画布中的坐标。 */
    public double x, y;
    /** 尺寸（画布单位）。 */
    public double w, h;
    /** 旋转角度（度，逆时针，绕锚点）。 */
    public double rot;
    /**
     * 沿外法线的分层序号（不是长度单位！）。
     * 实际偏移 = zOff x {@code PlaneRenderContext.LAYER_STEP} 个方块，
     * 用于让同一平面上相互重叠的控件错开、避免 Z-fighting。
     */
    public double zOff;
    /** 不透明度 0~1。 */
    public float alpha = 1.0f;
    /** 控件背景色（ARGB，0 = 透明）。所有控件类型都支持。 */
    public int background = 0;

    public abstract int kind();

    public String kindId() {
        return KIND_IDS[kind()];
    }

    public double minX() {
        return x;
    }

    public double minY() {
        return y;
    }

    public double maxX() {
        return x + w;
    }

    public double maxY() {
        return y + h;
    }

    public double centerX() {
        return x + w / 2;
    }

    public double centerY() {
        return y + h / 2;
    }

    /** 未旋转时的包围盒（画布坐标）。 */
    public double[] aabb() {
        return new double[]{minX(), minY(), maxX(), maxY()};
    }

    /**
     * 命中检测。{@code px,py} 为画布坐标；{@code tolerance} 为外扩容差。
     * 内部会把点绕锚点逆旋转回未旋转坐标系。
     */
    public boolean hitTest(double px, double py, double tolerance) {
        double theta = Math.toRadians(-rot);
        double dx = px - x, dy = py - y;
        double cos = Math.cos(theta), sin = Math.sin(theta);
        double rx = x + dx * cos - dy * sin;
        double ry = y + dx * sin + dy * cos;
        double[] b = aabb();
        return rx >= b[0] - tolerance && ry >= b[1] - tolerance && rx <= b[2] + tolerance && ry <= b[3] + tolerance;
    }

    /** 把画布坐标点转换到「未旋转的本控件坐标系」中的相对位置。 */
    public double[] toLocal(double px, double py) {
        double theta = Math.toRadians(-rot);
        double dx = px - x, dy = py - y;
        double cos = Math.cos(theta), sin = Math.sin(theta);
        return new double[]{dx * cos - dy * sin, dx * sin + dy * cos};
    }

    /** 将本控件坐标系中的方向向量旋转到画布坐标系。 */
    public double[] rotateVector(double lx, double ly) {
        double theta = Math.toRadians(rot);
        double cos = Math.cos(theta), sin = Math.sin(theta);
        return new double[]{lx * cos - ly * sin, lx * sin + ly * cos};
    }

    public void setPos(double nx, double ny) {
        this.x = nx;
        this.y = ny;
    }

    public void setSize(double nw, double nh) {
        this.w = Math.max(0.25, nw);
        this.h = Math.max(0.25, nh);
    }

    /** 控件内容是否需要在画布上「显示背景」——目前一律透明。 */
    public boolean isMedia() {
        return kind() == KIND_IMAGE || kind() == KIND_VIDEO;
    }

    /** 用于界面列表展示的名称。 */
    public abstract String label();

    /**
     * 【①】这个控件引用了哪些字体 ID。
     *
     * <p>服务端会拿它做「联机字体核验」：服务端没装的字体，普通玩家不许用
     * （管理员可以现场上传——上传流程会把字体落到 {@code <存档>/projector/fonts/}）。
     * 返回空列表表示这个控件不含文本，无需核验。默认实现为空。</p>
     */
    public java.util.List<String> fontIds() {
        return java.util.List.of();
    }

    /** 复制通用字段。 */
    public void copyCommonFrom(Widget o) {
        this.x = o.x;
        this.y = o.y;
        this.w = o.w;
        this.h = o.h;
        this.rot = o.rot;
        this.zOff = o.zOff;
        this.alpha = o.alpha;
        // 背景色也要一并拷贝，否则在编辑器里改背景永远不会生效
        this.background = o.background;
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putUUID("id", id);
        t.putInt("kind", kind());
        t.putDouble("x", x);
        t.putDouble("y", y);
        t.putDouble("w", w);
        t.putDouble("h", h);
        t.putDouble("rot", rot);
        t.putDouble("z", zOff);
        t.putFloat("alpha", alpha);
        t.putInt("bg", background);
        saveExtra(t);
        return t;
    }

    protected abstract void saveExtra(CompoundTag t);

    /** 读取子类特有字段（基类为空实现，子类按需重写）。 */
    public void loadExtra(CompoundTag t) {
    }

    public void loadCommon(CompoundTag t) {
        if (t.hasUUID("id")) id = t.getUUID("id");
        x = t.getDouble("x");
        y = t.getDouble("y");
        w = t.getDouble("w");
        h = t.getDouble("h");
        rot = t.getDouble("rot");
        zOff = t.getDouble("z");
        alpha = t.contains("alpha") ? t.getFloat("alpha") : 1.0f;
        background = t.contains("bg") ? t.getInt("bg") : 0;
    }

    @Nullable
    public static Widget create(int kind) {
        return switch (kind) {
            case KIND_TEXT -> new TextWidget();
            case KIND_IMAGE -> new ImageWidget();
            case KIND_VIDEO -> new VideoWidget();
            case KIND_CLOCK -> new ClockWidget();
            case KIND_WEATHER -> new WeatherWidget();
            case KIND_PROGRESS -> new ProgressWidget();
            case KIND_TIMER -> new TimerWidget();
            case KIND_LEADERBOARD -> new LeaderboardWidget();
            case KIND_CHESS -> new ChessWidget();
            case KIND_MUSIC -> new MusicWidget();
            default -> null;
        };
    }
}

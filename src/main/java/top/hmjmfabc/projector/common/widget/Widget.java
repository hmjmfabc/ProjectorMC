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

    /** 【27.2】网页控件（可选的客户端前置模组 MCEF 渲染网页）。 */
    public static final int KIND_WEB = 10;

    /**
     * 【27.2.2】展示框（物品的「二向化」投影，用户要求，简称「展示框」）。
     * <p>把选定的物品投影到平面上：右键放入、左键取出，轮廓可调色/旋转/缩放。</p>
     */
    public static final int KIND_ITEM = 11;

    public static final String[] KIND_IDS = {
            "text", "image", "video", "clock", "weather", "progress", "timer", "leaderboard",
            "chess", "music", "web", "item"};

    public UUID id = UUID.randomUUID();
    /** 锚点（左下角）在画布中的坐标。 */
    public double x, y;
    /** 尺寸（画布单位）。 */
    public double w, h;
    /**
     * 旋转角度（度，逆时针）。
     *
     * <p>⚠ <b>旋转轴心是控件的中心</b>（{@link #rotX()}/{@link #rotY()}），
     * 不是左下角 —— 27.2.1-hotfix-144 统一的。</p>
     *
     * <p>「绕左下角转」是本项目一个千年老 bug 的根因：一旋转，控件就绕左下角甩出去
     * （180° 直接甩到左下方向的另一侧），玩家看到的是「转完位置就错了」；
     * 更糟的是渲染、选中框、命中检测三处各自用了不同的轴心，
     * 于是「框不跟着转」「框和内容对不上」。现在**所有**旋转都必须走
     * {@link #rotX()}/{@link #rotY()}/{@link #rotPt(double, double)}，
     * 不许再出现第二份轴心。</p>
     */
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

    // ------------------------------------------------------------------
    // 旋转几何（**唯一真源**，27.2.1-hotfix-144）
    // ------------------------------------------------------------------

    /**
     * 旋转轴心（**画布坐标**）的 x —— 控件中心。
     *
     * <p>⚠ 两套「中心」在这里并存，别混（本文件最容易被改坏的地方）：</p>
     * <ul>
     *   <li>{@link #rotX()}/{@link #rotY()} = **画布坐标**的中心 —— 给
     *       {@code TextRenderer.drawInBox(…, pivotX, pivotY)} 与
     *       {@code TextRenderer.rot(…) } 当轴心（它们收的是画布坐标）；</li>
     *   <li>{@link #localRotX()}/{@link #localRotY()} = **局部坐标**（0..w）的中心 ——
     *       给 {@link #rotPt} 这类「局部 → 画布」的换算当轴心。</li>
     * </ul>
     * <p>曾经把 {@code rotX()} 改成返回局部值，结果所有 `drawInBox` 的轴心都偏了
     * （音乐标题/时间、排行榜……整体挪到左下一个 (x, y)）。别再改它的语义。</p>
     */
    public double rotX() {
        return x + w / 2.0;
    }

    /** 旋转轴心的 y（**画布坐标**，见 {@link #rotX()}）。 */
    public double rotY() {
        return y + h / 2.0;
    }

    /** 旋转轴心的 x（**局部坐标**，0..w）—— 只给局部↔画布的换算用。 */
    public double localRotX() {
        return w / 2.0;
    }

    /** 旋转轴心的 y（**局部坐标**，0..h）。 */
    public double localRotY() {
        return h / 2.0;
    }

    /**
     * 把「控件局部坐标」（相对左下角，未旋转）映射到**画布坐标**：
     * 绕局部中心旋转，再加上控件左下角。
     */
    public double[] rotPt(double lx, double ly) {
        double[] r = rotAround(localRotX(), localRotY(), rot, lx, ly);
        return new double[]{x + r[0], y + r[1]};
    }

    /** 把「控件局部坐标」的四个角映射到画布（顺序：左下、右下、右上、左上）。 */
    public double[][] rotCorners() {
        return new double[][]{rotPt(0, 0), rotPt(w, 0), rotPt(w, h), rotPt(0, h)};
    }

    /** 旋转后的包围盒（画布坐标，轴对齐）。 */
    public double[] rotatedAabb() {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (double[] c : rotCorners()) {
            minX = Math.min(minX, c[0]);
            minY = Math.min(minY, c[1]);
            maxX = Math.max(maxX, c[0]);
            maxY = Math.max(maxY, c[1]);
        }
        return new double[]{minX, minY, maxX, maxY};
    }

    /**
     * 把**画布坐标**逆旋转回「未旋转的局部坐标系」（相对左下角）—— {@link #rotPt} 的逆运算。
     *
     * <p>要求「值域仍是画布坐标系」的调用方（例如棋类：{@code centerOf} 给的是画布坐标、
     * 只差一个旋转）用 {@link #toLocalAbs}；要求「相对左下角的偏移」的调用方
     * （{@code hitButton}/{@code hitControl}/{@code seekFractionAt}…）用 {@link #toLocal}。</p>
     */
    public double[] toLocal(double px, double py) {
        return toLocalAbs(px, py);
    }

    /**
     * 把**画布坐标**映射回「未旋转的**局部**坐标系」——即几何原点 {@code ox/oy} 所在的坐标系。
     *
     * <p>算法顺序是铁的：**先减左下角**（画布 → 局部），**再绕局部中心逆旋转**。
     * 两个坐标系（画布 / 相对左下角）混着用就会整体偏 {@code (x, y)} ——
     * 玩家看到的就是「棋类控件的棋盘与实际操作空间偏移很严重」，本轮栽过一次。</p>
     *
     * <p>与 {@link #rotPt}（局部 → 画布）正好互为逆运算：
     * {@code toLocalAbs(rotPt(lx, ly)) == (lx, ly)}。</p>
     */
    public double[] toLocalAbs(double px, double py) {
        return rotAround(localRotX(), localRotY(), -rot, px - x, py - y);
    }

    /** 绕任意轴心逆时针旋转一个点（公用的那一条公式，别再抄第四遍）。 */
    public static double[] rotAround(double ax, double ay, double rotDeg, double px, double py) {
        if (rotDeg == 0.0) {
            return new double[]{px, py};
        }
        double theta = Math.toRadians(rotDeg);
        double cos = Math.cos(theta), sin = Math.sin(theta);
        double dx = px - ax, dy = py - ay;
        return new double[]{ax + dx * cos - dy * sin, ay + dx * sin + dy * cos};
    }

    /**
     * 命中检测。{@code px,py} 为画布坐标；{@code tolerance} 为外扩容差。
     *
     * <p>把点**绕控件中心逆旋转**回局部坐标再判矩形 —— 与渲染用的是同一个轴心
     * （以前渲染绕左下角、命中绕左下角，但选中框绕中心，三处对不上）。</p>
     */
    public boolean hitTest(double px, double py, double tolerance) {
        // toLocalAbs 的值域是**局部坐标系**（相对左下角），所以这里必须拿
        // 「以左下角为原点的矩形」去比 —— 用 aabb()（**画布**坐标）比会整体偏 (x, y)。
        double[] l = toLocalAbs(px, py);
        return l[0] >= -tolerance && l[1] >= -tolerance
                && l[0] <= w + tolerance && l[1] <= h + tolerance;
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
            case KIND_WEB -> new WebWidget();
            case KIND_ITEM -> new ItemWidget();
            default -> null;
        };
    }
}

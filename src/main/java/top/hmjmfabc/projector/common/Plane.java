package top.hmjmfabc.projector.common;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import top.hmjmfabc.projector.common.sequence.SequenceTrack;
import top.hmjmfabc.projector.common.widget.Widget;
import top.hmjmfabc.projector.common.widget.Widgets;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 一个「平面」：由同一朝向、彼此连通、大小一致的一组方块面拼成的画布。
 */
public final class Plane {

    public UUID id;
    public ResourceLocation dimension;
    public BlockPos anchor;
    public Direction face;
    /** 面内原点偏移（画布单位），用于不完整平面。 */
    public double originU, originV;
    /**
     * 画布整体平移量。
     *
     * <p>画布坐标 (0,0) 就是玩家准心命中的那个点。若平面向左/向下延伸，
     * 方块矩形会落到负坐标；为了渲染方便这里把它们整体平移成正数，
     * 绘制时再加上这个偏移即可。</p>
     */
    public double canvasOffsetX, canvasOffsetY;
    public int width, height;
    /** 该平面上所有面的碰撞盒是否都是完整立方体。 */
    public boolean fullFaces;
    /**
     * 锚点方块在该面上的「表面位置」（沿外法线的局部坐标，0~1）。
     * 完整方块是 0 或 1；铁砧顶面是 0.625 之类。
     *
     * <p>贴面深度以它为 0 参照点——因为它才是「画布原点所在的那张平面」。</p>
     */
    public double anchorSurface;

    /**
     * 需要重建（仅运行时，不进存档）。
     *
     * <p>当存档里的画布坐标系是按<b>旧版错误的贴面轴向</b>算出来的时候置位。
     * 那种数据无法自动换算（哪个方向算"左"已经丢了），只能拿当前世界重新圈一次。
     * 详见 {@code ServerEvents#migrateLegacyPlanes}。</p>
     */
    public transient boolean needsRebuild;

    /** 平面上互异的列数（由各方块面的网格列号统计，用于界面自校验）。 */
    public int blockColumns() {
        java.util.HashSet<Integer> set = new java.util.HashSet<>();
        for (PlaneBlock b : blocks.values()) set.add(b.col());
        return set.size();
    }

    /** 平面上互异的行数（由各方块面的网格行号统计，用于界面自校验）。 */
    public int blockRows() {
        java.util.HashSet<Integer> set = new java.util.HashSet<>();
        for (PlaneBlock b : blocks.values()) set.add(b.row());
        return set.size();
    }

    /** 构建时收集到的方块面数量。 */
    /** 构建时得到的列数 / 行数（应为该方向上的格数）。 */
    /** 每列 / 每行的跨度之和（画布单位）。 */

    public String name = "";
    public UUID creator;
    public String creatorName = "";

    /** 平面保护：任何人都不能破坏组成该平面的方块。 */
    public boolean protectBlocks;
    /** 内容保护：非管理员不得增删/修改控件。 */
    public boolean protectContent;
    /** 平面误挖掘警告：破坏方块需同时按住 Shift。 */
    public boolean miningWarning;

    public final List<Widget> widgets = new ArrayList<>();
    /**
     * 【⑩】流程时间轴。默认是空的 -> 不播放 -> 所有控件按常规渲染，
     * 所以对旧存档与不用流程的玩家<b>行为完全不变</b>。
     */
    public SequenceTrack sequence = new SequenceTrack();
    public final LinkedHashMap<Long, PlaneBlock> blocks = new LinkedHashMap<>();

    // ---- 运行时缓存 ----
    private transient PlaneCanvas canvasCache;
    private transient AABB boundsCache;

    public Plane() {
    }

    public PlaneCanvas canvas() {
        if (canvasCache == null || canvasCache.width != width || canvasCache.height != height) {
            canvasCache = new PlaneCanvas(anchor, face, originU, originV, width, height);
        }
        return canvasCache;
    }

    public boolean contains(BlockPos pos) {
        return blocks.containsKey(pos.asLong());
    }

    public boolean isIncomplete() {
        return !fullFaces;
    }

    public int blockCount() {
        return blocks.size();
    }

    /** 平面的世界空间包围盒（略放大以便渲染裁剪）。 */
    public AABB bounds() {
        if (boundsCache == null) {
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
            for (long key : blocks.keySet()) {
                BlockPos p = BlockPos.of(key);
                double pad = 1.25;
                minX = Math.min(minX, p.getX() - pad);
                minY = Math.min(minY, p.getY() - pad);
                minZ = Math.min(minZ, p.getZ() - pad);
                maxX = Math.max(maxX, p.getX() + 1 + pad);
                maxY = Math.max(maxY, p.getY() + 1 + pad);
                maxZ = Math.max(maxZ, p.getZ() + 1 + pad);
            }
            if (minX > maxX) {
                minX = anchor.getX();
                minY = anchor.getY();
                minZ = anchor.getZ();
                maxX = minX + 1;
                maxY = minY + 1;
                maxZ = minZ + 1;
            }
            boundsCache = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        }
        return boundsCache;
    }

    public void invalidateCache() {
        boundsCache = null;
        canvasCache = null;
    }

    /**
     * 世界里某点（须位于面所在平面附近）对应的画布 x 坐标。
     *
     * <p>注意单位：世界坐标点乘得到的是「方块数」，而控件坐标用的是画布单位
     * （1 方块 = {@link PlaneCanvas#UNITS_PER_BLOCK} 单位）。这里必须乘上去，
     * 否则命中检测会差 16 倍。</p>
     */
    public double canvasX(Vec3 world) {
        Direction r = BlockFace.right(face);
        Vec3 o = canvas().originWorld();
        double blocks = (world.x - o.x) * r.getStepX() + (world.y - o.y) * r.getStepY()
                + (world.z - o.z) * r.getStepZ();
        return blocks * PlaneCanvas.UNITS_PER_BLOCK + canvasOffsetX;
    }

    /** 世界里某点对应的画布 y 坐标（单位同上）。 */
    public double canvasY(Vec3 world) {
        Direction u = BlockFace.up(face);
        Vec3 o = canvas().originWorld();
        double blocks = (world.x - o.x) * u.getStepX() + (world.y - o.y) * u.getStepY()
                + (world.z - o.z) * u.getStepZ();
        return blocks * PlaneCanvas.UNITS_PER_BLOCK + canvasOffsetY;
    }

    /** 世界中的某个点位于哪个方块面上（找不到返回 null）。 */
    public PlaneBlock blockAt(double cx, double cy) {
        for (PlaneBlock b : blocks.values()) {
            if (b.contains(cx, cy)) return b;
        }
        return null;
    }

    /**
     * 命中检测：世界坐标 -> 控件。{@code tolerance} 为容差（画布单位）。
     */
    public Widget widgetAt(Vec3 world, double tolerance) {
        double cx = canvasX(world);
        double cy = canvasY(world);
        for (int i = widgets.size() - 1; i >= 0; i--) {
            Widget w = widgets.get(i);
            if (w.hitTest(cx, cy, tolerance)) return w;
        }
        return null;
    }

    /** 按 id 查找控件。 */
    public Widget widgetById(UUID wid) {
        for (Widget w : widgets) {
            if (w.id.equals(wid)) return w;
        }
        return null;
    }

    public boolean removeWidget(UUID wid) {
        return widgets.removeIf(w -> w.id.equals(wid));
    }

    /**
     * 用另一份数据<b>原地</b>更新本对象。
     *
     * <p>客户端每次收到服务端广播都会解析出一份新的 {@code Plane}。
     * 如果直接把缓存列表里的元素替换成新对象，任何已经持有旧引用的界面
     * （平面对话框、编辑器）就会永远停留在打开那一刻的快照上——
     * 新加的控件看不到、保护开关用过期值发送。原地更新可以彻底避免这个问题。</p>
     */
    public void applyFrom(Plane other) {
        if (other == null || !this.id.equals(other.id)) return;
        this.dimension = other.dimension;
        this.anchor = other.anchor;
        this.face = other.face;
        this.originU = other.originU;
        this.originV = other.originV;
        this.width = other.width;
        this.height = other.height;
        this.fullFaces = other.fullFaces;
        // 同步过来的旧数据可能没有这个字段（0 会让内容整格偏移），此时按面朝向取默认值
        this.canvasOffsetX = other.canvasOffsetX;
        this.canvasOffsetY = other.canvasOffsetY;
        this.anchorSurface = (Double.isNaN(other.anchorSurface) || other.anchorSurface < 0
                || other.anchorSurface > 1)
                ? defaultAnchorSurfaceFor(other.face)
                : other.anchorSurface;
        this.name = other.name;
        this.creator = other.creator;
        this.creatorName = other.creatorName;
        this.protectBlocks = other.protectBlocks;
        this.protectContent = other.protectContent;
        this.miningWarning = other.miningWarning;
        this.blocks.clear();
        this.blocks.putAll(other.blocks);
        // 控件必须「原地合并」而不是换一批新对象，理由见 mergeWidgets 的注释。
        mergeWidgets(other.widgets);
        // 【⑩】流程也要一起同步，否则多人游戏里只有「改流程的那个人」能看到动画。
        // 【必须原地合并】时间轴编辑界面持有 SequenceClip 的引用，直接换掉整份
        // 流程会让它指向孤儿——拖完长条画面没反应，但服务端其实已经收到了。
        // 与上面 widgets 的处理完全同理（AGENTS.md §5.5 第 25 条）。
        if (this.sequence == null) {
            this.sequence = new SequenceTrack();
        }
        this.sequence.mergeFrom(other.sequence);
        this.sequence.prune(this.widgets);
        invalidateCache();
    }

    /**
     * 用 {@code incoming} 原地更新本平面的控件列表。
     *
     * <p><b>为什么不能直接 {@code clear() + addAll()}：</b>
     * {@code PlaneCache.handleSync} 刻意「原地更新 Plane 对象」而不是替换引用，
     * 目的就是让已经打开的平面对话框 / 控件编辑器实时反映服务端状态。
     * 但如果这里把 widgets 换成一批<b>新对象</b>，那些界面持有的 widget 引用
     * 立刻变成孤儿，于是会出现两个非常难查的现象：</p>
     * <ul>
     *   <li>点「重置进度到 0%」：服务端确实重置并广播了，可编辑器预览的仍是旧对象
     *       → 看起来「按钮没用」；</li>
     *   <li>关闭编辑器时提交的 {@code updateWidget} 用的是旧对象的数据
     *       → 又把服务端刚做好的修改原样覆盖回去。</li>
     * </ul>
     * <p>这正是 ⑤.1「时间不支持调节 / 重置按钮无效」的根因。
     * 同 id 且同类型的控件<b>保留原对象引用</b>、只覆盖字段；其余按 incoming 重建。
     * 覆盖语义与 {@code ServerNetHandler.updateWidget} 完全一致（公共 + 类型特有字段）。</p>
     */
    public void mergeWidgets(List<Widget> incoming) {
        Map<UUID, Widget> old = new HashMap<>();
        for (Widget w : this.widgets) {
            if (w != null && w.id != null) old.put(w.id, w);
        }
        List<Widget> merged = new ArrayList<>(incoming == null ? 0 : incoming.size());
        if (incoming != null) {
            for (Widget fresh : incoming) {
                if (fresh == null) continue;
                Widget keep = fresh.id == null ? null : old.get(fresh.id);
                if (keep != null && keep != fresh && keep.kind() == fresh.kind()) {
                    CompoundTag snapshot = fresh.save();
                    keep.copyCommonFrom(fresh);
                    keep.loadExtra(snapshot);
                    merged.add(keep);
                } else {
                    merged.add(fresh);
                }
            }
        }
        this.widgets.clear();
        this.widgets.addAll(merged);
    }

    /**
     * 反推 {@link #anchorSurface}（用于兼容缺失该字段的旧存档）。
     *
     * <p>推导：锚点方块必然属于本平面，而 {@code depth} 是「本方块表面」相对
     * 「锚点方块表面」的偏移。用锚点方块自身的网格坐标加上它的 depth，
     * 就得到锚点表面的局部位置（0~1）。</p>
     */
    public double inferAnchorSurface() {
        return defaultAnchorSurface();
    }

    /** 完整方块面上的默认锚点表面位置（UP/SOUTH/EAST 为 1，其余为 0）。 */
    private double defaultAnchorSurface() {
        return defaultAnchorSurfaceFor(face);
    }

    static double defaultAnchorSurfaceFor(Direction face) {
        // 完整方块：面平面就在 max 侧（EAST/SOUTH/UP）时距离为 1，
        // 在 min 侧（WEST/DOWN/NORTH）时距离为 0
        return switch (face) {
            case UP, SOUTH, EAST -> 1.0;
            default -> 0.0;
        };
    }

    /** 平面内容是否为空（用于删除时的提示）。 */
    public boolean isEmpty() {
        return widgets.isEmpty();
    }

    public String displayName() {
        if (name != null && !name.isEmpty()) return name;
        return "\u672a\u547d\u540d"; // 未命名
    }

    // ------------------------------------------------------------------
    // 序列化
    // ------------------------------------------------------------------

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putUUID("id", id);
        t.putString("dim", dimension.toString());
        t.putLong("anchor", anchor.asLong());
        t.putString("face", face.getName());
        t.putDouble("ou", originU);
        t.putDouble("ov", originV);
        t.putDouble("as", anchorSurface);
        // 数据版本：2 = anchorSurface 的含义改为「从方块最小角沿外法线到面平面的距离」
        // （此前 min 侧的 DOWN/WEST/NORTH 被错误地存成了 1，会让内容离开方块一格）
        // 3 = right 轴修正为右手系（DOWN/WEST/EAST 曾有镜像缺陷）
        t.putInt("v", 3);
        t.putDouble("cox", canvasOffsetX);
        t.putDouble("coy", canvasOffsetY);
        t.putInt("w", width);
        t.putInt("h", height);
        t.putBoolean("full", fullFaces);
        if (name != null && !name.isEmpty()) t.putString("name", name);
        if (creator != null) t.putUUID("creator", creator);
        if (creatorName != null && !creatorName.isEmpty()) t.putString("creatorName", creatorName);
        t.putBoolean("protectBlocks", protectBlocks);
        t.putBoolean("protectContent", protectContent);
        t.putBoolean("miningWarning", miningWarning);

        ListTag bs = new ListTag();
        for (Map.Entry<Long, PlaneBlock> e : blocks.entrySet()) {
            CompoundTag be = e.getValue().save();
            be.putLong("p", e.getKey());
            bs.add(be);
        }
        t.put("blocks", bs);

        ListTag ws = new ListTag();
        for (Widget w : widgets) {
            ws.add(Widgets.save(w));
        }
        t.put("widgets", ws);
        // 【⑩】流程时间轴（没有片段时也照存，保持结构稳定）
        t.put("sequence", sequence.save());
        return t;
    }

    public static Plane load(CompoundTag t) {
        Plane p = new Plane();
        if (t.hasUUID("id")) p.id = t.getUUID("id");
        else p.id = UUID.randomUUID();
        // 防御损坏存档：维度缺失/非法时视为不可用，由上层丢弃该平面
        p.dimension = ResourceLocation.tryParse(t.getString("dim"));
        if (p.dimension == null) {
            throw new IllegalArgumentException("平面数据缺少合法的维度字段");
        }
        p.anchor = BlockPos.of(t.getLong("anchor"));
        Direction f = Direction.byName(t.getString("face"));
        p.face = f == null ? Direction.NORTH : f;
        p.originU = t.getDouble("ou");
        p.originV = t.getDouble("ov");
        // 旧存档没有这个字段：用组成平面里最小的 surfaceDepth 反推，
        // 保证升级后内容依然贴在正确的位置上。
        int dataVersion = t.contains("v") ? t.getInt("v") : 1;
        p.anchorSurface = t.contains("as") ? t.getDouble("as") : Double.NaN;
        if (dataVersion < 3) {
            p.needsRebuild = true;
            // right 轴修正过（DOWN/WEST/EAST 曾因左手系而镜像 + 偏一格）：
            // 旧平面的画布坐标系是按旧轴算的，无法自动换算，
            // 需要玩家用平面对话框里的「重新圈选此平面」重建一次。
            top.hmjmfabc.projector.Projector.LOGGER.warn(
                    "[Projector] 平面 {} 是旧版本圈选的（朝向 {}），"
                            + "贴面轴向已修正，建议用「重新圈选此平面」重建一次以获得正确朝向。",
                    p.id.toString().substring(0, 8), p.face.getName());
        }
        if (dataVersion < 2) {
            // 旧数据里 NORTH / WEST / DOWN 的 anchorSurface 被错误地存成了 1，
            // 会把内容推到离方块一整格的位置。完整方块平面直接用朝向的默认值修正，
            // 不完整平面（台阶/铁砧）只能近似——玩家可以按「重新圈选此平面」精确重建。
            double fixed = defaultAnchorSurfaceFor(p.face);
            if (p.anchorSurface > 0.999 && Math.abs(p.anchorSurface - fixed) > 1.0e-6) {
                p.anchorSurface = fixed;
            }
        }
        p.canvasOffsetX = t.getDouble("cox");
        p.canvasOffsetY = t.getDouble("coy");
        p.width = Math.max(1, t.getInt("w"));
        p.height = Math.max(1, t.getInt("h"));
        p.fullFaces = t.getBoolean("full");
        p.name = t.getString("name");
        p.creator = t.hasUUID("creator") ? t.getUUID("creator") : null;
        p.creatorName = t.getString("creatorName");
        p.protectBlocks = t.getBoolean("protectBlocks");
        p.protectContent = t.getBoolean("protectContent");
        p.miningWarning = t.getBoolean("miningWarning");

        ListTag bs = t.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < bs.size(); i++) {
            CompoundTag be = bs.getCompound(i);
            p.blocks.put(be.getLong("p"), PlaneBlock.load(be));
        }
        ListTag ws = t.getList("widgets", Tag.TAG_COMPOUND);
        for (int i = 0; i < ws.size(); i++) {
            Widget w = Widgets.load(ws.getCompound(i));
            if (w != null) p.widgets.add(w);
        }
        // 【⑩】流程时间轴；旧存档没有这个键 -> 空流程（等于不启用，行为与以前完全一致）
        if (t.contains("sequence")) {
            p.sequence = SequenceTrack.load(t.getCompound("sequence"));
        }
        // 引用了已不存在控件的片段直接丢掉，避免时间轴上出现「幽灵长条」
        p.sequence.prune(p.widgets);
        if (Double.isNaN(p.anchorSurface)) {
            p.anchorSurface = p.inferAnchorSurface();
        }
        return p;
    }

    // ------------------------------------------------------------------
    // 构建
    // ------------------------------------------------------------------

    /**
     * 由一次「面命中」出发，泛洪出整个连通平面。
     *
     * @param maxBlocks 最大方块数，超出即停止扩张
     */
    public static Plane build(BlockGetter level, ResourceLocation dimension, TargetPicker.FaceHit hit, int maxBlocks) {
        if (level == null) {
            // 没有世界访问能力时（服务端复核路径的退化情况）只保留单个方块面
            LinkedHashMap<Long, TargetPicker.FaceHit> single = new LinkedHashMap<>();
            single.put(hit.pos().asLong(), hit);
            return assemble(dimension, single, hit.face(), hit);
        }
        Direction face = hit.face();
        Direction r = BlockFace.right(face);
        Direction u = BlockFace.up(face);

        double spanU = hit.spanU();
        double spanV = hit.spanV();

        LinkedHashMap<Long, TargetPicker.FaceHit> collected = new LinkedHashMap<>();
        collected.put(hit.pos().asLong(), hit);

        final int maxSpan = top.hmjmfabc.projector.ProjectorConfig.INSTANCE.maxPlaneSpan.get();
        // 圈选边界用的锚点方块类型；关掉 sameBlockOnly 时为 null（不限制材质）
        final net.minecraft.world.level.block.Block srcBlock =
                top.hmjmfabc.projector.ProjectorConfig.INSTANCE.sameBlockOnly.get()
                        ? level.getBlockState(hit.pos()).getBlock() : null;

        // ------------------------------------------------------------------
        // 矩形生长（替代原来的泛洪）
        //
        // 以前的泛洪是「沿任意共面的方块面无限扩散」，在一整面墙上会跑出
        // 254x9 这种细长条，在起伏的地形上还会圈出带洞的不规则形状
        //（实测某个平面 33x14 的包围盒里只有 166/462 个格子是实的，36%）。
        // 用包围盒当画布，尺寸自然离谱。
        //
        // 现在改成：以准心命中的那一格为起点，向四个方向逐条外扩边，
        // **一条边上所有格子都必须是合格的面，才把这条边并进来**。
        // 这样得到的一定是严丝合缝的矩形屏幕，尺寸也就可预期了。
        // ------------------------------------------------------------------
        int u0 = 0, u1 = 0, v0 = 0, v1 = 0;
        boolean grew = true;
        while (grew && collected.size() < maxBlocks) {
            grew = false;
            // +u / -u 两条边沿 v 方向排列；+v / -v 两条边沿 u 方向排列
            if (growEdge(level, hit, face, r, u, spanU, spanV, collected,
                    v0, v1, u1 + 1, false, maxSpan, maxBlocks, srcBlock)) {
                u1++;
                grew = true;
            }
            if (growEdge(level, hit, face, r, u, spanU, spanV, collected,
                    v0, v1, u0 - 1, false, maxSpan, maxBlocks, srcBlock)) {
                u0--;
                grew = true;
            }
            if (growEdge(level, hit, face, r, u, spanU, spanV, collected,
                    u0, u1, v1 + 1, true, maxSpan, maxBlocks, srcBlock)) {
                v1++;
                grew = true;
            }
            if (growEdge(level, hit, face, r, u, spanU, spanV, collected,
                    u0, u1, v0 - 1, true, maxSpan, maxBlocks, srcBlock)) {
                v0--;
                grew = true;
            }
        }

        final int spanMinU = u0, spanMaxU = u1, spanMinV = v0, spanMaxV = v1;

        if (maxSpan > 0 && (u1 - u0 >= maxSpan || v1 - v0 >= maxSpan)) {
            top.hmjmfabc.projector.Projector.LOGGER.info(
                    "[Projector] 平面已长到 {} 格上限（配置 planes.maxPlaneSpan），停止外扩；"
                            + "想要更大的屏幕请调高它。", maxSpan);
        }
        if (collected.size() >= maxBlocks) {
            top.hmjmfabc.projector.Projector.LOGGER.info(
                    "[Projector] 平面已达方块数上限 {}（配置 planes.maxPlaneBlocks），"
                            + "圈选就此停止；若这不是你想要的范围，请调高上限或换一面更规整的墙。",
                    maxBlocks);
        }
        return assemble(dimension, collected, face, hit);
    }

    /**
     * 尝试把「一条边」并进矩形。
     *
     * <p><b>这就是「3x2 悬空面板为什么正好圈成 3x2」的原因：</b>
     * 只要外扩那条边上有任何一格不是合格的面（空气、不同高度的方块、半砖、
     * 不同朝向），整条边就整体放弃——所以矩形不会跨过空隙、也不会绕到旁边的墙上。
     * 早期的泛洪算法是逐格扩散的，它会绕过空气继续找远处连通的方块，
     * 于是一块悬空的 3x2 面板能被圈成 33x6。</p>
     *
     * <p>{@code alongU=false} 时，这条边是固定 {@code fixedGu} 那一列、u 取 {@code lo..hi}
     * 的那一格；{@code alongU=true} 时是固定 {@code fixedGv} 那一行。
     * <b>整条边上每一格都必须是合格的面才并入</b>——这条规则正是「圈出来一定是矩形」
     * 的保证；只要有一格不合格，整条边就放弃（下次也不会再试，因为矩形没变）。</p>
     *
     * @return 是否成功并入了这条边
     */
    private static boolean growEdge(BlockGetter level, TargetPicker.FaceHit anchor, Direction face,
                                    Direction r, Direction u, double spanU, double spanV,
                                    LinkedHashMap<Long, TargetPicker.FaceHit> collected,
                                    int lo, int hi, int fixed, boolean alongU,
                                    int maxSpan, int maxBlocks,
                                    @Nullable net.minecraft.world.level.block.Block srcBlock) {
        if (maxSpan > 0 && Math.abs(fixed) > maxSpan) return false;
        if (maxSpan > 0 && (Math.abs(lo) > maxSpan || Math.abs(hi) > maxSpan)) return false;
        if (collected.size() + (hi - lo + 1) > maxBlocks) return false;

        // 先整条边验证，全部通过才写入，避免出现「半条边」被并进来
        List<TargetPicker.FaceHit> pending = new ArrayList<>(hi - lo + 1);
        for (int a = lo; a <= hi; a++) {
            int gu = alongU ? a : fixed;
            int gv = alongU ? fixed : a;
            BlockPos np = offsetBy(anchor.pos(), r, u, gu, gv);
            if (collected.containsKey(np.asLong())) return false;
            TargetPicker.FaceHit h = matchFaceAt(level, np, face, anchor, r, u, spanU, spanV, srcBlock);
            if (h == null) return false;
            pending.add(h);
        }
        for (TargetPicker.FaceHit h : pending) {
            collected.put(h.pos().asLong(), h);
        }
        return true;
    }

    /** 以锚点方块为原点、沿 {@code r}/{@code u} 偏移 {@code (gu,gv)} 格得到的方块坐标。 */
    private static BlockPos offsetBy(BlockPos anchor, Direction r, Direction u, int gu, int gv) {
        return anchor.offset(
                r.getStepX() * gu + u.getStepX() * gv,
                r.getStepY() * gu + u.getStepY() * gv,
                r.getStepZ() * gu + u.getStepZ() * gv);
    }

    /**
     * 判断 {@code np} 处是否存在一个「和锚点面尺寸、面内位置都一致」的合格面。
     *
     * <p>四道门槛：不是空气、不是流体、有实心碰撞盒、是整格方块。
     * 不要求同一种方块（石砖 + 木板可以连成同一块屏幕）。</p>
     */
    @Nullable
    private static TargetPicker.FaceHit matchFaceAt(BlockGetter level, BlockPos np, Direction face,
                                                    TargetPicker.FaceHit anchor, Direction r, Direction u,
                                                    double spanU, double spanV,
                                                    @Nullable net.minecraft.world.level.block.Block srcBlock) {
        net.minecraft.world.level.block.state.BlockState st = level.getBlockState(np);
        if (st.isAir()) return null;
        // 【关键】材质边界：这是「贴在大墙上的那块屏幕」与「整面墙」之间唯一的判据。
        // 没有它，任何整格方块都会连进来，3x2 的屏幕会一路长到 33x6。
        // 想用多种方块拼屏幕就把 planes.sameBlockOnly 关掉。
        if (srcBlock != null && st.getBlock() != srcBlock) return null;
        // 流体：水和岩浆碰撞盒为空但 getShape() 是整格，必须单独排除
        if (!st.getFluidState().isEmpty()) return null;
        net.minecraft.world.phys.shapes.VoxelShape shape =
                st.getCollisionShape(level, np, net.minecraft.world.phys.shapes.CollisionContext.empty());
        if (shape.isEmpty()) return null;
        // 必须是整格方块：*墙*方块、栅栏、铁栏杆的碰撞盒是细柱/薄片，不能算完整方块
        if (!TargetPicker.isFullCube(shape.bounds())) return null;
        // 【关键】这个面必须真的「露出来」。
        // 以前只判断「方块存在 + 是整格」，于是墙脚下面埋在土里的方块也被算进来——
        // 它们的南面/东面在几何上确实存在，但被前方的土挡住，根本看不见。
        // 表现就是「在墙上按 U，把墙底下的所有方块一并计入」。
        if (isFaceCovered(level, np, face)) return null;
        return pickOnSameFace(np, shape, face, anchor, r, u, spanU, spanV);
    }

    /** 该面正前方是否被一个实心整格方块挡住（挡住就说明这个面不露在外面）。 */
    private static boolean isFaceCovered(BlockGetter level, BlockPos pos, Direction face) {
        BlockPos front = pos.relative(face);
        net.minecraft.world.level.block.state.BlockState fs = level.getBlockState(front);
        if (fs.isAir()) return false;
        // 流体、草、火把这类不挡视线的：不算被挡住
        net.minecraft.world.phys.shapes.VoxelShape fshape =
                fs.getCollisionShape(level, front, net.minecraft.world.phys.shapes.CollisionContext.empty());
        if (fshape.isEmpty()) return false;
        return TargetPicker.isFullCube(fshape.bounds());
    }

    /** 在给定方块上找出与 {@code ref} 位于同一朝向、且矩形尺寸一致的面。 */
    private static TargetPicker.FaceHit pickOnSameFace(BlockPos np, net.minecraft.world.phys.shapes.VoxelShape shape,
                                                       Direction face, TargetPicker.FaceHit ref,
                                                       Direction r, Direction u, double spanU, double spanV) {
        if (shape == null) {
            return null;
        }
        for (AABB local : shape.toAabbs()) {
            AABB box = local.move(np);
            TargetPicker.FaceHit h = describeBox(np, box, face, shape.bounds());
            if (h == null) continue;
            if (Math.abs(h.spanU() - spanU) < 1.0e-3 && Math.abs(h.spanV() - spanV) < 1.0e-3
                    && Math.abs(h.faceMinU() - ref.faceMinU()) < 1.0e-3
                    && Math.abs(h.faceMinV() - ref.faceMinV()) < 1.0e-3) {
                return h;
            }
        }
        return null;
    }

    /** 把一个世界坐标盒子描述成「朝向 face 的面」。 */
    private static TargetPicker.FaceHit describeBox(BlockPos np, AABB box, Direction face, AABB fullBounds) {
        Direction r = BlockFace.right(face);
        Direction u = BlockFace.up(face);
        double lx0 = box.minX - np.getX(), lx1 = box.maxX - np.getX();
        double ly0 = box.minY - np.getY(), ly1 = box.maxY - np.getY();
        double lz0 = box.minZ - np.getZ(), lz1 = box.maxZ - np.getZ();
        double[] uv = uvOf(r, u, lx0, lx1, ly0, ly1, lz0, lz1);
        double u0 = Math.min(uv[0], uv[1]);
        double u1 = Math.max(uv[0], uv[1]);
        double v0 = Math.min(uv[2], uv[3]);
        double v1 = Math.max(uv[2], uv[3]);
        if (u1 - u0 < 1.0e-4 || v1 - v0 < 1.0e-4) return null;
        Vec3 center = new Vec3((box.minX + box.maxX) / 2, (box.minY + box.maxY) / 2, (box.minZ + box.maxZ) / 2);
        boolean incomplete = !TargetPicker.isFullCube(fullBounds);
        return new TargetPicker.FaceHit(np, face, center, box, u0, v0, u1, v1, incomplete);
    }

    private static double[] uvOf(Direction r, Direction u,
                                 double lx0, double lx1, double ly0, double ly1, double lz0, double lz1) {
        double u0, u1;
        switch (r.getAxis()) {
            case X -> {
                u0 = r.getAxisDirection() == Direction.AxisDirection.POSITIVE ? lx0 : lx1;
                u1 = r.getAxisDirection() == Direction.AxisDirection.POSITIVE ? lx1 : lx0;
            }
            case Y -> {
                u0 = r.getAxisDirection() == Direction.AxisDirection.POSITIVE ? ly0 : ly1;
                u1 = r.getAxisDirection() == Direction.AxisDirection.POSITIVE ? ly1 : ly0;
            }
            default -> {
                u0 = r.getAxisDirection() == Direction.AxisDirection.POSITIVE ? lz0 : lz1;
                u1 = r.getAxisDirection() == Direction.AxisDirection.POSITIVE ? lz1 : lz0;
            }
        }
        double v0, v1;
        switch (u.getAxis()) {
            case X -> {
                v0 = u.getAxisDirection() == Direction.AxisDirection.POSITIVE ? lx0 : lx1;
                v1 = u.getAxisDirection() == Direction.AxisDirection.POSITIVE ? lx1 : lx0;
            }
            case Y -> {
                v0 = u.getAxisDirection() == Direction.AxisDirection.POSITIVE ? ly0 : ly1;
                v1 = u.getAxisDirection() == Direction.AxisDirection.POSITIVE ? ly1 : ly0;
            }
            default -> {
                v0 = u.getAxisDirection() == Direction.AxisDirection.POSITIVE ? lz0 : lz1;
                v1 = u.getAxisDirection() == Direction.AxisDirection.POSITIVE ? lz1 : lz0;
            }
        }
        return new double[]{u0, u1, v0, v1};
    }

    private static Plane assemble(ResourceLocation dimension, LinkedHashMap<Long, TargetPicker.FaceHit> collected,
                                  Direction face, TargetPicker.FaceHit anchorHit) {
        Plane p = new Plane();
        p.id = UUID.randomUUID();
        p.dimension = dimension;
        p.face = face;
        p.anchor = anchorHit.pos().immutable();
        p.creator = null;

        Direction r = BlockFace.right(face);
        Direction u = BlockFace.up(face);

        // 必须在计算每块 depth 之前就定好锚点面的位置：
        // depthOf() 需要它作为 0 参照，否则整张平面的内容会沿着法线偏移整整一格。
        p.anchorSurface = normalDistanceToFace(face, anchorHit.box(), anchorHit.pos());

        // 网格坐标：以「锚点方块」为原点的相对网格索引（很重要！）
        // 若直接用世界坐标当索引，PlaneCanvas.originWorld() 会把画布原点平移到
        // 离墙一个世界坐标距离的地方，所有内容都会画到错误的位置。
        double anchorGu = gridAxis(anchorHit.pos(), r);
        double anchorGv = gridAxis(anchorHit.pos(), u);

        Map<Integer, Double> colSpan = new LinkedHashMap<>();
        Map<Integer, Double> rowSpan = new LinkedHashMap<>();
        double minGridU = Double.MAX_VALUE, minGridV = Double.MAX_VALUE;

        /** 一个方块面在构建期的临时数据。 */
        record Raw(BlockPos pos, double u0, double v0, double su, double sv, double gu, double gv,
                   double normalExtreme) {
        }
        List<Raw> raws = new ArrayList<>();

        for (TargetPicker.FaceHit h : collected.values()) {
            double gu = gridAxis(h.pos(), r) - anchorGu;
            double gv = gridAxis(h.pos(), u) - anchorGv;
            // normalExtreme = 该面碰撞盒在法线轴上的「局部」位置（0~1），
            // 完整方块是 0 或 1；铁砧顶面是 0.5 之类。贴面偏移必须用它来算，
            // 否则楼梯/铁砧这类不完整方块上的内容会浮在表面之上或陷进去。
            double extreme = normalExtreme(face, h.box(), h.pos());
            raws.add(new Raw(h.pos(), h.faceMinU(), h.faceMinV(), h.spanU(), h.spanV(), gu, gv, extreme));
            // 列跨度 = 该列上「单个方块面」的宽度（画布单位）。
            // 注意两点：
            //  1) 同一列上有多少行，就重复累加多少次 —— 那会把宽度乘上行数，
            //     所以这里必须用 max 而不是 sum；
            //  2) h.spanU() 已经是画布单位（1 格 = 16），不要再乘/除 16。
            colSpan.merge((int) Math.floor(gu + 1.0e-6), h.spanU(), Math::max);
            rowSpan.merge((int) Math.floor(gv + 1.0e-6), h.spanV(), Math::max);
            minGridU = Math.min(minGridU, gu);
            minGridV = Math.min(minGridV, gv);
        }
        if (raws.isEmpty()) {
            raws.add(new Raw(anchorHit.pos(), anchorHit.faceMinU(), anchorHit.faceMinV(),
                    anchorHit.spanU(), anchorHit.spanV(), 0, 0,
                    normalExtreme(face, anchorHit.box(), anchorHit.pos())));
            colSpan.put(0, anchorHit.spanU());
            rowSpan.put(0, anchorHit.spanV());
            minGridU = 0;
            minGridV = 0;
        }

        // 画布原点 = 锚点方块「该面的最小角」的面内坐标（不是准心命中点的坐标！）。
        // faceMinU()/faceMinV() 是面内比例(0~1)，乘 16 转成画布单位。
        // 完整方块面的范围是 [0,1]，所以完整方块的 originUV 恒为 (0,0)。
        // 注意不能用 minGridU + faceMinU()：前者是方块数（可为负），后者是比例，
        // 单位不同，相加会让整张画布平移错位（表现为内容整体偏移一个方块）。
        p.originU = anchorHit.faceMinU() * PlaneCanvas.UNITS_PER_BLOCK;
        p.originV = anchorHit.faceMinV() * PlaneCanvas.UNITS_PER_BLOCK;

        double totalW = 0, totalH = 0;
        for (double v : colSpan.values()) totalW += v;
        for (double v : rowSpan.values()) totalH += v;

        // totalW/totalH 此时已经是「所有列/行跨度之和」，单位就是画布单位
        // 画布尺寸由「原始矩形的范围」决定（原点在命中点，可能向负方向延伸）
        double rMinX = Double.MAX_VALUE, rMaxX = -Double.MAX_VALUE;
        double rMinY = Double.MAX_VALUE, rMaxY = -Double.MAX_VALUE;
        for (Raw raw : raws) {
            double x0 = raw.gu() * PlaneCanvas.UNITS_PER_BLOCK - p.originU;
            double y0 = raw.gv() * PlaneCanvas.UNITS_PER_BLOCK - p.originV;
            rMinX = Math.min(rMinX, x0);
            rMaxX = Math.max(rMaxX, x0 + raw.su());
            rMinY = Math.min(rMinY, y0);
            rMaxY = Math.max(rMaxY, y0 + raw.sv());
        }
        if (raws.isEmpty()) {
            rMinX = 0; rMinY = 0; rMaxX = totalW; rMaxY = totalH;
        }
        p.canvasOffsetX = -rMinX;
        p.canvasOffsetY = -rMinY;
        // 画布原点：把命中点所在的方块面整体平移到非负坐标，避免出现负的画布坐标
        p.width = Math.max(1, (int) Math.round(rMaxX - rMinX));
        p.height = Math.max(1, (int) Math.round(rMaxY - rMinY));

        // 不变式自检：完整方块平面上，长宽必须正好等于「列/行数 x 16 单位」。
        // 任何一次误改单位换算都会在这里立刻暴露（而不是等到画面上才发现）。
        int expectW = (int) Math.round(totalW);
        int expectH = (int) Math.round(totalH);
        if (p.width != expectW || p.height != expectH) {
            top.hmjmfabc.projector.Projector.LOGGER.error(
                    "[Projector] 长宽自检失败！width={} 期望={} (列数 {}), height={} 期望={} (行数 {})"
                            + " —— 说明单位换算被改坏了",
                    p.width, expectW, colSpan.size(), p.height, expectH, rowSpan.size());
        }

        boolean full = true;
        for (Raw raw : raws) {
            // 以命中点为原点的原始坐标，再整体平移成非负
            double x0 = raw.gu() * PlaneCanvas.UNITS_PER_BLOCK - p.originU + p.canvasOffsetX;
            double y0 = raw.gv() * PlaneCanvas.UNITS_PER_BLOCK - p.originV + p.canvasOffsetY;
            double x1 = x0 + raw.su();
            double y1 = y0 + raw.sv();
            double dep = depthOf(face, p.anchor, p.anchorSurface, raw.pos(), raw.normalExtreme());
            p.blocks.put(raw.pos().asLong(), new PlaneBlock(x0, y0, x1, y1, dep));
            TargetPicker.FaceHit src = collected.get(raw.pos().asLong());
            if (src != null && src.incomplete()) full = false;
        }
        p.fullFaces = full;
        // 注意：SLF4J 只支持 {} 占位符，不支持 {:.3f} 这类格式说明符，
        // 所以需要预先格式化好再传进去。
        top.hmjmfabc.projector.Projector.LOGGER.info(
                "[Projector] 平面构建: 锚点={} 朝向={} {} u=[{},{}] v=[{},{}]"
                        + " -> 方块面 {} 个（{} 列 x {} 行）画布 {}x{} 单位 = {}x{} 格"
                        + " originUV=({},{}) 完整平面={}",
                p.anchor.toShortString(), face.getName(),
                anchorHit.completeFace() ? "完整方块面" : "不完整面",
                f3(anchorHit.faceMinU()), f3(anchorHit.faceMaxU()),
                f3(anchorHit.faceMinV()), f3(anchorHit.faceMaxV()),
                raws.size(), colSpan.size(), rowSpan.size(),
                p.width, p.height, p.width / 16, p.height / 16,
                f1(p.originU), f1(p.originV), full);

        return p;
    }

    /**
     * 方块位置在方向 {@code d} 上的网格坐标（d 为单位轴向量，三个分量中恰有一个为 ±1）。
     *
     * <p>实现上等价于 {@code pos · d}：只有非零的那个分量会起作用。</p>
     */
    private static double gridAxis(BlockPos pos, Direction d) {
        return pos.getX() * d.getStepX() + pos.getY() * d.getStepY() + pos.getZ() * d.getStepZ();
    }

    /** 碰撞盒在法线轴上的局部位置（0~1）。 */
    /** 碰撞盒在法线轴上的局部位置（0~1，取「朝外那一侧」的极值）。 */
    private static double normalExtreme(Direction face, AABB box, BlockPos pos) {
        return switch (face) {
            case EAST -> box.maxX - pos.getX();
            case WEST -> box.minX - pos.getX();
            case UP -> box.maxY - pos.getY();
            case DOWN -> box.minY - pos.getY();
            case SOUTH -> box.maxZ - pos.getZ();
            case NORTH -> box.minZ - pos.getZ();
        };
    }

    /**
     * 沿【外法线】从「方块最小角」到「该面所在平面」的距离（单位：方块，0~1）。
     *
     * <p><b>定义必须与 {@link #defaultAnchorSurfaceFor} 完全一致：</b>
     * 完整方块的 UP / EAST / SOUTH 面在 max 侧，距离是 1；
     * DOWN / WEST / NORTH 面在 min 侧，距离是 <b>0</b>。
     * 这正是 {@link #normalExtreme} 给出的值，因此这里<b>不能</b>再做
     * {@code 1.0 - extreme} 的翻转——那会让 NORTH / WEST / DOWN 三个朝向的
     * 内容被推到离方块整整一格的位置（表现为「平面不与方块贴合，有一条缝」）。</p>
     */
    static double normalDistanceToFace(Direction face, AABB box, BlockPos pos) {
        return Math.max(0.0, Math.min(1.0, normalExtreme(face, box, pos)));
    }

    /**
     * 该方块面相对「锚点方块表面」的位移（画布单位，沿外法线方向）。
     *
     * <p>{@code anchorSurface} 是锚点方块在该面上的表面位置（沿法线的局部坐标 0~1），
     * 因此完整方块平面上所有方块的结果都是 0（正好共面）；
     * 矮一截的方块（半砖、铁砧）会得到负值，让内容真正贴住那个更矮的表面。</p>
     */
    private static double depthOf(Direction face, BlockPos anchorPos, double anchorSurface,
                                 BlockPos pos, double extreme) {
        // 该面在法线轴上的世界坐标位置（外法线方向为正）
        double surfaceWorld = switch (face) {
            case EAST -> pos.getX() + extreme;
            case WEST -> -(pos.getX() + extreme);
            case UP -> pos.getY() + extreme;
            case DOWN -> -(pos.getY() + extreme);
            case SOUTH -> pos.getZ() + extreme;
            case NORTH -> -(pos.getZ() + extreme);
        };
        // 锚点面所在平面在法线轴上的世界坐标位置。
        // anchorSurface 的定义是「从方块最小角沿外法线到面平面的距离」，
        // 因此 min 侧的三个朝向同样直接用 +anchorSurface（不能写 1.0 - anchorSurface）。
        double anchorWorld = switch (face) {
            case EAST -> anchorPos.getX() + anchorSurface;
            case WEST -> -(anchorPos.getX() + anchorSurface);
            case UP -> anchorPos.getY() + anchorSurface;
            case DOWN -> -(anchorPos.getY() + anchorSurface);
            case SOUTH -> anchorPos.getZ() + anchorSurface;
            case NORTH -> -(anchorPos.getZ() + anchorSurface);
        };
        return (surfaceWorld - anchorWorld) * PlaneCanvas.UNITS_PER_BLOCK;
    }

    private static String f1(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    private static String f3(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    /** 便于调试：方块 ID 字符串。 */
    public static String blockName(BlockGetter level, BlockPos pos) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
    }

    /** 由已算好的 FaceHit 直接构建平面（服务端复核路径使用）。 */
    public static Plane buildFromHit(ResourceLocation dimension, TargetPicker.FaceHit hit, int maxBlocks) {
        return build(null, dimension, hit, maxBlocks);
    }
}

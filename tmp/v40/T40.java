import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import top.hmjmfabc.projector.common.BlockFace;
import top.hmjmfabc.projector.common.TargetPicker;
import top.hmjmfabc.projector.common.Plane;
import top.hmjmfabc.projector.common.PlaneBlock;
import top.hmjmfabc.projector.common.PlaneSide;
import top.hmjmfabc.projector.common.widget.TextWidget;
import top.hmjmfabc.projector.network.ServerNetHandler;
import top.hmjmfabc.projector.server.ProjectorData;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * T40 —— 【27.2.2-160】两条历史遗留问题的纯逻辑/源码断言套件。
 *
 * <h2>① 圈选：方块被挖掉又搭回来时，不能再新建一个空平面</h2>
 * 挖掉一块方块时 {@code onBlockBreak} 会把那个坐标从平面的方块集合里删掉；
 * 玩家把墙补回来、对着新方块按 U 时，老逻辑的「精确命中」落空 ⇒ 新建空平面，
 * 原来那些控件看着就像消失了。新判据按用户口径**看控件**（{@link ServerNetHandler#canRevivePlane}）。
 *
 * <h2>② 平面透视：不透明墙要挡得住，玻璃要透得过</h2>
 * 默认（{@code range = 0}）必须**彻底不透视** —— 深度测试始终保留，
 * 不透明批次先写深度 ⇒ 1 格厚的墙也挡得住；玻璃是半透明批次、在我们之后画 ⇒ 透过去看得见。
 */
public class T40 {

    private static int passed = 0;
    private static final List<String> failed = new ArrayList<>();

    public static void main(String[] args) {
        System.out.println("== T40 27.2.2-161（平面复活只搬几何 / 空平面清理 / 透视口径）==");
        reviveRule();
        adoptGeometry();
        frameShift();
        emptyPlaneCleanup();
        seeThroughDefault();
        wiring();

        System.out.println(failed.isEmpty()
                ? "== ALL PASS ==  " + passed + " passed, 0 failed"
                : "== FAILED ==  " + passed + " passed, " + failed.size() + " failed");
        for (String f : failed) {
            System.out.println("  \u274c " + f);
        }
        if (!failed.isEmpty()) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ 工具

    private static final ResourceLocation DIM =
            ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");

    private static void ok(boolean cond, String what) {
        if (cond) {
            passed++;
        } else {
            failed.add(what);
        }
    }

    private static void eq(Object a, Object b, String what) {
        ok(java.util.Objects.equals(a, b), what + "（实际 " + a + "，期望 " + b + "）");
    }

    private static String read(String rel) {
        try {
            return Files.readString(Path.of(rel));
        } catch (Exception e) {
            return "";
        }
    }

    /** 造一个真平面：4 个方块面、朝向 SOUTH、可以带控件。 */
    private static Plane plane(boolean withWidget) {
        Plane p = new Plane();
        p.id = UUID.randomUUID();
        p.dimension = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
        p.anchor = new BlockPos(0, 64, 0);
        p.face = Direction.SOUTH;
        p.originU = 0;
        p.originV = 0;
        p.width = 32;
        p.height = 32;
        p.anchorSurface = 1.0;
        p.canvasOffsetX = 0;
        p.canvasOffsetY = 0;
        p.fullFaces = true;
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                p.blocks.put(new BlockPos(x, 64 + y, 0).asLong(),
                        new PlaneBlock(x * 16, y * 16, x * 16 + 16, y * 16 + 16, 1.0));
            }
        }
        if (withWidget) {
            TextWidget tw = new TextWidget();
            tw.x = 0;
            tw.y = 0;
            p.widgets.add(tw);
        }
        return p;
    }

    // ------------------------------------------------------------------ ① 复活规则

    private static void reviveRule() {
        Plane withContent = plane(true);
        Plane empty = plane(false);

        // 2x2 的墙，包围盒自带 1.25 格 padding：
        //   ①被挖掉的那一块（原位置）就在盒内 ⇒ 必须能复活；
        //   ②紧挨着的那一块（比如补墙时往外扩了一格）也在 padding 内 ⇒ 也能复活；
        //   ③隔了两格以上的坐标 ⇒ 不该被这块方块"吸"过去。
        ok(ServerNetHandler.canRevivePlane(withContent, Direction.SOUTH, 0.5, 64.5, 0.5),
                "★ 被挖掉的那一块补回来 ⇒ 可以复活旧平面");
        ok(ServerNetHandler.canRevivePlane(withContent, Direction.SOUTH, 2.5, 64.5, 0.5),
                "紧挨着的那一块（padding 1.25 内）也算同一个平面");
        // ⚠ 这条同时钉住一个坑：PlaneDistance.withinRange 的 range=0 是「**不限**」
        //   （与 PlaneSide 那边的约定相反），传 0 会让任意远处的平面都算命中。
        ok(!ServerNetHandler.canRevivePlane(withContent, Direction.SOUTH, 8.5, 64.5, 0.5),
                "★ 离得远的方块不许被吸进来（不能把墙外的新墙算成旧平面）");
        ok(!ServerNetHandler.canRevivePlane(withContent, Direction.SOUTH, 40.5, 64.5, 0.5),
                "★ 很远的方块更不行（range 传 0 会变成「不限」，这里钉住）");

        ok(!ServerNetHandler.canRevivePlane(empty, Direction.SOUTH, 0.5, 64.5, 0.5),
                "★ 没有控件的空平面**不复活**（新建即可，反正没内容）");
        ok(!ServerNetHandler.canRevivePlane(withContent, Direction.NORTH, 0.5, 64.5, 0.5),
                "★ 朝向不同不算同一个平面");
        ok(!ServerNetHandler.canRevivePlane(withContent, null, 0.5, 64.5, 0.5),
                "朝向未知时不做复活判定");
        ok(!ServerNetHandler.canRevivePlane(null, Direction.SOUTH, 0.5, 64.5, 0.5),
                "null 平面直接 false");

        Plane noBlocks = plane(true);
        noBlocks.blocks.clear();
        ok(!ServerNetHandler.canRevivePlane(noBlocks, Direction.SOUTH, 0.5, 64.5, 0.5),
                "★ 一个方块都不剩的平面不复活（它本来就该被删掉）");
    }

    // ------------------------------------------------------------------ ② 空平面清理

    // ------------------------------------------- ①b 复活「只搬几何」（161 的核心回归）

    /**
     * 【161】160 的复活走的是 {@code applyFrom(fresh)}，而那是「以 fresh 为准的覆盖式同步」：
     * 它连 name / creator / protect* / 整份 widgets 一起覆盖 ⇒ 控件全被清空，
     * 再撞上「取消选中时自动删掉没有控件的平面」⇒ 玩家的平面**被彻底删掉**。
     */
    private static void adoptGeometry() {
        Plane old = plane(true);
        TextWidget second = new TextWidget();
        old.widgets.add(second);
        Object firstWidget = old.widgets.get(0);
        old.name = "我的公告板";
        UUID creator = UUID.randomUUID();
        old.creator = creator;
        old.deleteProtect = true;
        old.protectContent = true;

        // 「按当前世界重新 build」出来的空壳：几何不同、id 不同、没有控件/名字/保护
        Plane world = plane(false);
        world.width = 48;
        world.height = 16;
        world.anchor = new BlockPos(0, 64, 5);
        world.blocks.put(new BlockPos(9, 64, 0).asLong(), new PlaneBlock(0, 0, 16, 16, 1.0));

        old.adoptGeometryFrom(world);

        eq(old.widgets.size(), 2, "★ 复活只换几何：控件一个都不许少");
        ok(old.widgets.get(0) == firstWidget,
                "★ 控件必须还是原来那个对象（界面/编辑器持有的是它的引用）");
        ok(old.widgets.get(1) == second, "第二个控件也还在");
        eq(old.name, "我的公告板", "★ 名字保住");
        ok(creator.equals(old.creator), "★ 创建者还是原主（不能被改成按下 U 的那个玩家）");
        ok(old.deleteProtect, "★ 删除保护保住（否则「被删掉」根本拦不住）");
        ok(old.protectContent, "★ 内容保护保住");
        eq(old.width, 48, "几何按新世界更新：宽度");
        eq(old.height, 16, "几何按新世界更新：高度");
        eq(old.blocks.size(), 5, "几何按新世界更新：方块面数量");
        ok(old.anchor.getZ() == 5, "几何按新世界更新：锚点");

        // 把 applyFrom 的两条语义钉死（160 就是踩在这上面）
        Plane victim = plane(true);
        victim.name = "x";
        victim.deleteProtect = true;
        Plane shell = plane(false);
        shell.id = victim.id;                       // 同 id ⇒ 覆盖式同步真的会执行
        victim.applyFrom(shell);
        eq(victim.widgets.size(), 0,
                "applyFrom 是覆盖式同步：同 id + 空壳 ⇒ 控件被清空（所以复活绝不能用它）");
        eq(victim.name, "", "同上：名字也会被清掉");

        Plane target = plane(true);
        int w0 = target.width;
        Plane stranger = plane(false);              // id 不同 ⇒ 第一道闸门直接 return
        stranger.width = 99;
        target.applyFrom(stranger);
        eq(target.width, w0,
                "★ id 不同时 applyFrom 直接返回（build 给的是新 id ⇒ 以前「重新圈选」是静默空操作）");
        eq(target.widgets.size(), 1, "这种情况下控件不会被误清（早退也算是保护）");
    }

    // -------------------------- ①c 复活必须「沿用画布坐标系」（161 的第二个 bug）

    /**
     * 玩家的现象：在补回来的方块上按 U，整张平面**连着控件平移几格**，方向与格数
     * 跟方块所在位置有关。根因不是「搬了几何」，而是**把坐标系重算了一遍**：
     * 矩形是从准心命中的那一格往外扩的（`sameBlockOnly` 遇别的材质就停、有洞就在洞那列停），
     * 起点换了 ⇒ 矩形最小角换了 ⇒ `canvasOffsetX/Y` 重算 ⇒ 画布 (0,0) 在世界里挪位。
     * 这里用**物理量**验收：画布 (0,0) 的世界坐标（渲染器公式）必须一个毫米都不动。
     */
    private static void frameShift() {
        BlockPos a0 = new BlockPos(0, 64, 0);
        BlockPos far = new BlockPos(3, 64, 0);
        Plane old = Plane.build(null, DIM, faceHit(a0), 512, null);
        Vec3 z0 = canvasZero(old);

        // 不沿用坐标系（= 160/161 第一版的做法）⇒ 内容整体平移 3 格
        Plane noFrame = Plane.build(null, DIM, faceHit(far), 512, null);
        Vec3 z1 = canvasZero(noFrame);
        ok(Math.abs(z1.x - z0.x - 3.0) < 1.0e-6,
                "\u2605 不沿用坐标系时锚点换了 ⇒ 画布原点跟着平移 3 格（这就是玩家看到的现象）");
        ok(!noFrame.anchor.equals(old.anchor), "此时锚点变成了按 U 的那个方块");

        // 沿用坐标系（= 修法）⇒ 画布原点一动不动
        Plane withFrame = Plane.build(null, DIM, faceHit(far), 512, old);
        Vec3 z2 = canvasZero(withFrame);
        ok(Math.abs(z2.x - z0.x) + Math.abs(z2.y - z0.y) + Math.abs(z2.z - z0.z) < 1.0e-6,
                "\u2605 沿用旧坐标系后画布原点一动不动（内容不可能平移）");
        ok(withFrame.anchor.equals(old.anchor), "锚点沿用旧平面（不是按 U 的那个方块）");
        ok(Math.abs(withFrame.originU - old.originU) < 1.0e-9
                        && Math.abs(withFrame.canvasOffsetX - old.canvasOffsetX) < 1.0e-9,
                "原点 UV / 画布偏移都沿用旧值（重算它们正是平移的根源）");
        // 新方块落在**旧坐标系**里的正确位置：3 格 = 48 画布单位
        PlaneBlock nb = withFrame.blocks.get(far.asLong());
        ok(nb != null && Math.abs(nb.x0 - 48) < 1.0e-6,
                "补回来的方块在旧坐标系里落在 x=48（3 格），而不是被平移到 0");

        // absorbFromRebuild：只并方块、尺寸只增、内容一个不碰
        Plane victim = old;
        victim.widgets.add(new TextWidget());
        victim.name = "公告板";
        UUID c = UUID.randomUUID();
        victim.creator = c;
        victim.deleteProtect = true;
        int w0 = victim.width;
        victim.absorbFromRebuild(withFrame);
        ok(victim.blocks.containsKey(a0.asLong()) && victim.blocks.containsKey(far.asLong()),
                "\u2605 并集：老方块面留着自己那块（不丢可交互面积）");
        ok(victim.width >= w0, "尺寸只增不减");
        eq(victim.widgets.size(), 1, "控件不受影响");
        eq(victim.name, "公告板", "名字不受影响");
        ok(victim.deleteProtect && c.equals(victim.creator), "创建者与保护开关不受影响");
    }

    /** 完整方块的 SOUTH 面。 */
    private static TargetPicker.FaceHit faceHit(BlockPos p) {
        return new TargetPicker.FaceHit(p, Direction.SOUTH,
                new Vec3(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 1.0),
                new AABB(p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 1, p.getZ() + 1),
                0, 0, 1, 1, false);
    }

    /** 渲染器公式：画布 (0,0) 在世界里的位置。 */
    private static Vec3 canvasZero(Plane p) {
        Direction r = BlockFace.right(p.face);
        Direction u = BlockFace.up(p.face);
        Vec3 o = p.canvas().originWorld();
        return new Vec3(
                o.x - r.getStepX() * (p.canvasOffsetX / 16.0) - u.getStepX() * (p.canvasOffsetY / 16.0),
                o.y - r.getStepY() * (p.canvasOffsetX / 16.0) - u.getStepY() * (p.canvasOffsetY / 16.0),
                o.z - r.getStepZ() * (p.canvasOffsetX / 16.0) - u.getStepZ() * (p.canvasOffsetY / 16.0));
    }

    private static void emptyPlaneCleanup() {
        eq(ProjectorData.DATA_VERSION, 2, "数据版本是 2（= 已做过「清空平面」升级清理）");

        // 老存档（dataVersion = 1）：一个带控件的平面 + 两个空平面
        Plane keep = plane(true);
        Plane emptyA = plane(false);
        Plane emptyB = plane(false);
        CompoundTag old = new CompoundTag();
        old.putInt("dataVersion", 1);
        old.put("dimensions", dims(keep, emptyA, emptyB));
        ProjectorData loaded = ProjectorData.load(old, null);
        List<Plane> after = loaded.planesIn(ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"));
        eq(after.size(), 1, "★ 升级清理：两个空平面被删掉、带控件的留下");
        ok(!after.isEmpty() && after.get(0).id.equals(keep.id), "留下的是原来那个带控件的平面");
        ok(!after.isEmpty() && !after.get(0).widgets.isEmpty(), "它的控件还在");

        // 新存档（dataVersion = 2）：不许再清（否则玩家刚按 U 建的平面会被开服吃掉）
        CompoundTag fresh = new CompoundTag();
        fresh.putInt("dataVersion", ProjectorData.DATA_VERSION);
        fresh.put("dimensions", dims(keep, emptyA, emptyB));
        ProjectorData again = ProjectorData.load(fresh, null);
        eq(again.planesIn(ResourceLocation.fromNamespaceAndPath("minecraft", "overworld")).size(), 3,
                "★ 已经是新版本存档 ⇒ 一个都不清");

        // 没有 dataVersion 的极老存档（按 0 处理 ⇒ 也要清）
        CompoundTag ancient = new CompoundTag();
        ancient.put("dimensions", dims(keep, emptyB));
        eq(ProjectorData.load(ancient, null)
                        .planesIn(ResourceLocation.fromNamespaceAndPath("minecraft", "overworld")).size(), 1,
                "没有 dataVersion 的极老存档也走一次清理");
        ok(read("src/main/java/top/hmjmfabc/projector/server/ProjectorData.java")
                        .contains("tag.putInt(\"dataVersion\", DATA_VERSION)"),
                "save() 写的是 DATA_VERSION（不是写死的 1）");
    }

    private static ListTag dims(Plane... planes) {
        ListTag list = new ListTag();
        for (Plane p : planes) {
            list.add(p.save());
        }
        CompoundTag dt = new CompoundTag();
        dt.putString("id", "minecraft:overworld");
        dt.put("planes", list);
        ListTag out = new ListTag();
        out.add(dt);
        return out;
    }

    // ------------------------------------------------------------------ ③ 透视口径

    private static void seeThroughDefault() {
        // 默认（配置 0）：任何距离都不许关深度测试 ⇒ 1 格厚的不透明墙挡得住控件。
        ok(!PlaneSide.seeThroughFromBack(true, 0.0, 0.0), "★ 默认不透视：贴面也不透");
        ok(!PlaneSide.seeThroughFromBack(true, 0.5, 0.0), "★ 默认不透视：贴面附近也不透");
        ok(!PlaneSide.seeThroughFromBack(true, 4.0, 0.0), "默认不透视：远处更不透");
        ok(!PlaneSide.seeThroughFromBack(false, 0.0, 0.0), "正面本来就不透");
        // 玩家主动调大才恢复老行为
        ok(PlaneSide.seeThroughFromBack(true, 2.0, 4.0), "调大 range 才恢复「透墙」（老行为的开关）");
        ok(!PlaneSide.seeThroughFromBack(true, 9.0, 4.0), "超出 range 不透");
        // 玻璃要靠「半透明批次画在我们之后」来透过去 —— 阶段必须仍然在半透明块之前
        String client = read("src/main/java/top/hmjmfabc/projector/client/ProjectorClient.java");
        ok(client.contains("AFTER_ENTITIES") && !client.contains("Stage.AFTER_TRANSLUCENT_BLOCKS"),
                "★ 渲染阶段仍在不透明块之后、半透明块之前（玻璃才能透过来）");
        // 深度测试没被关掉：只有 seeThrough 为真时才走 textSeeThrough
        String collector = read("src/main/java/top/hmjmfabc/projector/client/render/QuadCollector.java");
        ok(collector.contains("seeThrough ? RenderType.textSeeThrough(texture) : RenderType.text(texture)"),
                "只有 seeThrough 为真时才用「无深度测试」管线");
    }

    // ------------------------------------------------------------------ ④ 接线

    private static void wiring() {
        String server = read("src/main/java/top/hmjmfabc/projector/network/ServerNetHandler.java");
        String client = read("src/main/java/top/hmjmfabc/projector/client/ClientInputHandler.java");
        String events = read("src/main/java/top/hmjmfabc/projector/server/ServerEvents.java");

        // onCreatePlane：先在「精确命中」之后、新建之前插一档「复活」
        int exact = server.indexOf("for (Plane p : data.planesAt(dim, pos))");
        int revive = server.indexOf("findRevivablePlane(data, dim, pos");
        int create = server.indexOf("data.add(dim, plane)");
        ok(exact > 0 && revive > exact, "★ 复活判定排在「精确命中」之后");
        ok(create > revive, "★ 复活判定排在「新建平面」之前（否则又会新建一个）");
        ok(server.contains("revivable.absorbFromRebuild(rebuilt)"),
                "\u2605 复活只并方块（absorbFromRebuild），控件/名字/创建者/保护一个都不动");
        ok(server.contains("maxPlaneBlocks.get(), revivable)"),
                "\u2605 复活时把 revivable 当「坐标系提供者」传给 build（否则内容会平移几格）");
        ok(server.contains("画布原点移动了"),
                "\u2605 复活后自检画布原点没动（动了就是坐标系又被重算了）");
        ok(!server.contains("revivable.adoptGeometryFrom("),
                "复活不许走 adoptGeometryFrom（那个会换坐标系 ⇒ 内容平移）");
        ok(!server.contains("revivable.applyFrom("),
                "\u2605 复活绝不能走 applyFrom \u2014\u2014 覆盖式同步会把控件/名字/创建者/保护全部清空（160 的 bug）");
        ok(server.contains("plane.adoptGeometryFrom(fresh)"),
                "\u2605「重新圈选此平面」也只搬几何（以前 applyFrom + 新 id = 静默空操作，还提示「已重建」）");
        ok(events.contains("plane.adoptGeometryFrom(fresh)"),
                "\u2605 旧存档自动重建也只搬几何（以前那个 applyFrom 同样是静默空操作）");
        ok(!server.contains(".applyFrom("),
                "ServerNetHandler 里不该再出现 applyFrom（服务端没有哪处需要整份覆盖）");
        ok(!events.contains(".applyFrom("), "ServerEvents 里也不该再出现 applyFrom");
        ok(server.contains("data.rebuildIndex(dim)"), "复活之后要重建方块索引（否则 planesAt 还是找不到）");
        ok(server.contains("PlanePermissions.canEditContent(p, player)"),
                "★ 复活别人的平面要过「改内容」权限（内容保护开着时只有管理员/创建者能改）");

        // 取消选中：空平面自动删除
        int cancel = client.indexOf("private void onCancel(");
        String cancelBody = cancel < 0 ? "" : client.substring(cancel, Math.min(client.length(), cancel + 2400));
        ok(cancelBody.contains("wasSelected.widgets.isEmpty()"), "★ 取消选中时判空（只看没有控件的）");
        ok(cancelBody.contains("\"deletePlane\""), "★ 取消选中时把空平面删掉（走服务端权限判定）");
        ok(cancelBody.contains("putBoolean(\"ifEmpty\", true)"),
                "★ 这条自动删除必须带 ifEmpty=true（让服务端知道这不是玩家主动删的）");
        int del = server.indexOf("case \"deletePlane\" ->");
        String delBody = del < 0 ? "" : server.substring(del, Math.min(server.length(), del + 1600));
        ok(delBody.contains("getBoolean(\"ifEmpty\")"),
                "★ 服务端对「自动删除空平面」自己再确认一次（不信客户端的过期副本）");
        ok(delBody.contains("!plane.widgets.isEmpty()"),
                "★ 判据是服务端那份控件列表：有控件就拒绝删除 + 回推权威状态");
        ok(delBody.indexOf("getBoolean(\"ifEmpty\")") < delBody.indexOf("canDelete"),
                "★ 兜底闸门必须排在权限判定之前（否则保护开关本身被清掉时也拦不住）");

        // 方块被挖掉时把方块从平面里删掉这件事仍然保留（否则复活判据没意义）
        ok(events.contains("plane.blocks.remove(event.getPos().asLong())"),
                "挖方块仍然会把它从平面的方块集合里删掉（这正是复活要处理的场景）");
        String side = read("src/main/java/top/hmjmfabc/projector/common/PlaneSide.java");
        ok(side.contains("range <= 0.0"), "★ seeThroughFromBack 里 0 = 永不透墙");
    }
}

package top.hmjmfabc.projector.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import top.hmjmfabc.projector.ProjectorConfig;
import top.hmjmfabc.projector.client.PlaneCache;
import top.hmjmfabc.projector.common.BlockFace;
import top.hmjmfabc.projector.common.Plane;
import top.hmjmfabc.projector.common.PlaneBlock;
import top.hmjmfabc.projector.common.PlaneCanvas;
import top.hmjmfabc.projector.common.PlaneDistance;
import top.hmjmfabc.projector.common.PlaneSide;
import top.hmjmfabc.projector.common.widget.Widget;

import java.util.List;

/**
 * 世界内平面渲染器。
 *
 * <p>每帧（在 {@code RenderLevelStageEvent} 的 AFTER_ENTITIES 阶段：
 * 必须在半透明方块之前，否则水面/玻璃会把水下内容整片挡掉）
 * 遍历当前维度内、距离玩家一定范围内的平面，把其中的控件转成四边形提交。</p>
 *
 * <p>性能策略：</p>
 * <ol>
 *   <li>先按距离与视锥粗筛，远处的平面完全不处理；</li>
 *   <li>平面内控件逐个检查是否与摄像机视锥相交；</li>
 *   <li>每个平面单独 flush 一次，避免所有顶点堆在一帧末尾；</li>
 *   <li>四边形数量有全局上限，超限直接丢弃多余部分（宁可少画，不可卡死）。</li>
 * </ol>
 */
public final class WorldPlaneRenderer {

    private final QuadCollector collector = new QuadCollector();

    /** 统计信息（调试用）。 */
    /** 每个平面上一次报告的内容摘要，只有变化时才重新打印（避免刷屏）。 */
    private final java.util.Map<java.util.UUID, String> reportedSummary = new java.util.HashMap<>();
    /** 每个平面上一次报告时的控件数量：数量一变就立刻报告。 */
    private final java.util.Map<java.util.UUID, Integer> reportedWidgetCount = new java.util.HashMap<>();
    /**
     * 每个平面上一次报告的**时间戳**，用于限流。
     *
     * <p><b>必须按平面记、而且必须是硬上限。</b>玩家实测日志被撑到 30MB：
     * 原来这里靠「摘要变化」限流，但摘要里只要出现 {@code ×0} 就被判成异常，
     * 而**被流程隐藏的控件本来就提交 0 个四边形** —— 于是每帧都成立、每帧一行。</p>
     */
    private final java.util.Map<java.util.UUID, Long> lastReportMsByPlane = new java.util.HashMap<>();
    /** 同一个平面的两条报告之间至少隔多久（毫秒）。 */
    private static final long REPORT_MIN_INTERVAL_MS = 1200L;
    /** 已经做过「画布原点自检」的平面（每个平面只打一次）。 */
    private final java.util.Set<java.util.UUID> canvasReported = new java.util.HashSet<>();

    private int lastPlanes;
    private int lastQuads;
    /** 【hotfix-II】每个平面上一次报告过的反面状态（状态变了才打日志，避免刷屏）。 */
    private final java.util.Map<java.util.UUID, String> reportedBackface = new java.util.HashMap<>();
    /** 反面日志的限流时间戳。 */
    private final java.util.Map<java.util.UUID, Long> lastBackfaceMsByPlane = new java.util.HashMap<>();

    public void render(PoseStack pose, Camera camera, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;

        QuadCollector.beginFrame();
        // 【⑩】每帧只取一次游戏刻：流程动画要给每个控件求一次时间，
        // 不缓存的话一个 100 控件的平面每帧就要读 100 次 Minecraft.level。
        newFrame();
        List<Plane> planes = PlaneCache.planesIn(level.dimension().location());
        // 【27.2】网页控件的会话生命周期：按需创建浏览器 / 尺寸变化才 resize /
        // 自动刷新 / 离开视野 15 秒释放。必须挂在**渲染线程 + 拿得到本维度平面列表**的地方
        // （创建浏览器只能从渲染线程调），而且要放在下面「空列表提前 return」之前 ——
        // 否则一条平面都没有时（玩家走远、换个维度）就永远不会空闲释放。
        tickWebSessions(mc, planes);
        if (planes.isEmpty()) {
            lastPlanes = 0;
            lastQuads = 0;
            return;
        }
        double maxDist = ProjectorConfig.INSTANCE.renderDistance.get();
        double maxDistSq = maxDist * maxDist;
        Vec3 camPos = camera.getPosition();

        // 关键：本渲染阶段的模型视图矩阵只含相机旋转、不含平移
        // （原版在关卡渲染里到处手动减去 camPos）。因此顶点必须使用
        // 「相机相对坐标」，否则所有内容都会被平移一个相机坐标而完全看不见。
        int drawn = 0;
        for (Plane plane : planes) {
            net.minecraft.world.phys.AABB bb = plane.bounds();
            // 摄像机到包围盒的最近距离（点-盒距离），大平面即使锚点很远也不会被误剔除
            double ddx = Math.max(0, Math.max(bb.minX - camPos.x, camPos.x - bb.maxX));
            double ddy = Math.max(0, Math.max(bb.minY - camPos.y, camPos.y - bb.maxY));
            double ddz = Math.max(0, Math.max(bb.minZ - camPos.z, camPos.z - bb.maxZ));
            if (ddx * ddx + ddy * ddy + ddz * ddz > maxDistSq) continue;
            if (canvasReported.add(plane.id)) {
                reportCanvasOrigin(plane);
            }
            if (plane.widgets.isEmpty()) {
                continue;
            }
            drawPlane(pose, plane, camPos);
            drawn++;
            // 反面状态是「按平面」的：画完立刻复位，别漏给选区渲染或下一帧
            if (QuadCollector.remainingThisFrame() <= 0) break;
        }
        lastPlanes = drawn;
        lastQuads = collector.quadCount();
    }

    /**
     * 【27.2】每帧驱动一次网页控件的会话（创建 / resize / 自动刷新 / 空闲释放）。
     *
     * <p>放在这里的三个理由：①只能从渲染线程创建浏览器；②每帧只跑一次，
     * 不会随平面数量重复；③这一层拿得到「本维度当前已同步的平面列表」，
     * 闲下来的控件才可能被释放。</p>
     *
     * <p>整段包在 try/catch 里：网页后端（可选前置模组）出任何问题都<b>不许</b>
     * 把整帧平面渲染带崩 —— 拿不到画面就画占位块，玩家照样能看见其它控件。</p>
     */
    private static void tickWebSessions(Minecraft mc, List<Plane> planes) {
        try {
            top.hmjmfabc.projector.client.web.WebSessions.tick(mc, planes);
        } catch (Throwable t) {
            if (webTickErrorLogged) {
                return;
            }
            webTickErrorLogged = true;
            top.hmjmfabc.projector.Projector.LOGGER.warn(
                    "[Projector][网页] 会话调度失败（本帧已跳过，后续同类错误不再重复记录）", t);
        }
    }

    /** 会话调度异常只报一次，避免每帧刷屏。 */
    private static boolean webTickErrorLogged;

    private void drawPlane(PoseStack pose, Plane plane, Vec3 cameraPos) {
        double[] axisX = axis(BlockFace.right(plane.face));
        double[] axisY = axis(BlockFace.up(plane.face));
        // 外法线：既用于 PlaneRenderContext（顶点沿它偏移），也是正反面判定的依据
        double[] normal = axis(BlockFace.normal(plane.face));
        // 【hotfix-II】正反面 + 「贴面透墙」判定：判据全是纯函数（PlaneSide / PlaneDistance），
        // 符号用具体坐标钉在 tmp/v29 里 —— 98 那次把符号写反，交付即「平面上什么都看不见」。
        net.minecraft.world.phys.AABB bb = plane.bounds();
        Vec3 center = bb.getCenter();
        boolean backside = !PlaneSide.isFront(normal,
                new double[]{center.x, center.y, center.z},
                new double[]{cameraPos.x, cameraPos.y, cameraPos.z});
        double distance = PlaneDistance.distanceToBox(cameraPos.x, cameraPos.y, cameraPos.z,
                bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ);
        double range = backfaceSeeThroughRange();
        boolean throughWall = PlaneSide.seeThroughFromBack(backside, distance, range);
        reportBackface(plane, backside, distance, range, throughWall);
        try {
            // 反面要同时做两件事，缺一个都看不到：
            //   ① 翻绕序（RenderType.text 默认 CULL，背面朝外会被整批剔除）；
            //   ② 贴着平面时关掉深度测试（否则方块本体挡在相机与内容之间）。
            // 几何位置一个像素都不动 —— 「正面在哪背面就显示在哪」。
            QuadCollector.setBackfaceView(backside);
            QuadCollector.setSeeThrough(throughWall);
            drawPlaneBody(pose, plane, cameraPos, axisX, axisY, normal);
        } finally {
            // 这两个开关是「按平面」的：画完立刻复位，漏出去会让后面的平面跟着穿墙
            QuadCollector.resetBackface();
        }
    }

    /**
     * 真正提交四边形的那一段（正反面开关已由 {@link #drawPlane} 设好）。
     *
     * <p>拆出来只是为了能用 try/finally 保证开关复位 —— 下面有一堆 continue/异常分支，
     * 散着写迟早会漏掉一条。</p>
     */
    private void drawPlaneBody(PoseStack pose, Plane plane, Vec3 cameraPos,
                               double[] axisX, double[] axisY, double[] normal) {
        PlaneCanvas canvas = plane.canvas();
        Vec3 origin = canvas.originWorld();
        // 画布坐标 (0,0) 是玩家命中的那个点；为了让方块矩形不出现负坐标，
        // 它们被整体平移了 canvasOffset，因此绘制原点要沿 u/v 轴反向平移同样的量。
        final double offU = plane.canvasOffsetX / PlaneCanvas.UNITS_PER_BLOCK;
        final double offV = plane.canvasOffsetY / PlaneCanvas.UNITS_PER_BLOCK;
        // 顶点使用【绝对世界坐标】：pose 矩阵（含相机平移）由 QuadCollector.flush 统一应用。
        // 【重要】顶点用【相机相对坐标】：事件给的 PoseStack 是恒等的，
        // 相机平移由 ModelViewMat 承担，所以这里必须自己减去相机位置。
        double[] originArr = {
                origin.x - axisX[0] * offU - axisY[0] * offV - cameraPos.x,
                origin.y - axisX[1] * offU - axisY[1] * offV - cameraPos.y,
                origin.z - axisX[2] * offU - axisY[2] * offV - cameraPos.z};

        // 【hotfix-99】光影兼容：可选用平面锚点处的**真实光照**画控件
        //（与旁边的方块表面同一份光照 ⇒ 不会被当成发光体）。
        applyWidgetLight(plane);
        // 画布原点取的是锚点方块的「最小角」，而面所在的真实平面还要沿外法线再走
        // anchorSurface 格（完整方块的 UP/SOUTH/EAST 面就是 1 格）。少了这一步，
        // 三个朝向的内容会被画进方块内部而完全看不见。
        double facePlaneOffset = plane.anchorSurface;

        // 统计「每个控件实际提交了多少四边形」。
        // 这一条日志足以区分「控件没被画」和「画了但看不见」两种完全不同的故障：
        // 四边形数为 0 说明几何在提交前就被丢掉了，>0 却看不见才需要查渲染状态。
        // 只在「内容变化」或「真的异常」时打印，并且**同一个平面每 1.2 秒最多一行**。
        StringBuilder detail = new StringBuilder();
        boolean zeroWhileVisible = false;
        for (Widget w : plane.widgets) {
            double surface = surfaceDepthOf(plane, w);
            // 深度 = 面平面偏移 + 方块表面位移 + 基础贴面偏移 + 控件分层
            // 内容沿法线的偏移（在正面那一侧）
            double frontDepth = surface + PlaneRenderContext.surfaceBias()
                    + w.zOff * PlaneRenderContext.LAYER_STEP;
            // 【hotfix-101】绕方块中面镜像：把「面外 offset」翻到另一侧。
            //   F=1（UP/EAST/SOUTH）：1-1-offset = -offset ⇒ 落在背面外侧 ✓
            //   F=0（DOWN/WEST/NORTH）：0-1-offset = -1-offset ⇒ 同样落在背面外侧 ✓
            // （上一版统一用 depth-1，F=0 的平面会跑到**正面**外侧 1 格 = 玩家看到的偏移）
            // 内容始终画在**原来的位置**（正面那一侧）—— 正反面看到的是同一个点
            double depth = facePlaneOffset + frontDepth;
            PlaneRenderContext ctx = new PlaneRenderContext(axisX, axisY, normal, originArr, depth);
            int before = collector.quadCount();
            // 【⑩】流程动画：控件的位置/缩放/透明度/沿法线抬升由时间轴决定。
            // 未播放或该控件不在流程里时 stateOf 返回 NORMAL，行为与以前完全一致。
            top.hmjmfabc.projector.common.sequence.SequenceAnim.State anim =
                    plane.sequence == null
                            ? top.hmjmfabc.projector.common.sequence.SequenceAnim.State.NORMAL
                            : plane.sequence.stateOf(w, currentGameTimeCached());
            WidgetRenderer.drawAnimated(collector, plane, ctx, w, anim);
            int quads = collector.quadCount() - before;
            // 【「流程中隐藏」不是异常】被流程藏起来的控件本来就提交 0 个四边形。
            // 以前这里用 summary.contains("×0") 判异常，于是有流程的平面**每帧**都判异常、
            // 每帧一行日志（玩家实测日志被撑到 30MB）。真正该报的是
            // 「当前状态本该可见、却一个四边形都没提交」。
            boolean hiddenBySequence = anim != top.hmjmfabc.projector.common.sequence.SequenceAnim.State.NORMAL;
            if (quads == 0 && !hiddenBySequence) zeroWhileVisible = true;
            detail.append(w.kindId()).append('×').append(quads)
                    .append(hiddenBySequence ? "(流程中)" : "").append(' ');
        }
        String summary = detail.length() == 0 ? "（无控件）" : detail.toString().trim();
        boolean widgetsChanged = Integer.valueOf(plane.widgets.size())
                .equals(reportedWidgetCount.put(plane.id, plane.widgets.size())) == false;
        boolean anomaly = zeroWhileVisible;
        long nowMs = System.currentTimeMillis();
        long lastForPlane = lastReportMsByPlane.getOrDefault(plane.id, 0L);
        boolean summaryChanged = !summary.equals(reportedSummary.get(plane.id));
        // 再加一道硬限流：无论什么理由，同一个平面 1.2 秒内只留一行。
        boolean report = (widgetsChanged || anomaly || summaryChanged)
                && nowMs - lastForPlane >= REPORT_MIN_INTERVAL_MS;
        if (report) {
            // 【必须把 summary 存回去】以前这里只 get 不 put，于是 summaryChanged
            // 永远为 true —— 「平面就绪」每 2 秒刷一条，把真正的异常全淹了。
            reportedSummary.put(plane.id, summary);
            lastReportMsByPlane.put(plane.id, nowMs);
            // 「实测」= 由各方块面的画布矩形直接量出来的跨度（单位：格）。
            // 它必须与「声明」的 宽/高 一致；不一致就说明画布单位与方块数的换算被改坏了
            // ——那会让内容整体放大或缩小 16 倍，是这套代码最容易复发的一类 bug。
            double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
            double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (PlaneBlock b : plane.blocks.values()) {
                minX = Math.min(minX, b.x0);
                maxX = Math.max(maxX, b.x1);
                minY = Math.min(minY, b.y0);
                maxY = Math.max(maxY, b.y1);
            }
            boolean sizeOk = Math.abs((maxX - minX) / 16.0 - plane.width / 16.0) < 0.01
                    && Math.abs((maxY - minY) / 16.0 - plane.height / 16.0) < 0.01;
            top.hmjmfabc.projector.Projector.LOGGER.info(
                    "[Projector] 平面就绪: {} 朝向 {} {}×{} 单位（{}×{} 格, {} 个方块面,"
                            + " 矩形 x[{}..{}] y[{}..{}] 单位, canvasOffset=({},{}), 原点=({},{},{}),"
                            + " 实测 {}×{} 格{}） 控件 {} 个 -> 四边形 {}",
                    plane.id.toString().substring(0, 8), plane.face.getName(),
                    plane.width, plane.height, plane.width / 16, plane.height / 16,
                    plane.blocks.size(),
                    (int) minX, (int) maxX, (int) minY, (int) maxY,
                    String.format(java.util.Locale.ROOT, "%.1f", plane.canvasOffsetX),
                    String.format(java.util.Locale.ROOT, "%.1f", plane.canvasOffsetY),
                    String.format(java.util.Locale.ROOT, "%.2f", plane.canvas().originWorld().x),
                    String.format(java.util.Locale.ROOT, "%.2f", plane.canvas().originWorld().y),
                    String.format(java.util.Locale.ROOT, "%.2f", plane.canvas().originWorld().z),
                    String.format(java.util.Locale.ROOT, "%.2f", (maxX - minX) / 16.0),
                    String.format(java.util.Locale.ROOT, "%.2f", (maxY - minY) / 16.0),
                    sizeOk ? "" : "  <<< 尺寸自检失败！单位换算可能被改坏",
                    plane.widgets.size(), summary);
        }
        collector.flush(pose);
    }

    /**
     * 【hotfix-II】「贴面透墙」的距离门槛（格）。
     *
     * <p>配置 {@code render.backfaceSeeThroughRange}，默认 <b>0</b>：
     * 只有相机进入平面包围盒（包围盒自带 1.25 格 padding ⇒ 约等于「站在平面旁边」）时，
     * 从背面才透墙看到内容；离远了按正常遮挡处理（隔着墙看不见，
     * 但墙上开洞或方块被挖掉时照样能看到 —— 因为绕序已经翻回来了）。
     * 配置读不到（极早期）时返回 0，绝不因此放宽。</p>
     */
    private static double backfaceSeeThroughRange() {
        try {
            return ProjectorConfig.INSTANCE.renderBackfaceSeeThrough.get();
        } catch (Throwable t) {
            return 0.0;
        }
    }

    /**
     * 反面状态变化时留一行日志（每平面 1 秒最多一行）。
     *
     * <p>「从背面看不见」这种问题必须能一眼看出是哪一种：是判定说「在正面」（那内容本该可见），
     * 还是判定说「在背面、超出门槛」（那是设计如此，走近就会透出来）。</p>
     */
    private void reportBackface(Plane plane, boolean backside, double distance,
                                double range, boolean throughWall) {
        if (plane == null || plane.id == null) return;
        String state = !backside ? "正面-正常绘制"
                : (throughWall ? "反面-贴面透墙" : "反面-正常遮挡");
        if (state.equals(reportedBackface.get(plane.id))) return;
        long now = System.currentTimeMillis();
        if (now - lastBackfaceMsByPlane.getOrDefault(plane.id, 0L) < 1000L) return;
        reportedBackface.put(plane.id, state);
        lastBackfaceMsByPlane.put(plane.id, now);
        top.hmjmfabc.projector.Projector.LOGGER.info(
                "[Projector] 平面视野: {} 朝向={} 相机到平面={} 格（透墙门槛={} 格）-> {}{}",
                plane.id.toString().substring(0, 8), plane.face.getName(),
                String.format(java.util.Locale.ROOT, "%.2f", distance),
                String.format(java.util.Locale.ROOT, "%.2f", range),
                state,
                backside && !throughWall
                        ? "（隔着方块看不到；贴到平面就会透出来）" : "");
    }

    /**
     * 【hotfix-99】把「本平面要用的光照值」交给 QuadCollector。
     *
     * <p>默认（{@code render.realLightForWidgets=false}）交回 -1，表示继续用
     * 「天空光 15 + 方块光 14」这个对光影安全的固定值；打开后取锚点方块的真实光照。</p>
     */
    /** 方向向量（六朝向的轴）→ double[3]。 */
    private static double[] axis(net.minecraft.core.Direction d) {
        return new double[]{d.getStepX(), d.getStepY(), d.getStepZ()};
    }

    private static void applyWidgetLight(Plane plane) {
        ClientLevel level = Minecraft.getInstance().level;
        try {
            if (!ProjectorConfig.INSTANCE.realLightForWidgets.get()) {
                top.hmjmfabc.projector.client.render.QuadCollector.setFrameLight(-1);
                return;
            }
            net.minecraft.core.BlockPos anchor = plane.anchor;
            if (anchor == null) {
                top.hmjmfabc.projector.client.render.QuadCollector.setFrameLight(-1);
                return;
            }
            top.hmjmfabc.projector.client.render.QuadCollector.setFrameLight(
                    net.minecraft.client.renderer.LevelRenderer.getLightColor(level, anchor));
        } catch (Throwable t) {
            top.hmjmfabc.projector.client.render.QuadCollector.setFrameLight(-1);
        }
    }

    /**
     * 本帧的游戏刻（每帧只取一次）。
     *
     * <p>流程动画对每个控件都要问一次时间，而 {@code Minecraft.getInstance().level}
     * 是带同步的字段读取——一个平面上百个控件、每帧读上百次完全没有必要。</p>
     */
    private static long gameTimeFrameCache;
    private static int gameTimeFrameStamp = -1;
    private static int frameCounter;

    static long currentGameTimeCached() {
        if (gameTimeFrameStamp != frameCounter) {
            gameTimeFrameStamp = frameCounter;
            gameTimeFrameCache = top.hmjmfabc.projector.client.render.WidgetRenderer.currentGameTime();
        }
        return gameTimeFrameCache;
    }

    /** 每个渲染帧开头调一次（由计划渲染的入口调用）。 */
    public static void newFrame() {
        frameCounter++;
    }

    /** 控件覆盖范围内方块的最大表面位移（画布单位 -> 方块单位）。 */
    public static double surfaceDepthOf(Plane plane, Widget w) {
        int c0 = (int) Math.floor(w.minX() / PlaneCanvas.UNITS_PER_BLOCK);
        int c1 = (int) Math.floor((w.maxX() - 1.0e-3) / PlaneCanvas.UNITS_PER_BLOCK);
        int r0 = (int) Math.floor(w.minY() / PlaneCanvas.UNITS_PER_BLOCK);
        int r1 = (int) Math.floor((w.maxY() - 1.0e-3) / PlaneCanvas.UNITS_PER_BLOCK);
        // 注意初值必须是最小值而不是 0：不完整方块（半砖、铁砧）的深度是负数，
        // 从 0 起算会把它们全部丢掉，「贴住矮表面」就变成了死代码。
        double best = -Double.MAX_VALUE;
        boolean found = false;
        for (PlaneBlock b : plane.blocks.values()) {
            int bc = b.col();
            int br = b.row();
            if (bc < c0 || bc > c1 || br < r0 || br > r1) continue;
            found = true;
            if (b.depth > best) best = b.depth;
        }
        if (!found) {
            // 控件完全落在画布之外（玩家把它拖出去了）：**相对位移就是 0**，
            // 也就是「坐在锚点面所在的那张平面上」。
            //
            // 【血泪教训·⑨.1 天气控件前偏一格】
            // 这里以前返回的是 plane.anchorSurface，而调用方 WorldPlaneRenderer
            // 已经加过一次 facePlaneOffset = plane.anchorSurface：
            //     depth = anchorSurface(面平面) + surface(本函数) + ...
            // 于是「控件不在画布内」时深度整整多算了 1 格——只在
            // anchorSurface == 1 的三个朝向（UP / EAST / SOUTH）上看得见，
            // 表现就是「控件相对平面向前偏移了 1 个方块」。
            // 本函数的语义是「相对锚点面的位移」，锚点面自身的量不属于这里。
            warnOutOfCanvasOnce(plane, w);
            return 0.0;
        }
        return best / PlaneCanvas.UNITS_PER_BLOCK;
    }

    /**
     * 「控件整体落在画布之外」只提示一次。
     *
     * <p>以前这条分支是静默的：玩家只看到控件浮在墙外，日志里一个字都没有，
     * 根本无从判断到底是哪一步算错的。按 §5.3「不要静默 return」补上日志。</p>
     */
    private static final java.util.Set<java.util.UUID> OUT_OF_CANVAS_LOGGED =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private static void warnOutOfCanvasOnce(Plane plane, Widget w) {
        if (w == null || w.id == null) return;
        if (!OUT_OF_CANVAS_LOGGED.add(w.id)) return;
        top.hmjmfabc.projector.Projector.LOGGER.warn(
                "[Projector] 控件 {}（{}）整体落在画布 {}×{} 单位之外，"
                        + "深度按锚点面处理（surface=0）。rect=({},{})-({},{})",
                w.id.toString().substring(0, 8), w.kindId(), plane.width, plane.height,
                w.minX(), w.minY(), w.maxX(), w.maxY());
    }

    public int lastPlaneCount() {
        return lastPlanes;
    }

    public int lastQuadCount() {
        return lastQuads;
    }

    public QuadCollector collector() {
        return collector;
    }

    /**
     * 画布原点自检：把「画布 (0,0) 在世界中的位置」用两个独立算法各算一遍。
     *
     * <p>A) 渲染器使用的公式：
     * {@code originWorld() - axisX*canvasOffsetX/16 - axisY*canvasOffsetY/16}。</p>
     * <p>B) 数据中的事实：矩形为 {@code (0,0)} 的那个方块的世界最小角。</p>
     *
     * <p>两者描述的是同一件事，必须相等。不相等就说明「平面数据」与「渲染公式」
     * 之间差了偏移——差值就是偏移量的具体数值，不用再靠猜。</p>
     */
    private static void reportCanvasOrigin(Plane plane) {
        try {
            net.minecraft.core.Direction r = BlockFace.right(plane.face);
            net.minecraft.core.Direction u = BlockFace.up(plane.face);
            net.minecraft.world.phys.Vec3 o = plane.canvas().originWorld();
            double ox = o.x - r.getStepX() * (plane.canvasOffsetX / 16.0)
                    - u.getStepX() * (plane.canvasOffsetY / 16.0);
            double oy = o.y - r.getStepY() * (plane.canvasOffsetX / 16.0)
                    - u.getStepY() * (plane.canvasOffsetY / 16.0);
            double oz = o.z - r.getStepZ() * (plane.canvasOffsetX / 16.0)
                    - u.getStepZ() * (plane.canvasOffsetY / 16.0);
            net.minecraft.core.BlockPos ob = null;
            for (java.util.Map.Entry<Long, PlaneBlock> en : plane.blocks.entrySet()) {
                PlaneBlock b = en.getValue();
                if (Math.abs(b.x0) < 0.5 && Math.abs(b.y0) < 0.5) {
                    ob = net.minecraft.core.BlockPos.of(en.getKey());
                    break;
                }
            }
            // 【更强的自检】把「矩形中心」映射回世界，验证它确实落在该方块的范围内。
            // 之前只比较 A/B 两个公式，而两者用的是同一套轴，轴方向错了它照样通过——
            // 正是这样漏掉了「u=0 角取错一格」的 bug。这个检查与轴的正负无关。
            boolean same = false;
            String detail = "未找到矩形原点方块";
            for (java.util.Map.Entry<Long, PlaneBlock> en : plane.blocks.entrySet()) {
                PlaneBlock b = en.getValue();
                if (Math.abs(b.x0) > 0.5 || Math.abs(b.y0) > 0.5) continue;
                net.minecraft.core.BlockPos p2 = net.minecraft.core.BlockPos.of(en.getKey());
                double cxx = (b.x0 + b.x1) / 2.0 / 16.0;
                double cyy = (b.y0 + b.y1) / 2.0 / 16.0;
                double wx = ox + r.getStepX() * cxx + u.getStepX() * cyy;
                double wy = oy + r.getStepY() * cxx + u.getStepY() * cyy;
                double wz = oz + r.getStepZ() * cxx + u.getStepZ() * cyy;
                // 注意：面所在的那根轴上原点本来就在方块边界（由 anchorSurface 抬升），
                // 所以只检查「面内」两根轴。
                boolean inX = r.getStepX() == 0 && u.getStepX() == 0
                        || (wx >= p2.getX() - 0.01 && wx <= p2.getX() + 1.01);
                boolean inY = r.getStepY() == 0 && u.getStepY() == 0
                        || (wy >= p2.getY() - 0.01 && wy <= p2.getY() + 1.01);
                boolean inZ = r.getStepZ() == 0 && u.getStepZ() == 0
                        || (wz >= p2.getZ() - 0.01 && wz <= p2.getZ() + 1.01);
                same = inX && inY && inZ;
                detail = String.format(java.util.Locale.ROOT,
                        "矩形中心->世界=(%.2f,%.2f,%.2f) 应落在方块%s内", wx, wy, wz, p2.toShortString());
                break;
            }
            top.hmjmfabc.projector.Projector.LOGGER.info(
                    "[Projector] 画布原点自检: {} 朝向={} 画布{}x{} 单位 canvasOffset=({},{})"
                            + " A渲染器=({},{},{}) B矩形(0,0)方块={} {}",
                    plane.id.toString().substring(0, 8), plane.face.getName(),
                    plane.width, plane.height,
                    String.format(java.util.Locale.ROOT, "%.1f", plane.canvasOffsetX),
                    String.format(java.util.Locale.ROOT, "%.1f", plane.canvasOffsetY),
                    String.format(java.util.Locale.ROOT, "%.2f", ox),
                    String.format(java.util.Locale.ROOT, "%.2f", oy),
                    String.format(java.util.Locale.ROOT, "%.2f", oz),
                    detail,
                    same ? "✅ 落在方块内" : "❌ 不在方块内 <<< 偏移就在这里");
        } catch (Throwable t) {
            top.hmjmfabc.projector.Projector.LOGGER.warn("[Projector] 画布原点自检失败", t);
        }
    }
}

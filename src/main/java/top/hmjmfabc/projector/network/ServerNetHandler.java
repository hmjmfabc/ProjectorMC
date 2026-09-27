package top.hmjmfabc.projector.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;
import top.hmjmfabc.projector.Projector;
import top.hmjmfabc.projector.ProjectorConfig;
import top.hmjmfabc.projector.common.Plane;
import top.hmjmfabc.projector.common.TargetPicker;
import top.hmjmfabc.projector.common.widget.Fonts;
import top.hmjmfabc.projector.common.widget.ProgressWidget;
import top.hmjmfabc.projector.common.widget.Widget;
import top.hmjmfabc.projector.common.widget.Widgets;
import top.hmjmfabc.projector.server.MediaStore;
import top.hmjmfabc.projector.server.OutboundMeter;
import top.hmjmfabc.projector.server.PlanePermissions;
import top.hmjmfabc.projector.server.ProjectorData;
import top.hmjmfabc.projector.server.Sanitize;
import top.hmjmfabc.projector.server.TransferLog;
import top.hmjmfabc.projector.server.ServerFonts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 所有「客户端 → 服务端」包的处理逻辑。这里是模组的权限与数据权威中心：
 * 客户端的任何修改请求都要经过这里校验，防作弊也防止误操作破坏存档。
 */
public final class ServerNetHandler {

    /** 单个同步包最多携带的平面数量。 */
    private static final int SYNC_PLANES_PER_PACKET = 8;
    /** 单个同步包的估算字节预算（保守取 1 MiB 上限的一半）。 */
    private static final int SYNC_BYTES_BUDGET = 512 * 1024;

    /** 正在接收的上传会话。 */
    private static final Map<UUID, UploadSession> UPLOADS = new HashMap<>();

    private ServerNetHandler() {
    }

    private static final class UploadSession {
        final Payloads.MediaUpload head;
        byte[] buffer;
        int received;
        /** 已收到的字节位图：防止同一 offset 重复发送来伪造「完整」状态。 */
        java.util.BitSet covered;
        /**
         * 【rc-77】服务端已经有同哈希的文件：本次上传只用来「告诉客户端别传了」，
         * 不分配缓冲、不落盘、不计入上传累计。
         */
        boolean duplicate;
        /** 【②】本次上传适用的字节上限（开始时就定好，后续分片沿用同一个值）。 */
        long maxBytes;
        long startedAt = System.currentTimeMillis();

        boolean expired() {
            // 5 分钟没有进展的上传会话视为过期，避免玩家断线后残留占用内存
            return System.currentTimeMillis() - startedAt > 300_000L;
        }

        UploadSession(Payloads.MediaUpload head) {
            this(head, (int) head.totalSize());
        }

        private UploadSession(Payloads.MediaUpload head, int size) {
            this.head = head;
            this.buffer = new byte[0];
            this.covered = new java.util.BitSet(size);
            if (size > 0) this.buffer = new byte[size];
        }

        /** 【rc-77】重复上传会话：只 ack，不占内存。 */
        static UploadSession duplicateOf(Payloads.MediaUpload head) {
            UploadSession s = new UploadSession(head, 0);
            s.duplicate = true;
            return s;
        }
    }

    // ------------------------------------------------------------------
    // 圈选：创建平面
    // ------------------------------------------------------------------

    public static void onCreatePlane(Payloads.CreatePlane payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            ServerLevel level = player.serverLevel();
            ResourceLocation dim = level.dimension().location();
            if (!dim.equals(payload.dimension())) return;

            BlockPos pos = BlockPos.of(payload.anchor());
            if (!level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
                return;
            }
            // 距离校验：客户端配置不可信，服务端自己算一遍（防止隔墙/超远圈选）
            double maxDist = ProjectorConfig.INSTANCE.selectDistance.get() + 4.0;
            if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > maxDist * maxDist) {
                return;
            }
            ProjectorData data = ProjectorData.get(level);

            // 先看这个面是不是已经属于某个平面
            for (Plane p : data.planesAt(dim, pos)) {
                if (p.face == Direction.byName(payload.face())) {
                    sendSync(player, level, p);
                    return;
                }
            }

            if (data.countIn(dim) >= ProjectorConfig.INSTANCE.maxPlanesPerWorld.get()) {
                player.displayClientMessage(Component.translatable("projector.msg.too_many_planes"), true);
                return;
            }

            TargetPicker.FaceHit hit = resolveHit(level, pos, payload);
            if (hit == null) return;

            // 服务端自己重新泛洪一次，得到权威的平面范围（不信任客户端计算的方块集合）
            Plane plane = Plane.build(level, dim, hit, ProjectorConfig.INSTANCE.maxPlaneBlocks.get());
            plane.creator = player.getUUID();
            plane.creatorName = player.getGameProfile().getName();
            data.add(dim, plane);
            Projector.LOGGER.debug("[Projector] {} 创建平面 {} ({} 个方块面)", plane.creatorName, plane.id, plane.blockCount());

            // 只同步数据、不主动弹窗：按设计稿，第一次 U 是「圈选」，
            // 再按一次 U 才打开对话框。
            sendSync(player, level, plane);
        });
    }

    @Nullable
    public static TargetPicker.FaceHit resolveHit(ServerLevel level, BlockPos pos, Payloads.CreatePlane payload) {
        Direction face = Direction.byName(payload.face());
        if (face == null) return null;
        net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
        net.minecraft.world.phys.shapes.VoxelShape shape =
                state.getCollisionShape(level, pos, CollisionContext.empty());
        if (shape.isEmpty()) shape = state.getShape(level, pos, CollisionContext.empty());
        if (shape.isEmpty()) return null;
        Vec3 hit = new Vec3(payload.hitX(), payload.hitY(), payload.hitZ());
        return TargetPicker.pickFromShapeAt(pos, shape, face, hit);
    }

    private static void sendSync(ServerPlayer player, ServerLevel level, Plane plane) {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        list.add(plane.save());
        tag.put("planes", list);
        tag.putBoolean("replace", false);
        PacketDistributor.sendToPlayer(player, new Payloads.SyncPlanes(level.dimension().location(), tag));
    }

    /**
     * 【rc-78】把某个平面的权威状态**只发给一个玩家**。
     *
     * <p>用途：客户端对控件是**乐观更新**（本地先改、再提交）。服务端拒绝之后如果不回灌，
     * 玩家自己的屏幕上一切照旧 —— 「我明明改成功了」「字体明明生效了」，
     * 只有重登或下一次全量同步才会消失。这条路径就是那些「形同虚设」错觉的来源。</p>
     */
    public static void resyncTo(ServerPlayer player, ServerLevel level, Plane plane) {
        try {
            CompoundTag tag = new CompoundTag();
            ListTag list = new ListTag();
            list.add(plane.save());
            tag.put("planes", list);
            tag.putBoolean("replace", false);
            PacketDistributor.sendToPlayer(player,
                    new Payloads.SyncPlanes(level.dimension().location(), tag));
        } catch (Throwable t) {
            Projector.LOGGER.warn("[Projector] 回灌平面状态失败: {}", t.toString());
        }
    }

    /**
     * 把某个平面的最新状态广播给该维度内所有玩家。
     *
     * <p>【rc-81】现在会**合并**：同一平面 100 毫秒内最多真发一次，其余的攒到下一次
     * （由服务端刻结算）。原因：玩家「频繁新增/编辑控件」时，每一次操作都会
     * 把**整个平面的 NBT** 发给该维度所有玩家；连续操作就是一阵广播风暴，
     * 会把保活包挤在后面（同 AGENTS §5.5 第 72 条上传那次是同一族问题），
     * 表现就是「连接不稳定、频繁中断」。</p>
     */
    public static void broadcastPlane(ServerLevel level, Plane plane) {
        noteBroadcast(plane);
        long now = System.currentTimeMillis();
        Long last = BROADCAST_LAST_SENT.get(plane.id);
        if (last != null && now - last < BROADCAST_MIN_INTERVAL_MS) {
            BROADCAST_PENDING.put(plane.id, new PendingBroadcast(level, plane));
            return;
        }
        sendPlaneNow(level, plane);
    }

    /** 真正发一次（也会被「合并」路径复用）。 */
    private static void sendPlaneNow(ServerLevel level, Plane plane) {
        BROADCAST_LAST_SENT.put(plane.id, System.currentTimeMillis());
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        list.add(plane.save());
        tag.put("planes", list);
        tag.putBoolean("replace", false);
        PacketDistributor.sendToPlayersInDimension(level, new Payloads.SyncPlanes(plane.dimension, tag));
    }

    /**
     * 【rc-81】收到的平面编辑请求计数：每秒超过 20 次就警告一行。
     *
     * <p>用来区分「玩家手速快」和「客户端在刷/回环」——后者会在玩家停手后仍然持续出现。</p>
     */
    private static void noteOp(String op) {
        long now = System.currentTimeMillis();
        if (now - OP_WINDOW_START > 1000L) {
            OP_WINDOW_START = now;
            OP_COUNT = 0L;
        }
        OP_COUNT++;
        if (OP_COUNT == 20L) {
            Projector.LOGGER.warn("[Projector][服务端][同步] 平面编辑请求过于频繁：{}+ 次/秒（最近 op={}）"
                            + "—— 若玩家已停手仍持续，说明客户端在刷",
                    OP_COUNT, op);
        }
    }

    private static long OP_WINDOW_START = System.currentTimeMillis();
    private static long OP_COUNT;

    /** 【rc-82】被拒绝的下发请求：同一（玩家,哈希,原因）10 秒只放行一行日志。 */
    private static final Map<String, long[]> REFUSALS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long REFUSAL_LOG_INTERVAL_MS = 10_000L;

    /**
     * 记一次「被拒绝的下发」。
     *
     * @return true = 现在应该写这一行；false = 10 秒内的重复拒绝，省略不写
     */
    private static boolean noteRefusal(ServerPlayer player, String hash, String reason) {
        String key = player.getUUID() + "|" + hash + "|" + reason;
        long now = System.currentTimeMillis();
        long[] st = REFUSALS.computeIfAbsent(key, k -> new long[]{0L, 0L});
        st[1]++;
        if (now - st[0] < REFUSAL_LOG_INTERVAL_MS) return false;
        long skipped = st[1] - 1;
        st[0] = now;
        st[1] = 0L;
        if (skipped > 0) {
            Projector.LOGGER.info("[Projector][服务端][下发] （同时段另有 {} 次同样的拒绝已省略）", skipped);
        }
        if (REFUSALS.size() > 4096) REFUSALS.clear();
        return true;
    }

    /** 【rc-81】广播计数：每秒超过 15 次就警告一行（说明有人在刷，或存在回环）。 */
    private static void noteBroadcast(Plane plane) {
        long now = System.currentTimeMillis();
        long[] st = BROADCAST_STATS.computeIfAbsent(plane.id, k -> new long[]{now, 0L});
        if (now - st[0] > 1000L) {
            st[0] = now;
            st[1] = 0L;
        }
        st[1]++;
        if (st[1] == 15L) {
            Projector.LOGGER.warn("[Projector][服务端][同步] 平面 {} 广播过于频繁：{}+ 次/秒"
                            + "（控件可能正在被连续编辑；若玩家停手后仍然如此，说明存在回环）",
                    plane.displayName(), st[1]);
        }
    }

    /** 每秒广播次数超过这个值就警告。 */
    private static final int BROADCAST_WARN_PER_SEC = 15;

    /** 【rc-81】同一平面两次广播之间的最小间隔（毫秒）：其余合并。 */
    private static final long BROADCAST_MIN_INTERVAL_MS = 100L;

    private static final Map<UUID, Long> BROADCAST_LAST_SENT = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID, long[]> BROADCAST_STATS = new java.util.concurrent.ConcurrentHashMap<>();

    /** 合并中的广播：平面 id -> 待发。 */
    private record PendingBroadcast(ServerLevel level, Plane plane) {
    }

    private static final Map<UUID, PendingBroadcast> BROADCAST_PENDING =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 【rc-81】每个服务端刻结算被合并掉的广播（由 ServerEvents 的 tick 钩子调用）。 */
    public static void flushPendingBroadcasts() {
        if (BROADCAST_PENDING.isEmpty()) return;
        for (UUID id : new java.util.ArrayList<>(BROADCAST_PENDING.keySet())) {
            PendingBroadcast p = BROADCAST_PENDING.remove(id);
            if (p == null) continue;
            sendPlaneNow(p.level(), p.plane());
        }
    }

    /** 服务端关闭时清掉统计状态（避免跨存档残留）。 */
    public static void resetBroadcastState() {
        BROADCAST_PENDING.clear();
        BROADCAST_LAST_SENT.clear();
        BROADCAST_STATS.clear();
    }

    /** 通知该维度内所有玩家：某个平面已被删除。 */
    public static void broadcastDeletion(ServerLevel level, Plane plane) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("plane", plane.id);
        PacketDistributor.sendToPlayersInDimension(level,
                new Payloads.SyncPlanes(plane.dimension, tag));
    }

    // ------------------------------------------------------------------
    // 平面编辑
    // ------------------------------------------------------------------

    public static void onPlaneEdit(Payloads.PlaneEdit payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            noteOp(payload.op());
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            ServerLevel level = player.serverLevel();
            ProjectorData data = ProjectorData.get(level);
            Plane plane = data.byId(payload.planeId());
            if (plane == null) return;
            if (!plane.dimension.equals(level.dimension().location())) return;
            CompoundTag args = payload.args() == null ? new CompoundTag() : payload.args();

            boolean changed = switch (payload.op()) {
                case "rename" -> {
                    if (!PlanePermissions.canManage(plane, player)) yield false;
                    String name = args.getString("name");
                    if (name.length() > 64) name = name.substring(0, 64);
                    plane.name = name;
                    yield true;
                }
                case "protectBlocks" -> {
                    if (!PlanePermissions.canToggleBlockProtection(player)) {
                        player.displayClientMessage(Component.translatable("projector.msg.only_admin"), true);
                        yield false;
                    }
                    plane.protectBlocks = args.getBoolean("value");
                    yield true;
                }
                case "protectContent" -> {
                    if (!PlanePermissions.canToggleContentProtection(plane, player)) yield false;
                    plane.protectContent = args.getBoolean("value");
                    yield true;
                }
                case "miningWarning" -> {
                    if (!PlanePermissions.canToggleMiningWarning(plane, player)) yield false;
                    plane.miningWarning = args.getBoolean("value");
                    yield true;
                }
                case "addWidget" -> {
                    if (!PlanePermissions.canEditContent(plane, player)) {
                        player.displayClientMessage(Component.translatable("projector.msg.content_protected"), true);
                        yield false;
                    }
                    yield addWidget(level, plane, args, player);
                }
                case "updateWidget" -> {
                    if (!PlanePermissions.canEditContent(plane, player)) {
                        player.displayClientMessage(Component.translatable("projector.msg.content_protected"), true);
                        yield false;
                    }
                    yield updateWidget(level, plane, args, player);
                }
                case "removeWidget" -> {
                    if (!PlanePermissions.canEditContent(plane, player)) {
                        player.displayClientMessage(Component.translatable("projector.msg.content_protected"), true);
                        yield false;
                    }
                    UUID wid = args.hasUUID("widget") ? args.getUUID("widget") : null;
                    boolean removed = wid != null && plane.removeWidget(wid);
                    // 【⑩】控件没了，它的流程片段也必须跟着走：
                    // 否则那个「幽灵片段」会一直占着时间轴，还可能被当成
                    // 「末尾片段」而让真正的末尾控件不再出场。
                    if (removed && plane.sequence != null) {
                        plane.sequence.prune(plane.widgets);
                    }
                    yield removed;
                }
                case "moveWidget" -> {
                    if (!PlanePermissions.canEditContent(plane, player)) yield false;
                    UUID wid = args.hasUUID("widget") ? args.getUUID("widget") : null;
                    Widget w = wid == null ? null : plane.widgetById(wid);
                    if (w == null) yield false;
                    double nx = args.getDouble("x");
                    double ny = args.getDouble("y");
                    if (!Sanitize.finite(nx) || !Sanitize.finite(ny)) yield false;
                    double limX = plane.width + Math.max(64, plane.width * 0.5);
                    double limY = plane.height + Math.max(64, plane.height * 0.5);
                    double slack = Math.max(24, Math.min(plane.width, plane.height) * 0.25);
                    w.x = Sanitize.clamp(nx, -slack, limX, 0);
                    w.y = Sanitize.clamp(ny, -slack, limY, 0);
                    yield true;
                }
                case "transfer" -> {
                    if (!PlanePermissions.canTransfer(plane, player)) yield false;
                    UUID target = args.hasUUID("target") ? args.getUUID("target") : null;
                    if (target == null) yield false;
                    ServerPlayer tp = level.getServer().getPlayerList().getPlayer(target);
                    if (tp == null) {
                        player.displayClientMessage(Component.translatable("projector.msg.player_offline"), true);
                        yield false;
                    }
                    plane.creator = target;
                    plane.creatorName = tp.getGameProfile().getName();
                    yield true;
                }
                case "rebuild" -> {
                    // 用当前世界重新泛洪一次：既能修复旧版本存档里缺失的贴面基准，
                    // 也能在方块被改动之后重新对齐平面范围。
                    if (!PlanePermissions.canManage(plane, player)) yield false;
                    TargetPicker.FaceHit hit = resolveHit(level, plane.anchor, new Payloads.CreatePlane(
                            plane.dimension, plane.anchor.asLong(), plane.face.getName(),
                            plane.anchor.getX() + 0.5, plane.anchor.getY() + 0.5, plane.anchor.getZ() + 0.5));
                    if (hit == null) {
                        player.displayClientMessage(Component.translatable("projector.msg.rebuild_failed"), true);
                        yield false;
                    }
                    Plane fresh = Plane.build(level, plane.dimension, hit,
                            ProjectorConfig.INSTANCE.maxPlaneBlocks.get());
                    plane.applyFrom(fresh);
                    player.displayClientMessage(Component.translatable("projector.msg.rebuilt",
                            plane.blockCount()), false);
                    yield true;
                }
                case "deletePlane" -> {
                    if (!PlanePermissions.canDelete(plane, player)) yield false;
                    data.remove(plane.dimension, plane);
                    broadcastDeletion(level, plane);
                    yield false; // 已经单独广播
                }
                // ---- 【⑩】流程时间轴 ----
                // 改流程属于「改内容」，权限与控件编辑完全一致（内容保护开着时
                // 只有管理员/创建者能改）——否则任何人都能给别人的公告栏排一段动画。
                case "seqSet" -> {
                    if (!PlanePermissions.canEditContent(plane, player)) {
                        player.displayClientMessage(
                                Component.translatable("projector.msg.content_protected"), true);
                        yield false;
                    }
                    if (!args.contains("sequence")) yield false;
                    var track = top.hmjmfabc.projector.common.sequence.SequenceTrack.load(
                            args.getCompound("sequence"));
                    // 逐个片段再夹一次范围：客户端的滑块不可信
                    for (var c : track.clips()) {
                        c.sanitize();
                    }
                    // 引用了不存在控件的片段直接丢掉（否则时间轴上会出现「幽灵长条」，
                    // 而且它会一直占着「末尾片段不出场」这个名额）
                    track.prune(plane.widgets);
                    if (track.clips().size() > ProjectorConfig.INSTANCE.maxWidgetsPerPlane.get()) {
                        yield false;
                    }
                    plane.sequence = track;
                    yield true;
                }
                case "seqPlay", "seqResume", "seqPause", "seqStop", "seqSeek" -> {
                    if (!PlanePermissions.canEditContent(plane, player)) {
                        player.displayClientMessage(
                                Component.translatable("projector.msg.content_protected"), true);
                        yield false;
                    }
                    if (plane.sequence == null) {
                        yield false;
                    }
                    long nowTick = level.getGameTime();
                    switch (payload.op()) {
                        case "seqPlay" -> plane.sequence.playFromStart(nowTick);
                        case "seqResume" -> plane.sequence.resume(nowTick);
                        case "seqPause" -> plane.sequence.pause(nowTick);
                        case "seqStop" -> plane.sequence.stop();
                        default -> plane.sequence.seek(args.getDouble("sec"), nowTick);
                    }
                    // 播放状态变化必须广播：动画进度是从「游戏刻」推出来的，
                    // 只要所有人都拿到同一个 startTick，画面就天然同步。
                    broadcastPlane(level, plane);
                    yield false;
                }
                default -> false;
            };

            if (changed) {
                data.markDirty();
                broadcastPlane(level, plane);
            } else {
                // 【rc-78】这次编辑被拒（权限/校验失败）：把服务端的权威状态回灌给发起者，
                // 让他的客户端把乐观改的那一份回滚掉。以前不回灌 ⇒
                // 「内容保护开着我也改得动」「服务端没装的字体我也用上了」这类错觉。
                resyncTo(player, level, plane);
            }
        });
    }

    /**
     * 【①】联机字体核验。
     *
     * <p>规则（与用户原文一致）：</p>
     * <ul>
     *   <li>只在「客户端与服务端分离」时生效——单人存档 / 联机房间（集成服务器）不核验；</li>
     *   <li>内置字体（打包在 jar 里）永远可用；</li>
     *   <li>服务端 `{@code <存档>/projector/fonts/}` 里没有的字体，<b>禁止普通玩家使用</b>；</li>
     *   <li><b>管理员不受限制</b>：他可以先用字体选择器把本地字体上传到服务端
     *       （落盘后会回读重新核验哈希），之后所有人就都能用了；</li>
     *   <li>配置项 {@code media.verifyFontHashes} 关掉时整条规则不生效。</li>
     * </ul>
     */
    private static boolean checkFonts(Widget w, ServerPlayer player) {
        boolean enabled;
        try {
            enabled = ProjectorConfig.INSTANCE.verifyFontHashes.get();
        } catch (Throwable t) {
            enabled = true;
        }
        if (!enabled) return true;
        MinecraftServer server = player.getServer();
        // 集成服务器 = 单人存档 / 联机房间：两端就是同一台机器，不存在不一致
        if (server == null || !server.isDedicatedServer()) return true;
        java.util.List<String> used = w.fontIds();
        if (used == null || used.isEmpty()) return true;
        // 管理员可以先上传再使用
        if (PlanePermissions.isAdmin(player)) return true;
        boolean anyForeign = false;
        for (String id : used) {
            if (id == null || id.isEmpty()) continue;
            if (top.hmjmfabc.projector.common.widget.Fonts.MINECRAFT_AE.equals(id)
                    || top.hmjmfabc.projector.common.widget.Fonts.CAVIAR_DREAMS.equals(id)) {
                continue;   // 内置字体，两边都有
            }
            if (!ServerFonts.has(server, id)) {
                // 【rc-76】禁止使用「服务端没装的字体」必须留下完整证据：字体 ID + 玩家 + UUID。
                Projector.LOGGER.info("[Projector][服务端][字体] 禁止使用未安装的字体 字体={}"
                                + " 原因=服务端字体目录里没有这个 ID 玩家={} UUID={} IP={}",
                        id, player.getGameProfile().getName(), player.getUUID(), player.getIpAddress());
                anyForeign = true;
            }
        }
        return !anyForeign;
    }

    private static boolean addWidget(ServerLevel level, Plane plane, CompoundTag args, ServerPlayer player) {
        if (plane.widgets.size() >= ProjectorConfig.INSTANCE.maxWidgetsPerPlane.get()) {
            player.displayClientMessage(Component.translatable("projector.msg.too_many_widgets"), true);
            return false;
        }
        CompoundTag wt = args.getCompound("widget");
        Widget w = Widgets.load(wt);
        if (w == null) return false;
        if (!validateWidget(level, plane, w, player)) return false;
        if (!sanitizeGeometry(w, plane)) return false;
        // 保留客户端生成的 UUID：这样界面可以在等待服务端确认期间继续编辑同一个控件。
        // 只有在 UUID 冲突时才重新分配。
        if (plane.widgetById(w.id) != null) {
            w.id = UUID.randomUUID();
        }
        // 【⑧】计时器的启动锚点由服务端来打（客户端算不出权威时间）。
        stampTimerStart(w, level.getGameTime());
        plane.widgets.add(w);
        return true;
    }

    /**
     * 【⑧】给计时器补上启动锚点。
     *
     * <p>两种情形都要处理：</p>
     * <ul>
     *   <li>处于运行中、但锚点还是 0（新建的）→ 打上当前游戏刻。
     *       否则 {@code elapsedTicks} 会把「这个世界已经过去的所有刻」都算进去，
     *       一放上去读数就是天文数字；</li>
     *   <li><b>从未启动过</b>（{@code !running && 累计刻==0 && 锚点<=0}）→ 直接开始计时。
     *       用户明确要求「不该先按一次继续才开始」，所以这里替玩家按下。</li>
     * </ul>
     */
    private static void stampTimerStart(Widget w, long gameTime) {
        if (!(w instanceof top.hmjmfabc.projector.common.widget.TimerWidget timer)) return;
        boolean neverStarted = !timer.running && timer.accumulatedTicks == 0 && timer.startGameTime <= 0;
        if (neverStarted) {
            timer.running = true;
        }
        if (timer.running && timer.startGameTime <= 0) {
            timer.startGameTime = gameTime;
        }
    }

    private static boolean updateWidget(ServerLevel level, Plane plane, CompoundTag args, ServerPlayer player) {
        // 说明：拖动节流由客户端负责（120ms + 松手补交一次）。
        // 服务端刻意不再限流——否则「松手时的最终提交」有可能正好落进窗口被丢弃，
        // 控件就会永久停在中间态，这种数据不一致比多几个广播包严重得多。
        // 注意：控件数据放在独立的键 "data" 里。
        // 客户端以前把 UUID 和控件 CompoundTag 都放在 "widget" 键上，
        // 后一个 put 会把前一个覆盖掉，于是 hasUUID("widget") 永远为假、
        // 所有控件更新都被静默拒绝（文本改不动、图片的媒体信息也存不下来）。
        if (!args.hasUUID("widget")) return reject("缺少 widget id");
        CompoundTag wt = args.getCompound("data");
        Widget incoming = Widgets.load(wt);
        if (incoming == null) return reject("控件数据无法解析");
        Widget existing = plane.widgetById(incoming.id);
        if (existing == null) return reject("平面上找不到该控件 id=" + incoming.id);
        if (existing.kind() != incoming.kind()) return reject("控件类型不匹配");
        // 与「新增控件」执行完全相同的校验（文本长度、媒体存在性、不完整面限制），
        // 否则这些限制可以被 updateWidget 轻松绕过。
        if (!validateWidget(level, plane, incoming, player)) return reject("内容校验未通过");
        if (!sanitizeGeometry(incoming, plane)) return reject(String.format(
                java.util.Locale.ROOT,
                "几何校验未通过 x=%.2f y=%.2f w=%.2f h=%.2f rot=%.2f z=%.2f alpha=%.2f",
                incoming.x, incoming.y, incoming.w, incoming.h, incoming.rot, incoming.zOff,
                incoming.alpha));
        // 覆盖公共字段（位置/尺寸/旋转/层级/透明度/背景）与类型特有的内容字段
        existing.copyCommonFrom(incoming);
        existing.loadExtra(incoming.save());
        // 老存档里已存在的计时器也要在第一次编辑时就自动开始（见 stampTimerStart）
        stampTimerStart(existing, level.getGameTime());
        return true;
    }

    /**
     * 记录「为什么这次控件更新被拒绝」。
     *
     * <p>以前这些分支都是静默 {@code return false}，客户端完全收不到反馈，
     * 玩家看到的现象只是「保存没反应」，排查起来没有任何线索。</p>
     */
    private static boolean reject(String reason) {
        top.hmjmfabc.projector.Projector.LOGGER.warn("[Projector] 控件更新被拒绝：{}", reason);
        return false;
    }

    /**
     * 数值校验：位置/尺寸/旋转/透明度/层级都必须有限且在合理区间内。
     *
     * <p>这些值最终会变成顶点坐标，NaN 或 1e308 会让渲染直接出问题，
     * 而且会通过广播污染该维度所有客户端。</p>
     */
    private static boolean sanitizeGeometry(Widget w, Plane plane) {
        // 收紧到画布范围：原来允许 ±4 倍画布，等于没限制，控件能被拖到看不见的地方
        double maxX = plane.width + Math.max(64, plane.width * 0.5);
        double maxY = plane.height + Math.max(64, plane.height * 0.5);
        if (!Sanitize.finite(w.x) || !Sanitize.finite(w.y) || !Sanitize.finite(w.w) || !Sanitize.finite(w.h)
                || !Sanitize.finite(w.rot) || !Sanitize.finite(w.zOff) || !Float.isFinite(w.alpha)) {
            return false;
        }
        double slack = Math.max(24, Math.min(plane.width, plane.height) * 0.25);
        w.x = Sanitize.clamp(w.x, -slack, maxX, 0);
        w.y = Sanitize.clamp(w.y, -slack, maxY, 0);
        w.w = Sanitize.clamp(w.w, 0.5, Math.max(16, plane.width * 2.0), 16);
        w.h = Sanitize.clamp(w.h, 0.5, Math.max(16, plane.height * 2.0), 16);
        w.rot = Sanitize.clamp(w.rot, -3600, 3600, 0);
        w.zOff = Sanitize.clamp(w.zOff, -512, 512, 0);
        w.alpha = Sanitize.clamp(w.alpha, 0f, 1f, 1f);
        // 各控件类型特有的数值字段同样必须校验：它们会直接影响排版与顶点坐标
        switch (w.kind()) {
            case Widget.KIND_TEXT -> {
                var tw = (top.hmjmfabc.projector.common.widget.TextWidget) w;
                tw.fontSize = Sanitize.clamp(tw.fontSize, 0.5, 512, 10);
                tw.lineSpacing = Sanitize.clamp(tw.lineSpacing, 0.4, 8.0, 1.15);
                tw.wrapWidth = tw.wrapWidth > 0 ? Sanitize.clamp(tw.wrapWidth, 1, 4096, -1) : -1;
                tw.background = tw.background;
            }
            case Widget.KIND_IMAGE -> {
                var iw = (top.hmjmfabc.projector.common.widget.ImageWidget) w;
                iw.u0 = Sanitize.clamp(iw.u0, 0, 1, 0);
                iw.v0 = Sanitize.clamp(iw.v0, 0, 1, 0);
                iw.u1 = Sanitize.clamp(iw.u1, 0, 1, 1);
                iw.v1 = Sanitize.clamp(iw.v1, 0, 1, 1);
            }
            case Widget.KIND_VIDEO -> {
                var vw = (top.hmjmfabc.projector.common.widget.VideoWidget) w;
                double maxFps = ProjectorConfig.INSTANCE.maxVideoFps.get();
                vw.fps = Sanitize.clamp(vw.fps, 0.1, Math.max(1.0, maxFps), 10);
                vw.frameCount = (int) Sanitize.clamp(vw.frameCount, 1, 200_000, 1);
                vw.pausedFrame = (int) Sanitize.clamp(vw.pausedFrame, 0, 200_000, 0);
            }
            case Widget.KIND_CLOCK -> {
                var cw = (top.hmjmfabc.projector.common.widget.ClockWidget) w;
                cw.fontSize = Sanitize.clamp(cw.fontSize, 0.5, 512, 12);
                cw.offsetMinutes = (int) Sanitize.clamp(cw.offsetMinutes, -1440, 1440, 0);
                // ⑦ 新增字段同样要校验：它们直接决定排版与顶点位置
                cw.titleSize = Sanitize.clamp(cw.titleSize, 0.5, 256, 8);
                cw.periodSize = Sanitize.clamp(cw.periodSize, 0.5, 256, 8);
                if (cw.style < 0 || cw.style >= top.hmjmfabc.projector.common.widget.ClockWidget.STYLE_NAMES.length) {
                    cw.style = top.hmjmfabc.projector.common.widget.ClockWidget.STYLE_SIMPLE;
                }
                if (cw.title != null && cw.title.length() > 256) {
                    cw.title = cw.title.substring(0, 256);
                }
            }
            case Widget.KIND_WEATHER -> {
                var ww = (top.hmjmfabc.projector.common.widget.WeatherWidget) w;
                ww.fontSize = Sanitize.clamp(ww.fontSize, 0.5, 512, 12);
                ww.iconScale = Sanitize.clamp(ww.iconScale, 0.05, 4.0, 0.85);
            }
            case Widget.KIND_PROGRESS -> {
                var pw = (top.hmjmfabc.projector.common.widget.ProgressWidget) w;
                pw.titleSize = Sanitize.clamp(pw.titleSize, 0.5, 256, 8);
                pw.valueSize = Sanitize.clamp(pw.valueSize, 0.5, 512, 18);
                pw.durationSeconds = Sanitize.clamp(pw.durationSeconds, 0, 86_400, 60);
                pw.manualProgress = Sanitize.clamp(pw.manualProgress, 0, 1, 0);
            }
            case Widget.KIND_TIMER -> {
                // 【⑧】时长上限按用户要求是 2147483647 秒（现实时间）。
                // 换算成刻是 4.3e10，long 完全放得下，所以这里可以直接收下这个上限。
                var tw = (top.hmjmfabc.projector.common.widget.TimerWidget) w;
                if (tw.type != top.hmjmfabc.projector.common.widget.TimerWidget.TYPE_UP
                        && tw.type != top.hmjmfabc.projector.common.widget.TimerWidget.TYPE_DOWN) {
                    tw.type = top.hmjmfabc.projector.common.widget.TimerWidget.TYPE_DOWN;
                }
                if (tw.style != top.hmjmfabc.projector.common.widget.TimerWidget.STYLE_SIMPLE
                        && tw.style != top.hmjmfabc.projector.common.widget.TimerWidget.STYLE_FANCY) {
                    tw.style = top.hmjmfabc.projector.common.widget.TimerWidget.STYLE_SIMPLE;
                }
                tw.fontSize = Sanitize.clamp(tw.fontSize, 0.5, 512, 16);
                tw.durationSeconds = Sanitize.clamp(tw.durationSeconds, 0, 2_147_483_647.0, 60);
                // 指令只允许单行、限长；真正的权限与存在性校验在执行时再做一次
                if (tw.command != null) {
                    String cmd = tw.command.replace('\n', ' ').replace('\r', ' ').trim();
                    if (cmd.startsWith("/")) cmd = cmd.substring(1);
                    tw.command = cmd.length() > 256 ? cmd.substring(0, 256) : cmd;
                }
            }
            case Widget.KIND_CHESS -> {
                // 【⑪】棋类游戏：棋子字号/线宽这类数值同样会进顶点，必须夹。
                // 棋局本身由 GameSession.load 自己兜底（棋盘尺寸不匹配就重开一局），
                // 这里只保证「看板」相关的数值安全。
                var gw = (top.hmjmfabc.projector.common.widget.ChessWidget) w;
                gw.fontSize = Sanitize.clamp(gw.fontSize, 0.5, 256, 12);
                if (gw.game == null) {
                    gw.game = new top.hmjmfabc.projector.common.game.GameSession();
                    gw.game.reset();
                }
                // 档位越界（手改存档/旧版本）时回落到「高」
                gw.game.difficulty = top.hmjmfabc.projector.common.game.GameDifficulty
                        .clamp(gw.game.difficulty);
                // 【rc-84】棋盘必须完整落在平面内：超出的部分在墙上是「没有面」的，
                // 右键永远打不到 —— 玩家会把「那半边的子走不了」当成规则 bug
                // （实测反馈：「兵越不过楚河汉界，炮吃不到相隔的子」）。
                if (top.hmjmfabc.projector.common.widget.ChessWidget.fitIntoPlane(
                        gw, plane.width, plane.height)) {
                    Projector.LOGGER.info("[Projector] 棋盘控件已按平面 {}x{} 缩小"
                            + "（原尺寸超出平面，超出的点位右键打不到）", plane.width, plane.height);
                }
            }
            case Widget.KIND_MUSIC -> {
                // 【音乐控件】只夹数值，不碰播放状态（播放状态由 toggle 动作改）
                var mw = (top.hmjmfabc.projector.common.widget.MusicWidget) w;
                mw.fontSize = Sanitize.clamp(mw.fontSize, 0.5, 256, 9);
                mw.corner = Sanitize.clamp(mw.corner, 0, 64, 5);
                mw.volume = Sanitize.clamp(mw.volume, 0, 1, 0.8);
                mw.barCount = (int) Sanitize.clamp(mw.barCount, 0, 256, 0);
                if (mw.sourceKey == null || mw.sourceKey.length() > 512) {
                    mw.sourceKey = mw.sourceKey == null ? "" : mw.sourceKey.substring(0, 512);
                }
                if (mw.title != null && mw.title.length() > 256) {
                    mw.title = mw.title.substring(0, 256);
                }
                if (mw.artist != null && mw.artist.length() > 256) {
                    mw.artist = mw.artist.substring(0, 256);
                }
                if (mw.sourceKind == null || mw.sourceKind.isBlank()) {
                    mw.sourceKind = "LOCAL";
                }
                mw.durationMs = (long) Sanitize.clamp(mw.durationMs, 0, 24 * 3600_000L, 0);
                mw.positionMs = (long) Sanitize.clamp(mw.positionMs, 0, 24 * 3600_000L, 0);
            }
            case Widget.KIND_LEADERBOARD -> {
                var lw = (top.hmjmfabc.projector.common.widget.LeaderboardWidget) w;
                lw.titleSize = Sanitize.clamp(lw.titleSize, 0.5, 256, 10);
                lw.rowSize = Sanitize.clamp(lw.rowSize, 0.5, 256, 9);
                lw.lineSpacing = Sanitize.clamp(lw.lineSpacing, 0.4, 8.0, 1.15);
                lw.maxRows = (int) Sanitize.clamp(lw.maxRows, 1, 64, 10);
                if (lw.title != null && lw.title.length() > 256) {
                    lw.title = lw.title.substring(0, 256);
                }
                if (lw.objective != null && lw.objective.length() > 128) {
                    lw.objective = lw.objective.substring(0, 128);
                }
            }
            default -> {
            }
        }
        return true;
    }

    private static boolean validateWidget(ServerLevel level, Plane plane, Widget w, ServerPlayer player) {
        // 【①】联机字体核验：服务端没装的字体，普通玩家不许用。
        // 管理员不受此限制——他可以在字体选择器里点「上传选中字体到服务端」，
        // 上传成功后所有人就都能用了（这正是用户要求的流程）。
        if (!checkFonts(w, player)) {
            player.displayClientMessage(Component.translatable("projector.msg.font_not_on_server"), true);
            return false;
        }
        // 防止客户端提交超大文本拖垮服务端与所有客户端的渲染
        if (w instanceof top.hmjmfabc.projector.common.widget.TextWidget tw && tw.text.length() > 8192) {
            player.displayClientMessage(Component.translatable("projector.msg.text_too_long"), true);
            return false;
        }
        if (w instanceof top.hmjmfabc.projector.common.widget.ProgressWidget pw && pw.title != null
                && pw.title.length() > 256) {
            pw.title = pw.title.substring(0, 256);
        }
        if (plane.isIncomplete() && w.kind() != Widget.KIND_TEXT) {
            player.displayClientMessage(Component.translatable("projector.msg.incomplete_text_only"), true);
            return false;
        }
        if (w.isMedia()) {
            String media = w.kind() == Widget.KIND_IMAGE
                    ? ((top.hmjmfabc.projector.common.widget.ImageWidget) w).mediaId
                    : ((top.hmjmfabc.projector.common.widget.VideoWidget) w).mediaId;
            if (media != null && !media.isEmpty() && !MediaStore.get(level.getServer()).has(media)) {
                player.displayClientMessage(Component.translatable("projector.msg.media_missing"), true);
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 媒体上传
    // ------------------------------------------------------------------

    public static void onMediaUpload(Payloads.MediaUpload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;

            // 服务端权威校验：默认**放开**（普通玩家也能传，大小配额按身份另行判定）；
            // 只有服务端把 media.onlyAdminCanUpload 打开时才要求管理员。
            if (!PlanePermissions.canAddMedia(player)) {
                player.displayClientMessage(Component.translatable("projector.msg.only_op4"), true);
                TransferLog.uploadFail(player,
                        "权限不足（服务端开启了 media.onlyAdminCanUpload，只有管理员能上传）",
                        payload.hash(), payload.name(), payload.totalSize(), 0);
                return;
            }

            UUID uuid = player.getUUID();
            UploadSession session = UPLOADS.get(uuid);
            if (session != null && session.expired()) {
                UPLOADS.remove(uuid);
                session = null;
            }

            if (payload.offset() == 0 && payload.hash() != null && !payload.hash().isEmpty()) {
                if (Sanitize.hash(payload.hash()) == null) {
                    player.displayClientMessage(Component.translatable("projector.msg.bad_hash"), true);
                    TransferLog.uploadFail(player, "哈希非法", payload.hash(), payload.name(),
                            payload.totalSize(), 0);
                    return;
                }
                // 新会话
                // 【②】配额按身份取：普通玩家 4 MB / 64 MB，管理员 16 MB / 256 MB，
                // 单人存档与联机房间完全不限（Long.MAX_VALUE）。
                long limit = payload.video() ? videoLimitFor(player) : imageLimitFor(player);
                if (payload.video()) {
                    String blocked = blockReasonForVideo(player, payload.totalSize());
                    if (blocked != null) {
                        player.displayClientMessage(Component.literal(blocked), true);
                        TransferLog.uploadFail(player, "视频超过硬上限（" + blocked + "）", payload.hash(),
                                payload.name(), payload.totalSize(), 0);
                        UPLOADS.remove(uuid);
                        return;
                    }
                }
                if (payload.totalSize() <= 0 || payload.totalSize() > limit) {
                    player.displayClientMessage(Component.translatable("projector.msg.media_too_large",
                            limit == Long.MAX_VALUE ? "不限" : (limit / 1024 / 1024) + " MB"), true);
                    TransferLog.uploadFail(player, "超过配额（上限 "
                            + (limit == Long.MAX_VALUE ? "不限" : (limit / 1024 / 1024) + " MB") + "）",
                            payload.hash(), payload.name(), payload.totalSize(), 0);
                    UPLOADS.remove(uuid);
                    return;
                }
                if (MediaStore.get(server).count() >= ProjectorConfig.INSTANCE.maxMediaPerWorld.get()
                        && !MediaStore.get(server).has(payload.hash())) {
                    player.displayClientMessage(Component.translatable("projector.msg.too_many_media"), true);
                    TransferLog.uploadFail(player, "存档媒体数量已达上限", payload.hash(), payload.name(),
                            payload.totalSize(), 0);
                    return;
                }
                // 【rc-77】服务端已经有同哈希、且大小完全一致 ⇒ 立刻告诉客户端「别传了」。
                // 以前这种情况客户端会把整份文件再传一遍（实测同一份 24 MB 视频传了 3 次），
                // 服务端也白收白写 —— 这就是用户看到的「流量远远超出视频大小」。
                MediaStore dupStore = MediaStore.get(server);
                MediaStore.Entry existing = dupStore.get(payload.hash());
                if (existing != null && existing.size == payload.totalSize()) {
                    session = UploadSession.duplicateOf(payload);
                    session.maxBytes = limit;
                    UPLOADS.put(uuid, session);
                    TransferLog.uploadSkip(player, payload.hash(), payload.name(), payload.totalSize());
                    PacketDistributor.sendToPlayer(player, new Payloads.MediaAck(
                            payload.session(), 0, true, true, "服务端已有同哈希"));
                    return;
                }
                session = new UploadSession(payload);
                session.maxBytes = limit;
                UPLOADS.put(uuid, session);
                // 【②】每一次上传都留一行「开始」（哈希/大小/分片数/玩家身份/IP 都在里面）
                int chunkCfg = 0;
                try {
                    chunkCfg = ProjectorConfig.INSTANCE.uploadChunkBytes.get();
                } catch (Throwable ignored) {
                }
                TransferLog.uploadStart(player, payload.hash(), payload.name(), payload.totalSize(),
                        payload.video(), limit, chunkCfg);
            }
            if (session == null) {
                TransferLog.uploadFail(player, "没有可用的上传会话（会话已过期或首片缺失）",
                        payload.hash(), payload.name(), 0, 0);
                return;
            }

            byte[] chunk = payload.data();
            if (chunk == null) chunk = new byte[0];
            int offset = payload.offset();
            if (chunk.length > ProjectorConfig.INSTANCE.uploadChunkBytes.get() * 4) {
                TransferLog.uploadFail(player, "单个分片过大（" + chunk.length + " 字节）",
                        session.head.hash(), session.head.name(), session.buffer.length, session.received);
                UPLOADS.remove(uuid);
                return;
            }
            if (offset < 0 || offset + chunk.length > session.buffer.length) {
                TransferLog.uploadFail(player, "分片越界（offset=" + offset + " len=" + chunk.length
                        + " 总长=" + session.buffer.length + "）",
                        session.head.hash(), session.head.name(), session.buffer.length, session.received);
                UPLOADS.remove(uuid);
                return;
            }
            if (session.duplicate) return;      // 已经通知过客户端停手，剩下的分片直接忽略

            System.arraycopy(chunk, 0, session.buffer, offset, chunk.length);
            // 【rc-77】只数「新覆盖」的字节：以前每次分片都调 covered.cardinality()，
            // 那是 O(文件大小/64) 的扫描 —— 24 MB 的文件传一遍要做 1227 次百万级字扫描，
            // 全在服务端主线程上（这也是服务端卡顿、保活延迟的帮凶）。
            int newly = 0;
            int end = offset + chunk.length;
            for (int i = offset; i < end; ) {
                int clear = session.covered.nextClearBit(i);
                if (clear >= end) break;
                session.covered.set(clear);
                newly++;
                i = clear + 1;
            }
            session.received += newly;

            // 【rc-77】逐片确认：客户端靠它做流控（最多领先一个窗口），
            // 服务端也因此能随时把「已有同哈希 / 有空洞」这类信息立刻告诉客户端。
            PacketDistributor.sendToPlayer(player, new Payloads.MediaAck(
                    payload.session(), session.received, false, false, ""));

            if (!payload.last()) return;
            UPLOADS.remove(uuid);
            if (session.received < session.buffer.length) {
                TransferLog.uploadFail(player, "分片不完整（有空洞）", session.head.hash(),
                        session.head.name(), session.buffer.length, session.received);
                return;
            }

            // 【rc-76 哈希校验】把收到的字节重新算一遍 SHA-1，与客户端声明的哈希比对。
            // 以前是**直接信任客户端声明**并把内容按那个哈希入库——传坏了也照存，
            // 于是别人按哈希下载永远拿到坏文件（症状：控件一直「加载中…」）。
            String actualHash = top.hmjmfabc.projector.server.ServerFonts.sha1Hex(session.buffer);
            boolean hashOk = actualHash != null && actualHash.equalsIgnoreCase(session.head.hash());
            TransferLog.hashCheck(player, "上传", session.head.hash(), actualHash, hashOk,
                    session.buffer.length);
            if (!hashOk) {
                try {
                    PacketDistributor.sendToPlayer(player, new Payloads.MediaAck(
                            payload.session(), 0, true, false, "服务端哈希校验不通过"));
                } catch (Throwable ignored) {
                }
                player.displayClientMessage(Component.translatable("projector.msg.media_failed"), true);
                TransferLog.uploadFail(player, "哈希校验不通过（收到的内容与声明的哈希不符）",
                        session.head.hash(), session.head.name(), session.buffer.length, session.received);
                return;
            }

            MediaStore store = MediaStore.get(server);
            MediaStore.Entry entry = new MediaStore.Entry();
            entry.hash = session.head.hash();
            entry.name = session.head.name();
            entry.video = session.head.video();
            entry.width = session.head.width();
            entry.height = session.head.height();
            entry.frameCount = Math.max(1, session.head.frameCount());
            entry.fps = session.head.fps();
            entry.size = session.buffer.length;
            long[] ft = session.head.frameTable();
            if (entry.video && ft != null) {
                for (int i = 0; i + 1 < ft.length && entry.frames.size() < 200_000; i += 2) {
                    long off = ft[i];
                    long len = ft[i + 1];
                    // 帧表由客户端提供：越界的偏移/长度直接丢弃，避免读越界与超大分配
                    if (off < 0 || len <= 0 || off + len > session.buffer.length) continue;
                    entry.frames.add(new long[]{off, len});
                }
            }
            try {
                boolean isNew = store.put(server, entry, session.buffer, session.maxBytes);
                // 【②】每一次上传都留一行「完成」（分片数/用时/是否新文件/当日上传累计）
                int chunkBytes = 1;
                try {
                    chunkBytes = Math.max(1, ProjectorConfig.INSTANCE.uploadChunkBytes.get());
                } catch (Throwable ignored) {
                }
                TransferLog.uploadDone(player, entry.hash, entry.name, entry.size,
                        (int) ((entry.size + chunkBytes - 1) / chunkBytes),
                        System.currentTimeMillis() - session.startedAt, isNew, session.maxBytes);
                // 【rc-77】最终确认：客户端要等服务端「落盘 + 哈希校验通过」才算上传成功
                try {
                    PacketDistributor.sendToPlayer(player, new Payloads.MediaAck(
                            payload.session(), (int) Math.min(Integer.MAX_VALUE, entry.size),
                            true, false, ""));
                } catch (Throwable ignored) {
                }
                // 媒体集合变了：给所有人重发清单，这样其他客户端能立刻按档位预缓存（⑫）
                for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                    sendServerInfo(sp);
                }
            } catch (IOException ex) {
                Projector.LOGGER.error("[Projector] 写入媒体失败", ex);
                player.displayClientMessage(Component.translatable("projector.msg.media_failed"), true);
            }
        });
    }

    // ------------------------------------------------------------------
    // 客户端拉取媒体
    // ------------------------------------------------------------------

    public static void onMediaRequest(Payloads.MediaRequest payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            MediaStore store = MediaStore.get(server);
            if (!Sanitize.isHash(payload.hash())) {
                TransferLog.downloadFail(player, "哈希非法", payload.hash(), payload.frame());
                replyMissing(player, payload.hash(), payload.frame());
                return;
            }
            MediaStore.Entry entry = store.get(payload.hash());
            if (entry == null) {
                TransferLog.downloadFail(player, "服务端没有这份媒体", payload.hash(), payload.frame());
                replyMissing(player, payload.hash(), payload.frame());
                return;
            }
            // 【②】当日出站流量已用尽 -> 当日剩余时间拒绝向任何客户端下发任何文件
            OutboundMeter meter = OutboundMeter.get(server);
            if (meter.rejectIfExhausted(player)) {
                // 【rc-82】限流：配额用尽后客户端可能还在猛问，以前每次拒绝都写一行
                //（实测一局 4587 行，日志被撑到几 MB）。现在同一「玩家+哈希+原因」
                // 10 秒最多一行，并注明这段时间内被省略了多少次。
                if (noteRefusal(player, payload.hash(), "当日出站流量已达上限")) {
                    TransferLog.downloadFail(player, "当日出站流量已达上限", payload.hash(), payload.frame());
                    // 【rc-82】顺手把「配额已用尽」推给这个客户端：否则它要等到下次登录/上传
                    // 才更新 flag，中间会一直问、服务端一直拒（这次就是这样刷了 4587 次）。
                    // 与上面同一道 10 秒闸门 ⇒ 最多每 10 秒一次，不会自己变成新的风暴。
                    sendServerInfo(player);
                }
                replyMissing(player, payload.hash(), payload.frame());
                return;
            }
            if (!entry.video) {
                // 图片：按区间读取并下发（避免每次分片都整读文件）
                int absOffset = Math.max(0, payload.offset());
                // 【rc-83】「从头再来」的请求过于频繁 -> 拒绝（见 restartStorm 的说明）
                if (absOffset == 0 && restartStorm(player, payload.hash())) {
                    replyMissing(player, payload.hash(), payload.frame());
                    return;
                }
                int want = payload.length() <= 0 ? CHUNK_BYTES : payload.length();
                byte[] part = store.readRange(server, payload.hash(), absOffset, Math.min(want, CHUNK_BYTES));
                if (part == null) {
                    TransferLog.downloadFail(player, "读取媒体区间失败（存档文件缺失？）",
                            payload.hash(), payload.frame());
                    replyMissing(player, payload.hash(), payload.frame());
                    return;
                }
                boolean lastChunk = absOffset + part.length >= entry.size;
                meter.record("图片", payload.hash(), part.length, player,
                        absOffset == 0, entry.size);
                TransferLog.download(player, "图片", payload.hash(), entry.name, entry.size, false,
                        payload.frame(), part.length);
                PacketDistributor.sendToPlayer(player, new Payloads.MediaData(payload.hash(), 0, absOffset,
                        (int) Math.min(Integer.MAX_VALUE, entry.size), false,
                        entry.width, entry.height, 1, 10, part, lastChunk, false));
                return;
            }
            // 【rc-83】视频：**只有 frame < 0 才表示「整段文件」**，frame >= 0 一律是单帧（含第 0 帧）。
            // 以前判据是 frame == 0，而视频播放循环必然反复回到第 0 帧 ⇒ 每循环一圈就重发一次
            // 整段文件（实测 24 MB 的视频被整段下发 3 次，白烧 72 MB 出站，玩家还什么也没看到）。
            if (payload.frame() < 0) {
                int absOffset = Math.max(0, payload.offset());
                if (absOffset == 0 && restartStorm(player, payload.hash())) {
                    replyMissing(player, payload.hash(), payload.frame());
                    return;
                }
                int want = payload.length() <= 0 ? CHUNK_BYTES : Math.min(payload.length(), CHUNK_BYTES);
                // 【rc-83】用 readRange 定位读：以前这里走 store.read()，
                // 于是**每一个 200 KB 分片都把整个 24 MB 文件读一遍**（还在服务端主线程上）。
                byte[] part = store.readRange(server, payload.hash(), absOffset, want);
                if (part == null) {
                    TransferLog.downloadFail(player, "读取视频区间失败（存档文件缺失？）",
                            payload.hash(), payload.frame());
                    replyMissing(player, payload.hash(), payload.frame());
                    return;
                }
                boolean lastChunk = (long) absOffset + part.length >= entry.size;
                meter.record("视频", payload.hash(), part.length, player, absOffset == 0, entry.size);
                TransferLog.download(player, "视频", payload.hash(), entry.name, entry.size, true,
                        payload.frame(), part.length);
                PacketDistributor.sendToPlayer(player, new Payloads.MediaData(payload.hash(), payload.frame(),
                        absOffset, (int) Math.min(Integer.MAX_VALUE, entry.size), true,
                        entry.width, entry.height, entry.frameCount, entry.fps, part, lastChunk, false));
                return;
            }
            byte[] data = store.readFrame(server, payload.hash(), payload.frame());
            String kind = "视频帧";
            if (data == null) {
                TransferLog.downloadFail(player, "读取视频帧失败（帧号越界或存档文件缺失）",
                        payload.hash(), payload.frame());
                replyMissing(player, payload.hash(), payload.frame());
                return;
            }
            int offset = Math.max(0, Math.min(payload.offset(), data.length));
            int length = payload.length() <= 0 ? data.length - offset
                    : Math.min(payload.length(), data.length - offset);
            length = Math.max(0, Math.min(length, CHUNK_BYTES));
            byte[] chunk = new byte[length];
            if (length > 0) {
                System.arraycopy(data, offset, chunk, 0, length);
            }
            boolean last = offset + length >= data.length;
            meter.record(kind, payload.hash(), chunk.length, player,
                    offset == 0, data.length);
            // 【②】逐分片累计到「同玩家 + 同哈希」的一次连续下发里，只在开头与结算时各打一行
            TransferLog.download(player, kind, payload.hash(), entry.name,
                    payload.frame() > 0 ? chunk.length : data.length, true, payload.frame(), chunk.length);
            PacketDistributor.sendToPlayer(player, new Payloads.MediaData(payload.hash(), payload.frame(), offset,
                    data.length, true, entry.width, entry.height, entry.frameCount, entry.fps,
                    chunk, last, false));
        });
    }

    /** 单次下发请求的字节上限（与客户端的续传步长一致）。 */
    private static final int CHUNK_BYTES = 200 * 1024;

    /** 见 {@link #restartStorm}。 */
    private static final Map<String, long[]> RESTARTS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_WHOLE_RESTARTS = 8;
    private static final long RESTART_WINDOW_MS = 10_000L;

    /**
     * 【rc-83】「同一份媒体从头再来」的请求计数：{@code 玩家UUID|哈希 -> {窗口起点, 次数}}。
     *
     * <p>正常情况下一次下载只会请求一次 {@code offset = 0}，后续全是递增偏移的续传。
     * 短时间大量「从头再来」必然意味着客户端在重试风暴里（rc-82 实测：一张图片被反复下发
     * 58 MB / 98.6 MB，两个会话到玩家登出都没收完），此时服务端必须<b>主动拒绝</b>：
     * 否则一条链路会被同一份内容灌满，玩家的操作包与保活包全部被饿死
     * （表现就是「延迟特别大，圈选平面和破坏方块都点不动」，最后连接被 reset）。</p>
     *
     * @return true = 属于重试风暴，调用方应当拒绝这次请求
     */
    private static boolean restartStorm(ServerPlayer player, String hash) {
        String key = player.getUUID() + "|" + hash;
        long now = System.currentTimeMillis();
        long[] st = RESTARTS.computeIfAbsent(key, k -> new long[]{now, 0L});
        synchronized (st) {
            if (now - st[0] > RESTART_WINDOW_MS) {
                st[0] = now;
                st[1] = 0L;
            }
            st[1]++;
            if (st[1] > MAX_WHOLE_RESTARTS) {
                if (noteRefusal(player, hash, "整段下载重复起始")) {
                    Projector.LOGGER.warn("[Projector][服务端][下发] 同一份媒体的「从头再来」请求过于频繁："
                                    + "{} 次/{}秒 玩家={} 哈希={} 已拒绝（正常客户端一次下载只请求一次；"
                                    + "出现这行说明客户端在重试风暴里）",
                            st[1], RESTART_WINDOW_MS / 1000L, player.getName().getString(), hash);
                }
                return true;
            }
        }
        if (RESTARTS.size() > 4096) RESTARTS.clear();
        return false;
    }

    private static void replyMissing(ServerPlayer player, String hash, int frame) {
        PacketDistributor.sendToPlayer(player, new Payloads.MediaData(hash, frame, 0, 0,
                false, 0, 0, 1, 10, new byte[0], true, true));
    }

    // ------------------------------------------------------------------
    // 【①②⑫】上传配额（普通玩家 / 管理员 / 单人存档）
    // ------------------------------------------------------------------

    /**
     * 单人存档与联机房间不受任何网络带宽限制（用户明确要求）。
     *
     * <p>判据用 {@code !server.isDedicatedServer()}：集成服务器就是单人存档或
     * 「对局域网开放」的房间，此时上传下载都在本机，限流只会让人困惑。</p>
     */
    private static boolean unlimited(ServerPlayer player) {
        return player.server == null || !player.server.isDedicatedServer();
    }

    /** 本次上传适用的图片上限（字节）；{@code Long.MAX_VALUE} = 不限。 */
    public static long imageLimitFor(ServerPlayer player) {
        if (unlimited(player)) return Long.MAX_VALUE;
        long v = PlanePermissions.isAdmin(player)
                ? ProjectorConfig.INSTANCE.adminMaxImageBytes.get()
                : ProjectorConfig.INSTANCE.maxImageBytes.get();
        return v <= 0 ? Long.MAX_VALUE : v;
    }

    /** 本次上传适用的视频上限（字节）；{@code Long.MAX_VALUE} = 不限。 */
    public static long videoLimitFor(ServerPlayer player) {
        if (unlimited(player)) return Long.MAX_VALUE;
        long v = PlanePermissions.isAdmin(player)
                ? ProjectorConfig.INSTANCE.adminMaxVideoBytes.get()
                : ProjectorConfig.INSTANCE.maxVideoBytes.get();
        return v <= 0 ? Long.MAX_VALUE : v;
    }

    /**
     * 【②】「超大视频直接阻断」判定。
     *
     * @return 被阻断时返回提示文本，放行返回 null
     */
    @Nullable
    public static String blockReasonForVideo(ServerPlayer player, long size) {
        if (unlimited(player)) return null;
        boolean enabled;
        long block;
        try {
            enabled = ProjectorConfig.INSTANCE.blockOversizeVideo.get();
            block = ProjectorConfig.INSTANCE.blockVideoBytes.get();
        } catch (Throwable t) {
            return null;
        }
        if (!enabled || block <= 0) return null;
        if (size > block) {
            return "视频超过硬性上限 " + (block / 1024 / 1024) + " MB，任何人不允许上传";
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 【①②⑫】环境清单下发
    // ------------------------------------------------------------------

    /** 单个 ServerInfo 包最多携带多少条媒体记录（避免超过自定义包 1 MiB 上限）。 */
    private static final int INFO_MEDIA_PER_PACKET = 512;

    /**
     * 把客户端决定「能不能用 / 要不要提前下载」所需的全部信息发过去。
     *
     * <p>这正是用户 ② 里那句「服务端先向客户端发送哈希值，在客户端完成校验」：
     * 客户端拿到媒体清单后先在本地 cache 里比对 SHA-1，命中就直接用本地缓存、
     * 完全不产生任何服务端出站流量；只有确实没有的才会来请求下载。</p>
     */
    public static void sendServerInfo(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;

        CompoundTag flags = new CompoundTag();
        flags.putBoolean("allowOriginal", ProjectorConfig.INSTANCE.allowOriginalVideoUpload.get());
        flags.putBoolean("allowBgVideoCache", ProjectorConfig.INSTANCE.allowBackgroundVideoCache.get());
        flags.putBoolean("verifyFonts", ProjectorConfig.INSTANCE.verifyFontHashes.get());
        flags.putLong("playerMaxImage", ProjectorConfig.INSTANCE.maxImageBytes.get());
        flags.putLong("playerMaxVideo", ProjectorConfig.INSTANCE.maxVideoBytes.get());
        flags.putLong("adminMaxImage", ProjectorConfig.INSTANCE.adminMaxImageBytes.get());
        flags.putLong("adminMaxVideo", ProjectorConfig.INSTANCE.adminMaxVideoBytes.get());
        flags.putLong("autoCompressVideo", ProjectorConfig.INSTANCE.autoCompressVideoBytes.get());
        flags.putLong("blockVideo", ProjectorConfig.INSTANCE.blockVideoBytes.get());
        flags.putBoolean("blockVideoOn", ProjectorConfig.INSTANCE.blockOversizeVideo.get());
        flags.putBoolean("unlimited", !server.isDedicatedServer());
        flags.putBoolean("onlyAdminUpload", ProjectorConfig.INSTANCE.onlyAdminCanUpload.get());
        OutboundMeter meter = OutboundMeter.get(server);
        flags.putBoolean("quotaExhausted", meter.exhausted());
        flags.putLong("dailyLimit", OutboundMeter.limit());
        flags.putLong("dailyUsed", meter.todayBytes());
        flags.putBoolean("admin", PlanePermissions.isAdmin(player));

        // ---- 字体清单 ----
        ListTag fonts = new ListTag();
        var fontMap = ServerFonts.scan(server);
        for (ServerFonts.Info info : fontMap.values()) {
            CompoundTag f = new CompoundTag();
            f.putString("id", info.fontId());
            f.putString("n", info.fileName());
            f.putString("sha1", info.sha1());
            f.putLong("s", info.size());
            fonts.add(f);
        }

        // ---- 媒体清单（分片）----
        MediaStore store = MediaStore.get(server);
        List<CompoundTag> mediaTags = new ArrayList<>();
        for (Map.Entry<String, MediaStore.Entry> e : store.allEntries().entrySet()) {
            CompoundTag m = new CompoundTag();
            m.putString("h", e.getKey());
            m.putLong("s", e.getValue().size);
            m.putBoolean("v", e.getValue().video);
            mediaTags.add(m);
        }
        int parts = Math.max(1, (mediaTags.size() + INFO_MEDIA_PER_PACKET - 1) / INFO_MEDIA_PER_PACKET);
        for (int part = 0; part < parts; part++) {
            ListTag slice = new ListTag();
            int from = part * INFO_MEDIA_PER_PACKET;
            int to = Math.min(mediaTags.size(), from + INFO_MEDIA_PER_PACKET);
            for (int i = from; i < to; i++) {
                slice.add(mediaTags.get(i));
            }
            CompoundTag tag = new CompoundTag();
            tag.put("media", slice);
            tag.putInt("part", part);
            tag.putInt("parts", parts);
            if (part == 0) {
                tag.put("fonts", fonts);
                tag.put("flags", flags);
            }
            PacketDistributor.sendToPlayer(player,
                    new Payloads.ServerInfo(tag, part, part == parts - 1));
        }
        Projector.LOGGER.info("[Projector] 已向 {} 下发环境清单：{} 条媒体 / {} 个字体 / {} 个分片",
                player.getGameProfile().getName(), mediaTags.size(), fonts.size(), parts);
    }

    // ------------------------------------------------------------------
    // 自定义字体上传
    // ------------------------------------------------------------------

    public static void onFontUpload(Payloads.FontUpload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (!PlanePermissions.canAddMedia(player)) {
                player.displayClientMessage(Component.translatable("projector.msg.only_op4"), true);
                TransferLog.uploadFail(player, "权限不足（字体上传同媒体上传，需要权限等级 4）",
                        payload.fontId(), payload.fileName(),
                        payload.data() == null ? 0 : payload.data().length, 0);
                return;
            }
            MinecraftServer server = player.getServer();
            if (server == null) return;
            TransferLog.uploadStart(player, payload.fontId(), payload.fileName(),
                    payload.data() == null ? 0 : payload.data().length, false, 0, 0);
            // 【①】落盘 + 回读重新核验哈希（ServerFonts.store 内部完成）
            ServerFonts.Info info = ServerFonts.store(server, payload.fontId(),
                    payload.fileName(), payload.data(), null);
            if (info == null) {
                player.displayClientMessage(Component.translatable("projector.msg.font_failed"), true);
                TransferLog.uploadFail(player, "字体保存或回读核验失败", payload.fontId(),
                        payload.fileName(), payload.data() == null ? 0 : payload.data().length, 0);
                PacketDistributor.sendToPlayer(player, new Payloads.FontStored(
                        payload.fontId(), payload.fileName(), "", false, "保存或核验失败"));
                return;
            }
            TransferLog.uploadDone(player, info.sha1(), info.fileName(), info.size(), 1, 0, true, 0);
            player.displayClientMessage(Component.translatable("projector.msg.font_ok",
                    info.fileName(), info.sha1().substring(0, 8)), false);
            PacketDistributor.sendToPlayer(player, new Payloads.FontStored(
                    info.fontId(), info.fileName(), info.sha1(), true, "ok"));
            // 字体集合变了：给所有人重发一次清单，其他客户端立刻就能用上
            for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                sendServerInfo(sp);
            }
        });
    }

    // ------------------------------------------------------------------
    // 特殊控件即时操作
    // ------------------------------------------------------------------

    public static void onWidgetAction(Payloads.WidgetAction payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            noteOp("widgetAction:" + payload.action());
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            ServerLevel level = player.serverLevel();
            ProjectorData data = ProjectorData.get(level);
            Plane plane = data.byId(payload.planeId());
            if (plane == null) return;
            if (!plane.dimension.equals(level.dimension().location())) return;
            if (!PlanePermissions.canEditContent(plane, player)) {
                player.displayClientMessage(Component.translatable("projector.msg.content_protected"), true);
                // 【rc-78】同 onPlaneEdit：拒绝之后必须让发起者回滚
                resyncTo(player, level, plane);
                return;
            }
            Widget w = plane.widgetById(payload.widgetId());
            long now = level.getGameTime();

            // 【⑧】计时器：自己的动作集
            if (w instanceof top.hmjmfabc.projector.common.widget.TimerWidget timer) {
                CompoundTag args = payload.args() == null ? new CompoundTag() : payload.args();
                switch (payload.action()) {
                    case "restart" -> timer.restart(now);
                    case "pause" -> timer.pause(now);
                    case "resume" -> timer.resume(now);
                    case "reset" -> {
                        timer.accumulatedTicks = 0;
                        timer.startGameTime = now;
                        timer.running = false;
                        timer.commandFired = false;
                    }
                    case "setRemaining" -> timer.setRemainingSeconds(args.getDouble("sec"), now);
                    case "set" -> {
                        if (args.contains("duration")) {
                            timer.durationSeconds = Sanitize.clamp(
                                    args.getDouble("duration"), 0, 2_147_483_647.0, 60);
                            timer.commandFired = false;
                            // 时长变小后剩余时间可能超出新上限，夹一下保证读数合理
                            timer.setRemainingSeconds(timer.remainingSeconds(now), now);
                        }
                        if (args.contains("command")) {
                            // 【安全】绑定指令是管理员能力；这里当场再校验一次，
                            // 绝不能只靠客户端把输入框藏起来。
                            if (!PlanePermissions.isAdmin(player)) {
                                player.displayClientMessage(
                                        Component.translatable("projector.msg.only_admin"), true);
                            } else {
                                String cmd = args.getString("command");
                                if (cmd.startsWith("/")) cmd = cmd.substring(1);
                                timer.command = cmd.length() > 256 ? cmd.substring(0, 256) : cmd;
                            }
                        }
                    }
                    default -> {
                        return;
                    }
                }
                data.markDirty();
                broadcastPlane(level, plane);
                return;
            }

            // 【27.1.1】音乐控件：世界里点播放键 → 服务端翻转状态并广播。
        // 状态放在控件数据里（playing + startedGameTime），所以所有客户端都能算出
        // 同一个播放位置，晚进来的玩家也能对上进度。
        if (w instanceof top.hmjmfabc.projector.common.widget.MusicWidget music) {
            switch (payload.action()) {
                case "toggle" -> music.toggle(now);
                case "seek" -> {
                    // 按比例跳进度：位置写进控件数据（服务端权威），播放中则重锚时间点
                    double fraction = Sanitize.clamp(
                            payload.args() == null ? 0 : payload.args().getDouble("fraction"), 0, 1, 0);
                    music.positionMs = (long) (fraction * music.effectiveDurationMs());
                    if (music.playing) {
                        music.startedGameTime = now;
                    }
                }
                case "stop" -> {
                    music.positionMs = 0L;
                    music.playing = false;
                }
                case "pause" -> {
                    if (music.playing) {
                        music.toggle(now);
                    }
                }
                case "resume" -> {
                    if (!music.playing && music.hasTrack()) {
                        music.toggle(now);
                    }
                }
                default -> {
                    // 不静默：动作名写错要能查出来（现有两个 default 都是静默 return，
                    // 这里不再新增同类问题）
                    Projector.LOGGER.warn("[Projector] 音乐控件收到未知动作：{}（平面 {} 控件 {}）",
                            payload.action(), plane.id, payload.widgetId());
                    return;
                }
            }
            data.markDirty();
            broadcastPlane(level, plane);
            Projector.LOGGER.info("[Projector][音乐] 服务端翻转播放状态 平面={} 控件={} 现在是{}（位置 {}ms）",
                    plane.id, music.id, music.playing ? "播放中" : "已暂停", music.positionMs);
            return;
        }

        if (!(w instanceof ProgressWidget progress)) return;
            switch (payload.action()) {
                case "reset" -> progress.reset(now);
                case "pause" -> progress.pause(now);
                case "resume" -> progress.resume(now);
                case "set" -> {
                    CompoundTag args = payload.args();
                    if (args != null) {
                        if (args.contains("duration")) {
                            progress.durationSeconds = Sanitize.clamp(
                                    args.getDouble("duration"), 0, 86_400, 60);
                        }
                        if (args.contains("manual")) {
                            progress.manualProgress = Math.max(0, Math.min(1, args.getDouble("manual")));
                            progress.accumulatedTicks = 0;
                            progress.startGameTime = now;
                        }
                        if (args.contains("title")) {
                            progress.title = args.getString("title");
                        }
                        if (args.getBoolean("restart")) {
                            progress.accumulatedTicks = 0;
                            progress.startGameTime = now;
                            progress.running = progress.durationSeconds > 0.01;
                        }
                    }
                }
                default -> {
                    return;
                }
            }
            data.markDirty();
            broadcastPlane(level, plane);
        });
    }

    // ------------------------------------------------------------------
    // ⑧ 计时器：倒计时归零时触发绑定指令
    // ------------------------------------------------------------------

    /**
     * 每秒扫一次所有平面的计时器控件：倒计时归零且绑定了指令、且还没触发过的，
     * 由服务端执行那条指令。
     *
     * <p>【安全边界】只允许「服务端自己注册过的指令」——不存在的指令直接丢弃并打日志，
     * 避免玩家把任意文本塞进指令字段然后看服务端反复报错刷屏。绑定入口本身
     * （{@code onWidgetAction} 的 {@code set}、以及 {@code isAdmin}）也已经限死为管理员。</p>
     */
    public static void tickTimers(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            ProjectorData data = ProjectorData.get(level);
            boolean dirty = false;
            for (Plane plane : data.planesIn(level.dimension().location())) {
                boolean planeChanged = false;
                for (Widget w : plane.widgets) {
                    if (!(w instanceof top.hmjmfabc.projector.common.widget.TimerWidget timer)) continue;
                    if (timer.command == null || timer.command.isEmpty() || timer.commandFired) continue;
                    if (!timer.finished(level.getGameTime())) continue;
                    timer.commandFired = true;
                    planeChanged = true;
                    try {
                        String cmd = timer.command.startsWith("/")
                                ? timer.command.substring(1) : timer.command;
                        // 不存在的指令直接拒绝（并留下日志），绝不做任何文本拼接执行。
                        // 判据用「根节点有没有这个名字的子节点」，也就是服务端自己注册过的指令。
                        String head = cmd.trim().split("\\s+", 2)[0];
                        if (head.isEmpty()
                                || server.getCommands().getDispatcher().getRoot().getChild(head) == null) {
                            Projector.LOGGER.warn("[Projector] 计时器绑定的指令不存在，已忽略：{}", cmd);
                            continue;
                        }
                        server.getCommands().performPrefixedCommand(
                                server.createCommandSourceStack().withSuppressedOutput(), cmd);
                        Projector.LOGGER.info("[Projector] 计时器归零，已执行绑定指令：{}（平面 {}）",
                                cmd, plane.id.toString().substring(0, 8));
                    } catch (Throwable t) {
                        Projector.LOGGER.warn("[Projector] 计时器指令执行失败：{}（{}）", timer.command, t.toString());
                    }
                }
                if (planeChanged) {
                    data.markDirty();
                    broadcastPlane(level, plane);
                    dirty = true;
                }
            }
            if (dirty) {
                // markDirty 已在上面按平面调用；这里只是保持可读性
                data.markDirty();
            }
        }
    }

    // ------------------------------------------------------------------
    // 玩家登录时同步
    // ------------------------------------------------------------------

    public static void syncAllTo(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        ProjectorData data = ProjectorData.get(level);
        List<Plane> planes = data.planesIn(level.dimension().location());
        if (planes.isEmpty()) {
            CompoundTag empty = new CompoundTag();
            empty.put("planes", new ListTag());
            empty.putBoolean("replace", true);
            PacketDistributor.sendToPlayer(player, new Payloads.SyncPlanes(level.dimension().location(), empty));
            return;
        }
        // 分批发送：单个自定义包的体积上限是 1 MiB，装不下上百个平面，
        // 所以既按个数切分，也按「估算字节数」切分（NBT 未压缩大小 ≈ toString 长度）。
        ListTag list = new ListTag();
        int bytes = 0;
        boolean first = true;
        for (Plane plane : planes) {
            CompoundTag saved;
            try {
                saved = plane.save();
            } catch (Throwable t) {
                Projector.LOGGER.warn("[Projector] 序列化平面 {} 失败，已跳过: {}", plane.id, t.toString());
                continue;
            }
            int size = saved.toString().length();
            if (!list.isEmpty() && (list.size() >= SYNC_PLANES_PER_PACKET || bytes + size > SYNC_BYTES_BUDGET)) {
                sendPlaneBatch(player, level, list, first);
                first = false;
                list = new ListTag();
                bytes = 0;
            }
            list.add(saved);
            bytes += size;
        }
        if (!list.isEmpty()) {
            sendPlaneBatch(player, level, list, first);
        }
    }

    private static void sendPlaneBatch(ServerPlayer player, ServerLevel level, ListTag list, boolean replace) {
        CompoundTag tag = new CompoundTag();
        tag.put("planes", list);
        tag.putBoolean("replace", replace);
        PacketDistributor.sendToPlayer(player, new Payloads.SyncPlanes(level.dimension().location(), tag));
    }

    /** 清理某个玩家残留的上传会话（登出时调用）。 */
    public static void clearSessions(UUID player) {
        UPLOADS.remove(player);
    }

    /**
     * 定期清理过期上传会话。
     *
     * <p>一个视频上传会话会预先分配与文件等大的缓冲（默认上限 24 MB）。
     * 如果只在「收到下一个包」时才检查过期，玩家发一个 head 包就退出上传流程的话，
     * 这块内存会一直保留到登出。因此由服务端 tick 定期扫一遍。</p>
     */
    public static void sweepExpiredUploads() {
        if (UPLOADS.isEmpty()) return;
        UPLOADS.entrySet().removeIf(e -> e.getValue().expired());
    }
}

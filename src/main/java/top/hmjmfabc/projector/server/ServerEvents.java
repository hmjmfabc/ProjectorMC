package top.hmjmfabc.projector.server;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import top.hmjmfabc.projector.Projector;
import top.hmjmfabc.projector.common.Plane;
import top.hmjmfabc.projector.network.ServerNetHandler;

import java.util.List;

/**
 * 服务端事件：平面保护（阻止破坏方块）、删除平面、登录同步。
 */
public final class ServerEvents {

    /** 破坏方块时如果被取消，记录位置，用于「删除平面」判定。 */
    public static final String TAG_DELETE_PLANE = "projector.delete";

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Player player = event.getPlayer();
        if (player == null) return;
        ResourceLocation dim = level.dimension().location();
        ProjectorData data = ProjectorData.get(level);
        List<Plane> planes = data.planesAt(dim, event.getPos());
        if (planes.isEmpty()) return;

        for (Plane plane : planes) {
            if (plane.protectBlocks) {
                // 平面保护：任何人都不能破坏
                event.setCanceled(true);
                if (player instanceof ServerPlayer sp) {
                    sp.displayClientMessage(Component.translatable("projector.msg.block_protected",
                            plane.displayName()), true);
                }
                return;
            }
            if (plane.miningWarning) {
                // 平面误挖掘警告：必须同时按住 Shift
                if (!player.isShiftKeyDown()) {
                    event.setCanceled(true);
                    if (player instanceof ServerPlayer sp) {
                        sp.displayClientMessage(Component.translatable("projector.msg.mining_warning"), true);
                    }
                    return;
                }
                // Shift + 左键 = 删除该平面。
                // 注意：这条路径同样必须过权限判定，否则任何玩家都能删掉别人的平面。
                if (player instanceof ServerPlayer sp
                        && !PlanePermissions.canDelete(plane, sp)) {
                    event.setCanceled(true);
                    sp.displayClientMessage(Component.translatable("projector.msg.no_permission_delete"), true);
                    return;
                }
                Projector.LOGGER.info("[Projector] {} 通过挖掘方块删除了平面 {}",
                        player.getName().getString(), plane.id);
                data.remove(plane.dimension, plane);
                ServerNetHandler.broadcastDeletion(level, plane);
                if (player instanceof ServerPlayer sp) {
                    sp.displayClientMessage(Component.translatable("projector.msg.plane_deleted",
                            plane.displayName()), false);
                }
                return;
            }
        }

        // 正常的方块破坏：把该方块从平面结构中移除，避免平面数据与世界长期不一致
        boolean changed = false;
        boolean structural = false;
        for (Plane plane : planes) {
            if (plane.blocks.remove(event.getPos().asLong()) != null) {
                changed = true;
                data.markDirty();
                if (plane.widgets.isEmpty() || plane.blocks.isEmpty()) {
                    structural = true;
                }
            }
        }
        if (changed) {
            data.rebuildIndex(dim);
            for (Plane plane : planes) {
                if (plane.blocks.isEmpty()) {
                    // 组成平面只剩零个方块：直接移除该平面
                    data.remove(plane.dimension, plane);
                    ServerNetHandler.broadcastDeletion(level, plane);
                } else {
                    ServerNetHandler.broadcastPlane(level, plane);
                }
            }
        }
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            ServerNetHandler.syncAllTo(sp);
            // 【①②⑫】紧接着下发环境清单（媒体哈希表 + 字体清单 + 各种开关）。
            // 客户端收到后就能：① 核验字体能不能用；② 先比对本地缓存再决定要不要下载；
            // ③ 按自己的缓存档位开始后台预缓存。
            try {
                ServerNetHandler.sendServerInfo(sp);
            } catch (Throwable t) {
                Projector.LOGGER.warn("[Projector] 下发环境清单失败：{}", t.toString());
            }
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            ServerNetHandler.clearSessions(sp.getUUID());
            OutboundMeter.forget(sp.getUUID());
            // 【②】把这个人还没结算的下发明细落成一行「合计」，否则日志里会缺半截
            TransferLog.flush(sp);
        }
    }

    @SubscribeEvent
    public void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            ServerNetHandler.syncAllTo(sp);
        }
    }

    /** 每秒清理一次过期的媒体上传会话（避免长时间占用几十 MB 内存）。 */
    @SubscribeEvent
    public void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        // 【rc-81】每个刻结算被合并掉的平面广播（同一平面 100ms 最多真发一次）
        ServerNetHandler.flushPendingBroadcasts();
        if (event.getServer().getTickCount() % 20 == 0) {
            ServerNetHandler.sweepExpiredUploads();
            // 【⑧】计时器倒计时归零 -> 触发绑定指令（每秒扫一次，最多晚 1 秒）
            try {
                ServerNetHandler.tickTimers(event.getServer());
            } catch (Throwable t) {
                // 计时器出错绝不能拖垮服务端主循环
                Projector.LOGGER.warn("[Projector] 计时器扫描异常：{}", t.toString());
            }
        }
    }


    /**
     * 自动重建「画布轴系已过时」的旧平面。
     *
     * <p>Build 49 修正了贴面轴向（DOWN / WEST / EAST 三个朝向原本是左手系，
     * 会导致内容镜像 + 沿该轴偏一格）。但那之前存下来的平面，画布坐标、
     * canvasOffset、每块方块的矩形都是按旧轴算好的——<b>无法自动换算</b>，
     * 只能拿当前世界重新圈一次。</p>
     *
     * <p>以前这件事要玩家自己去点「重新圈选此平面」，结果就是旧平面永远带着偏移，
     * 玩家会以为 bug 没修。现在服务端启动时自动做掉，玩家不需要任何操作。</p>
     */
    private static void migrateLegacyPlanes(net.minecraft.server.MinecraftServer server, ProjectorData data) {
        int migrated = 0;
        for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
            for (Plane plane : data.planesIn(level.dimension().location())) {
                if (!plane.needsRebuild) continue;
                try {
                    var hit = ServerNetHandler.resolveHit(level, plane.anchor,
                            new top.hmjmfabc.projector.network.Payloads.CreatePlane(
                                    plane.dimension, plane.anchor.asLong(), plane.face.getName(),
                                    plane.anchor.getX() + 0.5, plane.anchor.getY() + 0.5,
                                    plane.anchor.getZ() + 0.5));
                    if (hit == null) continue;
                    Plane fresh = Plane.build(level, plane.dimension, hit,
                            top.hmjmfabc.projector.ProjectorConfig.INSTANCE.maxPlaneBlocks.get());
                    plane.applyFrom(fresh);
                    plane.needsRebuild = false;
                    migrated++;
                } catch (Throwable t) {
                    Projector.LOGGER.warn("[Projector] 自动重建平面 {} 失败：{}",
                            plane.id.toString().substring(0, 8), t.toString());
                }
            }
        }
        if (migrated > 0) {
            data.setDirty();
            Projector.LOGGER.info("[Projector] 已自动重建 {} 个旧版平面（贴面轴向修正）", migrated);
        }
    }

    /**
     * 【rc-84】把「比平面还大的棋类控件」收进平面。
     *
     * <p>棋盘撑框是按「每格至少 {@code CELL_UNITS} 画布单位」算的（象棋 7.3x8.1 格、
     * 五子棋 12.2x12.2 格），玩家把小棋盘放在小平面上时，超出去的那部分<b>没有可点击的面</b>：
     * 右键打不到，那半边的子就「动不了、也吃不到」——玩家报的
     * 「兵越不过楚河汉界，炮吃不到相隔的子」正是这个形状（他不是在说规则错，
     * 而是那半块棋盘根本点不到）。</p>
     *
     * <p>服务端启动时自动收一次，玩家不需要手动重做控件。</p>
     */
    private static void fitChessWidgetsIntoPlanes(ProjectorData data, net.minecraft.server.MinecraftServer server) {
        int fixed = 0;
        for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
            for (Plane plane : data.planesIn(level.dimension().location())) {
                if (plane.width <= 0 || plane.height <= 0) continue;
                for (top.hmjmfabc.projector.common.widget.Widget w : plane.widgets) {
                    if (!(w instanceof top.hmjmfabc.projector.common.widget.ChessWidget cw)) continue;
                    if (top.hmjmfabc.projector.common.widget.ChessWidget.fitIntoPlane(
                            cw, plane.width, plane.height)) {
                        fixed++;
                        Projector.LOGGER.info("[Projector] 棋盘控件 {} 已缩小以完整落在平面内"
                                        + "（平面 {}x{} 单位；超出的点位右键打不到）",
                                top.hmjmfabc.projector.common.game.GameKind.name(cw.game.kind),
                                plane.width, plane.height);
                    }
                }
            }
        }
        if (fixed > 0) {
            data.setDirty();
            Projector.LOGGER.info("[Projector] 已把 {} 个棋类控件收进平面（旧存档自动修正）", fixed);
        }
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        ProjectorData data = ProjectorData.get(event.getServer());
        // 客户端与服务端可能共用同一个 JVM（单人游戏），切换存档时清掉上一次的运行时索引
        data.rebuildAll();
        migrateLegacyPlanes(event.getServer(), data);
        fitChessWidgetsIntoPlanes(data, event.getServer());
        // 【rc-82】把**生效的**日出站上限打出来：配置里被旧版夹过的值（2048MB）与需求（10GB）
        // 只差一个数字，不打出来根本看不出（实测玩家以为自己是 10GB，实际生效 2GB）。
        long dailyLimit = OutboundMeter.limit();
        Projector.LOGGER.info("[Projector] 服务端出站日上限={}（0 = 不限；由 media.dailyOutboundLimitBytes 决定）",
                dailyLimit <= 0 ? "不限" : (dailyLimit / 1048576L) + " MB");
        Projector.LOGGER.info("[Projector] 服务端就绪，已载入平面数据（主世界 {} 个）",
                data.countIn(net.minecraft.world.level.Level.OVERWORLD.location()));
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        Projector.LOGGER.info("[Projector] 服务端停止，保存平面数据");
        ServerNetHandler.resetBroadcastState();
        // 【②】结算所有在途的下发明细，保证日志完整
        TransferLog.flushAll();
    }
}

package top.hmjmfabc.projector.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import top.hmjmfabc.projector.ProjectorConfig;
import top.hmjmfabc.projector.client.gui.WidgetEditorScreen;
import top.hmjmfabc.projector.client.gui.PlaneDialogScreen;
import top.hmjmfabc.projector.common.Plane;
import top.hmjmfabc.projector.common.TargetPicker;
import top.hmjmfabc.projector.common.widget.Widget;
import top.hmjmfabc.projector.network.Payloads;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端输入处理：按键 <b>U</b> / <b>I</b> / <b>O</b> 与右键交互。
 *
 * <p>这里刻意不依赖任何鼠标专属 API：全部走 {@code KeyMapping} 与
 * {@code InteractionKeyMappingTriggered} 事件，因此在 Android 触控启动器上
 * 只要映射了对应按键就能正常使用。</p>
 */
public final class ClientInputHandler {

    public ClientInputHandler() {
    }

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        // 【斗蛐蛐自走的节拍器】必须放在下面「界面打开就 return」之前：
        // 对局界面开着的时候也要继续走棋。见 ChessAiDriver.pump()。
        ChessAiDriver.pump();
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (mc.screen != null) {
            return;
        }
        handleKey(mc, ProjectorKeys.KEY_CAPTURE);
        handleKey(mc, ProjectorKeys.KEY_CANCEL);
        handleKey(mc, ProjectorKeys.KEY_MEDIA_HELP);
    }

    /**
     * Esc 取消圈选。
     *
     * <p>Esc 在原版里是「打开暂停菜单」，而 {@code KeyMapping} 的 IN_GAME 冲突上下文
     * 在界面打开后就不再生效，`consumeClick()` 也拦不住暂停菜单。
     * 正确的钩子是 {@code ScreenEvent.Opening}（可取消）：</p>
     * <ul>
     *   <li>只有「已选中平面」或「正在等待圈选结果」时才接管——此时按 Esc
     *       <b>不打开暂停菜单</b>，改为取消圈选；</li>
     *   <li>其它任何时候一律放行，绝不影响玩家正常按 Esc 开菜单。</li>
     * </ul>
     */
    @SubscribeEvent
    public void onScreenOpening(net.neoforged.neoforge.client.event.ScreenEvent.Opening event) {
        if (!(event.getNewScreen() instanceof net.minecraft.client.gui.screens.PauseScreen)) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.player == null || mc.level == null) return;
        if (SelectionState.plane() == null && !SelectionState.isPendingCapture()) return;
        event.setCanceled(true);
        onCancel(mc);
    }

    private void handleKey(Minecraft mc, net.minecraft.client.KeyMapping key) {
        while (key.consumeClick()) {
            if (key == ProjectorKeys.KEY_CAPTURE) {
                onCapture(mc);
            } else if (key == ProjectorKeys.KEY_CANCEL) {
                onCancel(mc);
            } else if (key == ProjectorKeys.KEY_MEDIA_HELP) {
                onMediaHelp(mc);
            }
        }
    }

    // ------------------------------------------------------------------
    // U：圈选 / 打开对话框
    // ------------------------------------------------------------------

    private void onCapture(Minecraft mc) {
        Plane current = SelectionState.plane();
        if (current != null) {
            mc.setScreen(new PlaneDialogScreen(current));
            return;
        }
        if (mc.player == null || mc.level == null || mc.hitResult == null) {
            return;
        }
        TargetPicker.FaceHit hit = raycast(mc);
        if (hit == null) {
            mc.player.displayClientMessage(Component.translatable("projector.msg.no_target"), true);
            return;
        }
        SelectionState.rememberPick(hit.pos(), hit.face(), hit.hit());
        SelectionState.markPendingCapture();
        // 本地先记住一个「待确认」的平面，等服务端同步回来
        PacketDistributor.sendToServer(new Payloads.CreatePlane(
                mc.level.dimension().location(),
                hit.pos().asLong(),
                hit.face().getName(),
                hit.hit().x, hit.hit().y, hit.hit().z));
    }

    @Nullable
    public static TargetPicker.FaceHit raycast(Minecraft mc) {
        if (mc.player == null || mc.level == null) return null;
        LocalPlayer player = mc.player;
        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 dir = player.getViewVector(1.0f);
        double dist = ProjectorConfig.INSTANCE.selectDistance.get();
        return TargetPicker.pick(mc.level, eye, dir, dist, CollisionContext.empty());
    }

    // ------------------------------------------------------------------
    // I：取消圈选
    // ------------------------------------------------------------------

    private void onCancel(Minecraft mc) {
        if (SelectionState.hasSelection()) {
            SelectionState.clear();
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.translatable("projector.msg.selection_cleared"), true);
            }
        }
    }

    // ------------------------------------------------------------------
    // O：媒体目录提示
    // ------------------------------------------------------------------

    private void onMediaHelp(Minecraft mc) {
        if (mc.player == null) return;
        mc.player.displayClientMessage(Component.translatable("projector.msg.media_dir",
                top.hmjmfabc.projector.client.media.LocalMedia.mediaDir().toString()), false);
        mc.player.displayClientMessage(Component.translatable("projector.msg.font_dir",
                top.hmjmfabc.projector.client.media.LocalMedia.fontDir().toString()), false);
    }

    /**
     * 【hotfix-98】视频控件的世界内小控件：点左下角叫出播放键，点播放键启停，
     * 点进度条跳进度；5 秒无操作自动隐藏（见 {@code client.media.VideoControls}）。
     *
     * <p>几何一律取自 {@code VideoWidget}（与渲染同源），并且<b>不需要先按 U 选中平面</b>
     * —— 与音乐控件的世界内播放键保持一致。</p>
     *
     * @return true 表示这次点击已经被吃掉（调用方直接 return）
     */
    private static boolean handleVideoControl(Minecraft mc,
                                              InputEvent.InteractionKeyMappingTriggered event) {
        WidgetPick pick = pickWidget(mc, null);
        if (pick == null
                || !(pick.widget() instanceof top.hmjmfabc.projector.common.widget.VideoWidget video)) {
            return false;
        }
        Plane plane = pick.plane();
        Vec3 eye = mc.player.getEyePosition(1.0f);
        Vec3 dir = mc.player.getViewVector(1.0f);
        Double t = intersectPlane(plane, eye, dir, ProjectorConfig.INSTANCE.selectDistance.get());
        if (t == null) {
            return false;
        }
        Vec3 hit = eye.add(dir.scale(t));
        double cx = plane.canvasX(hit);
        double cy = plane.canvasY(hit);
        long now = System.currentTimeMillis();
        boolean visible = top.hmjmfabc.projector.client.media.VideoControls.visible(video.id, now);

        if (visible && video.hitControl(cx, cy)) {
            top.hmjmfabc.projector.client.media.VideoControls.toggleInWorld(plane, video);
            event.setCanceled(true);
            event.setSwingHand(false);
            return true;
        }
        if (visible) {
            double fraction = video.seekFractionAt(cx, cy);
            if (fraction >= 0) {
                top.hmjmfabc.projector.client.media.VideoControls.seekInWorld(plane, video, fraction);
                event.setCanceled(true);
                event.setSwingHand(false);
                return true;
            }
        }
        if (video.hitHotZone(cx, cy)) {
            // 只叫出控件，不做别的：避免「想调出来却误触暂停」
            top.hmjmfabc.projector.client.media.VideoControls.reveal(video.id, now);
            event.setCanceled(true);
            event.setSwingHand(false);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 右键：编辑控件
    // ------------------------------------------------------------------

    /** 一次控件拾取的结果：控件 + 它真正所属的平面。 */
    public record WidgetPick(Plane plane, Widget widget) {
    }

    @SubscribeEvent
    public void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        // 【hotfix-98】以前只认「使用物品」（右键）。Android 触屏上「点一下」多数映射成
        // 攻击键（左键），所以视频控件的小播放键在手机上根本点不到。现在左右键都收，
        // 但只对「视频控件的左下角那一块」生效，其余情况照旧放行给原版。
        if (!event.isUseItem() && !event.isAttack()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null) return;
        if (handleVideoControl(mc, event)) {
            return;
        }
        if (!event.isUseItem()) return;
        // 【用户要求 ⑥.2】未选中任何平面时，右键**不能**打开控件编辑页面。
        // 以前这里是无条件拾取（先看选中平面、再看本维度其它平面），于是玩家
        // 随手对着墙右键就会弹出编辑器，非常碍事。
        // 现在必须满足两条：① 已经选中了一个平面；② 控件就属于那个平面。
        Plane selected = SelectionState.plane();
        // 【⑥.2 的例外】没有选中平面时，**只**允许「右键棋盘直接下棋」这一种情况。
        // 用户明确要求棋类游戏要能在游戏内对局——而「必须先按 U 选中平面」这一步
        // 对下棋来说太绕了。其它控件仍然遵守 ⑥.2：没选平面就一律不弹编辑器。
        Widget w;
        Plane owner;
        if (selected == null) {
            WidgetPick pick = pickWidget(mc, null);
            if (pick == null) {
                return;
            }
            // 【27.1.1 音乐控件】又一条例外：没有选中平面时，**点在播放键上**就启停音乐。
            // 用户要求「世界内点击播放键可以启停音乐（无需选中平面）」——
            // 但只认播放键那一小块，点控件别处仍然什么都不做（保持 ⑥.2 的克制）。
            if (pick.widget() instanceof top.hmjmfabc.projector.common.widget.MusicWidget music) {
                double[] hit = musicHit(mc, pick.plane(), music);
                if (hit == null) {
                    return;
                }
                event.setCanceled(true);
                event.setSwingHand(false);
                if (hit[0] >= 0) {
                    // 点在波形条上 = 调进度（点了就跳，不用拖）
                    top.hmjmfabc.projector.client.music.MusicManager.seekInWorld(
                            pick.plane(), music, hit[0]);
                } else {
                    top.hmjmfabc.projector.client.music.MusicManager.toggleInWorld(pick.plane(), music);
                }
                return;
            }
            if (!(pick.widget() instanceof top.hmjmfabc.projector.common.widget.ChessWidget)) {
                return;
            }
            w = pick.widget();
            owner = pick.plane();
        } else {
            w = pickWidgetOn(selected);
            owner = selected;
            if (w == null) return;
        }
        // 阻止方块放置 / 物品使用，改为打开对应的界面
        event.setCanceled(true);
        event.setSwingHand(false);
        // 【27.1.1 音乐控件】选中平面时，点在播放键上同样是「启停音乐」而不是开编辑器
        //（否则想暂停音乐得先右键打开编辑器，太绕）。
        if (w instanceof top.hmjmfabc.projector.common.widget.MusicWidget music) {
            double[] hit = musicHit(mc, owner, music);
            if (hit != null) {
                event.setCanceled(true);
                event.setSwingHand(false);
                if (hit[0] >= 0) {
                    top.hmjmfabc.projector.client.music.MusicManager.seekInWorld(owner, music, hit[0]);
                } else {
                    top.hmjmfabc.projector.client.music.MusicManager.toggleInWorld(owner, music);
                }
                return;
            }
            // 既不在播放键、也不在波形条上 ⇒ 继续走「打开编辑器」
        }
        // 【⑪】棋盘控件：右键**直接开始对局**，而不是先弹一堆设置。
        // 用户反馈「棋类游戏不能在游戏内进行对局」——以前必须先右键 →
        // 打开控件编辑器 → 再找「打开对局界面…」三步，太深了。
        // 想改棋盘外观的话，对局界面里有「控件设置…」按钮。
        if (w instanceof top.hmjmfabc.projector.common.widget.ChessWidget chess) {
            // 【⑪ 绿灯】棋类控件**不需要先选中平面**，而且**直接在游戏内下棋**：
            // 一次右键 = 点一下棋盘上的那个点位/格子。
            // 走子类棋第一下选中自己的子（会高亮）、第二下走出；
            // 落子类棋直接落子。AI 回合或点歪了才退回对局界面。
            if (tryPlayChessInWorld(mc, owner, chess)) {
                return;
            }
            mc.setScreen(new top.hmjmfabc.projector.client.gui.ChessGameScreen(
                    owner, chess, new PlaneDialogScreen(owner)));
            return;
        }
        // 注意：必须用「控件所属的平面」，而不是当前选中的平面。
        // 上面已经保证两者是同一个平面；仍然显式传 selected 是为了让语义明确。
        mc.setScreen(WidgetEditorScreen.create(owner, w));
    }

    /**
     * 这块棋盘此刻是否「还有人看得见」：同维度且玩家与锚点方块的距离在渲染距离内。
     *
     * <p>斗蛐蛐自走靠它刹车——玩家走开之后就不再自己打（平面是公共屏幕，
     * 不该在背后持续算棋、发包）。</p>
     */
    private static boolean stillWatching(Minecraft mc, Plane plane) {
        if (mc.player == null || mc.level == null || plane == null || plane.anchor == null) return false;
        if (!mc.level.dimension().location().equals(plane.dimension)) return false;
        double d = top.hmjmfabc.projector.ProjectorConfig.INSTANCE.renderDistance.get();
        return mc.player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(plane.anchor)) <= d * d;
    }

    /** 【rc-84】棋盘点击被拒的日志限流（3 秒一行，避免连点刷屏）。 */
    private static long lastChessRejectLog;

    /**
     * 【⑪】在世界里直接点棋盘。
     *
     * @return true 表示这次右键已经被消费（做出了动作或选中了棋子），不要再弹界面
     */
    private static boolean tryPlayChessInWorld(Minecraft mc, Plane plane,
                                               top.hmjmfabc.projector.common.widget.ChessWidget chess) {
        if (mc.player == null || mc.level == null) return false;
        chess.ensureBoard();
        var g = chess.game;
        // 【斗蛐蛐自走许可】AI vs AI 模式下右键一次就让两边自己打下去，
        // 但只在「还有人看得见这块棋盘」时继续 —— 玩家走开就停，
        // 免得一张没人在看的公共屏幕在背后一直烧 CPU、一直发包
        // （用户专门提醒过「小心内存泄露」）。
        java.util.function.BooleanSupplier permit =
                g.mode == top.hmjmfabc.projector.common.game.GameMode.AI_VS_AI
                        ? () -> stillWatching(mc, plane) : null;
        // 【关键修复】轮到 AI 时**不能**只是弹界面就完事——那样人机模式下
        // 走完先手局面就永久卡在后手（用户报的「后手下子会跳转到编辑界面」）。
        // 这里就地驱动 AI 走一手，玩家可以继续在世界里接着下。
        if (g.result == 0 && g.isAiTurn()) {
            if (ChessAiDriver.isThinking(chess)) {
                mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "AI \u601d\u8003\u4e2d\u2026"), true);
                return true;
            }
            if (ChessAiDriver.runOneMove(plane, chess, permit)) {
                mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "AI \u6b63\u5728\u601d\u8003\u2026"), true);
                return true;
            }
        }
        // 真的没法在世界里操作（本局已结束）才交给对局界面
        if (g.result != 0) return false;
        Vec3 eye = mc.player.getEyePosition(1.0f);
        Vec3 dir = mc.player.getViewVector(1.0f);
        double dist = ProjectorConfig.INSTANCE.selectDistance.get();
        Double t = intersectPlane(plane, eye, dir, dist);
        if (t == null) return false;
        Vec3 p = eye.add(dir.scale(t));
        // 把命中点从世界坐标换到画布坐标，再由控件自己换算成点位下标
        int[] cell = chess.cellAt(plane.canvasX(p), plane.canvasY(p));
        if (cell == null) return false;
        var click = chess.clickCell(cell[0], cell[1]);
        switch (click) {
            case PLAYED -> {
                ChessAiDriver.submit(plane, chess);
                mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        g.result != 0 ? "\u672c\u5c40\u7ed3\u675f\uff1a" + g.resultText()
                                : "\u5df2\u843d\u5b50"),
                        true);
                // 人机模式：立刻让 AI 接一手，否则玩家会觉得「点了没反应」；
                // 斗蛐蛐模式：这一次落子之后两边自己接着打（permit 见上）。
                ChessAiDriver.runOneMove(plane, chess, permit);
                return true;
            }
            case SELECTED -> {
                mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "\u5df2\u9009\u4e2d\uff0c\u518d\u53f3\u952e\u76ee\u6807\u4f4d\u7f6e"), true);
                return true;
            }
            case NEED_SELECT -> {
                mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "\u8bf7\u5148\u53f3\u952e\u4e00\u4e2a\u81ea\u5df1\u7684\u68cb\u5b50"), true);
                return true;
            }
            case ILLEGAL -> {
                // 【rc-84】"不合法"三个字不够用：告诉玩家「该点哪里」。
                // 空盘的第一步（五子棋/围棋）被规则限制在天元，棋盘上已用亮块标出；
                // 走子类棋选中后也已高亮该子的全部落点。
                String msg;
                if (chess.firstMoveRestricted()) {
                    msg = "\u7b2c\u4e00\u624b\u53ea\u80fd\u4e0b\u5728\u9ad8\u4eae\u7684\u90a3\u4e00\u70b9"
                            + "\uff08\u5929\u5143\uff0c\u68cb\u76d8\u6b63\u4e2d\u5fc3\uff09";
                } else if (chess.selCol >= 0) {
                    int n = 0;
                    for (var hm : chess.game.legalMoves()) {
                        if (hm.fx() == chess.selCol && hm.fy() == chess.selRow) n++;
                    }
                    msg = n == 0
                            ? "\u8fd9\u4e2a\u5b50\u73b0\u5728\u8d70\u4e0d\u4e86"
                            : ("\u8fd9\u4e00\u6b65\u4e0d\u5408\u6cd5\uff08\u8be5\u5b50\u53ef\u8d70 " + n
                               + " \u4e2a\u70b9\uff0c\u5df2\u5728\u68cb\u76d8\u4e0a\u9ad8\u4eae\uff09");
                } else {
                    msg = "\u8fd9\u4e00\u6b65\u4e0d\u5408\u6cd5";
                }
                mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(msg), true);
                // 诊断：被拒的点击要能在日志里对上（哪一方、选中的是哪、点的是哪、当时几个子）
                long now = System.currentTimeMillis();
                if (now - lastChessRejectLog > 3000L) {
                    lastChessRejectLog = now;
                    top.hmjmfabc.projector.Projector.LOGGER.info(
                            "[Projector] 棋盘点击被拒：{} 模式={} 轮到={} 选中=({},{}) 点击=({},{})"
                                    + " 该处棋子={} 合法着法={} 个",
                            top.hmjmfabc.projector.common.game.GameKind.name(g.kind),
                            top.hmjmfabc.projector.common.game.GameMode.name(g.mode),
                            g.turn, chess.selCol, chess.selRow, cell[0], cell[1],
                            g.at(cell[0], cell[1]), g.legalMoves().size());
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** 准心所指的控件（先看选中平面，再看本维度其它平面），同时返回它所属的平面。 */
    @Nullable
    public static WidgetPick pickWidget(Minecraft mc, @Nullable Plane preferred) {
        List<Plane> candidates = new ArrayList<>();
        if (preferred != null) candidates.add(preferred);
        if (mc.level != null) {
            for (Plane p : PlaneCache.planesIn(mc.level.dimension().location())) {
                if (!candidates.contains(p)) candidates.add(p);
            }
        }
        for (Plane p : candidates) {
            Widget w = pickWidgetOn(p);
            if (w != null) return new WidgetPick(p, w);
        }
        return null;
    }

    /**
     * 这次右键是不是落在某个音乐控件的**播放键**上。
     *
     * <p>用控件自己的 {@code hitButton}（几何唯一来源），所以判定范围与画出来的播放键
     * 永远一致；触屏指尖比较粗，那里额外放宽了半格。</p>
     */
    private static double[] musicHit(Minecraft mc, Plane plane,
                                     top.hmjmfabc.projector.common.widget.MusicWidget music) {
        if (plane == null || mc.player == null) {
            return null;
        }
        Vec3 eye = mc.player.getEyePosition(1.0f);
        Vec3 dir = mc.player.getViewVector(1.0f);
        Double t = intersectPlane(plane, eye, dir, ProjectorConfig.INSTANCE.selectDistance.get());
        if (t == null) {
            return null;
        }
        Vec3 p = eye.add(dir.scale(t));
        double cx = plane.canvasX(p);
        double cy = plane.canvasY(p);
        if (music.hitButton(cx, cy)) {
            return new double[]{-1.0};            // 播放键 → 启停
        }
        double fraction = music.seekFractionAt(cx, cy);
        if (fraction >= 0) {
            return new double[]{fraction};        // 波形条 → 调进度
        }
        return null;
    }

    /** 在单个平面上做控件拾取。 */
    @Nullable
    public static Widget pickWidgetOn(Plane plane) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return null;
        Vec3 eye = mc.player.getEyePosition(1.0f);
        Vec3 dir = mc.player.getViewVector(1.0f);
        double dist = ProjectorConfig.INSTANCE.selectDistance.get();
        Double t = intersectPlane(plane, eye, dir, dist);
        if (t == null) return null;
        Vec3 p = eye.add(dir.scale(t));
        return plane.widgetAt(p, 0.6);
    }

    /** 射线与平面（该平面所在的那张平面）求交，返回参数 t。 */
    @Nullable
    public static Double intersectPlane(Plane plane, Vec3 from, Vec3 dir, double maxDist) {
        Direction n = plane.face;
        Vec3 origin = plane.canvas().originWorld();
        double denom = dir.x * n.getStepX() + dir.y * n.getStepY() + dir.z * n.getStepZ();
        if (Math.abs(denom) < 1.0e-6) return null;
        double num = (origin.x - from.x) * n.getStepX()
                + (origin.y - from.y) * n.getStepY()
                + (origin.z - from.z) * n.getStepZ();
        double t = num / denom;
        if (t < 0 || t > maxDist) return null;
        return t;
    }

    /** 判断某个方块是否属于某个平面（供破坏拦截提示使用）。 */
    @Nullable
    public static Plane planeAt(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        for (Plane p : PlaneCache.planesIn(mc.level.dimension().location())) {
            if (p.contains(pos)) return p;
        }
        return null;
    }
}

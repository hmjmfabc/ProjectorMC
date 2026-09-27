package top.hmjmfabc.projector.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import top.hmjmfabc.projector.client.ClientPermissions;
import top.hmjmfabc.projector.client.SelectionState;
import top.hmjmfabc.projector.common.Plane;
import top.hmjmfabc.projector.common.PlaneCanvas;
import top.hmjmfabc.projector.common.widget.Widget;
import top.hmjmfabc.projector.network.Payloads;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 平面对话框 —— 模组的核心管理界面。
 *
 * <p>包含：平面基本信息、命名、平面缩略图（可在其中拖动控件）、内容保护开关、
 * 新增控件菜单、创建者转让、删除平面等。</p>
 *
 * <h2>本轮的逻辑调整（用户 ⑤.4 / ⑥.1）</h2>
 * <ul>
 *   <li><b>移除「平面保护」与「平面误挖掘警告」两个按钮</b>：用户已决定不再写
 *       「挖掘方块会破坏平面」的判定，改为对话框最底部的红色「删除平面」
 *       （需连续两次确认），因此这两个开关失去了意义。</li>
 *   <li><b>修好「内容保护」按钮</b>：以前点击后标签文字永远停在旧值
 *       （回调里没有 {@code rebuildWidgets()}），看起来就是「点了没反应」。</li>
 *   <li><b>删除平面</b>：【hotfix-98 用户要求】<b>任何人都可以删</b>（默认放开），
 *       除非该平面开了「删除保护」（只有 4 级 OP 能开）——那时只有 4 级 OP 能删；
 *       二次确认后直接退回游戏内，
 *       平面及其全部控件消失。</li>
 *   <li><b>房主即管理员</b>：判定统一走 {@link ClientPermissions}，
 *       与服务端 {@code PlanePermissions} 的规则逐条对齐。</li>
 * </ul>
 */
public class PlaneDialogScreen extends ProjectorScreen {

    private final Plane plane;
    /** 【rc-80】快照值，仅用于日志；**判定一律用 adminNow()/canManageNow()**（数据可能后到）。 */
    private boolean admin;
    private boolean creator;
    private boolean canManage;
    /** 【hotfix-98】「删除保护」按钮（只有等级 4 的 OP 能点）。 */
    private Button deleteProtectButton;

    /** 【rc-80】受 canManage 门控、需要每帧刷新可用性的按钮。 */
    @Nullable
    private Button protectButton;
    @Nullable
    private Button rebuildButton;
    @Nullable
    private Button transferButton;
    @Nullable
    private Button deleteButton;

    @Nullable
    private CanvasWidgetView canvas;
    @Nullable
    private EditBox nameBox;
    @Nullable
    private Widget selectedWidget;
    /** 选中控件操作条上的两个按钮（供选中状态变化时切换可用性）。 */
    @Nullable
    private Button editSelectedButton;
    @Nullable
    private Button deleteSelectedButton;
    private boolean transferMode;
    /** 命名输入框的临时内容：rebuildWidgets() 会重跑 init()，必须保留玩家已输入但未保存的内容。 */
    private String nameDraft;
    private long lastDragSubmit;
    @Nullable
    private Widget dragDirty;
    @Nullable
    private Widget resizeDirty;

    public PlaneDialogScreen(Plane plane) {
        super(Component.literal("\u6295\u5f71\u4f2a\uff1a" + plane.displayName()));
        this.plane = plane;
        // 【rc-80】这里**只取一次快照**是错的：plane.creator 由服务端异步同步，
        // 对话框开得早一点（或同步还没到）就拿到 creator=null ⇒ canManage 永久为 false
        // ⇒ 所有「canManage」开关的按钮（内容保护 / 重新圈选 / 转让 / 删除平面）全变成
        // 点不动的灰按钮。玩家报的「内容保护按钮极其不灵敏、几乎点不动」就是这个：
        // 数据碰巧在开对话窗前到了就能点，否则整个对话框生命周期内都点不动。
        // 现在改成**按需计算**（见 adminNow/creatorNow/canManageNow），并在每帧刷新按钮可用性。
        this.admin = ClientPermissions.isAdmin();
        this.creator = ClientPermissions.isCreator(plane);
        this.canManage = admin || creator;
    }

    // ------------------------------------------------------------------
    // 布局
    // ------------------------------------------------------------------

    /** 信息区每一行的行高。 */
    private static final int ROW_H = 12;
    /** 信息区与「命名」区之间的间距。 */
    private static final int INFO_GAP = 6;

    private int leftPanelWidth() {
        return Math.min(230, Math.max(170, this.width / 4));
    }

    /**
     * 信息区的行列表。
     *
     * <p><b>{@code init()} 与 {@code render()} 必须共用同一份</b>——
     * 以前 {@code init()} 里把信息区硬编码成「5 行 × 12」，而 {@code render()}
     * 实际画的行数随平面形状变化（完整平面多两行、布局不一致再多一行），
     * 于是命名输入框与创建者/方块数文字互相压在一起，越看越乱。</p>
     */
    private List<Object[]> infoRows() {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"\u5e73\u9762\u4fe1\u606f", TEXT_ACCENT});
        boolean incomp = plane.isIncomplete();
        rows.add(new Object[]{incomp ? "\u4e0d\u5b8c\u6574\u5e73\u9762\uff08\u4ec5\u652f\u6301\u6587\u672c\uff09"
                : "\u5b8c\u6574\u65b9\u5757\u5e73\u9762", incomp ? 0xFFFFAA55 : TEXT_GREEN});
        if (incomp) {
            rows.add(new Object[]{"\u957f/\u5bbd\uff1a\u4e0d\u9002\u7528", TEXT_DIM});
        } else {
            // 只显示「格」——同时显示内部单位很容易被误读成尺寸错了
            rows.add(new Object[]{"\u957f " + fmt(plane.width) + " \u683c", TEXT_NORMAL});
            rows.add(new Object[]{"\u5bbd " + fmt(plane.height) + " \u683c", TEXT_NORMAL});
            int cols = plane.blockColumns();
            int rws = plane.blockRows();
            boolean okW = plane.width == cols * 16;
            boolean okH = plane.height == rws * 16;
            if (cols > 0) {
                rows.add(new Object[]{"\u5e03\u5c40 " + cols + " \u5217 x " + rws + " \u884c "
                        + (okW && okH ? "\u2714" : "\u2716"), okW && okH ? TEXT_GREEN : TEXT_RED});
                if (!okW || !okH) {
                    rows.add(new Object[]{"!! \u957f\u5bbd\u4e0e\u5217/\u884c\u6570\u4e0d\u4e00\u81f4", TEXT_RED});
                }
            }
        }
        rows.add(new Object[]{"\u521b\u5efa\u8005\uff1a" + (plane.creatorName == null || plane.creatorName.isEmpty()
                ? "\uff08\u65e0\uff09" : plane.creatorName), TEXT_NORMAL});
        rows.add(new Object[]{"\u65b9\u5757\u6570\uff1a" + plane.blockCount() + "   \u63a7\u4ef6\uff1a" + plane.widgets.size(),
                TEXT_DIM});
        return rows;
    }

    /** 信息区第一行的 y。 */
    private int infoTop(int py) {
        return py + 8;
    }

    /** 「命名」输入框的 y（信息区之后）。 */
    private int formTop(int py) {
        return infoTop(py) + infoRows().size() * ROW_H + INFO_GAP;
    }

    /**
     * 左侧面板「底部固定区」需要预留的高度：红色「删除平面」+「关闭」两行。
     *
     * <p>这两个按钮必须**永远**贴在面板底部（用户要求删除平面在对话框最底下），
     * 因此上面所有内容都必须给它们让位。</p>
     */
    private static final int BOTTOM_RESERVE = 8 + 20 + 6 + 20 + 4;

    @Override
    protected void init() {
        int pad = 10;
        int leftW = leftPanelWidth();
        int px = pad;
        int py = pad;
        int panelBottom = py + (this.height - pad * 2);

        // y 是「下一个可放按钮的位置」；limit 是内容区下边界。
        // 【为什么要这个 limit】以前所有按钮一路 y += 往下摆，屏幕一矮就与
        // 底部固定的「删除平面 / 关闭」重叠——而原版 Screen.mouseClicked 是按
        // children 添加顺序命中第一个，重叠之后先加的那个会把点击全吃掉
        // （AGENTS.md §5.5 第 24、41 条）。现在按**重要性**从上往下排，
        // 放不下的次要按钮直接不创建，并在 render 里提示。
        int y = formTop(py);
        final int limit = panelBottom - BOTTOM_RESERVE;
        int skipped = 0;

        // ---- 命名（最重要，放最前）----
        String nameInitial = nameDraft != null ? nameDraft : (plane.name == null ? "" : plane.name);
        nameBox = editBox(px + 8, y, leftW - 16, 18, nameInitial, 64, s -> nameDraft = s);
        y += 24;
        if (y + 18 <= limit) {
            button("\u4fdd\u5b58\u540d\u79f0", px + 8, y, leftW - 16, 18, b -> sendRename());
            y += 24;
        } else {
            skipped++;
        }

        // ---- 新增控件 / 新增流程（第二重要）----
        if (y + 18 <= limit) {
            button("\u65b0\u589e\u63a7\u4ef6 \u25b8", px + 8, y, leftW - 16, 18,
                    b -> openAddMenu()).active = canEdit();
            y += 24;
        } else {
            skipped++;
        }
        if (y + 18 <= limit) {
            int n = plane.sequence == null ? 0 : plane.sequence.size();
            String label = n == 0 ? "\u65b0\u589e\u6d41\u7a0b" : "\u6d41\u7a0b\uff08" + n + " \u4e2a\u7247\u6bb5\uff09";
            button(label, px + 8, y, leftW - 16, 18, b -> openSequence()).active = canEdit();
            y += 24;
        } else {
            skipped++;
        }

        // ---- 内容保护 ----
        if (y + 18 <= limit) {
            // 【⑤.4 修复】点击后必须 rebuildWidgets()，否则标签一直显示旧值。
            // 【rc-80】再加上**乐观本地翻转**：以前 rebuild 时读的还是服务端广播回来之前的旧值，
            // 于是「点了标签不变 ⇒ 以为没点上 ⇒ 再点一次」，看着就是「极其不灵敏」。
            // 服务端若拒绝，rc-78 的 resyncTo 会把权威状态灌回来，界面自动回滚。
            protectButton = button(contentLabel(), px + 8, y, leftW - 16, 18, b -> {
                boolean next = !plane.protectContent;
                plane.protectContent = next;                      // 乐观：本地先生效
                CompoundTag t = new CompoundTag();
                t.putBoolean("value", next);
                send("protectContent", t);
                top.hmjmfabc.projector.Projector.LOGGER.info(
                        "[Projector] 内容保护：提交 {}（平面 {}，本地权限 canManage={}）",
                        next ? "开启" : "关闭", plane.displayName(), canManageNow());
                rebuildWidgets();
            });
            protectButton.active = canManageNow();
            y += 24;
        } else {
            skipped++;
        }

        // ---- 删除保护（hotfix-98）----
        // 默认关闭 ⇒ 任何人都能删平面；只有等级 4 的 OP 能开这个开关，开了之后
        // 非 4 级 OP 删不掉（防恶意涂鸦/破坏）。与「内容保护」是两件事，互不影响。
        if (y + 18 <= limit) {
            deleteProtectButton = button(deleteProtectLabel(), px + 8, y, leftW - 16, 18, b -> {
                boolean next = !plane.deleteProtect;
                plane.deleteProtect = next;                    // 乐观：本地先生效
                CompoundTag t = new CompoundTag();
                t.putBoolean("value", next);
                send("protectDelete", t);
                top.hmjmfabc.projector.Projector.LOGGER.info(
                        "[Projector] 删除保护：提交 {}（平面 {}，本地等级 4={}）",
                        next ? "开启" : "关闭", plane.displayName(), canToggleDeleteProtectNow());
                rebuildWidgets();
            });
            deleteProtectButton.active = canToggleDeleteProtectNow();
            y += 24;
        } else {
            skipped++;
        }

        // ---- 重新圈选 ----
        if (y + 18 <= limit) {
            rebuildButton = button("\u91cd\u65b0\u5708\u9009\u6b64\u5e73\u9762", px + 8, y, leftW - 16, 18,
                    b -> send("rebuild", new CompoundTag()));
            rebuildButton.active = canManageNow();
            y += 24;
        } else {
            skipped++;
        }

        // ---- 转让创建者（最次要，放最后）----
        if (transferMode) {
            List<PlayerInfo> players = new ArrayList<>();
            var conn = Minecraft.getInstance().getConnection();
            if (conn != null) {
                players.addAll(conn.getOnlinePlayers());
            }
            int shown = 0;
            for (PlayerInfo info : players) {
                if (shown >= 5 || y + 18 > limit) break;
                if (Minecraft.getInstance().player != null
                        && info.getProfile().getId().equals(Minecraft.getInstance().player.getUUID())) {
                    continue;
                }
                String name = info.getProfile().getName();
                UUID id = info.getProfile().getId();
                button(name, px + 8, y, leftW - 16, 18, b -> {
                    CompoundTag t = new CompoundTag();
                    t.putUUID("target", id);
                    send("transfer", t);
                    transferMode = false;
                    rebuildWidgets();
                });
                y += 20;
                shown++;
            }
            if (y + 18 <= limit) {
                button("\u53d6\u6d88\u8f6c\u8ba9", px + 8, y, leftW - 16, 18, b -> {
                    transferMode = false;
                    rebuildWidgets();
                });
            }
        } else if (y + 18 <= limit) {
            transferButton = redButton("\u8f6c\u8ba9\u521b\u5efa\u8005", px + 8, y, leftW - 16, 18, b -> {
                transferMode = true;
                rebuildWidgets();
            });
            transferButton.active = canManageNow();
        } else {
            skipped++;
        }
        this.skippedButtons = skipped;

        // ---- 底部固定区（永远在面板底部，绝不参与上面的挤压）----
        int deleteY = panelBottom - 8 - 20;
        int closeY = deleteY - 26;
        button("\u5173\u95ed", px + 8, closeY, leftW - 16, 20, b -> onClose());
        deleteButton = redButton("\u5220\u9664\u5e73\u9762", px + 8, deleteY, leftW - 16, 20,
                b -> confirmDelete());
        deleteButton.active = canDeleteNow();
        if (deleteProtectButton != null) {
            deleteProtectButton.setMessage(Component.literal(deleteProtectLabel()));
            deleteProtectButton.active = canToggleDeleteProtectNow();
        }

        // ---- 右侧画布缩略图 ----
        int cx = px + leftW + 8;
        int cw = this.width - cx - pad;
        int chh = this.height - pad * 2 - 40;
        CanvasWidgetView view = new CanvasWidgetView(cx, py + 8, Math.max(60, cw), Math.max(60, chh),
                plane, new CanvasWidgetView.Listener() {
            @Override
            public void onSelected(@Nullable Widget widget) {
                selectedWidget = widget;
                updateWidgetButtons();
            }

            @Override
            public void onMoved(Widget widget, double newX, double newY) {
                double[] c = CanvasWidgetView.clampPos(newX, newY, widget.w, widget.h,
                        plane.width, plane.height);
                newX = c[0];
                newY = c[1];
                widget.x = newX;
                widget.y = newY;
                long now = System.currentTimeMillis();
                if (now - lastDragSubmit >= 120L) {
                    lastDragSubmit = now;
                    CompoundTag t = new CompoundTag();
                    t.putUUID("widget", widget.id);
                    t.putDouble("x", newX);
                    t.putDouble("y", newY);
                    send("moveWidget", t);
                } else {
                    dragDirty = widget;
                }
            }

            @Override
            public void onResized(Widget widget, double newW, double newH) {
                double nw = Math.max(1.0, Math.min(newW, Math.max(16.0, plane.width * 2.0)));
                double nh = Math.max(1.0, Math.min(newH, Math.max(16.0, plane.height * 2.0)));
                widget.w = nw;
                widget.h = nh;
                double[] c = CanvasWidgetView.clampPos(widget.x, widget.y, nw, nh,
                        plane.width, plane.height);
                widget.x = c[0];
                widget.y = c[1];
                if (widget instanceof top.hmjmfabc.projector.common.widget.TextWidget tw) {
                    tw.manualSize = true;
                    if (tw.wrapWidth > 0) {
                        tw.wrapWidth = nw;
                    }
                }
                long now = System.currentTimeMillis();
                if (now - lastDragSubmit >= 120L) {
                    lastDragSubmit = now;
                    sendWidgetUpdate(widget);
                } else {
                    resizeDirty = widget;
                }
            }

            @Override
            public void onDragFinished(Widget widget) {
                dragDirty = null;
                resizeDirty = null;
                lastDragSubmit = System.currentTimeMillis();
                sendWidgetUpdate(widget);
            }
        });
        view.setEditable(canEdit());
        this.canvas = addRenderableWidget(view);

        // 选中控件操作条
        int by = py + 8 + Math.max(60, chh) + 4;
        editSelectedButton = button("\u7f16\u8f91\u9009\u4e2d\u63a7\u4ef6", cx, by, 110, 18,
                b -> editSelected());
        deleteSelectedButton = button("\u5220\u9664\u9009\u4e2d\u63a7\u4ef6", cx + 116, by, 110, 18,
                b -> deleteSelected());
        updateWidgetButtons();
    }

    /** 因为空间不足而没被创建的按钮数量（render 里给出提示）。 */
    private int skippedButtons;

    /** 选中状态变化时刷新操作条的可用性（以前这个方法是个空实现）。 */
    private void updateWidgetButtons() {
        boolean has = selectedWidget != null;
        if (editSelectedButton != null) editSelectedButton.active = has;
        if (deleteSelectedButton != null) deleteSelectedButton.active = has && canEdit();
    }

    private boolean canEdit() {
        return ClientPermissions.canEditContent(plane);
    }

    /** 【rc-80】实时重算权限：平面数据/权限可能比对话框晚到，绝不能只在构造时取一次快照。 */
    private boolean adminNow() {
        return ClientPermissions.isAdmin();
    }

    private boolean creatorNow() {
        return ClientPermissions.isCreator(plane);
    }

    private boolean canManageNow() {
        return adminNow() || creatorNow();
    }

    /** 【hotfix-98】能不能开关「删除保护」：只认等级 4（单人/房主不受限）。 */
    private boolean canToggleDeleteProtectNow() {
        return top.hmjmfabc.projector.client.ClientPermissions.canToggleDeleteProtection();
    }

    /** 【hotfix-98】能不能删这个平面：默认能，开了删除保护才要求等级 4。 */
    private boolean canDeleteNow() {
        return top.hmjmfabc.projector.client.ClientPermissions.canDelete(plane);
    }

    /** 删除保护按钮的文字（含当前状态）。 */
    private String deleteProtectLabel() {
        String state = plane.deleteProtect
                ? "\u5f00\uff08\u53ea\u6709 4 \u7ea7 OP \u80fd\u5220\uff09" : "\u5173\uff08\u4eba\u4eba\u53ef\u5220\uff09";
        boolean allowed = canToggleDeleteProtectNow();
        return "\u5220\u9664\u4fdd\u62a4\uff1a" + state + (allowed ? "" : "\uff08\u9650 4 \u7ea7 OP\uff09");
    }

    /**
     * 【rc-80】每帧刷新受「能否管理」门控的按钮可用性。
     *
     * <p>这样即使打开对话框时平面数据还没同步到（{@code creator} 为空），
     * 数据一到按钮就会自动变可点，而不是整个生命周期都点不动。
     * 状态**发生变化时**打一行日志，便于下次判断「到底是不是权限没到位」。</p>
     */
    /**
     * 【rc-81】客户端发出的平面操作计数：每秒超过 20 次就警告一行。
     *
     * <p>玩家「频繁新增控件」导致连接不稳时，这行日志能立刻区分
     * 「玩家真的点得很快」和「客户端在刷/回环」（后者在玩家停手后仍会持续）。</p>
     */
    private static void noteClientOp(String op) {
        long now = System.currentTimeMillis();
        if (now - CLIENT_OP_WINDOW > 1000L) {
            CLIENT_OP_WINDOW = now;
            CLIENT_OP_COUNT = 0L;
        }
        CLIENT_OP_COUNT++;
        if (CLIENT_OP_COUNT == 20L) {
            top.hmjmfabc.projector.Projector.LOGGER.warn(
                    "[Projector][客户端][同步] 平面操作过于频繁：{}+ 次/秒（最近 op={}）", CLIENT_OP_COUNT, op);
        }
    }

    private static long CLIENT_OP_WINDOW = System.currentTimeMillis();
    private static long CLIENT_OP_COUNT;

    private void refreshGates() {
        boolean allowed = canManageNow();
        if (allowed != canManage) {
            canManage = allowed;
            admin = adminNow();
            creator = creatorNow();
            top.hmjmfabc.projector.Projector.LOGGER.info(
                    "[Projector] 平面对话框权限刷新：canManage={}（admin={} creator={}）平面={}",
                    allowed, admin, creator, plane.displayName());
        }
        if (protectButton != null) protectButton.active = allowed;
        if (rebuildButton != null) rebuildButton.active = allowed;
        if (transferButton != null) transferButton.active = allowed;
        // 【hotfix-98】删除与「删除保护」都不看 canManage：
        // 删除默认人人可点；删除保护只有等级 4 能点（各自按需重算，数据晚到也能修好）。
        if (deleteButton != null) deleteButton.active = canDeleteNow();
        if (deleteProtectButton != null) {
            deleteProtectButton.setMessage(Component.literal(deleteProtectLabel()));
            deleteProtectButton.active = canToggleDeleteProtectNow();
        }
    }

    private String contentLabel() {
        return "\u5185\u5bb9\u4fdd\u62a4\uff1a" + (plane.protectContent ? "\u5f00" : "\u5173");
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        int pad = 10;
        int leftW = leftPanelWidth();
        // 【rc-80】先刷新门控按钮的可用性，再画（否则画出来的是过期状态）
        refreshGates();
        panel(gfx, pad, pad, leftW, this.height - pad * 2);
        super.render(gfx, mouseX, mouseY, partialTick);
        int px = pad;
        int py = pad;

        // 信息区：行数与 init() 用的是同一份 infoRows()
        List<Object[]> rows = infoRows();
        int y = infoTop(py);
        for (Object[] row : rows) {
            label(gfx, (String) row[0], px + 8, y, (Integer) row[1]);
            y += ROW_H;
        }
        // 「命名」小标题画在输入框正上方
        label(gfx, "\u547d\u540d", px + 8, y, TEXT_ACCENT);

        // 画布标题
        if (canvas != null) {
            label(gfx, "\u5e73\u9762\u7f29\u7565\u56fe\uff08\u53ef\u62d6\u62fd\u79fb\u52a8 / \u53f3\u4e0b\u89d2\u7f29\u653e\uff09",
                    canvas.getX(), canvas.getY() - 11, TEXT_ACCENT);
        }

        // 权限提示
        String hint;
        if (!canEdit()) {
            hint = "\u63a7\u4ef6\u5185\u5bb9\u53d7\u4fdd\u62a4\uff0c\u4f60\u65e0\u6743\u4fee\u6539";
        } else if (!admin) {
            hint = "\u4f60\u662f\u521b\u5efa\u8005\uff1a\u53ef\u7ba1\u7406\u5185\u5bb9\u4e0e\u5220\u9664\u5e73\u9762";
        } else {
            hint = ClientPermissions.isAdmin() && Minecraft.getInstance().hasSingleplayerServer()
                    ? "\u623f\u4e3b\u6743\u9650\uff08\u89c6\u540c\u7ba1\u7406\u5458\uff09" : "\u7ba1\u7406\u5458\u6743\u9650";
        }
        label(gfx, hint, px + 8, this.height - pad - 12, admin ? TEXT_ACCENT : TEXT_DIM);
        // 空间不足时明说，免得玩家以为功能被删了
        if (skippedButtons > 0) {
            label(gfx, "\u7a97\u53e3\u592a\u77ee\uff1a\u6709 " + skippedButtons
                            + " \u4e2a\u6309\u94ae\u672a\u663e\u793a\uff0c\u8bf7\u62c9\u5927\u7a97\u53e3\u6216\u8c03\u5c0f GUI \u7f29\u653e",
                    px + 8, this.height - pad - 24, 0xFFFFAA55);
        }
    }

    private static String fmt(int units) {
        double blocks = units / (double) PlaneCanvas.UNITS_PER_BLOCK;
        if (Math.abs(blocks - Math.rint(blocks)) < 0.01) {
            return String.valueOf((int) Math.rint(blocks));
        }
        return String.format(java.util.Locale.ROOT, "%.1f", blocks);
    }

    // ------------------------------------------------------------------
    // 行为
    // ------------------------------------------------------------------

    private void sendRename() {
        CompoundTag t = new CompoundTag();
        String value = nameBox == null ? "" : nameBox.getValue();
        nameDraft = value;
        t.putString("name", value);
        send("rename", t);
    }

    /**
     * 发送一个平面编辑请求（作用于本对话框持有的平面）。
     *
     * <p>绝不能读全局的 {@code SelectionState.plane()}：那样出现过
     * 「界面显示 A 平面、实际操作 B 平面」的情况，最严重时会删错平面。</p>
     */
    private void send(String op, CompoundTag args) {
        // 【rc-81】所有平面操作都从这里出门，顺手做频率统计
        noteClientOp(op);
        PacketDistributor.sendToServer(new Payloads.PlaneEdit(plane.id, op, args));
    }

    public static void sendFor(Plane plane, String op, CompoundTag args) {
        // 【rc-81】其它界面（控件编辑器等）也走这里，统一计数
        noteClientOp(op);
        PacketDistributor.sendToServer(new Payloads.PlaneEdit(plane.id, op, args));
    }

    /** 提交一个控件的新状态（作用于本对话框持有的平面）。 */
    private void sendWidgetUpdate(Widget widget) {
        CompoundTag t = new CompoundTag();
        t.putUUID("widget", widget.id);
        // 控件数据用独立的键，避免覆盖上面的 UUID（详见 WidgetEditorScreen.widgetArgs）
        t.put("data", widget.save());
        PacketDistributor.sendToServer(new Payloads.PlaneEdit(plane.id, "updateWidget", t));
    }

    private void editSelected() {
        if (selectedWidget == null) return;
        Minecraft.getInstance().setScreen(WidgetEditorScreen.create(plane, selectedWidget));
    }

    private void deleteSelected() {
        if (selectedWidget == null) return;
        final Widget target = selectedWidget;
        Minecraft.getInstance().setScreen(new ConfirmScreen(this,
                "\u786e\u5b9a\u5220\u9664\u6b64\u63a7\u4ef6\uff1f",
                "\u5220\u9664\u540e\u65e0\u6cd5\u6062\u590d\u3002",
                () -> {
                    CompoundTag t = new CompoundTag();
                    t.putUUID("widget", target.id);
                    send("removeWidget", t);
                    selectedWidget = null;
                    Minecraft.getInstance().setScreen(this);
                }));
    }

    /**
     * 删除平面：<b>连续两次确认</b>之后才真正删除。
     *
     * <p>【用户 ⑤.4 的要求】</p>
     * <ul>
     *   <li>按钮在对话框最底部、红色字体；</li>
     *   <li>要按两次确定才能删除；</li>
     *   <li>删除后<b>直接退回游戏内</b>（不再停在对话框里）；</li>
     *   <li>只有管理员与平面创建者能删除。</li>
     * </ul>
     */
    private void confirmDelete() {
        // 【hotfix-98】删除权限放开了：默认任何人都能删；只有开了「删除保护」的平面
        // 才要求等级 4（判定与按钮可用性同源，避免「按钮能点、发出去被拒」）。
        if (!canDeleteNow()) {
            Minecraft.getInstance().player.displayClientMessage(
                    Component.translatable("projector.msg.delete_protected"), true);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        // 第二次确认：确认后真正删除并退回游戏内
        Runnable secondStep = () -> mc.setScreen(new ConfirmScreen(this,
                "\u518d\u6b21\u786e\u8ba4\uff1a\u771f\u7684\u8981\u5220\u9664\u5417\uff1f",
                "\u6b64\u64cd\u4f5c\u4e0d\u53ef\u64a4\u9500\u3002\u70b9\u300c\u786e\u5b9a\u300d\u540e\u7acb\u5373\u5220\u9664\u5e76\u8fd4\u56de\u6e38\u620f\u3002",
                this::doDeletePlane));
        // 第一次确认
        mc.setScreen(new ConfirmScreen(this,
                "\u786e\u5b9a\u5220\u9664\u5e73\u9762\uff1f",
                "\u5e73\u9762\u4e0a\u7684\u6240\u6709\u63a7\u4ef6\u4e0e\u4fe1\u606f\u5c06\u5168\u90e8\u6d88\u5931\uff01",
                secondStep));
    }

    /** 真正执行删除：发请求 → 清掉本地圈选 → 直接回到游戏内。 */
    private void doDeletePlane() {
        send("deletePlane", new CompoundTag());
        // 本地也立即移除，避免在等待服务端广播的一两帧里还能看到高光边框
        top.hmjmfabc.projector.client.PlaneCache.removeLocal(plane.id);
        SelectionState.clear();
        // setScreen(null) = 关掉所有模组界面，直接回到游戏内
        Minecraft.getInstance().setScreen(null);
    }

    private void openAddMenu() {
        Minecraft.getInstance().setScreen(new AddWidgetScreen(this, plane));
    }

    /**
     * 【⑩】打开流程（时间轴）编辑器。
     *
     * <p>按用户描述，时间轴「出现在对话框下方」。这里开的是一个独立界面，
     * 但它<b>保留了同一份平面预览图</b>（「控件的预览图不消失」），
     * 并且把时间轴放在预览图正下方——既满足了描述，又让时间轴与动画设置
     * 有足够的空间（塞进原对话框会和已有的按钮区互相挤压）。</p>
     */
    private void openSequence() {
        Minecraft.getInstance().setScreen(new SequenceEditorScreen(plane, this));
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }
}

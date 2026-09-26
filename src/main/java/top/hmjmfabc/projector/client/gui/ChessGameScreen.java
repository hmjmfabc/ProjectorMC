package top.hmjmfabc.projector.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import top.hmjmfabc.projector.client.ClientPermissions;
import top.hmjmfabc.projector.client.media.MediaCache;
import top.hmjmfabc.projector.common.Plane;
import top.hmjmfabc.projector.common.game.GameAi;
import top.hmjmfabc.projector.common.game.GameDifficulty;
import top.hmjmfabc.projector.common.game.GameKind;
import top.hmjmfabc.projector.common.game.GameMode;
import top.hmjmfabc.projector.common.game.GameRules;
import top.hmjmfabc.projector.common.game.GameSession;
import top.hmjmfabc.projector.common.game.Move;
import top.hmjmfabc.projector.client.render.PlaneRenderContext;
import top.hmjmfabc.projector.client.render.QuadCollector;
import top.hmjmfabc.projector.client.render.WidgetRenderer;
import top.hmjmfabc.projector.common.widget.ChessWidget;
import top.hmjmfabc.projector.network.Payloads;

import java.util.List;

/**
 * 【⑪】对局界面 —— 真正的「下棋」地方。
 *
 * <h2>为什么下棋用独立界面，而不是在世界上点棋子</h2>
 * <ul>
 *   <li>平面是斜的、可能还有旋转，在世界上做「点第几行第几列」的命中检测既难写又容易错，
 *       而一个正对着玩家的 2D 棋盘<b>零歧义</b>，也天然兼容触屏（Android 启动器把触摸映射成鼠标）；</li>
 *   <li>六种棋的棋盘尺寸相差极大（3x3 到 15x15），世界里那点尺寸根本点不准；</li>
 *   <li>墙上的控件保持「展示用」——所有人看到同一盘棋，这正好是公告栏场景想要的。</li>
 * </ul>
 *
 * <h2>AI 在哪里跑</h2>
 * <p>AI 搜索是<b>阻塞</b>的（最多几百毫秒），绝不能放在渲染线程——那会直接掉帧甚至卡死。
 * 所以它跑在 {@link MediaCache#worker()} 这个既有的工作线程池上，
 * 回来后用 {@code Minecraft.execute} 切回主线程提交结果。</p>
 *
 * <h2>斗蛐蛐循环与内存</h2>
 * <p>用户特别提醒「小心内存泄露」。本界面：</p>
 * <ul>
 *   <li>用一个 {@code closed} 标志让「界面已关闭后回来的 AI 结果」<b>直接丢弃</b>，
 *       不会继续提交、也不会再排下一手（否则循环会永远跑下去）；</li>
 *   <li>{@code removed()} 里调 {@code stopLoop()}，彻底切断循环；</li>
 *   <li>同一时刻<b>最多只有一个</b> AI 任务在跑（驱动器按控件 id 上闩锁），
 *       不会因为连点而堆出几十个任务；</li>
 *   <li>每一手的结果用 {@code moveCount} 校验：如果期间棋盘已经被别人改过
 *       （例如服务端广播了新局），这一个结果会被丢掉，而不是把旧局面写回去。</li>
 * </ul>
 */
public class ChessGameScreen extends ProjectorScreen {

    private static final int PAD = 10;
    private static final int LEFT_W = 150;

    private final Plane plane;
    private final ChessWidget widget;
    private final Screen parent;
    private final boolean canEdit;

    /** 界面是否已关闭：关闭后回来的 AI 结果一律丢弃（防循环、防内存泄露）。 */
    private volatile boolean closed;

    /**
     * 棋盘到屏幕的换算。
     *
     * <p><b>关键：对局界面不再自己算一套棋盘几何。</b>它把控件自己的画布空间
     * 直接用一组仿射参数映射到屏幕上（{@code screenX = ox + scale*canvasX}，
     * {@code screenY = oy - scale*canvasY}，y 取负是因为屏幕 y 向下而画布 y 向上），
     * 然后<b>调用与墙上完全相同的 {@code WidgetRenderer.drawChess}</b> 来画，
     * 点击也换算回画布坐标后交给 {@code widget.cellAt()} 判定。
     *
     * <p>以前这里有第二套「格心」几何，而控件那边已经改成「交叉点/格心」两套 ——
     * 于是墙上和界面里画在/判在不同的位置，用户看到的就是「下子位置不正确」。</p>
     */
    private double ox, oy, scale = 1.0;
    /** 棋盘占用的屏幕矩形（只用于画个边框）。 */
    private int bx, by, bw, bh;

    /** 选中的起点（走子类棋需要先选子）。 */
    private int selX = -1, selY = -1;
    private String status = "";
    private long lastSubmit;

    public ChessGameScreen(Plane plane, ChessWidget widget, Screen parent) {
        super(Component.literal("\u68cb\u7c7b\u6e38\u620f\uff1a" + widget.label()));
        this.plane = plane;
        this.widget = widget;
        this.parent = parent;
        this.canEdit = ClientPermissions.canEditContent(plane);
    }

    private GameSession game() {
        if (widget.game == null) {
            widget.game = new GameSession();
            widget.game.reset();
        }
        return widget.game;
    }

    // ------------------------------------------------------------------

    @Override
    protected void init() {
        GameSession g = game();
        int px = PAD;
        int y = PAD + 22;
        int w = LEFT_W;

        labelRow("\u68cb\u79cd\uff1a" + GameKind.name(g.kind), y);
        y += 12;
        button("\u5207\u6362\u68cb\u79cd", px, y, w, 18, b -> {
            g.setKind(GameKind.next(g.kind));
            // 换棋种必须把墙上的控件框撑到够大，否则每格会细到看不清
            widget.fitToBoard();
            afterChange("\u5df2\u5207\u6362\u5230 " + GameKind.name(g.kind));
        }).active = canEdit;
        y += 22;

        labelRow("\u6a21\u5f0f\uff1a" + GameMode.name(g.mode), y);
        y += 12;
        button("\u5207\u6362\u6a21\u5f0f", px, y, w, 18, b -> {
            g.mode = (g.mode + 1) % GameMode.NAMES.length;
            afterChange("\u5df2\u5207\u6362\u5230 " + GameMode.name(g.mode));
            maybeStartAi();
        }).active = canEdit;
        y += 24;

        // 【⑪】AI 智能档位
        button("AI \u667a\u80fd\uff1a" + GameDifficulty.name(g.difficulty), px, y, w, 18, b -> {
            g.difficulty = GameDifficulty.next(g.difficulty);
            afterChange("AI \u667a\u80fd\u5df2\u8bbe\u4e3a "
                    + GameDifficulty.name(g.difficulty));
        }).active = canEdit;
        y += 22;

        button("\u91cd\u65b0\u5f00\u5c40", px, y, w, 18, b -> {
            g.reset();
            selX = selY = -1;
            afterChange("\u5df2\u91cd\u65b0\u5f00\u5c40");
            maybeStartAi();
        }).active = canEdit;
        y += 22;

        // 斗蛐蛐循环（蛐 = U+86D0；曾误写成 U+8717「蜗」）
        if (g.mode == GameMode.AI_VS_AI) {
            button(g.loopEnabled ? "\u6597\u86d0\u86d0\u5faa\u73af\uff1a\u5f00" : "\u6597\u86d0\u86d0\u5faa\u73af\uff1a\u5173",
                    px, y, w, 18, b -> {
                g.loopEnabled = !g.loopEnabled;
                afterChange(g.loopEnabled ? "\u5df2\u5f00\u542f\u5faa\u73af\u5bf9\u5c40" : "\u5df2\u5173\u95ed\u5faa\u73af");
                maybeStartAi();
            }).active = canEdit;
            y += 20;
            labelRow("\u5df2\u6253 " + g.roundsPlayed + " \u5c40\uff1a\u5148\u624b\u80dc "
                    + g.roundsWonByA + " / \u540e\u624b\u80dc " + g.roundsWonByB, y);
            y += 12;
        }
        // 外观设置（颜色/字体/字号/旋转…）回到控件编辑器里做，
        // 这样右键棋盘就能一步开始下棋，设置也不会消失。
        button("\u63a7\u4ef6\u8bbe\u7f6e\u2026", px, y, w, 18,
                b -> Minecraft.getInstance().setScreen(
                        WidgetEditorScreen.create(plane, widget))).active = canEdit;

        // 棋盘布局：用控件自己的画布矩形映射到屏幕（唯一一套几何）
        widget.ensureBoard();
        int areaX = PAD + LEFT_W + 10;
        int availW = Math.max(40, this.width - areaX - PAD);
        int availH = Math.max(40, this.height - PAD * 2 - 24);
        double wW = Math.max(1.0, widget.w);
        double wH = Math.max(1.0, widget.h);
        scale = Math.max(0.2, Math.min(availW / wW, availH / wH));
        // 屏幕上棋盘的宽度/高度
        bw = (int) Math.round(wW * scale);
        bh = (int) Math.round(wH * scale);
        bx = areaX + Math.max(0, (availW - bw) / 2);
        by = PAD + 24 + Math.max(0, (availH - bh) / 2);
        // screenX = ox + scale*canvasX ; screenY = oy - scale*canvasY
        // canvasX = widget.x           -> screenX = bx
        // canvasY = widget.y + wH      -> screenY = by + bh
        ox = bx - scale * widget.x;
        oy = by + bh + scale * widget.y;

        button("\u8fd4\u56de", px, this.height - PAD - 22, w, 20,
                b -> Minecraft.getInstance().setScreen(parent));

        maybeStartAi();
    }

    private void labelRow(String text, int y) {
        labels.put(y, text);
    }

    private final java.util.Map<Integer, String> labels = new java.util.HashMap<>();

    /** 数据变了之后的统一处理：清空选中、提交给服务端、重建界面。 */
    private void afterChange(String message) {
        status = message;
        rebuildWidgets();
        submitGame(true);
    }

    // ------------------------------------------------------------------
    // 点击棋盘
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!canEdit) return super.mouseClicked(mouseX, mouseY, button);
        if (mouseX < bx || mouseY < by || mouseX >= bx + bw || mouseY >= by + bh) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        GameSession g = game();
        if (g.result != 0) {
            status = "\u672c\u5c40\u5df2\u7ed3\u675f\uff0c\u70b9\u300c\u91cd\u65b0\u5f00\u5c40\u300d";
            return true;
        }
        if (g.isAiTurn()) {
            status = "AI \u6b63\u5728\u601d\u8003\u2026";
            return true;
        }
        // 屏幕 -> 画布 -> 交给控件判定（与墙上同一套 cellAt）
        int[] cell = widget.cellAt((mouseX - ox) / scale, (oy - mouseY) / scale);
        if (cell == null) return true;
        int cx = cell[0], cy = cell[1];
        GameRules r = g.rules();

        List<Move> legal = g.legalMoves();
        // 走子类棋：第一次点选自己的子，第二次点目标格
        if (isPieceGame(g.kind)) {
            int p = g.at(cx, cy);
            if (p != 0 && Integer.signum(p) == g.turn) {
                selX = cx;
                selY = cy;
                status = "\u5df2\u9009\u4e2d " + r.glyph(p) + "\uff0c\u518d\u70b9\u76ee\u6807\u683c";
                return true;
            }
            if (selX < 0) {
                status = "\u8bf7\u5148\u70b9\u4e00\u4e2a\u81ea\u5df1\u7684\u68cb\u5b50";
                return true;
            }
            Move m = findMove(legal, selX, selY, cx, cy);
            if (m == null) {
                // 【rc-84】把「这个子能走到哪」直接说出来：选中时棋盘上已经高亮了它的全部落点，
                // 玩家不必再靠「点一下试试」猜（象棋的兵/炮规则尤其容易被误解成 bug）。
                int n = 0;
                for (Move cand : legal) if (cand.fx() == selX && cand.fy() == selY) n++;
                status = n == 0
                        ? "\u8fd9\u4e2a\u5b50\u73b0\u5728\u8d70\u4e0d\u4e86"
                        : ("\u8fd9\u4e00\u6b65\u4e0d\u5408\u6cd5\uff08\u8be5\u5b50\u53ef\u8d70 " + n
                           + " \u4e2a\u70b9\uff0c\u5df2\u5728\u68cb\u76d8\u4e0a\u9ad8\u4eae\uff09");
                return true;
            }
            selX = selY = -1;
            doMove(m);
            return true;
        }
        // 落子类棋：直接点空点
        Move m = findMove(legal, -1, -1, cx, cy);
        if (m == null) {
            // 【rc-84】空盘时不能只说「不能落子」：五子棋/围棋的第一手被规则限制在
            // 天元一点上（棋盘上已经用亮块着重标出），文案必须指向那一点。
            status = widget.firstMoveRestricted()
                    ? "\u7b2c\u4e00\u624b\u53ea\u80fd\u4e0b\u5728\u9ad8\u4eae\u7684\u90a3\u4e00\u70b9\uff08\u5929\u5143\uff0c\u68cb\u76d8\u6b63\u4e2d\u5fc3\uff09"
                    : "\u8fd9\u91cc\u4e0d\u80fd\u843d\u5b50";
            return true;
        }
        doMove(m);
        return true;
    }

    private static boolean isPieceGame(int kind) {
        return kind == GameKind.XIANGQI || kind == GameKind.CHESS;
    }

    @Nullable
    private static Move findMove(List<Move> legal, int fx, int fy, int tx, int ty) {
        for (Move m : legal) {
            if (m.tx() != tx || m.ty() != ty) continue;
            if (fx < 0) {
                if (m.isDrop()) return m;
            } else if (m.fx() == fx && m.fy() == fy) {
                return m;
            }
        }
        return null;
    }

    private void doMove(Move m) {
        GameSession g = game();
        if (!g.humanMove(m)) {
            status = "\u8fd9\u4e00\u6b65\u4e0d\u5408\u6cd5";
            return;
        }
        status = "";
        submitGame(true);
        rebuildWidgets();
        maybeStartAi();
    }

    // ------------------------------------------------------------------
    // AI
    // ------------------------------------------------------------------

    /**
     * 该不该让 AI 走一手。
     *
     * <p><b>AI 只有一条路径：全部交给 {@code ChessAiDriver}</b>
     * （世界里右键下棋也走它，见 AGENTS.md §5.5 第 52 条）。</p>
     *
     * <p>这里原先还留着一份「界面自己起线程算 AI + 算完自己续手」的副本，
     * 但它被上面 {@code isAiTurn()} 那一支挡在后面，<b>永远不会执行</b>
     * （能走到那儿必然「本局未结束且不是 AI 回合」，下一行就 return）——
     * 而死代码里恰好写着斗蛐蛐的「自动接着打」。也就是说：自走其实早就没了，
     * 而没人发现，因为测试脚本是自己 while 循环驱动 {@code aiMove()} 的。
     * 现在统一由驱动器负责，界面只负责「给不给续手许可」。</p>
     */
    private void maybeStartAi() {
        GameSession g = game();
        if (closed) return;
        if (g.result != 0) {
            // 一局结束：斗蛐蛐且开了循环才自动开下一局，其余模式停在终局画面
            if (g.mode == GameMode.AI_VS_AI && g.loopEnabled) {
                g.nextRoundIfLooping();
                submitGame(true);
                rebuildWidgets();
                maybeStartAi();
            }
            return;
        }
        if (!g.isAiTurn()) return;
        // 斗蛐蛐：允许驱动器在每手之后自动续手；人机 / 双人：走完这一手就停下等玩家。
        // 续手许可绑在界面的 closed 上 —— 界面一关，循环立刻断（防内存/CPU 漏水）。
        top.hmjmfabc.projector.client.ChessAiDriver.runOneMove(plane, widget,
                g.mode == GameMode.AI_VS_AI ? () -> !closed : null);
    }

    /**
     * 把棋局提交给服务端（走既有的 updateWidget 通道）。
     *
     * @param immediate true = 立刻发；false = 限流（AI 连下时不要每个包都广播一次整张平面）
     */
    private void submitGame(boolean immediate) {
        if (!canEdit) return;
        long now = System.currentTimeMillis();
        if (!immediate && now - lastSubmit < 300L) {
            pendingSubmit = true;
            return;
        }
        lastSubmit = now;
        pendingSubmit = false;
        // 【必须报告「本地已落子、等回声」】否则服务端那份更早的回声会把本地棋局退回去
        // （见 ChessWidget.noteLocalSubmit / ECHO_GRACE_MS）。
        widget.noteLocalSubmit();
        CompoundTag t = new CompoundTag();
        t.putUUID("widget", widget.id);
        t.put("data", widget.save());
        PacketDistributor.sendToServer(new Payloads.PlaneEdit(plane.id, "updateWidget", t));
    }

    private boolean pendingSubmit;

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        panel(gfx, PAD, PAD, LEFT_W, this.height - PAD * 2);
        super.render(gfx, mouseX, mouseY, partialTick);

        GameSession g = game();
        GameRules r = g.rules();
        labelShadow(gfx, "\u68cb\u7c7b\u6e38\u620f", PAD + 6, PAD + 6, TEXT_ACCENT);
        for (var e : labels.entrySet()) {
            label(gfx, e.getValue(), PAD + 6, e.getKey(), TEXT_DIM);
        }
        // 回合 / 结果
        label(gfx, g.turnText(), PAD + 6, this.height - PAD - 46,
                g.result != 0 ? TEXT_RED : TEXT_GREEN);
        // 【AI 提示必须实时算，不能存字段】AI 由驱动器接管，走完一手<b>不会</b>重建界面，
        // 存字段的话「AI 思考中…」会一直挂在界面上（即使早就轮到玩家了）。
        // 顺带这也是一处诊断：万一 AI 卡住，界面会停在「轮到 AI…」而不是骗人。
        String hint = status;
        if (g.result == 0 && g.isAiTurn()) {
            hint = top.hmjmfabc.projector.client.ChessAiDriver.isThinking(widget)
                    ? "AI \u601d\u8003\u4e2d\u2026" : "\u8f6e\u5230 AI\u2026";
        }
        if (!hint.isEmpty()) {
            label(gfx, hint, PAD + 6, this.height - PAD - 34, TEXT_ACCENT);
        }

        // 棋盘边框（只是视觉提示）
        gfx.fill(bx - 2, by - 2, bx + bw + 2, by + bh + 2, 0xFF3A3A55);

        // 【唯一一套几何】直接用墙上那套渲染代码画到屏幕空间：
        // axisY 取负是因为屏幕 y 向下、画布 y 向上。
        drawBoardOnScreen(gfx);
    }

    /**
     * 用与墙上完全相同的渲染代码把棋盘画到屏幕空间。
     *
     * <p>抄的是 {@code WidgetEditorScreen.drawPreview} 那套已经跑通的屏幕空间渲染：
     * 先 flush 掉 GUI 已有顶点、临时关深度测试、把 PoseStack 设成单位阵，
     * 再让 {@code QuadCollector} 提交并 flush。</p>
     */
    private void drawBoardOnScreen(GuiGraphics gfx) {
        double[] axisX = {scale, 0, 0};
        double[] axisY = {0, -scale, 0};
        double[] normal = {0, 0, -1};
        PlaneRenderContext ctx = new PlaneRenderContext(axisX, axisY, normal, new double[]{ox, oy, 0}, 0);
        gfx.flush();
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();
        gfx.pose().pushPose();
        gfx.pose().setIdentity();
        QuadCollector.beginFrame();
        board.clear();
        try {
            WidgetRenderer.drawChess(board, ctx, widget);
            board.flush(gfx.pose());
        } catch (Throwable t) {
            if (!boardErrorLogged) {
                boardErrorLogged = true;
                top.hmjmfabc.projector.Projector.LOGGER.error(
                        "[Projector] 对局界面棋盘渲染失败（后续同类错误不再重复记录）", t);
            }
        }
        gfx.pose().popPose();
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
    }

    private final QuadCollector board = new QuadCollector();
    private boolean boardErrorLogged;

    /** AI 在跑的时候给个提示。 */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        // 【关键】关闭界面时必须切断斗蛐蛐循环，否则一个看不见的界面会一直算下去。
        closed = true;
        try {
            game().stopLoop();
        } catch (Throwable ignored) {
        }
        if (pendingSubmit) {
            submitGame(true);
        }
        super.removed();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}

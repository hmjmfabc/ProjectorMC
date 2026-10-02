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
        // 【27.2 网页控件】先结算「按下之后要发的松开」。必须放在**所有提前 return 之前**：
        // 界面开着 / 不在世界里的时候，按下的那一下也得松开，否则页面上的按钮会一直显示被按住。
        top.hmjmfabc.projector.client.web.WebInput.pump();
        // 【斗蛐蛐自走的节拍器】必须放在下面「界面打开就 return」之前：
        // 对局界面开着的时候也要继续走棋。见 ChessAiDriver.pump()。
        ChessAiDriver.pump();
        if (mc.player == null || mc.level == null) {
            return;
        }
        // 【27.2-snapshot-127】MC 没认领的**左键**在这里补发（见 handleWebPageFallback）。
        // 位置有讲究：ClientTickEvent.Post 是 Minecraft.tick() 的**末尾**，
        // 而交互事件产生于同一个 tick() 里的 handleKeybinds() ⇒ 走到这里就说明
        // 「这次左键确实没有任何交互事件来认领它」，补发不会和正常那条路打架。
        if (mc.screen == null) {
            handleWebPageFallback(mc);
        }
        if (mc.screen != null) {
            return;
        }
        handleKey(mc, ProjectorKeys.KEY_CAPTURE);
        handleKey(mc, ProjectorKeys.KEY_CANCEL);
        handleKey(mc, ProjectorKeys.KEY_MEDIA_HELP);
    }

    /**
     * 【27.2-snapshot-126】鼠标按下：记下「刚按的是哪个键」。
     *
     * <p>它是右键判据 {@code WebInput.isWebPageClick} 的<b>第二条证据</b>
     * （另一条是 MC 的 {@code isUseItem()}），也是「MC 没把这次右键认成 use」时的兜底凭据
     * （见 {@link #handleWebRightFallback}）。</p>
     *
     * <p>⚠ <b>这里只记证据，不转发、也不取消事件</b>：取消掉
     * {@code InputEvent.MouseButton.Pre} 会让 MC 压根不产生交互事件
     * （{@code MouseHandler.onPress} 里 {@code onMouseButtonPre} 返回 true 就直接 return），
     * 于是「MC 把这次右键认成了什么」这个关键现场就永远看不到了 ——
     * 而那正是真机排查的第一眼。</p>
     *
     * <p>时机（1.21.1 源码核对过）：鼠标事件在帧末的 {@code Window.updateDisplay()} 里产生，
     * MC 的交互事件在<b>下一帧</b>的 {@code Minecraft.tick() → handleKeybinds()} 里产生
     * ⇒ 证据总是先到（所以 {@code MOUSE_EVIDENCE_MS} 要留够一帧的时间）。</p>
     */
    @SubscribeEvent
    public void onMouseButton(InputEvent.MouseButton.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null) {
            // 界面开着时鼠标归界面：那边不需要网页的右键判据
            // （而且在这里取消事件会把界面点击整个吃掉）。
            return;
        }
        top.hmjmfabc.projector.client.web.WebInput.noteMouseButton(
                event.getButton(), event.getAction(), System.currentTimeMillis());
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
        // 【27.1.3】点过就算「我要看」⇒ 允许下载（三种点击都算：叫出控件 / 播放键 / 进度条）
        top.hmjmfabc.projector.client.media.VideoControls.request(video.id);

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

    /**
     * 【27.2】网页控件的世界内按钮栏（⟳ ← → ⌂）与**页面本身的点击**：
     * 点控件底部那条叫出按钮栏，点按钮导航；其余位置（<b>右键</b>）转发给页面；
     * 5 秒无操作自动隐藏（见 {@code client.web.WebControls} / {@code client.web.WebInput}）。
     *
     * <p>与视频控件完全同一条路：几何取自控件自己（绘制与点击同源）、
     * 不要先按 U 选中平面。</p>
     *
     * <p><b>优先级</b>：①按钮栏（含叫出按钮栏的那条底边）②<b>右键 = 操作页面</b>
     * （点链接/按钮）③<b>左键 = 打开这个网页控件的编辑器</b>。</p>
     *
     * <p>⚠ 左右键的分工是<b>用户 2026-10-02 点名的口径</b>（上一版是反的）：
     * 右键操作网页，左键进编辑器。所以 {@code isAttack()} 那一支不再是「转发」而是
     * 「开编辑器」——<b>别对调回来</b>。右键在没有会话时（没装前置模组 / 页面还没出画面）
     * {@code WebInput.click} 返回 false ⇒ 仍然落到下面「打开编辑器」那条既有路径上，
     * 编辑器在任何情况下都进得去。</p>
     *
     * <p>⚠ 【27.2-snapshot-126】「是不是右键」由调用方按
     * {@code WebInput.isWebPageClick} 算好传进来（{@code right}）：<b>判据只有那一份</b>，
     * 这里不许再出现 {@code isUseItem()} 之类的判断（两份判定总有一份忘改）。</p>
     *
     * @param right 这次交互是不是右键（判据见 {@code WebInput.isWebPageClick}）
     * @return true 表示这次点击已经被吃掉（调用方直接 return）
     */
    private static boolean handleWebControl(Minecraft mc,
                                            InputEvent.InteractionKeyMappingTriggered event,
                                            boolean pageClick) {
        WidgetPick pick = pickWidget(mc, null);
        if (pick == null
                || !(pick.widget() instanceof top.hmjmfabc.projector.common.widget.WebWidget web)) {
            return false;
        }
        double[] local = webLocal(mc, pick.plane(), web);
        // ★【真机诊断】准心落在网页控件上时，一定留一行现场（限流：同一现场 3 行、隔 3 秒）。
        //   玩家报「点网页没反应」时，这一行 + WebInput 的「未转发：原因=…」
        //   就能定死是哪一道闸门 —— 以前这两处都是静默的，只能靠猜。
        top.hmjmfabc.projector.client.web.WebInput.logScene(
                pageClick ? top.hmjmfabc.projector.client.web.WebInput.MC_BUTTON_LEFT
                        : top.hmjmfabc.projector.client.web.WebInput.MC_BUTTON_RIGHT,
                event.isUseItem(), event.isAttack(), event.isPickBlock(), local, pick.plane(), web);
        // ② 画面本身：**左键**操作网页（点链接/按钮/聚焦输入框）。
        //    按钮栏与「转发给页面」都在 handleWebPress 里，**只有那一份**
        //    （鼠标钩子那条补发路径也调它）。
        if (pageClick) {
            // 交互事件认领了这次按下 ⇒ 鼠标层那条补发路径不再重复转发同一次按下
            top.hmjmfabc.projector.client.web.WebInput.claimMousePress();
            if (handleWebPress(mc, top.hmjmfabc.projector.client.web.WebInput.MC_BUTTON_LEFT)) {
                event.setCanceled(true);
                event.setSwingHand(false);
                return true;
            }
            // 没有会话（网页还没出画面）⇒ 吃掉但**不静默**：告诉玩家现在点不动
            event.setCanceled(true);
            event.setSwingHand(false);
            top.hmjmfabc.projector.client.web.WebControls.notifyNoPage();
            return true;
        }
        // ③ 右键 = **不操作网页**，只提示「请用左键」（玩家 2026-10-02 口径）。
        //    例外：网页压根没有画面时，右键改成打开编辑器（否则左键点不动、
        //    编辑器又没入口，玩家会被卡死）—— 这是唯一的例外，日志里写明原因。
        event.setCanceled(true);
        event.setSwingHand(false);
        if (!top.hmjmfabc.projector.client.web.WebInput.pictureReady(web)) {
            top.hmjmfabc.projector.client.web.WebControls.notifyNoPage();
            mc.setScreen(WidgetEditorScreen.create(pick.plane(), web));
            return true;
        }
        top.hmjmfabc.projector.client.web.WebControls.notifyLeftNeeded();
        return true;
    }

    /**
     * 世界内**一次点操作网页**：①按钮栏（⟳ ← → ⌂，含叫出按钮栏的底边）优先，
     * ②其余位置转发给页面（{@code WebInput.click}）。
     *
     * <p><b>转发链只有这一份</b>：交互事件那条路（{@link #handleWebControl}）与
     * 「MC 没认领这次左键」时的鼠标层补发（{@link #handleWebPageFallback}）都调它 ——
     * 两处各写一份必然有一天只改一处（本项目复发过的坑）。</p>
     *
     * @param mcButton {@code WebInput.MC_BUTTON_LEFT}（操作网页）或 {@code MC_BUTTON_RIGHT}
     * @return true = 这次点击已经被吃掉（转发成功，或被按钮栏接住）
     */
    private static boolean handleWebPress(Minecraft mc, int mcButton) {
        WidgetPick pick = pickWidget(mc, null);
        if (pick == null
                || !(pick.widget() instanceof top.hmjmfabc.projector.common.widget.WebWidget web)) {
            return false;
        }
        // ① 按钮栏优先：命中按钮/底边 = 我们自己的导航，绝不转发给页面
        //    （否则「点 ⟳ 刷新」会先被网页吃掉）
        if (top.hmjmfabc.projector.client.web.WebControls.handleWorldClick(mc, pick.plane(), web)) {
            return true;
        }
        // ② 画面本身：**按调用方给的键**转发给页面（左键＝操作网页）。
        //    键号是 MC / GLFW 的原始值（左 0 / 右 1）—— MCEF 的 sendMousePress 要的就是它
        //    （它自己会把 1/2 互换再映射成 CEF 的掩码，见 WebInput 类注释 ②）。
        double[] local = webLocal(mc, pick.plane(), web);
        if (local == null) {
            return false;
        }
        return top.hmjmfabc.projector.client.web.WebInput.click(
                pick.plane(), web, local[0], local[1], mcButton);
    }

    /**
     * 【27.2-snapshot-127】<b>MC 没认领这次左键时的补发</b>。
     *
     * <p>什么时候会走到这里：鼠标层确实按下了左键（{@code InputEvent.MouseButton.Pre} 记下了证据），
     * 但 MC 那条链<b>始终没有产生</b>带 {@code isAttack()==true} 的交互事件 ——
     * 比如「攻击」键没绑在鼠标左键上。这正是「点网页画面毫无反应」最可能的真相：
     * <b>点击根本没走到转发那一步</b>。</p>
     *
     * <p>调用点必须在 {@code Minecraft.tick() → handleKeybinds()}（也就是交互事件那条链）
     * <b>之后</b>才谈得上「没认领」：{@code ClientTickEvent.Post} 正是 {@code Minecraft.tick()}
     * 的<b>末尾</b>（1.21.1 源码核对过），所以是安全的。</p>
     *
     * <p>判据仍然是 {@code WebInput.isWebPageClick} 那一份；「这次按下有没有人认领」
     * 由 {@code WebInput} 的证据表回答 ⇒ <b>同一次按下最多转发一次</b>。</p>
     *
     * @return true = 已经补发（调用方不用再做别的）
     */
    private static boolean handleWebPageFallback(Minecraft mc) {
        if (!top.hmjmfabc.projector.client.web.WebInput.isWebClick(false, false,
                top.hmjmfabc.projector.client.web.WebInput.mouseButton())
                || !top.hmjmfabc.projector.client.web.WebInput.pendingMousePress()) {
            return false;
        }
        boolean ok = handleWebPress(mc, top.hmjmfabc.projector.client.web.WebInput.MC_BUTTON_LEFT);
        // 无论成没成都算「处理过了」：否则准心没落在控件上时，这个 tick 钩子
        // 会在证据有效期内每 tick 重试一次（白做射线）。
        top.hmjmfabc.projector.client.web.WebInput.claimMousePress();
        if (ok) {
            top.hmjmfabc.projector.client.web.WebInput.logPageFallback();
        }
        return ok;
    }

    /**
     * 【27.2】网页控件的键盘：
     * <ol>
     *   <li><b>I</b>（{@code WebInput.KEY_INPUT_TEXT}）＝打开「输入给网页」界面 ——
     *       网页里的输入框点进去之后，用 MC 自己的文本框（{@code EditBox}）打中文/字母/符号。
     *       MC 没给第三方模组「输入法合成串」的通道，借它自己的文本框是唯一可靠的做法。</li>
     *   <li>Backspace / Enter / Tab / 方向键 / Delete ＝ 转发给页面
     *       （{@code WebInput.forwardedKey}，只在「正在操作这个网页」时）。</li>
     * </ol>
     *
     * <p>为什么用 I 而不是 Enter：<b>Enter 要转发给页面</b>
     * （搜索框/表单按回车提交），两个功能不能共用一个键。I 现在空着 ——
     * ⑥.3 已把「取消圈选」由 I 改成 Esc（{@code ProjectorKeys.KEY_CANCEL}）。</p>
     *
     * <p><b>其余键一律不碰</b>：走路、开背包、打字聊天全都不受影响
     * （前三行就 return 了）。</p>
     */
    @SubscribeEvent
    public void onKeyInput(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null) {
            return;      // 界面开着时键盘归界面（我们的「输入给网页」界面也在其中，别抢它的键）
        }
        // 前置模组不可用（安卓上没装兼容层是常态）：一个键都不碰
        if (!top.hmjmfabc.projector.client.web.McefBridge.available()) {
            return;
        }
        int key = event.getKey();
        boolean press = event.getAction() != 0;   // 0=松开；1=按下；2=长按重复（都按「按下」转发）
        if (key == top.hmjmfabc.projector.client.web.WebInput.KEY_INPUT_TEXT) {
            if (press) {
                openWebInputScreen(mc);
            }
            return;
        }
        if (!top.hmjmfabc.projector.client.web.WebInput.forwardedKey(key)) {
            return;
        }
        WidgetPick pick = pickWidget(mc, null);
        if (pick == null
                || !(pick.widget() instanceof top.hmjmfabc.projector.common.widget.WebWidget web)) {
            return;
        }
        top.hmjmfabc.projector.client.web.WebInput.key(pick.plane(), web, key,
                event.getScanCode(), event.getModifiers(), press);
    }

    /**
     * 打开「输入给网页」界面（I 键）。
     *
     * <p>没有会话 / 环境不支持也照样打开 —— 界面里会写明原因（降级必须是明文的，
     * 不许「按了没反应」）。只有「作者不允许他人操作」时才不打开
     * （同一份判据 {@code WebControls.usable}，会顺手提示一句）。</p>
     */
    private static void openWebInputScreen(Minecraft mc) {
        WidgetPick pick = pickWidget(mc, null);
        if (pick == null
                || !(pick.widget() instanceof top.hmjmfabc.projector.common.widget.WebWidget web)) {
            // 准心没落在网页控件上：**不静默**，明说一句（玩家按 I 是想打字）
            top.hmjmfabc.projector.client.web.WebControls.notifyNeedAim();
            return;
        }
        if (!top.hmjmfabc.projector.client.web.WebInput.canUse(pick.plane(), web)) {
            return;      // 作者不允许他人操作：提示一句就完事（判据仍是 WebControls 那一份）
        }
        if (top.hmjmfabc.projector.client.web.WebInput.textInputStatus(web)
                == top.hmjmfabc.projector.client.web.WebInput.TextInputStatus.OK) {
            // ★ 主路：**MC 自己的聊天框**（snapshot-131）。
            //   理由（真机实证）：我们自己那个单行文本框在这台机器上收不到字符
            //   （输入法只把 Enter 之类的**按键**喂给游戏，字符没进来），
            //   而**聊天框是安卓上唯一被所有人验证过能打中文的地方**。
            //   所以：按 I ⇒ 开聊天框 ⇒ 打完回车 ⇒ 那段文字被我们截下来送进网页（不会发到公屏）。
            webPendingText = true;
            webPendingAtMs = System.currentTimeMillis();
            mc.setScreen(new net.minecraft.client.gui.screens.ChatScreen(""));
            top.hmjmfabc.projector.Projector.LOGGER.info(
                    "[Projector][网页] 打开聊天框当作「输入给网页」：打完回车，这段文字会送进网页"
                            + "（不会发到公屏）。控件={} 入口={}",
                    top.hmjmfabc.projector.client.web.WebSessions.shortId(web.id),
                    top.hmjmfabc.projector.client.web.WebInput.textMethodOf(web));
            top.hmjmfabc.projector.client.web.WebControls.notifyTyping();
        } else {
            // 没有画面 / 环境不支持：照旧开说明界面（把原因写在脸上，不静默）
            mc.setScreen(new top.hmjmfabc.projector.client.gui.WebInputScreen(pick.plane(), web));
        }
    }

    /** 「按了 I、正在等玩家在聊天框里打完回车」的一次性标记（带 60 秒有效期）。 */
    private static boolean webPendingText;
    private static long webPendingAtMs;

    /**
     * <b>把聊天框里打完的那段话截下来送进网页</b>（snapshot-131 的输入法主路）。
     *
     * <p>只有「刚按过 I」的那一条消息会被截（{@link #webPendingText}，60 秒内有效），
     * 所以<b>平时聊天完全不受影响</b>。以 {@code /} 开头的命令一律放行。</p>
     */
    @SubscribeEvent
    public void onClientChat(net.neoforged.neoforge.client.event.ClientChatEvent event) {
        if (!webPendingText) {
            return;
        }
        webPendingText = false;
        long age = System.currentTimeMillis() - webPendingAtMs;
        String msg = event.getMessage();
        if (msg == null || msg.isEmpty() || msg.startsWith("/") || age > 60_000L) {
            return;      // 命令/空消息/超时 ⇒ 照常聊天（不截）
        }
        Minecraft mc = Minecraft.getInstance();
        WidgetPick pick = pickWidget(mc, null);
        if (pick == null
                || !(pick.widget() instanceof top.hmjmfabc.projector.common.widget.WebWidget web)) {
            top.hmjmfabc.projector.client.web.WebControls.notifyNeedAim();
            return;      // 准心跑了 ⇒ 不截（这条就正常发出去）
        }
        if (top.hmjmfabc.projector.client.web.WebInput.insertText(pick.plane(), web, msg)) {
            event.setCanceled(true);      // 截下来：不发到公屏
            top.hmjmfabc.projector.Projector.LOGGER.info(
                    "[Projector][网页] 已把聊天框里的 {} 个字符送进网页（未发到公屏）：\"{}\"",
                    msg.length(), msg.length() > 24 ? msg.substring(0, 24) + "…" : msg);
        } else {
            top.hmjmfabc.projector.client.web.WebControls.notifyNoPage();
        }
    }

    /**
     * 【27.2】每帧把「准心正对着网页控件」转发给页面（悬停）。
     *
     * <p>挂 {@code RenderFrameEvent.Pre}：它在<b>每个渲染帧、渲染之前、客户端线程上</b>触发
     * （NeoForge 在 {@code Minecraft.runTick} 里 post），世界里没有单独的「鼠标移动」钩子，
     * 而准心就是这里的鼠标指针 ⇒ 这个钩子最贴切。频率由
     * {@code WebInput}(≈30 Hz + 同像素不重复) 自己节流，不会每帧都发。</p>
     *
     * <p>界面开着时一律不转发（与按键钩子同一条保护）：那时鼠标在操作 GUI。</p>
     */
    @SubscribeEvent
    public void onRenderFrame(net.neoforged.neoforge.client.event.RenderFrameEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null) {
            return;
        }
        // 前置模组不可用（安卓上这是常态：没装兼容层）：连拾取都不做，别每帧白扫一遍平面
        if (!top.hmjmfabc.projector.client.web.McefBridge.available()) {
            return;
        }
        // ★【27.2-pre-135 性能】世界里**一个网页控件都没有** ⇒ 一次射线都不做
        //   （标记由 WebSessions.tick 每帧顺手算，见 anyWidgetInWorld()）
        if (!top.hmjmfabc.projector.client.web.WebSessions.anyWidgetInWorld()) {
            return;
        }
        WidgetPick pick = pickWidget(mc, null);
        if (pick == null
                || !(pick.widget() instanceof top.hmjmfabc.projector.common.widget.WebWidget web)) {
            // 准心离开网页控件：复位节流（下次看回来要重发一次悬停）；
            // 页面光标不在这里抹掉 —— 它按自己的 5 秒窗口消失。
            top.hmjmfabc.projector.client.web.WebInput.leaveWidget();
            return;
        }
        double[] local = webLocal(mc, pick.plane(), web);
        if (local == null) {
            top.hmjmfabc.projector.client.web.WebInput.leaveWidget();
            return;
        }
        top.hmjmfabc.projector.client.web.WebInput.hover(pick.plane(), web, local[0], local[1]);
    }

    /**
     * 【27.2】滚轮：准心对着网页控件时转给页面（翻页），<b>并且吃掉这次滚轮</b>。
     *
     * <p>为什么必须 {@code setCanceled(true)}：原版 {@code MouseHandler.onScroll} 里，
     * 这个事件被取消时它就直接 {@code return}（NeoForge 的 patch 调
     * {@code ClientHooks.onMouseScroll(...)}，返回 true 即提前返回，已对着字节码确认）
     * ⇒ 不取消的话滚页面会顺手把快捷栏也换一格（两个动作同时发生）。</p>
     *
     * <p>只有「真的转发给页面」才取消：没命中网页控件、或没有会话时一律放行，
     * 行为与现在**完全一致**（否则滚轮就废了）。</p>
     */
    @SubscribeEvent
    public void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null) {
            return;
        }
        double dy = event.getScrollDeltaY();
        if (dy == 0.0) {
            return;
        }
        WidgetPick pick = pickWidget(mc, null);
        if (pick == null
                || !(pick.widget() instanceof top.hmjmfabc.projector.common.widget.WebWidget web)) {
            return;
        }
        double[] local = webLocal(mc, pick.plane(), web);
        if (local == null) {
            return;
        }
        if (top.hmjmfabc.projector.client.web.WebInput.scroll(
                pick.plane(), web, local[0], local[1], dy)) {
            event.setCanceled(true);
        }
    }

    /**
     * 准心打在某个网页控件上的**控件局部坐标**（局部 (0,0) = 左下、y 轴向上）。
     *
     * <p>与世界里点棋盘 / 点视频控件同一条路：世界射线 → 画布坐标 → 控件自己的逆变换
     * （{@code Widget.toLocal}）。这里<b>不另做射线</b>：复用
     * {@link #intersectPlane} 与拾取结果，免得出现两套几何。
     *
     * @return {@code {localX, localY}}；没打中返回 {@code null}
     */
    @Nullable
    private static double[] webLocal(Minecraft mc, Plane plane,
                                     top.hmjmfabc.projector.common.widget.WebWidget web) {
        if (plane == null || web == null || mc.player == null) {
            return null;
        }
        Vec3 eye = mc.player.getEyePosition(1.0f);
        Vec3 dir = mc.player.getViewVector(1.0f);
        Double t = intersectPlane(plane, eye, dir, ProjectorConfig.INSTANCE.selectDistance.get());
        if (t == null) {
            return null;
        }
        Vec3 hit = eye.add(dir.scale(t));
        return web.toLocal(plane.canvasX(hit), plane.canvasY(hit));
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
        //
        // 【27.2-snapshot-127 用户口径】**左键操作网页、右键打开编辑器**。
        // 判据**只有一份**：WebInput.isWebPageClick（左键那条）与本行的 right 合成。
        // right 认两条证据 —— MC 把这次输入认成 use，**或者**鼠标层刚按下的是右键：
        // startUseItem 前面还有 isDestroying/isHandsBusy 两道闸门，也可能玩家没把
        // 「使用」绑在鼠标右键上，∴ 不能只看 isUseItem()。左键同理（GLFW 层那条兜底）。
        int mb = top.hmjmfabc.projector.client.web.WebInput.mouseButton();
        // pageClick = **左键**（要转发给页面）；右键被 isWebClick 一票否决 ⇒ 走提示分支。
        // ⚠ 130 把这里写反了（变量名还叫 right、语义已经是左键），于是「左键弹提示、右键没反应」。
        boolean pageClick = top.hmjmfabc.projector.client.web.WebInput.isWebClick(
                event.isUseItem(), event.isAttack(), mb);
        if (!pageClick && !event.isUseItem()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null) return;
        if (handleVideoControl(mc, event)) {
            return;
        }
        // 【27.2】网页控件的按钮栏：同一条优先级（先看按钮，再考虑开编辑器）
        if (handleWebControl(mc, event, pageClick)) {
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

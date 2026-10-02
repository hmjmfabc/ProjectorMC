package top.hmjmfabc.projector.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import top.hmjmfabc.projector.client.web.WebInput;
import top.hmjmfabc.projector.common.Plane;
import top.hmjmfabc.projector.common.widget.WebWidget;

/**
 * 「输入给网页」界面（27.2 用户要求：<b>在网页里用输入法打字</b>）。
 *
 * <h2>为什么是「借 MC 自己的文本框」这条路</h2>
 * <p>MC 没有给第三方模组任何「输入法（IME）合成串」的通道，而且 <b>NeoForge 21.1 世界里根本没有
 * 「字符输入」事件</b>（{@code InputEvent} 只有 Key / MouseButton / MouseScrolling /
 * InteractionKeyMappingTriggered 四种，已对着 loader jar 核对）⇒ 世界里敲键盘收不到已上屏的汉字。
 * 唯一可靠的做法是<b>开一个有文本框的界面</b>：这是一个普通的 {@link EditBox}
 * （中文/字母/符号、输入法候选、粘贴全都天然可用）。</p>
 *
 * <p><b>snapshot-128 玩家口径：逐字实时送进页面。</b>打一个字符就立刻转给页面一次
 * （走兼容层的一次性 {@code insertText}），不再要求「打完再按发送」——
 * 上一版就是这样把玩家卡住的：他打字时只有 Enter 有反应（Enter 走的是按键转发那条路），
 * 字符一个都没进页面。现在界面里的文本框只是<b>镜像</b>（让你看见自己打了什么），
 * 真正的接收方是页面里那个输入框。</p>
 *
 * <h2>怎么进来 / 怎么出去</h2>
 * <ul>
 *   <li>进来：世界里准心对着网页控件、<b>右键点进网页里的输入框</b>之后按
 *       <b>I</b>（{@code WebInput.KEY_INPUT_TEXT}；{@code ClientInputHandler.onKeyInput}）。</li>
 *   <li>出去：Esc / 关闭 ⇒ 关界面、焦点还给游戏（{@code setScreen(null)} 内部会
 *       {@code grabMouse()}，已对着字节码确认）。打字是实时的，不需要「发送」。</li>
 * </ul>
 *
 * <h2>降级（不许静默失败）</h2>
 * <p>没有画面 / 环境没有文本入口时，界面照常打开，但会<b>写明原因</b>
 * （「当前环境不支持向网页输入文本」等）并把「发送」置灰 ——
 * 原因由 {@link WebInput#textInputStatus(WebWidget)} 一处给出（界面只负责把状态翻成语言文件里的句子），
 * 界面不另写一份判定。</p>
 */
public class WebInputScreen extends ProjectorScreen {

    /** 面板尺寸（小屏会按宽度收窄）。 */
    private static final int PANEL_W = 320;
    private static final int PANEL_H = 88;

    private final Plane plane;
    private final WebWidget widget;

    private EditBox box;
    private Button closeButton;

    /** 进界面那一刻判定的输入能力（用于降级文案与按钮可用性）。 */
    private WebInput.TextInputStatus status = WebInput.TextInputStatus.OK;

    /** 上一次提交的结果提示（空 = 不显示）。 */
    private String notice = "";
    /** 提示是不是「失败/不可用」（决定颜色）。 */
    private boolean noticeBad;

    /** 记住上次打过但没送出去的文本：重开界面不用重新打一遍。 */
    private static String lastText = "";

    public WebInputScreen(Plane plane, WebWidget widget) {
        super(Component.translatable("projector.gui.web_input_title"));
        this.plane = plane;
        this.widget = widget;
    }

    private int panelW() {
        return Math.max(160, Math.min(PANEL_W, this.width - 20));
    }

    private int panelY() {
        return Math.max(4, (this.height - PANEL_H) / 2);
    }

    @Override
    protected void init() {
        int w = panelW();
        int x = (this.width - w) / 2;
        int y = panelY();

        // 单行输入框：MC 自己的文本框 ⇒ 输入法 / 中文 / 粘贴都能用
        this.box = editBox(x + 12, y + 38, w - 24, 20, lastText, WebInput.MAX_TEXT, null);
        this.box.setFocused(true);
        setFocused(this.box);

        // snapshot-128：打字是实时的 ⇒ 不再需要「发送」，只留一个「关闭」
        this.closeButton = button(plainLang("projector.gui.web_input_cancel", "关闭"),
                x + w / 2 - 40, y + 68, 80, 20, b -> onClose());

        // 降级：把「为什么不能输入」摆在最显眼的地方，并且置灰发送键
        // （状态判据只有 WebInput.textInputStatus 一处，这里只负责翻译成人话）
        WebInput.TextInputStatus status = WebInput.textInputStatus(widget);
        this.status = status;
        if (status != WebInput.TextInputStatus.OK) {
            notice = reasonText(status);
            noticeBad = true;
            if (closeButton != null) {
                closeButton.active = true;      // 关界面永远可用（否则玩家被锁在界面里）
            }
        }
    }

    /** 状态 → 界面文案（语言文件里取；取不到用中文兜底）。 */
    private static String reasonText(WebInput.TextInputStatus status) {
        return switch (status) {
            case NO_SESSION -> plainLang("projector.msg.web_input_no_frame",
                    "这个网页现在没有画面，先等它加载出来再输入");
            case UNSUPPORTED -> plainLang("projector.msg.web_input_unsupported",
                    "当前环境不支持向网页输入文本");
            case OK -> "";
        };
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        super.render(gfx, mouseX, mouseY, partialTick);
        int w = panelW();
        int h = PANEL_H;
        int x = (this.width - w) / 2;
        int y = panelY();
        panel(gfx, x, y, w, h);
        centeredLabel(gfx, plainLang("projector.gui.web_input_title", "输入给网页"),
                this.width / 2, y + 8, TEXT_ACCENT);
        label(gfx, fit(plainLang("projector.gui.web_input_hint",
                        "打字会立刻进网页（中文/输入法可用）；"
                                + "回车＝提交；退格＝删除；Esc＝退出"), w - 24),
                x + 12, y + 24, TEXT_DIM);
        if (!notice.isEmpty()) {
            centeredLabel(gfx, fit(notice, w - 24), this.width / 2, y + 62,
                    noticeBad ? TEXT_RED : TEXT_GREEN);
        }
    }

    /**
     * <b>逐字实时送进页面</b>（snapshot-128 玩家口径）。
     *
     * <p>先让 {@code super} 处理（文本框里也能看见自己打了什么），随后把<b>同一个字符</b>
     * 转给页面一次 —— 上一次的翻车点就在这里：玩家在世界里（没有界面）敲键盘，
     * 而 NeoForge 世界里根本没有字符事件，于是只有 Enter/退格那些「按键」有反应。
     * 现在字符走这条路进页面。</p>
     *
     * <p>不支持 / 没有画面时不做任何事（界面上已经写明原因）。</p>
     */
    @Override
    public boolean charTyped(char c, int modifiers) {
        boolean handled = super.charTyped(c, modifiers);
        if (status == WebInput.TextInputStatus.OK && WebInput.typeChar(plane, widget, c)) {
            typed++;
        }
        return handled;
    }

    /**
     * 键盘：回车 ⇒ 转给页面（搜索框/表单提交）；退格/Delete ⇒ 转给页面；
     * Esc ⇒ 关界面。
     *
     * <p>必须排在 {@code super.keyPressed} 之前：焦点在 {@code EditBox} 上时，
     * 先由它处理键盘，退格与回车就轮不到我们了。</p>
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) {                        // Esc
            onClose();
            return true;
        }
        if (status == WebInput.TextInputStatus.OK
                && (keyCode == 257 || keyCode == 335)) {     // Enter / 小键盘 Enter
            WebInput.key(plane, widget, 257, 0, modifiers, true);
            WebInput.key(plane, widget, 257, 0, modifiers, false);
            notice = plainLang("projector.msg.web_input_enter", "回车已转给页面（表单/搜索会提交）");
            noticeBad = false;
            return true;
        }
        if (status == WebInput.TextInputStatus.OK
                && (keyCode == 259 || keyCode == 261)) {     // Backspace / Delete
            boolean press = true;
            WebInput.key(plane, widget, keyCode, 0, modifiers, press);
            WebInput.key(plane, widget, keyCode, 0, modifiers, false);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** 已经实时转给页面多少个字符（诊断：日志里一眼看出输入法到底有没有把字符送进来）。 */
    private int typed;

    /** 提交：整段文本交给桥 → 成功就关界面（焦点还给游戏），失败就把原因写在界面上。 */
    /**
     * 关界面时留一行诊断（snapshot-128）。
     *
     * <p>「实时送入 0 个字符」是**关键判据**：说明玩家在文本框里打的字根本没到 MC
     * （输入法/启动器的喂字通道有问题），不是我们没转发 —— 有了这一行，
     * 下一次真机日志一眼就能分清是谁的问题，不用再猜。</p>
     */
    @Override
    public void onClose() {
        top.hmjmfabc.projector.Projector.LOGGER.info(
                "[Projector][网页] 关闭「输入给网页」界面：实时送入 {} 个字符（入口={}）",
                typed, WebInput.textMethodOf(widget));
        super.onClose();
    }

    private String fit(String text, int maxWidth) {
        String t = text == null ? "" : text;
        if (this.font != null && this.font.width(t) > maxWidth) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < t.length(); i++) {
                if (this.font.width(b.toString() + t.charAt(i) + "\u2026") > maxWidth) {
                    break;
                }
                b.append(t.charAt(i));
            }
            return b + "\u2026";
        }
        return t;
    }
}

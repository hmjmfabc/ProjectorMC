package top.hmjmfabc.projector.client.gui;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 【27.2.3-hotfix-142 · 彩蛋】在平面编辑对话框左上角的版本号上连点 7 次触发的对话框。
 *
 * <p>内容由用户指定：正文「Ojang赶紧把The Sift加入原版😡🤚😡🤚😡🤚」，
 * 确定按钮「末地：问了吗？」。点确定之后：</p>
 * <ol>
 *   <li><b>退出所有界面</b>（{@code setScreen(null)} = 一路关掉模组界面回到游戏内，
 *       与「删除平面」后回游戏用的是同一个动作）；</li>
 *   <li>聊天栏收到一条 {@code [Mojang Studios]} 的回复（只在客户端本地显示，<b>不发到服务器</b>）；</li>
 *   <li>用<b>系统默认浏览器</b>打开
 *       <a href="https://www.bilibili.com/video/BV1GJ411x7h7">BV1GJ411x7h7</a>
 *       （用户指定；打不开只记一行日志，绝不影响游戏）。</li>
 * </ol>
 *
 * <p>⚠ 正文里的 emoji 照原样写（用户指定）。能不能显示是**看平台的**：
 * 桌面端一般有 emoji 字体，安卓启动器不一定有 —— 所以文案不动，
 * 也**不再**在旁边写「可能显示为空白」那行注释（用户要求删掉：表情能正常显示）。</p>
 */
public class EasterEggScreen extends ProjectorScreen {

    /** 正文（用户指定，一字不改）。 */
    private static final String LINE = "Ojang赶紧把The Sift加入原版😡🤚😡🤚😡🤚";

    /** 按钮文字（用户指定）。 */
    private static final String BUTTON = "末地：问了吗？";

    /** 点完确定之后聊天栏收到的那句话。 */
    private static final String REPLY = "感谢您的反馈。我们暂时不考虑删除末地。";

    /** 点完确定之后自动打开的地址（用户指定）。 */
    private static final String VIDEO_URL = "https://www.bilibili.com/video/BV1GJ411x7h7";

    private static final int W = 268;
    private static final int H = 84;

    public EasterEggScreen() {
        super(Component.literal("Projector Easter Egg"));
    }

    @Override
    protected void init() {
        int x = (this.width - W) / 2;
        int y = (this.height - H) / 2;
        button(BUTTON, x + 10, y + H - 30, W - 20, 20, b -> confirm());
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        super.render(gfx, mouseX, mouseY, partialTick);
        int x = (this.width - W) / 2;
        int y = (this.height - H) / 2;
        panel(gfx, x, y, W, H);
        // 只画正文一行（用户要求删掉下面那行 emoji 注释）
        centeredLabel(gfx, LINE, this.width / 2, y + 20, 0xFFFFDD55);
        centeredLabel(gfx, "\u2014\u2014 \u4f60\u70b9\u4e86 7 \u4e0b\u7248\u672c\u53f7\uff0c\u4e8e\u662f\u5b83\u771f\u7684\u6765\u4e86",
                this.width / 2, y + 42, TEXT_ACCENT);
    }

    /** 点确定：先说话，再退出所有界面，最后用系统默认浏览器打开那个视频。 */
    private void confirm() {
        Minecraft mc = Minecraft.getInstance();
        try {
            if (mc.player != null) {
                // 第二个参数 false = 走聊天栏（true 是物品栏上方那行 actionbar）。
                // 只在本地显示：这是给玩家看的玩笑回复，不该发到服务器。
                mc.player.displayClientMessage(
                        Component.literal("[Mojang Studios] " + REPLY), false);
            }
        } catch (Throwable t) {
            top.hmjmfabc.projector.Projector.LOGGER.warn("[Projector] 彩蛋回复发送失败：{}",
                    String.valueOf(t));
        }
        // 退出所有界面，直接回到游戏内
        mc.setScreen(null);
        openInBrowser(VIDEO_URL);
    }

    /**
     * 用系统默认浏览器打开一个网址。
     *
     * <p>用 {@link Util#getPlatform()}{@code .openUri(…)}，不自己起线程、不自己拼命令行：
     * 它内部就是「起一个守护线程去调系统命令」，而且把 Windows / macOS / Linux（含 xdg-open）
     * 三条路都处理好了。安卓上它走「未知平台」分支（什么都不做）—— 那种机器上本来也没有
     * 「默认浏览器」可以这样被调起来，所以只留一行日志。</p>
     *
     * <p>任何失败都只记日志：彩蛋不该有能力把游戏搞出问题。</p>
     */
    private static void openInBrowser(String url) {
        try {
            Util.getPlatform().openUri(url);
            top.hmjmfabc.projector.Projector.LOGGER.info(
                    "[Projector] 彩蛋：已用系统默认浏览器打开 {}", url);
        } catch (Throwable t) {
            top.hmjmfabc.projector.Projector.LOGGER.warn(
                    "[Projector] 彩蛋：打开浏览器失败（已忽略）：{}（地址 {}）", String.valueOf(t), url);
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }
}

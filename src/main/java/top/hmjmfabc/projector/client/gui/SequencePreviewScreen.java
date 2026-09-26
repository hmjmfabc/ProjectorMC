package top.hmjmfabc.projector.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import top.hmjmfabc.projector.client.ClientPermissions;
import top.hmjmfabc.projector.common.Plane;
import top.hmjmfabc.projector.common.sequence.SequenceClip;
import top.hmjmfabc.projector.network.Payloads;

import java.util.Locale;

/**
 * 【⑩】流程预览界面 —— 从 {@link SequenceEditorScreen} 右上角的「预览…」进入。
 *
 * <p>用户要求：「按下后，除时间轴以外的界面消失，玩家可以预览播放流程动画。
 * 预览界面类似于 Replay 模组播放回放时的界面。」</p>
 *
 * <p>所以这里做的事情是：</p>
 * <ul>
 *   <li><b>不画任何全屏遮罩</b>——{@link ProjectorScreen} 默认会给整个屏幕盖一层
 *       半透明深色，预览屏必须把它覆盖掉，否则「世界可见」就无从谈起；</li>
 *   <li>只在屏幕底部留一条<b>细控制条</b>：播放/暂停、重播、停止、当前时间，
 *       以及一条只读的细进度条（显示播放头在 0~999 秒里的位置）；</li>
 *   <li>玩家照常可以走动、转头——预览的是<b>世界里的真实渲染</b>，
 *       不是另开一个离屏画布，因此看到的就是最终效果；</li>
 *   <li>按 Esc 或点「退出预览」回到流程编辑器。</li>
 * </ul>
 */
public class SequencePreviewScreen extends ProjectorScreen {

    /** 底部控制条高度。 */
    private static final int BAR_H = 34;

    private final Plane plane;
    private final Screen parent;
    private final boolean canEdit;

    public SequencePreviewScreen(Plane plane, Screen parent) {
        super(Component.literal("\u6d41\u7a0b\u9884\u89c8"));
        this.plane = plane;
        this.parent = parent;
        this.canEdit = ClientPermissions.canEditContent(plane);
    }

    /**
     * <b>关键覆盖</b>：预览时不能盖遮罩，否则世界就看不见了。
     *
     * <p>{@code Screen.render()} 内部会调用 {@code renderBackground}，
     * 因此这里只要把它改成空实现，就得到了 Replay 模组那种「世界照常渲染、
     * 只有一条控制条浮在上面」的效果。</p>
     */
    @Override
    public void renderBackground(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        // 故意留空：预览要能看见世界
    }

    /**
     * 预览也不应该暂停游戏——否则动画（依赖世界游戏刻）会停住不动。
     */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        int pad = 10;
        int y = this.height - BAR_H + 8;
        int bw = 78;
        boolean playing = plane.sequence != null && plane.sequence.playing;
        button(playing ? "\u6682\u505c" : "\u64ad\u653e", pad, y, bw, 18,
                b -> togglePlay(playing)).active = canEdit;
        button("\u91cd\u64ad", pad + bw + 4, y, bw, 18, b -> send("seqPlay")).active = canEdit;
        button("\u505c\u6b62", pad + (bw + 4) * 2, y, bw, 18, b -> send("seqStop")).active = canEdit;
        button("\u9000\u51fa\u9884\u89c8", this.width - pad - bw, y, bw, 18,
                b -> Minecraft.getInstance().setScreen(parent));
    }

    private void togglePlay(boolean playing) {
        if (playing) {
            send("seqPause");
        } else {
            // 暂停后「播放」应当接着走（而不是从头），否则预览时很容易误触重播
            send(plane.sequence != null && plane.sequence.offsetSec > 0.01 ? "seqResume" : "seqPlay");
        }
    }

    private void send(String op) {
        if (!canEdit) return;
        PacketDistributor.sendToServer(new Payloads.PlaneEdit(plane.id, op, new CompoundTag()));
        Minecraft.getInstance().setScreen(new SequencePreviewScreen(plane, parent));
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        // 注意：**不要**调用 renderBackground（已覆盖为空，但这里连它也不调，
        // 避免将来有人给基类加上什么绘制又把世界盖掉）。
        super.render(gfx, mouseX, mouseY, partialTick);

        int barTop = this.height - BAR_H;
        gfx.fill(0, barTop, this.width, this.height, 0xB0000000);
        gfx.fill(0, barTop, this.width, barTop + 1, 0xFF3A3A55);

        double t = plane.sequence == null ? 0 : plane.sequence.timeSec(
                Minecraft.getInstance().level == null ? 0L
                        : Minecraft.getInstance().level.getGameTime());
        boolean playing = plane.sequence != null && plane.sequence.playing;
        String text = String.format(Locale.ROOT, "%s  %.1fs", playing ? "\u25b6" : "\u23f8", t);
        label(gfx, text, 10 + 78 * 3 + 16, barTop + 8, 0xFFFFFFFF);

        // 细进度条：播放头在 0~999 秒里的位置
        int barX = 10 + 78 * 3 + 16 + font.width(text) + 12;
        int barW = Math.max(40, this.width - barX - 96);
        gfx.fill(barX, barTop + 13, barX + barW, barTop + 17, 0xFF20242C);
        double frac = Math.max(0, Math.min(1, t / SequenceClip.MAX_SECONDS));
        gfx.fill(barX, barTop + 13, barX + (int) Math.round(barW * frac), barTop + 17, 0xFF7FD4FF);
        int headX = barX + (int) Math.round(barW * frac);
        gfx.fill(headX - 1, barTop + 10, headX + 1, barTop + 20, 0xFFFF5555);

        label(gfx, "\u6d41\u7a0b\u9884\u89c8\uff1a" + plane.displayName() + "   \uff08Esc \u8fd4\u56de\u7f16\u8f91\u5668\uff09",
                barX, barTop + 2, 0xFF9AA0B0);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}

package top.hmjmfabc.projector.compat.betterlogin.client.gui;

import top.hmjmfabc.projector.compat.betterlogin.NetMusicBetterLogin;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public class LoginSuccessScreen extends Screen {
    private static final ResourceLocation CHECK = ResourceLocation.fromNamespaceAndPath(NetMusicBetterLogin.MODID, "textures/gui/check.png");
    private final LinearLayout rootLayout;
    private final Button checkImage;
    private final Component text = Component.translatable("gui.netmusicbetterlogin.login.success");
    private final Button loginSuccessText;

    /** 【27.2.2】登录成功后点「确定」回到哪个界面（null = 回游戏）。 */
    @org.jetbrains.annotations.Nullable
    private final net.minecraft.client.gui.screens.Screen parent;

    public LoginSuccessScreen() {
        this(null);
    }

    public LoginSuccessScreen(net.minecraft.client.gui.screens.Screen parent) {
        super(Component.empty());
        this.parent = parent;
        rootLayout = new LinearLayout(120, 150, LinearLayout.Orientation.VERTICAL);
        rootLayout.setPosition(getCenterX(120), getCenterY(100));

        checkImage = Button.builder(Component.empty(), b -> {}).size(50, 50).build();
        rootLayout.addChild(checkImage, rootLayout.newCellSettings().alignHorizontallyCenter());

        Font font = Minecraft.getInstance().font;
        loginSuccessText = Button.builder(Component.empty(), b -> {}).size(font.width(text), font.lineHeight).build();
        rootLayout.addChild(loginSuccessText, rootLayout.newCellSettings().paddingTop(10).alignHorizontallyCenter());

        Button confirm = Button.builder(Component.translatable("gui.netmusicbetterlogin.login.confirm"), b -> this.onClose()).size(120, 20).build();
        rootLayout.addChild(confirm, rootLayout.newCellSettings().paddingTop(50).alignHorizontallyCenter());
        addRenderableWidget(confirm);
    }

    @Override
    protected void init() {
        rootLayout.setPosition(getCenterX(120), getCenterY(100));
        rootLayout.arrangeElements();
    }

    @Override
    public void resize(Minecraft pMinecraft, int pWidth, int pHeight) {
        this.width = pWidth;
        this.height = pHeight;
        init();
    }

    @Override
    public void render(GuiGraphics pGuiGraphics, int pMouseX, int pMouseY, float pPartialTick) {
        super.render(pGuiGraphics, pMouseX, pMouseY, pPartialTick);
        pGuiGraphics.blit(CHECK, checkImage.getX(), checkImage.getY(), 0, 0, 50, 50, 50, 50);
        pGuiGraphics.drawString(font, text, loginSuccessText.getX(), loginSuccessText.getY(), ChatFormatting.GREEN.getColor());
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    private int getCenter(int length, int eleLength) {
        return length / 2 - eleLength / 2;
    }

    private int getCenterX(int eleLength) {
        return getCenter(this.width, eleLength);
    }

    private int getCenterY(int eleLength) {
        return getCenter(this.height, eleLength);
    }
}

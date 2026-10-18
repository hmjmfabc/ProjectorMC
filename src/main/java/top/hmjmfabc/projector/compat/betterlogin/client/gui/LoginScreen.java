package top.hmjmfabc.projector.compat.betterlogin.client.gui;

import top.hmjmfabc.projector.compat.betterlogin.NetMusicBetterLogin;
import top.hmjmfabc.projector.compat.betterlogin.api.BetterNetWorker;
import top.hmjmfabc.projector.compat.betterlogin.api.pojo.NeteaseMessageResult;
import top.hmjmfabc.projector.compat.betterlogin.api.pojo.NeteaseQRKey;
import top.hmjmfabc.projector.compat.betterlogin.client.gui.layout.LinearLayout;
import top.hmjmfabc.projector.compat.betterlogin.client.gui.layout.SupplierChildLinearLayout;
import top.hmjmfabc.projector.compat.betterlogin.client.gui.widget.DynamicImage;
import top.hmjmfabc.projector.compat.betterlogin.client.gui.widget.QRCodeImage;
import top.hmjmfabc.projector.compat.betterlogin.client.gui.widget.RadioWidget;
import top.hmjmfabc.projector.compat.betterlogin.config.GeneralConfig;
import top.hmjmfabc.projector.compat.betterlogin.util.RenderUtil;
import com.google.gson.Gson;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.layouts.LayoutSettings;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class LoginScreen extends Screen {
    private static final ResourceLocation REFRESH = ResourceLocation.fromNamespaceAndPath(NetMusicBetterLogin.MODID, "textures/gui/refresh.png");
    private static final ResourceLocation CHECK = ResourceLocation.fromNamespaceAndPath(NetMusicBetterLogin.MODID, "textures/gui/check.png");
    private static final Gson GSON = new Gson();
    /** 【27.2.2】打开本界面的上一级（null = 直接回游戏）。从音乐控件的「登录」按钮进来时会带上它。 */
    @Nullable
    private final net.minecraft.client.gui.screens.Screen parent;
    private final LinearLayout rootLayout;
    private final QRCodeImage qrCodeImage;
    private final EditBox usernameInput;
    private final EditBox passwordInput;
    private final Button sendCaptchaButton;
    private final Button loginButton;
    private Component tip;
    private LoginMethod loginMethod = LoginMethod.EMAIL;
    private QRState qrState = QRState.PRELOAD;
    private String unikey = "";
    private int timer = 0;

    public LoginScreen() {
        this(null);
    }

    /** @param parent 关闭 / 登录成功后回到哪个界面（null = 回游戏） */
    public LoginScreen(net.minecraft.client.gui.screens.Screen parent) {
        super(Component.literal("Login"));
        this.parent = parent;
        rootLayout = new LinearLayout(390, 150, LinearLayout.Orientation.HORIZONTAL);
        rootLayout.setPosition(getCenterX(390), getCenterY(150));

        qrCodeImage = new QRCodeImage(0, 0, 150, 150, (x, y) -> refreshQRCode());
        qrCodeImage.setOnRender((guiGraphics, mouseX, mouseY, partialTick) -> {
            QRState state = getQrState();
            if (state.equals(QRState.ERROR) || state.equals(QRState.EXPIRE) || state.equals(QRState.SCAN_SUCCESS)) {
                guiGraphics.pose().pushPose();
                guiGraphics.pose().translate(0, 0, 200);
                guiGraphics.fill(
                        qrCodeImage.getX(), qrCodeImage.getY(),
                        qrCodeImage.getX() + qrCodeImage.getWidth(),
                        qrCodeImage.getY() + qrCodeImage.getHeight(),
                        0xE5FFFFFF
                );
                Component tip = switch (state) {
                    case EXPIRE ->
                            Component.translatable("gui.netmusicbetterlogin.login.qr_code.tip.expire").withStyle(ChatFormatting.RED).withStyle(ChatFormatting.BOLD);
                    case SCAN_SUCCESS ->
                            Component.translatable("gui.netmusicbetterlogin.login.qr_code.tip.scan_success").withStyle(ChatFormatting.DARK_GREEN).withStyle(ChatFormatting.BOLD);
                    case ERROR ->
                            Component.translatable("gui.netmusicbetterlogin.login.qr_code.tip.error").withStyle(ChatFormatting.RED).withStyle(ChatFormatting.BOLD);
                    default -> null;
                };
                RenderUtil.drawCenteredStringNoShadow(guiGraphics, font, tip, qrCodeImage.getX() + qrCodeImage.getWidth() / 2, qrCodeImage.getY() + 35, tip.getStyle().getColor().getValue());
                Component actionTip;
                int imageWidth = 40;
                if (!state.equals(QRState.SCAN_SUCCESS)) {
                    actionTip = Component.translatable("gui.netmusicbetterlogin.login.qr_code.tip.reload").withStyle(ChatFormatting.BLACK).withStyle(ChatFormatting.BOLD);
                    guiGraphics.blit(REFRESH, qrCodeImage.getX() + qrCodeImage.getWidth() / 2 - imageWidth / 2, qrCodeImage.getY() + 70, 0, 0, imageWidth, imageWidth, imageWidth, imageWidth);
                } else {
                    actionTip = Component.translatable("gui.netmusicbetterlogin.login.qr_code.tip.confirm").withStyle(ChatFormatting.BLACK).withStyle(ChatFormatting.BOLD);
                    guiGraphics.blit(CHECK, qrCodeImage.getX() + qrCodeImage.getWidth() / 2 - imageWidth / 2, qrCodeImage.getY() + 70, 0, 0, imageWidth, imageWidth, imageWidth, imageWidth);
                }
                int actionTipHeight = qrCodeImage.getY() + 50;
                RenderUtil.drawCenteredStringNoShadow(guiGraphics, font, actionTip, qrCodeImage.getX() + qrCodeImage.getWidth() / 2, actionTipHeight, actionTip.getStyle().getColor().getValue());
                guiGraphics.pose().popPose();
            }
        });
        rootLayout.addChild(qrCodeImage);
        addRenderableWidget(qrCodeImage);

        rootLayout.addChild(new Button.Builder(Component.empty(), button -> {}).size(40, 150).build());

        LinearLayout inputLayout = new LinearLayout(200, 130, LinearLayout.Orientation.VERTICAL);
        rootLayout.addChild(inputLayout, LayoutSettings.defaults().paddingVertical(10));

        List<Component> options = List.of(
                Component.translatable("gui.netmusicbetterlogin.login_type.email"),
                Component.translatable("gui.netmusicbetterlogin.login_type.phone")
        );
        RadioWidget loginMethodSwitch = new RadioWidget(0, 0, options, 10, 0, this::switchLoginMethod);
        inputLayout.addChild(loginMethodSwitch, inputLayout.newChildLayoutSettings().alignHorizontallyCenter());
        addRenderableWidget(loginMethodSwitch);

        usernameInput = new EditBox(getFont(), 0, 0, 198, 20, Component.literal("Username Input"));
        inputLayout.addChild(usernameInput, LayoutSettings.defaults().padding(1));
        addRenderableWidget(usernameInput);

        passwordInput = new EditBox(getFont(), 0, 0, 198, 20, Component.literal("Password Input"));
        addRenderableWidget(passwordInput);

        sendCaptchaButton = new Button.Builder(Component.translatable("gui.netmusicbetterlogin.login.send_captcha"), this::sendCaptcha)
                .size(60, 22)
                .build();
        addRenderableWidget(sendCaptchaButton);

        // 使用手机号登录时显示发送验证码按钮
        List<SupplierChildLinearLayout.ChildContainer> emailChilds = List.of(
                new SupplierChildLinearLayout.ChildContainer(passwordInput, LayoutSettings.defaults().paddingTop(1).paddingHorizontal(1))
        );
        List<SupplierChildLinearLayout.ChildContainer> phoneChilds = List.of(
                new SupplierChildLinearLayout.ChildContainer(passwordInput, LayoutSettings.defaults().paddingRight(5).paddingTop(1).paddingLeft(1)),
                new SupplierChildLinearLayout.ChildContainer(sendCaptchaButton, LayoutSettings.defaults())
        );
        SupplierChildLinearLayout passwordLayout = new SupplierChildLinearLayout(
                200, 22,
                SupplierChildLinearLayout.Orientation.HORIZONTAL,
                () -> switch (loginMethod) {
                    case EMAIL -> emailChilds;
                    case PHONE -> phoneChilds;
                });
        inputLayout.addChild(passwordLayout);

        loginButton = new Button.Builder(Component.translatable("gui.netmusicbetterlogin.login.login"), this::login)
                .width(200)
                .build();
        inputLayout.addChild(loginButton);
        addRenderableWidget(loginButton);

        loginMethodSwitch.setSelected(0);

        refreshQRCode();
    }

    public Font getFont() {
        return Minecraft.getInstance().font;
    }

    @Override
    protected void init() {
        rootLayout.setPosition(getCenterX(390), getCenterY(150));
        rootLayout.arrangeElements();
    }

    @Override
    public void tick() {
        // 每 2 秒检查一次二维码登录状态
        QRState state = getQrState();
        if ((state.equals(QRState.WAIT) || state.equals(QRState.SCAN_SUCCESS)) && this.timer == 0) {
            checkQRLoginStatus();
        }
        this.timer = (this.timer + 1) % 40;
    }

    @Override
    public void resize(Minecraft pMinecraft, int pWidth, int pHeight) {
        this.width = pWidth;
        this.height = pHeight;
        init();
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

    public void setQrState(QRState qrState) {
        this.qrState = qrState;
    }

    public QRState getQrState() {
        return qrState;
    }

    private void refreshQRCode() {
        QRState state = getQrState();
        if (state.equals(QRState.LOADING) || state.equals(QRState.WAIT) || state.equals(QRState.SCAN_SUCCESS)) {
            return;
        }
        setQrState(QRState.LOADING);
        this.unikey = "";
        CompletableFuture.supplyAsync(() -> {
                    try {
                        String resp = NetMusicBetterLogin.NETEASE_API.getQRKey();
                        NeteaseQRKey qrKey = GSON.fromJson(resp, NeteaseQRKey.class);
                        if (qrKey.code != 200) {
                            throw new RuntimeException("Failed to get QR key, response code: " + qrKey.code);
                        }
                        return qrKey.unikey;
                    } catch (Exception e) {
                        NetMusicBetterLogin.LOGGER.error("Failed to get QR key", e);
                        setQrState(QRState.ERROR);
                        throw new RuntimeException(e);
                    }
                }, Util.backgroundExecutor())
                .thenAccept(unikey -> {
                    // 【27.2.4-200 修】thenAccept 跑在 Util.backgroundExecutor() 上（supplyAsync 的续体），
                    // 而 updateQRCode 会写 DynamicTexture 的像素缓冲、还会 recordRenderCall，
                    // 界面字段也不是线程安全的 —— 必须回到客户端线程再做。
                    net.minecraft.client.Minecraft.getInstance().submit(() -> {
                        qrCodeImage.updateQRCode(
                                String.format("https://music.163.com/login?codekey=%s", unikey));
                        this.unikey = unikey;
                        if (qrCodeImage.getLoadingState().equals(DynamicImage.LoadingState.ERROR)) {
                            setQrState(QRState.ERROR);
                        } else {
                            setQrState(QRState.WAIT);
                        }
                    });
                });
    }

    private void switchLoginMethod(int i) {
        if (i == 0) {
            this.loginMethod = LoginMethod.EMAIL;
            this.sendCaptchaButton.visible = false;
            this.passwordInput.setWidth(198);
            this.passwordInput.setFormatter((value, displayPos) -> FormattedCharSequence.forward("*".repeat(value.length()), Style.EMPTY));
        } else if (i == 1) {
            this.loginMethod = LoginMethod.PHONE;
            this.sendCaptchaButton.visible = true;
            this.passwordInput.setWidth(134);
            this.passwordInput.setFormatter((value, displayPos) -> FormattedCharSequence.forward(value, Style.EMPTY));
        }
        this.passwordInput.setValue("");
        this.tip = null;
        rootLayout.arrangeElements();
    }

    private void sendCaptcha(Button button) {
        if (usernameInput.getValue().isBlank()) {
            tip = Component.translatable("gui.netmusicbetterlogin.login.tip.input_empty").withStyle(ChatFormatting.RED).withStyle(ChatFormatting.BOLD);
            return;
        }
        tip = null;
        CompletableFuture.runAsync(() -> {
            try {
                String response = NetMusicBetterLogin.NETEASE_API.sendCaptcha(usernameInput.getValue());
                NeteaseMessageResult result = GSON.fromJson(response, NeteaseMessageResult.class);
                if (result.code != 200) {
                    Component message = Optional.ofNullable(result.message)
                            .map(Component::literal)
                            .orElse(Component.translatable("gui.netmusicbetterlogin.login.tip.send_captcha.unknown_error"));
                    tip = Component.translatable("gui.netmusicbetterlogin.login.tip.send_captcha.error", message)
                            .withStyle(ChatFormatting.RED)
                            .withStyle(ChatFormatting.BOLD);
                    return;
                }
                tip = Component.translatable("gui.netmusicbetterlogin.login.tip.send_captcha.success").withStyle(ChatFormatting.GREEN).withStyle(ChatFormatting.BOLD);
            } catch (IOException e) {
                NetMusicBetterLogin.LOGGER.error("Failed to send captcha", e);
            }
        }, Util.backgroundExecutor());
    }

    private void login(Button button) {
        if (usernameInput.getValue().isBlank() || passwordInput.getValue().isBlank()) {
            tip = Component.translatable("gui.netmusicbetterlogin.login.tip.input_empty").withStyle(ChatFormatting.RED).withStyle(ChatFormatting.BOLD);
            return;
        }
        tip = null;
        CompletableFuture.runAsync(() -> {
            try {
                BetterNetWorker.HttpResponse response = switch (loginMethod) {
                    case EMAIL -> NetMusicBetterLogin.NETEASE_API.emailLogin(usernameInput.getValue(), passwordInput.getValue());
                    case PHONE -> NetMusicBetterLogin.NETEASE_API.phoneCaptchaLogin(usernameInput.getValue(), passwordInput.getValue());
                };
                NeteaseMessageResult result = GSON.fromJson(response.body, NeteaseMessageResult.class);
                if (result.code != 200) {
                    Component message = Optional.ofNullable(result.message)
                            .map(Component::literal)
                            .orElse(Component.translatable("gui.netmusicbetterlogin.login.tip.send_captcha.unknown_error"));
                    tip = Component.translatable("gui.netmusicbetterlogin.login.tip.error", message).withStyle(ChatFormatting.RED).withStyle(ChatFormatting.BOLD);
                    setCookieNoExtra(response.getCookies());
                    return;
                }
                setCookie(response.getCookies());
                this.success();
            } catch (Exception e) {
                NetMusicBetterLogin.LOGGER.error("Failed to login with phone captcha", e);
                throw new RuntimeException(e);
            }
        }, Util.backgroundExecutor());
    }

    private void checkQRLoginStatus() {
        if (!this.unikey.isEmpty()) {
            CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            return NetMusicBetterLogin.NETEASE_API.checkQRLoginStatus(this.unikey);
                        } catch (IOException e) {
                            NetMusicBetterLogin.LOGGER.error("Failed to check QR login status", e);
                            throw new RuntimeException(e);
                        }
                    }, Util.backgroundExecutor())
                    .thenAccept(response -> {
                        NeteaseMessageResult status = GSON.fromJson(response.body, NeteaseMessageResult.class);
                        switch (status.code) {
                            case 800 -> setQrState(QRState.EXPIRE);
                            case 801 -> setQrState(QRState.WAIT);
                            case 802 -> setQrState(QRState.SCAN_SUCCESS);
                            case 803 -> {
                                setCookie(response.getCookies());
                                this.success();
                            }
                        }
                    });
        }
    }

    public void success() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.submit(() -> {
            minecraft.setScreen(new LoginSuccessScreen(parent));
        });
    }

    private void setCookie(Map<String, String> cookieMap) {
        cookieMap.put("os", "pc");
        cookieMap.put("appver", "3.1.6");
        setCookieNoExtra(cookieMap);
    }

    private void setCookieNoExtra(Map<String, String> cookieMap) {
        cookieMap.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((a, b) -> a + "; " + b)
                .ifPresent(cookie -> {
                    GeneralConfig.setCookie(cookie);
                    NetMusicBetterLogin.NETEASE_API.setCookie(cookie);
                });
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        InputConstants.Key mouseKey = InputConstants.getKey(keyCode, scanCode);
        if (this.getMinecraft().options.keyInventory.isActiveAndMatches(mouseKey) && (usernameInput.isFocused() || passwordInput.isFocused())) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics pGuiGraphics, int pMouseX, int pMouseY, float pPartialTick) {
        super.render(pGuiGraphics, pMouseX, pMouseY, pPartialTick);

        MutableComponent usernameTip = Component.empty();
        MutableComponent passwordTip = Component.empty();
        if (loginMethod.equals(LoginMethod.EMAIL)) {
            usernameTip = Component.translatable("gui.netmusicbetterlogin.login.email.tip");
            passwordTip = Component.translatable("gui.netmusicbetterlogin.login.password.tip");
        } else if (loginMethod.equals(LoginMethod.PHONE)) {
            usernameTip = Component.translatable("gui.netmusicbetterlogin.login.phone.tip");
            passwordTip = Component.translatable("gui.netmusicbetterlogin.login.captcha.tip");
        }
        renderTipInEditBox(pGuiGraphics, usernameTip.withStyle(ChatFormatting.ITALIC), usernameInput);
        renderTipInEditBox(pGuiGraphics, passwordTip.withStyle(ChatFormatting.ITALIC), passwordInput);

        if (tip != null) {
            pGuiGraphics.drawString(font, tip, loginButton.getX(), loginButton.getY() - 13, tip.getStyle().getColor().getValue(), false);
        }
    }

    public void renderTipInEditBox(GuiGraphics guiGraphics, Component text, EditBox editBox) {
        // 【27.2.2】原来这里用 org.codehaus.plexus.util.StringUtils —— 那是 Gradle 插件
        // 类路径上的类，**游戏运行时并不存在**，执行到这一行就是 NoClassDefFoundError。
        // 判空用原版 String.isBlank() 就够了。
        String value = editBox.getValue();
        if ((value == null || value.isBlank()) && editBox.isVisible() && !editBox.isFocused()) {
            guiGraphics.drawString(font, text, editBox.getX() + 4, editBox.getY() + (editBox.getHeight() - font.lineHeight) / 2 + 1, 0xFFAAAAAA);
        }
    }

    @Override
    public void removed() {
        qrCodeImage.close();
    }

    @Override
    public void onClose() {
        // 【27.2.2】从编辑器 / 播放列表进来的话，关掉登录界面要回到那里，
        // 而不是被扔回游戏里（否则玩家得重新一层层点回音乐控件）。
        Minecraft.getInstance().setScreen(parent);
    }

    public enum QRState {
        PRELOAD,
        LOADING,
        ERROR,
        WAIT,
        EXPIRE,
        SCAN_SUCCESS
    }

    public enum LoginMethod {
        EMAIL,
        PHONE
    }
}

package top.hmjmfabc.projector.compat.betterlogin.client.gui.widget;

import top.hmjmfabc.projector.compat.betterlogin.NetMusicBetterLogin;
import top.hmjmfabc.projector.compat.betterlogin.client.gui.function.RenderConsumer;
import top.hmjmfabc.projector.compat.betterlogin.util.QRUtil;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.function.BiConsumer;

public class QRCodeImage extends DynamicImage {
    protected BiConsumer<Double, Double> onClick;
    protected RenderConsumer onRender;

    public QRCodeImage(int pX, int pY, int pWidth, int pHeight, BiConsumer<Double, Double> onClick) {
        super(pX, pY, pWidth, pHeight, ResourceLocation.fromNamespaceAndPath(NetMusicBetterLogin.MODID, "qr_code" + System.nanoTime()), state -> switch (state) {
            case LOADING -> Component.translatable("gui.netmusicbetterlogin.qr_code.loading");
            case ERROR -> Component.translatable("gui.netmusicbetterlogin.qr_code.load_failed");
            default -> Component.empty();
        });
        this.onClick = onClick;
    }

    public void setOnRender(RenderConsumer onRender) {
        this.onRender = onRender;
    }

    public void updateQRCode(String content) {
        try {
            setState(LoadingState.LOADING);
            setImage(QRUtil.generateQRCode(content));
            setState(LoadingState.LOADED);
        } catch (Exception e) {
            NetMusicBetterLogin.LOGGER.error("Failed to generate QR code image", e);
            setState(LoadingState.ERROR);
        }
    }

    @Override
    protected void renderWidget(GuiGraphics guiGraphics, int pMouseX, int pMouseY, float pPartialTick) {
        super.renderWidget(guiGraphics, pMouseX, pMouseY, pPartialTick);
        if (onRender != null) {
            onRender.accept(guiGraphics, pMouseX, pMouseY, pPartialTick);
        }
    }

    @Override
    public void onClick(double pMouseX, double pMouseY) {
        if (onClick != null) {
            onClick.accept(pMouseX, pMouseY);
        }
    }
}

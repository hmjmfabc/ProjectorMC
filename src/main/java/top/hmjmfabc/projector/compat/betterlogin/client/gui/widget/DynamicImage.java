package top.hmjmfabc.projector.compat.betterlogin.client.gui.widget;

import top.hmjmfabc.projector.compat.betterlogin.util.RenderUtil;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Function;

public class DynamicImage extends AbstractWidget implements AutoCloseable {
    public static final Function<LoadingState, Component> DEFAULT_TIP_SUPPLIER = state -> switch (state) {
        case LOADING -> Component.translatable("gui.netmusicbetterlogin.image.loading");
        case ERROR -> Component.translatable("gui.netmusicbetterlogin.image.load_failed");
        default -> Component.empty();
    };
    protected DynamicTexture texture = new DynamicTexture(1, 1, false);
    protected ResourceLocation path;
    protected Component tip = Component.empty();
    protected Function<LoadingState, Component> tipSupplier;
    protected LoadingState loadingState;

    public DynamicImage(int pX, int pY, int pWidth, int pHeight, ResourceLocation path, Function<LoadingState, Component> tipSupplier) {
        super(pX, pY, pWidth, pHeight, Component.empty());
        this.path = path;
        this.tipSupplier = tipSupplier;
        setState(LoadingState.LOADING);
        Minecraft.getInstance().getTextureManager().register(path, texture);
    }

    @Override
    protected void renderWidget(GuiGraphics guiGraphics, int pMouseX, int pMouseY, float pPartialTick) {
        if (loadingState.equals(LoadingState.LOADED)) {
            guiGraphics.blit(path, getX(), getY(), 0, 0, getWidth(), getHeight(), getWidth(), getHeight());
        } else {
            guiGraphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), 0xFFFFFFFF);
            RenderUtil.drawCenteredStringNoShadow(guiGraphics, Minecraft.getInstance().font, tip, getX() + getWidth() / 2, getY() + getHeight() / 2, 0xFF000000);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
    }

    public void setImage(NativeImage nativeImage) {
        texture.setPixels(nativeImage);
        RenderSystem.recordRenderCall(() -> {
            texture.releaseId();
            TextureUtil.prepareImage(texture.getId(), nativeImage.getWidth(), nativeImage.getHeight());
            texture.upload();
        });
    }

    public void setState(LoadingState loadingState) {
        this.loadingState = loadingState;
        tip = tipSupplier.apply(loadingState);
    }

    public LoadingState getLoadingState() {
        return loadingState;
    }

    @Override
    public void close() {
        Minecraft.getInstance().submit(() -> {
            Minecraft.getInstance().getTextureManager().release(path);
            texture.close();
        });
    }

    public enum LoadingState {
        LOADING,
        LOADED,
        ERROR
    }
}

package top.hmjmfabc.projector.client.media;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * 图片解码工具（基于 stb_image，Minecraft 自带，无任何额外依赖）。
 *
 * <p>输出统一为 {@code int[]}，每个元素是 {@code 0xAABBGGRR}（即
 * {@code NativeImage} / OpenGL 期望的 ABGR 排布），方便直接灌进纹理。</p>
 */
public final class ImageCodec {

    private ImageCodec() {
    }

    /** 解码结果。 */
    public record Decoded(int width, int height, int[] argb) {
    }

    /** 解码任意格式图片（PNG/JPG/BMP/TGA/GIF 首帧）到 ABGR 数组。 */
    @Nullable
    public static Decoded decode(byte[] data) {
        return decode(data, 0, data.length);
    }

    @Nullable
    public static Decoded decode(byte[] data, int offset, int length) {
        if (data == null || length <= 0) return null;
        ByteBuffer src = MemoryUtil.memAlloc(length);
        ByteBuffer pixels = null;
        IntBuffer w = MemoryUtil.memAllocInt(1);
        IntBuffer h = MemoryUtil.memAllocInt(1);
        IntBuffer comp = MemoryUtil.memAllocInt(1);
        try {
            src.put(data, offset, length);
            src.flip();
            pixels = STBImage.stbi_load_from_memory(src, w, h, comp, 4);
            if (pixels == null) {
                return null;
            }
            int width = w.get(0);
            int height = h.get(0);
            if (width <= 0 || height <= 0 || (long) width * height > 64L * 1024 * 1024) {
                return null;
            }
            int[] argb = new int[width * height];
            for (int i = 0, p = 0; i < argb.length; i++, p += 4) {
                int r = pixels.get(p) & 0xFF;
                int g = pixels.get(p + 1) & 0xFF;
                int b = pixels.get(p + 2) & 0xFF;
                int a = pixels.get(p + 3) & 0xFF;
                argb[i] = (a << 24) | (b << 16) | (g << 8) | r;
            }
            return new Decoded(width, height, argb);
        } catch (Throwable t) {
            return null;
        } finally {
            if (pixels != null) {
                STBImage.stbi_image_free(pixels);
            }
            MemoryUtil.memFree(src);
            MemoryUtil.memFree(w);
            MemoryUtil.memFree(h);
            MemoryUtil.memFree(comp);
        }
    }

    /** 只读取图片尺寸，不解码像素。 */
    @Nullable
    public static int[] size(byte[] data, int offset, int length) {
        if (data == null || length <= 0) return null;
        ByteBuffer src = MemoryUtil.memAlloc(length);
        IntBuffer w = MemoryUtil.memAllocInt(1);
        IntBuffer h = MemoryUtil.memAllocInt(1);
        IntBuffer c = MemoryUtil.memAllocInt(1);
        try {
            src.put(data, offset, length);
            src.flip();
            if (!STBImage.stbi_info_from_memory(src, w, h, c)) {
                return null;
            }
            return new int[]{w.get(0), h.get(0)};
        } catch (Throwable t) {
            return null;
        } finally {
            MemoryUtil.memFree(src);
            MemoryUtil.memFree(w);
            MemoryUtil.memFree(h);
            MemoryUtil.memFree(c);
        }
    }

    /**
     * 最近邻降采样。用于把过大的图片压到显存允许的尺寸内
     * （本机是手机/平板时尤其重要）。
     */
    public static Decoded downscale(Decoded src, int maxSize) {
        if (src == null || maxSize <= 0) return src;
        int w = src.width(), h = src.height();
        if (w <= maxSize && h <= maxSize) return src;
        double scale = Math.min((double) maxSize / w, (double) maxSize / h);
        int nw = Math.max(1, (int) Math.floor(w * scale));
        int nh = Math.max(1, (int) Math.floor(h * scale));
        int[] out = new int[nw * nh];
        for (int y = 0; y < nh; y++) {
            int sy = Math.min(h - 1, (int) (y / scale));
            int srcRow = sy * w;
            int dstRow = y * nw;
            for (int x = 0; x < nw; x++) {
                int sx = Math.min(w - 1, (int) (x / scale));
                out[dstRow + x] = src.argb()[srcRow + sx];
            }
        }
        return new Decoded(nw, nh, out);
    }

    /** 把 abgr 像素写入 NativeImage（需在渲染线程调用）。 */
    public static void fillNativeImage(com.mojang.blaze3d.platform.NativeImage image, Decoded decoded) {
        int w = Math.min(image.getWidth(), decoded.width());
        int h = Math.min(image.getHeight(), decoded.height());
        int[] px = decoded.argb();
        for (int y = 0; y < h; y++) {
            int row = y * decoded.width();
            for (int x = 0; x < w; x++) {
                image.setPixelRGBA(x, y, px[row + x]);
            }
        }
    }

    /** 计算降采样目标尺寸。 */
    public static int[] fit(int w, int h, int maxSize) {
        if (w <= maxSize && h <= maxSize) return new int[]{w, h};
        double scale = Math.min((double) maxSize / w, (double) maxSize / h);
        return new int[]{Math.max(1, (int) (w * scale)), Math.max(1, (int) (h * scale))};
    }
}

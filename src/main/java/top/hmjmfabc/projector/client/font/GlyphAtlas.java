package top.hmjmfabc.projector.client.font;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.system.MemoryUtil;
import top.hmjmfabc.projector.Projector;
import top.hmjmfabc.projector.ProjectorConfig;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 字形图集：把多个字体、多个字号的字形位图集中打包进若干张 RGBA 纹理。
 *
 * <p><b>为什么用 RGBA 而不是直觉上更省的 LUMINANCE：</b>
 * 字形位图本身是覆盖率灰度图，单通道看起来最省显存。但
 * {@code TextureUtil.prepareImage} 把纹理显存固定按 {@code GL_RGBA} 分配，
 * 之后再用 {@code glTexSubImage2D(..., GL_LUMINANCE, ...)} 上传，采样得到的是
 * {@code (L, L, L, 1)} —— <b>alpha 恒为 1</b>。于是字形里那一大片「覆盖率 0」的
 * 背景区域会以不透明黑块的形式画出来，而不是透明。部分 Android 端图形转发层
 * （GL4ES / VirGL / Zink）对 {@code GL_LUMINANCE} 子图像上传的处理还不一致，
 * 更会出现「顶点 / UV / 顶点色全部正确，屏幕上一个像素也没有」的情况。</p>
 *
 * <p>改成 RGBA 后：RGB 恒为白，Alpha 存覆盖率。采样得到 {@code (1,1,1,覆盖度)}，
 * 乘上顶点色就是「带抗锯齿边缘的任意颜色文字」，任何
 * {@code fragColor = texture(...) * vertexColor} 的着色器都能正确解释。
 * 一页 1024² 占 4 MB 显存，在 3500 MB 的内存预算内完全可接受。</p>
 *
 * <p>采取「shelf（货架）装箱」策略：字形按行从左到右摆放，一行放不下就换行。
 * 简单、零碎片、分配 O(1)，非常适合字形这种尺寸相近的小图块。</p>
 */
public final class GlyphAtlas {

    /** 每个字形四周留出的空隙（像素），避免线性过滤时采样到邻居。 */
    private static final int PADDING = 2;

    private final int pageSize;
    private final int maxPages;
    private final List<Page> pages = new ArrayList<>();
    private boolean warned;

    public GlyphAtlas(int pageSize, int maxPages) {
        this.pageSize = pageSize;
        this.maxPages = maxPages;
        // 【关键】第一页必须立刻创建并注册到 TextureManager：
        // RenderType 在 setupRenderState 时会去纹理管理器取这张图，如果此时页还没建，
        // 就会退化成绑定一个不存在的纹理（表现为「文字/图片全部不渲染」）。
        newPage();
    }

    /** 分配到的图集位置。 */
    public record Slot(int page, int x, int y, int w, int h) {
    }

    private static final class Page {
        final NativeImage image;
        final ResourceLocation location;
        int cursorX;
        int cursorY;
        int rowHeight;
        boolean dirty;

        Page(NativeImage image, ResourceLocation location) {
            this.image = image;
            this.location = location;
        }
    }

    /**
     * 申请一块 {@code w×h} 的位置并把像素拷进去。
     *
     * @param pixels 单通道灰度像素，长度必须 ≥ w*h
     * @return 分配结果；图集已满时返回 null
     */
    public Slot allocate(int w, int h, ByteBuffer pixels) {
        if (w <= 0 || h <= 0 || w > pageSize || h > pageSize) {
            return null;
        }
        for (int i = 0; i < pages.size(); i++) {
            Slot s = tryPlace(pages.get(i), i, w, h, pixels);
            if (s != null) return s;
        }
        if (pages.size() >= maxPages) {
            if (!warned) {
                warned = true;
                Projector.LOGGER.warn("[Projector] 字形图集已满（{} 页），后续字形将回退为方框。可调大配置项 render.glyphCachePages。", maxPages);
            }
            return null;
        }
        Page page = newPage();
        Slot s = tryPlace(page, pages.size() - 1, w, h, pixels);
        if (s == null && !warned) {
            warned = true;
            Projector.LOGGER.warn("[Projector] 单个字形 {}x{} 超过图集页大小 {}", w, h, pageSize);
        }
        return s;
    }

    private Slot tryPlace(Page page, int index, int w, int h, ByteBuffer pixels) {
        int pw = w + PADDING;
        int ph = h + PADDING;
        if (page.cursorX + pw > pageSize) {
            // 换行
            page.cursorX = 0;
            page.cursorY += page.rowHeight;
            page.rowHeight = 0;
        }
        if (page.cursorY + ph > pageSize) {
            return null;
        }
        int x = page.cursorX;
        int y = page.cursorY;
        page.cursorX += pw;
        page.rowHeight = Math.max(page.rowHeight, ph);

        // 写入像素（NativeImage 是自顶向下，字形位图也是自顶向下，方向一致）。
        // NativeImage 的像素值是 ABGR 打包：0xAABBGGRR。这里 R=G=B=0xFF 是
        // 「白色」，所以字节序无论是 RGBA 还是 ABGR 结果都一样，不会踩通道顺序的坑。
        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                int coverage = pixels.get(row * w + col) & 0xFF;
                page.image.setPixelRGBA(x + col, y + row, (coverage << 24) | 0x00FFFFFF);
            }
        }
        page.dirty = true;
        return new Slot(index, x, y, w, h);
    }

    private Page newPage() {
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, pageSize, pageSize, false);
        ResourceLocation loc = Projector.id("dynamic/glyph_atlas_" + pages.size());
        Page page = new Page(image, loc);
        pages.add(page);
        // 注册纹理（必须在渲染线程）
        Minecraft.getInstance().getTextureManager().register(loc, new DynamicTexture(image));
        Projector.LOGGER.debug("[Projector] 新建字形图集页 {} ({}x{})", loc, pageSize, pageSize);
        return page;
    }

    /** 把有改动的页上传到 GPU。每帧调用一次即可。 */
    public void uploadDirty() {
        var tm = Minecraft.getInstance().getTextureManager();
        for (Page page : pages) {
            if (!page.dirty) continue;
            var tex = tm.getTexture(page.location);
            if (tex instanceof DynamicTexture dt) {
                dt.upload();
            }
            page.dirty = false;
        }
    }

    public ResourceLocation locationOf(int page) {
        if (page < 0 || page >= pages.size()) {
            return pages.isEmpty() ? null : pages.get(0).location;
        }
        return pages.get(page).location;
    }

    public int pageCount() {
        return pages.size();
    }

    public void close() {
        var tm = Minecraft.getInstance().getTextureManager();
        for (Page page : pages) {
            try {
                tm.release(page.location);
            } catch (Exception ignored) {
                // 忽略：客户端退出时纹理管理器可能已经关闭
            }
            page.image.close();
        }
        pages.clear();
    }

    public static GlyphAtlas createDefault() {
        int size = ProjectorConfig.INSTANCE.atlasPageSize.get();
        int pages = ProjectorConfig.INSTANCE.glyphCachePages.get();
        return new GlyphAtlas(size, pages);
    }

    /**
     * 估算一个字体在 96px 光栅化下、给定字数需要多少张图集页。
     *
     * <p>96px 的字形约 95×95 像素，一张 1024² 的页只能放 ~100 个字形，
     * 对中文长文本很容易反复换页。这个结果用于界面上提示玩家调大配置。</p>
     */
    public static int estimatePages(int glyphCount) {
        int size = ProjectorConfig.INSTANCE.atlasPageSize.get();
        int perPage = Math.max(1, (size / 97) * (size / 97));
        return (glyphCount + perPage - 1) / perPage;
    }

    /** 原生内存工具：申请一块临时缓冲用于字形光栅化。 */
    public static ByteBuffer allocTemp(int bytes) {
        return MemoryUtil.memAlloc(Math.max(16, bytes));
    }

    public static void freeTemp(ByteBuffer buf) {
        if (buf != null) {
            MemoryUtil.memFree(buf);
        }
    }
}

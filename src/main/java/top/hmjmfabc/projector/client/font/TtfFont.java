package top.hmjmfabc.projector.client.font;

import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTruetype;
import org.lwjgl.system.MemoryUtil;
import top.hmjmfabc.projector.Projector;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * 一个已载入的 TrueType 字体。
 *
 * <p>实现方式：用 LWJGL 自带的 <b>stb_truetype</b>（Minecraft 本身就依赖它，
 * 不引入任何额外库、也不依赖系统字体服务）把字体文件解析成内存结构，
 * 再按需把用到的码点光栅化成灰度位图，交给 {@link GlyphAtlas} 打包。</p>
 *
 * <p><b>为什么固定用 96px 光栅化？</b>MC 里的字号范围很宽（0.5 格到 8 格），
 * 如果按实际屏幕字号逐个光栅化会产生几十套字形、浪费显存；固定一个偏大的
 * 光栅尺寸再用 mipmap + 线性过滤缩放，既能保证放大时不糊，又能让缩小后的
 * 抗锯齿由 mipmap 完成，同时保持每次渲染只绑定一张纹理。</p>
 */
public final class TtfFont implements AutoCloseable {

    /** 光栅化像素高度。 */
    public static final float RASTER_PX = 96.0f;

    private final String id;
    private final String displayName;
    private ByteBuffer fileData;
    private final STBTTFontinfo info;
    private final float scale;
    private final float ascent;
    private final float descent;
    private final float lineGap;
    /** 共享的 1 字节零缓冲，供空白字形占位使用。 */
    private static final ByteBuffer EMPTY_PIXEL = MemoryUtil.memAlloc(1);
    /** 图集已满等原因导致无法缓存的码点，避免反复重试与泄漏。 */
    private final java.util.Set<Integer> failed = new java.util.HashSet<>();
    private final Map<Integer, GlyphAtlas.Slot> slots = new HashMap<>();
    private final Map<Integer, Metrics> metrics = new HashMap<>();
    private final Map<Long, Float> kerning = new HashMap<>();
    private ByteBuffer rasterBuf;
    private boolean closed;

    /** 字形度量（单位：光栅化像素）。 */
    public record Metrics(float advance, float bearingX, float bearingY, float width, float height) {
    }

    private TtfFont(String id, String displayName, ByteBuffer fileData) throws IOException {
        this.id = id;
        this.displayName = displayName;
        this.fileData = fileData;
        this.info = STBTTFontinfo.malloc();
        boolean ok = STBTruetype.stbtt_InitFont(info, fileData);
        if (!ok) {
            // 不是标准 sfnt（可能是 TTC / WOFF / 损坏文件）：尝试从字体集合的第 0 个偏移开始
            int offset = STBTruetype.stbtt_GetFontOffsetForIndex(fileData, 0);
            if (offset >= 0) {
                ok = STBTruetype.stbtt_InitFont(info, fileData, offset);
            }
        }
        if (!ok) {
            // 关键：初始化失败后 stbtt 内部字段全是 0，继续使用会得到 0/NaN 的字形度量，
            // 进而把 NaN 顶点塞进顶点缓冲。必须在这里就失败。
            info.free();
            MemoryUtil.memFree(fileData);
            this.fileData = null;
            throw new IOException("无法解析字体文件（不是有效的 TrueType/OpenType 字体）");
        }
        this.scale = STBTruetype.stbtt_ScaleForPixelHeight(info, RASTER_PX);
        if (!(this.scale > 0f) || !Float.isFinite(this.scale)) {
            info.free();
            MemoryUtil.memFree(fileData);
            this.fileData = null;
            throw new IOException("字体度量异常（scale=" + this.scale + "）");
        }
        IntBuffer a = MemoryUtil.memAllocInt(1);
        IntBuffer d = MemoryUtil.memAllocInt(1);
        IntBuffer g = MemoryUtil.memAllocInt(1);
        STBTruetype.stbtt_GetFontVMetrics(info, a, d, g);
        this.ascent = a.get(0) * scale;
        this.descent = d.get(0) * scale;
        this.lineGap = g.get(0) * scale;
        MemoryUtil.memFree(a);
        MemoryUtil.memFree(d);
        MemoryUtil.memFree(g);
        Projector.LOGGER.debug("[Projector] 字体已载入: {} (ascent={}, descent={}, lineGap={})",
                displayName, this.ascent, this.descent, this.lineGap);
    }

    /** 从字节数组载入。 */
    public static TtfFont load(String id, String displayName, byte[] data) throws IOException {
        if (data == null || data.length < 16) {
            throw new IOException("字体数据过短");
        }
        ByteBuffer buf = MemoryUtil.memAlloc(data.length);
        buf.put(data);
        buf.flip();
        return new TtfFont(id, displayName, buf);
    }

    /** 从输入流载入（模组内置字体走这条路径）。 */
    public static TtfFont load(String id, String displayName, InputStream in) throws IOException {
        return load(id, displayName, in.readAllBytes());
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    /** 换算系数：字体单位 -> 光栅化像素。 */
    public float scale() {
        return scale;
    }

    public float ascentPx() {
        return ascent;
    }

    public float descentPx() {
        return descent;
    }

    public float lineHeightPx() {
        return ascent - descent + lineGap;
    }

    /** 某个码点的水平前进量（光栅化像素）。 */
    public float advance(int codePoint) {
        return measure(codePoint).advance();
    }

    public float kerning(int left, int right) {
        long key = ((long) left << 32) | (right & 0xFFFFFFFFL);
        Float k = kerning.get(key);
        if (k != null) return k;
        int raw = STBTruetype.stbtt_GetCodepointKernAdvance(info, left, right);
        float v = raw * scale;
        kerning.put(key, v);
        return v;
    }

    /**
     * 取得（必要时光栅化）某个码点的字形信息。
     *
     * @return 字形槽位；字体不含该码点或图集已满时返回 null
     */
    public GlyphAtlas.Slot slotOf(int codePoint, GlyphAtlas atlas) {
        if (closed) return null;
        GlyphAtlas.Slot slot = slots.get(codePoint);
        if (slot != null) return slot;
        if (failed.contains(codePoint)) return null;
        Metrics m = measure(codePoint);
        if (m == null) return null;
        int w = (int) Math.ceil(m.width());
        int h = (int) Math.ceil(m.height());
        if (w <= 0 || h <= 0) {
            // 空白字形（例如空格）：用 1x1 占位即可。
            // 使用共享的静态缓冲，避免每遇到一个空白字形就泄漏一块原生内存。
            GlyphAtlas.Slot empty = atlas.allocate(1, 1, EMPTY_PIXEL);
            if (empty != null) {
                slots.put(codePoint, empty);
            } else {
                failed.add(codePoint);
            }
            return empty;
        }
        int need = w * h;
        if (rasterBuf == null || rasterBuf.capacity() < need) {
            if (rasterBuf != null) MemoryUtil.memFree(rasterBuf);
            rasterBuf = MemoryUtil.memAlloc(need);
        }
        rasterBuf.clear();
        STBTruetype.stbtt_MakeGlyphBitmapSubpixel(info, rasterBuf, w, h, w, scale, scale, 0f, 0f, codePointToGlyph(codePoint));
        GlyphAtlas.Slot placed = atlas.allocate(w, h, rasterBuf);
        if (placed != null) {
            slots.put(codePoint, placed);
        } else {
            failed.add(codePoint);
        }
        return placed;
    }

    /** 直接查询字形位图（不做缓存），供导出的缩略图等场景使用。 */
    public Metrics measure(int codePoint) {
        Metrics m = metrics.get(codePoint);
        if (m != null) return m;
        IntBuffer adv = MemoryUtil.memAllocInt(1);
        IntBuffer lsb = MemoryUtil.memAllocInt(1);
        STBTruetype.stbtt_GetCodepointHMetrics(info, codePoint, adv, lsb);
        IntBuffer x0 = MemoryUtil.memAllocInt(1);
        IntBuffer y0 = MemoryUtil.memAllocInt(1);
        IntBuffer x1 = MemoryUtil.memAllocInt(1);
        IntBuffer y1 = MemoryUtil.memAllocInt(1);
        STBTruetype.stbtt_GetCodepointBitmapBoxSubpixel(info, codePoint, scale, scale, 0f, 0f, x0, y0, x1, y1);
        Metrics out = new Metrics(
                adv.get(0) * scale,
                x0.get(0),
                -y1.get(0),
                x1.get(0) - x0.get(0),
                y1.get(0) - y0.get(0));
        MemoryUtil.memFree(adv);
        MemoryUtil.memFree(lsb);
        MemoryUtil.memFree(x0);
        MemoryUtil.memFree(y0);
        MemoryUtil.memFree(x1);
        MemoryUtil.memFree(y1);
        metrics.put(codePoint, out);
        return out;
    }

    private int codePointToGlyph(int codePoint) {
        return STBTruetype.stbtt_FindGlyphIndex(info, codePoint);
    }

    /** 该字体的「缺失字形」方框（用于字体不含某字时的占位）。 */
    public int missingGlyphBox() {
        return 0xFFFD;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        slots.clear();
        failed.clear();
        metrics.clear();
        kerning.clear();
        if (rasterBuf != null) {
            MemoryUtil.memFree(rasterBuf);
            rasterBuf = null;
        }
        info.free();
        MemoryUtil.memFree(fileData);
    }
}

package top.hmjmfabc.projector.client.font;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import top.hmjmfabc.projector.Projector;
import top.hmjmfabc.projector.client.media.LocalMedia;
import top.hmjmfabc.projector.common.text.TextLayout;
import top.hmjmfabc.projector.common.widget.Fonts;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 字体管理器。
 *
 * <p>管理三类字体：</p>
 * <ul>
 *   <li><b>内置</b>：{@code minecraft.ttf}（Minecraft AE，默认）与
 *       {@code caviar_dreams.ttf}（Caviar Dreams，百分比控件专用）。
 *       两者都随模组打包在 {@code assets/projector/font/} 下，
 *       即使玩家没有对应文件也能正常工作。</li>
 *   <li><b>导入</b>：玩家把 {@code .ttf/.otf} 放进
 *       {@code .minecraft/projector/fonts/}，在界面里点「刷新字体」即可使用；
 *       在服务器上则需要 OP(4) 上传后由服务端统一下发。</li>
 * </ul>
 *
 * <p>所有字体共用同一个 {@link GlyphAtlas}，因此切换字体不会额外增加纹理绑定次数。</p>
 */
public final class FontManager {

    /**
     * 内置字体在 jar 中的路径。
     *
     * <p>注意：{@code ResourceLocation} 的路径是「命名空间相对」的，
     * 完整查找路径会拼成 {@code assets/<namespace>/<path>}。
     * 所以这里必须写成 {@code projector:font/minecraft.ttf}，
     * 而不是带上 {@code assets/} 前缀——否则会被当成 {@code minecraft:assets/...} 而永远找不到。</p>
     */
    public static final String BUILTIN_MINECRAFT_ID = "font/minecraft.ttf";
    public static final String BUILTIN_CAVIAR_ID = "font/caviar_dreams.ttf";

    private static final Map<String, TtfFont> FONTS = new LinkedHashMap<>();
    private static final Map<String, String> DISPLAY_NAMES = new LinkedHashMap<>();
    private static GlyphAtlas atlas;
    private static boolean builtinsLoaded;

    private FontManager() {
    }

    public static GlyphAtlas atlas() {
        if (atlas == null) {
            atlas = GlyphAtlas.createDefault();
        }
        return atlas;
    }

    /** 初始化内置字体（客户端启动时调用一次）。 */
    public static void loadBuiltins() {
        if (builtinsLoaded) return;
        builtinsLoaded = true;
        register(Fonts.MINECRAFT_AE, "Minecraft AE", BUILTIN_MINECRAFT_ID);
        register(Fonts.CAVIAR_DREAMS, "Caviar Dreams", BUILTIN_CAVIAR_ID);
        refreshCustom();
        for (var e : FONTS.entrySet()) {
            TtfFont f = e.getValue();
            Projector.LOGGER.info("[Projector] 字体 {}: {}，行高 {}px（{}px 光栅化）",
                    e.getKey(), f.displayName(),
                    String.format(java.util.Locale.ROOT, "%.1f", f.lineHeightPx()),
                    String.format(java.util.Locale.ROOT, "%.0f", TtfFont.RASTER_PX));
        }
        Projector.LOGGER.info("[Projector] 字体载入结果: 成功 {} 个 / 期望 2 个内置 —— {}",
                FONTS.size(), FONTS.isEmpty()
                        ? "!! 没有可用字体，所有文字都不会显示（请看上面的错误日志）"
                        : "可用: " + String.join(", ", FONTS.keySet()));
        // 图集页必须在这里就已经建好并注册纹理，否则渲染时会去加载不存在的纹理而整批失败
        // 先取一次 atlas()（会顺带建好第一页），再看页数，避免打出误导性的 "0 页"
        net.minecraft.resources.ResourceLocation atlas0 =
                atlas() == null ? null : atlas().locationOf(0);
        Projector.LOGGER.info("[Projector] 字形图集: {} 页 x {}px, 第一页纹理={}",
                atlasPages(), top.hmjmfabc.projector.ProjectorConfig.INSTANCE.atlasPageSize.get(),
                atlas0 == null ? "null（文字将无法渲染！）" : atlas0.toString());
    }

    private static void register(String id, String display, String resourcePath) {
        if (FONTS.containsKey(id)) return;
        try {
            ResourceLocation loc = ResourceLocation.fromNamespaceAndPath(Projector.MODID, resourcePath);
            var res = net.minecraft.client.Minecraft.getInstance().getResourceManager().getResource(loc);
            if (res.isEmpty()) {
                Projector.LOGGER.error("[Projector] 内置字体缺失: {}（请确认 jar 中包含 assets/projector/{}）",
                        loc, resourcePath);
                return;
            }
            try (InputStream in = res.get().open()) {
                TtfFont font = TtfFont.load(id, display, in);
                FONTS.put(id, font);
                DISPLAY_NAMES.put(id, display);
                Projector.LOGGER.info("[Projector] 内置字体就绪: {} ({})", display, id);
            }
        } catch (Exception ex) {
            Projector.LOGGER.error("[Projector] 载入内置字体失败 {}: {}", resourcePath, ex.toString());
        }
    }

    /** 扫描 {@code .minecraft/projector/fonts/} 下的自定义字体。 */
    public static void refreshCustom() {
        Path dir = LocalMedia.fontDir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                String name = p.getFileName().toString();
                String lower = name.toLowerCase(java.util.Locale.ROOT);
                if (!lower.endsWith(".ttf") && !lower.endsWith(".otf")) return;
                String id = Fonts.custom(stripExt(name));
                if (FONTS.containsKey(id)) return;
                try (InputStream in = Files.newInputStream(p)) {
                    TtfFont font = TtfFont.load(id, stripExt(name), in);
                    FONTS.put(id, font);
                    DISPLAY_NAMES.put(id, stripExt(name));
                    Projector.LOGGER.info("[Projector] 导入字体就绪: {}", name);
                } catch (Exception ex) {
                    Projector.LOGGER.warn("[Projector] 字体 {} 载入失败: {}", name, ex.toString());
                }
            });
        } catch (IOException ex) {
            Projector.LOGGER.warn("[Projector] 扫描字体目录失败: {}", ex.toString());
        }
    }

    private static String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? name : name.substring(0, i);
    }

    /** 取得字体；找不到时回退到默认字体，再回退到 null。 */
    public static TtfFont get(String id) {
        if (!builtinsLoaded) {
            loadBuiltins();
        }
        if (id == null) {
            return FONTS.get(Fonts.MINECRAFT_AE);
        }
        TtfFont f = FONTS.get(id);
        if (f != null) return f;
        return FONTS.get(Fonts.MINECRAFT_AE);
    }

    /** 严格取得（不回落），用于界面里判断字体是否可用。 */
    public static TtfFont getExact(String id) {
        if (!builtinsLoaded) loadBuiltins();
        return FONTS.get(id);
    }

    /** 字体对应的图集纹理。 */
    public static ResourceLocation atlasTexture(TtfFont font) {
        return atlas() == null ? null : atlas().locationOf(0);
    }

    /**
     * 纯文本渲染使用的字形图集纹理。
     *
     * <p>正常情况下返回我们自己生成的图集；如果它还没就绪（例如资源重载的瞬间），
     * 回退到原版默认字体的图集，这样 {@code RenderType.text(...)} 永远拿到一个
     * 有效的纹理，避免出现「纹理为 null」而导致的渲染崩溃或黑块。</p>
     */
    public static ResourceLocation fallbackAtlas() {
        GlyphAtlas a = atlas();
        if (a != null) {
            ResourceLocation own = a.locationOf(0);
            if (own != null) {
                return own;
            }
        }
        // 走到这里说明连图集都没建起来，只能退回原版字体图集（它一定存在）
        return net.minecraft.client.gui.font.FontManager.MISSING_FONT;
    }

    public static List<String> availableIds() {
        if (!builtinsLoaded) loadBuiltins();
        return new ArrayList<>(FONTS.keySet());
    }

    public static String displayName(String id) {
        if (!builtinsLoaded) loadBuiltins();
        String n = DISPLAY_NAMES.get(id);
        return n == null ? id : n;
    }

    public static int count() {
        return FONTS.size();
    }

    /** 图集已用页数（用于诊断 / 调优）。 */
    public static int atlasPages() {
        return atlas == null ? 0 : atlas.pageCount();
    }

    /** 每帧调用：把新光栅化的字形上传到 GPU。 */
    public static void uploadDirty() {
        if (atlas != null) {
            atlas.uploadDirty();
        }
    }

    /** 资源包重载 / 断开连接时释放。 */
    public static void shutdown() {
        for (TtfFont f : FONTS.values()) {
            try {
                f.close();
            } catch (Exception ignored) {
            }
        }
        FONTS.clear();
        DISPLAY_NAMES.clear();
        if (atlas != null) {
            atlas.close();
            atlas = null;
        }
        builtinsLoaded = false;
    }

    // ------------------------------------------------------------------
    // 适配 TextLayout
    // ------------------------------------------------------------------

    /** 把 TtfFont 适配成排版器需要的度量接口。 */
    public static TextLayout.FontMetrics metrics(TtfFont font) {
        return new TextLayout.FontMetrics() {
            @Override
            public float rasterPx() {
                return TtfFont.RASTER_PX;
            }

            @Override
            public float ascentPx() {
                return font.ascentPx();
            }

            @Override
            public float descentPx() {
                return font.descentPx();
            }

            @Override
            public float lineHeightPx() {
                return font.lineHeightPx();
            }

            @Override
            public float advance(int codePoint) {
                return font.advance(codePoint);
            }

            @Override
            public float kerning(int left, int right) {
                return font.kerning(left, right);
            }

            @Override
            public float[] glyphBox(int codePoint) {
                TtfFont.Metrics m = font.measure(codePoint);
                if (m == null) {
                    return null;
                }
                // 注意：空格这类字形宽度/高度为 0 但前进量不为 0，
                // 仍然要返回度量（渲染层会按「无像素字形」跳过），否则会被画成占位方框。
                if (m.width() <= 0 && m.height() <= 0 && m.advance() <= 0) {
                    return null;
                }
                return new float[]{m.bearingX(), m.bearingY(), m.width(), m.height()};
            }
        };
    }

    /** 直接取字形的 UV 与页号（排版回调里用）。 */
    public static GlyphAtlas.Slot slot(TtfFont font, int codePoint) {
        return font.slotOf(codePoint, atlas());
    }

    /** 用于界面预览：读取某个字体文件的字节（未注册时也能用）。 */
    public static TtfFont loadTransient(String id, String display, byte[] data) throws IOException {
        return TtfFont.load(id, display, data);
    }

    /** 兼容不同版本 Resource 类型的小工具（保留以便扩展）。 */
    public static InputStream openResource(Resource resource) throws IOException {
        return resource.open();
    }
}

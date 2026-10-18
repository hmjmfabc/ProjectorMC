package top.hmjmfabc.projector.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import top.hmjmfabc.projector.common.PlaneCanvas;
import top.hmjmfabc.projector.common.widget.ItemWidget;

/**
 * 【27.2.2】展示框的渲染：**轮廓 + 物品贴图**。
 *
 * <h2>怎么把「物品」弄到平面上</h2>
 * 项目里从来没有离屏渲染（没有 {@code RenderTarget} 的先例），而安卓启动器的 GL 是转发层，
 * 自己开 FBO 截图不可靠。所以这里走**最稳的一条**：直接问物品的**烘焙模型**要贴图
 * （{@code getParticleIcon()} = 该物品在物品栏里显示的那张图），
 * 把它按「轮廓矩形 + 缩放 + 旋转」贴到平面上 —— 与图片控件走同一条管线（{@code RenderType.text}）。
 *
 * <p>于是「轮廓内显示该物品的完整贴图，大小与轮廓完全一致」就是：
 * 贴图矩形的外接尺寸 = 轮廓尺寸 × {@link ItemWidget#iconScale}（默认 1.0 = 完全一致）。</p>
 *
 * <h2>轮廓</h2>
 * 一圈由 {@link ItemWidget#outlineColor} 与 {@link ItemWidget#outlineWidth} 决定的方框，
 * 画在贴图**下面**一层（与棋盘白边同一套观感）。空框时也画（提示玩家这里能放东西）。
 */
public final class ItemWidgetRenderer {

    private ItemWidgetRenderer() {
    }

    /** 同一控件内的层间距（方块）。⚠ 必须 ≥0.015，否则共面层会互相闪烁（老规矩）。 */
    private static final double LAYER = 0.015;

    /** 把注册名解析成 ItemStack（解析不出来返回空栈）。纯查表，不碰世界。 */
    public static ItemStack stackOf(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return ItemStack.EMPTY;
        }
        try {
            ResourceLocation id = ResourceLocation.tryParse(itemId.trim());
            if (id == null) {
                return ItemStack.EMPTY;
            }
            Item item = BuiltInRegistries.ITEM.get(id);
            if (item == null || item == Items.AIR) {
                return ItemStack.EMPTY;
            }
            return new ItemStack(item);
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    /** 这个物品在物品栏里的那张图（取不到返回 null）。 */
    public static TextureAtlasSprite spriteOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            var model = mc.getItemRenderer().getModel(stack, null, null, 0);
            var sprite = model.getParticleIcon();
            return sprite == null ? null : sprite;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 画整个展示框（轮廓 + 贴图 + 进出的光球）。 */
    public static void draw(QuadCollector collector, PlaneRenderContext ctx, ItemWidget w) {
        if (w.w <= 1 || w.h <= 1) {
            return;
        }
        float alpha = iconAlpha(w);
        ItemStack stack = stackOf(w.itemId);
        TextureAtlasSprite sprite = stack.isEmpty() ? null : spriteOf(stack);
        double size = iconSize(w);
        // 【27.2.2-159 用户口径】**边框粗细固定 = 物品贴图的一个像素**，不给加粗。
        //   ①「默认边框实在太厚了」：老存档里的 outlineW 还是旧语义（画布单位，旧默认 1.2），
        //      它会在「一个像素」之外再往外扩 1.2 ⇒ 实际有两三个像素粗。现在直接不用它了。
        //   ② 描出来的每一段本来就是**一个贴图像素**（段坐标是归一化的，所以 16×16 与 32×32
        //      的贴图都一样准），所以「不加粗」就是「正好最外面那一圈像素」。
        final double grow = 0.0;
        // 【27.2.2-159 用户口径】**边框不要渐显/渐隐** —— 它是框本身，一直在；
        // 会渐显渐隐的只有框里的物品（`alpha`）。所以这里**不乘** alpha。
        int outline = QuadCollector.withAlpha(w.outlineColor, w.alpha);

        // ① 贴图（放入时渐显、取出时渐隐）—— 先画，轮廓要压在它**上面**
        if (sprite != null && alpha > 0.001f) {
            int tint = QuadCollector.withAlpha(0xFFFFFFFF, w.alpha * alpha);
            drawIconQuad(collector, ctx, w, sprite, size, tint, ctx.depth());
        }
        // ② 轮廓。【27.2.2-157 用户口径】「我要的是**最外面那一圈像素**，而且是可以调色的，
        //    默认是白色」。所以：描的是贴图的**外轮廓**（见 outlineRuns），
        //    **画在贴图上面**一层 —— 这样它就是真正的「描边」：
        //    哪怕只有一像素粗也看得见，不必靠加粗去露头。
        //    ⚠ 上一版画在贴图**下面**：露在外面的只有「加粗出去的那一点点」，
        //      于是不得不加粗到 1~4 个像素 ⇒ 看着像把整张贴图描了一遍。
        //    ⚠ 再上一版是「贴图放大一圈垫在下面」：未放置时会变成一团**实心**剪影。
        if (!drawOutlineRuns(collector, ctx, w, outline, size, grow)) {
            // 兜底一：拿不到贴图像素（模组物品用了特殊加载器 / 资源还没就绪）
            // ⇒ 退回「放大一圈的副本垫在贴图下面」这种老办法（看得见就行）
            if (sprite != null) {
                // 读不到贴图像素时的兜底：把贴图放大一圈垫在下面（看得见就行）
                // 【27.2.4-200 修】必须比贴图**低一层**：以前这里的 depth 与贴图相同，
                // 而 rendertype_text 的深度测试是 LEQUAL + 后画者胜 ⇒ 这一圈剪影把贴图整个盖住，
                // 读不到贴图像素的模组物品就变成一块实心白块（outlineColor 默认是不透明白）。
                drawIconQuad(collector, ctx, w, sprite, size * 1.12,
                        QuadCollector.withAlpha(w.outlineColor, w.alpha), ctx.depth() - LAYER);
            } else if ((outline >>> 24) != 0) {
                // 兜底二：连物品都没选 ⇒ 一圈细方框告诉玩家「这里能放东西」
                drawEmptyBox(collector, ctx, w, outline);
            }
        }
        // ③ 光球（用户要求：放入/取出时有一个光球进出轮廓，颜色取物品贴图的颜色）
        drawOrb(collector, ctx, w);
    }

    // ------------------------------------------------------------------
    // 【27.2.2-156】物品轮廓：从贴图的 alpha 通道描出边界
    // ------------------------------------------------------------------

    /** 外轮廓的一小段（**归一化**坐标 0..1，y 已翻成「向上为正」）。 */
    public record Run(double x0, double y0, double x1, double y1) {
    }

    /** 描边最多允许多少段；超过就说明这贴图太碎，退回「放大副本」。 */
    private static final int MAX_RUNS = 96;

    private static final java.util.Map<String, java.util.List<Run>> OUTLINE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 【27.2.2-158 真根因】把「精灵名」换成**真正的资源路径**。
     *
     * <p>{@code sprite.contents().name()} 给的是 minecraft:item/diamond 这种**精灵名**
     * （模型引用贴图用的名字），**不是**资源路径 —— 资源管理器要的是
     * minecraft:textures/item/diamond.png。少了 textures/ 前缀和 .png 后缀，
     * {@code getResource} 必然返回空 ⇒ 「描轮廓」与「取物品颜色」两条链**全部静默失败**、
     * 双双走兜底：未放置显示成一圈**方框**、已放置显示成一团**不渐变的染色剪影**
     * （玩家报的「边框又成正方形了 + 渐显渐隐没了」就是这个）。
     * 算法本身没问题，见 {@code tmp/probe/Probe.java} 在真实贴图上的输出。</p>
     *
     * <p>两种形式都试：先按原样找（万一对方已经给了完整路径），再补前缀/后缀找。</p>
     */
    private static java.io.InputStream openTexture(ResourceLocation spriteName) {
        if (spriteName == null) {
            return null;
        }
        for (ResourceLocation candidate : new ResourceLocation[]{spriteName, texturePath(spriteName)}) {
            try {
                var found = Minecraft.getInstance().getResourceManager().getResource(candidate);
                if (found.isPresent()) {
                    return found.get().open();
                }
            } catch (Throwable ignored) {
                // 换下一种形式再试
            }
        }
        return null;
    }

    /** 精灵名 → 资源路径（minecraft:item/diamond → minecraft:textures/item/diamond.png）。 */
    public static ResourceLocation texturePath(ResourceLocation spriteName) {
        String path = spriteName.getPath();
        if (path.startsWith("textures/") && path.endsWith(".png")) {
            return spriteName;
        }
        return ResourceLocation.fromNamespaceAndPath(spriteName.getNamespace(),
                "textures/" + path + ".png");
    }

    /** 读不到贴图时的告警只打一次（按物品 id 去重）。 */
    private static final java.util.Set<String> TEX_WARNED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 这件物品的轮廓（按物品缓存）。
     *
     * <p>取的是贴图**最外面那一圈**像素（外轮廓，内部的镂空不算）——
     *     所以一件普通物品大约 20~40 段，一段画一个四边形，比棋盘（格线 + 九宫斜线 + 棋子）还轻。</p>
     *
     * @return 归一化坐标下的若干段；拿不到像素时返回空表（调用方走兜底）
     */
    public static java.util.List<Run> outlineRuns(ItemWidget w) {
        if (w == null || !w.hasItem()) {
            return java.util.List.of();
        }
        java.util.List<Run> cached = OUTLINE.get(w.itemId);
        if (cached != null) {
            return cached;
        }
        java.util.List<Run> runs = java.util.List.of();
        try {
            ItemStack stack = stackOf(w.itemId);
            TextureAtlasSprite sprite = stack.isEmpty() ? null : spriteOf(stack);
            if (sprite != null && sprite.contents() != null) {
                ResourceLocation name = sprite.contents().name();
                runs = computeRuns(name,
                        Math.max(1, sprite.contents().width()),
                        Math.max(1, sprite.contents().height()));
                if (runs.isEmpty() && TEX_WARNED.add(w.itemId)) {
                    top.hmjmfabc.projector.Projector.LOGGER.warn(
                            "[Projector][\u5c55\u793a\u6846] \u8bfb\u4e0d\u5230\u7269\u54c1\u8d34\u56fe\uff0c"
                                    + "\u8f6e\u5ed3\u4e0e\u53d6\u8272\u9000\u56de\u515c\u5e95\uff1a"
                                    + "\u7269\u54c1={} \u7cbe\u7075\u540d={} \u8bd5\u8fc7={}",
                            w.itemId, name, texturePath(name));
                }
            }
        } catch (Throwable t) {
            top.hmjmfabc.projector.Projector.LOGGER.debug(
                    "[Projector][展示框] 描物品轮廓失败 {}：{}", w.itemId, t.toString());
        }
        // 只缓存成功的结果：资源没就绪时算不出来，下次再算（缓存在这里会一辈子是空轮廓）
        if (!runs.isEmpty()) {
            OUTLINE.put(w.itemId, runs);
        }
        return runs;
    }

    /** 读贴图的前 fw×fh（第一帧）算边界段。 */
    private static java.util.List<Run> computeRuns(ResourceLocation texture, int frameW, int frameH) {
        if (texture == null) {
            return java.util.List.of();
        }
        com.mojang.blaze3d.platform.NativeImage img = null;
        try {
            try (java.io.InputStream in = openTexture(texture)) {
                if (in == null) {
                    return java.util.List.of();
                }
                img = com.mojang.blaze3d.platform.NativeImage.read(in);
            }
            // 动画贴图是一长条，只取第一帧的区域
            int fw = Math.max(1, Math.min(frameW, img.getWidth()));
            int fh = Math.max(1, Math.min(frameH, img.getHeight()));
            boolean[] solid = new boolean[fw * fh];
            for (int y = 0; y < fh; y++) {
                for (int x = 0; x < fw; x++) {
                    solid[y * fw + x] = (img.getPixelRGBA(x, y) >>> 24) >= 32;
                }
            }
            return runsFromMask(solid, fw, fh);
        } catch (Throwable t) {
            return java.util.List.of();
        } finally {
            if (img != null) {
                img.close();          // 堆外内存，必须关（本项目对内存有硬约束）
            }
        }
    }

    /**
     * 【纯函数，脚本可测】把「不透明掩码」变成**最外面那一圈**像素的轮廓段（归一化坐标，y 向上）。
     *
     * <p>用户口径（27.2.2-157）：「我要的是**最外面那一圈像素**，而且是可以调色的，默认是白色」。
     * 所以这里<b>只描外轮廓</b>：</p>
     * <ol>
     *   <li>先从贴图四条边往里**泛洪**，把「外面」的透明区域标出来；</li>
     *   <li>一个不透明像素只要**挨着「外面」**（四邻之一）——或者它自己就贴着贴图边 ——
     *       就算外圈上的一格；</li>
     *   <li>同行里连续的格子合成一段（一段 = 一个四边形）。</li>
     * </ol>
     *
     * <p>⚠ 上一版是把「挨着**任何**透明像素」的格子都算上 ⇒ 物品**内部**的镂空与缝隙
     * （刀柄与刀身之间那道缝之类）也会被描一圈，看起来就像「把贴图本身描了一遍」。
     * 内部镂空不与外面连通，所以现在会被正确地排除掉。</p>
     *
     * @param solid 逐像素的「不透明」掩码，按行排列（第 0 行是**贴图顶部**）
     */
    public static java.util.List<Run> runsFromMask(boolean[] solid, int fw, int fh) {
        if (solid == null || fw <= 0 || fh <= 0 || solid.length < fw * fh) {
            return java.util.List.of();
        }
        // ① 泛洪：标出与贴图边界连通的透明区域（＝「外面」）
        boolean[] outside = new boolean[fw * fh];
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        for (int x = 0; x < fw; x++) {
            pushIfOutside(solid, outside, queue, fw, x, 0);
            pushIfOutside(solid, outside, queue, fw, x, fh - 1);
        }
        for (int y = 0; y < fh; y++) {
            pushIfOutside(solid, outside, queue, fw, 0, y);
            pushIfOutside(solid, outside, queue, fw, fw - 1, y);
        }
        while (!queue.isEmpty()) {
            int idx = queue.poll();
            int x = idx % fw;
            int y = idx / fw;
            pushIfOutside(solid, outside, queue, fw, x - 1, y);
            pushIfOutside(solid, outside, queue, fw, x + 1, y);
            pushIfOutside(solid, outside, queue, fw, x, y - 1);
            pushIfOutside(solid, outside, queue, fw, x, y + 1);
        }
        // ② 外圈 = 不透明 且（贴图边缘 或 四邻里有「外面」）
        java.util.List<Run> out = new java.util.ArrayList<>();
        for (int y = 0; y < fh; y++) {
            int start = -1;
            for (int x = 0; x <= fw; x++) {
                boolean edge = x < fw && solid[y * fw + x] && onOuterRing(outside, fw, fh, x, y);
                if (edge && start < 0) {
                    start = x;
                } else if (!edge && start >= 0) {
                    // 归一化：x 向右、y 向上（PNG 第 0 行在顶部，所以要翻过来）
                    out.add(new Run(start / (double) fw, (fh - 1 - y) / (double) fh,
                            x / (double) fw, (fh - y) / (double) fh));
                    start = -1;
                }
            }
        }
        if (out.size() > MAX_RUNS) {
            return java.util.List.of();
        }
        return out;
    }

    private static void pushIfOutside(boolean[] solid, boolean[] outside,
                                      java.util.ArrayDeque<Integer> queue, int fw, int x, int y) {
        int h = outside.length / fw;
        if (x < 0 || y < 0 || x >= fw || y >= h) {
            return;
        }
        int idx = y * fw + x;
        if (outside[idx] || solid[idx]) {
            return;                     // 已经是「外面」或者是实心 ⇒ 不从这里扩散
        }
        outside[idx] = true;
        queue.add(idx);
    }

    /**
     * 这一格在不在「最外面那一圈」上。
     *
     * <p>说明：贴着贴图边的不透明格子一律算外圈 —— 贴图外面就是世界，
     * 不能因为「泛洪没走到画布外面」就漏掉它们。</p>
     */
    private static boolean onOuterRing(boolean[] outside, int fw, int fh, int x, int y) {
        if (x == 0 || y == 0 || x == fw - 1 || y == fh - 1) {
            return true;
        }
        return outside[y * fw + x - 1] || outside[y * fw + x + 1]
                || outside[(y - 1) * fw + x] || outside[(y + 1) * fw + x];
    }

    /**
     * 把边界段画出来（返回 false = 没有可用的段，调用方走兜底）。
     *
     * @param size 贴图在控件里的边长；归一化坐标先乘它变成「图标空间」的尺寸
     * @param grow 每一段往外扩多少（就是编辑器里的「轮廓线宽」）
     */
    private static boolean drawOutlineRuns(QuadCollector collector, PlaneRenderContext ctx,
                                           ItemWidget w, int color, double size, double grow) {
        if ((color >>> 24) == 0) {
            return false;
        }
        java.util.List<Run> runs = outlineRuns(w);
        if (runs.isEmpty()) {
            return false;
        }
        for (Run r : runs) {
            // 归一化(0..1) → 图标空间（以图标中心为原点）→ 再往外扩 grow
            double x0 = (r.x0() - 0.5) * size - grow;
            double x1 = (r.x1() - 0.5) * size + grow;
            double y0 = (r.y0() - 0.5) * size - grow;
            double y1 = (r.y1() - 0.5) * size + grow;
            drawIconRect(collector, ctx, w, x0, y0, x1, y1, color, ctx.depth() + LAYER);
        }
        return true;
    }

    /** 在「图标空间」（原点 = 图标中心，未叠 iconRot）画一个实心矩形。 */
    private static void drawIconRect(QuadCollector collector, PlaneRenderContext ctx, ItemWidget w,
                                     double x0, double y0, double x1, double y1,
                                     int color, double depth) {
        if ((color >>> 24) == 0 || Math.abs(x1 - x0) < 1.0e-4 || Math.abs(y1 - y0) < 1.0e-4) {
            return;
        }
        double[] px = new double[4];
        double[] py = new double[4];
        double[][] pts = {{x0, y0}, {x1, y0}, {x1, y1}, {x0, y1}};
        for (int i = 0; i < 4; i++) {
            double[] p = iconPoint(w, pts[i][0], pts[i][1]);
            px[i] = p[0];
            py[i] = p[1];
        }
        collector.setRenderType(QuadCollector.imageType(WidgetRenderer.WHITE));
        collector.canvasQuad(ctx.axisX(), ctx.axisY(), ctx.normal(), ctx.origin(),
                px[0], py[0], px[1], py[1], px[2], py[2], px[3], py[3],
                depth, 0f, 0f, 1f, 1f, color);
    }

    /**
     * 「图标空间」的一点 → 画布坐标：先按 {@code iconRot} 绕图标中心转，再叠控件自身的旋转。
     *
     * <p>贴图与轮廓都必须走这一条换算 —— 两套的话，一转「贴图旋转」轮廓就会与物品错开。</p>
     */
    private static double[] iconPoint(ItemWidget w, double lx, double ly) {
        double cx = w.w / 2.0, cy = w.h / 2.0;
        double rad = Math.toRadians(w.iconRot);
        double cos = Math.cos(rad), sin = Math.sin(rad);
        double rx = lx * cos - ly * sin;
        double ry = lx * sin + ly * cos;
        return w.rotPt(cx + rx, cy + ry);
    }

    /** 空框的占位方框（细一圈）。 */
    private static void drawEmptyBox(QuadCollector collector, PlaneRenderContext ctx, ItemWidget w,
                                     int color) {
        // 占位方框的线宽也**不读 outlineWidth**（老存档里那 1.2 是旧语义，会把框顶粗）；
        // 按控件尺寸取一个细比例，屏幕矮/控件小时也不会糊成一团
        double t = Math.max(0.6, Math.min(w.w, w.h) * 0.03);
        double d = ctx.depth();
        rect(collector, ctx, w, 0, 0, w.w, t, color, d);
        rect(collector, ctx, w, 0, w.h - t, w.w, w.h, color, d);
        rect(collector, ctx, w, 0, t, t, w.h - t, color, d);
        rect(collector, ctx, w, w.w - t, t, w.w, w.h - t, color, d);
    }

    /** 贴图（也就是剪影/轮廓那一层）的边长。 */
    private static double iconSize(ItemWidget w) {
        double scale = Math.max(0.05, Math.min(w.iconScale, 4.0));
        return Math.min(w.w, w.h) * scale;
    }

    /** 贴图：按 iconScale 缩放、iconRot 旋转，贴图**中心**对齐轮廓中心。 */
    /** 在控件中心画一个贴图方框（边长 size，按 iconRot 旋转，tint 染色）。 */
    private static void drawIconQuad(QuadCollector collector, PlaneRenderContext ctx, ItemWidget w,
                                     TextureAtlasSprite sprite, double size, int tint, double depth) {
        if ((tint >>> 24) == 0 || size <= 0.2) {
            return;
        }
        double half = size / 2.0;
        double[][] corners = {{-half, -half}, {half, -half}, {half, half}, {-half, half}};
        double[] px = new double[4], py = new double[4];
        for (int i = 0; i < 4; i++) {
            double[] p = iconPoint(w, corners[i][0], corners[i][1]);
            px[i] = p[0];
            py[i] = p[1];
        }
        collector.setRenderType(QuadCollector.imageType(sprite.atlasLocation()));
        collector.canvasQuad(ctx.axisX(), ctx.axisY(), ctx.normal(), ctx.origin(),
                px[0], py[0], px[1], py[1], px[2], py[2], px[3], py[3],
                depth, sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1(), tint);
    }

    // ------------------------------------------------------------------
    // 【27.2.2】进出的光球
    // ------------------------------------------------------------------

    /** 光球一次进出动画的时长（毫秒）。比贴图的渐显/渐隐略长一点，看起来像「被吸进去」。 */
    public static final long ORB_MS = 650L;

    /** 径向渐变的白色光斑贴图（由 {@code ProjectorClient} 在启动时生成并注册）。 */
    public static ResourceLocation GLOW = top.hmjmfabc.projector.Projector.id("dynamic/glow");

    /**
     * 光球：从轮廓**外面**飞进来（放入）/ 飞出去（取出）。
     *
     * <p>它同时承担「告诉玩家这一下点成功了」的职责：光球进到中心时贴图正好亮起来。
     * 因为入场/出场用的是同一条曲线（取出时把进度反过来跑），
     * 所以「放进去」和「拿出来」看起来是一进一出，而不是两段不同的动画。</p>
     */
    private static void drawOrb(QuadCollector collector, PlaneRenderContext ctx, ItemWidget w) {
        long changedAt = CHANGE_MS.getOrDefault(w.id, 0L);
        if (changedAt == 0L) {
            return;
        }
        long now = System.currentTimeMillis();
        long elapsed = now - changedAt;
        if (elapsed < 0 || elapsed > ORB_MS) {
            return;
        }
        if (!QuadCollector.textureReady(GLOW)) {
            // 光斑纹理还没注册好（启动瞬间）：宁可不画，也不要用缺失纹理的花格子糊一脸
            return;
        }
        float t = elapsed / (float) ORB_MS;
        // u = 光球在「从起点到中心」这条路上的进度；取出时反向
        float u = w.placed ? t : 1.0f - t;
        double unit = Math.min(w.w, w.h);
        double cx = w.w / 2.0, cy = w.h / 2.0;
        // 【27.2.2-156 用户口径】**光球的起点是玩家**（不是控件旁边）：
        // 把玩家的位置沿平面法线投影到画布坐标系，从那儿飞进控件。
        // 玩家一般站在平面正前方几格，所以看起来就是「从我这儿飞过去、落进框里」。
        double[] from = playerCanvasPoint(ctx);
        double sx = from != null ? from[0] : w.w + unit * 0.30;
        double sy = from != null ? from[1] : cy;
        // 太远就按方向截断：跨十几格飞过来只会闪一下，看不出是「从玩家出发」
        double dxs = sx - cx, dys = sy - cy;
        double dist = Math.hypot(dxs, dys);
        double cap = Math.max(unit * 3.0, PlaneCanvas.UNITS_PER_BLOCK * 6.0);
        if (dist > cap && dist > 1.0e-3) {
            sx = cx + dxs / dist * cap;
            sy = cy + dys / dist * cap;
            dist = cap;
        }
        double x = sx + (cx - sx) * u;
        double y = sy + (cy - sy) * u;
        // 走一条小弧线，观感上像「抛过来」而不是直愣愣地平移
        y += Math.sin(u * Math.PI) * Math.min(unit * 0.25, dist * 0.15);
        // 【27.2.2-154 用户口径】**光球太大了** —— 上一版最大到控件尺寸的 1.1 倍，
        // 在墙上一整块都被糊住。现在核心最大只有 0.30 倍（≈ 一颗弹珠），
        // 外面再套两层更淡的光晕（那才是「发光」的观感来源）。
        double core = unit * 0.30 * (0.45 + 0.55 * Math.sin(u * Math.PI));
        // 透明度：两端淡出（进出都不会「啪」地出现/消失）
        float fade = (float) Math.sin(Math.min(1.0, Math.max(0.0, u)) * Math.PI);
        float a = w.alpha * fade;
        if (a <= 0.01f || core <= 0.2) {
            return;
        }
        int base = orbColor(w, now, 1.0f) & 0xFFFFFF;
        // 三层叠出「发光」：光晕 → 中层 → 白热的核心。
        // 半透明叠加本身就会往白里推，所以核心不用真的做加色混合
        //（本项目刻意不新增 RenderType —— entityTranslucent 在安卓转发层上翻过车）。
        drawGlow(collector, ctx, w, x, y, core * 2.4, mix(base, 0xFFFFFF, 0.20f), a * 0.22f);
        drawGlow(collector, ctx, w, x, y, core * 1.5, mix(base, 0xFFFFFF, 0.45f), a * 0.45f);
        drawGlow(collector, ctx, w, x, y, core, mix(base, 0xFFFFFF, 0.85f), a);
    }

    /**
     * 玩家在**画布坐标系**里的位置（沿平面法线投影到平面上）。
     *
     * <p>纯几何：{@code ctx.origin()} 就是画布 (0,0) 的世界坐标，
     * 两个画布轴是单位向量 ⇒ 把玩家位置减去原点再往两根轴上投影即可，
     * 乘 {@link PlaneCanvas#UNITS_PER_BLOCK} 换算成画布单位。</p>
     *
     * <p>⚠ 刻意**只投影、不做别的**：一开始想过让光球在三维里从玩家飞向墙面
     * （连 depth 一起插值），但那样玩家站在平面背后时 depth 变负、光球会整段被墙挡住，
     * 而且中途的建筑还会把它切掉。现在球始终贴在平面这一层上飞，
     * 起点仍严格是玩家的位置投影 —— 既好懂又不会出岔子。</p>
     *
     * @return {画布 x, 画布 y}；没有玩家（加载界面等）时返回 null
     */
    private static double[] playerCanvasPoint(PlaneRenderContext ctx) {
        try {
            var mc = Minecraft.getInstance();
            if (mc.player == null) {
                return null;
            }
            var eye = mc.player.getEyePosition(1.0f);
            double[] o = ctx.origin();
            double[] ax = ctx.axisX();
            double[] ay = ctx.axisY();
            double dx = eye.x - o[0], dy = eye.y - o[1], dz = eye.z - o[2];
            double u = dx * ax[0] + dy * ax[1] + dz * ax[2];
            double v = dx * ay[0] + dy * ay[1] + dz * ay[2];
            return new double[]{u * PlaneCanvas.UNITS_PER_BLOCK, v * PlaneCanvas.UNITS_PER_BLOCK};
        } catch (Throwable t) {
            return null;
        }
    }

    /** 画一层光斑（一个四边形，用径向渐变的 GLOW 贴图）。 */
    private static void drawGlow(QuadCollector collector, PlaneRenderContext ctx, ItemWidget w,
                                 double x, double y, double size, int rgb, float alpha) {
        int color = QuadCollector.withAlpha(0xFF000000 | (rgb & 0xFFFFFF), alpha);
        if ((color >>> 24) == 0 || size <= 0.2) {
            return;
        }
        double half = size / 2.0;
        double[][] corners = {{-half, -half}, {half, -half}, {half, half}, {-half, half}};
        double[] px = new double[4], py = new double[4];
        for (int i = 0; i < 4; i++) {
            double[] p = w.rotPt(x + corners[i][0], y + corners[i][1]);
            px[i] = p[0];
            py[i] = p[1];
        }
        collector.setRenderType(QuadCollector.imageType(GLOW));
        collector.canvasQuad(ctx.axisX(), ctx.axisY(), ctx.normal(), ctx.origin(),
                px[0], py[0], px[1], py[1], px[2], py[2], px[3], py[3],
                ctx.depth() + LAYER * 2, 0f, 0f, 1f, 1f, color);
    }

    /** 两个 RGB 按比例混色（t=0 取 a，t=1 取 b）。 */
    public static int mix(int a, int b, float t) {
        float k = Math.max(0f, Math.min(1f, t));
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = Math.round(ar + (br - ar) * k);
        int g = Math.round(ag + (bg - ag) * k);
        int bl = Math.round(ab + (bb - ab) * k);
        return (r << 16) | (g << 8) | bl;
    }

    /**
     * 光球的颜色（用户 27.2.2-154 明确：**要取物品贴图的颜色**）。
     *
     * <p>取法：{@link #itemColor} 采「这张物品贴图里所有不透明像素的加权平均色」
     * （按 alpha 加权）。色相**固定**为采到的那个颜色 —— 这才是玩家说的「取色准」。</p>
     *
     * <p>「动态」体现在<b>亮度</b>上：随时间做 ±12% 的脉动（1.2 秒一个来回），
     * 所以光球是活的，但颜色始终是那件物品的颜色。</p>
     *
     * <p>为什么能采到颜色了：上一版我以为 1.21.1 拿不到贴图像素
     * （{@code SpriteContents.originalImage} 确实是私有的），但**不需要**走它 ——
     * {@code sprite.contents().name()} 给的是**原始贴图文件**的 ResourceLocation，
     * 直接用 {@code ResourceManager} 读那张 PNG 再 {@code NativeImage.read} 就有像素了，
     * 一行反射都不用。</p>
     */
    public static int orbColor(ItemWidget w, long nowMs, float alpha) {
        int rgb = itemColor(w);
        // 亮度脉动：0.88 ~ 1.12
        float pulse = 1.0f + 0.12f * (float) Math.sin(nowMs / 1200.0 * Math.PI * 2.0);
        int r = Math.min(255, Math.round(((rgb >> 16) & 0xFF) * pulse));
        int g = Math.min(255, Math.round(((rgb >> 8) & 0xFF) * pulse));
        int b = Math.min(255, Math.round((rgb & 0xFF) * pulse));
        int a = Math.round(255.0f * Math.max(0f, Math.min(1f, alpha)));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** 物品贴图平均色的缓存（键 = 物品注册名）。 */
    private static final java.util.Map<String, Integer> ITEM_COLOR =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 这件物品的「代表色」：贴图里所有不透明像素的 alpha 加权平均。
     *
     * <p>读不到贴图（模组物品用了特殊加载器、贴图缺失……）时退化成
     * 「按注册名散列出来的色相」，绝不返回黑色 —— 光球变黑看着像坏了。</p>
     */
    public static int itemColor(ItemWidget w) {
        String id = w == null || !w.hasItem() ? "" : w.itemId;
        if (id.isEmpty()) {
            return 0xFFD0D0D0;                       // 空框：中性浅灰
        }
        Integer cached = ITEM_COLOR.get(id);
        if (cached != null) {
            return cached;
        }
        int color = 0;
        try {
            ItemStack stack = stackOf(id);
            TextureAtlasSprite sprite = stack.isEmpty() ? null : spriteOf(stack);
            if (sprite != null && sprite.contents() != null) {
                color = averageColor(sprite.contents().name());
            }
        } catch (Throwable t) {
            // 采样只是装饰，绝不能让渲染炸掉
            top.hmjmfabc.projector.Projector.LOGGER.debug(
                    "[Projector][展示框] 采物品颜色失败 {}：{}", id, t.toString());
        }
        if (color != 0) {
            ITEM_COLOR.put(id, color);
            return color;
        }
        // 兜底：按注册名给一个稳定的色相。
        // ⚠ **不缓存兜底值** —— 资源还没加载好时就调用过一次（比如加载界面），
        //   缓存了就会一辈子是错颜色。采不到就下次再采。
        int hash = 0;
        for (int i = 0; i < id.length(); i++) {
            hash = hash * 31 + id.charAt(i);
        }
        return hsvToRgb(Math.floorMod(hash, 360), 0.45f, 1.0f);
    }

    /**
     * 读一张贴图 PNG 算平均色。
     *
     * <p>⚠ 像素的打包顺序与字形图集那一套一致：低字节是 R、高字节是 A
     * （{@code NativeImage.setPixelRGBA(x,y,(a<<24)|0xFFFFFF)} 能画出正确的白色，
     * 说明 A 落在 GL 期望的 alpha 位置上）。</p>
     *
     * <p>⚠ 必须 {@code close()}：{@code NativeImage} 是堆外内存，本项目对内存有硬约束。</p>
     */
    public static int averageColor(ResourceLocation texture) {
        if (texture == null) {
            return 0;
        }
        com.mojang.blaze3d.platform.NativeImage img = null;
        try {
            try (java.io.InputStream in = openTexture(texture)) {
                if (in == null) {
                    return 0;
                }
                img = com.mojang.blaze3d.platform.NativeImage.read(in);
            }
            long r = 0, g = 0, b = 0, wsum = 0;
            int wpx = img.getWidth(), hpx = img.getHeight();
            for (int y = 0; y < hpx; y++) {
                for (int x = 0; x < wpx; x++) {
                    int p = img.getPixelRGBA(x, y);
                    int a = p >>> 24;
                    if (a < 32) {
                        continue;                 // 透明像素不算（否则什么都是黑的）
                    }
                    r += (long) (p & 0xFF) * a;
                    g += (long) ((p >> 8) & 0xFF) * a;
                    b += (long) ((p >> 16) & 0xFF) * a;
                    wsum += a;
                }
            }
            if (wsum == 0) {
                return 0;
            }
            return (int) ((r / wsum) << 16 | (g / wsum) << 8 | (b / wsum));
        } catch (Throwable t) {
            return 0;
        } finally {
            if (img != null) {
                img.close();
            }
        }
    }

    /** HSV → RGB（0..1 的 h 允许超过 1 圈；不用 java.awt —— 安卓上没有那个类）。 */
    public static int hsvToRgb(float hue, float sat, float val) {
        float h = ((hue % 360.0f) + 360.0f) % 360.0f / 60.0f;
        float s = Math.max(0f, Math.min(1f, sat));
        float v = Math.max(0f, Math.min(1f, val));
        int i = (int) Math.floor(h);
        float f = h - i;
        float p = v * (1.0f - s);
        float q = v * (1.0f - s * f);
        float t = v * (1.0f - s * (1.0f - f));
        float r, g, b;
        switch (i % 6) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return (Math.round(r * 255.0f) << 16) | (Math.round(g * 255.0f) << 8)
                | Math.round(b * 255.0f);
    }

    /**
     * 贴图的透明度（放入/取出的渐显/渐隐）。
     *
     * <p>【27.2.2 用户要求】放入时贴图渐显、取出时渐隐，全程 {@link #FADE_MS} 毫秒。
     * 取出之后轮廓仍在（空框），所以渐隐期间还要继续画贴图 —— 这里用「距上次变化的时间」算。</p>
     */
    private static float iconAlpha(ItemWidget w) {
        long ms = System.currentTimeMillis();
        long changedAt = CHANGE_MS.getOrDefault(w.id, 0L);
        if (changedAt == 0L) {
            return w.placed ? 1.0f : 0.0f;
        }
        float f = Math.min(1.0f, (ms - changedAt) / (float) FADE_MS);
        return w.placed ? f : 1.0f - f;
    }

    /** 渐显/渐隐时长（毫秒）。 */
    public static final long FADE_MS = 450L;

    /** 每个控件「上次放入/取出的时刻」（毫秒，墙钟）。 */
    private static final java.util.Map<java.util.UUID, Long> CHANGE_MS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 状态变化时调用一次，启动渐显/渐隐动画。 */
    public static void noteChanged(java.util.UUID id) {
        if (id != null) {
            CHANGE_MS.put(id, System.currentTimeMillis());
        }
    }

    /** 诊断用（界面/日志）。 */
    public static String describe(ItemWidget w) {
        return "展示框 物品=" + (w.hasItem() ? w.itemId : "(未选)")
                + " 状态=" + (w.placed ? "已放入" : "空")
                + " 轮廓=" + Integer.toHexString(w.outlineColor)
                + " 加粗(停用)=" + w.outlineWidth + " 缩放=" + w.iconScale + " 图标角度=" + w.iconRot;
    }

    /** 画一个轴对齐矩形（局部坐标 → 画布）。 */
    private static void rect(QuadCollector collector, PlaneRenderContext ctx, ItemWidget w,
                             double x0, double y0, double x1, double y1, int argb, double depth) {
        if ((argb >>> 24) == 0 || Math.abs(x1 - x0) < 1.0e-4 || Math.abs(y1 - y0) < 1.0e-4) {
            return;
        }
        double[] p0 = w.rotPt(x0, y0);
        double[] p1 = w.rotPt(x1, y0);
        double[] p2 = w.rotPt(x1, y1);
        double[] p3 = w.rotPt(x0, y1);
        collector.setRenderType(QuadCollector.imageType(WidgetRenderer.WHITE));
        collector.canvasQuad(ctx.axisX(), ctx.axisY(), ctx.normal(), ctx.origin(),
                p0[0], p0[1], p1[0], p1[1], p2[0], p2[1], p3[0], p3[1],
                depth, 0f, 0f, 1f, 1f, argb);
    }
}

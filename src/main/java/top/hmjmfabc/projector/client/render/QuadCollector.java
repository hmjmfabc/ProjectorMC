package top.hmjmfabc.projector.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import top.hmjmfabc.projector.client.font.FontManager;
import top.hmjmfabc.projector.common.PlaneCanvas;
import top.hmjmfabc.projector.client.font.TtfFont;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

/**
 * 世界内四边形收集器。
 *
 * <p>所有控件（文字、图片、视频帧、天气图标）最终都会被转换成世界坐标下的
 * 四边形，交给本类按「渲染类型（纹理）」分批提交。分批能把 OpenGL 状态切换
 * 次数压到最低——这是移动端帧率的关键。</p>
 *
 * <p>四边形对象从对象池里取，每帧归还，运行期几乎不产生垃圾。</p>
 */
public final class QuadCollector {

    /** 每帧允许提交的最大四边形数，防止极端情况把内存吃满。 */
    public static final int MAX_QUADS = 24000;
    /**
     * 全亮光照（SkyLight 15 + BlockLight 15）。
     *
     * <p><b>注意：这个值会被光影（Iris / OptiFine）判定为「自发光」</b>——
     * 也就是用户 ④ 报的「控件被错误识别为光源（只显示发光、没有实际亮度）」。
     * 因此默认渲染改用 {@link #LIGHT_SHADER_SAFE}。</p>
     */
    public static final int LIGHT_FULL = 0xF000F0;

    /**
     * 【④ 光影兼容】「近似全亮」光照：SkyLight 15 + BlockLight <b>14</b>。
     *
     * <p>光影包的发光判定看的是「两个分量是否都取满」这个签名；把方块光降 1 档
     * 就不再是全亮签名，控件会被当作普通表面参与光照计算。而 14/15 与 15/15
     * 的亮度差异肉眼不可辨，内容的可读性完全不受影响。</p>
     */
    public static final int LIGHT_SHADER_SAFE = 0xF000E0;

    /**
     * 本帧由渲染器指定的光照值（-1 = 没指定，用配置里的固定值）。
     *
     * <p>【hotfix-99】打开 {@code render.realLightForWidgets} 时，渲染器会把
     * 「平面锚点处的真实光照」塞进来 —— 这样光影包会把平面当成普通表面参与光照与阴影，
     * 既不会发亮也不会比周围的墙更亮。</p>
     */
    private static int frameLight = -1;

    /** 由 {@code WorldPlaneRenderer} 每个平面设置一次（-1 = 恢复固定值）。 */
    public static void setFrameLight(int packedLight) {
        frameLight = packedLight;
    }

    // ------------------------------------------------------------------
    // 【hotfix-II】从背面看时的两个开关（由 WorldPlaneRenderer 按平面设置）
    // ------------------------------------------------------------------

    /**
     * 本帧是否正在渲染「从背面看」的平面。
     *
     * <p>必须翻绕序：{@code RenderType.text} 用默认 {@code CULL}，背面朝外的四边形
     * 会被整批剔除（一个顶点都提交不上去）。翻绕序只改<b>顶点提交顺序</b>，
     * 不动几何位置 —— 玩家要的是「正面在哪背面就显示在哪」。</p>
     */
    private static boolean backfaceView;

    /**
     * 是否关掉深度测试（= 透墙看到内容）。
     *
     * <p>只在「背面 <b>且</b> 贴着平面」时为真（判据见 {@code PlaneSide.seeThroughFromBack}）：
     * 关掉深度测试会让内容画在所有几何之上，离得远就会透过地形看到它（穿墙）。</p>
     */
    private static boolean seeThrough;

    public static void setBackfaceView(boolean value) {
        backfaceView = value;
    }

    public static void setSeeThrough(boolean value) {
        seeThrough = value;
    }

    /** 每个平面画完立刻复位：这两个开关是「按平面」的，漏给下一帧就会到处穿墙。 */
    public static void resetBackface() {
        backfaceView = false;
        seeThrough = false;
    }

    /** 本帧实际使用的光照值，由配置项 {@code render.avoidFullBrightLight} /
     *  {@code render.realLightForWidgets} 决定。 */
    private static int currentLight() {
        if (frameLight >= 0) {
            return frameLight;
        }
        try {
            return top.hmjmfabc.projector.ProjectorConfig.INSTANCE.avoidFullBrightLight.get()
                    ? LIGHT_SHADER_SAFE : LIGHT_FULL;
        } catch (Throwable t) {
            // 配置尚未加载完（极早期调用）时取安全的那个，绝不因此抛异常
            return LIGHT_SHADER_SAFE;
        }
    }

    /** 无叠加层（受伤/着火等红色叠加）。 */
    public static final int OVERLAY = 0;

    private static MultiBufferSource.BufferSource bufferSource;
    private static com.mojang.blaze3d.vertex.ByteBufferBuilder sharedBuilder;

    private static final List<Quad> POOL = new ArrayList<>();
    private static int poolIndex;

    private final Map<RenderType, List<Quad>> batches = new LinkedHashMap<>();
    private int quadCount;
    private RenderType currentType;
    /** 本帧全局已提交的四边形总数（跨所有收集器）。 */
    private static int frameQuadTotal;
    /** 绘制失败只记一次日志，避免刷屏。 */
    private static boolean flushErrorLogged;
    /** 一个待提交的四边形（4 个顶点）。 */
    private static final class Quad {
        final float[] pos = new float[12];
        final float[] uv = new float[8];
        int argb;
        float nx, ny, nz;
    }

    public QuadCollector() {
    }

    /** 每帧开始时回收对象池。 */
    public static void beginFrame() {
        poolIndex = 0;
        frameQuadTotal = 0;
        resetBackface();            // 兜底：上一帧若有异常退出，别把「透墙」状态带到新一帧
    }

    /** 本帧剩余可提交的四边形数量。 */
    public static int remainingThisFrame() {
        return Math.max(0, MAX_QUADS - frameQuadTotal);
    }

    private static Quad obtain() {
        if (poolIndex >= POOL.size()) {
            POOL.add(new Quad());
        }
        return POOL.get(poolIndex++);
    }

    /** 选择接下来的四边形使用哪个渲染类型（通常是某个纹理）。 */
    public void setRenderType(RenderType type) {
        this.currentType = type;
    }

    @Nullable
    public RenderType currentType() {
        return currentType;
    }

    public int quadCount() {
        return quadCount;
    }

    public boolean isEmpty() {
        return quadCount == 0;
    }

    // ------------------------------------------------------------------
    // 提交
    // ------------------------------------------------------------------

    /**
     * 提交一个位于「画布坐标系」中的四边形。
     *
     * <p><b>必须显式给出外法线。</b>之前留了一个「不传法线就用
     * {@code axisX × axisY} 兜底」的重载，结果所有内容渲染器都走了兜底分支：
     * 而 {@link BlockFace#right} / {@link BlockFace#up} 的叉乘在
     * <b>DOWN / WEST / EAST</b> 三个朝向上指向方块<b>内部</b>，
     * 于是内容被沿错误方向推进方块、被方块表面挡住，彻底看不见。
     * 现在把那个重载删掉，让编译器强制每个调用点都传正确的法线。</p>
     *
     * <p>顶点绕序也在这里统一纠正：GL 默认「逆时针为正面」，而方块面的
     * {@code (right, up)} 组合在部分朝向上是左手系，直接用
     * {@code a→b→c→d} 的顺序会让正面朝向方块内部、被背面剔除吃掉。
     * 这里比较「三角形的环绕方向」与「外法线」的符号，不一致就改成
     * {@code a→d→c→b}，因此无论平面朝向如何，正面永远朝着玩家。</p>
     *
     * <p><b>UV 的 V 方向在这里统一翻转。</b>四个角的约定是
     * {@code a = 左下, b = 右下, c = 右上, d = 左上}（画布 y 轴向上），
     * 而纹理坐标的 {@code v = 0} 在<b>图像顶部</b>（NativeImage 是自顶向下的）。
     * 因此左下角必须取 {@code v1}（纹理底边）、左上角取 {@code v0}（纹理顶边）。
     * 少了这一步，每个字形都会被单独上下颠倒——而字与字的顺序仍然正确，
     * 正是「逐字符倒置」这种故障的特征。</p>
     *
     * @param axisX  画布 x 轴的单位世界向量
     * @param axisY  画布 y 轴的单位世界向量
     * @param normal 平面的<b>外</b>法线（单位向量，由 {@link BlockFace#normal} 得出）
     * @param origin 画布原点的世界坐标（相机相对）
     * @param depth  沿外法线的额外偏移，单位：方块
     * @param u0,v0  纹理左上角
     * @param u1,v1  纹理右下角
     */
    public void canvasQuad(double[] axisX, double[] axisY, double[] normal, double[] origin,
                           double ax, double ay, double bx, double by,
                           double cx, double cy, double dx, double dy,
                           double depth,
                           float u0, float v0, float u1, float v1, int argb) {
        if (currentType == null || frameQuadTotal >= MAX_QUADS) return;
        // 兜底：任何非有限坐标都会让整批绘制失败，直接丢弃这个四边形
        if (!isFinite(ax) || !isFinite(ay) || !isFinite(bx) || !isFinite(by)
                || !isFinite(cx) || !isFinite(cy) || !isFinite(dx) || !isFinite(dy)
                || !isFinite(depth) || !Float.isFinite(u0) || !Float.isFinite(v0)
                || !Float.isFinite(u1) || !Float.isFinite(v1)) {
            return;
        }
        double nx = normal[0];
        double ny = normal[1];
        double nz = normal[2];
        double ox = origin[0] + nx * depth;
        double oy = origin[1] + ny * depth;
        double oz = origin[2] + nz * depth;

        // 绕序校正：GL 默认「逆时针为正面」。这里把 a→b→c→d 的环绕方向与
        // 「给定的外法线」对齐；若不对齐就改成 a→d→c→b（位置与 UV 一起走，
        // 否则纹理会镜像）。两个分支直接写出 4 个顶点，不分配临时数组。
        // cx2 = axisX × axisY（只用于判断绕序，不当作法线）
        double cx2 = axisX[1] * axisY[2] - axisX[2] * axisY[1];
        double cy2 = axisX[2] * axisY[0] - axisX[0] * axisY[2];
        double cz2 = axisX[0] * axisY[1] - axisX[1] * axisY[0];
        // orient = 三角形 a→b→c 在画布平面内的有向面积符号
        double orient = (bx - ax) * (cy - by) - (by - ay) * (cx - bx);
        boolean flip = orient * (cx2 * nx + cy2 * ny + cz2 * nz) < 0;
        // 【hotfix-II】从背面看时把绕序翻过来（否则背面朝外 ⇒ 被 CULL 整批剔除）。
        // 只改顶点提交顺序，几何位置一个像素都不动。
        if (backfaceView) {
            flip = !flip;
        }

        Quad q = obtain();
        q.argb = argb;
        q.nx = (float) nx;
        q.ny = (float) ny;
        q.nz = (float) nz;
        // a = 左下 -> 纹理底边 (v1)；d = 左上 -> 纹理顶边 (v0)。详见上面的 UV 说明。
        if (flip) {
            vertex(q, 0, ox, oy, oz, axisX, axisY, ax, ay, u0, v1);
            vertex(q, 1, ox, oy, oz, axisX, axisY, dx, dy, u0, v0);
            vertex(q, 2, ox, oy, oz, axisX, axisY, cx, cy, u1, v0);
            vertex(q, 3, ox, oy, oz, axisX, axisY, bx, by, u1, v1);
        } else {
            vertex(q, 0, ox, oy, oz, axisX, axisY, ax, ay, u0, v1);
            vertex(q, 1, ox, oy, oz, axisX, axisY, bx, by, u1, v1);
            vertex(q, 2, ox, oy, oz, axisX, axisY, cx, cy, u1, v0);
            vertex(q, 3, ox, oy, oz, axisX, axisY, dx, dy, u0, v0);
        }

        batches.computeIfAbsent(currentType, k -> new ArrayList<>(64)).add(q);
        quadCount++;
        frameQuadTotal++;
    }


    private static boolean isFinite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    /**
     * 写入第 {@code i} 个顶点。
     *
     * <p><b>画布坐标的单位是「16 单位 = 1 方块」，必须在这里除以
     * {@link PlaneCanvas#UNITS_PER_BLOCK} 才能变成世界偏移。</b>
     * 之前漏了这一步，于是 80×48 单位的画布被当成 80×48 <b>方块</b>画出来——
     * 内容整体放大 16 倍、飞出控件方框，高光边框也一样大得离谱。</p>
     */
    private static void vertex(Quad q, int i, double ox, double oy, double oz,
                               double[] ax, double[] ay,
                               double px, double py, float u, float v) {
        double bx = px / PlaneCanvas.UNITS_PER_BLOCK;
        double by = py / PlaneCanvas.UNITS_PER_BLOCK;
        q.pos[i * 3] = (float) (ox + ax[0] * bx + ay[0] * by);
        q.pos[i * 3 + 1] = (float) (oy + ax[1] * bx + ay[1] * by);
        q.pos[i * 3 + 2] = (float) (oz + ax[2] * bx + ay[2] * by);
        q.uv[i * 2] = u;
        q.uv[i * 2 + 1] = v;
    }

    /**
     * 提交一个位于「控件局部坐标系」中的四边形：局部坐标以控件锚点为原点、
     * 已经做过旋转，这里直接叠加控件在画布中的位置。
     */
    public void widgetQuad(double[] axisX, double[] axisY, double[] normal, double[] origin,
                           double wx, double wy, double depth,
                           double ax, double ay, double bx, double by,
                           double cx, double cy, double dx, double dy,
                           float u0, float v0, float u1, float v1, int argb) {
        canvasQuad(axisX, axisY, normal, origin,
                wx + ax, wy + ay, wx + bx, wy + by, wx + cx, wy + cy, wx + dx, wy + dy,
                depth, u0, v0, u1, v1, argb);
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    /**
     * 把收集到的所有四边形写进顶点缓冲并绘制；顺序与添加顺序一致。
     *
     * <p>顶点已经是<b>相机相对坐标</b>，这里不再乘任何矩阵：{@code RenderLevelStageEvent}
     * 给的 PoseStack 是恒等矩阵，相机变换由 {@code ModelViewMat} uniform 承担。</p>
     *
     * <p>顶点元素按「Position / Color / UV0 / UV1(Overlay) / UV2(Light) / Normal」
     * 全写一遍。{@code BufferBuilder.beginElement} 对<b>格式里没有的元素直接返回 -1</b>
     * （已核对字节码），调用会被安全忽略，因此同一段写入代码可以同时适配
     * {@code POSITION_COLOR_TEX_LIGHTMAP}（文字，忽略 Overlay/Normal）与
     * {@code NEW_ENTITY}（图片，六个元素都用得上）。</p>
     */
    public void flush(PoseStack pose) {
        if (batches.isEmpty()) {
            quadCount = 0;
            currentType = null;
            return;
        }
        // 【④】整帧只解析一次配置，避免在顶点循环里反复读配置
        final int lightToUse = currentLight();
        MultiBufferSource.BufferSource src = source();
        for (Map.Entry<RenderType, List<Quad>> e : batches.entrySet()) {
            List<Quad> list = e.getValue();
            if (list.isEmpty()) continue;
            try {
                VertexConsumer vc = src.getBuffer(e.getKey());
                for (Quad q : list) {
                    for (int i = 0; i < 4; i++) {
                        vc.addVertex(q.pos[i * 3], q.pos[i * 3 + 1], q.pos[i * 3 + 2]);
                        vc.setColor(q.argb);
                        vc.setUv(q.uv[i * 2], q.uv[i * 2 + 1]);
                        vc.setOverlay(OVERLAY);
                        vc.setLight(lightToUse);
                        vc.setNormal(q.nx, q.ny, q.nz);
                    }
                }
                src.endBatch(e.getKey());
            } catch (Throwable t) {
                // 单个渲染类型失败（例如纹理缺失）不该让整帧其它内容一起消失：
                // 降级为「丢弃这一批 + 只记一次日志」，下一帧与其它批次照常绘制。
                if (!flushErrorLogged) {
                    flushErrorLogged = true;
                    top.hmjmfabc.projector.Projector.LOGGER.error(
                            "[Projector] 某一批绘制失败（该批已跳过，其余内容继续绘制）", t);
                }
            }
            list.clear();
        }
        quadCount = 0;
        currentType = null;
    }

    /** 清空收集结果（丢弃）。 */
    public void clear() {
        for (List<Quad> l : batches.values()) l.clear();
        quadCount = 0;
        currentType = null;
    }

    // ------------------------------------------------------------------
    // 共享缓冲与渲染类型
    // ------------------------------------------------------------------

    private static MultiBufferSource.BufferSource source() {
        if (bufferSource == null) {
            SequencedMap<RenderType, com.mojang.blaze3d.vertex.ByteBufferBuilder> fixed = new LinkedHashMap<>();
            sharedBuilder = new com.mojang.blaze3d.vertex.ByteBufferBuilder(1048576);
            bufferSource = MultiBufferSource.immediateWithBuffers(fixed, sharedBuilder);
        }
        return bufferSource;
    }

    /** 释放共享缓冲（资源重载 / 断开连接时调用）。 */
    public static void resetSource() {
        bufferSource = null;
        if (sharedBuilder != null) {
            try {
                sharedBuilder.close();
            } catch (Throwable ignored) {
                // 已经关闭或仍有未提交数据时忽略
            }
            sharedBuilder = null;
        }
        poolIndex = 0;
    }

    // ------------------------------------------------------------------
    // 渲染类型
    // ------------------------------------------------------------------

    /**
     * 文字使用的渲染类型。
     *
     * <p>用原版 {@code RenderType.text(...)}：它就是原版在世界里画文字（告示牌、命名牌）
     * 用的那一个，{@code POSITION_COLOR_TEX_LIGHTMAP} 顶点格式，
     * 已经由无数整合包与手机端启动器验证过。为了让它正确工作，
     * 我们这边必须满足三个条件，缺一个都会「什么都看不见」：</p>
     * <ol>
     *   <li><b>字形图集必须是 RGBA（白色 RGB + 覆盖率 Alpha）</b>，
     *       见 {@code GlyphAtlas} 的类注释；</li>
     *   <li><b>纹理要按字形所在那一页取</b>，不能固定第 0 页；</li>
     *   <li><b>顶点绕序要让正面朝向玩家</b>，因为 {@code text} 默认开启背面剔除
     *       （已由 {@link #canvasQuad} 统一纠正）。</li>
     * </ol>
     *
     * @param atlas 字形图集某一页的纹理，为 null 时回退到图集第一页
     */
    @Nullable
    public static RenderType fontType(ResourceLocation atlas) {
        ResourceLocation real = atlas == null ? FontManager.fallbackAtlas() : atlas;
        return real == null ? null : textured(real);
    }

    /**
     * 图片 / 视频帧 / 天气图标 / 实色块使用的渲染类型。
     *
     * <p>与文字共用 {@code RenderType.text(...)}：本项目里唯一被实测证明
     * 「一定能画出来」的带纹理管线。</p>
     */
    public static RenderType imageType(ResourceLocation texture) {
        // 与文字用完全相同的渲染类型：这是本项目里唯一被实测证明「一定能画出来」的
        // 带纹理管线（文字就是靠它修好的）。
        // 原因：{@code entityTranslucent} / {@code entityTranslucentEmissive} 走的是
        // NEW_ENTITY 顶点格式与 rendertype_entity_* 着色器，需要光照图与叠加层纹理，
        // 在 Android 端的图形转发层（GL4ES / VirGL）上出现过「四边形已提交、
        // 纹理也就绪，但屏幕上一个像素都没有」的情况。
        // {@code text} 走 rendertype_text：只要纹理是 RGBA、UV 正确、光照填全亮，
        // 就是一条非常朴素可靠的路径，图片用起来同样正确。
        return textured(texture);
    }

    /**
     * 【hotfix-II】按「现在是不是在看反面」选纹理管线。
     *
     * <p>{@code RenderType.textSeeThrough} 就是 {@code text} 去掉深度测试：
     * 关掉深度测试是为了让内容不被方块本体挡住（几何位置**一个像素都不动**，
     * 所以从正面看在哪、从背面看还在哪）。它默认同样是 {@code CULL}，
     * 因此反面那一帧必须配合 {@link #setBackfaceView} 翻绕序，缺一个都看不到。</p>
     *
     * <p>文字与图片**共用**这一个入口：以前只有图片那一支换了渲染类型，
     * 于是玩家看到「图片/视频会穿墙、文字和时钟不会」这种只坏一半的现象。</p>
     */
    private static RenderType textured(ResourceLocation texture) {
        return seeThrough ? RenderType.textSeeThrough(texture) : RenderType.text(texture);
    }

    /**
     * 纹理是否已经注册在纹理管理器里。
     *
     * <p>渲染类型在 {@code setupRenderState} 阶段按名字取纹理，取不到就会绑定一个
     * 无效 ID —— 结果是这一批<b>一个像素都画不出来</b>，而且不会有任何报错。
     * 所以画之前先确认一次：没有纹理时干脆退化成占位色块，
     * 让玩家至少知道「控件在这里，只是素材还没就绪」。</p>
     */
    public static boolean textureReady(ResourceLocation texture) {
        if (texture == null) return false;
        try {
            return Minecraft.getInstance().getTextureManager().getTexture(texture) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 按字体 ID 取对应的渲染类型（字体缺失时回退到默认图集）。 */
    @Nullable
    public static RenderType typeForFont(String fontId) {
        TtfFont font = FontManager.get(fontId);
        return fontType(font == null ? FontManager.fallbackAtlas() : FontManager.atlasTexture(font));
    }

    // ------------------------------------------------------------------
    // 颜色工具
    // ------------------------------------------------------------------

    /** 叠加全局不透明度。 */
    public static int withAlpha(int argb, float alpha) {
        int a = (int) (((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, alpha)));
        return (a << 24) | (argb & 0xFFFFFF);
    }
}

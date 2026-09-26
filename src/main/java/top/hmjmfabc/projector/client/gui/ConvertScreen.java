package top.hmjmfabc.projector.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import top.hmjmfabc.projector.client.media.LocalMedia;
import top.hmjmfabc.projector.client.media.MediaCache;
import top.hmjmfabc.projector.client.media.convert.Ffmpeg;
import top.hmjmfabc.projector.client.media.convert.JcodecBackend;
import top.hmjmfabc.projector.client.media.convert.VideoConverter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 视频转换界面：把 MP4 等常见格式转成模组支持的 MJPEG / ZIP 帧序列。
 *
 * <p>转换跑在 {@link MediaCache#worker()} 的后台线程上，界面只负责显示进度与取消。
 * 完成后会自动刷新媒体列表，玩家直接就能选中新生成的文件。</p>
 *
 * <h2>⑤.3 的修复：为什么会「UI 重叠 / 开始转换按钮点不到」</h2>
 * <p>原先把所有文字、目标格式按钮、四个滑块一路 {@code y += …} 往下堆，
 * 而「开始转换 / 返回」按屏幕底边定位。屏幕不高时（Android 竖屏尤其明显）
 * 两段必然重叠。更致命的是原版 {@code Screen.mouseClicked} 是
 * <b>按 children 的添加顺序遍历、命中第一个就返回</b>，
 * 所以<b>先添加的滑块会吃掉后添加的按钮的点击</b>——
 * 按钮明明画在最上层，却怎么点都没反应。</p>
 * <p>现在的做法：</p>
 * <ol>
 *   <li>底部划出<b>固定保留区</b>（进度条 + 按钮），内容区永不侵入；</li>
 *   <li>内容区可<b>滚动</b>（滚轮 / 拖右侧滚动条），小屏也能看全所有参数；</li>
 *   <li>视口外的控件<b>既不添加也不激活</b>，物理上杜绝「误吃点击」。</li>
 * </ol>
 */
public class ConvertScreen extends ProjectorScreen {

    /** 一行说明文字（保存的是「内容坐标」，绘制时再减去滚动量）。 */
    private record Line(String text, int x, int vy, int color) {
    }

    /** 内容区顶部（标题下方）。 */
    private static final int CONTENT_TOP = 30;
    /** 底部固定保留区高度：进度条 + 状态文字 + 按钮行。 */
    private static final int BOTTOM_RESERVED = 62;
    /** 右侧滚动条宽度。 */
    private static final int SCROLLBAR_W = 5;

    private final Screen parent;
    private final Path source;
    private final Runnable onDone;
    private final List<Line> lines = new ArrayList<>();

    private final VideoConverter.Options options = VideoConverter.Options.defaults();
    private VideoConverter.Target target = VideoConverter.Target.MJPEG;

    private volatile double progress = -1;
    private volatile String status = "";
    private volatile boolean running;
    private volatile boolean finished;
    private final AtomicBoolean cancel = new AtomicBoolean();

    /** 内容总高度（内容坐标）与当前滚动量。 */
    private int contentHeight;
    private int scroll;
    private int maxScroll;
    /** 内容视口（屏幕坐标）。 */
    private int viewTop, viewBottom, viewLeft, viewRight;

    public ConvertScreen(Screen parent, Path source, Runnable onDone) {
        super(Component.literal("视频转换"));
        this.parent = parent;
        this.source = source;
        this.onDone = onDone;
    }

    /**
     * 布局入口。
     *
     * <p><b>必须叫 {@code init()}，不能只写 {@code rebuildWidgets()}。</b>
     * 原版 {@code Screen.rebuildWidgets()} 的实现是 {@code clearWidgets() + init()}，
     * 而 {@code setScreen()} 调用的正是 {@code init()}。之前这里只重写了
     * {@code rebuildWidgets()}，{@code init()} 走的是空实现——
     * 于是界面打开时一个控件都没建、一行字都没加，只剩下 {@code render()} 里
     * 那句硬编码的标题，看起来就是「卡在只有标题的空屏」。</p>
     */
    @Override
    protected void init() {
        try {
            buildLayout();
        } catch (Throwable t) {
            // 保底：构建失败时把原因写进日志，而不是留下一个空白界面
            top.hmjmfabc.projector.Projector.LOGGER.error("[Projector] 转换界面构建失败", t);
        }
    }

    private void buildLayout() {
        // 【重要】这里绝不能做任何可能阻塞的事（起进程、等超时、网络）。
        // 界面初始化在渲染线程上跑，一旦阻塞就是「只有标题的空屏」。
        // 所以只做一次纯文件检查，真正的 ffmpeg -version 验证推迟到
        // 「点开始转换」之后（那时已经在后台线程上）。
        clearWidgets();
        lines.clear();

        int cx = 14;
        int cw = Math.min(340, this.width - 28);
        int bottomY = this.height - 28;          // 按钮行
        viewTop = CONTENT_TOP;
        viewBottom = bottomY - 12;               // 按钮行上面留 12px
        viewLeft = cx;
        viewRight = cx + cw;
        if (viewBottom <= viewTop + 20) {
            // 屏幕实在太小：至少保证按钮行可用
            viewBottom = viewTop + 20;
        }

        // ---- 「内容坐标」从 0 开始累加，绘制/放置时统一减 scroll ----
        int vy = 0;
        int rowGap = 22;
        int sliderGap = 32;

        text("源文件：", cx, vy, 0xFFAAAAAA);
        vy += 11;
        text(clip(source.getFileName().toString(), cw), cx, vy, 0xFFFFFFFF);
        vy += 20;

        Path ffmpeg = Ffmpeg.quickPath();
        if (ffmpeg == null) {
            text("使用内置解码器（纯 Java，无需安装任何东西）", cx, vy, 0xFF66DD88);
            vy += 12;
            text("支持 MP4 / MOV / MKV 里的 H.264。H.265 / AV1 不支持，", cx, vy, 0xFFAAAAAA);
            vy += 11;
            text("桌面端若装了 ffmpeg 会优先用它（更快、编码更全）。", cx, vy, 0xFFAAAAAA);
            vy += 11;
            text("注意：纯 Java 解码较慢，耗时≈源视频长度（1080p 约 1~3 倍），", cx, vy, 0xFFFFCC66);
            vy += 11;
            text("与输出帧率/画面大小无关；嫌慢就限制转换时长。", cx, vy, 0xFFFFCC66);
            vy += 20;
        } else {
            text("检测到 ffmpeg，将优先使用它（更快）", cx, vy, 0xFF66DD88);
            vy += 20;
        }

        text("目标格式", cx, vy, 0xFFAAAAAA);
        vy += 14;
        for (VideoConverter.Target t : VideoConverter.Target.values()) {
            final VideoConverter.Target tt = t;
            int wy = viewTop + vy - scroll;
            if (fullyVisible(wy, 20)) {
                button((target == tt ? "● " : "○ ") + t.label, cx, wy, cw, 18, b -> {
                    target = tt;
                    rebuildWidgets();
                });
            }
            vy += rowGap;
        }
        vy += 4;

        vy = sliderRow(cx, vy, cw, "帧率（每帧都是一张 JPEG）", 1, 30, options.fps, true,
                v -> options.fps = v.intValue(), sliderGap);
        vy = sliderRow(cx, vy, cw, "最大边长（像素）", 64, 1024, options.maxSide, true,
                v -> options.maxSide = v.intValue(), sliderGap);
        vy = sliderRow(cx, vy, cw, "画质数值（越小越清晰、文件越大）", 1, 31, options.quality, true,
                v -> options.quality = v.intValue(), sliderGap);
        vy = sliderRow(cx, vy, cw, "只转换前 N 秒（0=全部，纯 Java 解码慢）", 0, 120,
                options.maxSeconds, true, v -> options.maxSeconds = v.intValue(), sliderGap);

        vy += 4;
        Path dst = VideoConverter.defaultOutput(source, target);
        text("输出：" + clip(dst.getFileName().toString(), cw), cx, vy, 0xFFCCCCCC);
        vy += 12;
        text("10 秒素材约 " + estimateMb() + " MB。存档单个媒体上限可在配置里调整，",
                cx, vy, 0xFF99AACC);
        vy += 11;
        text("素材太长请降低帧率或最大边长。", cx, vy, 0xFF99AACC);
        vy += 16;

        contentHeight = vy;
        int viewH = Math.max(1, viewBottom - viewTop);
        maxScroll = Math.max(0, contentHeight - viewH);
        if (scroll > maxScroll) scroll = maxScroll;
        if (scroll < 0) scroll = 0;

        // ---- 底部固定保留区：先加内容、后加按钮并不够，
        //      关键是内容控件永远不会出现在保留区里（见 fullyVisible 判定），
        //      所以按钮不可能被别的控件抢走点击。----
        int by = this.height - 28;
        if (running) {
            redButton("取消转换", cx, by, cw / 2 - 4, 20, b -> cancel.set(true));
        } else {
            button("开始转换", cx, by, cw / 2 - 4, 20, b -> start())
                    .active = ffmpeg != null || JcodecBackend.available();
        }
        button("返回", cx + cw / 2 + 4, by, cw / 2 - 4, 20, b -> close());

        // 滚动条（自绘，不占 children，避免再引入一个可能抢点击的控件）
        top.hmjmfabc.projector.Projector.LOGGER.debug(
                "[Projector] 转换界面已构建（{} 行文字 / {} 个控件，内容高 {} 视口高 {} 滚动 {}/{}）",
                lines.size(), this.children().size(), contentHeight, viewH, scroll, maxScroll);
    }

    /** 屏幕 y 处的 [y, y+h] 是否完整落在内容视口内（完全在内才创建控件）。 */
    private boolean fullyVisible(int y, int h) {
        return y >= viewTop && y + h <= viewBottom;
    }

    private void text(String s, int x, int vy, int color) {
        lines.add(new Line(s, x, vy, color));
    }

    /** 一行「标签 + 滑块」，返回下一行的内容坐标。 */
    private int sliderRow(int x, int vy, int w, String name, double min, double max, double value,
                          boolean integer, java.util.function.Consumer<Double> setter, int gap) {
        text(name + "：" + (integer ? String.valueOf((int) value)
                : String.format(Locale.ROOT, "%.2f", value)), x, vy, 0xFFCCCCCC);
        int wy = viewTop + vy + 10 - scroll;
        if (fullyVisible(wy, 12)) {
            addRenderableWidget(new SliderBar(x, wy, w, 12, min, max, value, integer, v -> {
                setter.accept(v);
                // 只刷新标签文字（重建会让正在拖动的滑块失去焦点，手感很差）：
                // 用一个轻量重绘标记即可，这里直接重建并保持滚动量不变。
                rebuildWidgets();
            }));
        }
        return vy + gap;
    }

    /** 10 秒素材的估算体积（MB）。 */
    private String estimateMb() {
        double perFrame = options.maxSide * options.maxSide * 0.28
                * (1.0 + (31 - options.quality) * 0.06);
        return String.format(Locale.ROOT, "%.1f", perFrame * options.fps * 10 / 1048576.0);
    }

    private void start() {
        if (running) return;
        final Path dst = VideoConverter.defaultOutput(source, target);
        cancel.set(false);
        running = true;
        finished = false;
        status = "正在准备…";
        progress = -1;
        rebuildWidgets();
        MediaCache.worker().execute(() -> {
            // 这里是后台线程：可以安全地做阻塞式探测
            Ffmpeg.locateBlocking();
            VideoConverter.Result r = VideoConverter.convert(source, dst, options,
                    new VideoConverter.Progress() {
                        @Override
                        public void update(double fraction, String message) {
                            progress = fraction;
                            status = message;
                        }

                        @Override
                        public boolean cancelled() {
                            return cancel.get();
                        }
                    });
            status = r.message();
            progress = r.ok() ? 1 : -1;
            if (r.ok()) {
                LocalMedia.rescan();
            }
            running = false;
            finished = true;
            Minecraft.getInstance().execute(() -> {
                if (onDone != null && r.ok()) onDone.run();
                rebuildWidgets();
            });
        });
    }

    private void close() {
        if (running) {
            cancel.set(true);
            return;
        }
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void onClose() {
        if (running) {
            // 转换途中按 ESC / 返回键：先请求取消，不直接关界面（否则线程会继续跑）
            cancel.set(true);
            return;
        }
        super.onClose();
    }

    // ------------------------------------------------------------------
    // 滚动
    // ------------------------------------------------------------------

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll > 0 && mouseX >= viewLeft - 4 && mouseX <= viewRight + SCROLLBAR_W + 4) {
            int before = scroll;
            scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(scrollY) * 14));
            if (scroll != before) {
                rebuildWidgets();
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** 点击右侧滚动条轨道直接跳转。 */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (maxScroll > 0 && mouseY >= viewTop && mouseY <= viewBottom
                && mouseX >= viewRight + 2 && mouseX <= viewRight + 2 + SCROLLBAR_W) {
            double t = (mouseY - viewTop) / Math.max(1.0, viewBottom - viewTop);
            scroll = Math.max(0, Math.min(maxScroll, (int) Math.round(t * maxScroll)));
            rebuildWidgets();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        renderBackground(gfx, mouseX, mouseY, partialTick);
        super.render(gfx, mouseX, mouseY, partialTick);

        // 内容文字：裁剪到内容视口，绝不允许溢出到底部保留区
        gfx.enableScissor(viewLeft - 2, viewTop, viewRight + SCROLLBAR_W + 4, viewBottom);
        for (Line l : lines) {
            int y = viewTop + l.vy() - scroll;
            if (y + 9 < viewTop || y > viewBottom) continue;
            label(gfx, l.text(), l.x(), y, l.color());
        }
        gfx.disableScissor();

        label(gfx, "视频转换", 14, 12, 0xFFFFFFFF);

        // 滚动条
        if (maxScroll > 0) {
            int trackH = Math.max(1, viewBottom - viewTop);
            int barH = Math.max(14, trackH * trackH / Math.max(1, contentHeight));
            int barY = viewTop + (trackH - barH) * scroll / maxScroll;
            gfx.fill(viewRight + 2, viewTop, viewRight + 2 + SCROLLBAR_W, viewBottom, 0xFF20242C);
            gfx.fill(viewRight + 2, barY, viewRight + 2 + SCROLLBAR_W, barY + barH, 0xFF5A5A80);
        } else if (contentHeight > viewBottom - viewTop) {
            // 理论上到不了这里；留一个可见提示，免得又变成「有些参数看不到」
            label(gfx, "内容超出，可滚动", viewRight + 2, viewTop, 0xFFFF8866);
        }

        // 底部保留区：进度条 + 状态
        if (running || finished) {
            int bx = 14;
            int bw = Math.min(340, this.width - 28);
            int by = this.height - 48;
            gfx.fill(bx, by, bx + bw, by + 8, 0xFF20242C);
            int fillW = progress < 0 ? 0 : (int) Math.round(bw * Math.max(0, Math.min(1, progress)));
            if (fillW > 0) {
                gfx.fill(bx, by, bx + fillW, by + 8, 0xFF44BB88);
            }
            label(gfx, clip(status, bw), bx, by - 11, 0xFFDDDDDD);
        }
    }

    private static String clip(String s, int px) {
        if (s == null) return "";
        int max = Math.max(8, px / 6);
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}

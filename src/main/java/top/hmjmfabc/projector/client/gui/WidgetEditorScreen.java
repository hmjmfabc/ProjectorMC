package top.hmjmfabc.projector.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import top.hmjmfabc.projector.client.SelectionState;
import top.hmjmfabc.projector.client.font.FontManager;
import top.hmjmfabc.projector.client.render.PlaneRenderContext;
import top.hmjmfabc.projector.client.render.QuadCollector;
import top.hmjmfabc.projector.client.render.TextRenderer;
import top.hmjmfabc.projector.client.render.WidgetRenderer;
import top.hmjmfabc.projector.common.Plane;
import top.hmjmfabc.projector.common.PlaneCanvas;
import top.hmjmfabc.projector.common.text.FormatCodes;
import top.hmjmfabc.projector.common.widget.ClockWidget;
import top.hmjmfabc.projector.common.widget.Fonts;
import top.hmjmfabc.projector.common.widget.ImageWidget;
import top.hmjmfabc.projector.common.widget.MusicWidget;
import top.hmjmfabc.projector.common.widget.ProgressWidget;
import top.hmjmfabc.projector.common.widget.TextWidget;
import top.hmjmfabc.projector.common.widget.VideoWidget;
import top.hmjmfabc.projector.common.widget.WeatherWidget;
import top.hmjmfabc.projector.common.widget.Widget;
import top.hmjmfabc.projector.network.Payloads;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 控件编辑器。
 *
 * <p>根据控件类型自动切换编辑内容：</p>
 * <ul>
 *   <li><b>文本</b>：文本输入框（支持 {@code &} 格式化代码）、字体、字号、行距、对齐、
 *       旋转、透明度、调色盘、样式按钮；</li>
 *   <li><b>图片</b>：预览 + 替换图片 / 删除控件；</li>
 *   <li><b>视频</b>：预览（不播放）+ 替换视频 / 帧率 / 循环 / 暂停；</li>
 *   <li><b>时钟</b>：字体、字号、时区、秒、颜色；</li>
 *   <li><b>天气</b>：字体、字号、图标开关、颜色；</li>
 *   <li><b>百分比进度</b>：标题、标题字号、百分比字号、持续时间、重置进度。</li>
 * </ul>
 *
 * <p>所有数值型参数都用滑块调节，因此「无极调节」在界面上是字面意义上的连续可调。</p>
 */
public class WidgetEditorScreen extends ProjectorScreen {

    private final Plane plane;
    private final Widget widget;
    private final Screen parent;
    private final boolean canEdit;

    @Nullable
    private EditBox textBox;
    @Nullable
    private EditBox titleBox;
    /** ⑦ 时钟标题输入框。 */
    @Nullable
    private EditBox clockTitleBox;
    /** ⑧ 计时器的「精确秒数」输入框（滑块表达不了 0~2147483647）。 */
    @Nullable
    private EditBox timerSecondsBox;
    /** ⑧ 剩余时间滑块的提交限流时间戳。 */
    private long lastTimerSubmit;
    /** ⑧ 计时器的「剩余时间」输入框（范围 0 ~ 最大时间）。 */
    @Nullable
    private EditBox timerRemainBox;
    /** ⑧ 计时器绑定的指令输入框（仅管理员可见）。 */
    @Nullable
    private EditBox timerCommandBox;
    /** ③ 排行榜的计分板项 / 标题输入框。 */
    @Nullable
    private EditBox lbObjectiveBox;
    @Nullable
    private EditBox lbTitleBox;
    /** ⑨.3 双色渐变：是否处于「点两个色块组成渐变」的模式。 */
    private boolean gradientMode;
    /** ⑨.3 已经点好的起点色（-1 表示还没点）。 */
    private int gradientA = -1;
    @Nullable
    private CanvasWidgetView canvasView;

    /** 已请求删除：onClose 时不再提交更新。 */
    private boolean pendingDelete;
    /**
     * 是否因为打开了子界面（字体选择器 / 媒体选择器）而被移除。
     * 这种情况不能自动提交，否则「取消」就失去意义了。
     */
    private boolean childScreen;
    private boolean previewErrorLogged;
    /** 左栏参数区允许到达的最大 y（超过就不再布置控件，避免它们跑到屏幕外）。 */
    private int bottomLimit;
    /** 进入编辑器时的控件快照，用于「取消」恢复。 */
    private net.minecraft.nbt.CompoundTag initialSnapshot;
    /** 进入编辑器时的进度持续时间，用于判断是否需要重启计时。 */
    private double initialDurationSeconds = -1;
    /** 进入编辑器时的文本内容/字体/字号，用于判断是否需要重算控件尺寸。 */
    private String initialText = "";
    private String initialFontId = "";
    private double initialFontSize = -1;
    private String previewMessage = "";
    /** 预览区（屏幕坐标）。 */
    private int pvX, pvY, pvW, pvH;

    private final QuadCollector preview = new QuadCollector();

    public WidgetEditorScreen(Plane plane, Widget widget, Screen parent) {
        super(Component.literal("\u7f16\u8f91\u63a7\u4ef6\uff1a" + widget.label()));
        this.plane = plane;
        this.widget = widget;
        this.parent = parent;
        // 与服务端 PlanePermissions 逐条对齐：权限等级 ≥ 2 **或本机就是房主** 都算管理员
        // （用户 ⑥.1）。以前这里只认权限等级，于是单人存档没开作弊时房主自己都改不了自己的控件。
        this.canEdit = top.hmjmfabc.projector.client.ClientPermissions.canEditContent(plane);
        this.initialSnapshot = widget.save();
        this.initialDurationSeconds = widget instanceof ProgressWidget pw ? pw.durationSeconds : -1;
        if (widget instanceof TextWidget tw0) {
            this.initialText = tw0.text == null ? "" : tw0.text;
            this.initialFontId = tw0.fontId;
            this.initialFontSize = tw0.fontSize;
        }
    }

    /** 工厂：服务端可能把该控件替换为其它类型，这里统一入口。 */
    public static Screen create(Plane plane, Widget widget) {
        return new WidgetEditorScreen(plane, widget, new PlaneDialogScreen(plane));
    }

    // ------------------------------------------------------------------
    // 布局
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        // 每次 init() 都重置提交闩锁：删除确认框取消后会回到同一个界面实例，
        // 若不复位，removed() 的兜底提交就永久失效了。
        saved = false;
        childScreen = false;
        rowLabels.clear();
        rowValues.clear();
        staticRows.clear();
        int pad = 10;
        // 左栏宽度自适应：窗口很窄（Android 竖屏 / 分屏）时优先保证按钮仍在屏幕内
        int leftW = Math.max(150, Math.min(240, this.width / 3));
        int px = pad;
        int y = pad + 22;

        pvX = px + leftW + 10;
        pvY = pad + 22;
        pvW = Math.max(40, this.width - pvX - pad);
        pvH = Math.max(60, this.height - pad * 2 - 60);

        int cx = px;
        int cw = leftW;
        // 参数区的垂直预算：不能压到底部的「保存/取消/删除」按钮
        bottomLimit = this.height - pad - 48;

        if (widget instanceof TextWidget tw) {
            textBox = editBox(cx + 4, y + 10, cw - 8, 18, tw.text, 512, s -> {
                tw.text = s;
                refitTextBox();
            });
            y += 36;
            button("\u5b57\u4f53\uff1a" + FontManager.displayName(tw.fontId), cx + 4, y, cw - 8, 18,
                    b -> openChild(new FontSelectorScreen(this, tw.fontId, id -> {
                        tw.fontId = id;
                        rebuildWidgets();
                    })));
            y += 22;
            button("\u5bf9\u9f50\uff1a" + alignName(tw.align), cx + 4, y, cw / 2 - 6, 18, b -> {
                tw.align = (tw.align + 1) % 3;
                rebuildWidgets();
            });
            button("\u80cc\u666f\uff1a" + (tw.background == 0 ? "\u65e0" : "\u6709"), cx + cw / 2 + 2, y, cw / 2 - 6, 18, b -> {
                tw.background = tw.background == 0 ? 0x80000000 : 0;
                rebuildWidgets();
            });
            y += 22;
            // 调色盘放在靠上的位置：它是文字控件最常用的功能，
            // 若放在一长串滑块之后，屏幕不够高时会被空间判断直接跳过（玩家看不到调色盘）。
            y = colorPalette(cx, y, cw, tw);
            // 数值滑块放最后：空间不足时它们会被优雅地省略，不会影响上面的功能
            y = sliderRow(cx, y, cw, "\u5b57\u53f7", 0.5, 256, tw.fontSize, false,
                    v -> tw.fontSize = v);
            y = sliderRow(cx, y, cw, "\u884c\u8ddd", 0.6, 3.0, tw.lineSpacing, false,
                    v -> tw.lineSpacing = v);
            y = sliderRow(cx, y, cw, "\u81ea\u52a8\u6362\u884c\u5bbd\u5ea6", 0, 512, tw.wrapWidth, true,
                    v -> tw.wrapWidth = v <= 0.5 ? -1 : v);
            y = sliderRow(cx, y, cw, "\u65cb\u8f6c\u89d2\u5ea6", -180, 180, tw.rot, true,
                    v -> tw.rot = v);
            y = sliderRow(cx, y, cw, "\u4e0d\u900f\u660e\u5ea6", 0.05, 1.0, tw.alpha, false,
                    v -> tw.alpha = (float) (double) v);
            y = sliderRow(cx, y, cw, "\u5c42\u7ea7(z)", -20, 40, tw.zOff, true,
                    v -> tw.zOff = v);
        } else if (widget instanceof ImageWidget iw) {
            button("\u66ff\u6362\u56fe\u7247", cx + 4, y, cw - 8, 20, b ->
                    openChild(new MediaPickerScreen(this, plane, false, iw)));
            y += 24;
            y = sliderRow(cx, y, cw, "\u6a2a\u5411\u4f4d\u7f6e", -256, plane.width + 256, iw.x, true, v -> iw.x = v);
            y = sliderRow(cx, y, cw, "\u7eb5\u5411\u4f4d\u7f6e", -256, plane.height + 256, iw.y, true, v -> iw.y = v);
            y = sliderRow(cx, y, cw, "\u5bbd\u5ea6", 1, 512, iw.w, true, v -> iw.w = Math.max(0.5, v));
            y = sliderRow(cx, y, cw, "\u9ad8\u5ea6", 1, 512, iw.h, true, v -> iw.h = Math.max(0.5, v));
            y = sliderRow(cx, y, cw, "\u65cb\u8f6c\u89d2\u5ea6", -180, 180, iw.rot, true, v -> iw.rot = v);
            y = sliderRow(cx, y, cw, "\u4e0d\u900f\u660e\u5ea6", 0.05, 1.0, iw.alpha, false, v -> iw.alpha = (float) (double) v);
            y += 2;
            button("\u7b49\u6bd4\u4f8b", cx + 4, y, cw - 8, 18, b -> {
                if (iw.srcW > 0 && iw.srcH > 0) {
                    iw.h = iw.w * iw.srcH / (double) iw.srcW;
                    rebuildWidgets();
                }
            });
        } else if (widget instanceof VideoWidget vw) {
            button("\u66ff\u6362\u89c6\u9891", cx + 4, y, cw - 8, 20, b ->
                    openChild(new MediaPickerScreen(this, plane, true, vw)));
            y += 24;
            y = sliderRow(cx, y, cw, "\u5bbd\u5ea6", 1, 512, vw.w, true, v -> vw.w = Math.max(0.5, v));
            y = sliderRow(cx, y, cw, "\u9ad8\u5ea6", 1, 512, vw.h, true, v -> vw.h = Math.max(0.5, v));
            y = sliderRow(cx, y, cw, "\u65cb\u8f6c\u89d2\u5ea6", -180, 180, vw.rot, true, v -> vw.rot = v);
            y = sliderRow(cx, y, cw, "\u5e27\u7387 fps", 1, 30, vw.fps, true, v -> vw.fps = Math.max(1, v));
            y = sliderRow(cx, y, cw, "\u4e0d\u900f\u660e\u5ea6", 0.05, 1.0, vw.alpha, false, v -> vw.alpha = (float) (double) v);
            button(vw.loop ? "\u5faa\u73af\uff1a\u5f00" : "\u5faa\u73af\uff1a\u5173", cx + 4, y, cw / 2 - 6, 18, b -> {
                vw.loop = !vw.loop;
                rebuildWidgets();
            });
            button(vw.paused ? "\u6682\u505c\u4e2d" : "\u64ad\u653e\u4e2d", cx + cw / 2 + 2, y, cw / 2 - 6, 18, b -> {
                vw.paused = !vw.paused;
                rebuildWidgets();
            });
        } else if (widget instanceof ClockWidget cw2) {
            // ---- ⑦ 标题 / 样式 / 午别（最上面三个最常用的开关）----
            clockTitleBox = editBox(cx + 4, y, cw - 8, 18, cw2.title, 128, s -> cw2.title = s);
            y += 22;
            button(cw2.showTitle ? "标题：显示" : "标题：隐藏", cx + 4, y, cw / 2 - 6, 18, b -> {
                cw2.showTitle = !cw2.showTitle;
                rebuildWidgets();
            });
            int styleIdx = Math.floorMod(cw2.style, ClockWidget.STYLE_NAMES.length);
            button("样式：" + ClockWidget.STYLE_NAMES[styleIdx], cx + cw / 2 + 2, y, cw / 2 - 6, 18, b -> {
                cw2.style = (Math.floorMod(cw2.style, ClockWidget.STYLE_NAMES.length) + 1)
                        % ClockWidget.STYLE_NAMES.length;
                rebuildWidgets();
            });
            y += 22;
            button(cw2.showPeriod ? "午别：显示" : "午别：隐藏", cx + 4, y, cw / 2 - 6, 18, b -> {
                cw2.showPeriod = !cw2.showPeriod;
                rebuildWidgets();
            });
            button("字体：时钟…", cx + cw / 2 + 2, y, cw / 2 - 6, 18,
                    b -> openChild(new FontSelectorScreen(this, cw2.fontId, id -> {
                        cw2.fontId = id;
                        rebuildWidgets();
                    })));
            y += 22;

            // ---- 【关键】调色盘必须放在**靠前**的位置 ----
            // 以前它排在最后，而这个分支的行数一多就会超出 bottomLimit，
            // 于是 colorPalette 直接 return —— 玩家看到的就是「时钟不支持调色了」。
            y = colorPalette(cx, y, cw, cw2);

            // ---- 标题 / 午别 的字体与颜色 ----
            if (y + 44 <= bottomLimit) {
                button("更换标题字体…", cx + 4, y, cw / 2 - 6, 18,
                        b -> openChild(new FontSelectorScreen(this, cw2.titleFontId, id -> {
                            cw2.titleFontId = id;
                            rebuildWidgets();
                        })));
                button("更换午别字体…", cx + cw / 2 + 2, y, cw / 2 - 6, 18,
                        b -> openChild(new FontSelectorScreen(this, cw2.periodFontId, id -> {
                            cw2.periodFontId = id;
                            rebuildWidgets();
                        })));
                y += 20;
                // 当前字体名单独一行：按钮里塞不下这么长的名字（会被截断成「标题字体：M…」）
                textRow("时钟 " + FontManager.displayName(cw2.fontId)
                        + " / 标题 " + FontManager.displayName(cw2.titleFontId)
                        + " / 午别 " + FontManager.displayName(cw2.periodFontId), y);
                y += 12;
            }

            // ---- 数值滑块（放最后，空间不够时被跳过也不影响主要功能）----
            y = sliderRow(cx, y, cw, "时钟字号", 1, 128, cw2.fontSize, false, v -> cw2.fontSize = v);
            y = sliderRow(cx, y, cw, "标题字号", 1, 64, cw2.titleSize, false, v -> cw2.titleSize = v);
            y = sliderRow(cx, y, cw, "午别字号", 1, 64, cw2.periodSize, false, v -> cw2.periodSize = v);
            y = sliderRow(cx, y, cw, "时区偏移(分钟)", -720, 840, cw2.offsetMinutes, true,
                    v -> cw2.offsetMinutes = (int) Math.round(v));
            y = sliderRow(cx, y, cw, "旋转角度", -180, 180, cw2.rot, true, v -> cw2.rot = v);
            y = sliderRow(cx, y, cw, "层级(z)", -20, 40, cw2.zOff, true, v -> cw2.zOff = v);
        } else if (widget instanceof WeatherWidget ww) {
            button("\u66f4\u6362\u5929\u6c14\u5b57\u4f53\u2026", cx + 4, y, cw - 8, 18,
                    b -> openChild(new FontSelectorScreen(this, ww.fontId, id -> {
                        ww.fontId = id;
                        rebuildWidgets();
                    })));
            y += 22;
            y = sliderRow(cx, y, cw, "\u5b57\u53f7", 1, 128, ww.fontSize, false, v -> ww.fontSize = v);
            y = sliderRow(cx, y, cw, "\u56fe\u6807\u5927\u5c0f", 0.2, 1.0, ww.iconScale, false, v -> ww.iconScale = v);
            y = sliderRow(cx, y, cw, "\u65cb\u8f6c\u89d2\u5ea6", -180, 180, ww.rot, true, v -> ww.rot = v);
            button(ww.showIcon ? "\u56fe\u6807\uff1a\u5f00" : "\u56fe\u6807\uff1a\u5173", cx + 4, y, cw / 2 - 6, 18, b -> {
                ww.showIcon = !ww.showIcon;
                rebuildWidgets();
            });
            button(ww.showText ? "\u6587\u5b57\uff1a\u5f00" : "\u6587\u5b57\uff1a\u5173", cx + cw / 2 + 2, y, cw / 2 - 6, 18, b -> {
                ww.showText = !ww.showText;
                rebuildWidgets();
            });
            y += 22;
            y = colorPalette(cx, y, cw, ww);
        } else if (widget instanceof ProgressWidget pw) {
            titleBox = editBox(cx + 4, y, cw - 8, 18, pw.title, 128, s -> pw.title = s);
            y += 24;
            y = sliderRow(cx, y, cw, "\u6807\u9898\u5b57\u53f7", 1, 64, pw.titleSize, false, v -> pw.titleSize = v);
            y = sliderRow(cx, y, cw, "\u767e\u5206\u6bd4\u5b57\u53f7", 1, 128, pw.valueSize, false, v -> pw.valueSize = v);
            y = sliderRow(cx, y, cw, "\u6301\u7eed\u65f6\u95f4(\u79d2)", 0, 3600, pw.durationSeconds, true,
                    v -> pw.durationSeconds = v);
            y = sliderRow(cx, y, cw, "\u624b\u52a8\u767e\u5206\u6bd4", 0, 100, pw.manualProgress * 100, true,
                    v -> pw.manualProgress = v / 100.0);
            y = sliderRow(cx, y, cw, "\u65cb\u8f6c\u89d2\u5ea6", -180, 180, pw.rot, true, v -> pw.rot = v);
            // 【⑤.1】这两个按钮以前无视 bottomLimit 直接往下摆，屏幕一矮就会压住
            // 底部的「保存并返回 / 取消 / 删除控件」——而原版是按 children 顺序
            // 命中第一个控件，于是先加的它们会把保存按钮的点击全部吃掉。
            if (y + 22 <= bottomLimit) {
                button("\u91cd\u7f6e\u8fdb\u5ea6\u5230 0%", cx + 4, y, cw - 8, 18, b -> {
                    sendAction("reset", new CompoundTag());
                    previewMessage = "\u5df2\u91cd\u7f6e";
                });
                y += 22;
            }
            if (y + 22 <= bottomLimit) {
                button(pw.running ? "\u6682\u505c\u81ea\u52a8\u63a8\u8fdb" : "\u5f00\u59cb\u81ea\u52a8\u63a8\u8fdb",
                        cx + 4, y, cw - 8, 18, b -> {
                    sendAction(pw.running ? "pause" : "resume", new CompoundTag());
                });
            }
        } else if (widget instanceof top.hmjmfabc.projector.common.widget.TimerWidget tm) {
            y = timerBranch(tm, cx, y, cw);
        } else if (widget instanceof top.hmjmfabc.projector.common.widget.LeaderboardWidget lb) {
            y = leaderboardBranch(lb, cx, y, cw);
        } else if (widget instanceof top.hmjmfabc.projector.common.widget.ChessWidget ch) {
            y = chessBranch(ch, cx, y, cw);
        } else if (widget instanceof MusicWidget mw) {
            y = musicBranch(mw, cx, y, cw);
        }

        // 底部按钮
        int by = this.height - pad - 22;
        button(canEdit ? "\u4fdd\u5b58\u5e76\u8fd4\u56de" : "\u5173\u95ed\uff08\u65e0\u6743\u4fee\u6539\uff09",
                cx + 4, by, cw / 2 - 6, 20, b -> save());
        // 「取消」：恢复进入编辑器时的状态并返回（触屏环境没有 ESC 时的唯一退路）
        button("\u53d6\u6d88", cx + 4, by - 22, cw / 2 - 6, 18, b -> cancel());
        redButton("\u5220\u9664\u63a7\u4ef6", cx + cw / 2 + 2, by, cw / 2 - 6, 20, b -> {
            openChild(new ConfirmScreen(this, "\u786e\u5b9a\u5220\u9664\u6b64\u63a7\u4ef6\uff1f",
                    "\u5220\u9664\u540e\u65e0\u6cd5\u6062\u590d\u3002", () -> {
                pendingDelete = true;
                CompoundTag t = new CompoundTag();
                t.putUUID("widget", widget.id);
                sendEdit("removeWidget", t);
                // 控件已经删掉了，编辑器本身没有继续存在的意义：
                // 直接回到「管理平面」界面（而不是退回编辑器再让玩家自己关）。
                Minecraft.getInstance().setScreen(new PlaneDialogScreen(plane));
            }));
        }).active = canEdit;
    }

    private static String alignName(int align) {
        return switch (align) {
            case 1 -> "\u5c45\u4e2d";
            case 2 -> "\u53f3\u5bf9\u9f50";
            default -> "\u5de6\u5bf9\u9f50";
        };
    }

    // ------------------------------------------------------------------
    // ⑧ 计时器编辑区
    // ------------------------------------------------------------------

    private int timerBranch(top.hmjmfabc.projector.common.widget.TimerWidget tm, int cx, int y, int cw) {
        int half = cw / 2 - 6;
        // 【排序原则】布局在 y 超过 bottomLimit 时会直接跳过后面的行，
        // 所以按重要性排：运行控制 → 时长/剩余时间 → 绑定指令 → 外观。
        button("\u7c7b\u578b\uff1a" + top.hmjmfabc.projector.common.widget.TimerWidget.TYPE_NAMES[tm.type],
                cx + 4, y, half, 18, b -> {
            tm.type = (tm.type + 1) % top.hmjmfabc.projector.common.widget.TimerWidget.TYPE_NAMES.length;
            rebuildWidgets();
        });
        button("\u6837\u5f0f\uff1a" + top.hmjmfabc.projector.common.widget.TimerWidget.STYLE_NAMES[tm.style],
                cx + cw / 2 + 2, y, half, 18, b -> {
            tm.style = (tm.style + 1) % top.hmjmfabc.projector.common.widget.TimerWidget.STYLE_NAMES.length;
            rebuildWidgets();
        });
        y += 22;

        // ---- ① 最大时间：直接在输入框里打字，范围 0 ~ 2147483647 ----
        // （用户明确要求：最大值不是固定的 3600，而是在对话框里输入）
        textRow("\u6700\u5927\u65f6\u95f4\uff08\u79d2\uff0c0 ~ 2147483647\uff09", y);
        timerSecondsBox = editBox(cx + 4, y + 10, cw - 8, 18, secondsText(tm.durationSeconds), 10, s -> {
            long v = parseSeconds(s, tm.durationSeconds);
            tm.durationSeconds = v;
        });
        y += 32;

        // ---- ② 剩余时间：滑块 + 输入框，范围 **0 ~ 上面那个最大值** ----
        // 滑块上限跟着最大值走；最大值特别大时滑块会变粗，精确值用输入框给。
        double maxDur = Math.max(1.0, tm.durationSeconds);
        double remain = tm.remainingSeconds(gameTimeNow());
        textRow("\u5269\u4f59\u65f6\u95f4\uff08\u79d2\uff0c0 ~ " + secondsText(tm.durationSeconds) + "\uff09", y);
        y += 11;
        addRenderableWidget(new SliderBar(cx + 4, y, cw - 8, 12, 0, maxDur, remain, false, v -> {
            // 本地即时改，界面立刻跟着动
            tm.setRemainingSeconds(v, gameTimeNow());
            if (timerRemainBox != null) {
                timerRemainBox.setValue(secondsText(v));
            }
            // 【必须限流】滑块每拖动一像素就回调一次，而服务端处理 widget_action 会
            // markDirty + 广播整个平面。不限流的话拖一次滑块能发出上百个包，
            // 把服务端与所有客户端的网络都打满。
            // 这里 300ms 最多发一次；滑块的最终值在关闭编辑器时由 saveInternal()
            // 通过 updateWidget（acc/start 都在存档里）一定落到服务端。
            long now = System.currentTimeMillis();
            if (now - lastTimerSubmit >= 300L) {
                lastTimerSubmit = now;
                sendAction("setRemaining", remainArgs(v));
            }
        }));
        y += 16;
        timerRemainBox = editBox(cx + 4, y, cw - 8, 18, secondsText(remain), 10, s -> {
            long v = parseSeconds(s, remain);
            tm.setRemainingSeconds(v, gameTimeNow());
        });
        y += 24;

        // ---- ③ 控制按钮 ----
        if (y + 20 <= bottomLimit) {
            int third = (cw - 12) / 3;
            button("\u91cd\u65b0\u5f00\u59cb", cx + 4, y, third, 18, b -> {
                sendAction("restart", new CompoundTag());
                // 本地也立刻归位，免得等服务端往返时读数还是旧的
                tm.accumulatedTicks = 0;
                tm.startGameTime = gameTimeNow();
                tm.running = true;
                rebuildWidgets();
            });
            button(tm.running ? "\u6682\u505c" : "\u7ee7\u7eed", cx + 8 + third, y, third, 18,
                    b -> sendAction(tm.running ? "pause" : "resume", new CompoundTag()));
            button("\u5f52\u96f6", cx + 12 + third * 2, y, third, 18,
                    b -> sendAction("reset", new CompoundTag()));
            y += 22;
        }

        // ---- ④ 管理员：绑定倒计时结束时执行的指令 ----
        if (top.hmjmfabc.projector.client.ClientPermissions.isAdmin()) {
            textRow("\u7ed1\u5b9a\u6307\u4ee4\uff08\u5012\u8ba1\u65f6\u5f52\u96f6\u65f6\u6267\u884c\uff0c\u53ef\u7559\u7a7a\uff09", y);
            timerCommandBox = editBox(cx + 4, y + 10, cw - 8, 18, tm.command == null ? "" : tm.command, 256,
                    s -> tm.command = s);
            y += 32;
        }

        // ---- ⑤ 外观 ----
        // 字体按钮用短标签 + 单独一行显示当前字体名（长标签在窄面板里会被截断）
        if (y + 30 <= bottomLimit) {
            textRow("\u5f53\u524d\u5b57\u4f53\uff1a" + FontManager.displayName(tm.fontId), y);
            button("\u66f4\u6362\u5b57\u4f53\u2026", cx + 4, y + 11, cw - 8, 18,
                    b -> openChild(new FontSelectorScreen(this, tm.fontId, id -> {
                        tm.fontId = id;
                        rebuildWidgets();
                    })));
            y += 32;
        }
        y = sliderRow(cx, y, cw, "\u5b57\u53f7", 1, 128, tm.fontSize, false, v -> tm.fontSize = v);
        y = sliderRow(cx, y, cw, "\u65cb\u8f6c\u89d2\u5ea6", -180, 180, tm.rot, true, v -> tm.rot = v);
        y = sliderRow(cx, y, cw, "\u5c42\u7ea7(z)", -20, 40, tm.zOff, true, v -> tm.zOff = v);
        y = colorPalette(cx, y, cw, tm);
        return y;
    }

    /** 解析秒数输入框；解析不了（正在输入）时保留原值。 */
    private static long parseSeconds(String text, double fallback) {
        try {
            long v = Long.parseLong(text.trim());
            if (v < 0) v = 0;
            if (v > 2147483647L) v = 2147483647L;
            return v;
        } catch (NumberFormatException e) {
            return Math.max(0, Math.round(fallback));
        }
    }

    private static long gameTimeNow() {
        var level = Minecraft.getInstance().level;
        return level == null ? 0L : level.getGameTime();
    }

    private static CompoundTag remainArgs(double sec) {
        CompoundTag t = new CompoundTag();
        t.putDouble("sec", sec);
        return t;
    }

    /** 把秒数写成适合放进输入框的整数文本。 */
    private static String secondsText(double secs) {
        long v = (long) Math.floor(Math.max(0, secs));
        return String.valueOf(Math.min(v, 2147483647L));
    }

    // ------------------------------------------------------------------
    // ③ 排行榜编辑区
    // ------------------------------------------------------------------

    /** 排行榜当前正在编辑的那一部分（0 标题 / 1 序号 / 2 玩家名 / 3 分数）。 */
    private int lbPart;

    private static final String[] LB_PART_NAMES = {
            "\u6807\u9898", "\u5e8f\u53f7", "\u73a9\u5bb6\u540d", "\u5206\u6570"};

    private int leaderboardBranch(top.hmjmfabc.projector.common.widget.LeaderboardWidget lb,
                                  int cx, int y, int cw) {
        // 计分板项：输入框 + 「循环选择」（把当前世界已有的 objective 挨个试一遍）
        textRow("\u8ba1\u5206\u677f\u9879\uff08objective\uff09", y);
        lbObjectiveBox = editBox(cx + 4, y + 10, cw - 8, 18, lb.objective, 128, s -> lb.objective = s);
        y += 32;
        button("\u5faa\u73af\u9009\u62e9\u73b0\u6709\u8ba1\u5206\u677f\u9879", cx + 4, y, cw - 8, 18, b -> {
            var names = top.hmjmfabc.projector.client.LeaderboardSource.objectiveNames();
            if (names.isEmpty()) {
                previewMessage = "\u5f53\u524d\u4e16\u754c\u6ca1\u6709\u4efb\u4f55\u8ba1\u5206\u677f\u9879";
            } else {
                int idx = names.indexOf(lb.objective);
                lb.objective = names.get((idx + 1) % names.size());
                previewMessage = "\u5df2\u5207\u5230 " + lb.objective;
                rebuildWidgets();
            }
        });
        y += 24;
        textRow("\u6807\u9898", y);
        lbTitleBox = editBox(cx + 4, y + 10, cw - 8, 18, lb.title, 128, s -> lb.title = s);
        y += 32;
        int half = cw / 2 - 6;
        button(lb.descending ? "\u6392\u5e8f\uff1a\u9ad8 \u2192 \u4f4e" : "\u6392\u5e8f\uff1a\u4f4e \u2192 \u9ad8",
                cx + 4, y, half, 18, b -> {
            lb.descending = !lb.descending;
            rebuildWidgets();
        });
        button("\u6700\u5927\u884c\u6570\uff1a" + lb.maxRows, cx + cw / 2 + 2, y, half, 18, b -> {
            lb.maxRows = lb.maxRows >= 20 ? 1 : lb.maxRows + 1;
            rebuildWidgets();
        });
        y += 22;
        // 四部分分别设字体与颜色：先用这个按钮选「当前编辑哪一部分」
        if (y + 22 <= bottomLimit) {
            button("\u5f53\u524d\u7f16\u8f91\uff1a" + LB_PART_NAMES[Math.floorMod(lbPart, 4)],
                    cx + 4, y, cw - 8, 18, b -> {
                lbPart = (Math.floorMod(lbPart, 4) + 1) % 4;
                rebuildWidgets();
            });
            y += 22;
            button("\u66f4\u6362\u8be5\u90e8\u5206\u5b57\u4f53\u2026",
                    cx + 4, y, cw - 8, 18, b -> openChild(new FontSelectorScreen(this, lbFontOf(lb), id -> {
                setLbFont(lb, id);
                rebuildWidgets();
            })));
            y += 22;
        }
        y = sliderRow(cx, y, cw, "\u6807\u9898\u5b57\u53f7", 1, 64, lb.titleSize, false, v -> lb.titleSize = v);
        y = sliderRow(cx, y, cw, "\u884c\u5b57\u53f7", 1, 64, lb.rowSize, false, v -> lb.rowSize = v);
        y = sliderRow(cx, y, cw, "\u884c\u8ddd", 0.6, 3.0, lb.lineSpacing, false, v -> lb.lineSpacing = v);
        y = colorPalette(cx, y, cw, lb);
        return y;
    }

    private String lbFontOf(top.hmjmfabc.projector.common.widget.LeaderboardWidget lb) {
        return switch (Math.floorMod(lbPart, 4)) {
            case 1 -> lb.indexFontId;
            case 2 -> lb.nameFontId;
            case 3 -> lb.scoreFontId;
            default -> lb.titleFontId;
        };
    }

    private void setLbFont(top.hmjmfabc.projector.common.widget.LeaderboardWidget lb, String id) {
        switch (Math.floorMod(lbPart, 4)) {
            case 1 -> lb.indexFontId = id;
            case 2 -> lb.nameFontId = id;
            case 3 -> lb.scoreFontId = id;
            default -> lb.titleFontId = id;
        }
    }

    /**
     * 排行榜的分部索引（把编辑器实例状态借给静态方法用，避免重复逻辑）。
     */
    private int lbPartOf(top.hmjmfabc.projector.common.widget.LeaderboardWidget lb) {
        return lbPart;
    }

    private void setLbColor(top.hmjmfabc.projector.common.widget.LeaderboardWidget lb, int argb) {
        switch (Math.floorMod(lbPart, 4)) {
            case 1 -> lb.indexColor = argb;
            case 2 -> lb.nameColor = argb;
            case 3 -> lb.scoreColor = argb;
            default -> lb.titleColor = argb;
        }
    }

    /** 在指定 y 处登记一行普通文字（供 render 统一绘制）。 */
    private void textRow(String text, int y) {
        staticRows.put(y, text);
    }

    private final java.util.Map<Integer, String> staticRows = new java.util.HashMap<>();

    /** 生成一行「标签 + 滑块」。 */
    private int sliderRow(int x, int y, int w, String name, double min, double max, double value,
                          boolean integer, java.util.function.Consumer<Double> setter) {
        if (y + 22 > bottomLimit) {
            // 空间不足：跳过这一行（宁可少几个滑块，也不要把按钮挤出屏幕）
            return y + 22;
        }
        if (!canEdit) {
            // 无权限时不注册可交互控件，避免玩家白忙一场后被服务端静默拒绝
            rowLabels.put(y, name);
            return y + 22;
        }
        // 滑块本身画在标签下方；标签里带上实时数值，玩家才能确认「到底调到多少了」。
        // 同时把改动同步到预览区，做到所见即所得。
        addRenderableWidget(new SliderBar(x + 4, y, w - 8, 12, min, max, value, integer, v -> {
            setter.accept(v);
            rowValues.put(y, formatValue(v, integer));
        }));
        rowLabels.put(y, name);
        rowValues.put(y, formatValue(value, integer));
        return y + 22;
    }

    /** 滑块数值的显示格式。 */
    private static String formatValue(double v, boolean integer) {
        if (integer) {
            return String.valueOf(Math.round(v));
        }
        if (Math.abs(v) >= 100) {
            return String.valueOf(Math.round(v));
        }
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    private final java.util.Map<Integer, String> rowValues = new java.util.HashMap<>();

    private final java.util.Map<Integer, String> rowLabels = new java.util.HashMap<>();

    /** 生成调色盘 + 样式按钮，返回下一个空闲 y。 */
    private int colorPalette(int x, int y, int w, Widget target) {
        // 只要求能放下「色块两行 + 样式一行」；空间再紧张也要把色块画出来，
        // 因为调色盘是文字控件最核心的功能之一。
        if (y + 44 > bottomLimit) {
            return y;
        }
        int swatch = 9;
        int perRow = Math.max(4, (w - 8) / (swatch + 1));
        for (int i = 0; i < 16; i++) {
            final int code = i;
            int bx = x + 4 + (i % perRow) * (swatch + 1);
            int by = y + (i / perRow) * (swatch + 1);
            final int color = FormatCodes.COLORS[i];
            addRenderableWidget(new ColorSwatch(bx, by, swatch, color, false, b -> applyColor(target, color)));
        }
        // 16 个色块需要 ceil(16/perRow) 行，之前少算一行会让第 16 个色块
        // 与下面的样式按钮重叠
        y += ((16 + perRow - 1) / perRow) * (swatch + 1) + 3;
        int bw = Math.max(28, (w - 10) / 5 - 2);
        styleButton(x + 4, y, bw, "\u7c97\u4f53", "&l", target);
        styleButton(x + 5 + bw, y, bw, "\u659c\u4f53", "&o", target);
        styleButton(x + 6 + bw * 2, y, bw, "\u4e0b\u5212\u7ebf", "&n", target);
        styleButton(x + 7 + bw * 3, y, bw, "\u5220\u9664\u7ebf", "&m", target);
        styleButton(x + 8 + bw * 4, y, bw, "\u91cd\u7f6e", "&r", target);
        y += 20;
        // ---- 【⑨.2 &z / ⑨.3 &s..e..】渐变选项 ----
        // 只有「有文本流」的控件才有意义（时钟/天气是直接改颜色字段，不吃格式化代码）。
        if (supportsText(target) && y + 20 <= bottomLimit) {
            int gw = Math.max(40, (w - 12) / 2);
            button("&z \u5f69\u8272\u6e10\u53d8", x + 4, y, gw, 18, b -> {
                insertCode("&z");
                previewMessage = "\u5df2\u63d2\u5165\u5f69\u8272\u6e10\u53d8 &z";
            });
            button(gradientMode ? "\u8bf7\u70b9\u8d77\u70b9\u8272\u2026" : "&s..e \u53cc\u8272\u6e10\u53d8",
                    x + 8 + gw, y, gw, 18, b -> {
                gradientMode = !gradientMode;
                gradientA = -1;
                previewMessage = gradientMode
                        ? "\u5148\u70b9\u4e00\u4e2a\u8272\u5757\u4f5c\u4e3a\u8d77\u70b9\u8272\uff0c\u518d\u70b9\u4e00\u4e2a\u4f5c\u4e3a\u7ec8\u70b9\u8272"
                        : "";
                rebuildWidgets();
            });
            y += 20;
        }
        return y;
    }

    // ------------------------------------------------------------------
    // ⑪ 棋类游戏编辑区
    // ------------------------------------------------------------------

    private int chessBranch(top.hmjmfabc.projector.common.widget.ChessWidget ch, int cx, int y, int cw) {
        // 先兜底再取 final 引用：lambda 里要改棋局，局部变量必须是 effectively final
        if (ch.game == null) {
            ch.game = new top.hmjmfabc.projector.common.game.GameSession();
            ch.game.reset();
        }
        final var g = ch.game;
        textRow("\u68cb\u79cd\uff1a" + top.hmjmfabc.projector.common.game.GameKind.name(g.kind)
                + "\uff08" + g.width() + "x" + g.height() + "\uff09", y);
        y += 12;
        button("\u5207\u6362\u68cb\u79cd", cx + 4, y, cw - 8, 18, b -> {
            g.setKind(top.hmjmfabc.projector.common.game.GameKind.next(g.kind));
            // 【必须撑框】换成更大的棋盘（例如井字棋 3x3 -> 五子棋 15x15）后，
            // 若不撑框，每格会缩到 2~3 画布单位，格线与棋子糊成一团，
            // 看上去就是「控件没渲染出来」。
            ch.fitToBoard();
            // 【rc-84】撑框按「每格 CELL_UNITS」撑，可能比平面还大（象棋要 7.3x8.1 格、
            // 五子棋要 12x12 格）；超出平面的那部分没有可点击的面，右键永远打不到 ——
            // 玩家会把「那边的点走不了」当成规则 bug。这里立刻收进平面。
            // 【rc-84】逻辑同上一行注释：撑框可能比平面还大，必须收进平面。
            // 说明文字交给日志（界面里 y 是 lambda 外的非 final 局部量，不能在闭包里用）。
            if (top.hmjmfabc.projector.common.widget.ChessWidget.fitIntoPlane(
                    ch, plane.width, plane.height)) {
                top.hmjmfabc.projector.Projector.LOGGER.info(
                        "[Projector] 棋盘控件已按平面大小缩小以完整落在平面内"
                                + "（平面 {}x{} 单位，原尺寸装不下；超出平面的点位右键打不到）",
                        plane.width, plane.height);
            }
            rebuildWidgets();
            // 尺寸变了要一并提交，否则只有本地看着正常
            saveInternal();
        }).active = canEdit;
        y += 22;
        textRow("\u6a21\u5f0f\uff1a" + top.hmjmfabc.projector.common.game.GameMode.name(g.mode), y);
        y += 12;
        button("\u5207\u6362\u6a21\u5f0f", cx + 4, y, cw - 8, 18, b -> {
            g.mode = (g.mode + 1) % top.hmjmfabc.projector.common.game.GameMode.NAMES.length;
            rebuildWidgets();
        }).active = canEdit;
        y += 22;
        // 【⑪】AI 智能档位：正常（会失误）/ 高（原来那一版）/ 极限
        textRow("AI \u667a\u80fd\uff1a"
                + top.hmjmfabc.projector.common.game.GameDifficulty.name(g.difficulty), y);
        y += 12;
        button("\u5207\u6362 AI \u667a\u80fd", cx + 4, y, cw - 8, 18, b -> {
            g.difficulty = top.hmjmfabc.projector.common.game.GameDifficulty.next(g.difficulty);
            rebuildWidgets();
        }).active = canEdit;
        y += 22;
        // 真正下棋在专门的对局界面里：六种棋的棋盘尺寸差得太多，
        // 在墙上那点面积里点格子既不准也不适合触屏。
        button("\u6253\u5f00\u5bf9\u5c40\u754c\u9762\u2026", cx + 4, y, cw - 8, 20, b ->
                openChild(new ChessGameScreen(plane, ch, this))).active = canEdit;
        y += 24;
        button("\u91cd\u65b0\u5f00\u5c40", cx + 4, y, cw - 8, 18, b -> {
            g.reset();
            rebuildWidgets();
        }).active = canEdit;
        y += 22;
        button(ch.showGrid ? "\u683c\u7ebf\uff1a\u5f00" : "\u683c\u7ebf\uff1a\u5173", cx + 4, y, cw / 2 - 6, 18, b -> {
            ch.showGrid = !ch.showGrid;
            rebuildWidgets();
        });
        button(ch.highlightLast ? "\u9ad8\u4eae\u6700\u540e\u4e00\u624b" : "\u4e0d\u9ad8\u4eae", cx + cw / 2 + 2, y, cw / 2 - 6, 18, b -> {
            ch.highlightLast = !ch.highlightLast;
            rebuildWidgets();
        });
        y += 22;
        button("\u66f4\u6362\u68cb\u5b50\u5b57\u4f53\u2026", cx + 4, y, cw - 8, 18,
                b -> openChild(new FontSelectorScreen(this, ch.fontId, id -> {
                    ch.fontId = id;
                    rebuildWidgets();
                })));
        y += 22;
        y = sliderRow(cx, y, cw, "\u68cb\u5b50\u5b57\u53f7", 1, 64, ch.fontSize, false, v -> ch.fontSize = v);
        y = sliderRow(cx, y, cw, "\u65cb\u8f6c\u89d2\u5ea6", -180, 180, ch.rot, true, v -> ch.rot = v);
        y = sliderRow(cx, y, cw, "\u5c42\u7ea7(z)", -20, 40, ch.zOff, true, v -> ch.zOff = v);
        return y;
    }

    /** 这个控件是否有「文本流」可以插入格式化代码。 */
    private static boolean supportsText(Widget target) {
        return target instanceof TextWidget || target instanceof ProgressWidget;
    }

    private static String hex6(int argb) {
        return String.format(java.util.Locale.ROOT, "%06X", argb & 0xFFFFFF);
    }

    private void styleButton(int x, int y, int w, String label, String code, Widget target) {
        button(label, x, y, w, 18, b -> insertCode(code));
    }

    private void applyColor(Widget target, int argb) {
        // 【⑨.3】双色渐变模式：第一次点色块 = 起点色，第二次 = 终点色，
        // 两次点完就把 &s#AAAAAA&#BBBBBB 插进文本流。
        if (gradientMode && supportsText(target)) {
            if (gradientA < 0) {
                gradientA = argb;
                previewMessage = "\u8d77\u70b9\u8272 " + hex6(argb) + "\uff0c\u518d\u70b9\u4e00\u4e2a\u8272\u5757\u4f5c\u4e3a\u7ec8\u70b9\u8272";
                return;
            }
            String code = "&s#" + hex6(gradientA) + "&#" + hex6(argb);
            gradientMode = false;
            gradientA = -1;
            insertCode(code);
            previewMessage = "\u5df2\u63d2\u5165\u53cc\u8272\u6e10\u53d8 " + code;
            return;
        }
        // 【27.1.1】音乐控件：调色盘改的是「进度色」（波形已放部分 + 播放键图形）
        if (target instanceof MusicWidget mw) {
            mw.accentColor = argb;
            previewMessage = "\u97f3\u4e50\u8fdb\u5ea6\u8272 " + hex6(argb);
            return;
        }
        // 【③b】排行榜：调色盘改的是「当前编辑的那一部分」的颜色
        if (target instanceof top.hmjmfabc.projector.common.widget.LeaderboardWidget lb) {
            setLbColor(lb, argb);
            previewMessage = LB_PART_NAMES[Math.floorMod(lbPart, 4)] + "\u989c\u8272\u5df2\u66f4\u65b0 " + hex6(argb);
            return;
        }
        // 【⑧】计时器：调色盘改的是数字颜色
        if (target instanceof top.hmjmfabc.projector.common.widget.TimerWidget tm) {
            tm.color = argb;
            previewMessage = "\u989c\u8272\u5df2\u66f4\u65b0 " + hex6(argb);
            return;
        }
        String code = "&#" + hex6(argb);
        insertCode(code);
    }

    /** 把格式化代码插入到当前文本框光标处（或直接改颜色字段）。 */
    private void insertCode(String code) {
        if (widget instanceof TextWidget && textBox != null) {
            String v = textBox.getValue();
            int cursor = textBox.getCursorPosition();
            String nv = v.substring(0, Math.min(cursor, v.length())) + code + v.substring(Math.min(cursor, v.length()));
            textBox.setValue(nv);
            ((TextWidget) widget).text = nv;
            return;
        }
        if (widget instanceof ClockWidget cw) {
            // ⑦ 时钟标题现在也是有文本流的：标题输入框处于编辑状态时，
            // 调色盘/格式按钮一律插到标题里；否则维持旧行为（直接改时钟颜色字段）。
            if (clockTitleBox != null && clockTitleBox.isFocused()) {
                String v = clockTitleBox.getValue();
                int cursor = clockTitleBox.getCursorPosition();
                int at = Math.min(cursor, v.length());
                String nv = v.substring(0, at) + code + v.substring(at);
                clockTitleBox.setValue(nv);
                cw.title = nv;
                return;
            }
            handleColorField(cw, code);
        } else if (widget instanceof WeatherWidget ww) {
            handleColorField(ww, code);
        } else if (widget instanceof ProgressWidget pw && titleBox != null) {
            String v = titleBox.getValue();
            int cursor = titleBox.getCursorPosition();
            String nv = v.substring(0, Math.min(cursor, v.length())) + code + v.substring(Math.min(cursor, v.length()));
            titleBox.setValue(nv);
            pw.title = nv;
        }
    }

    private void handleColorField(Object target, String code) {
        int color = parseColorCode(code);
        if (color == 0) color = 0xFFFFFFFF;
        if (target instanceof ClockWidget cw) {
            cw.color = color;
        } else if (target instanceof WeatherWidget ww) {
            ww.color = color;
        }
        previewMessage = "\u989c\u8272\u5df2\u66f4\u65b0";
    }

    private static int parseColorCode(String code) {
        if (code == null) return 0;
        if (code.startsWith("&#") && code.length() >= 8) {
            try {
                return 0xFF000000 | Integer.parseInt(code.substring(2, 8), 16);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        if (code.length() >= 2 && code.charAt(0) == '&') {
            int idx = FormatCodes.COLOR_CHARS.indexOf(Character.toLowerCase(code.charAt(1)));
            if (idx >= 0) return FormatCodes.COLORS[idx];
        }
        return 0;
    }

    // ------------------------------------------------------------------
    // 保存
    // ------------------------------------------------------------------

    private void save() {
        if (pendingDelete) {
            return;
        }
        saved = true;
        saveInternal();
        closeTo(parent);
    }

    /**
     * 按当前文字内容重排文字控件的方框。
     *
     * <p><b>必须在输入框的回调里实时调用。</b>文字输入框是「边打边写」到
     * {@code tw.text} 的，如果只在关闭界面时才重排方框，那么整个编辑过程中
     * 方框都停留在旧尺寸上，看上去就是「控件框跟文本对不上」。</p>
     *
     * <p>玩家手动拖过尺寸（{@code manualSize}）时不动方框，改为让文字按框宽折行，
     * 保证文字仍然落在框里。</p>
     */
    private void refitTextBox() {
        try {
            refitTextBox0();
        } catch (Throwable t) {
            // 这里在输入框回调里执行：一旦抛异常会打断 charTyped，
            // 玩家表现为「打字打不进去 / 保存不了」。宁可放弃重排，也不能影响输入与保存。
            if (!refitErrorLogged) {
                refitErrorLogged = true;
                top.hmjmfabc.projector.Projector.LOGGER.error("[Projector] 文字控件尺寸重排失败", t);
            }
        }
    }

    /** 尺寸重排失败只记一次日志。 */
    private static boolean refitErrorLogged;

    private void refitTextBox0() {
        if (!(widget instanceof TextWidget tw)) return;
        var font = FontManager.get(tw.fontId);
        if (font == null) return;
        if (tw.manualSize) {
            // 手动定过框：让文字折行适配框宽，而不是溢出到框外
            if (tw.w > 1 && (tw.wrapWidth <= 0 || Math.abs(tw.wrapWidth - tw.w) > 0.5)) {
                tw.wrapWidth = tw.w;
            }
            return;
        }
        var m = top.hmjmfabc.projector.common.text.TextLayout.measure(tw.text,
                FontManager.metrics(font), tw.fontSize, tw.wrapWidth, tw.lineSpacing);
        double bw = m.width() + 2;
        double bh = m.height() + 2;
        // 兜底：任何非有限/非正数都不写进控件——NaN 会被服务端判为非法而整包拒绝，
        // 表现就是「保存没反应」。
        if (!Double.isFinite(bw) || !Double.isFinite(bh) || bw <= 0 || bh <= 0) return;
        tw.setSize(bw, bh);
    }

    private void saveInternal() {
        if (!canEdit) {
            // 无权限：服务端一定会拒绝，不必浪费一次网络往返
            return;
        }
        if (textBox != null && widget instanceof TextWidget tw) {
            tw.text = textBox.getValue();
            // 关键：只有当「文字内容 / 字体 / 字号」真的发生变化时才重算尺寸。
            // 否则玩家在缩略图里拖好的尺寸会在每次保存时被打回按内容自适应的结果，
            // 表现出来就是「拖了大小，一移动又变回原样」。
            boolean contentChanged = !tw.text.equals(initialText)
                    || !java.util.Objects.equals(tw.fontId, initialFontId)
                    || Math.abs(tw.fontSize - initialFontSize) > 1.0e-6;
            // 内容变了、或者框明显装不下内容（旧存档里的占位尺寸 64x16）都要重排。
            // 具体逻辑见 refitTextBox()：manualSize=false 时贴合内容，
            // manualSize=true 时改为让文字按框宽折行。
            boolean boxTooSmall = false;
            var font = FontManager.get(tw.fontId);
            if (font != null) {
                var m0 = top.hmjmfabc.projector.common.text.TextLayout.measure(tw.text,
                        FontManager.metrics(font), tw.fontSize, tw.wrapWidth, tw.lineSpacing);
                boxTooSmall = m0.width() > tw.w + 0.5 || m0.height() > tw.h + 0.5;
            }
            if (contentChanged || boxTooSmall) {
                refitTextBox();
            }
        }
        if (titleBox != null && widget instanceof ProgressWidget pw) {
            pw.title = titleBox.getValue();
        }
        if (clockTitleBox != null && widget instanceof ClockWidget cwk) {
            cwk.title = clockTitleBox.getValue();
        }
        // ⑧ 计时器：把输入框里的精确秒数与指令写回控件
        if (widget instanceof top.hmjmfabc.projector.common.widget.TimerWidget tm) {
            if (timerSecondsBox != null) {
                try {
                    long v = Long.parseLong(timerSecondsBox.getValue().trim());
                    if (v < 0) v = 0;
                    if (v > 2147483647L) v = 2147483647L;
                    tm.durationSeconds = v;
                } catch (NumberFormatException ignored) {
                    // 输入框内容不是数字：保留滑块设的值
                }
            }
            if (timerRemainBox != null) {
                tm.setRemainingSeconds(parseSeconds(timerRemainBox.getValue(),
                        tm.remainingSeconds(gameTimeNow())), gameTimeNow());
            }
            if (timerCommandBox != null) {
                String cmd = timerCommandBox.getValue();
                if (cmd.startsWith("/")) cmd = cmd.substring(1);
                tm.command = cmd.trim();
            }
        }
        // ③ 排行榜：把输入框内容写回
        if (widget instanceof top.hmjmfabc.projector.common.widget.LeaderboardWidget lb) {
            if (lbObjectiveBox != null) lb.objective = lbObjectiveBox.getValue().trim();
            if (lbTitleBox != null) lb.title = lbTitleBox.getValue();
        }
        sendEdit("updateWidget", widgetArgs());
        // 一条精简回执：确认「到底发出去了什么」。服务端若拒绝会另打一条 WARN。
        if (widget instanceof TextWidget tw2) {
            top.hmjmfabc.projector.Projector.LOGGER.info(
                    "[Projector] 提交文字控件：文本长度={} 字号={} 框={}x{} 单位 对齐={} 手动尺寸={}",
                    tw2.text.length(), tw2.fontSize, tw2.w, tw2.h, tw2.align, tw2.manualSize);
        }
        if (widget instanceof ProgressWidget pw) {
            // 只有持续时间或标题真的变了才重启计时：
            // 否则「顺手打开编辑器看一眼再关掉」也会把进度打回 0%。
            CompoundTag extra = new CompoundTag();
            extra.putDouble("duration", pw.durationSeconds);
            extra.putString("title", pw.title);
            boolean durationChanged = Math.abs(pw.durationSeconds - initialDurationSeconds) > 1.0e-3;
            if (durationChanged || !pw.running) {
                extra.putBoolean("restart", true);
            }
            sendAction("set", extra);
        }
    }

    /**
     * 显式关闭并返回父界面。
     *
     * <p>切屏动作<b>只</b>在这里做：不要放在 {@code removed()} 里，
     * 因为原版 {@code Minecraft.setScreen} 是先调用 {@code removed()}、
     * 再更新 {@code screen} 字段，在 {@code removed()} 里再次调用 {@code setScreen}
     * 会造成嵌套重入（父界面被初始化两遍）。</p>
     */
    private void closeTo(Screen target) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == this) {
            mc.setScreen(target);
        }
    }

    private CompoundTag widgetArgs() {
        CompoundTag t = new CompoundTag();
        t.putUUID("widget", widget.id);
        // 【必须用不同的键】以前这里两行都用 "widget"，第二个 put 把 UUID 覆盖掉，
        // 服务端 hasUUID("widget") 永远为假 -> 所有编辑都保存不了。
        t.put("data", widget.save());
        return t;
    }

    private void sendEdit(String op, CompoundTag args) {
        PacketDistributor.sendToServer(new Payloads.PlaneEdit(plane.id, op, args));
    }

    private void sendAction(String action, CompoundTag args) {
        PacketDistributor.sendToServer(new Payloads.WidgetAction(plane.id, widget.id, action, args));
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        // 背景面板必须先于控件绘制，否则会把按钮/滑块盖上一层半透明深色
        int padBefore = 10;
        int leftWBefore = Math.max(150, Math.min(240, this.width / 3));
        panel(gfx, padBefore, padBefore, leftWBefore, this.height - padBefore * 2);
        super.render(gfx, mouseX, mouseY, partialTick);
        int pad = 10;
        int leftW = Math.max(150, Math.min(240, this.width / 3));
        labelShadow(gfx, kindName(widget), pad + 6, pad + 8, TEXT_ACCENT);
        label(gfx, canEdit ? "\u53ef\u7f16\u8f91" : "\u53d7\u4fdd\u62a4\uff0c\u65e0\u6743\u4fee\u6539",
                pad + 6, pad + this.height - pad * 2 - 12, canEdit ? TEXT_GREEN : TEXT_RED);

        // 滑块标签 + 当前数值（数值靠右对齐，方便确认调节结果）
        for (var e : rowLabels.entrySet()) {
            int ly = e.getKey() - 9;
            label(gfx, e.getValue(), pad + 6, ly, TEXT_DIM);
            String value = rowValues.get(e.getKey());
            if (value != null) {
                int vw = font.width(value);
                label(gfx, value, pad + leftW - 10 - vw, ly, TEXT_ACCENT);
            }
        }
        // 普通说明行（③⑧ 新控件的标签）
        for (var e : staticRows.entrySet()) {
            label(gfx, e.getValue(), pad + 6, e.getKey(), TEXT_DIM);
        }

        // 预览区
        panel(gfx, pvX, pvY, pvW, pvH);
        label(gfx, "\u9884\u89c8\uff08\u81ea\u52a8\u653e\u5927\uff09", pvX + 6, pvY + 5, TEXT_ACCENT);
        if (widget instanceof TextWidget tw) {
            int infoW = font.width("\u5b57\u53f7 " + String.format(java.util.Locale.ROOT, "%.1f", tw.fontSize));
            label(gfx, "\u5b57\u53f7 " + String.format(java.util.Locale.ROOT, "%.1f", tw.fontSize)
                    + "  \u5b57\u4f53 " + FontManager.displayName(tw.fontId),
                    pvX + pvW - 6 - font.width("\u5b57\u53f7 000.0  \u5b57\u4f53 " + FontManager.displayName(tw.fontId)),
                    pvY + 5, TEXT_DIM);
        }
        drawPreview(gfx);
        if (!previewMessage.isEmpty()) {
            label(gfx, previewMessage, pvX + 6, pvY + pvH - 12, TEXT_DIM);
        }
    }

    /** 在 2D 屏幕空间里预览控件（使用与游戏内完全相同的渲染代码）。 */
    private void drawPreview(GuiGraphics gfx) {
        int vx = pvX + 6;
        int vy = pvY + 18;
        int vw = pvW - 12;
        int vh = pvH - 36;
        if (vw <= 8 || vh <= 8) return;

        // 预览策略：**对准控件本身**并自动放大，而不是把整张平面塞进来。
        // 否则平面一大（例如 9x9 格的墙），控件在预览里只有几个像素，既看不清也调不了。
        double[] aabb = top.hmjmfabc.projector.client.render.SelectionRenderer.widgetAabb(widget);
        double wW = Math.max(1.0, aabb[2] - aabb[0]);
        double wH = Math.max(1.0, aabb[3] - aabb[1]);
        double padU = Math.max(4.0, Math.max(wW, wH) * 0.15);
        double fitW = wW + padU * 2;
        double fitH = wH + padU * 2;
        double scale = Math.min(vw / fitW, vh / fitH);
        // 上限 12 倍，避免很小的控件被拉成模糊的一大块
        scale = Math.min(scale, 12.0);
        if (!Double.isFinite(scale) || scale <= 0) scale = 1.0;

        // 让控件外接矩形居中：origin 是画布 (0,0) 对应的屏幕位置
        double cxCanvas = (aabb[0] + aabb[2]) / 2.0;
        double cyCanvas = (aabb[1] + aabb[3]) / 2.0;
        double ox = vx + vw / 2.0 - cxCanvas * scale;
        double oy = vy + vh / 2.0 + cyCanvas * scale;

        gfx.fill(vx, vy, vx + vw, vy + vh, 0xFF101018);
        gfx.enableScissor(vx, vy, vx + vw, vy + vh);

        double[] origin = {ox, oy, 0};
        double[] axisX = {scale, 0, 0};
        double[] axisY = {0, -scale, 0};
        // 屏幕空间里「朝向观察者」就是 -Z
        double[] normal = {0, 0, -1};
        PlaneRenderContext ctx = new PlaneRenderContext(axisX, axisY, normal, origin, 0);

        // 关键：原版 GUI 的所有 2D 内容（fill / drawString）都画在 z=0 且开启深度测试，
        // 如果我们的预览也画在 z=0，深度比较会是平局（LEQUAL）而不稳定，
        // 结果就是「预览完全不显示」。这里先把 GUI 已有的顶点冲刷掉，
        // 再把预览放到 z=+0.1（更靠近摄像机）并临时关掉深度测试。
        gfx.flush();
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();
        gfx.pose().pushPose();
        gfx.pose().setIdentity();
        QuadCollector.beginFrame();
        preview.clear();
        try {
            WidgetRenderer.draw(preview, plane, ctx, widget);
            preview.flush(gfx.pose());
        } catch (Throwable t) {
            // 预览失败不应影响编辑，但必须留下线索（否则字体/媒体坏了完全查不出来）
            if (!previewErrorLogged) {
                previewErrorLogged = true;
                top.hmjmfabc.projector.Projector.LOGGER.error("[Projector] 控件预览渲染失败（后续同类错误不再重复记录）", t);
            }
        }
        gfx.pose().popPose();
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
        gfx.disableScissor();

        // 控件边框
        gfx.fill(vx, vy, vx + vw, vy + 1, PANEL_BORDER);
    }

    /**
     * 音乐控件专用的编辑区（27.1.1）。
     *
     * <p>能改的东西：歌（打开音乐选择器）、音量、圆角、字号、颜色，以及
     * 「世界里点播放键就能启停」这个交互之外的手动控制按钮。</p>
     */
    private int musicBranch(MusicWidget mw, int cx, int y, int cw) {
        String name = mw.title == null || mw.title.isBlank() ? "（未选择音乐）" : mw.title;
        if (mw.artist != null && !mw.artist.isBlank()) {
            name = name + " - " + mw.artist;
        }
        button("\u9009\u62e9\u97f3\u4e50\uff1a" + shorten(name, 18), cx + 4, y, cw - 8, 20, b ->
                openChild(new MusicPickerScreen(this, plane, mw, track -> {
                    top.hmjmfabc.projector.client.music.MusicTrack.applyTo(mw, track);
                    top.hmjmfabc.projector.client.music.MusicManager.onTrackChanged(plane, mw);
                    CompoundTag t = new CompoundTag();
                    t.putUUID("widget", mw.id);
                    t.put("data", mw.save());
                    PlaneDialogScreen.sendFor(plane, "updateWidget", t);
                    rebuildWidgets();
                })));
        y += 24;

        if (y + 22 <= bottomLimit) {
            button(mw.playing ? "\u6682\u505c\uff08\u4e16\u754c\u91cc\u70b9\u64ad\u653e\u952e\u4e5f\u884c\uff09"
                            : "\u5f00\u59cb\u64ad\u653e\uff08\u4e16\u754c\u91cc\u70b9\u64ad\u653e\u952e\u4e5f\u884c\uff09",
                    cx + 4, y, cw - 8, 18, b -> {
                CompoundTag extra = new CompoundTag();
                sendAction(mw.playing ? "pause" : "resume", extra);
            });
            y += 22;
        }

        y = sliderRow(cx, y, cw, "\u64ad\u653e\u8fdb\u5ea6", 0.0, 1.0, mw.progressAt(
                top.hmjmfabc.projector.client.music.MusicManager.gameTime()), false, v -> {
            CompoundTag extra = new CompoundTag();
            extra.putDouble("fraction", v);
            sendAction("seek", extra);
        });
        y = sliderRow(cx, y, cw, "\u97f3\u91cf", 0.0, 1.0, mw.volume, false, v -> mw.volume = v);
        y = sliderRow(cx, y, cw, "\u5706\u89d2\u534a\u5f84", 0, 24, mw.corner, true, v -> mw.corner = v);
        y = sliderRow(cx, y, cw, "\u5b57\u53f7", 1, 64, mw.fontSize, false, v -> mw.fontSize = v);
        y = sliderRow(cx, y, cw, "\u7ad6\u6761\u6570", 0, 128, mw.barCount, true,
                v -> mw.barCount = (int) (double) v);
        y = sliderRow(cx, y, cw, "\u5bbd\u5ea6", 8, 512, mw.w, true, v -> mw.w = Math.max(4, v));
        y = sliderRow(cx, y, cw, "\u9ad8\u5ea6", 4, 256, mw.h, true, v -> mw.h = Math.max(3, v));
        y = sliderRow(cx, y, cw, "\u65cb\u8f6c\u89d2\u5ea6", -180, 180, mw.rot, true, v -> mw.rot = v);
        y = sliderRow(cx, y, cw, "\u4e0d\u900f\u660e\u5ea6", 0.05, 1.0, mw.alpha, false, v -> mw.alpha = (float) (double) v);
        y = sliderRow(cx, y, cw, "\u5c42\u7ea7(z)", -20, 40, mw.zOff, true, v -> mw.zOff = v);
        if (y + 22 <= bottomLimit) {
            button(mw.showLyric ? "\u6b4c\u8bcd\uff1a\u663e\u793a" : "\u6b4c\u8bcd\uff1a\u5173\u95ed",
                    cx + 4, y, cw / 2 - 6, 18, b -> {
                mw.showLyric = !mw.showLyric;
                rebuildWidgets();
            });
            button("\u5b57\u4f53\uff1a\u97f3\u4e50\u2026", cx + cw / 2 + 2, y, cw / 2 - 6, 18,
                    b -> openChild(new FontSelectorScreen(this, mw.fontId, id -> {
                        mw.fontId = id;
                        rebuildWidgets();
                    })));
            y += 22;
        }
        y = colorPalette(cx, y, cw, mw);
        return y;
    }

    private static String shorten(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "\u2026";
    }

    private static String kindName(Widget w) {
        return switch (w.kind()) {
            case Widget.KIND_TEXT -> "\u6587\u672c\u63a7\u4ef6";
            case Widget.KIND_IMAGE -> "\u56fe\u7247\u63a7\u4ef6";
            case Widget.KIND_VIDEO -> "\u89c6\u9891\u63a7\u4ef6";
            case Widget.KIND_CLOCK -> "\u65f6\u949f\u63a7\u4ef6";
            case Widget.KIND_WEATHER -> "\u5929\u6c14\u63a7\u4ef6";
            case Widget.KIND_PROGRESS -> "\u767e\u5206\u6bd4\u8fdb\u5ea6\u63a7\u4ef6";
            case Widget.KIND_TIMER -> "\u8ba1\u65f6\u5668\u63a7\u4ef6";
            case Widget.KIND_LEADERBOARD -> "\u6392\u884c\u699c\u63a7\u4ef6";
            case Widget.KIND_CHESS -> "\u68cb\u7c7b\u6e38\u620f\u63a7\u4ef6";
            case Widget.KIND_MUSIC -> "\u97f3\u4e50\u63a7\u4ef6";
            default -> "\u63a7\u4ef6";
        };
    }

    /** 放弃本次修改：把控件恢复到进入编辑器时的状态并返回。 */
    private void cancel() {
        try {
            if (initialSnapshot != null) {
                Widget restored = top.hmjmfabc.projector.common.widget.Widgets.load(initialSnapshot);
                if (restored != null && restored.kind() == widget.kind()) {
                    widget.loadCommon(restored.save());
                    widget.loadExtra(restored.save());
                    CompoundTag t = new CompoundTag();
                    t.putUUID("widget", widget.id);
                    t.put("data", widget.save());
                    sendEdit("updateWidget", t);
                    if (widget instanceof ProgressWidget pw) {
                        CompoundTag extra = new CompoundTag();
                        extra.putDouble("duration", pw.durationSeconds);
                        extra.putString("title", pw.title);
                        extra.putBoolean("restart", true);
                        sendAction("set", extra);
                    }
                }
            }
        } catch (Throwable t) {
            top.hmjmfabc.projector.Projector.LOGGER.warn("[Projector] 取消编辑时恢复失败", t);
        }
        pendingDelete = true; // 阻止 onClose/removed 再次提交
        closeTo(parent);
    }

    @Override
    public void removed() {
        // 关键：原版在「进入传送门 / 死亡」等情况下会直接 setScreen(...) 顶掉当前界面，
        // 此时只会调用 removed()，不会调用 onClose()。把提交逻辑放在这里可以保证
        // 未提交的修改不会因为一次传送而丢失。
        super.removed();
        if (!pendingDelete && !saved && !childScreen) {
            commit();
        }
    }

    /** 打开子界面：标记一下，避免 removed() 误判为「被顶掉」而自动提交。 */
    private void openChild(Screen child) {
        childScreen = true;
        Minecraft.getInstance().setScreen(child);
    }

    /** 标记已经提交过，避免 removed() 与 onClose() 重复提交。 */
    private boolean saved;

    private void commit() {
        saved = true;
        saveInternal();
    }

    @Override
    public void onClose() {
        // ESC / Android 返回键都会走到这里。原版的 onClose() 才负责关屏，
        // 只提交不关屏会让「按 ESC 退不出去」，而且每按一次重复提交一次。
        if (!pendingDelete) {
            commit();
        }
        closeTo(parent);
    }
}

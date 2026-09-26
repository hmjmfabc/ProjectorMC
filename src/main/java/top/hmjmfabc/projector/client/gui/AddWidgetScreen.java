package top.hmjmfabc.projector.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import top.hmjmfabc.projector.client.SelectionState;
import top.hmjmfabc.projector.common.Plane;
import top.hmjmfabc.projector.common.widget.Widget;
import top.hmjmfabc.projector.common.widget.Widgets;
import top.hmjmfabc.projector.network.Payloads;

/**
 * 「新增控件」子菜单。
 *
 * <p>对应设计稿：新增文本 / 新增图片 / 新增视频 / 新增特殊控件
 * （特殊控件包含时钟、天气、百分比进度）。</p>
 */
public class AddWidgetScreen extends ProjectorScreen {

    private final Screen parent;
    private final Plane plane;
    private boolean specialExpanded;

    public AddWidgetScreen(Screen parent, Plane plane) {
        super(Component.literal("\u65b0\u589e\u63a7\u4ef6"));
        this.parent = parent;
        this.plane = plane;
    }

    private int panelX, panelY, panelW, panelH;

    @Override
    protected void init() {
        int w = 200;
        panelW = w;
        // 特殊控件展开后多了一行（排行榜 / 计时器），面板高度跟着加
        panelH = specialExpanded ? 220 : 168;
        panelX = (this.width - w) / 2;
        panelY = Math.max(4, (this.height - panelH) / 2);
        int x = panelX;
        int y = panelY + 22;

        button("\u65b0\u589e\u6587\u672c", x + 12, y, w - 24, 20, b -> add(Widget.KIND_TEXT));
        y += 24;

        boolean incomplete = plane.isIncomplete();
        var mediaBtn = button("\u65b0\u589e\u56fe\u7247", x + 12, y, w - 24, 20, b -> add(Widget.KIND_IMAGE));
        mediaBtn.active = !incomplete;
        y += 24;
        var videoBtn = button("\u65b0\u589e\u89c6\u9891", x + 12, y, w - 24, 20, b -> add(Widget.KIND_VIDEO));
        videoBtn.active = !incomplete;
        y += 24;

        if (specialExpanded) {
            int third = (w - 48) / 3;
            button("\u65f6\u949f", x + 24, y, third, 20, b -> add(Widget.KIND_CLOCK)).active = !incomplete;
            button("\u5929\u6c14", x + 28 + third, y, third, 20, b -> add(Widget.KIND_WEATHER)).active = !incomplete;
            button("\u767e\u5206\u6bd4", x + 32 + third * 2, y, third, 20, b -> add(Widget.KIND_PROGRESS)).active = !incomplete;
            y += 24;
            // 【③b / ⑧】新增两个特殊控件
            button("\u6392\u884c\u699c", x + 24, y, third, 20, b -> add(Widget.KIND_LEADERBOARD)).active = !incomplete;
            button("\u8ba1\u65f6\u5668", x + 28 + third, y, third, 20, b -> add(Widget.KIND_TIMER)).active = !incomplete;
            y += 24;
            // 【⑪】棋类游戏（六种棋合并成一个入口）
            button("\u68cb\u7c7b\u6e38\u620f", x + 24, y, third, 20, b -> add(Widget.KIND_CHESS)).active = !incomplete;
            y += 24;
        } else {
            button("\u65b0\u589e\u7279\u6b8a\u63a7\u4ef6 \u25b8", x + 12, y, w - 24, 20, b -> {
                specialExpanded = true;
                rebuildWidgets();
            });
            y += 24;
        }

        y += 6;
        if (incomplete) {
            // 占位，提示文字在 render 里画
            y += 0;
        }
        button("\u8fd4\u56de", x + 12, panelY + panelH - 26, w - 24, 20,
                b -> Minecraft.getInstance().setScreen(parent));
    }

    private void add(int kind) {
        Widget w = Widgets.createDefault(kind);
        // 小平面（例如 1 格宽的柱子）上不要让新控件一出生就超出画布
        double maxW = Math.max(8, plane.width * 0.8);
        double maxH = Math.max(8, plane.height * 0.8);

        if (w instanceof top.hmjmfabc.projector.common.widget.TextWidget tw) {
            // 文字控件必须按真实字体度量定尺寸，而且**不能**像图片那样等比缩小方框：
            // 方框缩小了、里面的字号没变，文字就会溢出框外。
            // 正确做法是方框贴合文字；若仍超出画布，就按比例缩小字号再重算。
            var font = top.hmjmfabc.projector.client.font.FontManager.get(tw.fontId);
            if (font != null) {
                for (int attempt = 0; attempt < 12; attempt++) {
                    var m = top.hmjmfabc.projector.common.text.TextLayout.measure(tw.text,
                            top.hmjmfabc.projector.client.font.FontManager.metrics(font),
                            tw.fontSize, tw.wrapWidth, tw.lineSpacing);
                    double bw = Math.max(1, m.width() + 2);
                    double bh = Math.max(1, m.height() + 2);
                    double k = Math.min(1.0, Math.min(maxW / bw, maxH / bh));
                    if (k > 0.999 || tw.fontSize <= 4.0) {
                        tw.setSize(bw, bh);
                        break;
                    }
                    // 字号线性缩放近似成立（字形度量随字号线性变化），迭代几次即可收敛
                    tw.fontSize = Math.max(4.0, tw.fontSize * k);
                }
                tw.manualSize = false;
            }
        } else {
            if (w.w > maxW) {
                double k = maxW / w.w;
                w.w *= k;
                w.h *= k;
            }
            if (w.h > maxH) {
                double k = maxH / w.h;
                w.w *= k;
                w.h *= k;
            }
        }
        // 放在画布中央偏左上，避免多个控件完全重叠
        w.x = Math.max(0, plane.width / 2.0 - w.w / 2 + (plane.widgets.size() % 5) * 4);
        w.y = Math.max(0, plane.height / 2.0 - w.h / 2 + (plane.widgets.size() % 5) * 4);
        w.zOff = Math.min(20, plane.widgets.size());

        CompoundTag t = new CompoundTag();
        t.put("widget", w.save());
        PlaneDialogScreen.sendFor(plane, "addWidget", t);

        // 图片/视频先选文件，选中后再建立控件
        if (kind == Widget.KIND_IMAGE || kind == Widget.KIND_VIDEO) {
            Minecraft.getInstance().setScreen(new MediaPickerScreen(parent, plane, kind == Widget.KIND_VIDEO, w));
        } else {
            Minecraft.getInstance().setScreen(new WidgetEditorScreen(plane, w, parent));
        }
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        panel(gfx, panelX, panelY, panelW, panelH);
        super.render(gfx, mouseX, mouseY, partialTick);
        centeredLabel(gfx, "\u65b0\u589e\u63a7\u4ef6", panelX + panelW / 2, panelY + 8, TEXT_ACCENT);
        if (plane.isIncomplete()) {
            centeredLabel(gfx, "\u4e0d\u5b8c\u6574\u5e73\u9762\u4ec5\u652f\u6301\u7eaf\u6587\u672c",
                    panelX + panelW / 2, panelY + panelH - 44, 0xFFFFAA55);
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}

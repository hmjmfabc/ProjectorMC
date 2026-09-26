package top.hmjmfabc.projector.common.widget;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 控件工厂 / 序列化调度。
 *
 * <p>存储格式使用 NBT（与 Minecraft 存档一致），每个控件保存
 * {@code kind} 字段作为类型标签，便于后续版本扩展。</p>
 */
public final class Widgets {

    private Widgets() {
    }

    public static CompoundTag save(Widget w) {
        return w.save();
    }

    @Nullable
    public static Widget load(CompoundTag t) {
        int kind = t.getInt("kind");
        Widget w = Widget.create(kind);
        if (w == null) {
            return null;
        }
        w.loadCommon(t);
        w.loadExtra(t);
        if (w.id == null) w.id = UUID.randomUUID();
        return w;
    }

    /** 由类型 ID 创建默认参数的控件（用于「新增控件」菜单）。 */
    public static Widget createDefault(int kind) {
        Widget w = Widget.create(kind);
        if (w == null) w = new TextWidget();
        switch (w.kind()) {
            case Widget.KIND_TEXT -> {
                TextWidget tw = (TextWidget) w;
                tw.text = "Hello, Projector!";
                tw.fontSize = 16;
                // 默认框必须按内容估算，不能写死 64x16：
                // 那句示例文字在 16 号字下宽约 190 画布单位，
                // 固定 64 会让文字远远溢出控件方框（表现为「字不在框里」）。
                // 这里只用粗略字宽（真实度量由客户端在创建/编辑时精确重算）。
                double approxCharW = tw.fontSize * 0.62;
                w.w = Math.max(8, Math.round(tw.text.length() * approxCharW) + 2);
                w.h = Math.max(8, Math.round(tw.fontSize * 1.2) + 2);
            }
            case Widget.KIND_IMAGE, Widget.KIND_VIDEO -> {
                w.w = 64;
                w.h = 64;
            }
            case Widget.KIND_CLOCK -> {
                w.w = 48;
                w.h = 16;
            }
            case Widget.KIND_WEATHER -> {
                w.w = 56;
                w.h = 16;
            }
            case Widget.KIND_PROGRESS -> {
                w.w = 64;
                w.h = 40;
            }
            case Widget.KIND_TIMER -> {
                // 「时:分:秒」比「xx%」宽得多
                w.w = 80;
                w.h = 24;
                // 新建即开始计时（用户要求：不需要先按一次「继续」）
                TimerWidget tw = (TimerWidget) w;
                tw.running = true;
                tw.startGameTime = 0;   // 由服务端在 addWidget 时补上当前游戏刻
            }
            case Widget.KIND_LEADERBOARD -> {
                w.w = 80;
                w.h = 72;
            }
            case Widget.KIND_CHESS -> {
                // 【为什么要按棋盘格数反推尺寸】以前固定 96x96 单位（= 6x6 格方块），
                // 放到 15x15 的五子棋上每格只有 0.4 格方块，格线细到亚像素，
                // 看上去就是一团糊掉的乱线（用户反馈「完全无法正常显示 / 边框很炸裂」）。
                // 现在保证每格至少 CELL_UNITS 画布单位。
                ChessWidget cw = (ChessWidget) w;
                cw.game.reset();
                int cols = Math.max(1, cw.game.width());
                int rows = Math.max(1, cw.game.height());
                w.w = cols * ChessWidget.CELL_UNITS;
                w.h = rows * ChessWidget.CELL_UNITS;
            }
            default -> {
                w.w = 32;
                w.h = 32;
            }
        }
        return w;
    }
}

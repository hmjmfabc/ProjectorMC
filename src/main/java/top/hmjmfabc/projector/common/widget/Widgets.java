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

    /** 新建网页控件的默认地址。 */
    public static final String DEFAULT_WEB_URL = "https://cn.bing.com/";

    /** 平时新建文本控件的默认文字。 */
    public static final String DEFAULT_TEXT = "Hello, Projector!";

    /** 几个固定的「生日」彩蛋：月-日 → 默认文字。 */
    private static final int[][] BIRTHDAYS = {
            {5, 17},   // Minecraft
            {8, 31},   // 初音未来
            {9, 21},   // 作者
            {9, 19},   // 本模组
    };

    private static final String[] BIRTHDAY_TEXTS = {
            "Happy birthday,Minecraft!",
            "Happy birthday,Hatsune Miku!",
            "Happy birthday,Hmjmfabc!",
            "Happy birthday,Projector!",
    };

    /**
     * 新建文本控件的默认文字 —— 遇到「生日」就换成祝福语（彩蛋）。
     *
     * <p>日期（已核对，用户给的日期无误）：</p>
     * <ul>
     *   <li><b>5 月 17 日</b>：Minecraft 的生日 —— 2009 年 5 月 17 日发布第一个公开版本
     *       （Classic 的首次公开发布就是这个日子）；</li>
     *   <li><b>8 月 31 日</b>：初音未来的生日（2007 年 8 月 31 日发售）；</li>
     *   <li><b>9 月 21 日</b>：作者 Hmjmfabc 的生日（用户给定）；</li>
     *   <li><b>9 月 19 日</b>：本模组投影仪的生日（用户给定）。</li>
     * </ul>
     *
     * <p>用的是**玩家本机的日期**（{@code LocalDate.now()}，系统时区）——
     * 彩蛋本来就该按玩家自己的日历走，不必与服务端统一。</p>
     *
     * <p>纯函数（可注入日期），所以 {@code tmp/v38} 能在不改系统时间的前提下
     * 把 365 天全跑一遍 —— 日期彩蛋最容易犯的错就是「那天永远不触发」。</p>
     */
    public static String defaultText() {
        return defaultTextFor(java.time.LocalDate.now());
    }

    /** 上面那个的纯函数版本（给定日期）。 */
    public static String defaultTextFor(java.time.LocalDate date) {
        if (date != null) {
            for (int i = 0; i < BIRTHDAYS.length; i++) {
                if (date.getMonthValue() == BIRTHDAYS[i][0] && date.getDayOfMonth() == BIRTHDAYS[i][1]) {
                    return BIRTHDAY_TEXTS[i];
                }
            }
        }
        return DEFAULT_TEXT;
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
                tw.text = defaultText();
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
            case Widget.KIND_MUSIC -> {
                // 「语音条」的长宽比：一行播放键 + 波形 + 时间，太扁了不好点、太高了占地方
                w.w = 112;
                w.h = 20;
            }
            case Widget.KIND_WEB -> {
                // 网页按 16:9 起手（和「新建在线视频」同一个默认比例），并给个能看的地址框
                WebWidget web = (WebWidget) w;
                web.w = 96;
                web.h = 54;
                // 【27.2.3-hotfix-142】默认地址：默认搜索引擎（以前是空 = 白页，
                // 玩家建完控件还得自己想个网址，不知道填什么就卡住了）。
                // 只认 http/https（WebWidget.acceptable），这个地址是合法且免登录的。
                web.url = DEFAULT_WEB_URL;
            }
            default -> {
                w.w = 32;
                w.h = 32;
            }
        }
        return w;
    }
}

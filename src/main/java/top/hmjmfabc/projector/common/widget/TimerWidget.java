package top.hmjmfabc.projector.common.widget;

import net.minecraft.nbt.CompoundTag;

/**
 * 计时器控件（用户 ⑧）：正计时 / 倒计时两种类型，简约 / 花哨两种样式。
 *
 * <h2>计时基准</h2>
 * <p>时长以<b>现实秒</b>填写（0 ~ 2147483647），但计时锚点用<b>服务端游戏刻</b>：
 * {@code 1 秒 = 20 刻}。这样同一存档里所有客户端看到的读数完全一致，
 * 服务端也才能在倒计时归零的那一刻执行绑定指令。单人存档按 Esc 暂停时
 * 游戏刻不走，计时也会跟着暂停——与百分比控件的既有行为一致。</p>
 *
 * <h2>花哨样式（用户原文）</h2>
 * <ul>
 *   <li><b>正计时</b>：每分钟变一次色，序列「红、橙、黄、绿、青、蓝、紫」；</li>
 *   <li><b>倒计时</b>：按剩余比例变色
 *     <ul>
 *       <li>60%~100% 绿色</li>
 *       <li>40%~60% 蓝色</li>
 *       <li>30%~40% 黄色</li>
 *       <li>20%~30% 橙色</li>
 *       <li>&lt;20% 红色，且每 5 秒闪烁一次</li>
 *       <li>剩余 ≤10 秒：炫彩渐变色 + 疯狂闪烁，直到停止</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <h2>显示格式</h2>
 * <p>恒为「时:分:秒」，小时不封顶：{@code 360602} 秒显示为 {@code 100:10:02}。</p>
 */
public class TimerWidget extends Widget {

    /** 类型：正计时。 */
    public static final int TYPE_UP = 0;
    /** 类型：倒计时。 */
    public static final int TYPE_DOWN = 1;
    public static final String[] TYPE_NAMES = {"\u6b63\u8ba1\u65f6", "\u5012\u8ba1\u65f6"};

    /** 样式：简约。 */
    public static final int STYLE_SIMPLE = 0;
    /** 样式：花哨。 */
    public static final int STYLE_FANCY = 1;
    public static final String[] STYLE_NAMES = {"\u7b80\u7ea6", "\u82b1\u54e8"};

    /** 花哨正计时的循环色序：红 橙 黄 绿 青 蓝 紫。 */
    public static final int[] FANCY_CYCLE = {
            0xFFFF3B30, 0xFFFF9500, 0xFFFFD60A, 0xFF34C759,
            0xFF00C7BE, 0xFF0A84FF, 0xFFAF52DE};
    /** 花哨倒计时的分档色。 */
    public static final int CD_GREEN = 0xFF34C759;
    public static final int CD_BLUE = 0xFF0A84FF;
    public static final int CD_YELLOW = 0xFFFFD60A;
    public static final int CD_ORANGE = 0xFFFF9500;
    public static final int CD_RED = 0xFFFF3B30;

    /** 倒计时最后多少秒进入「炫彩渐变 + 疯狂闪烁」。 */
    public static final double MADNESS_SECONDS = 10.0;

    public int type = TYPE_DOWN;
    public int style = STYLE_SIMPLE;
    public String fontId = Fonts.CAVIAR_DREAMS;
    public double fontSize = 16;
    /** 颜色（ARGB）。简约样式使用；花哨样式的颜色由 {@link #fancyColor} 给出。 */
    public int color = 0xFFFFFFFF;

    /** 倒计时时长（现实秒，0 ~ 2147483647）。 */
    public double durationSeconds = 60;
    /**
     * 正在计时。
     *
     * <p>新建的计时器**默认就是运行的**：用户反馈「一开始要按一次继续才开始」，
     * 那是以前默认 {@code running=false} 造成的。锚点由服务端在 addWidget 时
     * 打上当前游戏刻（{@code startGameTime <= 0} 会被补）。</p>
     */
    public boolean running = true;
    /** 计时锚点（游戏刻）。 */
    public long startGameTime;
    /** 已累积的游戏刻（跨暂停累计）。 */
    public long accumulatedTicks;

    /**
     * 管理员绑定的指令（不含前导 {@code /}）。倒计时归零时由服务端执行一次。
     * 空字符串 = 不绑定。
     */
    public String command = "";
    /** 已经触发过指令，避免重复执行（服务端写回后持久化）。 */
    public boolean commandFired;

    @Override
    public int kind() {
        return KIND_TIMER;
    }

    @Override
    public String label() {
        return (type == TYPE_DOWN ? "\u5012\u8ba1\u65f6 " : "\u6b63\u8ba1\u65f6 ")
                + formatSeconds(type == TYPE_DOWN ? durationSeconds : 0);
    }

    // ------------------------------------------------------------------
    // 计时核心
    // ------------------------------------------------------------------

    public long elapsedTicks(long gameTime) {
        long e = accumulatedTicks;
        // 锚点还没被服务端打上（startGameTime <= 0）时先算 0：
        // 否则会把「这个世界已经过去的所有刻」都算进去，一放上去读数就是天文数字。
        if (running && startGameTime > 0) {
            e += Math.max(0L, gameTime - startGameTime);
        }
        return Math.max(0L, e);
    }

    /** 已计时的现实秒数。 */
    public double elapsedSeconds(long gameTime) {
        return elapsedTicks(gameTime) / 20.0;
    }

    /** 倒计时剩余秒数（正计时恒为 0）。 */
    public double remainingSeconds(long gameTime) {
        if (type != TYPE_DOWN) return 0;
        return Math.max(0.0, durationSeconds - elapsedSeconds(gameTime));
    }

    /** 当前应显示的数字（正计时=已过秒数；倒计时=剩余秒数）。 */
    public double displaySeconds(long gameTime) {
        return type == TYPE_DOWN ? remainingSeconds(gameTime) : elapsedSeconds(gameTime);
    }

    /** 倒计时是否已经归零。 */
    public boolean finished(long gameTime) {
        return type == TYPE_DOWN && durationSeconds > 0 && remainingSeconds(gameTime) <= 0.0;
    }

    /** 倒计时剩余比例（1 → 0）；正计时或零时长返回 0。 */
    public double fraction(long gameTime) {
        if (durationSeconds <= 0.01) return 0.0;
        double f = remainingSeconds(gameTime) / durationSeconds;
        return f < 0 ? 0 : (f > 1 ? 1 : f);
    }

    /** 显示文本，形如 {@code 100:10:02}。 */
    public String text(long gameTime) {
        return formatSeconds(displaySeconds(gameTime));
    }

    /** 把秒数写成「时:分:秒」（小时不封顶）。360602 → {@code 100:10:02}。 */
    public static String formatSeconds(double secs) {
        long total = (long) Math.floor(Math.max(0.0, secs));
        long h = total / 3600L;
        long m = (total % 3600L) / 60L;
        long s = total % 60L;
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", h, m, s);
    }

    // ------------------------------------------------------------------
    // 花哨样式
    // ------------------------------------------------------------------

    /** 花哨样式下当前的颜色；简约样式返回 {@link #color}。 */
    public int fancyColor(long gameTime) {
        if (style != STYLE_FANCY) return color;
        if (type == TYPE_UP) {
            long mins = (long) Math.floor(elapsedSeconds(gameTime) / 60.0);
            return FANCY_CYCLE[(int) Math.floorMod(mins, FANCY_CYCLE.length)];
        }
        double f = fraction(gameTime);
        // 区间取「闭下界」：用户原文写的是 60%~100% 绿、40%~60% 蓝、30%~40% 黄、
        // 20%~30% 橙、<20% 红，也就是「刚好剩 60%」应当算绿色那一档。
        if (f >= 0.60) return CD_GREEN;
        if (f >= 0.40) return CD_BLUE;
        if (f >= 0.30) return CD_YELLOW;
        if (f >= 0.20) return CD_ORANGE;
        return CD_RED;
    }

    /**
     * 花哨样式下这一帧的不透明度倍率（1 = 不闪）。
     *
     * <p>注意闪烁用<b>本地真实时间</b>驱动：它是纯视觉节奏，
     * 不需要（也不应该）占用服务端刻来同步。</p>
     */
    public float fancyAlphaFactor(long gameTime) {
        if (style != STYLE_FANCY || type != TYPE_DOWN) return 1.0f;
        double remain = remainingSeconds(gameTime);
        if (remain <= 0 || durationSeconds <= 0.01) return 1.0f;
        long ms = System.currentTimeMillis();
        if (remain <= MADNESS_SECONDS) {
            // 最后 10 秒：疯狂闪烁（约 6 Hz）
            return (ms / 80L) % 2L == 0L ? 1.0f : 0.12f;
        }
        if (fraction(gameTime) < 0.20) {
            // 20% 以下：每 5 秒闪一次，每次亮 0.5 秒
            double phase = elapsedSeconds(gameTime) % 5.0;
            return phase < 0.5 ? 1.0f : 0.22f;
        }
        return 1.0f;
    }

    /** 是否应该用「炫彩渐变」渲染（花哨倒计时最后 10 秒）。 */
    public boolean madnessRainbow(long gameTime) {
        return style == STYLE_FANCY && type == TYPE_DOWN && durationSeconds > 0.01
                && remainingSeconds(gameTime) > 0 && remainingSeconds(gameTime) <= MADNESS_SECONDS;
    }

    // ------------------------------------------------------------------
    // 服务端 / 对话框操作
    // ------------------------------------------------------------------

    /** 重新开始计时（正计时从 0 起，倒计时从满时长起）。 */
    public void restart(long gameTime) {
        this.accumulatedTicks = 0;
        this.startGameTime = gameTime;
        this.running = true;
        this.commandFired = false;
    }

    /**
     * 【⑧】直接设置「剩余时间」（秒）。
     *
     * <p>用户要的是「最大时间在对话框里输入、剩余时间在 0 ~ 最大秒数之间调整」。
     * 这里把它换算成「已计时的游戏刻」并重锚，因此无论当前是运行中还是暂停，
     * 设完之后读数都会等于目标值，且随后按正常速度继续走。</p>
     */
    public void setRemainingSeconds(double target, long gameTime) {
        double max = Math.max(0.0, durationSeconds);
        double rem = target < 0 ? 0 : (target > max ? max : target);
        long elapsed = Math.round((max - rem) * 20.0);
        this.accumulatedTicks = Math.max(0L, elapsed);
        if (running) {
            this.startGameTime = gameTime;
        }
        // 剩余时间被手动改过，之前那次的「已触发指令」标记要清掉，
        // 否则把倒计时往回拨之后就再也不会触发绑定的指令了。
        this.commandFired = false;
    }

    public void pause(long gameTime) {
        if (running) {
            accumulatedTicks = elapsedTicks(gameTime);
            running = false;
        }
    }

    public void resume(long gameTime) {
        if (!running) {
            startGameTime = gameTime;
            running = true;
        }
    }

    /** 【①】本控件引用的字体（供联机字体核验使用）。 */
    @Override
    public java.util.List<String> fontIds() {
        return java.util.List.of(fontId == null ? "" : fontId);
    }

    @Override
    protected void saveExtra(CompoundTag t) {
        t.putInt("type", type);
        t.putInt("style", style);
        t.putString("font", fontId);
        t.putDouble("fs", fontSize);
        t.putInt("color", color);
        t.putDouble("duration", durationSeconds);
        t.putBoolean("running", running);
        t.putLong("start", startGameTime);
        t.putLong("acc", accumulatedTicks);
        t.putString("cmd", command == null ? "" : command);
        t.putBoolean("cmdFired", commandFired);
    }

    @Override
    public void loadExtra(CompoundTag t) {
        type = t.contains("type") ? t.getInt("type") : TYPE_DOWN;
        if (type != TYPE_UP && type != TYPE_DOWN) type = TYPE_DOWN;
        style = t.contains("style") ? t.getInt("style") : STYLE_SIMPLE;
        if (style != STYLE_SIMPLE && style != STYLE_FANCY) style = STYLE_SIMPLE;
        fontId = t.contains("font") ? t.getString("font") : Fonts.CAVIAR_DREAMS;
        fontSize = t.contains("fs") ? t.getDouble("fs") : 16;
        color = t.contains("color") ? t.getInt("color") : 0xFFFFFFFF;
        durationSeconds = t.contains("duration") ? t.getDouble("duration") : 60;
        running = t.getBoolean("running");
        startGameTime = t.getLong("start");
        accumulatedTicks = t.getLong("acc");
        command = t.contains("cmd") ? t.getString("cmd") : "";
        commandFired = t.getBoolean("cmdFired");
    }
}

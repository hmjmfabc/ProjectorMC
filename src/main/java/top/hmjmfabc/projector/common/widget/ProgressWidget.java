package top.hmjmfabc.projector.common.widget;

import net.minecraft.nbt.CompoundTag;

/**
 * 百分比进度控件：上方小字标题（默认「Completed」），下方大字「xx%」。
 *
 * <p>字体固定为 {@link Fonts#CAVIAR_DREAMS}（caviar_dreams.ttf），符合设计要求。
 * 进度可以在界面上手动设置，也可以设定「持续时间（秒）」后自动从 0% 走到 100%。</p>
 *
 * <p>自动进度的基准时间保存在控件里（游戏刻），由服务端在「开始/重置」时写入，
 * 因此同一存档的所有客户端看到的是同一个进度；游戏暂停（单人 Esc）时
 * 游戏刻不走，进度也会自然暂停。</p>
 */
public class ProgressWidget extends Widget {

    /** 大字百分比颜色（ARGB）。 */
    public int valueColor = 0xFFFFFFFF;
    /** 小字标题颜色（ARGB）。 */
    public int titleColor = 0xFFBFBFBF;
    /** 小字标题字号（画布单位）。 */
    public double titleSize = 8;
    /** 大字百分比字号（画布单位）。 */
    public double valueSize = 18;
    /** 标题文本（支持 & 格式化代码）。【③】用户要求首行文字为「Completed」。 */
    public String title = "&7Completed";

    /** 自动进度总时长（秒，&lt;=0 表示不自动）。 */
    public double durationSeconds = 60;
    /** 自动推进中。 */
    public boolean running;
    /** 自动推进起点（游戏刻）。 */
    public long startGameTime;
    /** 已累积的游戏刻（跨暂停累计）。 */
    public long accumulatedTicks;
    /** 手动进度 0~1（自动模式被忽略）。 */
    public double manualProgress;
    /** 是否显示百分号。 */
    public boolean showPercentSign = true;

    @Override
    public int kind() {
        return KIND_PROGRESS;
    }

    @Override
    public String label() {
        return "\u8fdb\u5ea6 " + Math.round(currentProgress(0) * 100) + "%";
    }

    /**
     * 计算当前进度（0~1）。
     *
     * @param gameTime 当前游戏刻
     */
    public double currentProgress(long gameTime) {
        if (!running || durationSeconds <= 0.01) {
            return clamp01(manualProgress);
        }
        long elapsed = accumulatedTicks + Math.max(0, gameTime - startGameTime);
        double frac = elapsed / (durationSeconds * 20.0);
        return clamp01(frac);
    }

    public boolean isFinished(long gameTime) {
        return running && currentProgress(gameTime) >= 1.0;
    }

    /** 显示用整数百分比（四舍五入）。 */
    public int percent(long gameTime) {
        return (int) Math.round(currentProgress(gameTime) * 100.0);
    }

    /** 服务端/对话框操作：立即重置到 0% 并重新开始计时。 */
    public void reset(long gameTime) {
        this.accumulatedTicks = 0;
        this.startGameTime = gameTime;
        this.running = this.durationSeconds > 0.01;
        if (!this.running) {
            this.manualProgress = 0;
        }
    }

    /** 服务端/对话框操作：暂停自动推进，并保留当前百分比。 */
    public void pause(long gameTime) {
        if (running) {
            accumulatedTicks += Math.max(0, gameTime - startGameTime);
            running = false;
        }
    }

    /** 服务端/对话框操作：继续（或开始）自动推进。 */
    public void resume(long gameTime) {
        if (!running && durationSeconds > 0.01) {
            startGameTime = gameTime;
            running = true;
            if (accumulatedTicks >= (long) (durationSeconds * 20.0)) {
                accumulatedTicks = 0;
            }
        }
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    @Override
    protected void saveExtra(CompoundTag t) {
        t.putInt("valueColor", valueColor);
        t.putInt("titleColor", titleColor);
        t.putDouble("titleSize", titleSize);
        t.putDouble("valueSize", valueSize);
        t.putString("title", title);
        t.putDouble("duration", durationSeconds);
        t.putBoolean("running", running);
        t.putLong("start", startGameTime);
        t.putLong("acc", accumulatedTicks);
        t.putDouble("manual", manualProgress);
        t.putBoolean("percentSign", showPercentSign);
    }

    @Override
    public void loadExtra(CompoundTag t) {
        valueColor = t.contains("valueColor") ? t.getInt("valueColor") : 0xFFFFFFFF;
        titleColor = t.contains("titleColor") ? t.getInt("titleColor") : 0xFFBFBFBF;
        titleSize = t.contains("titleSize") ? t.getDouble("titleSize") : 8;
        valueSize = t.contains("valueSize") ? t.getDouble("valueSize") : 18;
        title = t.contains("title") ? t.getString("title") : "&7Completed";
        durationSeconds = t.contains("duration") ? t.getDouble("duration") : 60;
        running = t.getBoolean("running");
        startGameTime = t.getLong("start");
        accumulatedTicks = t.getLong("acc");
        manualProgress = t.getDouble("manual");
        showPercentSign = !t.contains("percentSign") || t.getBoolean("percentSign");
    }
}

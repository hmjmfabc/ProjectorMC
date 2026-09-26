package top.hmjmfabc.projector.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.Scoreboard;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 排行榜的数据源：从客户端计分板读取任意 objective 的分数并排序。
 *
 * <p>原版本来就会把计分板同步给所有客户端，所以这里<b>不需要任何新增网络包</b>，
 * 服务端也不需要参与——排行榜天然是「所有人看到同一份数据」，
 * 而且单人存档与多人服务器走的是同一条路径。</p>
 */
public final class LeaderboardSource {

    /** 一行：玩家名 + 分数。 */
    public record Row(String name, int score) {
    }

    private LeaderboardSource() {
    }

    @Nullable
    private static Scoreboard scoreboard() {
        var level = Minecraft.getInstance().level;
        return level == null ? null : level.getScoreboard();
    }

    /**
     * 解析 objective：先按名称精确匹配，再退回按「显示名」匹配。
     *
     * <p>退回这一步是必要的：玩家在游戏里看到的通常是显示名（可以用 {@code /scoreboard
     * objectives setdisplay} 改成任意文本），而内部名不一定是同一个字符串。</p>
     */
    @Nullable
    public static Objective resolve(String objectiveName) {
        if (objectiveName == null || objectiveName.isEmpty()) return null;
        Scoreboard sb = scoreboard();
        if (sb == null) return null;
        Objective obj = sb.getObjective(objectiveName);
        if (obj != null) return obj;
        String lower = objectiveName.toLowerCase(Locale.ROOT);
        for (Objective o : sb.getObjectives()) {
            if (o.getName().toLowerCase(Locale.ROOT).equals(lower)) return o;
            try {
                if (o.getDisplayName().getString().toLowerCase(Locale.ROOT).equals(lower)) return o;
            } catch (Throwable ignored) {
                // 显示名解析失败不影响按内部名匹配
            }
        }
        return null;
    }

    /** 当前世界所有 objective 的名称（供编辑界面下拉/循环选择）。 */
    public static List<String> objectiveNames() {
        List<String> out = new ArrayList<>();
        Scoreboard sb = scoreboard();
        if (sb == null) return out;
        for (Objective o : sb.getObjectives()) {
            out.add(o.getName());
        }
        out.sort(String::compareTo);
        return out;
    }

    /**
     * 取某个 objective 的排行榜行。
     *
     * @param descending true = 由高到低
     * @param maxRows    最多取多少行（内部还会限一次上限，防止有人写个 100 万行）
     */
    public static List<Row> rows(String objectiveName, boolean descending, int maxRows) {
        List<Row> out = new ArrayList<>();
        Objective obj = resolve(objectiveName);
        if (obj == null) return out;
        int limit = Math.max(1, Math.min(maxRows, 64));
        try {
            collect(obj, out);
        } catch (Throwable t) {
            // 计分板读取失败绝不能让渲染崩掉（渲染线程上抛异常 = 整帧没了）
            return out;
        }
        out.sort(descending
                ? Comparator.comparingInt(Row::score).reversed().thenComparing(Row::name)
                : Comparator.comparingInt(Row::score).thenComparing(Row::name));
        if (out.size() > limit) {
            return new ArrayList<>(out.subList(0, limit));
        }
        return out;
    }

    /**
     * 把 objective 里的条目读进 {@code out}。
     *
     * <p>用 {@code Scoreboard.listPlayerScores(Objective)} → {@code PlayerScoreEntry}
     * 这条路（已用 javap 核对过 1.21.1 的真实签名）：</p>
     * <pre>
     *   public Collection&lt;PlayerScoreEntry&gt; listPlayerScores(Objective)
     *   PlayerScoreEntry.owner() : String
     *   PlayerScoreEntry.value() : int
     * </pre>
     * <p>{@code isHidden()} 为真的条目（内部用 {@code #} 前缀的隐藏行）不显示——
     * 原版侧边栏也是这么处理的。</p>
     */
    private static void collect(Objective obj, List<Row> out) {
        Scoreboard sb = scoreboard();
        if (sb == null) return;
        for (PlayerScoreEntry e : sb.listPlayerScores(obj)) {
            if (e == null || e.isHidden()) continue;
            out.add(new Row(e.owner(), e.value()));
        }
    }
}

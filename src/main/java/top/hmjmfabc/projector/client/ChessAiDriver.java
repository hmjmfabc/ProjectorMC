package top.hmjmfabc.projector.client;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import top.hmjmfabc.projector.Projector;
import top.hmjmfabc.projector.client.media.MediaCache;
import top.hmjmfabc.projector.common.Plane;
import top.hmjmfabc.projector.common.game.GameAi;
import top.hmjmfabc.projector.common.game.GameKind;
import top.hmjmfabc.projector.common.game.GameMode;
import top.hmjmfabc.projector.common.game.GameSession;
import top.hmjmfabc.projector.common.widget.ChessWidget;
import top.hmjmfabc.projector.network.Payloads;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * 【⑪】驱动棋类 AI 走一步（客户端侧）。
 *
 * <h2>为什么必须单独抽出来</h2>
 * <p>以前只有「对局界面」里才会跑 AI。于是玩家在<b>世界里直接右键下棋</b>时，
 * 人机模式下走完先手之后局面就<b>永久卡在后手</b>——之后再怎么右键，
 * {@code isAiTurn()} 都是 true，只能不停弹出对局界面。
 * 这就是用户报的「后手下子会跳转到编辑界面」。</p>
 *
 * <h2>线程与安全</h2>
 * <ul>
 *   <li>搜索是<b>阻塞</b>的（最多几百毫秒），必须放在 {@link MediaCache#worker()} 上，
 *       绝不能占用渲染线程；</li>
 *   <li>{@link #THINKING} 是每个控件一把闩锁：连点也不会堆出几十个 AI 任务；</li>
 *   <li>结果回来时用「<b>手上这一手的序号</b>」校验：期间棋盘被服务端广播改过
 *       （或这一局已经重开）就丢弃，绝不把旧局面写回去；</li>
 *   <li>AI 结果写回后照常走 {@code updateWidget} 提交，<b>服务端仍然是权威</b>。</li>
 * </ul>
 */
public final class ChessAiDriver {

    /** 正在思考中的控件 id。 */
    private static final Map<UUID, Integer> THINKING = new ConcurrentHashMap<>();

    /**
     * 连续「结果作废」的次数（按控件）。
     *
     * <p>局面在思考期间变过时我们重算一手（否则自走会断），但万一本地与服务端
     * 一直对不上，重算就会变成空转 —— 连丢太多次就刹车，并留一行日志。</p>
     */
    private static final Map<UUID, Integer> DISCARD_STREAK = new ConcurrentHashMap<>();
    /** 允许连续作废多少次（超过就停）。 */
    private static final int MAX_DISCARD_STREAK = 12;

    /**
     * 斗蛐蛐自走时，两手之间的最小间隔（毫秒）。
     *
     * <p><b>为什么需要它：</b>玩家实测日志里自走是 20~40ms 一手
     * （象棋 3 秒内从 32 子杀到 10 子），「太快了来不及看」。
     * 这里按 10 倍放慢到 ~3 手/秒。</p>
     *
     * <p>刻意<b>不做成配置项</b>：本模组的配置改默认值对已生成的 toml 无效
     * （AGENTS.md 第 21 条），玩家会以为「改了没生效」。节奏是产品手感，写死即可。</p>
     */
    private static final long SELF_PLAY_INTERVAL_MS = 300L;

    /** 一个「到点再走下一手」的自走请求。 */
    private record Pending(Plane plane, ChessWidget widget, BooleanSupplier permit, long dueAt) {
    }

    /**
     * 等间隔到点后要续手的自走请求（按控件 id）。
     *
     * <p>它取代了以前「算完立刻递归排下一手」的写法：那样节奏完全取决于 AI 思考速度，
     * 快起来就是 20ms 一手。现在统一由 {@link #pump()}（每个客户端 tick）到点拉起。</p>
     */
    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    private ChessAiDriver() {
    }

    /**
     * 每个客户端 tick 调一次：把到点的自走请求拉起来。
     *
     * <p>界面开着、在世界里看着都走这一条路。许可失效（界面关了 / 玩家走出渲染距离）
     * 就把请求丢掉并记一行日志——自走必须能刹住。</p>
     */
    public static void pump() {
        if (PENDING.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Pending> e : PENDING.entrySet()) {
            Pending p = e.getValue();
            if (now < p.dueAt()) continue;
            // 同一控件已经有任务在算：**留着这个请求**，下一 tick 再看。
            // （如果这里直接丢掉，自走链就断了 —— 正是「下几个子就停了」那种症状。）
            if (isThinking(p.widget())) continue;
            if (!PENDING.remove(e.getKey(), p)) continue;   // 别人已经取走
            if (!p.permit().getAsBoolean()) {
                Projector.LOGGER.info("[Projector] 斗蛐蛐自走暂停：续手许可已失效（界面关闭 / 玩家离开棋盘）");
                continue;
            }
            runOneMove(p.plane(), p.widget(), p.permit());
        }
    }

    /** 这个控件此刻是否正在算 AI（界面可以据此显示「AI 思考中…」）。 */
    public static boolean isThinking(ChessWidget widget) {
        return widget != null && THINKING.containsKey(widget.id);
    }

    /**
     * 如果轮到 AI，就在工作线程上算一手并提交（不自走，一步一个触发）。
     *
     * @return true 表示确实排了一个任务
     */
    public static boolean runOneMove(Plane plane, ChessWidget widget) {
        return runOneMove(plane, widget, null);
    }

    /**
     * 如果轮到 AI，就在工作线程上算一手并提交。
     *
     * @param autoContinue <b>非 null = 允许自走</b>（斗蛐蛐 AI vs AI 用）。
     *                     每次续手之前都会问一次这个条件，返回 false 就立刻停下——
     *                     界面关掉、玩家离开都必须能刹住（用户专门提醒过「小心内存泄露」）。
     *                     人机 / 双人模式传 null（AI 走完就该等玩家了）。
     * @return true 表示确实排了一个任务
     */
    public static boolean runOneMove(Plane plane, ChessWidget widget, @Nullable BooleanSupplier autoContinue) {
        if (plane == null || widget == null) return false;
        widget.ensureBoard();
        GameSession g = widget.game;
        if (g == null || g.result != 0 || !g.isAiTurn()) return false;
        final int baseMoves = g.moveCount;
        final int baseKind = g.kind;
        final GameSession target = g;
        // 闩锁：同一个控件同时只允许一个 AI 任务
        if (THINKING.putIfAbsent(widget.id, baseMoves) != null) return false;
        final UUID wid = widget.id;

        MediaCache.worker().execute(() -> {
            GameAi.Choice choice = null;
            try {
                // 【必须把劫争禁令交给着法生成】否则围棋会选中被 play() 拒绝的落点，
                // 局面永不推进 -> 见 GameSession.bannedDropIndex()。
                choice = new GameAi(target.rules(), target.difficulty)
                        .choose(target.board.clone(), target.turn, target.bannedDropIndex());
            } catch (Throwable t) {
                Projector.LOGGER.warn("[Projector] 棋类 AI 出错：{}", t.toString());
            }
            final GameAi.Choice result = choice;
            Minecraft.getInstance().execute(() -> {
                THINKING.remove(wid);
                // 结果过期（棋盘被改过 / 换了棋种 / 这一局重开了）-> 丢弃。
                // 【刻意不用对象身份判定】以前用的是 `widget.game != target`，
                // 而 ChessWidget.loadExtra 每次广播都会换一个新的 GameSession，
                // 于是 AI 刚提交完、广播一到就把自己的结果丢了 ——
                // 表现是「人类下完 AI 不动，必须再点一下棋盘」。
                // 现在 ChessWidget.loadExtra 已改成原地合并（GameSession.copyFrom），
                // 这里再改用「手数 + 棋种」判定，即使将来又有人换对象也不会复发。
                GameSession now = widget.game;
                if (now == null || now.kind != baseKind || now.moveCount != baseMoves) {
                    // 局面在我们思考期间被改过（服务端广播 / 别人走了一手 / 这一局重开了）。
                    // 这一手作废，但【绝不能就此不排下一手】—— 否则自走链断掉，
                    // 表现就是「AI 下几个子就停了」。只要还轮到 AI、许可还在，就重算一手。
                    // 连丢多次说明本地与服务端一直对不上，那就刹车并留日志（防空转）。
                    int streak = DISCARD_STREAK.merge(wid, 1, Integer::sum);
                    if (streak > MAX_DISCARD_STREAK) {
                        DISCARD_STREAK.remove(wid);
                        Projector.LOGGER.warn("[Projector] 棋局连续 {} 次与本地对不上（{}，本地 {} 手），"
                                + "停止自走", streak, GameKind.name(now == null ? baseKind : now.kind),
                                now == null ? -1 : now.moveCount);
                        return;
                    }
                    Projector.LOGGER.debug("[Projector] 棋局在思考期间变过（{} 手 -> {} 手），这一手作废后重算",
                            baseMoves, now == null ? -1 : now.moveCount);
                    if (now != null && now.result == 0 && autoContinue != null
                            && autoContinue.getAsBoolean()) {
                        continueLoop(plane, widget, autoContinue);
                    }
                    return;
                }
                if (result == null || result.move() == null) {
                    // AI 选不出着法：可能真的没棋可走了（围棋唯一的空点被劫争禁令挡着之类）。
                    // 让会话自己收尾，否则这一局会永久停在「谁都走不了」的状态上。
                    if (target.settleIfNoPlayableMove()) {
                        DISCARD_STREAK.remove(wid);
                        submit(plane, widget);
                        if (autoContinue != null && autoContinue.getAsBoolean()) {
                            continueLoop(plane, widget, autoContinue);
                        }
                    } else {
                        Projector.LOGGER.warn("[Projector] AI 选不出着法（{}，{} 手），自走停止",
                                GameKind.name(now.kind), now.moveCount);
                    }
                    return;
                }
                if (!target.play(result.move(), target.turn)) {
                    // 【绝不静默，也绝不续手】被拒绝说明「着法生成」与「会话规则」
                    // 又不一致了；此时局面没有推进，如果还自动续手就会变成
                    // 无限自转（一次 AI 搜索 + 一个服务端包，永远停不下来）。
                    Projector.LOGGER.warn("[Projector] AI 着法被拒绝（棋种 {}，手数 {}），已停止续手",
                            GameKind.name(now.kind), now.moveCount);
                    return;
                }
                DISCARD_STREAK.remove(wid);
                submit(plane, widget);
                if (autoContinue != null && autoContinue.getAsBoolean()) {
                    continueLoop(plane, widget, autoContinue);
                }
            });
        });
        return true;
    }

    /**
     * 【斗蛐蛐自走】一手走完之后接着排下一手；一局结束则按「循环」开关决定要不要开下一局。
     *
     * <p>刹车条件（缺一不可）：许可是 false、不再轮到 AI、这一局结束且没开循环。
     * 手数安全阀 {@code GameSession.MAX_MOVES} 由会话自己保证，这里只负责不再续手。</p>
     *
     * <p>每次「本该续手却停下」都留一行日志说明原因 —— 自走停住是最难查的一类问题
     * （用户报的「下几个子就停了」就是它），没有日志只能靠猜。</p>
     */
    private static void continueLoop(Plane plane, ChessWidget widget, BooleanSupplier autoContinue) {
        GameSession g = widget.game;
        if (g == null) return;
        if (g.result != 0) {
            // 斗蛐蛐：开着循环就自动开下一局，否则停在这一局的终局画面上
            if (g.mode != GameMode.AI_VS_AI || !g.loopEnabled) {
                Projector.LOGGER.info("[Projector] 斗蛐蛐自走结束：本局已分胜负（{}），循环未开",
                        g.resultText());
                return;
            }
            g.nextRoundIfLooping();
            submit(plane, widget);
        }
        if (!g.isAiTurn()) return;
        if (!autoContinue.getAsBoolean()) {
            Projector.LOGGER.info("[Projector] 斗蛐蛐自走暂停：续手许可已失效（界面关闭 / 玩家离开棋盘）");
            return;
        }
        // 【放慢节奏】不再「算完立刻排下一手」（那样是 20~40ms 一手，快到看不清），
        // 改成登记一个到点请求，由 pump() 在每个客户端 tick 里拉起下一手。
        // 同线程时 execute 是内联执行，但这里只是登记，不会递归堆栈、更不会空转烧 CPU。
        PENDING.put(widget.id, new Pending(plane, widget, autoContinue,
                System.currentTimeMillis() + SELF_PLAY_INTERVAL_MS));
    }

    /** 把棋局提交给服务端（走既有的 updateWidget 通道）。 */
    public static void submit(Plane plane, ChessWidget widget) {
        if (plane == null || widget == null) return;
        // 【必须报告「本地已落子、等回声」】否则服务端那份更早的回声会把本地棋局退回去，
        // 在途的 AI 结果随之被判过期 —— 斗蛐蛐自走就此停住（见 ChessWidget.noteLocalSubmit）。
        widget.noteLocalSubmit();
        CompoundTag t = new CompoundTag();
        t.putUUID("widget", widget.id);
        t.put("data", widget.save());
        PacketDistributor.sendToServer(new Payloads.PlaneEdit(plane.id, "updateWidget", t));
    }
}

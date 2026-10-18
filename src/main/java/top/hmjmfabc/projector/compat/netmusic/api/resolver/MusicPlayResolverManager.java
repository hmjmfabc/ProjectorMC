package top.hmjmfabc.projector.compat.netmusic.api.resolver;

import top.hmjmfabc.projector.compat.netmusic.api.ItemMusicCD;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 【27.2.2】网络音乐机 {@code api.resolver.MusicPlayResolverManager} 的**镜像实现**。
 *
 * <p>语义与 NetMusic 一致：附属把解析器 {@code registerResolver(...)} 进来，
 * 播放端按 {@link IAsyncSongUrlResolver#getPriority()} 从高到低问「谁能解析」。</p>
 *
 * <p>投影仪侧的接入点：{@code MusicManager} 在解析音频地址时先问这里
 * （见 {@code resolveViaAddons}），没人接手才走内置的网易云直链解析。
 * 这样「VIP 登录附属」这类东西才能真正影响播放（拿到高码率地址）。</p>
 */
public final class MusicPlayResolverManager {

    private MusicPlayResolverManager() {
    }

    private static final CopyOnWriteArrayList<IAsyncSongUrlResolver> RESOLVERS =
            new CopyOnWriteArrayList<>();

    /** 注册一个解析器（重复注册同一个实例会被忽略）。 */
    public static void registerResolver(IAsyncSongUrlResolver resolver) {
        if (resolver == null) return;
        RESOLVERS.addIfAbsent(resolver);
        top.hmjmfabc.projector.Projector.LOGGER.info(
                "[Projector][音乐] 已注册外部解析器：{}（优先级 {}），当前共 {} 个",
                resolver.getClass().getSimpleName(), resolver.getPriority(), RESOLVERS.size());
    }

    /** 按优先级排好序的解析器快照。 */
    public static List<IAsyncSongUrlResolver> resolvers() {
        List<IAsyncSongUrlResolver> out = new ArrayList<>(RESOLVERS);
        out.sort(Comparator.comparingInt(IAsyncSongUrlResolver::getPriority).reversed());
        return out;
    }

    /** 有没有解析器愿意接这首歌。 */
    public static boolean canResolve(ItemMusicCD.SongInfo songInfo) {
        for (IAsyncSongUrlResolver r : resolvers()) {
            try {
                if (r.canResolve(songInfo)) return true;
            } catch (Throwable ignored) {
                // 外部解析器出错不该影响播放
            }
        }
        return false;
    }

    /** 交给第一个愿意接的解析器（没人接返回一个已完成的空结果）。 */
    public static CompletableFuture<ItemMusicCD.SongInfo> resolve(ItemMusicCD.SongInfo songInfo) {
        for (IAsyncSongUrlResolver r : resolvers()) {
            try {
                if (r.canResolve(songInfo)) {
                    return r.resolve(songInfo);
                }
            } catch (Throwable t) {
                top.hmjmfabc.projector.Projector.LOGGER.warn(
                        "[Projector][音乐] 外部解析器 {} 报错（已跳过）：{}",
                        r.getClass().getSimpleName(), String.valueOf(t));
            }
        }
        return CompletableFuture.completedFuture(songInfo);
    }

    /** 与 NetMusic 同名的初始化钩子（投影仪不需要额外初始化，保留给附属调用）。 */
    public static void init() {
        // 无操作
    }
}

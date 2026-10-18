package top.hmjmfabc.projector.compat.netmusic.api.resolver;

import top.hmjmfabc.projector.compat.netmusic.api.ItemMusicCD;

import java.util.concurrent.CompletableFuture;

/**
 * 【27.2.2】网络音乐机 {@code api.resolver.IAsyncSongUrlResolver} 的**镜像接口**。
 *
 * <p>签名与语义按公开 API 对齐，实现方（例如 VIP 登录附属）改一行 import 即可接入。
 * 投影仪的音乐模块在解析音频地址时，会**先问所有已注册的解析器**
 * （按 {@link #getPriority()} 从高到低），没人接手才走内置的网易云解析。</p>
 */
public interface IAsyncSongUrlResolver {

    /** 这个解析器接不接这首歌。 */
    boolean canResolve(ItemMusicCD.SongInfo songInfo);

    /** 异步解析出可播放的地址（返回的 SongInfo 里 songUrl 必须非空）。 */
    CompletableFuture<ItemMusicCD.SongInfo> resolve(ItemMusicCD.SongInfo songInfo);

    /** 优先级，大的先问（与 NetMusic 同名同义）。 */
    default int getPriority() {
        return 0;
    }
}

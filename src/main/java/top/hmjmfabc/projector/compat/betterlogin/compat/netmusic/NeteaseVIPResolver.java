package top.hmjmfabc.projector.compat.betterlogin.compat.netmusic;

import top.hmjmfabc.projector.compat.betterlogin.NetMusicBetterLogin;
import top.hmjmfabc.projector.compat.betterlogin.api.pojo.NetEaseMusicPlayInfo;
import top.hmjmfabc.projector.compat.betterlogin.config.GeneralConfig;
import top.hmjmfabc.projector.compat.netmusic.api.resolver.IAsyncSongUrlResolver;
import top.hmjmfabc.projector.compat.netmusic.api.ItemMusicCD;
import com.google.gson.Gson;
import net.minecraft.Util;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static top.hmjmfabc.projector.client.music.NetEaseApi.MUSIC_163_URL;

public class NeteaseVIPResolver implements IAsyncSongUrlResolver {
    private static final Gson GSON = new Gson();
    private static final Pattern PATTERN = Pattern.compile("^.*?\\?id=(\\d+)\\.mp3$");

    @Override
    public boolean canResolve(ItemMusicCD.SongInfo songInfo) {
        String songUrl = songInfo.songUrl;
        return songUrl.startsWith(MUSIC_163_URL) && PATTERN.matcher(songUrl).find();
    }

    @Override
    public CompletableFuture<ItemMusicCD.SongInfo> resolve(ItemMusicCD.SongInfo songInfo) {
        return getRealSongInfo(songInfo);
    }

    @Override
    public int getPriority() {
        return 10;
    }

    public static CompletableFuture<ItemMusicCD.SongInfo> getRealSongInfo(ItemMusicCD.SongInfo songInfo) {
        return CompletableFuture.supplyAsync(() -> {
            String songUrl = songInfo.songUrl;
            if (!songUrl.startsWith(MUSIC_163_URL)) {
                return songInfo;
            }
            Matcher matcher = PATTERN.matcher(songUrl);
            if (!matcher.find()) {
                return songInfo;
            }
            long musicId = Long.parseLong(matcher.group(1));
            try {
                String response = NetMusicBetterLogin.NETEASE_API.getPlayInfo(musicId, GeneralConfig.getLevel());
                NetEaseMusicPlayInfo playInfo = GSON.fromJson(response, NetEaseMusicPlayInfo.class);
                Optional<NetEaseMusicPlayInfo.PlayInfo> info = playInfo.getPlayInfo();
                Integer code = info.map(NetEaseMusicPlayInfo.PlayInfo::getCode)
                        .orElseThrow(() -> new RuntimeException("code is null"));
                if (code != 200) {
                    throw new RuntimeException(String.format("Response code is %d, Please check vip status and song availability.", code));
                }
                String url = info.map(NetEaseMusicPlayInfo.PlayInfo::getUrl)
                        .orElseThrow(() -> new RuntimeException("url is null"));
                int time = info.map(NetEaseMusicPlayInfo.PlayInfo::getTime)
                        .orElseThrow(() -> new RuntimeException("time is null")) / 1000;
                // 如果是播客声音, 接口返回的时间会是 0
                // 这里用 podcastCtrp 字段来判断是否为播客, 如果是播客且时间为 0 则使用原来的时间
                int finalTime = info.map(NetEaseMusicPlayInfo.PlayInfo::getPodcastCtrp)
                        // 双重保险, 只在时间为 0 时才使用原来的时间
                        .map(c -> time == 0 ? songInfo.songTime : time)
                        .orElse(time);
                songInfo.songUrl = url;
                songInfo.songTime = finalTime;
            } catch (Throwable e) {
                NetMusicBetterLogin.LOGGER.error("Failed to get play info for music id: {}", musicId, e);
            }
            return songInfo;
        }, Util.backgroundExecutor());
    }
}

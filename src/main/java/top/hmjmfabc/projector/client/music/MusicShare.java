package top.hmjmfabc.projector.client.music;

import org.jetbrains.annotations.Nullable;
import top.hmjmfabc.projector.Projector;
import top.hmjmfabc.projector.client.media.LocalMedia;
import top.hmjmfabc.projector.client.media.MediaUploader;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 【27.2】把「玩家本机的一首音频」发布到服务端 —— 别的玩家才听得到。
 *
 * <h2>为什么必须有这个类（这是玩家报的一个真 Bug）</h2>
 * <p>玩家原话：<b>「服务器上其他玩家听不到玩家 A 放置的本地音乐」</b>。</p>
 * <p>根因很直白：本地音乐以前只在控件里存一个<b>文件路径</b>
 * （{@code .minecraft/projector/musics/xxx.mp3}），而那份文件<b>只存在于 A 的电脑上</b>；
 * 别的客户端按这个路径去找，必然找不到 ⇒ {@code openStream()} 抛异常 ⇒ 什么都不响。
 * 图片与视频不会这样，因为它们选完就按 SHA-1 上传到服务端、由所有客户端按哈希下载复用 ——
 * 偏偏音乐这条线当初没接上。</p>
 *
 * <h2>做法：与图片/视频完全同一条管道</h2>
 * <ol>
 *   <li>算出文件内容的 <b>SHA-1</b>，用它当控件的 key；</li>
 *   <li>把「自己这一份」登记进本地索引（{@link LocalMedia#registerCache}）——
 *       音乐目录不在素材扫描范围里，不登记的话连自己都放不了；</li>
 *   <li>后台把整份文件上传给服务端（{@link MediaUploader#upload}，**非视频**语义
 *       ⇒ 整段文件、按图片那条通道下发）；</li>
 *   <li>其他客户端的 {@code MusicManager} 发现自己没有这个哈希，就用
 *       {@code MediaCache.prefetch(hash, false)} 把它下载下来，下好即自动开始播放。</li>
 * </ol>
 *
 * <p><b>失败必须留痕</b>：上传失败时日志里有一条 WARN 明说「其他玩家听不到这首」，
 * 界面上也会带一句提示 —— 这种事静默掉，玩家只会以为是「音乐控件坏了」。</p>
 */
public final class MusicShare {

    private MusicShare() {
    }

    /**
     * 发布结果。
     *
     * @param key      写进控件的 key：成功 = SHA-1；失败 = 回退到原来的文件路径 key
     * @param uploaded 是否成功排队上传（false 时其他玩家听不到，本地仍能听）
     * @param message  给界面显示的一句话
     */
    public record Published(String key, boolean uploaded, String message) {
    }

    /**
     * 发布一首本地音频。
     *
     * @param file        音频文件（null / 不存在时原样返回失败结果，不抛异常）
     * @param displayName 展示名（文件名，用于日志与服务端记录）
     */
    public static Published publish(@Nullable Path file, @Nullable String displayName) {
        if (file == null || !Files.isRegularFile(file)) {
            return new Published(null, false, "找不到这个音频文件");
        }
        String name = displayName == null || displayName.isBlank()
                ? file.getFileName().toString() : displayName;
        final String hash;
        try {
            hash = LocalMedia.sha1File(file);
        } catch (Throwable t) {
            Projector.LOGGER.warn("[Projector][音乐] 算哈希失败（{}）：{}；这首只能自己听",
                    name, t.toString());
            return new Published(null, false, "算哈希失败：" + t);
        }
        // ① 先让「自己」能放：音乐目录不在素材扫描范围里，必须登记进索引
        try {
            LocalMedia.registerCache(hash, file);
        } catch (Throwable t) {
            Projector.LOGGER.warn("[Projector][音乐] 登记本地音频失败（{}）：{}", name, t.toString());
        }
        long size = 0L;
        try {
            size = Files.size(file);
        } catch (Throwable ignored) {
            // 拿不到大小不影响发布
        }
        // ② 后台发布到服务端（与图片/视频同一条管道：分片 + 服务端去重 + 哈希校验）
        final long bytes = size;
        boolean queued;
        try {
            queued = MediaUploader.upload(file, false, new MediaUploader.Callback() {
                @Override
                public void onProgress(float fraction, String message) {
                    // 音乐文件不大，进度不进日志（免得刷屏）
                }

                @Override
                public void onDone(boolean success, String message) {
                    if (success) {
                        Projector.LOGGER.info("[Projector][音乐] 已发布到服务端：{}（哈希 {}，{} MB）"
                                        + "—— 其他玩家现在也能听到这首",
                                name, MusicTrack.shortHash(hash), bytes / 1048576.0);
                    } else {
                        Projector.LOGGER.warn("[Projector][音乐] 发布失败（{}，哈希 {}）：{}"
                                        + " ⇒ 其他玩家听不到这首（本地播放不受影响）",
                                name, MusicTrack.shortHash(hash), message);
                    }
                }
            });
        } catch (Throwable t) {
            Projector.LOGGER.warn("[Projector][音乐] 发布请求提交失败（{}）：{}", name, t.toString());
            queued = false;
        }
        Projector.LOGGER.info("[Projector][音乐] 本地音乐改为按哈希引用：{} → {}（{} 字节）",
                name, MusicTrack.shortHash(hash), bytes);
        return new Published(hash, queued,
                queued ? "已发布到服务端（其他玩家也能听到）" : "发布未提交：其他玩家可能听不到");
    }
}

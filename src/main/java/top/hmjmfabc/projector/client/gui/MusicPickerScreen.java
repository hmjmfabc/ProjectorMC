package top.hmjmfabc.projector.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import top.hmjmfabc.projector.Projector;
import top.hmjmfabc.projector.client.media.LocalMedia;
import top.hmjmfabc.projector.client.music.MusicTrack;
import top.hmjmfabc.projector.client.music.NetEaseApi;
import top.hmjmfabc.projector.common.Plane;
import top.hmjmfabc.projector.common.widget.MusicWidget;
import top.hmjmfabc.projector.common.widget.Widget;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 音乐选择界面：左边是候选列表，右边是网易云入口。
 *
 * <p>两个来源：</p>
 * <ul>
 *   <li><b>本地文件</b>：扫 {@code .minecraft/projector/musics}、
 *       {@code .minecraft/projector/media} 与旧版 {@code versions/medias}，
 *       认 MP3 / FLAC / M4A / AAC / WAV；</li>
 *   <li><b>网易云音乐</b>：可以按关键字搜索，也可以直接粘贴歌曲 ID 或分享链接。</li>
 * </ul>
 *
 * <p>与图片/视频选择器同一套交互：选中即回填并关闭；什么都不选就返回的话，
 * 会把这个「空音乐控件」删掉（不然平面上会留下一个点不动的空壳）。</p>
 */
public class MusicPickerScreen extends ProjectorScreen {

    /** 列表里的一行。 */
    private record Entry(String title, String subtitle, MusicTrack track) {
    }

    private final Screen parent;
    private final Plane plane;
    @Nullable
    private final Widget target;
    private final Consumer<MusicTrack> onPick;

    private final List<Entry> localEntries = new ArrayList<>();
    private final List<Entry> searchEntries = new ArrayList<>();

    @Nullable
    private ScrollList<Entry> list;
    @Nullable
    private EditBox queryBox;

    private boolean showSearch;
    private String status = "正在扫描本地音乐…";
    private boolean picked;

    public MusicPickerScreen(Screen parent, Plane plane, @Nullable Widget target,
                             Consumer<MusicTrack> onPick) {
        super(Component.literal("选择音乐"));
        this.parent = parent;
        this.plane = plane;
        this.target = target;
        this.onPick = onPick;
    }

    @Override
    protected void init() {
        int pad = 12;
        int listW = Math.max(160, (int) (this.width * 0.52));
        int listH = this.height - pad * 2 - 58;
        int rx = pad + listW + 10;
        int rw = Math.max(120, this.width - rx - pad);

        ScrollList<Entry> l = new ScrollList<>(pad, pad + 22, listW, listH,
                e -> e.title(),
                e -> e.subtitle(),
                (e, idx) -> pick(e));
        l.setItems(showSearch ? searchEntries : localEntries);
        this.list = addRenderableWidget(l);

        queryBox = editBox(rx, pad + 22, rw, 18, "", 256, null);
        queryBox.setHint(Component.literal("歌名关键字 / 歌曲ID / 分享链接"));

        int by = pad + 46;
        button("搜索网易云", rx, by, rw, 20, b -> doSearch());
        by += 24;
        button("用 ID / 链接添加", rx, by, rw, 20, b -> addById());
        by += 24;
        button(showSearch ? "显示本地音乐" : "显示搜索结果", rx, by, rw, 20, b -> {
            showSearch = !showSearch;
            rebuildWidgets();
        });
        by += 24;
        button("重新扫描本地", rx, by, rw, 20, b -> {
            scanLocalAsync();
            showSearch = false;
            rebuildWidgets();
        });
        by += 24;
        button("打开音乐目录", rx, by, rw, 20, b -> LocalMedia.ensureDirectories());

        button("取消", pad, this.height - pad - 24, listW / 2 - 4, 20, b -> onClose());
        button("返回编辑器", pad + listW / 2 + 4, this.height - pad - 24, listW / 2 - 4, 20,
                b -> onClose());

        scanLocalAsync();
    }

    // ------------------------------------------------------------------ 数据

    /** 扫本地音频（工作线程，避免卡界面）。 */
    private void scanLocalAsync() {
        Thread scanner = new Thread(() -> {
            List<Entry> found = new ArrayList<>();
            for (Path dir : MusicTrack.searchDirs()) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                try (Stream<Path> stream = Files.list(dir)) {
                    stream.filter(Files::isRegularFile)
                            .filter(p -> MusicTrack.isAudioFile(p.getFileName().toString()))
                            .sorted()
                            .forEach(p -> {
                                String name = p.getFileName().toString();
                                long size = 0;
                                try {
                                    size = Files.size(p);
                                } catch (Exception ignored) {
                                    // 拿不到大小不影响选择
                                }
                                found.add(new Entry(name, dir.getFileName() + " · " + human(size),
                                        MusicTrack.local(MusicTrack.keyFor(p), stripExt(name), 0L)));
                            });
                } catch (Exception e) {
                    Projector.LOGGER.debug("[Projector][音乐] 扫描 {} 失败：{}", dir, e.toString());
                }
            }
            Minecraft.getInstance().execute(() -> {
                localEntries.clear();
                localEntries.addAll(found);
                if (!showSearch) {
                    status = found.isEmpty()
                            ? "本地没有音频文件（放到 .minecraft/projector/musics 里）"
                            : "本地音乐 " + found.size() + " 首";
                    if (list != null) {
                        list.setItems(localEntries);
                    }
                }
            });
        }, "Projector-Music-Scan");
        scanner.setDaemon(true);
        scanner.setPriority(Thread.MIN_PRIORITY);
        scanner.start();
    }

    private void doSearch() {
        String query = queryBox == null ? "" : queryBox.getValue().trim();
        if (query.isEmpty()) {
            status = "先输入歌名或歌手";
            return;
        }
        // 输入的是 ID / 链接就直接按 ID 走，不必先搜索
        if (NetEaseApi.parseSongId(query) > 0) {
            addById();
            return;
        }
        status = "正在搜索「" + query + "」…";
        Thread worker = new Thread(() -> {
            try {
                List<NetEaseApi.SearchHit> hits = NetEaseApi.search(query);
                Minecraft.getInstance().execute(() -> {
                    searchEntries.clear();
                    for (NetEaseApi.SearchHit hit : hits) {
                        // fee == 1 是网易云的「VIP 歌曲」：游客态（没填 Cookie）拿不到直链，
                        // 先在列表上标出来，免得玩家点了之后一头雾水
                        String vip = hit.fee() == 1 ? " · VIP（可能需要 Cookie）" : "";
                        searchEntries.add(new Entry(hit.title(), hit.artist() + " · "
                                + formatDuration(hit.durationMs()) + vip, hit.toTrack()));
                    }
                    showSearch = true;
                    status = hits.isEmpty() ? "没搜到结果" : "搜索到 " + hits.size() + " 首（点一行即可选中）";
                    rebuildWidgets();
                });
            } catch (Exception e) {
                Minecraft.getInstance().execute(() -> status = "搜索失败：" + shortMessage(e));
                Projector.LOGGER.warn("[Projector][音乐] 搜索失败：{}", e.toString());
            }
        }, "Projector-Music-Search");
        worker.setDaemon(true);
        worker.start();
    }

    private void addById() {
        String query = queryBox == null ? "" : queryBox.getValue().trim();
        long id = NetEaseApi.parseSongId(query);
        if (id <= 0) {
            status = "认不出歌曲 ID（可以粘贴 music.163.com 的分享链接）";
            return;
        }
        status = "正在读取歌曲 " + id + " …";
        Thread worker = new Thread(() -> {
            try {
                NetEaseApi.SongDetail detail = NetEaseApi.detail(id);
                Minecraft.getInstance().execute(() -> {
                    if (detail.fee() == 1) {
                        status = "这是 VIP 歌曲，游客态可能无法播放（可在配置里填 neteaseCookie）";
                    }
                    pick(new Entry(detail.title(),
                            detail.artist() + " · " + formatDuration(detail.durationMs()),
                            MusicTrack.netease(detail.id(), detail.title(), detail.artist(),
                                    detail.durationMs())));
                });
            } catch (Exception e) {
                Minecraft.getInstance().execute(() -> status = "读取失败：" + shortMessage(e));
                Projector.LOGGER.warn("[Projector][音乐] 取歌曲信息失败 {}：{}", id, e.toString());
            }
        }, "Projector-Music-Detail");
        worker.setDaemon(true);
        worker.start();
    }

    private void pick(Entry entry) {
        if (entry == null || entry.track() == null || picked) {
            return;
        }
        MusicTrack track = entry.track();
        if (track.kind() == MusicTrack.Kind.LOCAL && track.durationMs() <= 0) {
            // 本地文件的音频里没有元数据时长：选中时后台量一次，
            // 免得控件先显示成兜底的 3:00（用户实测报过「2:40 显示成 3:00」）
            picked = true;
            status = "正在读取音频时长…";
            Thread worker = new Thread(() -> {
                long duration = probeDuration(track);
                Minecraft.getInstance().execute(() -> onPick.accept(duration > 0
                        ? MusicTrack.local(track.key(), track.title(), duration) : track));
            }, "Projector-Music-Probe");
            worker.setDaemon(true);
            worker.start();
            return;
        }
        picked = true;
        onPick.accept(track);
    }

    /**
     * 量本地音频的时长：解码一次 + **把文件大小交给探测器**（码率×字节数那条最可靠，
     * 因为 mp3spi 对「流式读入的 MP3」不给帧数）。
     */
    private static long probeDuration(MusicTrack track) {
        java.nio.file.Path path = MusicTrack.resolveLocal(track.key());
        long bytes = 0L;
        try {
            if (path != null) {
                bytes = java.nio.file.Files.size(path);
            }
        } catch (Throwable ignored) {
            // 拿不到大小就走后面的兜底
        }
        try (java.io.InputStream raw = track.openStream();
             javax.sound.sampled.AudioInputStream pcm =
                     top.hmjmfabc.projector.client.music.AudioDecoder.open(raw)) {
            long ms = top.hmjmfabc.projector.client.music.AudioDecoder.probeDurationMs(pcm, bytes);
            Projector.LOGGER.info("[Projector][音乐] 量时长 {}：{}ms（{} 字节，{} Hz，帧数={}）",
                    track.key(), ms, bytes, (int) pcm.getFormat().getSampleRate(), pcm.getFrameLength());
            return ms;
        } catch (Throwable t) {
            Projector.LOGGER.debug("[Projector][音乐] 读取时长失败 {}：{}", track.key(), t.toString());
        }
        return 0L;
    }

    @Override
    public void onClose() {
        if (!picked && target != null && target instanceof MusicWidget music && !music.hasTrack()) {
            // 什么都没选：把这个空壳删掉（与图片/视频选择器一致）
            CompoundTag t = new CompoundTag();
            t.putUUID("widget", music.id);
            PlaneDialogScreen.sendFor(plane, "removeWidget", t);
        }
        Minecraft.getInstance().setScreen(parent);
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        super.render(gfx, mouseX, mouseY, partialTick);
        labelShadow(gfx, showSearch ? "网易云搜索结果" : "本地音乐", 12, 8, TEXT_ACCENT);
        label(gfx, status, 12, this.height - 40, TEXT_DIM);
        String hint = "本地音乐放在 .minecraft/projector/musics 里；网易云可以搜关键字，"
                      + "也可以直接粘贴歌曲 ID / 分享链接。";
        label(gfx, hint, 12, this.height - 30, TEXT_DIM);
    }

    private static String formatDuration(long ms) {
        long total = Math.max(0, ms) / 1000;
        return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60);
    }

    private static String human(long bytes) {
        if (bytes >= 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
        }
        return String.format(Locale.ROOT, "%d KB", Math.max(1, bytes / 1024));
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String shortMessage(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isBlank()) {
            m = e.getClass().getSimpleName();
        }
        return m.length() > 80 ? m.substring(0, 80) + "…" : m;
    }
}


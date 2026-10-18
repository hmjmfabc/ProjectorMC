package top.hmjmfabc.projector.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import top.hmjmfabc.projector.Projector;
import top.hmjmfabc.projector.client.music.MusicManager;
import top.hmjmfabc.projector.client.music.MusicTrack;
import top.hmjmfabc.projector.common.Plane;
import top.hmjmfabc.projector.common.widget.MusicWidget;

import java.util.ArrayList;
import java.util.List;

/**
 * 【27.2.2】播放列表界面（用户要求「音乐控件要有播放列表」）。
 *
 * <h2>怎么用</h2>
 * 左边是列表（**从上到下就是播放顺序**），点一行把它选中；右边所有按钮都作用在选中的那一行：
 * 播放 / 上移 / 下移 / 移除。「添加歌曲…」打开与「选择音乐」同一个界面
 * （{@link MusicPickerScreen}），在那里选中的歌是**追加到列表末尾**，而不是替换当前这首。
 *
 * <h2>播放模式</h2>
 * 顺序（放到最后一首停下）/ 列表循环 / 单曲循环 / 随机。
 * 真正推进列表的是**服务端**（{@code ServerNetHandler.tickPlaylists}）——
 * 播放状态本来就是服务端权威的，放在服务端才不会出现「N 个玩家各推一次」的乱象。
 *
 * <h2>提交方式</h2>
 * 与编辑器完全一致：改完控件立刻发 {@code updateWidget}（不做本地猜测），
 * 服务端广收后所有客户端一起看到新的列表。
 */
public class MusicPlaylistScreen extends ProjectorScreen {

    /** 一行（下标一起带着，免得来回 indexOf）。 */
    private record Row(int index, MusicWidget.Entry entry) {
    }

    private final Screen parent;
    private final Plane plane;
    private final MusicWidget music;

    private final List<Row> rows = new ArrayList<>();
    @Nullable
    private ScrollList<Row> list;
    private int selected;

    public MusicPlaylistScreen(Screen parent, Plane plane, MusicWidget music) {
        super(Component.literal("\u64ad\u653e\u5217\u8868"));
        this.parent = parent;
        this.plane = plane;
        this.music = music;
        // 默认选中「正在播的那一首」，没有就选第一首 —— 打开界面就能直接点「播放」
        this.selected = music.playlistIndex >= 0 && music.playlistIndex < music.playlistSize()
                ? music.playlistIndex : (music.playlistSize() > 0 ? 0 : -1);
    }

    @Override
    protected void init() {
        final int pad = 12;
        final int listW = Math.max(160, (int) (this.width * 0.52));
        final int listH = this.height - pad * 2 - 58;
        final int rx = pad + listW + 10;
        final int rw = Math.max(120, this.width - rx - pad);

        refreshRows();
        ScrollList<Row> l = new ScrollList<>(pad, pad + 22, listW, listH,
                r -> (r.index() + 1) + ". " + r.entry().display(),
                r -> sourceName(r.entry().kind())
                        + (r.index() == music.playlistIndex ? " \u00b7 \u6b63\u5728\u64ad\u653e" : ""),
                (r, idx) -> {
                    selected = r.index();
                    if (list != null) {
                        list.setSelected(selected);
                    }
                });
        l.setItems(rows);
        l.setSelected(selected);
        this.list = addRenderableWidget(l);

        int by = pad + 22;
        button("\u64ad\u653e\u9009\u4e2d\u7684\u8fd9\u9996", rx, by, rw, 20, b -> playSelected());
        by += 24;
        // 上移 / 下移 并排，省一行高度（窄屏很重要）
        button("\u4e0a\u79fb", rx, by, rw / 2 - 2, 20, b -> move(-1));
        button("\u4e0b\u79fb", rx + rw / 2 + 2, by, rw / 2 - 2, 20, b -> move(1));
        by += 24;
        button("\u79fb\u9664\u9009\u4e2d\u7684\u8fd9\u9996", rx, by, rw, 20, b -> removeSelected());
        by += 28;
        button("\u6dfb\u52a0\u6b4c\u66f2\u2026", rx, by, rw, 20, b -> addTrack());
        by += 24;
        button("\u64ad\u653e\u6a21\u5f0f\uff1a" + MusicWidget.MODE_NAMES[
                Math.floorMod(music.playMode, MusicWidget.MODE_NAMES.length)], rx, by, rw, 20, b -> {
            music.playMode = (music.playMode + 1) % MusicWidget.MODE_NAMES.length;
            submit("\u64ad\u653e\u6a21\u5f0f\uff1a" + MusicWidget.MODE_NAMES[music.playMode]);
            rebuildWidgets();
        });
        by += 24;
        button("\u6e05\u7a7a\u5217\u8868", rx, by, rw, 20, b -> {
            music.clearPlaylist();
            selected = -1;
            submit("\u64ad\u653e\u5217\u8868\u5df2\u6e05\u7a7a");
            rebuildWidgets();
        });
        by += 28;
        button("\u8fd4\u56de\u7f16\u8f91\u5668", rx, by, rw, 20, b -> onClose());

        button("\u5173\u95ed", pad, this.height - pad - 24, listW / 2 - 4, 20, b -> onClose());
        button("\u4fdd\u5b58\u5e76\u8fd4\u56de", pad + listW / 2 + 4, this.height - pad - 24,
                listW / 2 - 4, 20, b -> onClose());
    }

    // ------------------------------------------------------------------ 数据

    private void refreshRows() {
        rows.clear();
        List<MusicWidget.Entry> entries = music.playlistEntries();
        for (int i = 0; i < entries.size(); i++) {
            rows.add(new Row(i, entries.get(i)));
        }
    }

    /** 列表改了之后：重算行、把选中夹回合法范围、重建界面。 */
    private void afterListChange() {
        if (selected >= music.playlistSize()) {
            selected = music.playlistSize() - 1;
        }
        refreshRows();
        rebuildWidgets();
    }

    private void move(int delta) {
        int to = selected + delta;
        if (selected < 0 || to < 0 || to >= music.playlistSize()) {
            return;
        }
        music.moveInPlaylist(selected, to);
        selected = to;
        submit("\u5df2" + (delta < 0 ? "\u4e0a\u79fb" : "\u4e0b\u79fb"));
        afterListChange();
    }

    private void removeSelected() {
        if (selected < 0 || selected >= music.playlistSize()) {
            return;
        }
        String name = music.playlistAt(selected) == null ? "" : music.playlistAt(selected).display();
        music.removeFromPlaylist(selected);
        submit("\u5df2\u79fb\u9664\uff1a" + name);
        afterListChange();
    }

    /**
     * 播放选中的那一首。
     *
     * <p>三件事一次做完：把歌写进控件、把它标记成「列表里第几首」、开始播。
     * 起点用 0（重头放），与在选歌界面里换歌的手感一致。</p>
     */
    private void playSelected() {
        MusicWidget.Entry e = music.playlistAt(selected);
        if (e == null) {
            return;
        }
        music.setTrackInfo(e.kind(), e.key(), e.title(), e.artist(), e.durationMs());
        music.playlistIndex = selected;
        music.playing = true;
        music.startedGameTime = gameTimeNow();
        // 波形/歌词按新歌重来；本机正在放这一首时先停掉，下一 tick 会按新歌重新起流
        MusicManager.onTrackChanged(plane, music);
        submit("\u6b63\u5728\u64ad\u653e\u7b2c " + (selected + 1) + " \u9996\uff1a" + e.display());
        rebuildWidgets();
    }

    /** 「添加歌曲…」：复用选歌界面，但选中后是**追加**到列表末尾。 */
    private void addTrack() {
        Minecraft.getInstance().setScreen(new MusicPickerScreen(this, plane, null, track -> {
            if (track == null || track.isEmpty()) {
                return;
            }
            music.addToPlaylist(track.kind().name(), track.key(), track.title(),
                    track.artist(), track.durationMs());
            selected = music.playlistSize() - 1;
            // 列表原来一首都没有、而且控件里也没歌 ⇒ 顺手把这首设成当前歌（否则点了播放没反应）
            if (!music.hasTrack()) {
                music.setTrackInfo(track.kind().name(), track.key(), track.title(),
                        track.artist(), track.durationMs());
                music.playlistIndex = selected;
            } else if (music.playlistIndex < 0) {
                music.playlistIndex = music.indexOfTrack(track.kind().name(), track.key());
            }
            submit("\u5df2\u6dfb\u52a0\u5230\u64ad\u653e\u5217\u8868\uff1a" + track.displayName());
            Minecraft.getInstance().setScreen(this);
        }, music));
    }

    /**
     * 把播放列表提交给服务端（**唯一出口**）。
     *
     * <p>与编辑器里换歌走同一条路（{@code updateWidget} + 整个控件 data）——
     * 播放列表本来就是控件数据的一部分，不需要新协议。</p>
     */
    private void submit(String message) {
        status = message;
        CompoundTag t = new CompoundTag();
        t.putUUID("widget", music.id);
        t.put("data", music.save());
        PlaneDialogScreen.sendFor(plane, "updateWidget", t);
        Projector.LOGGER.info("[Projector][音乐] 播放列表更新：{}（{}）",
                music.describePlaylist(), message);
    }

    private static String sourceName(String kind) {
        if (kind == null) {
            return "\u672c\u5730";
        }
        return switch (kind.toUpperCase(java.util.Locale.ROOT)) {
            case "NETEASE" -> "\u7f51\u6613\u4e91";
            case "URL" -> "\u76f4\u94fe";
            default -> "\u672c\u5730";
        };
    }

    private static long gameTimeNow() {
        var level = Minecraft.getInstance().level;
        return level == null ? 0L : level.getGameTime();
    }

    private String status = "";

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        super.render(gfx, mouseX, mouseY, partialTick);
        labelShadow(gfx, "\u64ad\u653e\u5217\u8868\uff1a" + music.describePlaylist(), 12, 8, TEXT_ACCENT);
        label(gfx, status, 12, this.height - 40, TEXT_DIM);
        label(gfx, "\u4ece\u4e0a\u5230\u4e0b\u5c31\u662f\u64ad\u653e\u987a\u5e8f\uff1b"
                + "\u70b9\u4e00\u884c\u9009\u4e2d\u5b83\uff0c\u53f3\u8fb9\u7684\u6309\u94ae\u90fd\u4f5c\u7528\u5728\u9009\u4e2d\u7684\u90a3\u4e00\u884c\u3002",
                12, this.height - 30, TEXT_DIM);
    }
}

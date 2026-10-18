package top.hmjmfabc.projector.compat.netmusic.api;

/**
 * 【27.2.2】网络音乐机 {@code item.ItemMusicCD} 里那个 **SongInfo** 的镜像。
 *
 * <p>只为「解析器（resolver）API」而存在：NetMusic 的
 * {@code IAsyncSongUrlResolver} 用 {@code ItemMusicCD.SongInfo} 当参数与返回值，
 * 子类实现（例如 VIP 解析器）会在里面读 {@code songId/songUrl/songTime}。
 * 这里给出**同样的公开字段名**，让那些实现改一行 import 就能接过来。</p>
 *
 * <p>⚠ 这里**不复制它的任何实现**，字段语义按公开 API 命名对齐：
 * {@code songId} 网易云歌曲 id、{@code songUrl} 解析出来的音频地址、
 * {@code songTime} 时长（**秒**，与 NetMusic 一致）。</p>
 */
public final class ItemMusicCD {

    private ItemMusicCD() {
    }

    /**
     * 一首歌的解析结果（镜像 NetMusic 的同名内部类）。
     * <p>字段名/类型与原版一致：{@code songUrl} 是 **String**（不是 URL），
     * {@code songTime} 单位是**秒**。附属的解析器直接读它们。</p>
     */
    public static class SongInfo implements Cloneable {
        public String songUrl = "";
        public String songName = "";
        public int songTime;
        public String transName = "";
        public boolean vip;
        public boolean readOnly;
        public java.util.List<String> artists = new java.util.ArrayList<>();

        public SongInfo() {
        }

        public SongInfo(String songUrl, String songName, int songTime) {
            this.songUrl = songUrl;
            this.songName = songName;
            this.songTime = songTime;
        }

        /** 网易云歌曲 id（投影仪自用：从直链里解析出来的一并带在身上）。 */
        public int songId;

        public SongInfo copyFrom(SongInfo other) {
            this.songUrl = other.songUrl;
            this.songName = other.songName;
            this.songTime = other.songTime;
            this.transName = other.transName;
            this.vip = other.vip;
            this.readOnly = other.readOnly;
            this.artists = new java.util.ArrayList<>(other.artists);
            this.songId = other.songId;
            return this;
        }

        @Override
        public SongInfo clone() {
            SongInfo c = new SongInfo();
            c.copyFrom(this);
            return c;
        }

        @Override
        public String toString() {
            return "SongInfo{name=" + songName + ", time=" + songTime + "s, url=" + songUrl + "}";
        }
    }
}

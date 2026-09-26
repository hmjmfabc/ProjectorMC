package top.hmjmfabc.projector.server;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;
import top.hmjmfabc.projector.Projector;
import top.hmjmfabc.projector.ProjectorConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 存档内的媒体资源仓库。
 *
 * <p>与平面数据分开存放：</p>
 * <pre>
 * &lt;存档&gt;/projector/assets/&lt;hash&gt;.bin      媒体原始字节
 * &lt;存档&gt;/data/projector_media.dat      媒体索引（名称、帧表、尺寸）
 * &lt;存档&gt;/projector/fonts/&lt;name&gt;.ttf     上传的自定义字体
 * </pre>
 *
 * <p>之所以把二进制单独落文件、索引放进 SavedData，是因为 NBT 里塞大块
 * 二进制既浪费内存又会拖慢存档写入；分开之后索引始终很小，媒体文件按需读取。</p>
 */
public final class MediaStore extends SavedData {

    public static final String FILE_ID = "projector_media";

    /** 单次读取（一帧 / 一片）的字节上限，防止客户端构造超大请求。 */
    public static final int MAX_FRAME_BYTES = 4 * 1024 * 1024;

    /** 一条媒体记录。 */
    public static final class Entry {
        public String hash = "";
        public String name = "";
        public boolean video;
        public int width, height;
        public int frameCount = 1;
        public double fps = 10;
        public long size;
        /** 每一帧在文件中的 [偏移, 长度] 组合（long = (offset<<24)|len 不安全，这里用两个 int 的 long 打包）。 */
        public final List<long[]> frames = new ArrayList<>();

        public CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("hash", hash);
            t.putString("name", name);
            t.putBoolean("video", video);
            t.putInt("w", width);
            t.putInt("h", height);
            t.putInt("frames", frameCount);
            t.putDouble("fps", fps);
            t.putLong("size", size);
            if (video && !frames.isEmpty()) {
                long[] arr = new long[frames.size() * 2];
                for (int i = 0; i < frames.size(); i++) {
                    arr[i * 2] = frames.get(i)[0];
                    arr[i * 2 + 1] = frames.get(i)[1];
                }
                t.putLongArray("frameTable", arr);
            }
            return t;
        }

        public static Entry load(CompoundTag t) {
            Entry e = new Entry();
            e.hash = t.getString("hash");
            e.name = t.getString("name");
            e.video = t.getBoolean("video");
            e.width = t.getInt("w");
            e.height = t.getInt("h");
            e.frameCount = Math.max(1, t.getInt("frames"));
            e.fps = t.contains("fps") && t.getDouble("fps") > 0.01 ? t.getDouble("fps") : 10;
            e.size = t.getLong("size");
            if (t.contains("frameTable")) {
                long[] arr = t.getLongArray("frameTable");
                for (int i = 0; i + 1 < arr.length; i += 2) {
                    e.frames.add(new long[]{arr[i], arr[i + 1]});
                }
            }
            return e;
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private transient Path assetDir;

    public static MediaStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(MediaStore::new, MediaStore::load), FILE_ID);
    }

    public static MediaStore load(CompoundTag tag, HolderLookup.Provider registries) {
        MediaStore store = new MediaStore();
        ListTag list = tag.getList("entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            Entry e = Entry.load(list.getCompound(i));
            // 存档里的哈希同样要过滤：手工改坏存档也不能让它拼出非法路径
            if (Sanitize.isHash(e.hash)) {
                store.entries.put(e.hash.toLowerCase(java.util.Locale.ROOT), e);
            }
        }
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Entry e : entries.values()) {
            list.add(e.save());
        }
        tag.put("entries", list);
        return tag;
    }

    // ------------------------------------------------------------------

    private Path assetDir(MinecraftServer server) {
        if (assetDir == null) {
            assetDir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                    .resolve("projector").resolve("assets");
        }
        return assetDir;
    }

    @Nullable
    public Entry get(String hash) {
        return entries.get(hash);
    }

    /** 【①②⑫】只读的全部条目（用于向客户端下发媒体清单）。 */
    public Map<String, Entry> allEntries() {
        return java.util.Collections.unmodifiableMap(entries);
    }

    public boolean has(String hash) {
        return entries.containsKey(hash);
    }

    public int count() {
        return entries.size();
    }

    /**
     * 写入一份媒体。若同 hash 已存在则直接复用（去重）。
     *
     * @return 是否为新写入
     */
    /**
     * 写入一份媒体。
     *
     * <p>安全性：{@code entry.hash} 必须是通过校验的 SHA-1，
     * 文件名一律由「哈希 + .bin」拼出并做前缀校验，杜绝路径穿越。</p>
     */
    public boolean put(MinecraftServer server, Entry entry, byte[] data, long maxBytes) throws IOException {
        String hash = Sanitize.hash(entry.hash);
        if (hash == null) {
            throw new IOException("非法的媒体哈希: " + entry.hash);
        }
        entry.hash = hash;
        // 【②】上限由调用方给出：普通玩家 / 管理员 / 单人存档各不相同，
        // 不能再读一个全局配置项（那会让管理员的上限形同虚设）。
        if (data.length <= 0 || (maxBytes > 0 && data.length > maxBytes)) {
            throw new IOException("媒体大小超出限制: " + data.length + " > " + maxBytes);
        }
        // 帧表在 ServerNetHandler 里已经逐条做过边界校验（off+len <= 文件长度），
        // 这里只做总量上限与最终夹取，绝对不能清空——否则 readFrame 永远读不到帧。
        if (entry.video) {
            entry.frameCount = Math.max(1, Math.min(entry.frameCount, 200_000));
            entry.frames.removeIf(r -> r[0] < 0 || r[1] <= 0 || r[0] + r[1] > data.length);
            if (!entry.frames.isEmpty()) {
                entry.frameCount = entry.frames.size();
            }
        } else {
            entry.frames.clear();
            entry.frameCount = 1;
        }
        Path dir = assetDir(server);
        Files.createDirectories(dir);
        Path target = Sanitize.resolveInside(dir, hash + ".bin");
        if (target == null) {
            throw new IOException("非法的媒体路径");
        }
        if (!Files.exists(target)) {
            Path tmp = dir.resolve(entry.hash + ".bin.tmp");
            Files.write(tmp, data);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        boolean isNew = !entries.containsKey(entry.hash);
        if (isNew) {
            entries.put(entry.hash, entry);
            setDirty();
        }
        // 已存在同一哈希时保留原索引：哈希相同意味着内容相同，
        // 不允许客户端用伪造的元数据覆盖已有记录。
        return isNew;
    }

    /** 读取整个媒体文件的字节。 */
    @Nullable
    public byte[] read(MinecraftServer server, String hash) {
        if (!Sanitize.isHash(hash)) return null;
        Entry e = entries.get(hash);
        if (e == null) return null;
        Path f = Sanitize.resolveInside(assetDir(server), hash + ".bin");
        if (f == null) return null;
        if (!Files.isReadable(f)) {
            Projector.LOGGER.warn("[Projector] 媒体文件缺失: {}", f);
            return null;
        }
        try {
            return Files.readAllBytes(f);
        } catch (IOException ex) {
            Projector.LOGGER.warn("[Projector] 读取媒体失败 {}: {}", hash, ex.toString());
            return null;
        }
    }

    /** 只读取某一帧的字节（视频按需读取，避免整段载入内存）。 */
    @Nullable
    public byte[] readFrame(MinecraftServer server, String hash, int frame) {
        if (!Sanitize.isHash(hash)) return null;
        Entry e = entries.get(hash);
        if (e == null) return null;
        Path f = Sanitize.resolveInside(assetDir(server), hash + ".bin");
        if (f == null || !Files.isReadable(f)) return null;
        try {
            if (e.frames.isEmpty() || frame < 0 || frame >= e.frames.size()) {
                return null;
            }
            long[] range = e.frames.get(frame);
            long offset = range[0];
            long len = range[1];
            // 帧表来自客户端，必须再夹一次上界，否则几字节的包就能让服务端分配 GB 级数组
            if (offset < 0 || len <= 0 || offset + len > e.size || e.size <= 0) {
                return null;
            }
            int n = (int) Math.min(len, MAX_FRAME_BYTES);
            byte[] buf = new byte[n];
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f.toFile(), "r")) {
                raf.seek(offset);
                raf.readFully(buf);
            }
            return buf;
        } catch (IOException ex) {
            Projector.LOGGER.warn("[Projector] 读取视频帧失败 {}#{}: {}", hash, frame, ex.toString());
            return null;
        }
    }

    /** 按区间读取（用于分片下发图片，避免每次都整读文件）。 */
    @Nullable
    public byte[] readRange(MinecraftServer server, String hash, long offset, int length) {
        if (!Sanitize.isHash(hash) || length <= 0) return null;
        Entry e = entries.get(hash);
        if (e == null) return null;
        Path f = Sanitize.resolveInside(assetDir(server), hash + ".bin");
        if (f == null || !Files.isReadable(f)) return null;
        try {
            long size = Files.size(f);
            if (offset < 0 || offset >= size) return null;
            int n = (int) Math.min(Math.min(length, MAX_FRAME_BYTES), size - offset);
            byte[] buf = new byte[n];
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f.toFile(), "r")) {
                raf.seek(offset);
                raf.readFully(buf);
            }
            return buf;
        } catch (IOException ex) {
            return null;
        }
    }

    public boolean remove(MinecraftServer server, String hash) {
        Entry e = entries.remove(hash);
        if (e == null) return false;
        try {
            Files.deleteIfExists(assetDir(server).resolve(hash + ".bin"));
        } catch (IOException ex) {
            Projector.LOGGER.warn("[Projector] 删除媒体失败 {}: {}", hash, ex.toString());
        }
        setDirty();
        return true;
    }
}

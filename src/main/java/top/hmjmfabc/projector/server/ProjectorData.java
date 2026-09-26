package top.hmjmfabc.projector.server;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;
import top.hmjmfabc.projector.common.Plane;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 平面数据的持久化容器。
 *
 * <p>以 {@code world/data/projector_data.dat} 的形式保存在存档根目录下，
 * 所有维度共用同一份数据；每个平面用 {@code dimension} 字段标明所属维度。</p>
 *
 * <p>同时维护「方块 → 平面」的反向索引，用于快速判断某个方块是否属于受保护的平面。
 * 索引是运行时结构，不进存档，加载后按需重建。</p>
 */
public final class ProjectorData extends SavedData {

    public static final String FILE_ID = "projector_data";

    private final Map<ResourceLocation, List<Plane>> byDimension = new HashMap<>();
    /** 运行时反向索引：dimension -> (blockPos.asLong() -> planeIds)。 */
    private final transient Map<ResourceLocation, Map<Long, Set<UUID>>> index = new HashMap<>();

    /**
     * 取全局唯一的平面数据实例。
     *
     * <p>务必挂在<b>主世界</b>的 DataStorage 上：如果按维度各挂一份，
     * 每个维度都会写出独立的 {@code projector_data.dat}，
     * {@code byId} / 上限计数 / 跨维度查询都会拿到不一致的结果。</p>
     */
    public static ProjectorData get(ServerLevel level) {
        return get(level.getServer());
    }

    public static ProjectorData get(net.minecraft.server.MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(ProjectorData::new, ProjectorData::load), FILE_ID);
    }

    public static ProjectorData load(CompoundTag tag, HolderLookup.Provider registries) {
        ProjectorData data = new ProjectorData();
        ListTag dims = tag.getList("dimensions", Tag.TAG_COMPOUND);
        for (int i = 0; i < dims.size(); i++) {
            CompoundTag dt = dims.getCompound(i);
            ResourceLocation dim = ResourceLocation.tryParse(dt.getString("id"));
            if (dim == null) continue;
            ListTag planes = dt.getList("planes", Tag.TAG_COMPOUND);
            List<Plane> list = new ArrayList<>(planes.size());
            for (int j = 0; j < planes.size(); j++) {
                try {
                    list.add(Plane.load(planes.getCompound(j)));
                } catch (Exception ex) {
                    top.hmjmfabc.projector.Projector.LOGGER.warn("[Projector] 跳过损坏的平面数据 #{}: {}", j, ex.toString());
                }
            }
            data.byDimension.put(dim, list);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag dims = new ListTag();
        for (Map.Entry<ResourceLocation, List<Plane>> e : byDimension.entrySet()) {
            if (e.getKey() == null) continue;
            if (e.getValue().isEmpty()) continue;
            CompoundTag dt = new CompoundTag();
            dt.putString("id", e.getKey().toString());
            ListTag planes = new ListTag();
            for (Plane p : e.getValue()) {
                planes.add(p.save());
            }
            dt.put("planes", planes);
            dims.add(dt);
        }
        tag.put("dimensions", dims);
        tag.putInt("dataVersion", 1);
        return tag;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public List<Plane> planesIn(ResourceKey<Level> dimension) {
        return byDimension.computeIfAbsent(dimension.location(), k -> new ArrayList<>());
    }

    public List<Plane> planesIn(ResourceLocation dimension) {
        return byDimension.computeIfAbsent(dimension, k -> new ArrayList<>());
    }

    @Nullable
    public Plane byId(UUID id) {
        for (List<Plane> list : byDimension.values()) {
            for (Plane p : list) {
                if (p.id.equals(id)) return p;
            }
        }
        return null;
    }

    /** 取出包含指定方块的平面。 */
    public List<Plane> planesAt(ResourceLocation dimension, BlockPos pos) {
        Map<Long, Set<UUID>> idx = indexFor(dimension);
        Set<UUID> ids = idx.get(pos.asLong());
        if (ids == null || ids.isEmpty()) return Collections.emptyList();
        List<Plane> out = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            Plane p = byId(id);
            if (p != null) out.add(p);
        }
        return out;
    }

    public boolean isBlockCovered(ResourceLocation dimension, BlockPos pos) {
        Set<UUID> ids = indexFor(dimension).get(pos.asLong());
        return ids != null && !ids.isEmpty();
    }

    /** 是否存在「开启了平面保护」且覆盖该方块的平面。 */
    @Nullable
    public Plane protectionAt(ResourceLocation dimension, BlockPos pos) {
        for (Plane p : planesAt(dimension, pos)) {
            if (p.protectBlocks) return p;
        }
        return null;
    }

    public int countIn(ResourceLocation dimension) {
        return planesIn(dimension).size();
    }

    // ------------------------------------------------------------------
    // 修改
    // ------------------------------------------------------------------

    public void add(ResourceLocation dimension, Plane plane) {
        planesIn(dimension).add(plane);
        rebuildIndex(dimension);
        setDirty();
    }

    public void remove(ResourceLocation dimension, Plane plane) {
        List<Plane> list = planesIn(dimension);
        list.remove(plane);
        // 数据是全局共享的，plane.dimension 才是权威归属；两者不一致时两个都清一遍
        if (plane.dimension != null && !plane.dimension.equals(dimension)) {
            planesIn(plane.dimension).remove(plane);
            rebuildIndex(plane.dimension);
        }
        rebuildIndex(dimension);
        setDirty();
    }

    public void markDirty() {
        setDirty();
    }

    /** 方块被破坏 / 平面结构变化后调用，重建索引。 */
    public void rebuildIndex(ResourceLocation dimension) {
        Map<Long, Set<UUID>> idx = new HashMap<>();
        for (Plane p : planesIn(dimension)) {
            for (long key : p.blocks.keySet()) {
                idx.computeIfAbsent(key, k -> new HashSet<>(2)).add(p.id);
            }
        }
        index.put(dimension, idx);
    }

    public void rebuildAll() {
        index.clear();
    }

    private Map<Long, Set<UUID>> indexFor(ResourceLocation dimension) {
        Map<Long, Set<UUID>> idx = index.get(dimension);
        if (idx == null) {
            rebuildIndex(dimension);
            idx = index.get(dimension);
            if (idx == null) {
                idx = new HashMap<>();
                index.put(dimension, idx);
            }
        }
        return idx;
    }
}

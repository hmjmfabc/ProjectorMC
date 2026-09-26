package top.hmjmfabc.projector.client;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import top.hmjmfabc.projector.Projector;
import top.hmjmfabc.projector.common.Plane;
import top.hmjmfabc.projector.common.widget.Widget;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端平面缓存。
 *
 * <p>服务端是权威数据源，客户端只维护一份只读副本用于渲染与交互。
 * 任何修改都通过 {@code PlaneEdit} 包发送给服务端，等服务端回广播后再生效
 * （这样多人游戏里不会出现「我看到的和别人不一样」）。</p>
 */
public final class PlaneCache {

    private static final Map<ResourceLocation, List<Plane>> PLANES = new HashMap<>();

    private PlaneCache() {
    }

    public static List<Plane> planesIn(ResourceLocation dimension) {
        List<Plane> l = PLANES.get(dimension);
        return l == null ? Collections.emptyList() : l;
    }

    /** 当前所有已缓存的平面（跨维度）。 */
    public static List<Plane> all() {
        List<Plane> out = new ArrayList<>();
        for (List<Plane> l : PLANES.values()) out.addAll(l);
        return out;
    }

    public static Plane byId(UUID id) {
        for (List<Plane> l : PLANES.values()) {
            for (Plane p : l) {
                if (p.id.equals(id)) return p;
            }
        }
        return null;
    }

    /** 处理服务端下发的同步包。 */
    public static void handleSync(ResourceLocation dimension, CompoundTag tag) {
        if (tag == null) return;
        List<Plane> list = PLANES.computeIfAbsent(dimension, k -> new ArrayList<>());

        if (tag.hasUUID("plane") && !tag.contains("planes")) {
            UUID remove = tag.getUUID("plane");
            list.removeIf(p -> p.id.equals(remove));
            return;
        }

        boolean replace = tag.getBoolean("replace");
        ListTag planes = tag.getList("planes", Tag.TAG_COMPOUND);
        if (replace) {
            list.clear();
        }
        for (int i = 0; i < planes.size(); i++) {
            Plane loaded;
            try {
                loaded = Plane.load(planes.getCompound(i));
            } catch (Exception ex) {
                Projector.LOGGER.warn("[Projector] 客户端解析平面失败: {}", ex.toString());
                continue;
            }
            boolean merged = false;
            for (Plane existing : list) {
                if (existing.id.equals(loaded.id)) {
                    // 原地更新而不是替换对象引用：界面上已经打开的对话框/编辑器
                    // 持有的是同一个对象，这样它们才会实时反映服务端的最新状态。
                    existing.applyFrom(loaded);
                    merged = true;
                    break;
                }
            }
            if (!merged) {
                list.add(loaded);
            }
        }
    }

    /** 查找包含指定方块、并且朝向匹配的平面。 */
    public static Plane findAt(ResourceLocation dimension, net.minecraft.core.BlockPos pos, net.minecraft.core.Direction face) {
        for (Plane p : planesIn(dimension)) {
            if (p.face == face && p.contains(pos)) return p;
        }
        return null;
    }

    public static void clear() {
        PLANES.clear();
    }

    public static void clearDimension(ResourceLocation dimension) {
        PLANES.remove(dimension);
    }

    public static int count() {
        int n = 0;
        for (List<Plane> l : PLANES.values()) n += l.size();
        return n;
    }

    /** 统计控件数量（调试）。 */
    public static int widgetCount() {
        int n = 0;
        for (List<Plane> l : PLANES.values()) {
            for (Plane p : l) n += p.widgets.size();
        }
        return n;
    }

    /** 便捷：把控件替换为本地副本（用于界面即时预览，不发给服务端）。 */
    public static void applyLocalWidget(Plane plane, Widget widget) {
        if (plane == null || widget == null) return;
        for (int i = 0; i < plane.widgets.size(); i++) {
            if (plane.widgets.get(i).id.equals(widget.id)) {
                plane.widgets.set(i, widget);
                return;
            }
        }
        plane.widgets.add(widget);
    }

    /** 本地新增（服务端确认前的乐观更新）。 */
    public static void addLocal(ResourceLocation dimension, Plane plane) {
        List<Plane> list = PLANES.computeIfAbsent(dimension, k -> new ArrayList<>());
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(plane.id)) {
                list.set(i, plane);
                return;
            }
        }
        list.add(plane);
    }

    public static void removeLocal(UUID id) {
        for (List<Plane> l : PLANES.values()) {
            l.removeIf(p -> p.id.equals(id));
        }
    }
}

package top.hmjmfabc.projector.client;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import top.hmjmfabc.projector.common.Plane;

import java.util.UUID;

/**
 * 客户端圈选状态。
 *
 * <p>流程（与设计稿一致）：</p>
 * <ol>
 *   <li>准心对准某个方块面，按 <b>U</b>：向服务端请求把该面登记为平面；</li>
 *   <li>服务端创建/返回平面后，客户端把它设为「当前选中」，并以高光边框显示；</li>
 *   <li>再按 <b>U</b>：打开该平面的对话框；</li>
 *   <li>按 <b>I</b>：取消选中。</li>
 * </ol>
 */
public final class SelectionState {

    private static UUID selectedId;
    private static Plane pendingPlane;
    /** 是否正在等待服务端确认一次刚发起的圈选。 */
    private static boolean pendingCapture;
    /** 最近一次本地拾取到的面（用于确认服务端是否真的创建成功）。 */
    private static BlockPos lastPickPos;
    private static Direction lastPickFace;
    private static Vec3 lastPickHit;

    private SelectionState() {
    }

    @Nullable
    public static Plane plane() {
        if (pendingPlane != null) {
            return pendingPlane;
        }
        if (selectedId == null) return null;
        Plane p = PlaneCache.byId(selectedId);
        if (p == null) {
            selectedId = null;
            return null;
        }
        return p;
    }

    public static void select(@Nullable Plane plane) {
        pendingCapture = false;
        pendingPlane = null;
        selectedId = plane == null ? null : plane.id;
    }

    /** 标记「刚刚发出圈选请求，正在等待服务端同步」。 */
    public static void markPendingCapture() {
        pendingCapture = true;
    }

    /** 是否正在等待一次圈选的确认。 */
    public static boolean isPendingCapture() {
        return pendingCapture;
    }

    public static void selectId(@Nullable UUID id) {
        pendingPlane = null;
        selectedId = id;
    }

    /** 服务端刚创建、数据还没到齐时的临时引用。 */
    public static void setPending(@Nullable Plane plane) {
        pendingPlane = plane;
        selectedId = plane == null ? null : plane.id;
    }

    public static boolean hasSelection() {
        return plane() != null;
    }

    public static void clear() {
        selectedId = null;
        pendingPlane = null;
        pendingCapture = false;
    }

    public static void rememberPick(BlockPos pos, Direction face, Vec3 hit) {
        lastPickPos = pos;
        lastPickFace = face;
        lastPickHit = hit;
    }

    @Nullable
    public static BlockPos lastPickPos() {
        return lastPickPos;
    }

    @Nullable
    public static Direction lastPickFace() {
        return lastPickFace;
    }

    @Nullable
    public static Vec3 lastPickHit() {
        return lastPickHit;
    }

    /** 当前维度。 */
    @Nullable
    public static ResourceLocation dimension() {
        var level = net.minecraft.client.Minecraft.getInstance().level;
        return level == null ? null : level.dimension().location();
    }
}

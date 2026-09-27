package top.hmjmfabc.projector.client;

import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import top.hmjmfabc.projector.common.Plane;

/**
 * 客户端侧的权限提示判定。
 *
 * <p><b>服务端才是权威</b>——这里只用来决定界面上的按钮是否可用、
 * 以及提前给出「你没有权限」的提示，避免玩家点半天再被服务端拒绝。
 * 判定规则必须与 {@code server.PlanePermissions} 逐条对齐，否则会出现
 * 「界面说能用、服务端说不行」的错位。</p>
 *
 * <p>【用户要求 ⑥.1】单人存档与联机房主，<b>无论有无作弊权限都视作管理员</b>。
 * 服务端侧的判据是 {@code !player.server.isDedicatedServer()}，
 * 客户端与之等价的判据是 {@link Minecraft#hasSingleplayerServer()}
 * （单人存档与「对局域网开放」的房主都为 true，连第三方服务器时为 false）。</p>
 */
public final class ClientPermissions {

    /** 管理员判定的权限等级门槛（与 {@code PlanePermissions.ADMIN_LEVEL} 一致）。 */
    public static final int ADMIN_LEVEL = 2;

    private ClientPermissions() {
    }

    /** 是否是管理员（权限等级 ≥ 2，或本机就是房主）。 */
    public static boolean isAdmin() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        if (mc.player.getPermissionLevel() >= ADMIN_LEVEL) return true;
        return mc.hasSingleplayerServer();
    }

    /** 是否是某个平面的创建者。 */
    public static boolean isCreator(@Nullable Plane plane) {
        Minecraft mc = Minecraft.getInstance();
        return plane != null && mc.player != null
                && plane.creator != null && plane.creator.equals(mc.player.getUUID());
    }

    /** 能否管理平面（命名、转让、删除、开关保护、增删控件）。 */
    public static boolean canManage(@Nullable Plane plane) {
        return isAdmin() || isCreator(plane);
    }

    /** 能否修改平面上的控件（内容保护开启时只有管理员/创建者可以）。 */
    public static boolean canEditContent(@Nullable Plane plane) {
        if (plane == null) return false;
        return canManage(plane) || !plane.protectContent;
    }

    /**
     * 能否删除这个平面（hotfix-98）。
     *
     * <p>规则与服务端 {@code PlanePermissions.canDelete} 逐条对齐：
     * <b>默认人人都能删</b>；只有这个平面开了「删除保护」时，才要求等级 4。</p>
     */
    public static boolean canDelete(@Nullable Plane plane) {
        if (plane == null) return false;
        if (!plane.deleteProtect) return true;
        return canToggleDeleteProtection();
    }

    /**
     * 能否开关「删除保护」。只认等级 4（单人存档 / 局域网房主不受限）。
     */
    public static boolean canToggleDeleteProtection() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        if (mc.hasSingleplayerServer()) return true;
        return mc.player.getPermissionLevel() >= 4;
    }

    /** 能否向服务端添加图片/视频。单人存档不受限；服务器要求等级 4。 */
    public static boolean canAddMedia() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        if (mc.hasSingleplayerServer()) return true;
        return mc.player.getPermissionLevel() >= 4;
    }
}

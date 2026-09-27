package top.hmjmfabc.projector.server;

import net.minecraft.server.level.ServerPlayer;
import top.hmjmfabc.projector.common.Plane;

import java.util.UUID;

/**
 * 平面权限判定。所有判定都在服务端执行，客户端界面上的按钮可用性只是提示。
 *
 * <p>规则（与设计稿一致）：</p>
 * <ul>
 *   <li>权限等级 ≥ 2 的玩家是「管理员」，可以管理任意平面；</li>
 *   <li>平面创建者即使不是管理员，也可以管理自己的平面；</li>
 *   <li>「平面保护」只允许管理员开启，创建者只能用「平面误挖掘警告」；</li>
 *   <li>「内容保护」开启后，非管理员且非创建者不得增删/修改控件。</li>
 * </ul>
 */
public final class PlanePermissions {

    /** 管理员判定的权限等级门槛。 */
    public static final int ADMIN_LEVEL = 2;

    /**
     * 【hotfix-98】「删除保护」所需的最低权限等级。
     *
     * <p>只有这个等级的 OP 能开关它，也只有这个等级的人能删掉受保护的平面。</p>
     */
    public static final int DELETE_PROTECT_LEVEL = 4;

    private PlanePermissions() {
    }

    /**
     * 是否管理员。
     *
     * <p>【用户要求】单人存档与联机房主，**无论有无作弊权限都视作管理员**：
     * 这类玩家本来就能随手改存档／踢人，把他挡在权限门外只会让人困惑。
     * 判定依据是「服务端不是独立服务器」（即集成服务器：单人存档或联机房间）。</p>
     */
    public static boolean isAdmin(ServerPlayer player) {
        if (player == null) return false;
        if (player.hasPermissions(ADMIN_LEVEL)) return true;
        // 集成服务器 = 单人存档 / 联机房间的房主；独立服务器（第三方服务器）不适用
        return !player.server.isDedicatedServer();
    }

    public static boolean isCreator(Plane plane, UUID uuid) {
        return plane.creator != null && plane.creator.equals(uuid);
    }

    /** 能否管理平面（命名、转让、删除、开关保护、增删控件）。 */
    public static boolean canManage(Plane plane, ServerPlayer player) {
        if (player == null) return false;
        if (isAdmin(player)) return true;
        return isCreator(plane, player.getUUID());
    }

    /**
     * 能否修改平面上的控件（增删改）。
     *
     * <p>【rc-78】「管理员豁免」的门槛改成可配（{@code planes.contentProtectMinLevel}）：
     * 以前只要 {@code isAdmin} 为真就放行，而 {@code isAdmin} 的判定是「权限等级 ≥ 2」
     * —— 于是一个「内容保护」开着的平面，**任何等级 2 的 OP 都能照改不误**，
     * 玩家看到的就是「内容保护形同虚设」。默认仍是 2（与既有约定一致），
     * 想收紧就把它设为 4。</p>
     *
     * <p>另外：**豁免必须留痕**。等级够的人改别人的受保护平面时打一行日志
     * （含玩家/UUID/权限等级/平面），否则「为什么被改了」永远查不出来。
     * 拖动会高频调用，所以按 (玩家, 平面) 做了 5 秒节流。</p>
     */
    public static boolean canEditContent(Plane plane, ServerPlayer player) {
        if (player == null) return false;
        if (isCreator(plane, player.getUUID())) return true;
        if (!plane.protectContent) return true;
        // 内容保护开着：只有权限等级达到门槛的人能豁免
        int need = contentProtectMinLevel();
        if (player.hasPermissions(need) || isIntegratedHost(player)) {
            logBypass(plane, player, need);
            return true;
        }
        return false;
    }

    /** 【rc-78】内容保护豁免所需的最低权限等级（配置读不到时按 2 处理）。 */
    public static int contentProtectMinLevel() {
        try {
            return Math.max(1, Math.min(4,
                    top.hmjmfabc.projector.ProjectorConfig.INSTANCE.contentProtectMinLevel.get()));
        } catch (Throwable t) {
            return ADMIN_LEVEL;
        }
    }

    /** 集成服务器（单人存档 / 联机房间）的房主永远豁免：他就是这台机器的管理员。 */
    private static boolean isIntegratedHost(ServerPlayer player) {
        return player.server != null && !player.server.isDedicatedServer();
    }

    /** 玩家的权限等级（{@code ServerPlayer#getPermissionLevel} 是 protected，这里按公开 API 取）。 */
    private static int levelOf(ServerPlayer player) {
        try {
            return player.server.getProfilePermissions(player.getGameProfile());
        } catch (Throwable t) {
            for (int lv = 4; lv >= 1; lv--) {
                if (player.hasPermissions(lv)) return lv;
            }
            return 0;
        }
    }

    /** 【rc-78】豁免留痕（同一个玩家 + 平面 5 秒最多一行，避免拖动时刷屏）。 */
    private static final java.util.Map<String, Long> BYPASS_LOGGED = new java.util.concurrent.ConcurrentHashMap<>();

    private static void logBypass(Plane plane, ServerPlayer player, int need) {
        String key = player.getUUID() + "|" + plane.id;
        long now = System.currentTimeMillis();
        Long last = BYPASS_LOGGED.get(key);
        if (last != null && now - last < 5000L) return;
        BYPASS_LOGGED.put(key, now);
        top.hmjmfabc.projector.Projector.LOGGER.info(
                "[Projector][服务端][内容保护] 豁免 平面={} 创建者={} 玩家={} UUID={} 权限等级={} 门槛={} IP={}",
                plane.displayName(), plane.creatorName, player.getGameProfile().getName(),
                player.getUUID(), levelOf(player), need, player.getIpAddress());
    }

    /** 能否切换「平面保护」。只有管理员可以。 */
    public static boolean canToggleBlockProtection(ServerPlayer player) {
        return isAdmin(player);
    }

    /** 能否切换「平面误挖掘警告」。创建者与管理员都可以。 */
    public static boolean canToggleMiningWarning(Plane plane, ServerPlayer player) {
        return canManage(plane, player);
    }

    /** 能否切换「内容保护」。创建者与管理员都可以。 */
    public static boolean canToggleContentProtection(Plane plane, ServerPlayer player) {
        return canManage(plane, player);
    }

    /** 能否转让创建者。只有管理员或当前创建者可以。 */
    public static boolean canTransfer(Plane plane, ServerPlayer player) {
        return canManage(plane, player);
    }

    /**
     * 能否删除平面。
     *
     * <p>【hotfix-98 用户要求】<b>默认放开：任何人都能删除任何平面</b>
     * （以前只有管理员与创建者可以，玩家报「删不掉别人乱画的平面」）。
     * 唯一例外是这个平面开了<b>删除保护</b>（{@link Plane#deleteProtect}）——
     * 那时只有权限等级 ≥ {@link #DELETE_PROTECT_LEVEL} 的 OP 能删。</p>
     *
     * <p>注意：这与<b>内容保护</b>是两件事 —— 内容保护管「能不能改上面的控件」，
     * 删除保护只管「能不能把这个平面整个删掉」，互不影响。</p>
     */
    public static boolean canDelete(Plane plane, ServerPlayer player) {
        if (player == null) return false;
        if (plane == null || !plane.deleteProtect) return true;
        return opLevel(player) >= DELETE_PROTECT_LEVEL;
    }

    /**
     * 能否开关「删除保护」。<b>只认权限等级（4 级 OP）</b>，不看是不是创建者
     * —— 这个开关的用途就是挡住包括创建者在内的所有人乱删。
     */
    public static boolean canToggleDeleteProtection(ServerPlayer player) {
        if (player == null) return false;
        if (!player.server.isDedicatedServer()) return true;   // 单人/联机房主
        return opLevel(player) >= DELETE_PROTECT_LEVEL;
    }

    /** 玩家在独立服务器上的权限等级（读不到时按 hasPermissions 逐级退）。 */
    public static int opLevel(ServerPlayer player) {
        return levelOf(player);
    }

    /**
     * 能否添加媒体（上传图片/视频到服务端）。
     *
     * <p>【用户要求】<b>默认放开</b>：普通玩家也能上传，但各自受配额限制
     * （普通玩家 4 MB / 64 MB，管理员 16 MB / 256 MB，见 {@code media.maxImageBytes} 等）。
     * 想把上传锁回管理员，就在配置里打开 {@code media.onlyAdminCanUpload}。</p>
     *
     * <p><b>这里只判「谁能传」，大小配额由
     * {@code ServerNetHandler.imageLimitFor/videoLimitFor} 按身份另行判定</b>——
     * 两个判定必须成对出现，缺一个就会出现「谁都能传任意大小」。</p>
     */
    public static boolean canAddMedia(ServerPlayer player) {
        if (player == null) return false;
        if (!onlyAdminCanUpload()) return true;
        return isAdmin(player);
    }

    /** 【②】配置读取：读不到就按「放开」处理，绝不因为读不到配置把玩家挡在门外。 */
    private static boolean onlyAdminCanUpload() {
        try {
            return top.hmjmfabc.projector.ProjectorConfig.INSTANCE.onlyAdminCanUpload.get();
        } catch (Throwable t) {
            return false;
        }
    }
}

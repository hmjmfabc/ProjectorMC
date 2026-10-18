package top.hmjmfabc.projector.compat.neo.event.entity.player;

import net.minecraft.world.entity.player.Player;

/**
 * 【Fabric 移植层】{@code top.hmjmfabc.projector.compat.neo.event.entity.player.PlayerEvent} 的替身。
 *
 * <p>本模组用到的三个：登录、登出、跨维度（都要重新同步平面数据）。</p>
 */
public class PlayerEvent {

    private final Player entity;

    private PlayerEvent(Player entity) {
        this.entity = entity;
    }

    public Player getEntity() {
        return entity;
    }

    /** 玩家进入服务器（Fabric：{@code ServerPlayConnectionEvents.JOIN}）。 */
    public static final class PlayerLoggedInEvent extends PlayerEvent {
        public PlayerLoggedInEvent(Player entity) {
            super(entity);
        }
    }

    /** 玩家离开服务器（Fabric：{@code ServerPlayConnectionEvents.DISCONNECT}）。 */
    public static final class PlayerLoggedOutEvent extends PlayerEvent {
        public PlayerLoggedOutEvent(Player entity) {
            super(entity);
        }
    }

    /** 玩家跨维度（Fabric：{@code ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD}）。 */
    public static final class PlayerChangedDimensionEvent extends PlayerEvent {
        public PlayerChangedDimensionEvent(Player entity) {
            super(entity);
        }
    }
}

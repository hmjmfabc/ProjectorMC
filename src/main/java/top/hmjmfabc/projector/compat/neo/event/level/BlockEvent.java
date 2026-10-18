package top.hmjmfabc.projector.compat.neo.event.level;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.LevelAccessor;

/**
 * 【Fabric 移植层】{@code top.hmjmfabc.projector.compat.neo.event.level.BlockEvent} 的替身。
 *
 * <p>本模组只监听 {@link BreakEvent}（玩家要挖掉方块时），Fabric 侧对应
 * {@code PlayerBlockBreakEvents.BEFORE}。</p>
 */
public final class BlockEvent {

    private BlockEvent() {
    }

    /** 玩家即将破坏一个方块（可取消）。 */
    public static final class BreakEvent {
        private final LevelAccessor level;
        private final BlockPos pos;
        private final Player player;
        private boolean canceled;

        public BreakEvent(LevelAccessor level, BlockPos pos, Player player) {
            this.level = level;
            this.pos = pos;
            this.player = player;
        }

        public LevelAccessor getLevel() {
            return level;
        }

        public BlockPos getPos() {
            return pos;
        }

        public Player getPlayer() {
            return player;
        }

        public boolean isCanceled() {
            return canceled;
        }

        /** 取消 = 这个方块挖不动（平面保护 / 误挖掘警告走这条路）。 */
        public void setCanceled(boolean canceled) {
            this.canceled = canceled;
        }
    }
}

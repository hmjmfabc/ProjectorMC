package top.hmjmfabc.projector.compat.neo.client.event;

import net.minecraft.client.gui.screens.Screen;

/**
 * 【Fabric 移植层】{@code top.hmjmfabc.projector.compat.neo.client.event.ScreenEvent} 的替身。
 *
 * <p>本模组只用 {@link Opening}：<b>Esc 取消圈选</b>要在"暂停菜单即将打开"这一瞬间把屏幕拦下来
 * （{@code KeyMapping.consumeClick()} 拦不住暂停菜单，键位冲突上下文也只管战斗键）。</p>
 */
public final class ScreenEvent {

    private ScreenEvent() {
    }

    /** 某个界面即将打开（可取消 = 不打开）。 */
    public static final class Opening {
        private Screen newScreen;
        private boolean canceled;

        public Opening(Screen newScreen) {
            this.newScreen = newScreen;
        }

        public Screen getNewScreen() {
            return newScreen;
        }

        public void setNewScreen(Screen newScreen) {
            this.newScreen = newScreen;
        }

        public boolean isCanceled() {
            return canceled;
        }

        public void setCanceled(boolean canceled) {
            this.canceled = canceled;
        }
    }
}

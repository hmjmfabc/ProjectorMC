package top.hmjmfabc.projector.compat.neo.client.event;

/**
 * 【Fabric 移植层】{@code top.hmjmfabc.projector.compat.neo.client.event.InputEvent} 的替身。
 *
 * <p>四个子事件的语义（都由 Mixin 在<b>原版处理之前</b>触发）：</p>
 * <ul>
 *   <li>{@link Key}：键盘按下/松开/长按；</li>
 *   <li>{@link MouseButton.Pre}：鼠标按键（只观察，原模组不取消）；</li>
 *   <li>{@link MouseScrollingEvent}：滚轮（可取消 —— 转给网页后必须吃掉，
 *       否则会顺带切换快捷栏）；</li>
 *   <li>{@link InteractionKeyMappingTriggered}：原版的"攻击/使用/选取"三种交互
 *       （左右键点控件、点棋盘、点视频播放键都走这里）。</li>
 * </ul>
 */
public final class InputEvent {

    private InputEvent() {
    }

    /** 键盘事件。 */
    public static final class Key {
        private final int key;
        private final int scanCode;
        private final int action;
        private final int modifiers;
        private boolean canceled;

        public Key(int key, int scanCode, int action, int modifiers) {
            this.key = key;
            this.scanCode = scanCode;
            this.action = action;
            this.modifiers = modifiers;
        }

        public int getKey() {
            return key;
        }

        public int getScanCode() {
            return scanCode;
        }

        /** 0 = 松开，1 = 按下，2 = 长按重复。 */
        public int getAction() {
            return action;
        }

        public int getModifiers() {
            return modifiers;
        }

        public boolean isCanceled() {
            return canceled;
        }

        public void setCanceled(boolean canceled) {
            this.canceled = canceled;
        }
    }

    /** 鼠标按键事件。 */
    public static final class MouseButton {
        private MouseButton() {
        }

        /** 原版处理<b>之前</b>（可取消）。 */
        public static final class Pre {
            private final int button;
            private final int action;
            private boolean canceled;

            public Pre(int button, int action) {
                this.button = button;
                this.action = action;
            }

            /** GLFW 口径：0 = 左键，1 = 右键，2 = 中键。 */
            public int getButton() {
                return button;
            }

            /** 0 = 松开，1 = 按下。 */
            public int getAction() {
                return action;
            }

            public boolean isCanceled() {
                return canceled;
            }

            public void setCanceled(boolean canceled) {
                this.canceled = canceled;
            }
        }
    }

    /** 滚轮事件（可取消）。 */
    public static final class MouseScrollingEvent {
        private final double scrollDeltaX;
        private final double scrollDeltaY;
        private boolean canceled;

        public MouseScrollingEvent(double scrollDeltaX, double scrollDeltaY) {
            this.scrollDeltaX = scrollDeltaX;
            this.scrollDeltaY = scrollDeltaY;
        }

        public double getScrollDeltaX() {
            return scrollDeltaX;
        }

        public double getScrollDeltaY() {
            return scrollDeltaY;
        }

        public boolean isCanceled() {
            return canceled;
        }

        public void setCanceled(boolean canceled) {
            this.canceled = canceled;
        }
    }

    /** 原版把"按键映射"翻译成一次交互（攻击 / 使用物品 / 选取方块）时触发。 */
    public static final class InteractionKeyMappingTriggered {
        private final boolean attack;
        private final boolean useItem;
        private final boolean pickBlock;
        private boolean swingHand = true;
        private boolean canceled;

        public InteractionKeyMappingTriggered(boolean attack, boolean useItem, boolean pickBlock) {
            this.attack = attack;
            this.useItem = useItem;
            this.pickBlock = pickBlock;
        }

        public boolean isAttack() {
            return attack;
        }

        public boolean isUseItem() {
            return useItem;
        }

        public boolean isPickBlock() {
            return pickBlock;
        }

        public boolean isCanceled() {
            return canceled;
        }

        public void setCanceled(boolean canceled) {
            this.canceled = canceled;
        }

        /**
         * 是否让玩家挥手。
         *
         * <p>本移植层里它是<b>只读的</b>：原模组全部 {@code setSwingHand(false)} 都紧跟着
         * {@code setCanceled(true)}（逐处核对过 10 处调用），而"取消"已经让原版这一步整体不发生，
         * 于是挥手自然不会播放 ⇒ 不需要额外实现（写在这里是为了留存这个结论）。</p>
         */
        public boolean shouldSwingHand() {
            return swingHand;
        }

        public void setSwingHand(boolean swingHand) {
            this.swingHand = swingHand;
        }
    }
}

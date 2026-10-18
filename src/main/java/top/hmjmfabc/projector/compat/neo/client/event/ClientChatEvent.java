package top.hmjmfabc.projector.compat.neo.client.event;

/**
 * 【Fabric 移植层】{@code top.hmjmfabc.projector.compat.neo.client.event.ClientChatEvent} 的替身。
 *
 * <p>玩家在聊天框按回车、消息<b>还没发出去</b>时触发：可取消（消息不发送、也不进历史）。
 * 本模组用它把"刚打完的那段话"送进网页控件的输入框（I 键那条输入法主路）。</p>
 */
public final class ClientChatEvent {

    private String message;
    private boolean canceled;

    public ClientChatEvent(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public boolean isCanceled() {
        return canceled;
    }

    /** 取消 = 这条消息不发送、不进聊天历史。 */
    public void setCanceled(boolean canceled) {
        this.canceled = canceled;
    }
}

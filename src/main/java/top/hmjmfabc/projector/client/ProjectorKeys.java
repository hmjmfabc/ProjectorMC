package top.hmjmfabc.projector.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import top.hmjmfabc.projector.Projector;

/**
 * 按键绑定。
 *
 * <p>默认键位：<b>U</b> 圈选平面 / 打开对话框，<b>Esc</b> 取消圈选，
 * <b>O</b> 打开媒体文件夹提示，<b>I</b> 重播流程（选中平面时）/ 输入给网页（没选平面时）。<br>
 * ⚠ 语言文件里那句「取消圈选 (I)」曾经长期与实际不符（⑥.3 就改成 Esc 了），
 * 27.2.1-hotfix-144 一并订正 —— 玩家看到的按键说明必须与绑定一致。<br>
 * 全部通过原版 {@code KeyMapping} 注册，因此在 Android 触控启动器
 * （PojavLauncher / FoldCraft 等）里也能映射到屏幕按键；
 * 同时模组对鼠标/触摸右键做了兼容处理，不依赖任何鼠标专属 API。</p>
 */
public final class ProjectorKeys {

    public static final String CATEGORY = "key.categories.projector";

    public static final KeyMapping KEY_CAPTURE = new KeyMapping(
            "key.projector.capture",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_U,
            CATEGORY);

    public static final KeyMapping KEY_CANCEL = new KeyMapping(
            "key.projector.cancel",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            // 用户要求：取消圈选由 I 改为 Esc。
            // 注意 Esc 在原版里是「打开暂停菜单」，所以 ClientInputHandler 里必须
            // 只在自己「确实有东西可取消」时消费这次按键，并且要吞掉，否则会顺带弹出暂停菜单。
            InputConstants.KEY_ESCAPE,
            CATEGORY);

    public static final KeyMapping KEY_MEDIA_HELP = new KeyMapping(
            "key.projector.media_help",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_O,
            CATEGORY);

    /**
     * 【27.2.1-hotfix-144 用户要求】对着**已选中的平面**按 I = 重播流程；
     * 这个平面还没有流程时提示「尚未添加流程」。
     *
     * <p>I 以前是空的（⑥.3 把「取消圈选」从 I 改成了 Esc），所以这里正好用它。
     * 注意与网页控件的 I（「输入给网页」）不冲突：那条只在准心对着网页控件时生效，
     * 而且**已选中平面**时优先走重播流程。</p>
     */
    public static final KeyMapping KEY_SEQ_REPLAY = new KeyMapping(
            "key.projector.seq_replay",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_I,
            CATEGORY);

    private ProjectorKeys() {
    }

    public static void register(net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent event) {
        event.register(KEY_CAPTURE);
        event.register(KEY_CANCEL);
        event.register(KEY_MEDIA_HELP);
        event.register(KEY_SEQ_REPLAY);
        Projector.LOGGER.debug("[Projector] 按键已注册: U / Esc / O / I（重播流程）");
    }
}

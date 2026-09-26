package top.hmjmfabc.projector.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import top.hmjmfabc.projector.Projector;

/**
 * 按键绑定。
 *
 * <p>默认键位：<b>U</b> 圈选平面 / 打开对话框，<b>I</b> 取消圈选，
 * <b>O</b> 打开媒体文件夹提示。<br>
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

    private ProjectorKeys() {
    }

    public static void register(net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent event) {
        event.register(KEY_CAPTURE);
        event.register(KEY_CANCEL);
        event.register(KEY_MEDIA_HELP);
        Projector.LOGGER.debug("[Projector] 按键已注册: U / I / O");
    }
}

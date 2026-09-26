package top.hmjmfabc.projector.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import top.hmjmfabc.projector.client.font.FontManager;
import top.hmjmfabc.projector.client.media.LocalMedia;
import top.hmjmfabc.projector.common.widget.Fonts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 字体选择器。
 *
 * <p>列出所有可用字体：内置的 Minecraft AE 与 Caviar Dreams，以及玩家放在
 * {@code .minecraft/projector/fonts/} 里的自定义 {@code .ttf/.otf}。
 * 选中后即时生效，游戏内文字会立刻用新字体重新排版。</p>
 */
public class FontSelectorScreen extends ProjectorScreen {

    /** 默认与内置字体一览（用于界面展示与「恢复默认」按钮）。 */
    private static final String[][] BUILTIN = {
            {Fonts.MINECRAFT_AE, "Minecraft AE\uff08\u9ed8\u8ba4\uff09"},
            {Fonts.CAVIAR_DREAMS, "Caviar Dreams\uff08\u767e\u5206\u6bd4\u63a7\u4ef6\u4e13\u7528\uff09"},
    };

    private final Screen parent;
    private final Consumer<String> onPick;
    private final String current;

    @Nullable
    private ScrollList<String> list;
    private String hoveredId;
    /** 【①】上传结果提示。 */
    private String previewMessage = "";

    public FontSelectorScreen(Screen parent, String current, Consumer<String> onPick) {
        super(Component.literal("\u9009\u62e9\u5b57\u4f53"));
        this.parent = parent;
        this.current = current;
        this.onPick = onPick;
    }

    @Override
    protected void init() {
        FontManager.refreshCustom();
        List<String> ids = new ArrayList<>(FontManager.availableIds());

        int pad = 14;
        int listW = Math.min(300, this.width / 3);
        int listH = this.height - pad * 2 - 70;

        // 默认选中「当前字体」：否则没点过任何一行时按「确定」会毫无反应
        hoveredId = current;
        ScrollList<String> l = new ScrollList<>(pad, pad + 22, listW, listH,
                id -> FontManager.displayName(id) + (id.equals(current) ? "  \u2714" : "")
                        + (serverOk(id) ? "" : "  \u2716\u670d\u52a1\u5668\u672a\u5b89\u88c5"),
                id -> id,
                (id, idx) -> hoveredId = id);
        l.setItems(ids);
        this.list = addRenderableWidget(l);

        button("\u786e\u5b9a", pad, this.height - pad - 24, listW / 2 - 4, 20, b -> pick(hoveredId));
        button("\u8fd4\u56de", pad + listW / 2 + 4, this.height - pad - 24, listW / 2 - 4, 20,
                b -> Minecraft.getInstance().setScreen(parent));
        button("\u91cd\u65b0\u626b\u63cf\u5b57\u4f53\u76ee\u5f55", pad, this.height - pad - 48, listW, 20, b -> {
            FontManager.refreshCustom();
            rebuildWidgets();
        });

        // 右侧说明
        int bx = pad + listW + 12;
        int bw = Math.min(230, this.width - bx - pad);
        button("\u6253\u5f00\u5b57\u4f53\u76ee\u5f55", bx, pad + 22, bw, 20, b -> {
            LocalMedia.ensureDirectories();
        });
        // 【①】管理员：把选中的字体上传到服务端
        final String sel = hoveredId;
        Button up = button("\u4e0a\u4f20\u9009\u4e2d\u5b57\u4f53\u5230\u670d\u52a1\u5668", bx, pad + 46, bw, 20,
                b -> uploadSelected());
        boolean canUp = !Minecraft.getInstance().hasSingleplayerServer()
                && Minecraft.getInstance().player != null
                && Minecraft.getInstance().player.getPermissionLevel() >= 4;
        up.active = canUp;
    }

    /** 【①】服务端是否已安装该字体（内置字体永远算已安装）。 */
    private static boolean serverOk(String id) {
        if (id == null) return true;
        if (Fonts.MINECRAFT_AE.equals(id) || Fonts.CAVIAR_DREAMS.equals(id)) return true;
        if (!top.hmjmfabc.projector.client.ClientServerInfo.received()) return true;
        return top.hmjmfabc.projector.client.ClientServerInfo.fontAvailable(id);
    }

    /**
     * 【①】管理员把本地字体上传到服务端。
     *
     * <p>上传后服务端会把文件落盘并<b>回读重新核验哈希</b>，再回一条
     * {@code FontStored} 通知。这里只负责把文件字节发出去。</p>
     */
    private void uploadSelected() {
        String id = hoveredId;
        if (id == null) return;
        if (id.equals(Fonts.MINECRAFT_AE) || id.equals(Fonts.CAVIAR_DREAMS)) {
            previewMessage = "\u5185\u7f6e\u5b57\u4f53\u65e0\u9700\u4e0a\u4f20";
            return;
        }
        // 找到本地文件：字体 ID 形如 custom:<名字>
        String base = id;
        int colon = base.indexOf(':');
        if (colon >= 0) base = base.substring(colon + 1);
        java.nio.file.Path found = null;
        try (var stream = Files.list(LocalMedia.fontDir())) {
            for (java.nio.file.Path p : (Iterable<java.nio.file.Path>) stream::iterator) {
                String name = p.getFileName().toString();
                int dot = name.lastIndexOf('.');
                String stem = dot > 0 ? name.substring(0, dot) : name;
                if (stem.equalsIgnoreCase(base)) {
                    found = p;
                    break;
                }
            }
        } catch (IOException ignored) {
        }
        if (found == null) {
            previewMessage = "\u672c\u5730\u627e\u4e0d\u5230\u8be5\u5b57\u4f53\u6587\u4ef6";
            return;
        }
        try {
            byte[] data = Files.readAllBytes(found);
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new top.hmjmfabc.projector.network.Payloads.FontUpload(
                            id, found.getFileName().toString(), data));
            previewMessage = "\u5df2\u53d1\u9001\u4e0a\u4f20\uff08" + data.length + " \u5b57\u8282\uff09\uff0c\u7b49\u5f85\u670d\u52a1\u7aef\u6838\u9a8c";
        } catch (IOException ex) {
            previewMessage = "\u8bfb\u53d6\u5b57\u4f53\u5931\u8d25";
        }
    }

    private void pick(@Nullable String id) {
        if (id == null) return;
        // 【rc-78】硬拦：服务端没装的字体，普通玩家**根本选不了**。
        // 以前只在列表后面标一句「✖服务端未安装」，但「确定」照样生效 ⇒
        // 本地立刻用上了该字体，只有保存会被服务端拒（而且客户端不回滚），
        // 玩家看到的现象就是「禁止普通玩家使用未添加字体形同虚设」。
        if (!serverOk(id) && !isAdminHere()) {
            previewMessage = "\u8be5\u5b57\u4f53\u670d\u52a1\u7aef\u672a\u5b89\u88c5\uff0c"
                    + "\u666e\u901a\u73a9\u5bb6\u4e0d\u80fd\u4f7f\u7528"
                    + "\uff08\u8bf7\u7ba1\u7406\u5458\u5148\u300c\u4e0a\u4f20\u9009\u4e2d\u5b57\u4f53\u5230\u670d\u52a1\u5668\u300d\uff09";
            top.hmjmfabc.projector.Projector.LOGGER.warn(
                    "[Projector][客户端][字体] 禁止选择未安装的字体 字体={} 原因=服务端字体清单里没有这个 ID"
                            + "（普通玩家不可用；管理员可先上传到服务端再用）", id);
            return;
        }
        onPick.accept(id);
        Minecraft.getInstance().setScreen(parent);
    }

    /** 本端是否以管理员身份操作：单人/联机房间视为管理员，服务器上要求权限等级 4。 */
    private static boolean isAdminHere() {
        var player = Minecraft.getInstance().player;
        if (player == null) return false;
        if (Minecraft.getInstance().hasSingleplayerServer()) return true;
        return player.getPermissionLevel() >= 4;
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        super.render(gfx, mouseX, mouseY, partialTick);
        int pad = 14;
        labelShadow(gfx, "\u9009\u62e9\u5b57\u4f53", pad, pad, TEXT_ACCENT);
        int bx = pad + Math.min(300, this.width / 3) + 12;
        int y = pad + 50;
        label(gfx, "\u5b57\u4f53\u76ee\u5f55\uff1a", bx, y, TEXT_DIM);
        y += 12;
        label(gfx, LocalMedia.fontDir().toString(), bx, y, TEXT_NORMAL);
        y += 20;
        label(gfx, "\u628a .ttf / .otf \u6587\u4ef6\u4e22\u8fdb\u8be5\u76ee\u5f55\uff0c", bx, y, TEXT_DIM);
        y += 12;
        label(gfx, "\u7136\u540e\u70b9\u4e0a\u9762\u7684\u300c\u91cd\u65b0\u626b\u63cf\u300d\u5373\u53ef\u4f7f\u7528\u3002", bx, y, TEXT_DIM);
        y += 20;
        label(gfx, "\u5df2\u8f7d\u5165\uff1a" + FontManager.count() + " \u4e2a\u5b57\u4f53", bx, y, TEXT_GREEN);
        // 【①】联机字体核验状态
        y += 20;
        if (Minecraft.getInstance().hasSingleplayerServer()) {
            label(gfx, "\u5355\u4eba\u5b58\u6863\uff1a\u4e0d\u6821\u9a8c\u670d\u52a1\u7aef\u5b57\u4f53", bx, y, TEXT_DIM);
        } else if (!top.hmjmfabc.projector.client.ClientServerInfo.received()) {
            label(gfx, "\u8fd8\u6ca1\u6536\u5230\u670d\u52a1\u7aef\u5b57\u4f53\u6e05\u5355", bx, y, TEXT_DIM);
        } else {
            label(gfx, "\u670d\u52a1\u7aef\u5df2\u88c5\uff1a"
                    + top.hmjmfabc.projector.client.ClientServerInfo.fonts().size() + " \u4e2a\u5b57\u4f53",
                    bx, y, TEXT_ACCENT);
            y += 12;
            label(gfx, "\u6807 \u2716 \u7684\u5b57\u4f53\u670d\u52a1\u7aef\u6ca1\u6709\uff0c\u666e\u901a\u73a9\u5bb6\u4e0d\u80fd\u7528", bx, y, 0xFFFFAA55);
            y += 12;
            label(gfx, "\u7ba1\u7406\u5458\u53ef\u70b9\u4e0a\u65b9\u6309\u94ae\u4e0a\u4f20\uff08\u4f1a\u81ea\u52a8\u6838\u9a8c\u54c8\u5e0c\uff09", bx, y, TEXT_DIM);
        }
        if (hoveredId != null) {
            y += 20;
            label(gfx, "\u5f53\u524d\u9009\u4e2d\uff1a" + FontManager.displayName(hoveredId), bx, y, TEXT_ACCENT);
        }
        if (!previewMessage.isEmpty()) {
            y += 12;
            label(gfx, previewMessage, bx, y, TEXT_NORMAL);
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    /** 便捷：探测一个字体文件是否可以载入（供界面提示用）。 */
    public static boolean testFont(Path path) {
        try {
            if (!Files.isRegularFile(path)) return false;
            byte[] data = Files.readAllBytes(path);
            if (data.length < 16) return false;
            var font = FontManager.loadTransient("test", "test", data);
            font.close();
            return true;
        } catch (IOException ex) {
            return false;
        }
    }
}

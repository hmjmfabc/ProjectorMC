package top.hmjmfabc.projector.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import top.hmjmfabc.projector.common.widget.ItemWidget;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 【27.2.2】展示框的**物品检索界面**（用户要求：支持按名称、ID、模组 ID 检索，含模组物品）。
 *
 * <h2>检索规则（三条，任一命中即列出）</h2>
 * <ol>
 *   <li><b>名称</b>：物品的显示名（本地化后的名字，中英文都试）；</li>
 *   <li><b>ID</b>：注册名，形如 {@code minecraft:diamond} 或只写 {@code diamond}；</li>
 *   <li><b>模组 ID</b>：命名空间，形如 {@code minecraft}、{@code watermedia}（列出该模组的所有物品）。</li>
 * </ol>
 * <p>空关键字列出**全部**物品（按注册名排序），上限 {@link #MAX_LIST} 条，避免一次塞几千行。</p>
 *
 * <p>写成静态纯函数（{@link #match}）是为了能用脚本验证：喂一批候选，验命中与排序，
 * 不需要真的打开界面。</p>
 */
public class ItemPickerScreen extends ProjectorScreen {

    /** 列表最多显示多少条（超出时提示继续打字缩小范围）。 */
    public static final int MAX_LIST = 400;

    private final ProjectorScreen parent;
    private final ItemWidget target;
    private final Consumer<ItemStack> onPick;

    private ScrollList<ItemStack> list;
    /** 当前选中的物品（ScrollList 只回调 onPick，选中态要自己记）。 */
    private ItemStack selected = ItemStack.EMPTY;
    private String query = "";
    private String status = "";
    /** 检索结果缓存（关键字变了才重算）。 */
    private List<ItemStack> results = List.of();

    public ItemPickerScreen(ProjectorScreen parent, ItemWidget target, Consumer<ItemStack> onPick) {
        super(Component.literal("\u9009\u62e9\u7269\u54c1"));
        this.parent = parent;
        this.target = target;
        this.onPick = onPick;
    }

    // ------------------------------------------------------------------
    // 检索（纯函数，便于脚本验证）
    // ------------------------------------------------------------------

    /**
     * 一件物品是否命中关键字。
     *
     * @param query 关键字（已 trim + 小写；空 = 全命中）
     * @param id    注册名（{@code namespace:path}）
     * @param name  显示名（本地化）
     */
    public static boolean match(String query, String id, String name) {
        if (query == null || query.isEmpty()) {
            return true;
        }
        if (id == null) {
            id = "";
        }
        if (name == null) {
            name = "";
        }
        String q = query.toLowerCase(Locale.ROOT);
        String idl = id.toLowerCase(Locale.ROOT);
        String nl = name.toLowerCase(Locale.ROOT);
        // ① 名称命中（本地化名）
        if (!nl.isEmpty() && nl.contains(q)) {
            return true;
        }
        // ② 注册名命中（含只写路径部分的情形：diamond 命中 minecraft:diamond）
        if (idl.contains(q)) {
            return true;
        }
        // ③ 模组 ID 命中：命名空间完全等于关键字，或者以「关键字:」开头
        int colon = idl.indexOf(':');
        if (colon > 0) {
            String ns = idl.substring(0, colon);
            if (ns.equals(q) || ns.startsWith(q)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检索物品表。
     *
     * @param query 关键字（大小写不敏感）
     * @param limit 最多返回多少条
     * @return 物品列表（按注册名排序，稳定）
     */
    public static List<ItemStack> search(String query, int limit) {
        String q = query == null ? "" : query.trim();
        List<ItemStack> out = new ArrayList<>();
        try {
            List<ResourceLocation> keys = new ArrayList<>(BuiltInRegistries.ITEM.keySet());
            keys.sort(java.util.Comparator.comparing(ResourceLocation::toString));
            for (ResourceLocation id : keys) {
                Item item = BuiltInRegistries.ITEM.get(id);
                if (item == null || item == Items.AIR) {
                    continue;
                }
                ItemStack stack = new ItemStack(item);
                String name;
                try {
                    name = stack.getHoverName().getString();
                } catch (Throwable t) {
                    name = "";
                }
                if (!match(q, id.toString(), name)) {
                    continue;
                }
                out.add(stack);
                if (out.size() >= limit) {
                    break;
                }
            }
        } catch (Throwable t) {
            // 注册表还没就绪（极早期）：返回空列表，界面照常显示
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 界面
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        int pad = 10;
        int listW = Math.max(120, this.width / 2);
        int listH = this.height - pad * 2 - 34;
        ScrollList<ItemStack> l = new ScrollList<>(pad, pad + 22, listW, listH,
                s -> s.getHoverName().getString(),
                s -> {
                    ResourceLocation id = BuiltInRegistries.ITEM.getKey(s.getItem());
                    return id == null ? "?" : id.toString();
                },
                (s, idx) -> {
                    selected = s;
                    ResourceLocation id = BuiltInRegistries.ITEM.getKey(s.getItem());
                    status = (id == null ? "" : id.toString())
                            + "  （" + s.getHoverName().getString() + "）";
                });
        l.setItems(results);
        this.list = addRenderableWidget(l);

        int bx = pad + listW + 8;
        int bw = Math.max(70, this.width - bx - pad);
        editBox(bx, pad + 22, bw, 18, query, 64, s -> {
            query = s;
            refresh();
        });
        button("\u786e\u8ba4", bx, this.height - pad - 24, bw, 20, b -> confirm());
        button("\u6e05\u7a7a\u5173\u952e\u5b57", bx, pad + 44, bw, 18, b -> {
            query = "";
            refresh();
            // 重建界面：输入框内容不是可写回的（EditBox 的文本只能靠重建刷新）
            Minecraft.getInstance().setScreen(new ItemPickerScreen(parent, target, onPick));
        });
        button("\u8fd4\u56de", bx, this.height - pad - 48, bw, 20,
                b -> Minecraft.getInstance().setScreen(parent));
        refresh();
    }

    /** 重算检索结果并喂给列表。 */
    private void refresh() {
        results = search(query, MAX_LIST);
        if (list != null) {
            list.setItems(results);
        }
    }

    private void confirm() {
        ItemStack sel = selected;
        if (sel == null || sel.isEmpty()) {
            status = "\u5148\u5728\u5de6\u8fb9\u9009\u4e00\u4e2a\u7269\u54c1";
            return;
        }
        if (onPick != null) {
            onPick.accept(sel);
        }
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        super.render(gfx, mouseX, mouseY, partialTick);
        label(gfx, "\u9009\u62e9\u7269\u54c1\uff08\u540d\u79f0 / ID / \u6a21\u7ec4 ID\uff09",
                10, 8, TEXT_ACCENT);
        String shown = "\u5171 " + results.size() + " \u9879"
                + (results.size() >= MAX_LIST ? "\uff08\u5df2\u622a\u65ad\uff0c\u7ee7\u7eed\u6253\u5b57\u7f29\u5c0f\u8303\u56f4\uff09" : "");
        label(gfx, shown, 10, this.height - 18, TEXT_DIM);
        if (!status.isEmpty()) {
            label(gfx, status, 10, this.height - 30, 0xFFFFDD55);
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}

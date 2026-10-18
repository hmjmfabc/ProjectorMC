package top.hmjmfabc.projector.common.widget;

import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.List;

/**
 * 【27.2.2】<b>展示框</b>（物品展示框的「二向化」版本，用户要求）。
 *
 * <h2>它是什么</h2>
 * 把一个**物品**投影到平面上：控件给出一圈可调色 / 可旋转 / 可缩放的**轮廓**，
 * 玩家在世界里**右键**这个控件就把「选定的物品」（新增控件时挑的那件）放进去 ——
 * 放进去之后轮廓内显示该物品的完整贴图，**贴图大小与轮廓完全一致**；
 * **左键**点它则把物品取回来。
 *
 * <h2>状态</h2>
 * 只有两种：{@link #placed} 为 false（空框，只画轮廓）或 true（框内有物品）。
 * 「谁放的」由世界里的玩家自己决定（放进去的是玩家手里的那份的**投影**，
 * 不消耗背包 —— 投影仪是屏幕，不是箱子），所以这里只记状态本身。
 *
 * <h2>指令绑定（用户要求：<b>只有管理员能设置</b>）</h2>
 * {@link #placeCommand} / {@link #takeCommand} 是两条**放进/取出时执行**的指令，
 * 默认空 => 不执行。设置入口在编辑器里，但**权威判定在服务端**
 * （见 {@code ServerNetHandler} 的 {@code canManage} 判定与拒绝留痕）——
 * 客户端只做按钮灰显，不允许靠改客户端越权。
 *
 * <p>指令里的占位符（服务端执行前替换）：{@code %player%} 玩家名、
 * {@code %item%} 物品 id、{@code %x% %y% %z%} 控件所在平面的方块坐标。</p>
 */
public class ItemWidget extends Widget {

    /** 允许放进展示框的物品（只按注册名认，服务端权威）。 */
    public String itemId = "";
    /** 显示名（界面用；取不到时回落成 itemId）。 */
    public String itemName = "";

    /** 轮廓颜色（ARGB；默认白色，与音乐控件的白框同一套观感）。 */
    public int outlineColor = 0xFFFFFFFF;
    /**
     * 【已停用，只为兼容旧存档保留】轮廓加粗。
     *
     * <p>【27.2.2-159 用户口径】轮廓粗细固定为「物品贴图的一个像素」——
     * 用户说「默认边框实在太厚了」，而老存档里这个值还是旧语义（画布单位、旧默认 1.2），
     * 会在一个像素之外再往外扩 1.2 ⇒ 实际有两三个像素粗。现在渲染**完全不读它**，
     * 字段与 NBT 键继续保留（读旧存档不丢数据、也不影响新存档）。</p>
     */
    public double outlineWidth = 0.0;

    /** 轮廓内的缩放倍率（0.2~1.0，1.0 = 贴图铺满轮廓，用户要求「大小与轮廓完全一致」）。 */
    public double iconScale = 1.0;
    /** 轮廓的旋转角（度，逆时针）—— 与控件自身的 {@link #rot} 叠加。 */
    public double iconRot;

    /** 框里现在有没有东西。 */
    public boolean placed;
    /** 最后一次放入的玩家名（界面/诊断用）。 */
    public String lastPlayer = "";
    /** 最后一次放入的时间（游戏刻，用于动画与诊断）。 */
    public long lastChangeTick;

    /** 放入时执行的指令（空 = 不执行；**只有管理员能改**）。 */
    public String placeCommand = "";
    /** 取出时执行的指令（同上）。 */
    public String takeCommand = "";

    @Override
    public int kind() {
        return KIND_ITEM;
    }

    @Override
    public String label() {
        String n = itemName == null || itemName.isBlank() ? itemId : itemName;
        if (n == null || n.isEmpty()) {
            n = "\u5c55\u793a\u6846";
        }
        return n + (placed ? "\uff08\u5df2\u653e\u5165\uff09" : "\uff08\u7a7a\uff09");
    }

    /** 有没有选定物品（没选定就不能放，也就没有轮廓内的贴图）。 */
    public boolean hasItem() {
        return itemId != null && !itemId.isBlank();
    }

    // 【27.2.4-200】这里原本有 canPlace/canTake 两个「闸门」，注释还写着
    // 「服务端会查玩家背包」——但它们**全项目零调用**（服务端 itemPlace/itemTake 只判
    // hasItem()/placed），属于假闸门：让人以为有校验，实际没有任何校验。
    // 现在删掉：真正的判定只有服务端 onWidgetAction 里的状态判断；
    // 如果以后要加「必须持有该物品」，请在 ServerNetHandler 里查背包并留日志，别再写成没人调的方法。

    /** 放入（返回是否真的发生了变化）。 */
    public boolean place(long gameTick, String playerName) {
        if (placed) {
            return false;
        }
        placed = true;
        lastPlayer = playerName == null ? "" : playerName;
        lastChangeTick = gameTick;
        return true;
    }

    /** 取出（返回是否真的发生了变化）。 */
    public boolean take(long gameTick, String playerName) {
        if (!placed) {
            return false;
        }
        placed = false;
        lastPlayer = playerName == null ? "" : playerName;
        lastChangeTick = gameTick;
        return true;
    }

    /**
     * 指令里占位符的替换（**执行前**统一走这里，保证两条指令行为一致）。
     *
     * @param raw    原始指令（可以带或不带前导斜杠）
     * @param player 玩家名
     * @param x/y/z  平面锚点方块坐标
     * @return 可执行形态（已去掉前导斜杠；空指令返回空串）
     */
    public String renderCommand(String raw, String player, int x, int y, int z) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String s = raw.trim();
        if (s.startsWith("/")) {
            s = s.substring(1);
        }
        if (player != null) {
            s = s.replace("%player%", player);
        }
        s = s.replace("%item%", itemId == null ? "" : itemId);
        s = s.replace("%x%", Integer.toString(x));
        s = s.replace("%y%", Integer.toString(y));
        s = s.replace("%z%", Integer.toString(z));
        return s;
    }

    /** 诊断用：这一版有哪些可变的外观参数（内容变了才打日志）。 */
    public List<String> describeStyle() {
        List<String> out = new ArrayList<>(4);
        out.add("outline=" + Integer.toHexString(outlineColor));
        out.add("w=" + outlineWidth);
        out.add("scale=" + iconScale);
        out.add("rot=" + iconRot);
        return out;
    }

    @Override
    protected void saveExtra(CompoundTag t) {
        t.putString("item", itemId == null ? "" : itemId);
        t.putString("itemName", itemName == null ? "" : itemName);
        t.putInt("outline", outlineColor);
        t.putDouble("outlineW", outlineWidth);
        t.putDouble("iconScale", iconScale);
        t.putDouble("iconRot", iconRot);
        t.putBoolean("placed", placed);
        t.putString("lastPlayer", lastPlayer == null ? "" : lastPlayer);
        t.putLong("lastTick", lastChangeTick);
        t.putString("cmdPlace", placeCommand == null ? "" : placeCommand);
        t.putString("cmdTake", takeCommand == null ? "" : takeCommand);
    }

    @Override
    public void loadExtra(CompoundTag t) {
        itemId = t.getString("item");
        itemName = t.contains("itemName") ? t.getString("itemName") : "";
        outlineColor = t.contains("outline") ? t.getInt("outline") : 0xFFFFFFFF;
        outlineWidth = t.contains("outlineW") ? t.getDouble("outlineW") : 0.0;
        iconScale = t.contains("iconScale") ? t.getDouble("iconScale") : 1.0;
        iconRot = t.contains("iconRot") ? t.getDouble("iconRot") : 0;
        placed = t.getBoolean("placed");
        lastPlayer = t.contains("lastPlayer") ? t.getString("lastPlayer") : "";
        lastChangeTick = t.getLong("lastTick");
        placeCommand = t.contains("cmdPlace") ? t.getString("cmdPlace") : "";
        takeCommand = t.contains("cmdTake") ? t.getString("cmdTake") : "";
    }
}

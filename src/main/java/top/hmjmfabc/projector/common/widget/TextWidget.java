package top.hmjmfabc.projector.common.widget;

import net.minecraft.nbt.CompoundTag;

/**
 * 文本控件。
 *
 * <p>{@code text} 支持 Minecraft 格式化代码，但 {@code §} 一律写成 {@code &}：
 * {@code &4} 为深红、{@code &l} 为粗体……需要显示真正的 {@code &} 时写两个，
 * 即 {@code &&} 渲染为 {@code &}。</p>
 */
public class TextWidget extends Widget {

    public String text = "";
    public String fontId = Fonts.MINECRAFT_AE;
    /** 字号（画布单位，16 = 一格高）。默认 16 ≈ 一个字高正好一格。 */
    public double fontSize = 16;
    /** 自动换行宽度（画布单位，&lt;=0 表示不换行，按 \n 断行）。 */
    public double wrapWidth = -1;
    /** 行距倍数。 */
    public double lineSpacing = 1.15;
    /** 水平对齐：0 左 1 中 2 右。 */
    public int align = 0;
    /** 文本阴影。 */
    public boolean shadow = true;
    /**
     * 是否由玩家手动指定了尺寸。
     *
     * <p>为 false（默认）时，文本框大小随内容与字号自动调整；
     * 一旦玩家在缩略图里拖过右下角缩放，就置为 true，之后不再自动改尺寸。</p>
     */
    public boolean manualSize;

    // ------------------------------------------------------------------
    // 【27.2-pre-136】「打字」动画（参考剪映的文字打字效果）
    // ------------------------------------------------------------------

    /** 是否开启「打字」动画：文字一个一个字冒出来。 */
    public boolean typewriter;
    /** 打字速度（每秒多少个字）。 */
    public double typeSpeed = 6.0;
    /** 打完一遍之后是否从头再来（关掉就停在打完的样子）。 */
    public boolean typeLoop = true;
    /** 打完一整遍之后停多久再重来（秒）。 */
    public double typeHold = 1.5;

    /**
     * 这一刻应该显示到第几个字（打字动画）。
     *
     * @param gameTime 游戏时刻（tick，20 = 1 秒）；与流程动画/音乐控件用的是同一个时钟
     * @return 可见字数；关掉打字动画时返回 {@link Integer#MAX_VALUE}（表示「全部显示」）
     */
    public int typedChars(long gameTime) {
        if (!typewriter) return Integer.MAX_VALUE;
        int total = top.hmjmfabc.projector.common.text.TextLayout.visibleCount(text);
        if (total <= 0) return Integer.MAX_VALUE;
        double speed = typeSpeed <= 0.05 ? 6.0 : typeSpeed;
        double elapsed = gameTime / 20.0;
        double n;
        if (typeLoop) {
            double hold = Math.max(0.0, typeHold);
            double cycle = total / speed + hold;
            double phase = cycle <= 0.01 ? 0 : elapsed % cycle;
            n = Math.floor(phase * speed);
        } else {
            n = Math.floor(elapsed * speed);
        }
        if (n <= 0) return 0;
        return n >= total ? Integer.MAX_VALUE : (int) n;
    }

    @Override
    public int kind() {
        return KIND_TEXT;
    }

    @Override
    public String label() {
        String flat = top.hmjmfabc.projector.common.text.FormatCodes.strip(text);
        if (flat.length() > 24) flat = flat.substring(0, 24) + "\u2026";
        return flat.isEmpty() ? "\u7a7a\u6587\u672c" : flat;
    }

    /** 【①】本控件引用的字体（供联机字体核验使用）。 */
    @Override
    public java.util.List<String> fontIds() {
        return java.util.List.of(fontId == null ? "" : fontId);
    }

    @Override
    protected void saveExtra(CompoundTag t) {
        t.putString("text", text);
        t.putString("font", fontId);
        t.putDouble("fs", fontSize);
        t.putDouble("wrap", wrapWidth);
        t.putDouble("ls", lineSpacing);
        t.putInt("align", align);
        t.putBoolean("shadow", shadow);
        t.putBoolean("manualSize", manualSize);
        t.putBoolean("typewriter", typewriter);
        t.putDouble("typeSpeed", typeSpeed);
        t.putBoolean("typeLoop", typeLoop);
        t.putDouble("typeHold", typeHold);
    }

    @Override
    public void loadExtra(CompoundTag t) {
        text = t.getString("text");
        fontId = t.contains("font") ? t.getString("font") : Fonts.MINECRAFT_AE;
        fontSize = t.contains("fs") ? t.getDouble("fs") : 16;
        wrapWidth = t.contains("wrap") ? t.getDouble("wrap") : -1;
        lineSpacing = t.contains("ls") ? t.getDouble("ls") : 1.15;
        align = t.getInt("align");
        shadow = !t.contains("shadow") || t.getBoolean("shadow");
        manualSize = t.getBoolean("manualSize");
        // 旧存档没有这几个键 -> 默认关闭打字动画（表现与以前完全一致）
        typewriter = t.contains("typewriter") && t.getBoolean("typewriter");
        typeSpeed = t.contains("typeSpeed") ? t.getDouble("typeSpeed") : 6.0;
        typeLoop = !t.contains("typeLoop") || t.getBoolean("typeLoop");
        typeHold = t.contains("typeHold") ? t.getDouble("typeHold") : 1.5;
    }
}

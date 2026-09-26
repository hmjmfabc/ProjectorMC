package top.hmjmfabc.projector.common;

import net.minecraft.nbt.CompoundTag;

/**
 * 平面上的一个「方块面」单元。
 *
 * <p>x0/y0/x1/y1 均为画布坐标（1 方块 = 16 单位），x1/y1 为开区间上界。
 * depth 是该方块表面相对「锚点面所在平面」沿外法线的位移（画布单位），
 * 用于让不完整方块（楼梯台阶、铁砧顶面）上的内容贴合真实表面。</p>
 */
public final class PlaneBlock {

    /** 画布坐标下的方块面矩形。 */
    public double x0, y0, x1, y1;
    /** 表面沿外法线的位移（画布单位）。 */
    public double depth;
    /** 命中点（用于计算画布原点），只在构建期使用。 */
    public transient double hitU, hitV;

    public PlaneBlock() {
    }

    public PlaneBlock(double x0, double y0, double x1, double y1, double depth) {
        this.x0 = x0;
        this.y0 = y0;
        this.x1 = x1;
        this.y1 = y1;
        this.depth = depth;
    }

    public double width() {
        return x1 - x0;
    }

    public double height() {
        return y1 - y0;
    }

    public boolean contains(double cx, double cy) {
        return cx >= x0 && cx < x1 && cy >= y0 && cy < y1;
    }

    /** 以画布单位计，该方块列为第几列 / 第几行。 */
    public int col() {
        return (int) Math.floor(x0 / PlaneCanvas.UNITS_PER_BLOCK + 1.0e-6);
    }

    public int row() {
        return (int) Math.floor(y0 / PlaneCanvas.UNITS_PER_BLOCK + 1.0e-6);
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putDouble("x0", x0);
        t.putDouble("y0", y0);
        t.putDouble("x1", x1);
        t.putDouble("y1", y1);
        t.putDouble("d", depth);
        return t;
    }

    public static PlaneBlock load(CompoundTag t) {
        PlaneBlock b = new PlaneBlock();
        b.x0 = t.getDouble("x0");
        b.y0 = t.getDouble("y0");
        b.x1 = t.getDouble("x1");
        b.y1 = t.getDouble("y1");
        b.depth = t.getDouble("d");
        return b;
    }
}

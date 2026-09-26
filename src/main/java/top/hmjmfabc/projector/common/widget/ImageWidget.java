package top.hmjmfabc.projector.common.widget;

import net.minecraft.nbt.CompoundTag;

/**
 * 图片控件。媒体本身以 SHA-1 为键保存在存档的 {@code projector_media} 目录里，
 * 控件只记录这个键与显示参数，因此同一个平面在任何客户端都能取到同一张图。
 */
public class ImageWidget extends Widget {

    /** 媒体键（SHA-1 十六进制）。空串表示尚未选择图片。 */
    public String mediaId = "";
    /** 显示用的原始文件名。 */
    public String mediaName = "";
    /** 原始像素尺寸（仅用于界面展示）。 */
    public int srcW, srcH;
    /** 裁剪区域（UV，0~1）。 */
    public double u0 = 0, v0 = 0, u1 = 1, v1 = 1;
    /** 叠加色调（ARGB，0xFFFFFFFF 表示原色）。 */
    public int tint = 0xFFFFFFFF;

    @Override
    public int kind() {
        return KIND_IMAGE;
    }

    @Override
    public String label() {
        String n = mediaName;
        if (n == null || n.isEmpty()) n = "\u56fe\u7247";
        return n + " (" + srcW + "\u00d7" + srcH + ")";
    }

    @Override
    protected void saveExtra(CompoundTag t) {
        t.putString("media", mediaId);
        t.putString("mediaName", mediaName);
        t.putInt("srcW", srcW);
        t.putInt("srcH", srcH);
        t.putDouble("u0", u0);
        t.putDouble("v0", v0);
        t.putDouble("u1", u1);
        t.putDouble("v1", v1);
        t.putInt("tint", tint);
    }

    @Override
    public void loadExtra(CompoundTag t) {
        mediaId = t.getString("media");
        mediaName = t.getString("mediaName");
        srcW = t.getInt("srcW");
        srcH = t.getInt("srcH");
        u0 = t.contains("u0") ? t.getDouble("u0") : 0;
        v0 = t.contains("v0") ? t.getDouble("v0") : 0;
        u1 = t.contains("u1") ? t.getDouble("u1") : 1;
        v1 = t.contains("v1") ? t.getDouble("v1") : 1;
        tint = t.contains("tint") ? t.getInt("tint") : 0xFFFFFFFF;
    }
}

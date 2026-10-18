package top.hmjmfabc.projector.compat.betterlogin.util;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.mojang.blaze3d.platform.NativeImage;

public class QRUtil {

    public static NativeImage generateQRCode(String content) throws WriterException {
        BitMatrix bitMatrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 150, 150);
        // 转为 NativeImage
        NativeImage nativeImage = new NativeImage(bitMatrix.getWidth(), bitMatrix.getHeight(), false);
        for (int x = 0; x < bitMatrix.getWidth(); x++) {
            for (int y = 0; y < bitMatrix.getHeight(); y++) {
                nativeImage.setPixelRGBA(x, y, bitMatrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
            }
        }
        return nativeImage;
    }

}

package top.hmjmfabc.projector.client.music;

import java.io.IOException;
import java.io.InputStream;

/**
 * 音频流工具：把读指针移到第一个 MPEG 帧（跳过 ID3v2 标签）。
 *
 * <p>本文件移植自 <b>Net Music Mod（网络音乐机）</b> 的 {@code util/Mp3Util}，
 * 作者 TartaricAcid 等，原项目以 MIT 许可发布（详见仓库根目录 NOTICE）。
 * 本项目（投影仪）只有音乐控件这一个模块引用了该项目。</p>
 */
public final class Mp3Util {
    private Mp3Util() {
    }

    /**
     * 跳过 ID3 标签。
     *
     * <p>⚠ 需要传入**支持 mark/reset** 的流（{@link java.io.FileInputStream} 不支持，
     * 必须先包一层 {@link java.io.BufferedInputStream}）。</p>
     */
    public static void skipID3(InputStream inputStream) throws IOException {
        // 读取 ID3 标签头部
        inputStream.mark(10);
        byte[] header = new byte[10];
        int read = inputStream.read(header, 0, 10);
        if (read < 10) {
            inputStream.reset();
            return;
        }

        // 检查是否有 ID3 标签
        if (header[0] == 'I' && header[1] == 'D' && header[2] == '3') {
            // 计算元数据大小（synchsafe：每字节只用低 7 位）
            int size = (header[6] << 21) | (header[7] << 14) | (header[8] << 7) | header[9];

            // 跳过元数据
            int skipped = 0;
            int skip;
            do {
                skip = (int) inputStream.skip(size - skipped);
                if (skip != 0) {
                    skipped += skip;
                }
            } while (skipped < size && skip != 0);
        } else {
            inputStream.reset();
        }
    }
}

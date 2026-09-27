package top.hmjmfabc.projector.client.music;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 给 MP3 解码器用的缓冲流：把 IOException 变成 RuntimeException 抛出去。
 *
 * <p>mp3 解码库会吞掉所有 IOException（连接超时也一起吞），导致读取循环
 * 陷入「读到 0 字节但也不报错」的死循环、把线程拖住。这里重写 read，
 * 让异常能正常冒泡到调用方。</p>
 *
 * <p>本文件移植自 <b>Net Music Mod（网络音乐机）</b> 的
 * {@code client/audio/MusicBufferedInputStream}（作者 IMG），原项目 MIT 许可，见 NOTICE。</p>
 */
public class MusicBufferedInputStream extends BufferedInputStream {
    public MusicBufferedInputStream(InputStream in) {
        super(in);
    }

    @Override
    public synchronized int read(byte[] b, int off, int len) {
        try {
            return super.read(b, off, len);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}

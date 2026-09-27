package top.hmjmfabc.projector.client.music;

import top.hmjmfabc.projector.Projector;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.function.Function;

/**
 * 可自动续传的 HTTP 音频输入流：读到一半断了就从断点重新发一次 Range 请求。
 *
 * <p>移植自 <b>Net Music Mod（网络音乐机）</b> 的 {@code client/audio/ChunkedAudioStream}
 * （作者 IMG，MIT 许可，见 NOTICE），只保留本项目需要的行为（最多重试 3 次）。</p>
 */
public class ChunkedAudioStream extends InputStream {
    private static final int MAX_RETRY = 3;

    /** 用「起始字节」造一个带 Range 头的请求。 */
    private final Function<Long, HttpRequest> request;

    private InputStream currentStream;
    private long currentStart;

    public ChunkedAudioStream(Function<Long, HttpRequest> request) throws IOException {
        this.request = request;
        this.currentStart = 0L;
        this.currentStream = openChunk(0L);
    }

    private InputStream openChunk(long start) throws IOException {
        HttpRequest httpRequest = request.apply(start);
        return MusicHttp.sendForStream(httpRequest);
    }

    private InputStream current() throws IOException {
        if (currentStream == null) {
            currentStream = openChunk(currentStart);
        }
        return currentStream;
    }

    private int tryRead(byte[] b, int off, int len, int left) throws IOException {
        if (left <= 0) {
            throw new IOException("音频流读取失败（已重试 " + MAX_RETRY + " 次）");
        }
        try {
            return current().read(b, off, len);
        } catch (IOException e) {
            Projector.LOGGER.warn("[Projector][音乐] 音频流在 {} 字节处断开：{}（剩余重试 {}）",
                    currentStart, e.toString(), left - 1);
            closeCurrent();
            return tryRead(b, off, len, left - 1);
        }
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : (one[0] & 0xFF);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = tryRead(b, off, len, MAX_RETRY);
        if (n > 0) {
            currentStart += n;
        }
        return n;
    }

    private void closeCurrent() {
        InputStream stream = currentStream;
        currentStream = null;
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
                // 关不掉不影响重试
            }
        }
    }

    @Override
    public void close() throws IOException {
        closeCurrent();
        super.close();
    }

    /** 便于诊断：这条流的来源。 */
    public String describeSource() {
        try {
            return URI.create(request.apply(0L).uri().toString()).toString();
        } catch (Exception e) {
            return "未知来源";
        }
    }
}

package top.hmjmfabc.projector.client.music;

import top.hmjmfabc.projector.Projector;
import top.hmjmfabc.projector.ProjectorConfig;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * 音乐模块统一使用的 HTTP 客户端。
 *
 * <p>设计参考 <b>Net Music Mod（网络音乐机）</b> 的 {@code api/NetWorker}（MIT，见 NOTICE）：
 * 全局共用一个 {@link HttpClient}（每次都新建会耗尽连接与线程），
 * 且**必须** {@code followRedirects(ALWAYS)} —— 网易云的直链是 302 跳转，
 * 不跟随就只能拿到一个空响应体。</p>
 */
public final class MusicHttp {
    private MusicHttp() {
    }

    public static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .proxy(new MusicProxySelector())
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    /** 读超时：连接建立后最多等这么久（播放流要长，所以单独给）。 */
    public static final Duration API_TIMEOUT = Duration.ofSeconds(15);

    public static <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException {
        try {
            return CLIENT.send(request, handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("请求被中断：" + request.uri(), e);
        }
    }

    public static InputStream sendForStream(HttpRequest request) throws IOException {
        HttpResponse<InputStream> response = send(request, HttpResponse.BodyHandlers.ofInputStream());
        int code = response.statusCode();
        // 200 = 完整响应；206 = 分段响应（我们发的是 Range 请求，正常就是 206）
        if (code != 200 && code != 206) {
            try (InputStream body = response.body()) {
                if (body != null) {
                    body.close();
                }
            } catch (IOException ignored) {
                // 关不掉就算了，下面要抛的才是关键信息
            }
            throw new IOException("音频地址返回 HTTP " + code + "：" + request.uri());
        }
        InputStream body = response.body();
        if (body == null) {
            throw new IOException("音频地址返回空响应体：" + request.uri());
        }
        return body;
    }

    /**
     * 代理选择器：每次 {@code select} 都重新读配置（改配置不必重启游戏）。
     *
     * <p>与 Net Music Mod 的 {@code ConfigProxySelector} 同一个思路（MIT，见 NOTICE）。</p>
     */
    public static final class MusicProxySelector extends ProxySelector {
        @Override
        public List<Proxy> select(URI uri) {
            try {
                String address = ProjectorConfig.INSTANCE.musicProxyAddress.get();
                if (address == null || address.isBlank()) {
                    return List.of(Proxy.NO_PROXY);
                }
                int colon = address.lastIndexOf(':');
                if (colon <= 0 || colon == address.length() - 1) {
                    return List.of(Proxy.NO_PROXY);
                }
                String host = address.substring(0, colon).trim();
                int port = Integer.parseInt(address.substring(colon + 1).trim());
                return List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port)));
            } catch (Exception e) {
                Projector.LOGGER.warn("[Projector][音乐] 代理地址无效，本次直连：{}", e.toString());
                return List.of(Proxy.NO_PROXY);
            }
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            Projector.LOGGER.warn("[Projector][音乐] 代理连接失败 {}：{}", sa, ioe.toString());
        }
    }
}

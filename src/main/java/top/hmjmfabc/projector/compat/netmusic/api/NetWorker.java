package top.hmjmfabc.projector.compat.netmusic.api;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

/**
 * 【27.2.2】网络音乐机（NetMusic）{@code api.NetWorker} 的**镜像实现**。
 *
 * <h2>为什么要有这个类</h2>
 * 网络音乐机的那批「技术性附属」都在代码里直接 {@code import
 * com.github.tartaricacid.netmusic.api.NetWorker}。要让它们能接到投影仪上，
 * 有两条路：① 在**它的包名**下提供同名类 —— 但那样与真正的网络音乐机**类名冲突**，
 * 两者不能共存；② 在**我们自己的包名**下照它的**方法名与语义**实现一份，
 * 由附属改一行 import 接过来（许可证允许的附属可以由我直接改这一行）。
 * 这里采用②，并且**只按公开方法签名与语义实现，不复制它的任何代码**。
 *
 * <h2>镜像到的方法</h2>
 * {@link #get(String, Map)}、{@link #send(HttpRequest, HttpResponse.BodyHandler)}
 * —— 这两条是附属实际用到的全部（已用 grep 逐个核对过调用点）。
 */
public final class NetWorker {

    private NetWorker() {
    }

    /**
     * 公共 HTTP 客户端（与 NetMusic 同名同语义：附属如果直接读这个字段也能用）。
     * <p>不跟随重定向到其它协议、超时给足（网易云偶尔慢）。</p>
     */
    public static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .proxy(ProxySelector.getDefault())
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .build();

    private static final Gson GSON = new Gson();

    /** GET 一个 URL，返回响应体字符串（与 NetMusic 的同名方法语义一致）。 */
    public static String get(String url, Map<String, String> requestPropertyData) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).GET();
        if (requestPropertyData != null) {
            requestPropertyData.forEach((k, v) -> {
                if (k != null && v != null) builder.header(k, v);
            });
        }
        try {
            return HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString()).body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("请求被中断：" + url, e);
        }
    }

    /** GET 一个 URL，把响应体当 JSON 解析（投影仪自己的便捷方法）。 */
    public static JsonObject getJson(String url, Map<String, String> headers) throws IOException {
        String body = get(url, headers);
        JsonElement e = GSON.fromJson(body, JsonElement.class);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
    }

    /** 发一个自定义请求（与 NetMusic 的同名方法语义一致）。 */
    public static <T> HttpResponse<T> send(HttpRequest request,
                                           HttpResponse.BodyHandler<T> bodyHandler) throws IOException {
        try {
            return HTTP_CLIENT.send(request, bodyHandler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("请求被中断：" + request.uri(), e);
        }
    }

    /** 与 NetMusic 同名：从配置里取代理（投影仪用 {@code music.proxyAddress}）。 */
    public static Proxy getProxyFromConfig() {
        try {
            String addr = top.hmjmfabc.projector.ProjectorConfig.INSTANCE.musicProxyAddress.get();
            if (addr != null && !addr.isBlank() && addr.contains(":")) {
                String[] parts = addr.split(":", 2);
                return new Proxy(Proxy.Type.HTTP,
                        new InetSocketAddress(parts[0].trim(), Integer.parseInt(parts[1].trim())));
            }
        } catch (Throwable ignored) {
            // 配置读不到就当直连
        }
        return Proxy.NO_PROXY;
    }

    /** 与 NetMusic 同名的内部类（附属若引用它也能编译）。 */
    public static final class ConfigProxySelector extends ProxySelector {
        @Override
        public List<Proxy> select(URI uri) {
            return List.of(getProxyFromConfig());
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            // 与 NetMusic 一致：失败不抛，交给上层重试
        }
    }
}

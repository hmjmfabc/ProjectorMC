package top.hmjmfabc.projector.compat.betterlogin.api;

import top.hmjmfabc.projector.compat.netmusic.api.NetWorker;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.*;

public class BetterNetWorker {
    public static HttpResponse get(String url, Map<String, String> requestPropertyData) throws IOException {
        HttpRequest request = createRequestBuilder(url, requestPropertyData)
                .GET()
                .build();
        java.net.http.HttpResponse<String> response = NetWorker.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

        return new HttpResponse(response.body(), response.headers().map());
    }

    private static HttpRequest.Builder createRequestBuilder(String url, Map<String, String> requestPropertyData) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url));
        Objects.requireNonNull(builder);
        requestPropertyData.forEach(builder::header);
        return builder;
    }

    public static class HttpResponse {
        public String body;
        public Map<String, List<String>> headers;

        public HttpResponse(String body, Map<String, List<String>> headers) {
            this.body = body;
            this.headers = headers;
        }

        public List<String> getHeader(String key) {
            return headers.get(key);
        }

        public Map<String, String> getCookies() {
            List<String> header = getHeader("Set-Cookie");
            if (header == null || header.isEmpty()) {
                return Collections.emptyMap();
            }
            HashMap<String, String> cookieMap = new HashMap<>();
            header.stream().map(this::getCookieBody)
                    .filter(Objects::nonNull)
                    .forEach(cookie -> {
                        String[] parts = cookie.split("=", 2);
                        if (parts.length == 2) {
                            cookieMap.put(parts[0].trim(), parts[1].trim());
                        }
                    });
            return cookieMap;
        }

        public String getCookieBody(String rawCookie) {
            String[] parts = rawCookie.split(";");
            if (parts.length > 0) {
                return parts[0].trim();
            }
            return null;
        }
    }
}

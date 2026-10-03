package com.techducat.speso;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Talks to a node's Rpc. Knows nothing about keys: it only moves JSON. */
final class RpcClient {
    static final class RpcException extends IOException {
        final int status;
        RpcException(int status, String msg) { super(msg); this.status = status; }
    }

    private static final int MAX_REPLY = 1 << 20;
    private final String base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    RpcClient(String base) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        if (!this.base.startsWith("http://") && !this.base.startsWith("https://")) throw new IllegalArgumentException("rpc url must be http(s)");
    }

    Map<String, Object> get(String path) throws IOException { return send(HttpRequest.newBuilder(URI.create(base + path)).GET()); }

    Map<String, Object> post(String path, String json) throws IOException {
        return send(HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)));
    }

    private Map<String, Object> send(HttpRequest.Builder b) throws IOException {
        try {
            HttpResponse<java.io.InputStream> r = http.send(b.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (java.io.InputStream in = r.body()) { body = in.readNBytes(MAX_REPLY + 1); }
            if (body.length > MAX_REPLY) throw new IOException("reply too large");
            Map<String, Object> m;
            try { m = Json.parseObject(new String(body, java.nio.charset.StandardCharsets.UTF_8)); }
            catch (IllegalArgumentException e) { throw new IOException("node sent something that is not JSON (is this an RPC port?)"); }
            if (r.statusCode() != 200) throw new RpcException(r.statusCode(), String.valueOf(m.getOrDefault("error", "HTTP " + r.statusCode())));
            return m;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    static long num(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (!(v instanceof Number n)) throw new IllegalStateException("node reply is missing '" + k + "'");
        return n.longValue();
    }

    static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (!(v instanceof String s)) throw new IllegalStateException("node reply is missing '" + k + "'");
        return s;
    }
}

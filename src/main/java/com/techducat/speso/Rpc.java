package com.techducat.speso;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * JSON-over-HTTP RPC for thin wallets. Read-only except POST /v1/tx, which takes an ALREADY SIGNED
 * transaction: the node never sees a private key, so exposing this does not expose anyone's funds.
 *
 *   GET  /v1/status              network, height, tip, score, supply, mempool, peers, oracle
 *   GET  /v1/rate                score, value of 1 spesmilo in GBU, indicators, supply vs target, difficulty
 *   GET  /v1/quote?gbu=10        cost of 10 GBU now, worst case after 1/3/6 blocks, suggested max
 *   GET  /v1/account/{addr}      balance and the next sequence number to sign with (pending included)
 *   GET  /v1/publishers          registered oracle publishers and whether each has a live report
 *   GET  /v1/block/{height}      block summary
 *   GET  /v1/tx/{id}             pending | confirmed (height, confirmations) | unknown
 *   POST /v1/tx                  {"tx":"<encoded signed tx>"}  ->  {"id":"..."}
 *
 * Binds to 127.0.0.1 unless told otherwise. Bounded body size, bounded thread pool (excess load is
 * dropped), token bucket per client IP. There is no TLS and no authentication: put it behind a
 * reverse proxy if it must be reachable from outside, and remember a node can lie to its clients
 * (wrong balance, wrong nonce, censorship); it cannot move their money.
 */
final class Rpc {
    static final int MAX_BODY = 64 * 1024;

    // Without TCP_NODELAY the JDK server's header+body writes hit Nagle/delayed-ACK and every reply stalls ~45 ms.
    // The JDK reads this once per JVM, before the first HttpServer is created.
    static { System.setProperty("sun.net.httpserver.nodelay", "true"); }

    private final Node node;
    private final Ledger ledger;
    private final HttpServer http;
    private final ThreadPoolExecutor pool;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    private static final class Bucket {          // 60 burst, 20 tokens/sec
        double tokens = 60; long last = System.nanoTime();
        synchronized boolean take(double cost) {
            long now = System.nanoTime();
            tokens = Math.min(60, tokens + (now - last) / 1e9 * 20);
            last = now;
            if (tokens < cost) return false;
            tokens -= cost;
            return true;
        }
    }

    Rpc(Node node, String bind, int port) throws IOException {
        this.node = node;
        this.ledger = node.ledger;
        http = HttpServer.create(new InetSocketAddress(InetAddress.getByName(bind), port), 64);
        pool = new ThreadPoolExecutor(8, 8, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), r -> {
            Thread t = new Thread(r, "rpc"); t.setDaemon(true); return t;
        });
        http.setExecutor(r -> { try { pool.execute(r); } catch (RejectedExecutionException e) { /* overloaded: drop */ } });
        http.createContext("/v1/", this::handle);
    }

    void start() { http.start(); }
    void stop() { http.stop(0); pool.shutdownNow(); }
    int port() { return http.getAddress().getPort(); }

    // ------------------------------------------------------------------ plumbing

    private static final class HttpError extends RuntimeException {
        final int code;
        HttpError(int code, String msg) { super(msg); this.code = code; }
    }

    private void reply(HttpExchange ex, int code, Object body) throws IOException {
        byte[] b = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            if (buckets.size() > 10_000) buckets.clear();
            String ip = ex.getRemoteAddress().getAddress().getHostAddress();
            String method = ex.getRequestMethod(), path = ex.getRequestURI().getPath();
            double cost = method.equals("POST") ? 5 : 1;
            if (!buckets.computeIfAbsent(ip, k -> new Bucket()).take(cost)) throw new HttpError(429, "slow down");
            reply(ex, 200, route(method, path, ex));
        } catch (HttpError e) {
            reply(ex, e.code, Json.obj("error", e.getMessage()));
        } catch (RuntimeException e) {
            reply(ex, 500, Json.obj("error", "internal error"));
        } finally {
            ex.close();
        }
    }

    private Object route(String method, String path, HttpExchange ex) throws IOException {
        String[] p = path.substring(1).split("/");               // ["v1", "account", "<addr>"]
        if (p.length < 2) throw new HttpError(404, "not found");
        boolean get = method.equals("GET"), post = method.equals("POST");
        switch (p[1]) {
            case "status" -> { need(get, p, 2); return status(); }
            case "rate" -> { need(get, p, 2); return rate(); }
            case "publishers" -> { need(get, p, 2); return publishers(); }
            case "quote" -> { need(get, p, 2); return quote(ex.getRequestURI().getRawQuery()); }
            case "account" -> { need(get, p, 3); return account(p[2]); }
            case "block" -> { need(get, p, 3); return block(p[2]); }
            case "tx" -> {
                if (post) { need(true, p, 2); return submit(ex); }
                need(get, p, 3);
                return tx(p[2]);
            }
            default -> throw new HttpError(404, "not found");
        }
    }

    private static void need(boolean ok, String[] p, int len) {
        if (p.length != len) throw new HttpError(404, "not found");
        if (!ok) throw new HttpError(405, "method not allowed");
    }

    // ------------------------------------------------------------------ handlers

    private Object status() {
        Block t = ledger.tip();
        return Json.obj("network", Params.NETWORK, "networkId", Params.NETWORK_ID, "height", t.index, "tip", t.hash, "score", t.score,
                "bits", t.bits, "supply", ledger.supply(), "supplyFmt", Params.fmt(ledger.supply()),
                "mempool", ledger.mempoolSize(), "peers", node.peers().size(), "mining", node.isMining(),
                "oracle", ledger.oracleStatus(), "anchored", !Params.PUBLISHERS.isEmpty());
    }

    private Object rate() {
        Block t = ledger.tip();
        Map<String, Object> ind = new LinkedHashMap<>();
        for (int i = 0; i < EconomyIndex.N; i++) ind.put(EconomyIndex.NAMES[i], t.indicators[i]);
        return Json.obj("block", t.index, "score", t.score,
                "gbuPerSpesmilo", String.format(Locale.ROOT, "%.4f", t.score / 10000.0),
                "indicators", ind, "supply", ledger.supply(), "target", ledger.effectiveTarget(), "baseTarget", Params.supplyTarget(t.score),
                "bits", t.bits, "oracle", ledger.oracleStatus());
    }

    private Object publishers() {
        List<Object> l = new ArrayList<>();
        for (String a : Params.PUBLISHERS) l.add(Json.obj("address", a, "live", ledger.hasAttestation(a)));
        return Json.obj("threshold", Params.PUBLISHER_THRESHOLD, "publishers", l);
    }

    private Object quote(String rawQuery) {
        String gbu = null;
        if (rawQuery != null)
            for (String kv : rawQuery.split("&")) if (kv.startsWith("gbu=")) gbu = java.net.URLDecoder.decode(kv.substring(4), StandardCharsets.UTF_8);
        if (gbu == null) throw new HttpError(400, "missing ?gbu=");
        long amount;
        try { amount = Params.parse(gbu); } catch (IllegalArgumentException e) { throw new HttpError(400, e.getMessage()); }
        if (amount <= 0 || amount > Params.MAX_AMOUNT) throw new HttpError(400, "amount out of range");
        Block t = ledger.tip();
        long now = Quote.cost(amount, t.score), max = Quote.suggestedMax(amount, t.score);
        return Json.obj("gbu", Params.fmt(amount), "block", t.index, "score", t.score, "cost", now, "costFmt", Params.fmt(now),
                "worstCase", Quote.table(amount, t.score), "suggestedMax", max, "suggestedMaxFmt", Params.fmt(max));
    }

    private Object account(String addr) {
        if (!Crypto.isAddress(addr)) throw new HttpError(400, "bad address");
        long b = ledger.balance(addr);
        return Json.obj("address", addr, "balance", b, "balanceFmt", Params.fmt(b), "nextSeq", ledger.nextSeq(addr));
    }

    private Object block(String h) {
        int idx;
        try { idx = Integer.parseInt(h); } catch (NumberFormatException e) { throw new HttpError(400, "bad height"); }
        List<Block> bs = ledger.blocksFrom(idx, 1);
        if (idx < 0 || bs.isEmpty()) throw new HttpError(404, "no such block");
        Block b = bs.get(0);
        List<Object> ids = new ArrayList<>();
        for (Transaction t : b.txs) ids.add(t.id());
        return Json.obj("index", b.index, "hash", b.hash, "prevHash", b.prevHash, "time", b.time, "bits", b.bits,
                "miner", b.miner, "score", b.score, "txs", ids);
    }

    private Object tx(String id) {
        if (!id.matches("[0-9a-f]{16}")) throw new HttpError(400, "bad tx id");
        long[] r = ledger.findTx(id);
        if (r[0] == 1) return Json.obj("id", id, "state", "pending");
        if (r[0] == 2) return Json.obj("id", id, "state", "confirmed", "height", r[1], "confirmations", ledger.height() - r[1] + 1);
        return Json.obj("id", id, "state", "unknown");
    }

    private Object submit(HttpExchange ex) throws IOException {
        String len = ex.getRequestHeaders().getFirst("Content-Length");
        if (len != null) {
            try { if (Long.parseLong(len) > MAX_BODY) throw new HttpError(413, "body too large"); }
            catch (NumberFormatException e) { throw new HttpError(400, "bad Content-Length"); }
        }
        byte[] body = ex.getRequestBody().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) throw new HttpError(413, "body too large");
        Transaction t;
        try {
            Object enc = Json.parseObject(new String(body, StandardCharsets.UTF_8)).get("tx");
            if (!(enc instanceof String s)) throw new HttpError(400, "missing \"tx\"");
            t = Transaction.decode(s);
        } catch (IllegalArgumentException e) {
            throw new HttpError(400, "malformed transaction: " + e.getMessage());
        }
        switch (node.submitTx(t)) {
            case ACCEPTED -> { return Json.obj("id", t.id(), "state", "pending"); }
            case INVALID -> throw new HttpError(400, "invalid transaction (bad signature, fee too low, or malformed fields)");
            default -> throw new HttpError(409, "rejected: " + ledger.rejectReason(t));
        }
    }
}

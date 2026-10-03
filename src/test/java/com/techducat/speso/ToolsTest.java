package com.techducat.speso;

import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;

/**
 * Run: java -cp out com.techducat.speso.ToolsTest
 * JSON, the automated feed (against a local fake World Bank / IMF), the RPC, and the thin wallet.
 * The real World Bank and IMF are never contacted: their reply formats are reproduced as fixtures.
 */
public final class ToolsTest {
    static void check(String n, boolean c) { SelfTest.check(n, c); }
    static void section(String s) { SelfTest.section(s); }

    // ------------------------------------------------------------------ fixtures

    static String wbBody(Map<Integer, Double> years) {
        StringBuilder sb = new StringBuilder("[{\"page\":1,\"pages\":1,\"per_page\":100,\"total\":" + years.size() + "},[");
        boolean first = true;
        for (Map.Entry<Integer, Double> e : years.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"indicator\":{\"id\":\"X\",\"value\":\"x\"},\"country\":{\"id\":\"1W\",\"value\":\"World\"},\"countryiso3code\":\"WLD\",\"date\":\"")
              .append(e.getKey()).append("\",\"value\":").append(e.getValue() == null ? "null" : e.getValue().toString()).append('}');
        }
        return sb.append("]]").toString();
    }

    static String imfBody(String ind, Map<Integer, Double> years) {
        StringBuilder sb = new StringBuilder("STRUCTURE,STRUCTURE_ID,ACTION,COUNTRY,INDICATOR,FREQUENCY,TIME_PERIOD,OBS_VALUE\n");
        for (Map.Entry<Integer, Double> e : years.entrySet())
            sb.append("dataflow,\"IMF.RES:WEO(+.0.0)\",I,G001,").append(ind).append(",A,").append(e.getKey()).append(',').append(e.getValue()).append('\n');
        return sb.toString();
    }

    static Map<Integer, Double> yrs(Object... kv) {
        Map<Integer, Double> m = new TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((Integer) kv[i], kv[i + 1] == null ? null : ((Number) kv[i + 1]).doubleValue());
        return m;
    }

    static final Map<String, String> BODIES = new HashMap<>();
    static final Map<String, Integer> CODES = new HashMap<>();

    static HttpServer fakeUpstream() throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            int code = CODES.getOrDefault(path, BODIES.containsKey(path) ? 200 : 404);
            byte[] b = BODIES.getOrDefault(path, "not found").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
            ex.close();
        });
        s.start();
        return s;
    }

    static final String[] WB = {"NY.GDP.MKTP.KD.ZG", "FP.CPI.TOTL.ZG", "SL.UEM.TOTL.ZS", "NE.EXP.GNFS.KD.ZG"};
    static final String[] IM = {"NGDP_RPCH", "PCPIPCH", null, "TX_RPCH"};

    static void wb(int i, Map<Integer, Double> y) { BODIES.put("/wb/country/WLD/indicator/" + WB[i], wbBody(y)); }
    static void imf(int i, Map<Integer, Double> y) { BODIES.put("/imf/data/dataflow/IMF.RES/WEO/~/G001." + IM[i] + ".A", imfBody(IM[i], y)); }
    static String imfPath(int i) { return "/imf/data/dataflow/IMF.RES/WEO/~/G001." + IM[i] + ".A"; }

    static void happyData() {
        BODIES.clear(); CODES.clear();
        wb(0, yrs(2023, 2.6, 2024, 2.9, 2025, 2.8, 2026, null));
        imf(0, yrs(2023, 3.3, 2024, 3.3, 2025, 3.2, 2026, 3.1, 2027, 3.2));      // 2026+ are forecasts: ignored
        wb(1, yrs(2024, 5.0, 2025, 4.0));         imf(1, yrs(2024, 5.9, 2025, 4.4));
        wb(2, yrs(2024, 5.2, 2025, 5.1));
        wb(3, yrs(2024, 1.0, 2025, 3.0));         imf(3, yrs(2024, 1.5, 2025, 3.6));
    }

    static Feed.Config cfg(HttpServer s) {
        Feed.Config c = new Feed.Config();
        c.wbBase = "http://127.0.0.1:" + s.getAddress().getPort() + "/wb";
        c.imfBase = "http://127.0.0.1:" + s.getAddress().getPort() + "/imf";
        return c;
    }

    static String problem(Feed f) {
        try { f.fetch(2025); return null; } catch (Feed.FeedException e) { return e.getMessage(); }
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] a) throws Exception {
        System.setProperty("sun.net.httpserver.nodelay", "true");   // before ANY HttpServer exists in this JVM
        System.setProperty("speso.initbits", "6");
        System.setProperty("speso.retarget", "6");
        System.setProperty("speso.blockms", "1000");
        System.setProperty("speso.supplybase", "600");
        System.setProperty("speso.oraclewindow", "12");
        System.setProperty("speso.pbkdf2", "1000");

        json();
        feed();
        rpcAndWallet();

        System.out.println(SelfTest.failures == 0 ? "\nTOOLS: ALL TESTS PASSED" : "\n" + SelfTest.failures + " FAILED");
        System.exit(SelfTest.failures == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ json

    static void json() {
        section("JSON");
        Map<String, Object> m = Json.obj("s", "a\"b\\c\nd\u0001", "n", 12L, "f", 1.5, "b", true, "z", null, "l", List.of(1, "x"), "a", new long[]{1, 2});
        Map<String, Object> back = Json.parseObject(Json.write(m));
        check("write/parse round trip keeps strings with quotes, newlines and control characters", "a\"b\\c\nd\u0001".equals(back.get("s")));
        check("integers come back as Long, decimals as Double", back.get("n") instanceof Long && back.get("f") instanceof Double && back.get("l") instanceof List);
        boolean bad = true;
        for (String s : new String[]{"{", "{\"a\":}", "[1,]", "{\"a\":1} x", "\"\\q\"", "nul", ""}) {
            try { Json.parse(s); bad = false; } catch (IllegalArgumentException e) { /* expected */ }
        }
        check("malformed JSON is refused", bad);
        boolean deep = false;
        try { Json.parse("[".repeat(200) + "]".repeat(200)); } catch (IllegalArgumentException e) { deep = true; }
        check("absurd nesting is refused", deep);
    }

    // ------------------------------------------------------------------ feed

    static void feed() throws Exception {
        section("Feed: parsers");
        boolean err = false;
        try { Feed.parseWorldBank("[{\"message\":[{\"id\":\"120\"}]}]"); } catch (Feed.FeedException e) { err = true; }
        check("a World Bank error reply is refused", err);
        TreeMap<Integer, Double> p = Feed.parseWorldBank(wbBody(yrs(2024, 2.9, 2025, null)));
        check("World Bank null values are skipped", p.size() == 1 && p.get(2024) == 2.9);
        TreeMap<Integer, Double> q = Feed.parseImf("TIME_PERIOD,OBS_VALUE,NOTE\n2024,3.3,\"a, b\"\n2025,,x\n2023,abc,x\nQ1 2022,1.0,x\n");
        check("IMF CSV: quoted commas, empty, non-numeric and non-annual rows handled", q.size() == 1 && q.get(2024) == 3.3);
        boolean amb = false;
        try { Feed.parseImf("TIME_PERIOD,OBS_VALUE\n2024,3.3\n2024,3.4\n"); } catch (Feed.FeedException e) { amb = true; }
        check("conflicting duplicate years (ambiguous series key) are refused", amb);
        boolean nocol = false;
        try { Feed.parseImf("A,B\n1,2\n"); } catch (Feed.FeedException e) { nocol = true; }
        check("a CSV without TIME_PERIOD/OBS_VALUE is refused", nocol);

        HttpServer up = fakeUpstream();
        try {
            section("Feed: cross-check of World Bank and IMF");
            happyData();
            Feed.Result r = new Feed(cfg(up)).fetch(2025);
            check("both sources agree: published value is the mean (gdp 2.8/3.2 -> 3.00)", r.indicators[0] == 300);
            check("inflation 4.0/4.4 -> 4.20, unemployment (World Bank only) 5.10, trade 3.0/3.6 -> 3.30",
                    r.indicators[1] == 420 && r.indicators[2] == 510 && r.indicators[3] == 330);
            check("stress is held at the baseline", r.indicators[4] == EconomyIndex.BASELINE[4]);
            check("forecast years (after last year) were ignored", r.notes.get(0).contains("2025"));

            Path dir = Files.createTempDirectory("feed");
            Path out = dir.resolve("indicators.txt");
            Feed.write(r, out);
            check("the written file is read back identically by the node's reporter", Arrays.equals(EconomyIndex.readFeed(out.toString()), r.indicators));
            check("a fresh file passes the staleness check; an old one does not",
                    Node.feedFresh(out.toString(), 60_000) && !touchOld(out) );
            check("a missing file is stale when an age limit is set", !Node.feedFresh(dir.resolve("nope").toString(), 60_000));

            happyData();
            wb(0, yrs(2024, 2.9, 2025, null));
            check("a year missing at one source falls back to the latest year both have (2024)", new Feed(cfg(up)).fetch(2025).notes.get(0).contains("2024"));

            happyData();
            wb(0, yrs(2025, 1.0));
            String why = problem(new Feed(cfg(up)));
            check("sources that disagree beyond tolerance: nothing is published", why != null && why.contains("disagree"));

            happyData();
            CODES.put(imfPath(1), 500);
            why = problem(new Feed(cfg(up)));
            check("one source down: nothing is published (no silent single-source fallback)", why != null && why.contains("inflation") && why.contains("500"));

            happyData();
            BODIES.remove(imfPath(0));
            why = problem(new Feed(cfg(up)));
            check("a source that does not have the series (404): nothing is published", why != null && why.contains("gdp") && why.contains("404"));

            happyData();
            wb(0, yrs(2020, 2.0)); imf(0, yrs(2020, 2.1));
            why = problem(new Feed(cfg(up)));
            check("data older than maxLag years is refused", why != null && why.contains("older"));

            happyData();
            BODIES.put("/wb/country/WLD/indicator/" + WB[1], "<html>captive portal</html>");
            BODIES.put(imfPath(3), "garbage");
            why = problem(new Feed(cfg(up)));
            check("garbage replies are refused and ALL problems are reported", why != null && why.contains("inflation") && why.contains("trade"));

            Feed.Config dead = cfg(up);
            dead.wbBase = "http://127.0.0.1:1/wb";
            why = problem(new Feed(dead));
            check("an unreachable source is reported with its address, never as 'null'",
                    why != null && why.contains("cannot reach http://127.0.0.1:1/wb") && !why.contains("null"));

            happyData();
            Feed.Config single = cfg(up);
            single.imf[0] = null;
            wb(0, yrs(2025, 2.8));
            check("an indicator configured with one source uses it (gdp 2.80)", new Feed(single).fetch(2025).indicators[0] == 280);

            Path props = dir.resolve("feed.properties");
            Files.writeString(props, "wb.base=http://x/wb\nstress=41.5\ngdp.imf=\ngdp.tol=0.5\nmaxlag=1\ntrade.wb=ABC\n");
            Feed.Config lc = Feed.Config.load(props.toString());
            check("config file overrides sources, tolerance, lag and a fixed stress",
                    lc.wbBase.equals("http://x/wb") && lc.stress == 4150 && lc.imf[0] == null && lc.tol[0] == 0.5 && lc.maxLag == 1 && lc.wb[3].equals("ABC"));

            section("Feed: updater daemon");
            happyData();
            Path live = dir.resolve("live.txt");
            List<String> log = Collections.synchronizedList(new ArrayList<>());
            Feed.Updater u = new Feed.Updater(cfg(up), live, 200, log::add);
            // the updater uses the real clock's "last year"; align the fixtures with it
            int ly = java.time.Year.now().getValue() - 1;
            BODIES.clear();
            wb(0, yrs(ly, 2.8)); imf(0, yrs(ly, 3.2)); wb(1, yrs(ly, 4.0)); imf(1, yrs(ly, 4.4));
            wb(2, yrs(ly, 5.1)); wb(3, yrs(ly, 3.0)); imf(3, yrs(ly, 3.6));
            Thread t = u.start();
            SelfTest.waitFor(() -> Files.exists(live), 5000);
            check("updater writes the feed file", Files.exists(live) && EconomyIndex.readFeed(live.toString()) != null);
            long before = Files.getLastModifiedTime(live).toMillis();
            CODES.put(imfPath(0), 503);
            Thread.sleep(700);
            u.stop(); t.interrupt();
            check("when a source fails the file is left alone (and ages out) and the failure is logged",
                    Files.getLastModifiedTime(live).toMillis() >= before && log.stream().anyMatch(s -> s.contains("NOT updated")));
        } finally {
            up.stop(0);
        }
    }

    static boolean touchOld(Path p) throws IOException {
        Files.setLastModifiedTime(p, FileTime.fromMillis(System.currentTimeMillis() - 3_600_000L * 10));
        return Node.feedFresh(p.toString(), 3_600_000L);
    }

    // ------------------------------------------------------------------ rpc + wallet

    static HttpResponse<String> http(HttpClient c, String method, String url, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url));
        b = method.equals("POST") ? b.POST(HttpRequest.BodyPublishers.ofString(body)) : method.equals("GET") ? b.GET() : b.method(method, HttpRequest.BodyPublishers.noBody());
        return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static int cli(StringBuilder out, String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(), e = new ByteArrayOutputStream();
        int rc = Cli.run(args, new PrintStream(o, true, StandardCharsets.UTF_8), new PrintStream(e, true, StandardCharsets.UTF_8));
        out.setLength(0);
        out.append(o.toString(StandardCharsets.UTF_8)).append(e.toString(StandardCharsets.UTF_8));
        return rc;
    }

    static void rpcAndWallet() throws Exception {
        section("Thin wallet: signs for the network id its node reports");
        {
            Wallet tw = SelfTest.wallet();
            Transaction own = tw.pay(tw.address, 1, Params.MIN_FEE, 0);
            tw.networkId = "ffffffffffffffff";                   // a node whose publisher list differs from this JVM's
            Transaction foreign = tw.pay(tw.address, 1, Params.MIN_FEE, 0);
            check("a signature made for another network id is invalid here (no cross-network replay)", own.verify() && !foreign.verify());
            check("the same transaction verifies against the id it was signed for",
                    Crypto.verify(foreign.fromPub, foreign.signingText("ffffffffffffffff"), foreign.sig));
        }

        section("Wallet: reading an address without the password");
        Path wf0 = Files.createTempFile("enc", ".wallet");
        Files.delete(wf0);
        Wallet enc = Wallet.loadOrCreate(wf0.toString(), "pw".toCharArray());
        check("addressOf() works on an encrypted wallet without decrypting it", enc.address.equals(Wallet.addressOf(wf0.toString())));
        check("addressOf() is null for a missing file", Wallet.addressOf(wf0 + ".nope") == null);

        section("RPC and thin wallet");
        long SM = Params.SPESMILO;
        Path dir = Files.createTempDirectory("tw");
        String wfile = dir.resolve("w.dat").toString();
        StringBuilder out = new StringBuilder();
        Wallet bob = SelfTest.wallet();

        Ledger l = new Ledger();
        Node node = new Node(l, 0, SelfTest.wallet(), null, m -> {});
        Rpc rpc = new Rpc(node, "127.0.0.1", 0);
        rpc.start();
        String url = "http://127.0.0.1:" + rpc.port();
        HttpClient hc = HttpClient.newHttpClient();
        try {
            check("the thin wallet creates its own wallet file", cli(out, "--wallet", wfile, "--plain-wallet", "new") == 0 && Files.exists(Paths.get(wfile)));
            check("'new' refuses to overwrite an existing wallet", cli(out, "--wallet", wfile, "--plain-wallet", "new") != 0);
            Wallet me = Wallet.loadOrCreate(wfile, null);
            cli(out, "--wallet", wfile, "me");
            check("'me' prints the address", out.toString().trim().equals(me.address));
            check("commands on a missing wallet fail instead of creating one", cli(out, "--wallet", dir.resolve("typo.dat").toString(), "--plain-wallet", "send", bob.address, "1") != 0
                    && !Files.exists(dir.resolve("typo.dat")));

            for (int i = 0; i < 3; i++) SelfTest.mine(l, me);
            HttpResponse<String> st = http(hc, "GET", url + "/v1/status", null);
            Map<String, Object> sm = Json.parseObject(st.body());
            check("GET /v1/status: network, height, tip", st.statusCode() == 200 && Params.NETWORK.equals(sm.get("network")) && ((Number) sm.get("height")).intValue() == 3);
            Map<String, Object> acc = Json.parseObject(http(hc, "GET", url + "/v1/account/" + me.address, null).body());
            check("GET /v1/account: balance and next sequence number", ((Number) acc.get("balance")).longValue() == 150 * SM && ((Number) acc.get("nextSeq")).longValue() == 0);
            Map<String, Object> qm = Json.parseObject(http(hc, "GET", url + "/v1/quote?gbu=10", null).body());
            check("GET /v1/quote: matches the shared Quote maths and offers a ceiling above the price",
                    ((Number) qm.get("cost")).longValue() == Quote.cost(10 * SM, l.tip().score) && ((List<?>) qm.get("worstCase")).size() == 3
                    && ((Number) qm.get("suggestedMax")).longValue() > ((Number) qm.get("cost")).longValue());
            check("GET /v1/rate and /v1/publishers answer", http(hc, "GET", url + "/v1/rate", null).statusCode() == 200 && http(hc, "GET", url + "/v1/publishers", null).statusCode() == 200);
            Map<String, Object> blk = Json.parseObject(http(hc, "GET", url + "/v1/block/2", null).body());
            check("GET /v1/block/{n}", ((Number) blk.get("index")).intValue() == 2 && blk.get("miner").equals(me.address));

            check("balance via the thin wallet", cli(out, "--rpc", url, "--wallet", wfile, "balance") == 0 && out.toString().contains("150"));
            check("send via the thin wallet signs locally and the node accepts it",
                    cli(out, "--rpc", url, "--wallet", wfile, "--plain-wallet", "send", bob.address, "5") == 0 && out.toString().startsWith("submitted") && l.mempoolSize() == 1);
            String id = out.toString().split("\\s+")[1];
            cli(out, "--rpc", url, "tx", id);
            check("tx lookup: pending", out.toString().trim().equals("pending"));
            SelfTest.mine(l, me);
            cli(out, "--rpc", url, "tx", id);
            check("tx lookup: confirmed with height", out.toString().contains("confirmed at height 4"));
            check("bob received 5 spesmiloj", l.balance(bob.address) == 5 * SM);

            check("second send uses the next sequence number (pending included)",
                    cli(out, "--rpc", url, "--wallet", wfile, "--plain-wallet", "send", bob.address, "1") == 0
                    && cli(out, "--rpc", url, "--wallet", wfile, "--plain-wallet", "send", bob.address, "1") == 0 && l.mempoolSize() == 2);
            SelfTest.mine(l, me);
            check("sendgbu with an automatic ceiling", cli(out, "--rpc", url, "--wallet", wfile, "--plain-wallet", "sendgbu", bob.address, "10", "auto") == 0
                    && out.toString().contains("submitted") && l.mempoolSize() == 1);
            check("sendgbu with a ceiling below the price is refused by the node",
                    cli(out, "--rpc", url, "--wallet", wfile, "--plain-wallet", "sendgbu", bob.address, "10", "1") != 0);
            check("overspending is refused (409 from the node)", cli(out, "--rpc", url, "--wallet", wfile, "--plain-wallet", "send", bob.address, "999999") == 1 && out.toString().contains("409"));
            check("a bad address is refused locally", cli(out, "--rpc", url, "--wallet", wfile, "--plain-wallet", "send", "nothex", "1") == 2);
            check("quote and rate via the thin wallet", cli(out, "--rpc", url, "quote", "10") == 0 && out.toString().contains("suggested ceiling")
                    && cli(out, "--rpc", url, "rate") == 0 && out.toString().contains("oracle"));

            section("Error messages found by the usability test");
            cli(out, "--rpc", url, "--wallet", wfile, "--plain-wallet", "send", bob.address, "999999");
            check("overspend names the cause and the amounts, not a list of guesses",
                    out.toString().contains("insufficient funds") && out.toString().contains("available") && !out.toString().contains("duplicate, or mempool"));
            check("a missing argument names the command and shows its usage",
                    cli(out, "--rpc", url, "--wallet", wfile, "--plain-wallet", "send") == 2
                    && out.toString().contains("missing argument for 'send'") && out.toString().contains("usage: send <address>"));
            check("status is readable: a rate line and a formatted supply, not raw fields",
                    cli(out, "--rpc", url, "status") == 0 && out.toString().contains("rate:") && out.toString().contains("GBU")
                    && !out.toString().contains("supplyFmt") && !out.toString().contains("score: "));
            check("a bad address says what an address is",
                    cli(out, "--rpc", url, "--wallet", wfile, "--plain-wallet", "send", "nothex", "1") == 2 && out.toString().contains("40 hexadecimal"));
            check("an unreachable node says where it looked, never 'null'",
                    cli(out, "--rpc", "http://127.0.0.1:9", "status") == 1
                    && out.toString().contains("cannot reach the node at http://127.0.0.1:9") && !out.toString().contains("null"));
            check("no arguments lists every command, one per line",
                    cli(out) == 2 && out.toString().contains("sendgbu <address>") && out.toString().contains("publishers"));

            section("RPC: hostile input");
            Rpc hostile = new Rpc(node, "127.0.0.1", 0);      // own server => own rate-limit bucket, so earlier calls don't interfere
            hostile.start();
            String hurl = "http://127.0.0.1:" + hostile.port();
            check("unknown path is 404", http(hc, "GET", hurl + "/v1/nothing", null).statusCode() == 404);
            check("wrong method is 405", http(hc, "DELETE", hurl + "/v1/status", null).statusCode() == 405);
            check("bad address is 400", http(hc, "GET", hurl + "/v1/account/xyz", null).statusCode() == 400);
            check("bad tx id is 400", http(hc, "GET", hurl + "/v1/tx/zz", null).statusCode() == 400);
            check("unknown block is 404", http(hc, "GET", hurl + "/v1/block/9999", null).statusCode() == 404);
            check("quote without amount is 400", http(hc, "GET", hurl + "/v1/quote", null).statusCode() == 400);
            check("quote with a fraction of a speso is 400", http(hc, "GET", hurl + "/v1/quote?gbu=0.0001", null).statusCode() == 400);
            check("POST of garbage is 400", http(hc, "POST", hurl + "/v1/tx", "not json").statusCode() == 400);
            check("POST with a missing field is 400", http(hc, "POST", hurl + "/v1/tx", "{}").statusCode() == 400);
            check("POST of a malformed transaction is 400", http(hc, "POST", hurl + "/v1/tx", "{\"tx\":\"a|b\"}").statusCode() == 400);
            Transaction good = me.pay(bob.address, SM, Params.MIN_FEE, l.nextSeq(me.address));
            String forged = good.encode().replace("|" + SM + "|", "|" + 90 * SM + "|");
            check("POST of a tampered (bad signature) transaction is 400", http(hc, "POST", hurl + "/v1/tx", Json.write(Json.obj("tx", forged))).statusCode() == 400);
            check("POST of a body over 64 KB is 413", http(hc, "POST", hurl + "/v1/tx", "{\"tx\":\"" + "A".repeat(Rpc.MAX_BODY + 10) + "\"}").statusCode() == 413);
            check("the node still serves afterwards", http(hc, "GET", hurl + "/v1/status", null).statusCode() == 200);
            hostile.stop();

            section("RPC: rate limit and client safety");
            Rpc r2 = new Rpc(node, "127.0.0.1", 0);
            r2.start();
            int limited = 0;
            for (int i = 0; i < 120; i++) if (http(hc, "GET", "http://127.0.0.1:" + r2.port() + "/v1/status", null).statusCode() == 429) limited++;
            r2.stop();
            check("a client hammering the RPC gets 429s", limited > 0);

            HttpServer liar = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            liar.createContext("/", ex -> {
                byte[] b = "{\"network\":\"othernet\",\"height\":1}".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, b.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(b); }
                ex.close();
            });
            liar.start();
            int rc = cli(out, "--rpc", "http://127.0.0.1:" + liar.getAddress().getPort(), "--wallet", wfile, "--plain-wallet", "send", bob.address, "1");
            liar.stop(0);
            check("the client refuses to sign for a node on another network", rc == 2 && out.toString().contains("network"));
            check("an unreachable node is a clean error, not a crash", cli(out, "--rpc", "http://127.0.0.1:1", "status") == 1);
            check("the wallet file never leaves the machine: RPC has no endpoint that takes a key",
                    http(hc, "POST", url + "/v1/wallet", "{}").statusCode() == 404);
        } finally {
            rpc.stop();
        }
    }
}

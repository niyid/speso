package com.techducat.speso;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.time.Year;
import java.util.*;
import java.util.function.Consumer;

/**
 * Automated publisher feed: pulls world aggregates from the World Bank and the IMF, cross-checks
 * them, and writes the five-indicator file that Node's reporter publishes (the format of indicators.txt).
 *
 * RULES (all of them err toward publishing NOTHING rather than a doubtful number):
 *  - For an indicator configured with both sources, BOTH must answer, for the SAME year (the latest
 *    year both have, no later than last year), and must agree within the indicator's tolerance.
 *    The published value is their mean. A source that is down, empty, ambiguous or in disagreement
 *    means no file is written. (Silence is safe: the oracle freezes without a publisher quorum.)
 *  - An indicator configured with ONE source uses that source's latest value, with a note.
 *  - Data older than maxLag years is refused.
 *  - "stress" has no World Bank / IMF series: it is held at the baseline (contributes zero to the score)
 *    unless the config gives a fixed number. This is a placeholder, see README.
 *
 * Sources differ in method (the World Bank weights by market exchange rates, the IMF by purchasing
 * power), so tolerances are wide. The cross-check catches errors and outages, not small differences.
 *
 * Endpoints (override with system properties or the config file; both APIs need no key):
 *   World Bank  {wb}/country/WLD/indicator/{code}?format=json&per_page=100&date=Y0:Y1
 *   IMF WEO     {imf}/data/dataflow/IMF.RES/WEO/~/{world}.{code}.A   Accept: text/csv
 */
final class Feed {
    static final class FeedException extends Exception {
        FeedException(String m) { super(m); }
    }

    static final class Config {
        String wbBase = System.getProperty("speso.feed.wb", "https://api.worldbank.org/v2");
        String imfBase = System.getProperty("speso.feed.imf", "https://api.imf.org/external/sdmx/3.0");
        String imfWorld = "G001";
        int maxLag = 3;                                           // years behind "last year" that we still accept
        long stress = EconomyIndex.BASELINE[4];                   // hundredths
        // index: gdp, inflation, unemployment, trade, stress (stress has no source)
        final String[] wb  = {"NY.GDP.MKTP.KD.ZG", "FP.CPI.TOTL.ZG", "SL.UEM.TOTL.ZS", "NE.EXP.GNFS.KD.ZG", null};
        final String[] imf = {"NGDP_RPCH", "PCPIPCH", null, "TX_RPCH", null};
        final double[] tol = {1.00, 2.00, 0, 3.00, 0};            // percentage points

        /** Properties file. Keys: wb.base imf.base imf.world maxlag stress, and <name>.wb / .imf / .tol per indicator. */
        static Config load(String file) throws IOException {
            Config c = new Config();
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(Paths.get(file))) { p.load(in); }
            c.wbBase = p.getProperty("wb.base", c.wbBase).trim();
            c.imfBase = p.getProperty("imf.base", c.imfBase).trim();
            c.imfWorld = p.getProperty("imf.world", c.imfWorld).trim();
            c.maxLag = Integer.parseInt(p.getProperty("maxlag", String.valueOf(c.maxLag)).trim());
            String st = p.getProperty("stress", "baseline").trim();
            if (!st.equals("baseline")) c.stress = Math.round(Double.parseDouble(st) * 100);
            for (int i = 0; i < 4; i++) {
                String n = EconomyIndex.NAMES[i];
                if (p.containsKey(n + ".wb")) c.wb[i] = emptyToNull(p.getProperty(n + ".wb"));
                if (p.containsKey(n + ".imf")) c.imf[i] = emptyToNull(p.getProperty(n + ".imf"));
                if (p.containsKey(n + ".tol")) c.tol[i] = Double.parseDouble(p.getProperty(n + ".tol").trim());
            }
            return c;
        }

        private static String emptyToNull(String s) { s = s.trim(); return s.isEmpty() ? null : s; }
    }

    static final class Result {
        final long[] indicators;
        final List<String> notes;
        Result(long[] i, List<String> n) { indicators = i; notes = n; }
    }

    private final Config cfg;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final int MAX_BODY = 8 << 20;

    Feed(Config cfg) { this.cfg = cfg; }

    // ------------------------------------------------------------------ fetching

    private String get(String url, String accept) throws IOException {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(25))
                    .header("Accept", accept).header("User-Agent", "speso-feed/1").GET().build();
            HttpResponse<InputStream> r = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (InputStream in = r.body()) { body = in.readNBytes(MAX_BODY + 1); }
            if (body.length > MAX_BODY) throw new IOException("reply too large from " + url);
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " from " + url);
            return new String(body, StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        } catch (IOException e) {
            // ConnectException, timeouts etc. often carry no message at all: say where and what, never "null"
            if (e.getMessage() != null && e.getMessage().startsWith("HTTP ")) throw e;
            throw new IOException("cannot reach " + url.replaceAll("\\?.*", "") + " (" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()) + ")");
        }
    }

    private TreeMap<Integer, Double> worldBank(String code, int lastYear) throws IOException, FeedException {
        String url = cfg.wbBase + "/country/WLD/indicator/" + code + "?format=json&per_page=100&date=" + (lastYear - 15) + ":" + (lastYear + 1);
        return parseWorldBank(get(url, "application/json"));
    }

    private TreeMap<Integer, Double> imf(String code) throws IOException, FeedException {
        String url = cfg.imfBase + "/data/dataflow/IMF.RES/WEO/~/" + cfg.imfWorld + "." + code + ".A";
        return parseImf(get(url, "text/csv"));
    }

    // ------------------------------------------------------------------ parsing (static: tested against fixtures)

    /** World Bank JSON: [ {paging...}, [ {"date":"2023","value":2.6,...}, ... ] ]. Null values are skipped. */
    static TreeMap<Integer, Double> parseWorldBank(String body) throws FeedException {
        Object o;
        try { o = Json.parse(body); } catch (IllegalArgumentException e) { throw new FeedException("World Bank sent unparseable data"); }
        if (!(o instanceof List<?> top) || top.size() < 2) throw new FeedException("World Bank returned an error or no data");
        TreeMap<Integer, Double> out = new TreeMap<>();
        if (top.get(1) == null) return out;
        if (!(top.get(1) instanceof List<?> rows)) throw new FeedException("World Bank reply has an unexpected shape");
        for (Object r : rows) {
            if (!(r instanceof Map<?, ?> m) || !(m.get("value") instanceof Number v) || !(m.get("date") instanceof String d)) continue;
            if (!d.matches("\\d{4}")) continue;
            int y = Integer.parseInt(d);
            if (out.containsKey(y) && Math.abs(out.get(y) - v.doubleValue()) > 1e-9) throw new FeedException("World Bank returned two values for " + y);
            out.put(y, v.doubleValue());
        }
        return out;
    }

    /** SDMX-CSV: header with TIME_PERIOD and OBS_VALUE columns. Annual rows only. Conflicting duplicates are refused. */
    static TreeMap<Integer, Double> parseImf(String csv) throws FeedException {
        String[] lines = csv.split("\\r?\\n");
        if (lines.length < 1 || lines[0].isBlank()) throw new FeedException("IMF returned nothing");
        List<String> head = splitCsv(lines[0]);
        int tp = -1, ov = -1;
        for (int i = 0; i < head.size(); i++) {
            String h = head.get(i).trim().toUpperCase(Locale.ROOT);
            if (h.startsWith("TIME_PERIOD") && tp < 0) tp = i;
            if (h.startsWith("OBS_VALUE") && ov < 0) ov = i;
        }
        if (tp < 0 || ov < 0) throw new FeedException("IMF reply has no TIME_PERIOD / OBS_VALUE columns");
        TreeMap<Integer, Double> out = new TreeMap<>();
        for (int li = 1; li < lines.length; li++) {
            if (lines[li].isBlank()) continue;
            List<String> f = splitCsv(lines[li]);
            if (f.size() <= Math.max(tp, ov)) continue;
            String t = f.get(tp).trim(), v = f.get(ov).trim();
            if (!t.matches("\\d{4}") || v.isEmpty()) continue;
            double d;
            try { d = Double.parseDouble(v); } catch (NumberFormatException e) { continue; }
            if (Double.isNaN(d) || Double.isInfinite(d)) continue;
            int y = Integer.parseInt(t);
            if (out.containsKey(y) && Math.abs(out.get(y) - d) > 1e-9)
                throw new FeedException("IMF returned several different series for " + y + " (the series key is ambiguous)");
            out.put(y, d);
        }
        return out;
    }

    static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (q) {
                if (c == '"') { if (i + 1 < line.length() && line.charAt(i + 1) == '"') { sb.append('"'); i++; } else q = false; }
                else sb.append(c);
            } else if (c == '"') q = true;
            else if (c == ',') { out.add(sb.toString()); sb.setLength(0); }
            else sb.append(c);
        }
        out.add(sb.toString());
        return out;
    }

    // ------------------------------------------------------------------ decision

    /** Fetch, cross-check, and return the reading, or throw with EVERY problem found. */
    Result fetch(int lastYear) throws FeedException {
        long[] ind = new long[EconomyIndex.N];
        List<String> notes = new ArrayList<>(), problems = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String name = EconomyIndex.NAMES[i];
            try {
                TreeMap<Integer, Double> a = cfg.wb[i] == null ? null : worldBank(cfg.wb[i], lastYear);
                TreeMap<Integer, Double> b = cfg.imf[i] == null ? null : imf(cfg.imf[i]);
                if (a == null && b == null) throw new FeedException("no source configured");
                int year; double value;
                if (a != null && b != null) {
                    Integer y = latestCommon(a, b, lastYear);
                    if (y == null) throw new FeedException("the sources have no year in common up to " + lastYear);
                    double d = Math.abs(a.get(y) - b.get(y));
                    if (d > cfg.tol[i] + 1e-9)
                        throw new FeedException("sources disagree for " + y + ": World Bank " + a.get(y) + " vs IMF " + b.get(y) + " (tolerance " + cfg.tol[i] + ")");
                    year = y; value = (a.get(y) + b.get(y)) / 2;
                    notes.add(name + " " + y + ": World Bank " + a.get(y) + ", IMF " + b.get(y) + " -> " + value);
                } else {
                    TreeMap<Integer, Double> only = a != null ? a : b;
                    Map.Entry<Integer, Double> e = only.floorEntry(lastYear);
                    if (e == null) throw new FeedException("no value up to " + lastYear);
                    year = e.getKey(); value = e.getValue();
                    notes.add(name + " " + year + ": " + (a != null ? "World Bank" : "IMF") + " only -> " + value);
                }
                if (lastYear - year > cfg.maxLag) throw new FeedException("latest data is from " + year + ", older than " + cfg.maxLag + " years");
                ind[i] = Math.round(value * 100);
            } catch (FeedException | IOException e) {
                problems.add(name + ": " + e.getMessage());
            }
        }
        ind[4] = cfg.stress;
        notes.add("stress: " + (cfg.stress == EconomyIndex.BASELINE[4] ? "held at baseline (no source)" : "fixed at " + cfg.stress / 100.0));
        if (problems.isEmpty() && !EconomyIndex.validReport(ind)) problems.add("the combined reading is outside the allowed ranges: " + EconomyIndex.encode(ind));
        if (!problems.isEmpty()) throw new FeedException(String.join("; ", problems));
        return new Result(ind, notes);
    }

    static Integer latestCommon(TreeMap<Integer, Double> a, TreeMap<Integer, Double> b, int lastYear) {
        for (int y : a.headMap(lastYear, true).descendingKeySet()) if (b.containsKey(y)) return y;
        return null;
    }

    // ------------------------------------------------------------------ output

    /** Write atomically; the file's mtime is what Node uses to detect a stale feed. */
    static void write(Result r, Path out) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("# written by speso Feed at " + Instant.now());
        for (String n : r.notes) lines.add("# " + n.replace('=', ':'));
        for (int i = 0; i < EconomyIndex.N; i++) lines.add(EconomyIndex.NAMES[i] + "=" + BigDecimal.valueOf(r.indicators[i], 2).toPlainString());
        Path dir = out.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "feed", ".tmp");
        Files.write(tmp, lines, StandardCharsets.UTF_8);
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    // ------------------------------------------------------------------ daemon

    /** Re-fetches every intervalMs (retrying sooner after a failure) and rewrites the file on success only. */
    static final class Updater implements Runnable {
        private final Config cfg; private final Path out; private final long intervalMs; private final Consumer<String> log;
        private volatile boolean running = true;
        Updater(Config cfg, Path out, long intervalMs, Consumer<String> log) { this.cfg = cfg; this.out = out; this.intervalMs = intervalMs; this.log = log; }

        void stop() { running = false; }

        @Override public void run() {
            while (running) {
                long wait = intervalMs;
                try {
                    Result r = new Feed(cfg).fetch(Year.now().getValue() - 1);
                    write(r, out);
                    log.accept("feed updated: " + EconomyIndex.describe(r.indicators));
                } catch (FeedException | IOException e) {
                    log.accept("feed NOT updated (" + e.getMessage() + ")");
                    wait = Math.min(intervalMs, 15 * 60 * 1000L);
                }
                try { Thread.sleep(wait); } catch (InterruptedException e) { return; }
            }
        }

        Thread start() {
            Thread t = new Thread(this, "feed");
            t.setDaemon(true);
            t.start();
            return t;
        }
    }

    // ------------------------------------------------------------------ one-shot tool

    /** java -cp out com.techducat.speso.Feed [--config file] [--out indicators.txt] [--probe] */
    public static void main(String[] a) throws Exception {
        String conf = null, out = "indicators.txt";
        boolean probe = false;
        for (int i = 0; i < a.length; i++) {
            switch (a[i]) {
                case "--config" -> conf = a[++i];
                case "--out" -> out = a[++i];
                case "--probe" -> probe = true;
                default -> { System.err.println("usage: Feed [--config file] [--out file] [--probe]"); System.exit(2); }
            }
        }
        Config c = conf == null ? new Config() : Config.load(conf);
        try {
            Result r = new Feed(c).fetch(Year.now().getValue() - 1);
            for (String n : r.notes) System.out.println("  " + n);
            System.out.println("=> " + EconomyIndex.describe(r.indicators));
            if (!probe) { write(r, Paths.get(out)); System.out.println("wrote " + out); }
        } catch (FeedException e) {
            System.err.println("NOT publishing: " + e.getMessage());
            System.exit(1);
        }
    }
}

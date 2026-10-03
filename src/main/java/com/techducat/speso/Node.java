package com.techducat.speso;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * P2P node: server and client in one. One TCP connection = one request.
 *
 * HANDSHAKE (every connection, both directions)
 *   server -> CHAL <random nonce>
 *   client -> AUTH <pubkey> <signature of "speso-auth|nonce"> <client's listening port>
 *   client -> <request line>
 * The signature proves the caller holds the private key behind its node identity (a
 * keypair kept in <datadir>/node.key). That gives every peer a stable cryptographic ID
 * we can ban or allow-list. Note what it does NOT do: it doesn't encrypt anything, and
 * a Sybil attacker can mint unlimited identities, which is why limits are also per IP.
 * (Nothing on the wire needs secrecy or sender-trust: transactions are signed and blocks
 * are self-validating proof-of-work. The real threat is resource exhaustion.)
 *
 * REQUESTS                                   REPLY (lines, then END)
 *   HELLO                                     known peers, "host:port" each
 *   TX <encoded>                              OK | NO
 *   BLOCK <encoded>                           OK | NO
 *   TIP                                       "height hash work"
 *   LOCATE <hash,hash,...>                    index of first hash on our main chain
 *   GETBLOCKS <fromIndex> <max>               encoded blocks (max 100)
 *
 * DoS PROTECTION: bounded line length, bounded reply size, socket timeouts, bounded thread
 * pools (excess work is dropped, never queued without limit), a token bucket per IP,
 * ban scores per IP and per peer ID (invalid data costs points; 100 points = 10 min ban),
 * a peer-count cap, and mempool limits in the Ledger. Validation order in the Ledger is
 * cheapest-first (proof-of-work before signatures before state replay).
 */
final class Node {
    static final int MAX_LINE = 512 * 1024, MAX_PEERS = 32, BAN_THRESHOLD = 100, MAX_BATCH = 100;
    static final long BAN_MS = 10 * 60 * 1000L;

    final Ledger ledger;
    final int port;
    final Wallet identity;
    final Store store;                                        // may be null (no persistence)
    private final Consumer<String> log;
    private final Set<String> peers = ConcurrentHashMap.newKeySet();
    private final Set<String> allow = ConcurrentHashMap.newKeySet();           // empty = open network
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Map<String, Integer> penalties = new ConcurrentHashMap<>();
    private final Map<String, Long> bans = new ConcurrentHashMap<>();
    private final ExecutorService handlers = pool(32, 64, true);
    private final ExecutorService outbound = pool(8, 256, false);
    private volatile boolean running = true, mining = false, reporting = false, publishing = false;
    private ServerSocket server;

    Node(Ledger ledger, int port, Wallet identity, Store store, Consumer<String> log) {
        this.ledger = ledger; this.port = port; this.identity = identity; this.store = store; this.log = log;
        if (store != null) for (String p : store.readPeers()) if (peers.size() < MAX_PEERS) peers.add(p);
    }

    Set<String> peers() { return peers; }
    void allowOnly(String peerId) { allow.add(peerId); }
    boolean isBanned(String key) {
        Long until = bans.get(key);
        if (until == null) return false;
        if (System.currentTimeMillis() > until) { bans.remove(key); return false; }
        return true;
    }

    // ------------------------------------------------------------------ plumbing

    private static ExecutorService pool(int threads, int queue, boolean abort) {
        return new ThreadPoolExecutor(threads, threads, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(queue),
                r -> { Thread t = new Thread(r, "node-pool"); t.setDaemon(true); return t; },
                abort ? new ThreadPoolExecutor.AbortPolicy() : new ThreadPoolExecutor.DiscardPolicy());
    }

    private static void daemon(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    /** readLine with a hard length cap, so a peer can't make us buffer gigabytes. Null at EOF. */
    static String readLine(Reader in, int max) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return sb.toString();
            if (c == '\r') continue;
            if (sb.length() >= max) throw new IOException("line too long");
            sb.append((char) c);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static final class Bucket {       // token bucket: 100 burst, 50 requests/sec sustained
        double tokens = 100; long last = System.nanoTime();
        synchronized boolean take(double cost) {
            long now = System.nanoTime();
            tokens = Math.min(100, tokens + (now - last) / 1e9 * 50);
            last = now;
            if (tokens < cost) return false;
            tokens -= cost;
            return true;
        }
    }

    private boolean allowRate(String ip, double cost) {
        if (buckets.size() > 10_000) buckets.clear();         // crude memory bound
        return buckets.computeIfAbsent(ip, k -> new Bucket()).take(cost);
    }

    /** Add misbehaviour points against an IP and/or peer ID; ban at the threshold. */
    void punish(String ip, String id, int points) {
        for (String key : new String[]{ip, id}) {
            if (key == null) continue;
            int v = penalties.merge(key, points, Integer::sum);
            if (v >= BAN_THRESHOLD) {
                penalties.remove(key);
                bans.put(key, System.currentTimeMillis() + BAN_MS);
                log.accept("BANNED " + key + " for 10 minutes");
            }
        }
    }

    // ------------------------------------------------------------------ server side

    void start() throws IOException {
        server = new ServerSocket(port);
        daemon("accept", () -> {
            while (running) {
                try {
                    Socket s = server.accept();
                    try { handlers.execute(() -> handle(s)); }
                    catch (RejectedExecutionException e) { s.close(); }      // overloaded: shed load
                } catch (IOException e) { if (running) log.accept("accept error: " + e); }
            }
        });
        daemon("resync", () -> {                  // safety net in case gossip was missed
            while (running) { sleep(15_000); sync(); }
        });
        log.accept("listening on port " + port + "  node id " + identity.address);
    }

    void stop() {
        running = false; mining = false; reporting = false; publishing = false;
        try { server.close(); } catch (IOException ignored) {}
        handlers.shutdownNow(); outbound.shutdownNow();
    }

    private void handle(Socket s) {
        String ip = s.getInetAddress().getHostAddress(), id = null;
        try (s;
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
             PrintWriter out = new PrintWriter(s.getOutputStream(), true)) {
            s.setSoTimeout(5000);
            if (isBanned(ip) || !allowRate(ip, 1)) return;

            String nonce = Crypto.randomHex(16);
            out.println("CHAL " + nonce);
            String authLine = readLine(in, 2048);
            String[] a = authLine == null ? new String[0] : authLine.split(" ");
            if (a.length != 4 || !a[0].equals("AUTH") || !Crypto.verify(a[1], "speso-auth|" + nonce, a[2])) {
                punish(ip, null, 30);
                return;
            }
            id = Crypto.address(a[1]);
            if (isBanned(id) || (!allow.isEmpty() && !allow.contains(id))) return;

            String line = readLine(in, MAX_LINE);
            if (line == null) return;
            int sp = line.indexOf(' ');
            String cmd = sp < 0 ? line : line.substring(0, sp);
            String arg = sp < 0 ? "" : line.substring(sp + 1);

            switch (cmd) {
                case "HELLO" -> {
                    addPeer(ip + ":" + a[3]);        // port is self-reported (not verified); peers are capped
                    int n = 0;
                    for (String p : peers) { if (n++ >= MAX_PEERS) break; out.println(p); }
                }
                case "TX" -> {
                    Transaction t = Transaction.decode(arg);
                    Ledger.TxResult r = ledger.addTx(t);
                    if (r == Ledger.TxResult.ACCEPTED) { log.accept("tx accepted: " + t); gossip("TX " + arg); }
                    else if (r == Ledger.TxResult.INVALID) punish(ip, id, 20);
                    out.println(r == Ledger.TxResult.ACCEPTED ? "OK" : "NO");
                }
                case "BLOCK" -> {
                    Block b = Block.decode(arg);
                    Ledger.BlockResult r = ledger.addBlock(b);
                    if (r == Ledger.BlockResult.ACCEPTED) {
                        log.accept("block " + b.index + " accepted from network");
                        gossip("BLOCK " + arg);
                    } else if (r == Ledger.BlockResult.INVALID) {
                        punish(ip, id, 50);
                    } else if (b.index > ledger.height()) {
                        outbound.execute(this::sync);       // we're behind or on a fork
                    }
                    out.println(r == Ledger.BlockResult.ACCEPTED ? "OK" : "NO");
                }
                case "TIP" -> out.println(ledger.height() + " " + ledger.tip().hash + " " + ledger.work());
                case "LOCATE" -> {
                    String[] hs = arg.split(",");
                    if (hs.length > 64) { punish(ip, id, 20); return; }
                    out.println(ledger.locate(Arrays.asList(hs)));
                }
                case "GETBLOCKS" -> {
                    if (!allowRate(ip, 5)) { punish(ip, id, 10); return; }      // expensive: costs more tokens
                    String[] p = arg.split(" ");
                    int from = Integer.parseInt(p[0]), max = Math.min(Integer.parseInt(p[1]), MAX_BATCH);
                    for (Block b : ledger.blocksFrom(from, max)) out.println(b.encode());
                }
                default -> punish(ip, id, 10);
            }
            out.println("END");
        } catch (IOException e) {
            // timeouts and dropped connections are normal; not misbehaviour
        } catch (RuntimeException e) {
            punish(ip, id, 30);                      // malformed payload
        }
    }

    // ------------------------------------------------------------------ client side

    List<String> request(String peer, String msg) throws IOException {
        int c = peer.lastIndexOf(':');
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(peer.substring(0, c), Integer.parseInt(peer.substring(c + 1))), 2000);
            s.setSoTimeout(15_000);
            PrintWriter out = new PrintWriter(s.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
            String chal = readLine(in, 256);
            if (chal == null || !chal.startsWith("CHAL ")) throw new IOException("bad handshake");
            out.println("AUTH " + identity.pubB64 + " " + Crypto.sign(identity.priv, "speso-auth|" + chal.substring(5)) + " " + port);
            out.println(msg);
            List<String> reply = new ArrayList<>();
            String l;
            while ((l = readLine(in, MAX_LINE)) != null && !l.equals("END")) {
                reply.add(l);
                if (reply.size() > 1000) throw new IOException("reply too long");
            }
            return reply;
        }
    }

    private void addPeer(String p) {
        try {
            int c = p.lastIndexOf(':');
            String host = InetAddress.getByName(p.substring(0, c)).getHostAddress();
            int pt = Integer.parseInt(p.substring(c + 1));
            if (InetAddress.getByName(host).isLoopbackAddress() && pt == port) return;   // that's us
            if (peers.size() >= MAX_PEERS) return;
            if (peers.add(host + ":" + pt)) {
                log.accept("new peer " + host + ":" + pt);
                if (store != null) store.writePeers(peers);
            }
        } catch (Exception ignored) {}
    }

    /** Join the network through one known peer. */
    void connect(String peer) {
        addPeer(peer);
        try {
            for (String p : request(peer, "HELLO")) addPeer(p);
        } catch (IOException e) { log.accept("could not reach " + peer + ": " + e.getMessage()); }
        sync();
    }

    private void gossip(String msg) {
        for (String p : peers)
            outbound.execute(() -> {
                try { request(p, msg); } catch (IOException e) { peers.remove(p); }
            });
    }

    // ------------------------------------------------------------------ sync

    void sync() { for (String p : peers) syncWith(p); }

    /**
     * Incremental sync: compare total work; if the peer is ahead, find our newest common block
     * with a locator, then download only what we lack, 100 blocks at a time. If the peer's branch
     * forks from ours, collect it all and let the Ledger decide via reorg().
     */
    void syncWith(String p) {
        try {
            String[] tip = request(p, "TIP").get(0).split(" ");
            if (new java.math.BigInteger(tip[2]).compareTo(ledger.work()) <= 0) return;   // not ahead of us
            int peerHeight = Integer.parseInt(tip[0]);

            int anc = Integer.parseInt(request(p, "LOCATE " + String.join(",", ledger.locator())).get(0));
            boolean fastForward = anc == ledger.height();
            List<Block> pending = new ArrayList<>();
            int next = anc + 1;
            while (next <= peerHeight && next - anc <= 50_000) {
                List<String> lines = request(p, "GETBLOCKS " + next + " " + MAX_BATCH);
                if (lines.isEmpty()) break;
                for (String l : lines) {
                    Block b = Block.decode(l);
                    if (b.index != next) { log.accept("sync: " + p + " sent out-of-order block"); return; }
                    if (fastForward) {
                        if (ledger.addBlock(b) != Ledger.BlockResult.ACCEPTED) { log.accept("sync: bad block from " + p); return; }
                    } else pending.add(b);
                    next++;
                }
            }
            if (!fastForward && !pending.isEmpty() && ledger.reorg(anc, pending))
                log.accept("reorganised onto " + p + "'s chain, height now " + ledger.height());
            else if (fastForward && next > anc + 1)
                log.accept("synced from " + p + ", height now " + ledger.height());
        } catch (Exception e) { /* unreachable or garbage; try the next peer */ }
    }

    /** Record locally and gossip a transaction. Returns null on success, else the reason. */
    String submit(Transaction t) {
        Ledger.TxResult r = ledger.addTx(t);
        if (r != Ledger.TxResult.ACCEPTED)
            return "rejected (" + (r == Ledger.TxResult.INVALID ? "invalid or fee too low"
                    : "wrong sequence, insufficient funds, duplicate, or mempool full") + ")";
        gossip("TX " + t.encode());
        return null;
    }

    // ------------------------------------------------------------------ mining and reporting

    void startMining(Wallet w, long pauseMs) {
        if (mining) return;
        mining = true;
        daemon("miner", () -> {
            while (mining) {
                Block b = ledger.mine(w.address);
                if (b != null && ledger.addBlock(b) == Ledger.BlockResult.ACCEPTED) {
                    log.accept("MINED block " + b.index + " (" + b.txs.size() + " txs) bits=" + b.bits
                            + " score=" + b.score / 100.0 + " supply=" + Params.show(ledger.supply()));
                    gossip("BLOCK " + b.encode());
                    if (pauseMs > 0) sleep(pauseMs);
                }
            }
        });
    }

    void stopMining() { mining = false; }
    boolean isMining() { return mining; }

    /**
     * Act as an oracle reporter: whenever the local feed file changes (or the old report is about
     * to expire) publish a signed report. Needs a balance: it is your voting weight.
     */
    void startReporter(Wallet w, String feedFile) {
        if (reporting) return;
        reporting = true;
        daemon("reporter", () -> {
            long[] last = null;
            int lastHeight = -1;
            while (reporting) {
                sleep(2000);
                long[] f = EconomyIndex.readFeed(feedFile);
                if (f == null) continue;
                int h = ledger.height();
                if (last != null && Arrays.equals(f, last) && h - lastHeight < Params.ORACLE_WINDOW / 2) continue;
                if (ledger.balance(w.address) < Params.MIN_FEE) continue;
                String err = submit(w.report(f, Params.MIN_FEE, ledger.nextSeq(w.address)));
                if (err == null) {
                    last = f; lastHeight = h;
                    log.accept("published oracle report: " + EconomyIndex.describe(f));
                }
            }
        });
    }

    void stopReporting() { reporting = false; }

    /**
     * Act as a data PUBLISHER (this wallet's address must be in Params.PUBLISHERS): sign the feed as an
     * attestation whenever it changes or the old one is about to expire, and the optional "market="
     * line as a market quote. Needs no balance: publishers are a role, not a stake.
     */
    void startPublisher(Wallet w, String feedFile) {
        if (publishing) return;
        if (!Params.isPublisher(w.address)) throw new IllegalStateException("this wallet is not in the network's publisher set");
        publishing = true;
        daemon("publisher", () -> {
            long[] last = null;
            long lastQuote = 0;
            int lastHeight = -1;
            while (publishing) {
                sleep(2000);
                long[] f = EconomyIndex.readFeed(feedFile);
                if (f == null) continue;
                long q = EconomyIndex.readQuote(feedFile);
                int h = ledger.height();
                boolean fresh = last != null && Arrays.equals(f, last) && q == lastQuote && h - lastHeight < Params.ORACLE_WINDOW / 2;
                if (fresh) continue;
                String err = submit(w.attest(f, 0, ledger.nextSeq(w.address)));
                if (err == null && q > 0) err = submit(w.quote(q, 0, ledger.nextSeq(w.address)));
                if (err == null) {
                    last = f; lastQuote = q; lastHeight = h;
                    log.accept("published attestation: " + EconomyIndex.describe(f) + (q > 0 ? "  market=" + q / 10000.0 : ""));
                }
            }
        });
    }

    void stopPublishing() { publishing = false; }
}

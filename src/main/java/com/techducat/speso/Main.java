package com.techducat.speso;

import java.util.*;

/**
 * java -cp out com.techducat.speso.Main --i-understand-unaudited [--port 7000] [--data dir] [--wallet file]
 *                   [--peer host:port]... [--mine] [--feed indicators.txt] [--publish indicators.txt]
 *                   [--allow peerId]... [--plain-wallet] [--rpc port [--rpc-bind addr]]
 *                   [--auto-feed [--feed-config file] [--feed-hours 6]]
 *
 * Network: -Dspeso.network=testnet (default) | mainnet.  Publishers: -Dspeso.publishers=addr,addr,addr [-Dspeso.pubthreshold=2].
 * mainnet refuses to start without publishers. RPC (for the Cli thin wallet) binds 127.0.0.1 unless --rpc-bind says otherwise.
 * --auto-feed fetches World Bank + IMF data into the feed file (see Feed) and publishes it: as a PUBLISHER if this
 * wallet is in the publisher set, else as a stake REPORTER.
 *
 * Wallet password: env SPESO_PASSWORD, or typed at the prompt. --plain-wallet stores the key unencrypted.
 * --feed makes this node a stake REPORTER: it ratifies or vetoes (needs a balance to carry weight).
 * --publish makes this node a data PUBLISHER: it signs the file's readings (this wallet's address must be in
 * -Dspeso.publishers, the same list on every node). The file may also hold "market=1.02" (value of 1 spesmilo in GBU).
 * --i-understand-unaudited is required: this software has not been audited and must not hold anything of value.
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        int port = 7000;
        String dataDir = null, walletFile = null, feed = null, publish = null;
        boolean mine = false, plain = false, accepted = false, autoFeed = false;
        long pause = 0, feedHours = 6;
        int rpcPort = 0;
        String rpcBind = "127.0.0.1", feedConfig = null;
        List<String> peerArgs = new ArrayList<>(), allowArgs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port"   -> port = Integer.parseInt(args[++i]);
                case "--data"   -> dataDir = args[++i];
                case "--wallet" -> walletFile = args[++i];
                case "--peer"   -> peerArgs.add(args[++i]);
                case "--allow"  -> allowArgs.add(args[++i]);
                case "--feed"   -> feed = args[++i];
                case "--publish" -> publish = args[++i];
                case "--rpc"    -> rpcPort = Integer.parseInt(args[++i]);
                case "--rpc-bind" -> rpcBind = args[++i];
                case "--auto-feed" -> autoFeed = true;
                case "--feed-config" -> feedConfig = args[++i];
                case "--feed-hours" -> feedHours = Long.parseLong(args[++i]);
                case "--i-understand-unaudited" -> accepted = true;
                case "--pause"  -> pause = Long.parseLong(args[++i]);
                case "--mine"   -> mine = true;
                case "--plain-wallet" -> plain = true;
                default -> { System.err.println("unknown option " + args[i]); System.exit(1); }
            }
        }
        if (!accepted) {
            System.err.println("The Speso has NOT been audited by anyone and must not hold anything of real value.");
            System.err.println("Bugs here can lose or mint money, and the price is only as honest as its publishers.");
            System.err.println("Re-run with --i-understand-unaudited to continue (experiments and test networks only).");
            System.exit(1);
        }
        try {
            Params.checkLaunch(Params.NETWORK, !Params.PUBLISHERS.isEmpty(), accepted);
        } catch (IllegalStateException e) { System.err.println(e.getMessage()); System.exit(1); }
        System.out.println(Params.MAINNET
                ? "*** MAINNET - UNAUDITED SOFTWARE. Keep only what you can afford to lose. See SECURITY.md. ***"
                : "*** " + Params.NETWORK.toUpperCase() + ": experimental network. Coins here have no value and must not be treated as money. ***");
        if (Params.PUBLISHERS.isEmpty())
            System.out.println("*** No publishers configured (-Dspeso.publishers=addr,...): the score is FROZEN at baseline. ***");
        System.out.println("network " + Params.NETWORK_ID + ", " + Params.PUBLISHERS.size() + " publisher(s), "
                + Params.PUBLISHER_THRESHOLD + " must agree");
        if (dataDir == null) dataDir = "data-" + port;
        if (walletFile == null) walletFile = dataDir + "/wallet.dat";

        char[] password = null;
        if (!plain) {
            String env = System.getenv("SPESO_PASSWORD");
            if (env != null) password = env.toCharArray();
            else if (System.console() != null) password = System.console().readPassword("wallet password: ");
            if (password == null || password.length == 0) {
                System.err.println("A wallet password is required (set SPESO_PASSWORD or type it), or pass --plain-wallet.");
                return;
            }
        }
        Wallet wallet;
        try {
            wallet = Wallet.loadOrCreate(walletFile, password);
        } catch (java.security.GeneralSecurityException e) {
            System.err.println("cannot open wallet: " + e.getMessage());
            return;
        }
        if (password != null) Arrays.fill(password, '\0');
        Wallet identity = Wallet.loadOrCreate(dataDir + "/node.key", null);

        Store store = new Store(dataDir);
        Ledger ledger = new Ledger(store);
        Node node = new Node(ledger, port, identity, store, m -> System.out.println("[node] " + m));
        for (String id : allowArgs) node.allowOnly(id);
        node.start();
        for (String p : peerArgs) node.connect(p);
        if (!peerArgs.isEmpty() || !node.peers().isEmpty()) node.sync();
        if (mine) node.startMining(wallet, pause);
        Rpc rpc = null;
        if (rpcPort > 0) {
            rpc = new Rpc(node, rpcBind, rpcPort);
            rpc.start();
            System.out.println("RPC on http://" + rpcBind + ":" + rpc.port() + "/v1/  (no TLS, no auth: "
                    + (rpcBind.equals("127.0.0.1") || rpcBind.equals("localhost") ? "local only" : "REACHABLE FROM OUTSIDE - put it behind a proxy") + ")");
        }
        Feed.Updater updater = null;
        long maxAge = 0;
        if (autoFeed) {
            String file = feed != null ? feed : publish != null ? publish : dataDir + "/indicators.txt";
            Feed.Config fc = feedConfig == null ? new Feed.Config() : Feed.Config.load(feedConfig);
            updater = new Feed.Updater(fc, java.nio.file.Paths.get(file), feedHours * 3600_000L, m -> System.out.println("[feed] " + m));
            updater.start();
            maxAge = 2 * feedHours * 3600_000L;       // a feed that stops refreshing goes silent instead of repeating old numbers
            if (feed == null && publish == null) {
                if (Params.isPublisher(wallet.address)) publish = file; else feed = file;
            }
        }
        if (publish != null && !Params.isPublisher(wallet.address)) {
            System.err.println("--publish: this wallet (" + wallet.address + ") is not in -Dspeso.publishers; add it on every node or use --feed");
            node.stop();
            return;
        }
        if (feed != null) node.startReporter(wallet, feed, maxAge);
        if (publish != null) node.startPublisher(wallet, publish, maxAge);

        System.out.println(Params.PUBLISHERS.isEmpty()
                ? "WARNING: no oracle publishers configured - the score is FROZEN at baseline (fail closed)."
                : "oracle publishers: " + Params.PUBLISHERS.size() + ", " + Params.PUBLISHER_THRESHOLD + " must agree"
                  + (Params.isPublisher(wallet.address) ? "   (this wallet IS a publisher)" : ""));
        System.out.println("height " + ledger.height() + "   address: " + wallet.address);
        System.out.println("type 'help' for commands");

        final long pauseMs = pause;
        final Rpc rpcServer = rpc;
        final Feed.Updater feedUpdater = updater;
        Scanner in = new Scanner(System.in);
        while (in.hasNextLine()) {
            String[] c = in.nextLine().trim().split("\\s+");
            if (c[0].isEmpty()) continue;
            try {
                switch (c[0]) {
                    case "help" -> System.out.println(
                        "  me                                  my address\n" +
                        "  balance [address]                   confirmed balance (in spesmiloj)\n" +
                        "  send <addr> <spesmiloj> [fee]       pay (3 decimals max: the speso, 1/1000, is indivisible)\n" +
                        "  sendgbu <addr> <gbu> <max> [fee]    pay a GBU-denominated amount, settled at the chain's rate (max = your cost ceiling)\n" +
                        "  quote <gbu>                         what <gbu> costs right now in spesmiloj, and the worst case after N blocks\n" +
                        "  publishers                          the registered oracle publishers and whether each has a live attestation\n" +
                        "  rate                                protocol reference value, indicators, supply, difficulty, oracle status\n" +
                        "  chain [n]                           last n blocks (default 5)\n" +
                        "  status | peers | connect h:p | sync\n" +
                        "  mine | stopmine\n" +
                        "  quit");
                    case "me" -> System.out.println(wallet.address);
                    case "balance" -> {
                        String a = c.length > 1 ? c[1] : wallet.address;
                        System.out.println(Params.show(ledger.balance(a)) + "   (" + ledger.balance(a) + " spesoj)");
                    }
                    case "send" -> {
                        long fee = c.length > 3 ? Params.parse(c[3]) : Params.MIN_FEE;
                        Transaction t = wallet.pay(c[1], Params.parse(c[2]), fee, ledger.nextSeq(wallet.address));
                        String err = node.submit(t);
                        System.out.println(err == null ? "submitted " + t.id() + " (confirms when mined)" : err);
                    }
                    case "sendgbu" -> {
                        long fee = c.length > 4 ? Params.parse(c[4]) : Params.MIN_FEE;
                        Transaction t = wallet.payGbu(c[1], Params.parse(c[2]), Params.parse(c[3]), fee, ledger.nextSeq(wallet.address));
                        String err = node.submit(t);
                        System.out.println(err == null ? "submitted " + t.id() + " (settles at the rate of the block that mines it)" : err);
                    }
                    case "quote" -> {
                        long gbu = Params.parse(c[1]), score = ledger.tip().score;
                        StringBuilder sb = new StringBuilder(Params.fmt(gbu) + " GBU = " + Params.show(Quote.cost(gbu, score))
                                + " now (rate from block " + ledger.tip().index + ")");
                        for (int n : Quote.HORIZONS)
                            sb.append("\n  worst case in ").append(n).append(" block(s): ").append(Params.show(Quote.worstCost(gbu, score, n)));
                        System.out.println(sb + "\n  suggested ceiling for sendgbu: " + Params.show(Quote.suggestedMax(gbu, score)));
                    }
                    case "publishers" -> {
                        if (Params.PUBLISHERS.isEmpty()) System.out.println("none configured (the score is frozen)");
                        else for (String p : Params.PUBLISHERS) System.out.println(p + (ledger.hasAttestation(p) ? "  live" : "  silent"));
                    }
                    case "rate" -> {
                        Block t = ledger.tip();
                        System.out.printf("score %.2f  =>  protocol reference: 1 " + Params.SYMBOL + " = %.4f GBU (what a market pays may differ)%n", t.score / 100.0, t.score / 10000.0);
                        System.out.println(EconomyIndex.describe(t.indicators));
                        System.out.println("supply " + Params.show(ledger.supply()) + " / target "
                                + Params.show(ledger.effectiveTarget()) + " (score-only target " + Params.show(Params.supplyTarget(t.score)) + ")" + "   difficulty " + t.bits + " bits");
                        System.out.println("burned since genesis " + Params.show(ledger.burned()));
                        System.out.println("oracle: " + ledger.oracleStatus());
                    }
                    case "chain" -> {
                        int n = c.length > 1 ? Integer.parseInt(c[1]) : 5;
                        List<Block> ch = ledger.chain();
                        for (int i = Math.max(0, ch.size() - n); i < ch.size(); i++) {
                            Block b = ch.get(i);
                            System.out.println("#" + b.index + " " + b.hash.substring(0, 12) + " <- " +
                                (b.index == 0 ? "-" : b.prevHash.substring(0, 12)) + "  txs=" + b.txs.size() +
                                "  bits=" + b.bits + "  miner=" + b.miner.substring(0, Math.min(8, b.miner.length())) +
                                "  score=" + b.score / 100.0);
                        }
                    }
                    case "status" -> System.out.println("height " + ledger.height() + ", mempool " + ledger.mempoolSize()
                            + ", peers " + node.peers() + ", mining " + node.isMining() + ", node id " + identity.address);
                    case "peers" -> System.out.println(node.peers());
                    case "connect" -> node.connect(c[1]);
                    case "sync" -> node.sync();
                    case "mine" -> node.startMining(wallet, pauseMs);
                    case "stopmine" -> node.stopMining();
                    case "quit" -> { if (rpcServer != null) rpcServer.stop(); if (feedUpdater != null) feedUpdater.stop(); node.stop(); return; }
                    default -> System.out.println("?");
                }
            } catch (Exception e) {
                System.out.println("error: " + e);
            }
        }
        // stdin closed (daemon, systemd, nohup): keep serving until the process is stopped, instead of shutting down
        System.out.println("console closed: the node keeps running (stop it with SIGTERM or Ctrl-C)");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (rpcServer != null) rpcServer.stop();
            if (feedUpdater != null) feedUpdater.stop();
            node.stop();
        }));
        try { Thread.currentThread().join(); } catch (InterruptedException ignored) { }
    }
}

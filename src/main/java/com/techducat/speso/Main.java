package com.techducat.speso;

import java.util.*;

/**
 * java -cp out com.techducat.speso.Main --i-understand-unaudited [--port 7000] [--data dir] [--wallet file]
 *                   [--peer host:port]... [--mine] [--feed indicators.txt] [--publish indicators.txt]
 *                   [--allow peerId]... [--plain-wallet]
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
        boolean mine = false, plain = false, accepted = false;
        long pause = 0;
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
                case "--i-understand-unaudited" -> accepted = true;
                case "--pause"  -> pause = Long.parseLong(args[++i]);
                case "--mine"   -> mine = true;
                case "--plain-wallet" -> plain = true;
                default -> { System.err.println("unknown option " + args[i]); return; }
            }
        }
        if (!accepted) {
            System.err.println("The Speso has NOT been audited by anyone and must not hold anything of real value.");
            System.err.println("Bugs here can lose or mint money, and the price is only as honest as its publishers.");
            System.err.println("Re-run with --i-understand-unaudited to continue (experiments and test networks only).");
            return;
        }
        System.out.println("*** UNAUDITED SOFTWARE: do not use for anything of real value. ***");
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
        if (feed != null) node.startReporter(wallet, feed);
        if (publish != null) node.startPublisher(wallet, publish);

        System.out.println("height " + ledger.height() + "   address: " + wallet.address);
        System.out.println("type 'help' for commands");

        final long pauseMs = pause;
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
                    case "rate" -> {
                        Block t = ledger.tip();
                        System.out.printf("score %.2f  =>  protocol reference: 1 " + Params.SYMBOL + " = %.4f GBU (what a market pays may differ)%n", t.score / 100.0, t.score / 10000.0);
                        System.out.println(EconomyIndex.describe(t.indicators));
                        System.out.println("supply " + Params.show(ledger.supply()) + " / base target "
                                + Params.show(Params.supplyTarget(t.score)) + "   difficulty " + t.bits + " bits");
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
                    case "quit" -> { node.stop(); return; }
                    default -> System.out.println("?");
                }
            } catch (Exception e) {
                System.out.println("error: " + e);
            }
        }
        node.stop();
    }
}

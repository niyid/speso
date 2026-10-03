package com.techducat.speso;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.*;
import java.util.*;

/**
 * Thin wallet: holds the key, signs locally, and uses a node's RPC for everything else. It runs no
 * chain, mines nothing, and never sends a private key anywhere.
 *
 *   java -cp out com.techducat.speso.Cli [--rpc URL] [--wallet FILE] [--plain-wallet] <command>
 *
 *   new                              create the wallet file (refuses to overwrite)
 *   me                               address (no password needed)
 *   balance [addr]                   balance (no password needed for your own: only the address is read)
 *   send <addr> <spesmiloj> [fee]
 *   sendgbu <addr> <gbu> <max|auto> [fee]    auto = ceiling that survives 3 blocks of the fastest possible fall
 *   quote <gbu>
 *   rate | status | publishers
 *   tx <id>
 *
 * Trust: the node can lie about your balance or sequence number, or drop your transaction, but it
 * cannot spend your money. The client refuses to sign for a node that is on a different network.
 * RPC URL: --rpc, or env SPESO_RPC, default http://127.0.0.1:7100. Password: env SPESO_PASSWORD or prompt.
 */
public final class Cli {
    public static void main(String[] a) {
        System.exit(run(a, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        String rpc = System.getenv().getOrDefault("SPESO_RPC", "http://127.0.0.1:7100");
        String wfile = System.getProperty("user.home") + "/.speso/wallet.dat";
        boolean plain = false;
        List<String> rest = new ArrayList<>();
        final List<String> args0 = rest;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--rpc" -> rpc = args[++i];
                    case "--wallet" -> wfile = args[++i];
                    case "--plain-wallet" -> plain = true;
                    default -> rest.add(args[i]);
                }
            }
            if (rest.isEmpty()) { usage(err); return 2; }
            return exec(rpc, wfile, plain, rest, out);
        } catch (RpcClient.RpcException e) {
            err.println("node said (" + e.status + "): " + e.getMessage());
            return 1;
        } catch (IOException | java.security.GeneralSecurityException e) {
            String m = e.getMessage();
            err.println("error: " + (m == null || e instanceof java.net.ConnectException
                    ? "cannot reach the node at " + rpc + " (is it running with --rpc? set --rpc URL or SPESO_RPC)" : m));
            return 1;
        } catch (IllegalArgumentException | IndexOutOfBoundsException | IllegalStateException e) {
            if (e instanceof IndexOutOfBoundsException && !args0.isEmpty()) {
                String c = args0.get(0);
                err.println("error: missing argument for '" + c + "'" + (USAGE.containsKey(c) ? "\nusage: " + USAGE.get(c) : ""));
            } else err.println("error: " + e.getMessage());
            return 2;
        }
    }

    static final Map<String, String> USAGE = new LinkedHashMap<>();
    private static void u(String cmd, String syntax, String desc) { USAGE.put(cmd, String.format("%-42s%s", syntax, desc)); }
    static {
        u("new", "new", "create the wallet file (refuses to overwrite)");
        u("me", "me", "print your address");
        u("balance", "balance [address]", "confirmed balance");
        u("send", "send <address> <spesmiloj> [fee]", "pay; up to 3 decimals");
        u("sendgbu", "sendgbu <address> <gbu> <max|auto> [fee]", "pay a GBU amount; max = your cost ceiling, auto = safe ceiling");
        u("quote", "quote <gbu>", "cost of <gbu> now and in the worst case");
        u("rate", "rate", "score, indicators, supply, oracle state");
        u("status", "status", "network, height, rate, supply, mempool, oracle");
        u("publishers", "publishers", "registered publishers and whether each is live");
        u("tx", "tx <id>", "pending, confirmed (with confirmations) or unknown");
    }

    private static void usage(PrintStream err) {
        err.println("usage: Cli [--rpc URL] [--wallet FILE] [--plain-wallet] <command>");
        for (String u : USAGE.values()) err.println("  " + u);
        err.println("RPC URL defaults to $SPESO_RPC or http://127.0.0.1:7100; the wallet password comes from $SPESO_PASSWORD or a prompt.");
    }

    private static char[] password(boolean plain) {
        if (plain) return null;
        String env = System.getenv("SPESO_PASSWORD");
        if (env != null && !env.isEmpty()) return env.toCharArray();
        if (System.console() != null) {
            char[] pw = System.console().readPassword("wallet password: ");
            if (pw != null && pw.length > 0) return pw;
        }
        throw new IllegalStateException("a wallet password is required (SPESO_PASSWORD or prompt), or use --plain-wallet");
    }

    private static Wallet open(String file, boolean plain) throws IOException, java.security.GeneralSecurityException {
        if (!Files.exists(Paths.get(file))) throw new IllegalStateException("no wallet at " + file + " (run 'new' first)");
        char[] pw = password(plain);
        try { return Wallet.loadOrCreate(file, pw); } finally { if (pw != null) Arrays.fill(pw, '\0'); }
    }

    /** Refuse to sign for a node on another network: signatures are network-bound and would be worthless. */
    private static Map<String, Object> checkedStatus(RpcClient c) throws IOException {
        Map<String, Object> st = c.get("/v1/status");
        String net = RpcClient.str(st, "network");
        if (!net.equals(Params.NETWORK))
            throw new IllegalStateException("the node is on network '" + net + "' but this client is set to '" + Params.NETWORK
                    + "' (use -Dspeso.network=" + net + ")");
        return st;
    }

    private static int exec(String rpc, String wfile, boolean plain, List<String> c, PrintStream out)
            throws IOException, java.security.GeneralSecurityException {
        RpcClient client = new RpcClient(rpc);
        switch (c.get(0)) {
            case "new" -> {
                if (Files.exists(Paths.get(wfile))) throw new IllegalStateException("wallet already exists at " + wfile);
                char[] pw = password(plain);
                try { out.println("created " + wfile + "\naddress: " + Wallet.loadOrCreate(wfile, pw).address
                        + "\nLosing the file or the password loses the funds. There is no recovery."); }
                finally { if (pw != null) Arrays.fill(pw, '\0'); }
            }
            case "me" -> {
                String a = Wallet.addressOf(wfile);
                if (a == null) throw new IllegalStateException("no readable wallet at " + wfile);
                out.println(a);
            }
            case "balance" -> {
                String a = c.size() > 1 ? c.get(1) : Wallet.addressOf(wfile);
                if (a == null) throw new IllegalStateException("no address: pass one or create a wallet");
                Map<String, Object> m = client.get("/v1/account/" + a);
                out.println(Params.SYMBOL + " " + RpcClient.str(m, "balanceFmt") + "   (" + RpcClient.num(m, "balance") + " spesoj)");
            }
            case "send" -> {
                long amount = Params.parse(c.get(2)), fee = c.size() > 3 ? Params.parse(c.get(3)) : Params.MIN_FEE;
                String to = c.get(1);
                if (!Crypto.isAddress(to)) throw new IllegalArgumentException("bad address '" + to + "' (an address is 40 hexadecimal characters; get yours with 'me')");
                Map<String, Object> st = checkedStatus(client);
                Wallet w = open(wfile, plain);
                w.networkId = RpcClient.str(st, "networkId");      // sign for the node's network id, not this JVM's own settings
                long seq = RpcClient.num(client.get("/v1/account/" + w.address), "nextSeq");
                Transaction t = w.pay(to, amount, fee, seq);
                out.println(submit(client, t, "confirms when mined"));
            }
            case "sendgbu" -> {
                String to = c.get(1);
                if (!Crypto.isAddress(to)) throw new IllegalArgumentException("bad address '" + to + "' (an address is 40 hexadecimal characters; get yours with 'me')");
                long gbu = Params.parse(c.get(2)), fee = c.size() > 4 ? Params.parse(c.get(4)) : Params.MIN_FEE, max;
                Map<String, Object> st = checkedStatus(client);
                if (c.get(3).equals("auto")) {
                    max = RpcClient.num(client.get("/v1/quote?gbu=" + Params.fmt(gbu)), "suggestedMax");
                    out.println("using ceiling " + Params.show(max) + " (worst case after 3 blocks)");
                } else max = Params.parse(c.get(3));
                Wallet w = open(wfile, plain);
                w.networkId = RpcClient.str(st, "networkId");
                long seq = RpcClient.num(client.get("/v1/account/" + w.address), "nextSeq");
                Transaction t = w.payGbu(to, gbu, max, fee, seq);
                out.println(submit(client, t, "settles at the rate of the block that mines it, never above your ceiling"));
            }
            case "quote" -> {
                Map<String, Object> m = client.get("/v1/quote?gbu=" + c.get(1));
                out.println(m.get("gbu") + " GBU = " + Params.SYMBOL + " " + m.get("costFmt") + " now (block " + m.get("block") + ")");
                for (Object r : (List<?>) m.get("worstCase")) {
                    Map<?, ?> row = (Map<?, ?>) r;
                    out.println("  worst case in " + row.get("blocks") + " block(s): " + Params.SYMBOL + " " + row.get("costFmt"));
                }
                out.println("  suggested ceiling: " + Params.SYMBOL + " " + m.get("suggestedMaxFmt"));
            }
            case "rate" -> {
                Map<String, Object> m = client.get("/v1/rate");
                out.println("score " + RpcClient.num(m, "score") / 100.0 + "  =>  1 " + Params.SYMBOL + " = " + m.get("gbuPerSpesmilo") + " GBU");
                out.println("indicators " + m.get("indicators"));
                out.println("supply " + Params.show(RpcClient.num(m, "supply")) + " / target " + Params.show(RpcClient.num(m, "target"))
                        + "   difficulty " + m.get("bits") + " bits");
                out.println("oracle: " + m.get("oracle"));
            }
            case "status" -> {
                Map<String, Object> m = client.get("/v1/status");
                long score = RpcClient.num(m, "score");
                out.println("network:    " + RpcClient.str(m, "network") + "  (id " + RpcClient.str(m, "networkId") + ")");
                out.println("height:     " + RpcClient.num(m, "height") + "   tip " + RpcClient.str(m, "tip").substring(0, 16) + "...");
                out.println("rate:       score " + String.format("%.1f", score / 100.0) + "  =>  1 " + Params.SYMBOL + " = "
                        + String.format("%.4f", score / 10000.0) + " GBU");
                out.println("supply:     " + Params.SYMBOL + " " + RpcClient.str(m, "supplyFmt") + "   difficulty " + RpcClient.num(m, "bits") + " bits");
                out.println("node:       mempool " + RpcClient.num(m, "mempool") + ", peers " + RpcClient.num(m, "peers")
                        + ", mining " + (Boolean.TRUE.equals(m.get("mining")) ? "yes" : "no"));
                out.println("oracle:     " + RpcClient.str(m, "oracle"));
            }
            case "publishers" -> {
                Map<String, Object> m = client.get("/v1/publishers");
                out.println("threshold " + m.get("threshold"));
                for (Object o : (List<?>) m.get("publishers")) {
                    Map<?, ?> p = (Map<?, ?>) o;
                    out.println(p.get("address") + (Boolean.TRUE.equals(p.get("live")) ? "  live" : "  silent"));
                }
            }
            case "tx" -> {
                Map<String, Object> m = client.get("/v1/tx/" + c.get(1));
                out.println(m.get("state") + (m.containsKey("height") ? " at height " + m.get("height") + " (" + m.get("confirmations") + " confirmation(s))" : ""));
            }
            default -> throw new IllegalArgumentException("unknown command '" + c.get(0) + "'");
        }
        return 0;
    }

    private static String submit(RpcClient client, Transaction t, String note) throws IOException {
        Map<String, Object> r = client.post("/v1/tx", Json.write(Json.obj("tx", t.encode())));
        return "submitted " + r.get("id") + " (" + note + ")";
    }
}

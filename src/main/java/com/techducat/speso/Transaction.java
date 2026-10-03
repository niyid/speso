package com.techducat.speso;

/**
 * Five kinds of signed transaction, all sharing one envelope:
 *
 *   'P'  pay `amount` spesoj to `to`.
 *   'G'  pay `amount` of GBU (Global Basket Units, same 1/1000 precision) to `to`. The cost in spesoj is
 *        converted at the on-chain rate of the previous block: ceil(amount / rate).
 *        `aux` is the sender's signed ceiling on that cost (slippage protection).
 *        This is the "scoring-denominated" payment: a merchant can price in GBU and
 *        always receive the right number of spesmiloj for today's score.
 *   'R'  oracle report: `data` is the sender's reading of the five economy indicators.
 *        Its weight in the stake median is the sender's balance. It can ratify or veto the
 *        publishers' reading; it cannot set the number.
 *   'A'  publisher attestation: same payload as a report, but valid only from a PUBLISHER address
 *        (Params.PUBLISHERS). No stake weight. This is what the price is computed from.
 *   'Q'  publisher market quote: `data` is one integer, the market value of one spesmilo in score
 *        units (10000 == 1 GBU). Feeds the peg feedback in the monetary policy. Publishers only.
 *   Publisher transactions ('A', 'Q') may carry a zero fee: only the fixed publisher set can send them.
 *
 * DOUBLE-SPEND DEFENCE: every account has a sequence number starting at 0. A transaction
 * is valid only if its `seq` is the account's next one. Two different transactions cannot
 * both be number N, and a replay of a used one is invalid. The signature covers `seq`.
 *
 * Every other transaction pays a `fee` (>= Params.MIN_FEE) to the miner: the anti-spam price of
 * taking up block space.
 *
 * REPLAY ACROSS NETWORKS: the signature covers Params.NETWORK_ID as well as the payload, so a
 * transaction signed for one network (a testnet, a fork with other rules) is invalid on any other.
 */
final class Transaction {
    static final char PAY = 'P', PAY_GBU = 'G', REPORT = 'R', ATTEST = 'A', QUOTE = 'Q';

    final String fromPub;   // base64 public key of the sender
    final char kind;
    final String to;        // recipient address ("-" for reports)
    final long amount;      // spesoj (P) or thousandths of a GBU (G); 0 for reports
    final long aux;         // G: max cost in spesoj the sender accepts; otherwise 0
    final long fee;
    final long seq;
    final String data;      // R: "gdp,infl,unemp,trade,stress" in hundredths; otherwise "-"
    final String sig;       // base64 ECDSA signature over payload(); null while unsigned

    private String fromAddr;
    private Boolean verified;

    Transaction(String fromPub, char kind, String to, long amount, long aux, long fee, long seq, String data, String sig) {
        this.fromPub = fromPub; this.kind = kind; this.to = to; this.amount = amount; this.aux = aux;
        this.fee = fee; this.seq = seq; this.data = data; this.sig = sig;
    }

    Transaction withSig(String s) {
        return new Transaction(fromPub, kind, to, amount, aux, fee, seq, data, s);
    }

    String from() {
        if (fromAddr == null) fromAddr = Crypto.address(fromPub);
        return fromAddr;
    }

    boolean isPublisherKind() { return kind == ATTEST || kind == QUOTE; }

    /** What actually gets signed: the payload bound to this network. */
    String signingText() { return signingText(Params.NETWORK_ID); }

    /** The same, for an explicit network id: a thin wallet signs for the id its node reports, not for its own JVM's settings. */
    String signingText(String networkId) { return networkId + "|" + payload(); }

    /** The transaction's own fields, as sent on the wire. */
    String payload() {
        return fromPub + "|" + kind + "|" + to + "|" + amount + "|" + aux + "|" + fee + "|" + seq + "|" + data;
    }

    String id() { return Crypto.sha256(payload()).substring(0, 16); }

    /** The single integer carried by a 'Q' transaction. */
    long quote() { return Long.parseLong(data); }

    long[] indicators() {
        String[] p = data.split(",");
        long[] r = new long[p.length];
        for (int i = 0; i < p.length; i++) r[i] = Long.parseLong(p[i]);
        return r;
    }

    /** Context-free checks: well-formed and correctly signed. Balance/seq are checked in the Ledger. */
    boolean verify() {
        if (verified == null) verified = computeVerify();
        return verified;
    }

    private boolean computeVerify() {
        if (sig == null) return false;
        long minFee = isPublisherKind() ? 0 : Params.MIN_FEE;
        if (fee < minFee || fee > Params.MAX_AMOUNT || seq < 0) return false;
        switch (kind) {
            case PAY -> {
                if (amount <= 0 || amount > Params.MAX_AMOUNT || aux != 0) return false;
                if (!Crypto.isAddress(to) || !data.equals("-")) return false;
            }
            case PAY_GBU -> {
                if (amount <= 0 || amount > Params.MAX_AMOUNT || aux <= 0 || aux > Params.MAX_AMOUNT) return false;
                if (!Crypto.isAddress(to) || !data.equals("-")) return false;
            }
            case REPORT -> {
                if (amount != 0 || aux != 0 || !to.equals("-")) return false;
                try {
                    if (!EconomyIndex.validReport(indicators())) return false;
                } catch (NumberFormatException e) { return false; }
            }
            case ATTEST -> {
                if (amount != 0 || aux != 0 || !to.equals("-")) return false;
                if (!Params.isPublisher(from())) return false;           // only the named publishers may attest
                try {
                    if (!EconomyIndex.validReport(indicators())) return false;
                } catch (NumberFormatException e) { return false; }
            }
            case QUOTE -> {
                if (amount != 0 || aux != 0 || !to.equals("-")) return false;
                if (!Params.isPublisher(from())) return false;
                try {
                    long q = quote();
                    if (q < EconomyIndex.SCORE_MIN || q > EconomyIndex.SCORE_MAX) return false;
                } catch (NumberFormatException e) { return false; }
            }
            default -> { return false; }
        }
        return Crypto.verify(fromPub, signingText(), sig);
    }

    String encode() { return payload() + "|" + sig; }

    static Transaction decode(String s) {
        String[] p = s.split("\\|", -1);
        if (p.length != 9 || p[1].length() != 1) throw new IllegalArgumentException("bad tx");
        return new Transaction(p[0], p[1].charAt(0), p[2], Long.parseLong(p[3]), Long.parseLong(p[4]),
                Long.parseLong(p[5]), Long.parseLong(p[6]), p[7], p[8]);
    }

    @Override public String toString() {
        String who = from().substring(0, 8) + "...";
        return switch (kind) {
            case PAY -> id() + " " + who + " -> " + to.substring(0, 8) + "... " + Params.show(amount);
            case PAY_GBU -> id() + " " + who + " -> " + to.substring(0, 8) + "... " + Params.fmt(amount) + " GBU";
            case ATTEST -> id() + " " + who + " PUBLISHER attests " + EconomyIndex.describe(indicators());
            case QUOTE -> id() + " " + who + " PUBLISHER quotes market " + quote() / 10000.0 + " GBU";
            default -> id() + " " + who + " reports " + EconomyIndex.describe(indicators());
        };
    }
}

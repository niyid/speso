package com.techducat.speso;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

/**
 * Every consensus rule constant lives here. All nodes on one network MUST agree on these
 * (they feed into the genesis hash, so mismatched nodes simply won't sync).
 *
 * For test networks a few can be overridden with -Dspeso.xxx=value.
 */
final class Params {
    private Params() {}

    private static long prop(String k, long d) {
        String v = System.getProperty(k);
        return v == null ? d : Long.parseLong(v);
    }

    // ---- money
    // The SPESO is the indivisible unit on the chain: every amount is an integer number of spesoj.
    // (Saussure made it deliberately tiny, "to avoid fractions".) People deal in the SPESMILO,
    // symbol U+20B7, which is exactly 1000 spesoj. Prices are shown with 3 decimals of a spesmilo.
    static final long SPESMILO = 1000L;
    static final String SYMBOL = symbol();                    // falls back to "Sm" on terminals that can't show it
    static final long MAX_AMOUNT = 100_000_000L * SPESMILO;   // sanity ceiling for any single amount
    static final long MIN_FEE = 1;                            // one speso: anti-spam floor per transaction

    // ---- proof of work + difficulty retargeting (difficulty is in leading zero BITS)
    static final int  INIT_BITS = (int) prop("speso.initbits", 12);
    static final int  MIN_BITS = 4, MAX_BITS = 48;
    static final int  RETARGET_INTERVAL = (int) prop("speso.retarget", 20);   // must be >= 4
    static final long TARGET_BLOCK_MS = prop("speso.blockms", 10_000);
    static final long MAX_FUTURE_MS = 2 * 3600 * 1000L;

    // ---- blocks and mempool
    static final int MAX_TXS = 100;
    static final int MEMPOOL_MAX = 2000;
    static final int MEMPOOL_PER_ACCOUNT = 25;

    // ---- monetary policy (see Ledger.State.payout)
    static final long SUPPLY_BASE = prop("speso.supplybase", 21_000_000) * SPESMILO; // target supply when score == 100
    static final long MAX_REWARD = 50 * SPESMILO;                                      // max new spesmiloj per block

    // ---- oracle
    static final int ORACLE_WINDOW = (int) prop("speso.oraclewindow", 50);  // a report counts for this many blocks
    static final int ORACLE_QUORUM_DIV = 3;                                   // reporting stake must be >= supply/3
    static final int ORACLE_SHARE_PCT = 20;                                   // % of block reward shared by agreeing reporters

    // The oracle has TWO KEYS. Publishers sign the data; stake holders ratify or veto it. The score
    // moves only when both agree, so neither key alone can set it: a stake majority can only freeze
    // the score, and so can the publishers.
    // PUBLISHERS: addresses allowed to sign attestations and market quotes ('A' and 'Q' transactions).
    // Fixed at genesis: they are part of the network id, so a node with a different list is on a
    // different network. An empty list means no attestation can ever be valid and the score stays
    // frozen (fail closed, never "stake only").
    static final List<String> PUBLISHERS = parsePublishers(System.getProperty("speso.publishers", ""));
    static final int PUBLISHER_THRESHOLD = (int) prop("speso.pubthreshold", PUBLISHERS.size() / 2 + 1);   // k of n must agree
    static {
        if (!PUBLISHERS.isEmpty() && (PUBLISHER_THRESHOLD < 1 || PUBLISHER_THRESHOLD > PUBLISHERS.size()))
            throw new IllegalStateException("speso.pubthreshold must be between 1 and the number of publishers");
    }

    // ---- peg feedback: the one lever that listens to the market (see adjustedTarget)
    static final int QUOTE_TOLERANCE_PCT = 5;      // publishers' market quotes must agree within this % to count
    static final int PEG_ADJ_MIN_PCT = 50;         // the market signal can scale the supply target by 50% .. 150%,
    static final int PEG_ADJ_MAX_PCT = 150;        //   so a lying quote quorum has bounded reach

    /** Identifies this network. Signed into every transaction (no cross-network replay) and into the genesis block. */
    static final String NETWORK_ID = Crypto.sha256("speso/2|" + INIT_BITS + "|" + SUPPLY_BASE + "|" + MAX_REWARD + "|"
            + ORACLE_WINDOW + "|" + ORACLE_QUORUM_DIV + "|" + ORACLE_SHARE_PCT + "|" + String.join(",", PUBLISHERS) + "|"
            + PUBLISHER_THRESHOLD + "|" + QUOTE_TOLERANCE_PCT + "|" + PEG_ADJ_MIN_PCT + "|" + PEG_ADJ_MAX_PCT).substring(0, 16);

    static boolean isPublisher(String address) { return PUBLISHERS.contains(address); }

    private static List<String> parsePublishers(String csv) {
        TreeSet<String> out = new TreeSet<>();            // sorted + deduplicated: command-line order is irrelevant
        for (String a : csv.split(",")) {
            a = a.trim();
            if (a.isEmpty()) continue;
            if (!Crypto.isAddress(a)) throw new IllegalStateException("bad publisher address '" + a + "'");
            out.add(a);
        }
        return List.copyOf(out);
    }

    // ---- helpers
    static long mulDiv(long a, long b, long c) {
        return BigInteger.valueOf(a).multiply(BigInteger.valueOf(b)).divide(BigInteger.valueOf(c)).longValueExact();
    }

    static long mulDivCeil(long a, long b, long c) {
        BigInteger[] qr = BigInteger.valueOf(a).multiply(BigInteger.valueOf(b)).divideAndRemainder(BigInteger.valueOf(c));
        return qr[1].signum() == 0 ? qr[0].longValueExact() : qr[0].add(BigInteger.ONE).longValueExact();
    }

    /** Supply the monetary policy steers toward: a healthier economy => fewer spesmiloj. */
    static long supplyTarget(long score) { return mulDiv(SUPPLY_BASE, EconomyIndex.SCORE_BASE, score); }

    /**
     * The same target corrected by what the market says. `market` is the attested market value of one
     * spesmilo in score units (10000 == 1 GBU), or 0 when there is no attested quote (then: no change).
     * Market below the peg (the score) => the target shrinks: minting stops and fees burn. Market above
     * the peg => the target grows. The correction is clamped to 50%..150% of the plain target.
     * This is feedback, not enforcement: it cannot make anybody pay the peg price.
     */
    static long adjustedTarget(long score, long market) {
        long t = supplyTarget(score);
        if (market <= 0) return t;
        long pct = Math.max(PEG_ADJ_MIN_PCT, Math.min(PEG_ADJ_MAX_PCT, mulDiv(market, 100, score)));
        return mulDiv(t, pct, 100);
    }

    private static String symbol() {
        try { return System.out.charset().newEncoder().canEncode('\u20B7') ? "\u20B7" : "Sm"; }
        catch (Exception e) { return "Sm"; }
    }

    /** 1500 spesoj -> "1.5" (spesmiloj). */
    static String fmt(long spesoj) {
        return BigDecimal.valueOf(spesoj, 3).stripTrailingZeros().toPlainString();
    }

    /** 1500 spesoj -> "SYMBOL 1.5". */
    static String show(long spesoj) { return SYMBOL + " " + fmt(spesoj); }

    /** "1.5" spesmiloj -> 1500 spesoj. Rejects fractions of a speso. */
    static long parse(String s) {
        try {
            return new BigDecimal(s).movePointRight(3).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException("bad amount '" + s + "' (a speso is indivisible: max 3 decimals of a spesmilo)");
        }
    }
}

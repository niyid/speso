package com.techducat.speso;

import java.io.IOException;
import java.nio.file.*;

/**
 * The "global economy score" that drives the currency's value.
 *
 * Every node must compute EXACTLY the same score from the same inputs, so:
 *   - indicators are integers in hundredths (2.85% is stored as 285),
 *   - the score is integer arithmetic only (10000 == 100.00 == baseline).
 *
 * The weights below are placeholders: this is the part you'd argue about for years.
 *
 * Value of one spesmilo, in "Global Basket Units" (GBU) = score / 10000.
 * A healthy world economy => a spesmilo is worth more than 1 GBU; a sick one => less.
 * (The original 1907 spesmilo was pinned to 0.733 g of gold. This one is pinned to the world.)
 */
final class EconomyIndex {
    private EconomyIndex() {}

    static final int N = 5;
    static final String[] NAMES = {"gdp", "inflation", "unemployment", "trade", "stress"};
    //                                    gdp   infl   unemp  trade  stress     (hundredths)
    static final long[] BASELINE = {      300,  200,   500,   300,   3000 };
    //  gdp          = real world GDP growth, % per year
    //  inflation    = global inflation, % (2% is "ideal", deviation either way hurts)
    //  unemployment = global unemployment, %
    //  trade        = world trade volume growth, %
    //  stress       = financial stress index, 0..100 (lower is better)

    static final long SCORE_BASE = 10_000, SCORE_MIN = 1_000, SCORE_MAX = 100_000;

    static long score(long[] i) {
        long s = SCORE_BASE;
        s += 8 * (i[0] - BASELINE[0]);              // +1 pt GDP growth        => +8.00%
        s -= 4 * Math.abs(i[1] - BASELINE[1]);      // 1 pt off inflation target => -4.00%
        s -= 3 * (i[2] - BASELINE[2]);              // +1 pt unemployment      => -3.00%
        s += 2 * (i[3] - BASELINE[3]);              // +1 pt trade growth      => +2.00%
        s -= (i[4] - BASELINE[4]) / 2;              // +10 stress points       => -5.00%
        return Math.max(SCORE_MIN, Math.min(SCORE_MAX, s));
    }

    /** Max move per block: 2%. Stops one lying miner from teleporting the price. */
    static boolean withinBand(long prevScore, long newScore) {
        return Math.abs(newScore - prevScore) <= prevScore / 50;
    }

    /**
     * Move from the previous indicators toward the target, but only as far as the
     * per-block band allows. (Halve the distance until the score change is legal.)
     */
    static long[] limited(long[] prev, long[] target, long prevScore) {
        long[] t = target.clone();
        for (int k = 0; k < 24; k++) {
            if (withinBand(prevScore, score(t))) return t;
            for (int i = 0; i < N; i++) t[i] = (prev[i] + t[i]) / 2;
        }
        return prev.clone();
    }

    // Sanity bounds for a single reading (hundredths). Reports outside these are invalid.
    static final long[] MIN = {-2000, -1000,     0, -3000,     0};
    static final long[] MAX = { 2000,  5000, 10000,  3000, 10000};
    // A report "agrees" with the consensus if every indicator is within this distance (hundredths).
    static final long[] TOLERANCE = {50, 50, 50, 100, 500};

    static boolean validReport(long[] i) {
        if (i.length != N) return false;
        for (int k = 0; k < N; k++) if (i[k] < MIN[k] || i[k] > MAX[k]) return false;
        return true;
    }

    // Wider band than TOLERANCE. A stake report outside it, in the block where it lands, contradicts the publishers
    // clearly enough to be slashed (see Ledger.State.slash). Inside TOLERANCE it counts as agreement; between the
    // two it is neither rewarded nor punished, so honest reporters with slightly different sources are safe.
    static final long[] ANCHOR_TOL = {100, 100, 100, 200, 1000};

    static boolean within(long[] a, long[] b, long[] tol) {
        for (int k = 0; k < N; k++) if (Math.abs(a[k] - b[k]) > tol[k]) return false;
        return true;
    }

    static boolean agrees(long[] report, long[] consensus) {
        for (int k = 0; k < N; k++) if (Math.abs(report[k] - consensus[k]) > TOLERANCE[k]) return false;
        return true;
    }

    static String encode(long[] i) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < i.length; k++) { if (k > 0) sb.append(','); sb.append(i[k]); }
        return sb.toString();
    }

    /**
     * Reads lines like "gdp=2.8" (percent). Returns null unless ALL indicators are present and
     * in range: a reporter should never publish half a reading.
     */
    static long[] readFeed(String path) {
        if (path == null) return null;
        long[] r = new long[N];
        boolean[] seen = new boolean[N];
        try {
            for (String line : Files.readAllLines(Paths.get(path))) {
                String[] kv = line.trim().split("=");
                if (kv.length != 2) continue;
                for (int i = 0; i < N; i++)
                    if (NAMES[i].equals(kv[0].trim())) {
                        r[i] = Math.round(Double.parseDouble(kv[1].trim()) * 100);
                        seen[i] = true;
                    }
            }
        } catch (IOException | NumberFormatException e) { return null; }
        for (boolean b : seen) if (!b) return null;
        return validReport(r) ? r : null;
    }

    /**
     * Optional line "market=1.03" in a publisher's feed: the market value of one spesmilo in GBU.
     * Returns it in score units (10000 == 1 GBU), or 0 if absent or out of range.
     */
    static long readQuote(String path) {
        if (path == null) return 0;
        try {
            for (String line : Files.readAllLines(Paths.get(path))) {
                String[] kv = line.trim().split("=");
                if (kv.length == 2 && kv[0].trim().equals("market")) {
                    long q = Math.round(Double.parseDouble(kv[1].trim()) * SCORE_BASE);
                    return q >= SCORE_MIN && q <= SCORE_MAX ? q : 0;
                }
            }
        } catch (IOException | NumberFormatException e) { return 0; }
        return 0;
    }

    static String describe(long[] i) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < N; k++) sb.append(NAMES[k]).append('=').append(i[k] / 100.0).append(' ');
        return sb.toString().trim();
    }
}

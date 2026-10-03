package com.techducat.speso;

import java.util.*;

/**
 * What a GBU-denominated payment costs, now and in the worst case a few blocks ahead.
 * The score can fall at most score/50 per block (EconomyIndex.withinBand), so the price of a
 * payment that settles n blocks later can rise by no more than the compounded 2% steps below.
 * Shared by the console, the RPC and the thin wallet.
 */
final class Quote {
    private Quote() {}
    static final int[] HORIZONS = {1, 3, 6};

    /** Spesoj needed for `gbuThousandths` at `score` (rounded up, as the chain does). */
    static long cost(long gbuThousandths, long score) {
        return Params.mulDivCeil(gbuThousandths, EconomyIndex.SCORE_BASE, score);
    }

    /** Lowest score reachable after n blocks. */
    static long worstScore(long score, int n) {
        for (int i = 0; i < n; i++) score = Math.max(EconomyIndex.SCORE_MIN, score - score / 50);
        return score;
    }

    static long worstCost(long gbuThousandths, long score, int n) {
        return cost(gbuThousandths, worstScore(score, n));
    }

    /** A ceiling that survives up to 3 blocks of the fastest possible fall. */
    static long suggestedMax(long gbuThousandths, long score) { return worstCost(gbuThousandths, score, 3); }

    static List<Map<String, Object>> table(long gbuThousandths, long score) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int n : HORIZONS) {
            long c = worstCost(gbuThousandths, score, n);
            rows.add(Json.obj("blocks", n, "cost", c, "costFmt", Params.fmt(c)));
        }
        return rows;
    }
}

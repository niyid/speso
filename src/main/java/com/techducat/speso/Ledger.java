package com.techducat.speso;

import java.math.BigInteger;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * The chain (a linked list of Blocks), the account state derived from it, and the pool of
 * not-yet-mined transactions.
 *
 * DOUBLE-SPEND DEFENCE
 *  1. Per-account sequence numbers (see Transaction): one seq, one payment, ever.
 *  2. Balance checked at the exact point of application.
 *  3. Mempool validated against (confirmed state + pending txs): first seen wins.
 *  4. Blocks are verified by REPLAYING their transactions on a copy of the state.
 *  5. Fork choice = most cumulative proof-of-work. Rewriting a confirmed payment means
 *     out-working the rest of the network.
 *
 * THE ORACLE: TWO KEYS
 *  What the oracle proves is AGREEMENT, not truth, so no single group may be able to set the number.
 *   Key 1, PUBLISHERS: a fixed set of named addresses (Params.PUBLISHERS) sign ATTESTATIONS of the
 *     five indicators. Their per-indicator median is the candidate value, and it only counts when at
 *     least Params.PUBLISHER_THRESHOLD publishers agree with it within tolerance.
 *   Key 2, STAKE: any holder may publish a REPORT; the stake-weighted median of live reports
 *     RATIFIES or VETOES the candidate. It needs reporters holding >= 1/3 of the supply, and its
 *     median must lie within tolerance of the publishers'.
 *  The block's indicators must equal the candidate (moved by at most 2% in score) and the candidate
 *  exists only when BOTH keys agree. So:
 *     - miners cannot choose the numbers; a block with other numbers is invalid,
 *     - a stake majority cannot set the number: it can only veto (the score freezes),
 *     - publishers cannot set it without stake ratification: they can only freeze it too,
 *     - fake data needs a publisher quorum AND a stake majority to collude,
 *     - with no publishers configured, or no quorum on either side, the score is frozen,
 *     - stake reporters within tolerance of the ratified value share 20% of the block reward.
 *  What this does NOT give: truth. Publishers are trusted named parties; see README.
 *
 * MONETARY POLICY (the score actually does something)
 *  target supply = SUPPLY_BASE * 100 / score, scaled 50%..150% by the PEG FEEDBACK: publishers may
 *  also attest the market value of one spesmilo (QUOTES); when a quorum of them agree, a market price
 *  below the peg shrinks the target and one above it grows the target. Below target the block reward
 *  is minted (up to MAX_REWARD); above it nothing is minted and all fees are burned. A rising score
 *  therefore tightens supply; a falling one loosens it. Supply can never exceed the target.
 *  This is feedback, not enforcement: nothing here can make a market pay the peg price.
 */
final class Ledger {
    enum TxResult { ACCEPTED, REJECTED, INVALID }
    enum BlockResult { ACCEPTED, NO_CONNECT, INVALID }

    // ======================================================================== state

    static final class Report {
        final long[] ind; final int height;
        Report(long[] ind, int height) { this.ind = ind; this.height = height; }
    }

    static final class Oracle {
        boolean quorum;                                    // both keys agree: the score may move
        long[] median;                                     // the publishers' value, valid only if quorum
        String why;                                        // why there is no quorum (null if there is)
        final Map<String, Long> agree = new HashMap<>();   // stake reporter -> stake, for those near the ratified value
        long agreeTotal;
        long reportingStake;
        int reporters;
        int pubLive, pubAgree;                             // live publisher attestations, and how many agree
        long market;                                       // attested market value of one spesmilo (10000 == 1 GBU), 0 = none
        long[] anchor;                                     // the publishers' agreed value, set even when stake vetoes it (null if none)
    }

    /** Balances, next-expected sequence numbers, live oracle reports, attestations, quotes and total supply. Copyable. */
    static final class State {
        final Map<String, Long> bal = new HashMap<>();
        final Map<String, Long> seq = new HashMap<>();
        final Map<String, Report> reports = new HashMap<>();      // stake reports (ratify / veto)
        final Map<String, Report> attests = new HashMap<>();      // publisher attestations (set the value)
        final Map<String, Report> quotes = new HashMap<>();       // publisher market quotes (ind[0] = quote)
        long supply = 0;
        long fees = 0;      // fees collected so far in the block being applied

        State copy() {
            State s = new State();
            s.bal.putAll(bal); s.seq.putAll(seq); s.reports.putAll(reports);
            s.attests.putAll(attests); s.quotes.putAll(quotes);
            s.supply = supply;
            return s;
        }

        long nextSeq(String addr) { return seq.getOrDefault(addr, 0L); }

        /** Apply one transaction. `rateScore` is the previous block's score (GBU conversion). */
        boolean apply(Transaction t, long rateScore, int height) {
            if (!t.verify()) return false;
            String from = t.from();
            if (t.seq != nextSeq(from)) return false;               // replay / double-spend / gap
            long cost;
            switch (t.kind) {
                case Transaction.PAY -> cost = t.amount;
                case Transaction.PAY_GBU -> {
                    cost = Params.mulDivCeil(t.amount, EconomyIndex.SCORE_BASE, rateScore);
                    if (cost > t.aux) return false;                 // rate moved past sender's limit
                }
                case Transaction.REPORT, Transaction.ATTEST, Transaction.QUOTE -> cost = 0;
                default -> { return false; }
            }
            long total = cost + t.fee;
            long b = bal.getOrDefault(from, 0L);
            if (b < total) return false;                            // insufficient funds
            bal.put(from, b - total);
            if (cost > 0) bal.merge(t.to, cost, Long::sum);
            fees += t.fee;
            seq.put(from, t.seq + 1);
            switch (t.kind) {
                case Transaction.REPORT -> reports.put(from, new Report(t.indicators(), height));
                case Transaction.ATTEST -> attests.put(from, new Report(t.indicators(), height));
                case Transaction.QUOTE -> quotes.put(from, new Report(new long[]{t.quote()}, height));
                default -> { }
            }
            return true;
        }

        /** Per-indicator median of unweighted values (lower median). */
        private static long[] plainMedian(List<long[]> vals) {
            long[] med = new long[EconomyIndex.N];
            for (int k = 0; k < EconomyIndex.N; k++) {
                final int kk = k;
                long[] col = vals.stream().mapToLong(v -> v[kk]).sorted().toArray();
                med[k] = col[(col.length - 1) / 2];
            }
            return med;
        }

        /** Per-indicator stake-weighted median (lower median). */
        private long[] stakeMedian(Map<String, Long> stake, long total) {
            long[] med = new long[EconomyIndex.N];
            for (int i = 0; i < EconomyIndex.N; i++) {
                final int k = i;
                List<long[]> vw = new ArrayList<>();
                for (Map.Entry<String, Long> e : stake.entrySet())
                    vw.add(new long[]{reports.get(e.getKey()).ind[k], e.getValue()});
                vw.sort(Comparator.comparingLong(a -> a[0]));
                long acc = 0;
                med[i] = vw.get(vw.size() - 1)[0];
                for (long[] x : vw) {
                    acc += x[1];
                    if (acc * 2 >= total) { med[i] = x[0]; break; }
                }
            }
            return med;
        }

        /** Attested market value of one spesmilo: the median quote, if a publisher quorum agree within tolerance; else 0. */
        private long marketQuote() {
            if (quotes.isEmpty() || quotes.size() < Params.PUBLISHER_THRESHOLD) return 0;
            long[] v = quotes.values().stream().mapToLong(r -> r.ind[0]).sorted().toArray();
            long med = v[(v.length - 1) / 2];
            int agree = 0;
            for (long x : v) if (Math.abs(x - med) * 100 <= med * Params.QUOTE_TOLERANCE_PCT) agree++;
            return agree >= Params.PUBLISHER_THRESHOLD ? med : 0;
        }

        /**
         * The oracle's verdict at this height: the two keys. Also drops expired reports, attestations
         * and quotes. `quorum` is true only if publishers AND stake agree (see class comment).
         */
        Oracle oracle(int height) {
            Oracle o = new Oracle();
            reports.values().removeIf(r -> height - r.height > Params.ORACLE_WINDOW);
            attests.values().removeIf(r -> height - r.height > Params.ORACLE_WINDOW);
            quotes.values().removeIf(r -> height - r.height > Params.ORACLE_WINDOW);
            o.market = marketQuote();

            // Key 2 inputs: stake reports, weighted by current balance
            Map<String, Long> stake = new HashMap<>();
            long total = 0;
            for (String who : reports.keySet()) {
                long w = bal.getOrDefault(who, 0L);
                if (w > 0) { stake.put(who, w); total += w; }
            }
            o.reporters = stake.size();
            o.reportingStake = total;
            boolean stakeQuorum = supply > 0 && total * Params.ORACLE_QUORUM_DIV >= supply;

            // Key 1: publishers. The candidate value is their median, but only if k of n agree with it.
            List<long[]> pubs = new ArrayList<>();
            for (Report r : attests.values()) pubs.add(r.ind);
            o.pubLive = pubs.size();
            long[] candidate = null;
            if (!pubs.isEmpty() && pubs.size() >= Params.PUBLISHER_THRESHOLD) {
                long[] med = plainMedian(pubs);
                for (long[] p : pubs) if (EconomyIndex.agrees(p, med)) o.pubAgree++;
                if (o.pubAgree >= Params.PUBLISHER_THRESHOLD) candidate = med;
            }
            o.anchor = candidate;
            if (candidate == null) {
                o.why = pubs.size() < Params.PUBLISHER_THRESHOLD ? "publishers below quorum" : "publishers disagree";
                return o;
            }
            if (!stakeQuorum) { o.why = "no stake quorum to ratify"; return o; }
            if (!EconomyIndex.agrees(stakeMedian(stake, total), candidate)) { o.why = "stake vetoes the publishers"; return o; }

            o.quorum = true;
            o.median = candidate;
            for (Map.Entry<String, Long> e : stake.entrySet())
                if (EconomyIndex.agrees(reports.get(e.getKey()).ind, candidate)) {
                    o.agree.put(e.getKey(), e.getValue());
                    o.agreeTotal += e.getValue();
                }
            return o;
        }

        /**
         * Slashing: a stake reporter whose report INCLUDED IN THIS BLOCK contradicts the publishers' agreed value
         * by more than EconomyIndex.ANCHOR_TOL loses SLASH_PCT of its balance (burned). This is what makes a stake
         * veto cost something. Judged only in the block where the report lands, so an honest holder is never
         * punished later for a stale report. No publisher consensus => nobody is slashed (a stake majority must
         * never be able to slash the minority).
         */
        void slash(Oracle o, int height) {
            if (o.anchor == null || Params.SLASH_PCT == 0) return;
            for (Map.Entry<String, Report> e : reports.entrySet()) {
                Report r = e.getValue();
                if (r.height != height || EconomyIndex.within(r.ind, o.anchor, EconomyIndex.ANCHOR_TOL)) continue;
                long b = bal.getOrDefault(e.getKey(), 0L), cut = b * Params.SLASH_PCT / 100;
                if (cut > 0) { bal.put(e.getKey(), b - cut); supply -= cut; }
            }
        }

        /** Indicators a block at this point MUST carry. */
        static long[] nextIndicators(Oracle o, Block prev) {
            return o.quorum ? EconomyIndex.limited(prev.indicators, o.median, prev.score) : prev.indicators.clone();
        }

        /** Monetary policy + paying out reward and fees. Called last, once the block's score is known. */
        void payout(String miner, Oracle o, long score) {
            long target = Params.adjustedTarget(score, o.market);
            long reward = Math.max(0, Math.min(Params.MAX_REWARD, target - supply));
            long feesToMiner = fees, burned = 0;
            if (supply > target) { burned = fees; feesToMiner = 0; }      // over target: contract
            long minerCut = reward;
            if (o.quorum && o.agreeTotal > 0 && reward > 0) {
                long bonus = reward * Params.ORACLE_SHARE_PCT / 100;
                long paid = 0;
                for (Map.Entry<String, Long> e : o.agree.entrySet()) {
                    long share = Params.mulDiv(bonus, e.getValue(), o.agreeTotal);
                    if (share > 0) { bal.merge(e.getKey(), share, Long::sum); paid += share; }
                }
                minerCut = reward - paid;
            }
            bal.merge(miner, minerCut + feesToMiner, Long::sum);
            supply += reward - burned;
            fees = 0;
        }

        long sumBalances() { long s = 0; for (long v : bal.values()) s += v; return s; }
    }

    // ======================================================================== validation

    /** Checks that need no chain context: structure and proof-of-work. Cheap, so done first. */
    static boolean checkStateless(Block b) {
        if (!b.hash.equals(b.computeHash())) return false;
        if (b.bits < Params.MIN_BITS || b.bits > Params.MAX_BITS) return false;
        if (!b.meetsDifficulty()) return false;
        if (!Crypto.isAddress(b.miner)) return false;
        return b.txs.size() <= Params.MAX_TXS;
    }

    /** Difficulty the block after `prev` must use. Retargets every RETARGET_INTERVAL blocks. */
    static int expectedBits(Block prev) {
        int n = prev.index + 1, interval = Params.RETARGET_INTERVAL;
        if (n < interval || n % interval != 0) return prev.bits;
        Block first = prev;
        for (int k = 0; k < interval - 2; k++) first = first.prev;      // block n-interval+1
        long expected = (long) (interval - 2) * Params.TARGET_BLOCK_MS;
        long actual = Math.max(expected / 4, Math.min(expected * 4, prev.time - first.time));
        int bits = prev.bits;
        if (actual * 4 <= expected) bits += 2;
        else if (actual * 2 < expected) bits += 1;
        else if (actual >= expected * 4) bits -= 2;
        else if (actual > expected * 2) bits -= 1;
        return Math.max(Params.MIN_BITS, Math.min(Params.MAX_BITS, bits));
    }

    /** Median timestamp of the last (up to) 11 blocks ending at `tip`. A new block must be strictly later. */
    static long medianTimePast(Block tip) {
        long[] t = new long[11];
        int n = 0;
        for (Block x = tip; x != null && n < 11; x = x.prev) t[n++] = x.time;
        Arrays.sort(t, 0, n);
        return t[n / 2];
    }

    /** Full validity check. On success `st` has the block's effects applied and b.cumWork is set. */
    static boolean validate(Block b, Block prev, State st) {
        if (!checkStateless(b)) return false;
        if (b.index != prev.index + 1 || !b.prevHash.equals(prev.hash)) return false;
        if (b.bits != expectedBits(prev)) return false;
        if (b.time < prev.time || b.time <= medianTimePast(prev)) return false;      // no time-warping behind the median
        if (b.time > System.currentTimeMillis() + Params.MAX_FUTURE_MS) return false;
        st.fees = 0;
        for (Transaction t : b.txs) if (!st.apply(t, prev.score, b.index)) return false;   // replay every tx
        Oracle o = st.oracle(b.index);
        if (!Arrays.equals(State.nextIndicators(o, prev), b.indicators)) return false;      // oracle decides
        st.slash(o, b.index);
        st.payout(b.miner, o, b.score);
        b.cumWork = prev.cumWork.add(b.work());
        return true;
    }

    // ======================================================================== instance

    final Block genesis = Block.genesis();
    private final List<Block> chain = new ArrayList<>();           // by height; mirrors the linked list
    private final Map<String, Integer> byHash = new HashMap<>();
    private volatile Block tip;
    private State state = new State();
    private List<Transaction> mempool = new ArrayList<>();
    private final Store store;

    Ledger() { this(null); }

    Ledger(Store store) {
        this.store = store;
        chain.add(genesis);
        byHash.put(genesis.hash, 0);
        tip = genesis;
        if (store == null) return;
        List<String> lines = store.readBlocks();
        int ok = 0;
        for (String line : lines) {
            try {
                if (extend(Block.decode(line)) != BlockResult.ACCEPTED) break;
                ok++;
            } catch (RuntimeException e) { break; }
        }
        if (ok < lines.size()) store.rewrite(chain);               // cut off the bad tail
    }

    // ---------------------------------------------------------------- queries

    Block tip() { return tip; }
    int height() { return tip.index; }
    synchronized BigInteger work() { return tip.cumWork; }
    synchronized long balance(String addr) { return state.bal.getOrDefault(addr, 0L); }
    synchronized long supply() { return state.supply; }
    synchronized long sumBalances() { return state.sumBalances(); }
    synchronized int mempoolSize() { return mempool.size(); }
    synchronized List<Block> chain() { return new ArrayList<>(chain); }

    /** Next seq a wallet should use, counting its own pending payments. */
    synchronized long nextSeq(String addr) {
        long n = state.nextSeq(addr);
        for (Transaction t : mempool) if (t.from().equals(addr)) n++;
        return n;
    }

    /** Where a transaction is: {1,0} pending, {2,height} confirmed, {0,0} unknown. Scans at most the last 20,000 blocks. */
    synchronized long[] findTx(String id) {
        for (Transaction t : mempool) if (t.id().equals(id)) return new long[]{1, 0};
        for (int i = chain.size() - 1; i >= 1 && i > chain.size() - 20_000; i--)
            for (Transaction t : chain.get(i).txs) if (t.id().equals(id)) return new long[]{2, i};
        return new long[]{0, 0};
    }

    /** Supply target the monetary policy steers toward right now: the score's target, corrected by the attested market quote. */
    synchronized long effectiveTarget() {
        return Params.adjustedTarget(tip.score, state.copy().oracle(tip.index + 1).market);
    }

    /** True if this publisher has a live attestation (not yet expired). */
    synchronized boolean hasAttestation(String addr) {
        Report r = state.attests.get(addr);
        return r != null && tip.index + 1 - r.height <= Params.ORACLE_WINDOW;
    }

    synchronized String oracleStatus() {
        Oracle o = state.copy().oracle(tip.index + 1);
        return "publishers " + o.pubLive + "/" + Params.PUBLISHERS.size() + " live (" + o.pubAgree + " agree, need "
                + Params.PUBLISHER_THRESHOLD + "); stake " + o.reporters + " reports, " + Params.show(o.reportingStake)
                + " (" + (state.supply == 0 ? 0 : o.reportingStake * 100 / state.supply) + "% of supply, need 33%); quorum "
                + (o.quorum ? "YES" : "no (score frozen: " + o.why + ")")
                + (o.market > 0 ? "; market quote " + o.market / 10000.0 + " GBU per " + Params.SYMBOL : "; no market quote");
    }

    /** Hashes at exponentially growing distances back from the tip: lets a peer find our common ancestor. */
    synchronized List<String> locator() {
        List<String> l = new ArrayList<>();
        int i = tip.index, step = 1;
        while (i > 0) {
            l.add(chain.get(i).hash);
            if (l.size() >= 10) step *= 2;
            i -= step;
        }
        l.add(genesis.hash);
        return l;
    }

    /** Index of the first hash in `hashes` that is on our main chain (0 = genesis if none match). */
    synchronized int locate(List<String> hashes) {
        for (String h : hashes) { Integer i = byHash.get(h); if (i != null) return i; }
        return 0;
    }

    synchronized List<Block> blocksFrom(int from, int max) {
        int a = Math.max(0, Math.min(from, chain.size()));
        int b = Math.min(chain.size(), a + Math.max(0, max));
        return new ArrayList<>(chain.subList(a, b));
    }

    // ---------------------------------------------------------------- transactions

    /** Accept a transaction into the mempool iff it would be valid on top of everything pending. */
    synchronized TxResult addTx(Transaction t) {
        if (!t.verify()) return TxResult.INVALID;
        if (mempool.size() >= Params.MEMPOOL_MAX && !t.isPublisherKind()) return TxResult.REJECTED;
        int mine = 0;
        for (Transaction p : mempool) if (p.from().equals(t.from())) mine++;
        if (mine >= Params.MEMPOOL_PER_ACCOUNT) return TxResult.REJECTED;
        State scratch = state.copy();
        for (Transaction p : mempool) scratch.apply(p, tip.score, tip.index + 1);
        if (!scratch.apply(t, tip.score, tip.index + 1)) return TxResult.REJECTED;  // dup / conflict / broke
        mempool.add(t);
        return TxResult.ACCEPTED;
    }

    /**
     * Why addTx would refuse this (already known to be signed correctly): the specific cause instead of a list of guesses.
     * Replays the same checks addTx does, in the same order, against confirmed state plus everything pending.
     */
    synchronized String rejectReason(Transaction t) {
        if (mempool.size() >= Params.MEMPOOL_MAX && !t.isPublisherKind()) return "the node's mempool is full; try again shortly";
        for (Transaction p : mempool) if (p.id().equals(t.id())) return "already pending (duplicate)";
        int mine = 0;
        for (Transaction p : mempool) if (p.from().equals(t.from())) mine++;
        if (mine >= Params.MEMPOOL_PER_ACCOUNT) return "this account already has " + mine + " pending transactions; wait for a block";
        State scratch = state.copy();
        for (Transaction p : mempool) scratch.apply(p, tip.score, tip.index + 1);
        long want = scratch.nextSeq(t.from());
        if (t.seq != want) return "wrong sequence number " + t.seq + " (the next valid one is " + want + ")";
        long cost;
        switch (t.kind) {
            case Transaction.PAY -> cost = t.amount;
            case Transaction.PAY_GBU -> {
                cost = Params.mulDivCeil(t.amount, EconomyIndex.SCORE_BASE, tip.score);
                if (cost > t.aux) return "the rate moved: this costs " + Params.show(cost) + " now, above your ceiling of " + Params.show(t.aux);
            }
            default -> cost = 0;
        }
        long have = scratch.bal.getOrDefault(t.from(), 0L);
        if (have < cost + t.fee)
            return "insufficient funds: needs " + Params.show(cost + t.fee) + " (amount + fee), available " + Params.show(have)
                    + " after pending payments";
        return "not valid on top of the current chain";
    }

    private void pruneMempool() {              // after the tip changes: drop what became invalid or was mined
        State scratch = state.copy();
        List<Transaction> keep = new ArrayList<>();
        for (Transaction t : mempool) if (scratch.apply(t, tip.score, tip.index + 1)) keep.add(t);
        mempool = keep;
    }

    // ---------------------------------------------------------------- blocks

    private BlockResult extend(Block b) {       // caller holds the lock (or is the constructor)
        if (!checkStateless(b)) return BlockResult.INVALID;
        if (!b.prevHash.equals(tip.hash)) return BlockResult.NO_CONNECT;
        State st = state.copy();
        if (!validate(b, tip, st)) return BlockResult.INVALID;
        b.prev = tip;
        chain.add(b);
        byHash.put(b.hash, b.index);
        tip = b;
        state = st;
        return BlockResult.ACCEPTED;
    }

    /** Try to extend our tip with b. */
    synchronized BlockResult addBlock(Block b) {
        BlockResult r = extend(b);
        if (r == BlockResult.ACCEPTED) {
            pruneMempool();
            if (store != null) store.append(b);
        }
        return r;
    }

    /** State after applying chain[1..idx]. */
    private State replay(int idx) {
        State st = new State();
        for (int i = 1; i <= idx; i++) if (!validate(chain.get(i), chain.get(i - 1), st)) return null;
        return st;
    }

    /**
     * Switch to a competing branch that forks off our chain after block `ancIdx` and carries more
     * cumulative work than our current chain. Transactions from the abandoned blocks go back to
     * the mempool if they are still valid. (Replays history up to the fork: O(chain length).)
     */
    synchronized boolean reorg(int ancIdx, List<Block> blocks) {
        if (blocks.isEmpty() || ancIdx < 0 || ancIdx >= chain.size()) return false;
        Block anc = chain.get(ancIdx);
        if (!blocks.get(0).prevHash.equals(anc.hash)) return false;
        State st = replay(ancIdx);
        if (st == null) return false;
        Block prev = anc;
        for (Block b : blocks) {
            if (!validate(b, prev, st)) return false;
            b.prev = prev;
            prev = b;
        }
        if (prev.cumWork.compareTo(tip.cumWork) <= 0) return false;       // not heavier: ignore

        List<Block> orphaned = new ArrayList<>(chain.subList(ancIdx + 1, chain.size()));
        while (chain.size() > ancIdx + 1) byHash.remove(chain.remove(chain.size() - 1).hash);
        for (Block b : blocks) { chain.add(b); byHash.put(b.hash, b.index); }
        tip = prev;
        state = st;
        List<Transaction> old = mempool;
        mempool = new ArrayList<>();
        for (Block o : orphaned) for (Transaction t : o.txs) addTx(t);
        for (Transaction t : old) addTx(t);
        if (store != null) store.rewrite(chain);
        return true;
    }

    // ---------------------------------------------------------------- mining

    /** Build and solve the next block. Returns null if someone else extended the chain first. */
    Block mine(String minerAddr) { return mine(minerAddr, System.currentTimeMillis()); }

    Block mine(String minerAddr, long time) {
        Block prev;
        List<Transaction> picked = new ArrayList<>();
        long[] ind;
        int bits;
        synchronized (this) {
            prev = tip;
            State scratch = state.copy();
            List<Transaction> cand = new ArrayList<>(mempool);
            cand.sort((a, b) -> a.isPublisherKind() != b.isPublisherKind()
                    ? (a.isPublisherKind() ? -1 : 1)                          // publisher txs first: they carry no fee
                    : Long.compare(b.fee, a.fee));                            // then highest fee first (stable)
            boolean progress = true;
            while (progress && picked.size() < Params.MAX_TXS) {
                progress = false;
                for (Transaction t : cand) {
                    if (picked.size() >= Params.MAX_TXS) break;
                    if (picked.contains(t)) continue;
                    if (scratch.apply(t, prev.score, prev.index + 1)) { picked.add(t); progress = true; }
                }
            }
            ind = State.nextIndicators(scratch.oracle(prev.index + 1), prev);
            bits = expectedBits(prev);
        }
        Block b = new Block(prev.index + 1, prev.hash, Math.max(time, Math.max(prev.time, medianTimePast(prev) + 1)), bits, 0, minerAddr, ind, picked);
        final Block base = prev;
        BooleanSupplier stale = () -> tip != base;
        return b.solve(stale) ? b : null;
    }
}

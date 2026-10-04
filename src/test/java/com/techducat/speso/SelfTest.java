package com.techducat.speso;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Run: ./build.sh && java -cp out com.techducat.speso.SelfTest   (no framework: prints PASS/FAIL, exit code 1 on failure)
 *
 * Uses small test-network parameters so everything runs in seconds. They must be set before
 * Params is first touched, which is why they're the first lines of main().
 */
public final class SelfTest {
    static int failures = 0;
    static long SM;                      // spesoj per spesmilo, read from Params once the test params are set
    static final Wallet[] NO_WALLETS = new Wallet[0];
    static Wallet[] PUBS;                // the test network's three data publishers (2 of 3 must agree)

    static void check(String name, boolean cond) {
        System.out.println((cond ? "  PASS  " : "  FAIL  ") + name);
        if (!cond) failures++;
    }

    static void section(String s) { System.out.println(s + ":"); }

    // ------------------------------------------------------------------ helpers

    static Wallet wallet() throws Exception {
        Path p = Files.createTempFile("wallet", ".dat");
        Files.delete(p);
        return Wallet.loadOrCreate(p.toString(), null);
    }

    /** Test clock: each block is stamped exactly one target interval after its parent, so retargeting stays neutral. */
    static long nextTime(Ledger l) { return l.tip().time + Params.TARGET_BLOCK_MS; }

    static Block mineAt(Ledger l, Wallet miner, long time) {
        Block b = l.mine(miner.address, time);
        if (b == null || l.addBlock(b) != Ledger.BlockResult.ACCEPTED) throw new IllegalStateException("could not extend chain");
        return b;
    }

    static Block mine(Ledger l, Wallet miner) { return mineAt(l, miner, nextTime(l)); }

    static Block copy(Block b) { return Block.decode(b.encode()); }

    /** Build a block by hand and solve its proof-of-work, to test that the ledger rejects it. */
    static Block forge(Block base, int bits, long[] ind, List<Transaction> txs, String miner) {
        Block b = new Block(base.index, base.prevHash, base.time, bits, 0, miner, ind, txs);
        b.solve(() -> false);
        return b;
    }

    static void reportAs(Ledger l, Wallet w, long[] ind) {
        Transaction t = w.report(ind, Params.MIN_FEE, l.nextSeq(w.address));
        if (l.addTx(t) != Ledger.TxResult.ACCEPTED) throw new IllegalStateException("report rejected");
    }

    /** A publisher signs the data (zero fee: a publisher is a role, not a bankroll). */
    static void attestAs(Ledger l, Wallet p, long[] ind) {
        Transaction t = p.attest(ind, 0, l.nextSeq(p.address));
        if (l.addTx(t) != Ledger.TxResult.ACCEPTED) throw new IllegalStateException("attestation rejected");
    }

    static void attestAll(Ledger l, Wallet[] ps, long[] ind) { for (Wallet p : ps) attestAs(l, p, ind); }

    static void quoteAs(Ledger l, Wallet p, long market) {
        Transaction t = p.quote(market, 0, l.nextSeq(p.address));
        if (l.addTx(t) != Ledger.TxResult.ACCEPTED) throw new IllegalStateException("quote rejected");
    }

    /** A fresh ledger in which five holders (a..e) own 100 spesmiloj each, score at baseline. */
    static Ledger funded(Wallet[] w) {
        Ledger l = new Ledger();
        for (int i = 0; i < 10; i++) mine(l, w[i % 5]);
        return l;
    }

    static long[] with(long[] base, int idx, long v) { long[] r = base.clone(); r[idx] = v; return r; }

    static void waitFor(java.util.function.BooleanSupplier c, int ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (!c.getAsBoolean() && System.currentTimeMillis() < end) Thread.sleep(25);
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] a) throws Exception {
        System.setProperty("speso.initbits", "6");
        System.setProperty("speso.retarget", "6");
        System.setProperty("speso.blockms", "1000");
        System.setProperty("speso.supplybase", "600");
        System.setProperty("speso.oraclewindow", "12");
        System.setProperty("speso.pbkdf2", "1000");
        PUBS = new Wallet[]{wallet(), wallet(), wallet()};
        System.setProperty("speso.publishers", PUBS[0].address + "," + PUBS[1].address + "," + PUBS[2].address);

        SM = Params.SPESMILO;
        final long FEE = Params.MIN_FEE;
        Wallet alice = wallet(), bob = wallet(), carol = wallet();
        final Wallet[] pubs = PUBS;

        // ============================================================ double spend
        section("Double-spend defences");
        Ledger l = new Ledger();
        mine(l, alice);
        check("miner got reward", l.balance(alice.address) == 50 * SM);
        Transaction t1 = alice.pay(bob.address, 40 * SM, FEE, 0);
        Transaction t2 = alice.pay(carol.address, 40 * SM, FEE, 0);
        check("first payment accepted", l.addTx(t1) == Ledger.TxResult.ACCEPTED);
        check("conflicting payment (same seq) rejected", l.addTx(t2) == Ledger.TxResult.REJECTED);
        check("exact replay rejected", l.addTx(t1) == Ledger.TxResult.REJECTED);
        check("overspend with next seq rejected", l.addTx(alice.pay(carol.address, 40 * SM, FEE, 1)) == Ledger.TxResult.REJECTED);
        check("payment within remaining balance accepted", l.addTx(alice.pay(carol.address, 9 * SM, FEE, 1)) == Ledger.TxResult.ACCEPTED);
        mine(l, bob);
        check("bob got 40 + reward + fees", l.balance(bob.address) == 90 * SM + 2 * FEE);
        check("alice spent exactly what she sent", l.balance(alice.address) == 1 * SM - 2 * FEE);
        check("replay of a confirmed tx rejected", l.addTx(t1) == Ledger.TxResult.REJECTED);
        check("mempool empty after mining", l.mempoolSize() == 0);
        check("supply equals sum of balances", l.supply() == l.sumBalances());

        // ============================================================ forgery, fees, decimals
        section("Forgery, fees and decimals");
        Transaction swapAmount = new Transaction(alice.pubB64, 'P', bob.address, 5 * SM, 0, FEE, 5, "-", t1.sig);
        check("stolen signature on a different tx rejected", !swapAmount.verify());
        Transaction bobsTx = bob.pay(carol.address, SM, FEE, 0);
        check("signature from another key rejected", !new Transaction(alice.pubB64, 'P', bobsTx.to, bobsTx.amount, 0, FEE, 0, "-", bobsTx.sig).verify());
        check("tampered amount rejected", !new Transaction(bob.pubB64, 'P', carol.address, 1000 * SM, 0, FEE, 0, "-", bobsTx.sig).verify());
        Transaction bare = alice.pay(bob.address, SM, FEE, 0);
        Transaction unbound = bare.withSig(Crypto.sign(alice.priv, bare.payload()));    // signed WITHOUT the network id
        check("signature not bound to this network is rejected (no cross-network replay)", bare.verify() && !unbound.verify());
        check("genesis commits to the network id (publisher set and policy)", l.genesis.miner.equals("genesis:" + Params.NETWORK_ID));
        check("fee below minimum is INVALID", l.addTx(bob.pay(carol.address, SM, FEE - 1, 0)) == Ledger.TxResult.INVALID);
        check("unknown tx kind rejected", !new Transaction(bob.pubB64, 'X', carol.address, 1, 0, FEE, 0, "-", "AAAA").verify());
        check("1 speso (the indivisible unit) can be paid", l.addTx(bob.pay(carol.address, 1, FEE, 0)) == Ledger.TxResult.ACCEPTED);
        check("amount parsing: \u20B7 1.5 = 1500 spesoj", Params.parse("1.5") == 1500L && Params.fmt(1500L).equals("1.5") && Params.fmt(1L).equals("0.001"));
        check("a spesmilo is exactly 1000 spesoj", Params.SPESMILO == 1000L);
        boolean threw = false;
        try { Params.parse("0.0001"); } catch (IllegalArgumentException e) { threw = true; }
        check("a fraction of a speso is rejected", threw);
        mine(l, carol);

        section("Mempool limits");
        Ledger lm = new Ledger();
        mine(lm, alice);
        int accepted = 0;
        for (int i = 0; i < Params.MEMPOOL_PER_ACCOUNT + 5; i++)
            if (lm.addTx(alice.pay(bob.address, 1000, FEE, lm.nextSeq(alice.address))) == Ledger.TxResult.ACCEPTED) accepted++;
        check("one account can queue at most " + Params.MEMPOOL_PER_ACCOUNT + " pending txs", accepted == Params.MEMPOOL_PER_ACCOUNT);

        // ============================================================ attacks via blocks
        section("Blocks that try to cheat");
        Ledger l2 = new Ledger();
        mine(l2, alice);
        Block good = l2.mine(alice.address, nextTime(l2));
        List<Transaction> overspend = List.of(alice.pay(bob.address, 40 * SM, FEE, 0), alice.pay(carol.address, 40 * SM, FEE, 1));
        check("block spending 80 from a 50 balance rejected",
                l2.addBlock(forge(good, good.bits, good.indicators, new ArrayList<>(overspend), alice.address)) == Ledger.BlockResult.INVALID);
        List<Transaction> dupSeq = List.of(alice.pay(bob.address, 10 * SM, FEE, 0), alice.pay(carol.address, 10 * SM, FEE, 0));
        check("block reusing a sequence number rejected",
                l2.addBlock(forge(good, good.bits, good.indicators, new ArrayList<>(dupSeq), alice.address)) == Ledger.BlockResult.INVALID);
        long[] lie = with(EconomyIndex.BASELINE, 0, -200);
        check("miner-chosen indicators rejected (oracle decides, not miners)",
                l2.addBlock(forge(good, good.bits, lie, new ArrayList<>(), alice.address)) == Ledger.BlockResult.INVALID);
        check("block with wrong difficulty rejected",
                l2.addBlock(forge(good, good.bits - 1, good.indicators, new ArrayList<>(), alice.address)) == Ledger.BlockResult.INVALID);
        check("honest block still accepted", l2.addBlock(good) == Ledger.BlockResult.ACCEPTED);
        check("duplicate of the tip does not connect", l2.addBlock(copy(good)) == Ledger.BlockResult.NO_CONNECT);

        // ============================================================ the oracle + monetary policy
        section("Oracle: two keys (publishers sign the data, stake ratifies it)");
        Ledger lo = new Ledger();
        Wallet[] w = {alice, bob, carol, wallet(), wallet()};       // a, b, c, d, e
        for (int i = 0; i < 10; i++) mine(lo, w[i % 5]);
        check("five holders with 100 spesmiloj each", lo.balance(w[0].address) == 100 * SM && lo.balance(w[4].address) == 100 * SM);
        check("score starts at baseline", lo.tip().score == 10_000);

        long[] boom = with(EconomyIndex.BASELINE, 0, 500);          // gdp 5%  => score 116.00
        long[] bust = with(with(EconomyIndex.BASELINE, 0, -200), 4, 8000);
        attestAll(lo, pubs, boom);                                  // all three publishers sign: boom
        for (int i = 0; i < 3; i++) reportAs(lo, w[i], boom);       // a,b,c (60% of stake) ratify it
        for (int i = 3; i < 5; i++) reportAs(lo, w[i], bust);       // d,e (40%) say bust
        long aBefore = lo.balance(w[0].address), eBefore = lo.balance(w[4].address);
        mine(lo, w[3]);
        long s1 = lo.tip().score;
        check("both keys agree: score moves toward boom, not bust", s1 > 10_000 && s1 <= 10_200);
        check("move limited to 2% per block", EconomyIndex.withinBand(10_000, s1));
        check("ratifying reporter shares the reward bonus", lo.balance(w[0].address) > aBefore);
        long eAfterFee = eBefore - FEE;
        check("dissenting reporter gets no bonus and is slashed for contradicting the publishers",
                lo.balance(w[4].address) == eAfterFee - eAfterFee * Params.SLASH_PCT / 100);
        check("publishers paid no fee: a role, not a bankroll", lo.balance(pubs[0].address) == 0);
        int guard = 0;
        long lastScore = s1;
        boolean monotone = true;
        while (lo.tip().score != EconomyIndex.score(boom) && guard++ < 40) {
            mine(lo, w[guard % 5]);
            monotone &= lo.tip().score >= lastScore;
            lastScore = lo.tip().score;
        }
        check("score converges exactly to the publishers' reading, monotonically", lo.tip().score == EconomyIndex.score(boom) && monotone);
        check("supply equals sum of balances through all of it", lo.supply() == lo.sumBalances());

        section("Oracle: neither key can set the price alone");
        Ledger k1 = funded(w);                                      // stake only
        for (int i = 0; i < 3; i++) reportAs(k1, w[i], boom);
        for (int i = 3; i < 5; i++) reportAs(k1, w[i], bust);
        for (int i = 0; i < 4; i++) mine(k1, w[3 + i % 2]);
        check("a stake MAJORITY alone cannot move the price (no publisher quorum)",
                k1.tip().score == 10_000 && k1.oracleStatus().contains("publishers below quorum"));

        Ledger k2 = funded(w);                                      // publishers only
        attestAll(k2, pubs, boom);
        for (int i = 0; i < 4; i++) mine(k2, w[i % 5]);
        check("publishers alone cannot move the price (no stake ratification)",
                k2.tip().score == 10_000 && k2.oracleStatus().contains("no stake quorum"));

        Ledger k3 = funded(w);                                      // stake majority lies against the publishers
        attestAll(k3, pubs, boom);
        for (int i = 0; i < 3; i++) reportAs(k3, w[i], bust);
        for (int i = 3; i < 5; i++) reportAs(k3, w[i], boom);
        Wallet outsider = wallet();                                 // mines without reporting, so nobody's stake shifts
        for (int i = 0; i < 4; i++) mine(k3, outsider);
        check("a stake majority cannot OVERRIDE attested data: it can only freeze (veto)",
                k3.tip().score == 10_000 && k3.oracleStatus().contains("stake vetoes"));

        Ledger k4 = funded(w);                                      // publishers split three ways
        attestAs(k4, pubs[0], boom); attestAs(k4, pubs[1], bust); attestAs(k4, pubs[2], EconomyIndex.BASELINE);
        for (int i = 0; i < 5; i++) reportAs(k4, w[i], EconomyIndex.BASELINE);
        for (int i = 0; i < 4; i++) mine(k4, w[i % 5]);
        check("publishers that do not agree (no 2 of 3 within tolerance): score frozen",
                k4.tip().score == 10_000 && k4.oracleStatus().contains("publishers disagree"));

        Ledger k5 = funded(w);                                      // one publisher plus ALL the stake
        attestAs(k5, pubs[0], boom);
        for (int i = 0; i < 5; i++) reportAs(k5, w[i], boom);
        for (int i = 0; i < 4; i++) mine(k5, w[i % 5]);
        check("one publisher backed by 100% of the stake still cannot set the price",
                k5.tip().score == 10_000 && k5.oracleStatus().contains("publishers below quorum"));

        Ledger lp = new Ledger();
        long[] insane = with(EconomyIndex.BASELINE, 0, 999_999);
        check("an attestation from a non-publisher is INVALID", lp.addTx(alice.attest(boom, 0, 0)) == Ledger.TxResult.INVALID);
        check("a market quote from a non-publisher is INVALID", lp.addTx(alice.quote(9000, 0, 0)) == Ledger.TxResult.INVALID);
        check("an out-of-range attestation is INVALID even from a publisher", lp.addTx(pubs[0].attest(insane, 0, 0)) == Ledger.TxResult.INVALID);
        check("an out-of-range market quote is INVALID even from a publisher", lp.addTx(pubs[0].quote(5, 0, 0)) == Ledger.TxResult.INVALID);
        check("a publisher's zero-fee attestation is accepted", lp.addTx(pubs[0].attest(boom, 0, 0)) == Ledger.TxResult.ACCEPTED);
        check("a publisher cannot spend: a zero-fee payment is still INVALID", lp.addTx(pubs[0].pay(bob.address, 1, 0, 1)) == Ledger.TxResult.INVALID);

        section("Monetary policy follows the score");
        long score = lo.tip().score;
        check("healthy economy (score 116) => target supply below baseline", Params.supplyTarget(score) < Params.supplyTarget(10_000));
        check("supply is above the new target", lo.supply() > Params.supplyTarget(score));
        long supBefore = lo.supply(), cBefore = lo.balance(w[2].address);
        long burnFee = 100;
        check("fee-paying tx accepted", lo.addTx(w[0].pay(w[1].address, SM, burnFee, lo.nextSeq(w[0].address))) == Ledger.TxResult.ACCEPTED);
        mine(lo, w[2]);
        check("over target: no new coins minted", lo.supply() == supBefore - burnFee);
        check("over target: fees are burned, not paid to the miner", lo.balance(w[2].address) == cBefore);
        check("supply still equals sum of balances", lo.supply() == lo.sumBalances());

        section("Peg feedback: attested market quotes steer the supply target");
        long base = Params.supplyTarget(10_000);
        check("no quote => target unchanged", Params.adjustedTarget(10_000, 0) == base);
        check("market at 80% of the peg => target 80%", Params.adjustedTarget(10_000, 8000) == base * 80 / 100);
        check("the correction is clamped at 50% (a lying quote has bounded reach)", Params.adjustedTarget(10_000, 1000) == base / 2);
        check("a market far above the peg cannot lift the ceiling past the base (strictly deflationary)", Params.adjustedTarget(10_000, 90_000) == base);
        check("in a boom a high quote can lift the ceiling back up, but only to the base", Params.adjustedTarget(12_500, 90_000) == base);

        Ledger q1 = funded(w);                                      // supply 500, plain target 600
        long supQ = q1.supply();
        for (Wallet p : pubs) quoteAs(q1, p, 5000);                 // market pays 0.5 GBU where the peg says 1.0
        mine(q1, w[0]);
        check("quorum quotes market below peg: minting stops although supply < the plain target", q1.supply() == supQ);
        check("quote is visible in the oracle status", q1.oracleStatus().contains("market quote 0.5"));
        q1.addTx(w[1].pay(w[2].address, SM, 70, q1.nextSeq(w[1].address)));
        mine(q1, w[3]);
        check("and fees are burned while the market is below the peg", q1.supply() == supQ - 70);
        check("supply still equals sum of balances", q1.supply() == q1.sumBalances());

        Ledger q2 = funded(w);
        quoteAs(q2, pubs[0], 3000); quoteAs(q2, pubs[1], 8000); quoteAs(q2, pubs[2], 12_000);
        long sq2 = q2.supply();
        mine(q2, w[0]);
        check("quotes that do not agree are ignored: minting continues", q2.supply() == sq2 + 50 * SM);

        Ledger q3 = funded(w);
        quoteAs(q3, pubs[0], 5000);
        long sq3 = q3.supply();
        mine(q3, w[0]);
        check("a single publisher's quote is below quorum and ignored", q3.supply() == sq3 + 50 * SM);

        Ledger q4 = funded(w);
        quoteAs(q4, pubs[0], 5000); quoteAs(q4, pubs[1], 5100); quoteAs(q4, pubs[2], 12_000);
        long sq4 = q4.supply();
        mine(q4, w[0]);
        check("one outlier quote does not break a quorum of two", q4.supply() == sq4);

        section("GBU-denominated payments");
        long cost = Params.mulDivCeil(10 * SM, 10_000, score);
        long eBal = lo.balance(w[4].address);
        check("limit below the converted cost is rejected",
                lo.addTx(w[0].payGbu(w[4].address, 10 * SM, cost - 1, FEE, lo.nextSeq(w[0].address))) == Ledger.TxResult.REJECTED);
        check("GBU payment accepted", lo.addTx(w[0].payGbu(w[4].address, 10 * SM, cost, FEE, lo.nextSeq(w[0].address))) == Ledger.TxResult.ACCEPTED);
        mine(lo, w[1]);
        check("recipient received exactly ceil(10 GBU / rate) spesoj, about 8.62 spesmiloj", lo.balance(w[4].address) == eBal + cost && cost / SM == 8);
        check("conversion is rate-dependent: same 10 GBU costs more spesoj at a lower score",
                Params.mulDivCeil(10 * SM, 10_000, 8000) > cost);

        section("Supply follows the score down, and the oracle stalls without quorum");
        long[] recession = with(EconomyIndex.BASELINE, 0, 100);     // target score 84.00
        attestAll(lo, pubs, recession);
        for (int i = 0; i < 3; i++) reportAs(lo, w[i], recession);
        long supLow = lo.supply();
        mine(lo, w[0]);
        int downBlocks = 1;
        long supMax = lo.supply();
        while (lo.tip().score > 9500 && downBlocks < 20) { mine(lo, w[downBlocks % 5]); downBlocks++; supMax = Math.max(supMax, lo.supply()); }
        check("score fell as publishers and stake agreed on recession", lo.tip().score < score);
        check("weaker economy => target supply up => minting resumed", supMax > supLow);
        check("supply never exceeds the target while minting", lo.supply() <= Params.supplyTarget(lo.tip().score) || lo.supply() == supLow);
        // keep mining until the reports expire (window = 12 blocks) while the score is still mid-move
        for (int i = 0; i < 12; i++) mine(lo, w[i % 5]);
        long frozen = lo.tip().score;
        check("reports expired => no quorum", lo.oracleStatus().contains("quorum no"));
        check("score is frozen mid-way, not at the recession target", frozen != EconomyIndex.score(recession));
        for (int i = 0; i < 4; i++) mine(lo, w[i % 5]);
        check("score stays frozen without quorum", lo.tip().score == frozen);

        section("A small stake minority cannot ratify");
        attestAll(lo, pubs, bust);                                  // even with attested data...
        reportAs(lo, w[4], bust);                                   // ...one holder, ~15% of supply, is below the stake quorum
        for (int i = 0; i < 4; i++) mine(lo, w[i % 4]);
        check("lone reporter below stake quorum is ignored", lo.tip().score == frozen && lo.oracleStatus().contains("quorum no"));
        check("supply still equals sum of balances", lo.supply() == lo.sumBalances());

        section("Supply cap");
        Ledger lc = new Ledger();
        for (int i = 0; i < 20; i++) mine(lc, alice);
        check("supply stops at the target (600 spesmiloj at score 100)", lc.supply() == 600 * SM);

        // ============================================================ difficulty
        section("Difficulty retargeting");
        int I = Params.RETARGET_INTERVAL;
        Ledger neutral = new Ledger(), fast = new Ledger(), slow = new Ledger();
        for (int i = 1; i <= I; i++) {
            mineAt(neutral, alice, i * Params.TARGET_BLOCK_MS);
            mineAt(fast, alice, i * Params.TARGET_BLOCK_MS / 100);
            mineAt(slow, alice, i * Params.TARGET_BLOCK_MS * 10);
        }
        check("on-target block times keep difficulty", neutral.tip().bits == Params.INIT_BITS);
        check("blocks far too fast => difficulty +2 bits", fast.tip().bits == Params.INIT_BITS + 2);
        check("blocks far too slow => difficulty -2 bits (floor 4)", slow.tip().bits == Math.max(Params.MIN_BITS, Params.INIT_BITS - 2));
        check("block bits follow Block.zeroBits", Block.zeroBits("0f") == 4 && Block.zeroBits("00ff") == 8 && Block.zeroBits("1") == 3);

        // ============================================================ fork choice
        section("Fork choice by cumulative work");
        Ledger x = new Ledger(), y = new Ledger();
        mine(x, alice);
        for (int i = 0; i < 3; i++) mine(y, bob);
        check("x and y diverged", !x.tip().hash.equals(y.tip().hash));
        List<Block> yb = y.chain().subList(1, y.chain().size());
        List<Block> xb = x.chain().subList(1, x.chain().size());
        check("lighter chain does not replace a heavier one", !y.reorg(0, new ArrayList<>(xb.stream().map(SelfTest::copy).toList())));
        check("heavier chain replaces a lighter one", x.reorg(0, new ArrayList<>(yb.stream().map(SelfTest::copy).toList())));
        check("after reorg alice's reward is gone, bob has 150", x.balance(alice.address) == 0 && x.balance(bob.address) == 150 * SM);

        Ledger p = new Ledger(), q = new Ledger();                   // fork at depth 2
        for (int i = 0; i < 2; i++) { Block b = mine(p, alice); q.addBlock(copy(b)); }
        Transaction orphanTx = alice.pay(carol.address, SM, FEE, 0);
        p.addTx(orphanTx);
        mine(p, alice);                                              // p: includes orphanTx at height 3
        for (int i = 0; i < 3; i++) mine(q, bob);                    // q: heavier branch from height 2
        List<Block> branch = q.chain().subList(3, q.chain().size()).stream().map(SelfTest::copy).toList();
        check("reorg at depth 2", p.reorg(2, new ArrayList<>(branch)) && p.tip().hash.equals(q.tip().hash));
        check("orphaned tx returned to the mempool (still valid)", p.mempoolSize() == 1);

        // ============================================================ persistence
        section("Persistence");
        Path dir = Files.createTempDirectory("speso-test");
        Ledger d1 = new Ledger(new Store(dir.toString()));
        mine(d1, alice); mine(d1, bob);
        d1.addTx(alice.pay(carol.address, 3 * SM, FEE, 0));
        for (int i = 0; i < 4; i++) mine(d1, carol);
        String tipHash = d1.tip().hash;
        long aliceBal = d1.balance(alice.address), sup = d1.supply();
        Ledger d2 = new Ledger(new Store(dir.toString()));
        check("restart restores the same tip", d2.tip().hash.equals(tipHash) && d2.height() == 6);
        check("restart restores balances and supply", d2.balance(alice.address) == aliceBal && d2.supply() == sup);
        Path file = dir.resolve("chain.dat");
        Files.writeString(file, "this is not a block\n", StandardOpenOption.APPEND);
        Ledger d3 = new Ledger(new Store(dir.toString()));
        check("garbage at the end of the file is cut off", d3.height() == 6 && Files.readAllLines(file).size() == 6);
        List<String> lines = new ArrayList<>(Files.readAllLines(file));
        lines.set(1, lines.get(1).replace("#" + bob.address + "#", "#" + carol.address + "#"));
        Files.write(file, lines);
        Ledger d4 = new Ledger(new Store(dir.toString()));
        check("a tampered block stops the replay there", d4.height() < 6);

        // ============================================================ wallet
        section("Wallet encryption");
        Path wf = Files.createTempFile("enc", ".wallet");
        Files.delete(wf);
        Wallet enc = Wallet.loadOrCreate(wf.toString(), "correct horse".toCharArray());
        Wallet again = Wallet.loadOrCreate(wf.toString(), "correct horse".toCharArray());
        check("right password reopens the same wallet", enc.address.equals(again.address));
        String content = Files.readString(wf);
        check("file is marked encrypted and has no plaintext key", content.startsWith("SPESO-WALLET-ENC-1") && !content.contains("PLAIN"));
        boolean wrong = false, none = false;
        try { Wallet.loadOrCreate(wf.toString(), "battery staple".toCharArray()); } catch (java.security.GeneralSecurityException e) { wrong = true; }
        try { Wallet.loadOrCreate(wf.toString(), null); } catch (java.security.GeneralSecurityException e) { none = true; }
        check("wrong password fails", wrong);
        check("missing password fails", none);
        try {
            check("wallet file is created private (rw-------) from the start",
                    Files.getPosixFilePermissions(wf).equals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) { check("(non-POSIX filesystem: file permissions not checked)", true); }
        Transaction st = again.pay(alice.address, SM, FEE, 0);
        check("decrypted wallet signs valid transactions", st.verify() && st.from().equals(enc.address));

        // ============================================================ network
        networkTests();

        System.out.println(failures == 0 ? "\nALL TESTS PASSED" : "\n" + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ network section

    /** Minimal raw client for poking a node the way a hostile peer would. */
    static List<String> raw(int port, Wallet id, String request) {
        List<String> out = new ArrayList<>();
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(3000);
            Reader in = new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8);
            PrintWriter w = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
            String chal = Node.readLine(in, 1024);
            if (chal == null) { out.add("<closed>"); return out; }
            if (id != null)
                w.println("AUTH " + id.pubB64 + " " + Crypto.sign(id.priv, Node.authMessage(chal.substring(5))) + " 9999");
            else
                w.println("HELLO i am not authenticating");
            w.println(request);
            String l;
            while ((l = Node.readLine(in, 1 << 20)) != null && !l.equals("END")) out.add(l);
        } catch (IOException e) { out.add("<io:" + e.getClass().getSimpleName() + ">"); }
        return out;
    }

    static void networkTests() throws Exception {
        section("Network: sync, gossip and DoS protection");
        Wallet miner = wallet(), idA = wallet(), idB = wallet(), bob = wallet();
        Ledger la = new Ledger(), lb = new Ledger();
        for (int i = 0; i < 120; i++) mine(la, miner);
        Node a = new Node(la, 17101, idA, null, m -> {});
        Node b = new Node(lb, 17102, idB, null, m -> {});
        a.start(); b.start();
        try {
            b.connect("127.0.0.1:17101");
            check("new node downloads 120 blocks in batches of 100", lb.height() == 120 && lb.tip().hash.equals(la.tip().hash));
            check("A learned B as a peer (via HELLO)", a.peers().contains("127.0.0.1:17102"));

            check("tx submitted at A", a.submit(miner.pay(bob.address, SM, Params.MIN_FEE, la.nextSeq(miner.address))) == null);
            waitFor(() -> lb.mempoolSize() == 1, 3000);
            check("tx gossiped to B", lb.mempoolSize() == 1);

            Block nb = mine(la, miner);
            a.syncWith("127.0.0.1:17102");                           // A is not behind: nothing happens
            b.syncWith("127.0.0.1:17101");
            check("B catches up by one block incrementally", lb.height() == 121 && lb.mempoolSize() == 0);
            check("a publisher's zero-fee attestation is accepted and gossiped",
                    a.submit(PUBS[0].attest(EconomyIndex.BASELINE, 0, la.nextSeq(PUBS[0].address))) == null);
            waitFor(() -> lb.mempoolSize() == 1, 3000);
            check("attestation reached B", lb.mempoolSize() == 1);
            check("a non-publisher's attestation is refused at submit", a.submit(bob.attest(EconomyIndex.BASELINE, 0, 0)) != null);

            check("TIP works for an authenticated peer", !raw(17101, idB, "TIP").isEmpty() && raw(17101, idB, "TIP").get(0).startsWith("121 "));
            List<String> noAuth = raw(17101, null, "TIP");
            check("request without a valid AUTH gets nothing", noAuth.isEmpty() || noAuth.get(0).startsWith("<"));
            Wallet attacker = wallet();
            check("garbage payload is survived", raw(17101, attacker, "BLOCK not-a-block").size() <= 1);
            check("node still serves after garbage", raw(17101, idB, "TIP").get(0).startsWith("121 "));
            String huge = "TX " + "A".repeat(Node.MAX_LINE + 10);
            List<String> big = raw(17101, attacker, huge);
            check("oversized line is refused (no reply)", big.isEmpty() || big.get(0).startsWith("<"));
            check("node still serves after oversized line", raw(17101, idB, "TIP").get(0).startsWith("121 "));

            Wallet other = wallet();
            a.punish(null, other.address, 60);
            check("below the threshold a peer ID is not banned", !a.isBanned(other.address));
            a.punish(null, other.address, 60);
            check("crossing 100 points bans a peer ID", a.isBanned(other.address));
            List<String> bannedReply = raw(17101, other, "TIP");
            check("a banned peer ID gets no reply to its request", bannedReply.isEmpty() || bannedReply.get(0).startsWith("<"));

            for (int i = 0; i < 4; i++) { raw(17101, attacker, "TX garbage"); Thread.sleep(60); }
            waitFor(() -> a.isBanned("127.0.0.1"), 2000);          // penalty lands just after the socket closes
            check("sustained garbage gets the source IP banned", a.isBanned("127.0.0.1"));
            List<String> after = raw(17101, idB, "TIP");
            check("banned peers are dropped before the handshake", after.size() == 1 && after.get(0).startsWith("<"));
        } finally {
            a.stop(); b.stop();
        }
    }
}

package com.techducat.speso;

import java.util.*;

/**
 * Run: java -cp out com.techducat.speso.DeflationTest
 *
 * The deflationary side of the design, one property per check:
 *   1. the issuance ceiling FALLS as the economy strengthens (base x 100 / score) and NEVER rises above the base:
 *      a weak economy lowers the value of a spesmilo, it does not inflate the supply;
 *   2. above the ceiling nothing is minted and every fee is destroyed, so the supply can only shrink;
 *   3. in a recession the supply stays where it was (the elastic variant, -Dspeso.elastic=true, would mint);
 *   4. a stronger economy makes one spesmilo worth more GBU, a weaker one less (the price side);
 *   5. every destroyed coin is counted.
 * Three publishers, two must agree; the supply base is 600 so the cap is reached in 12 blocks.
 * Params are static, hence a JVM of its own.
 */
public final class DeflationTest {
    static Wallet[] pub = new Wallet[3], h = new Wallet[5];
    static long SM;

    static void check(String n, boolean c) { SelfTest.check(n, c); }

    static Ledger setup() {                         // 12 blocks of ₷ 50: the supply reaches its ₷ 600 cap at score 100
        Ledger l = new Ledger();
        Wallet[] order = {pub[0], pub[1], pub[2], h[0], h[1], h[2], h[3], h[4]};
        for (int i = 0; i < 12; i++) SelfTest.mine(l, order[i % 8]);
        return l;
    }

    /** Pay `fee` from one holder to another and mine it with `miner`. Returns the miner's balance change. */
    static long payAndMine(Ledger l, Wallet from, Wallet to, long fee, Wallet miner) {
        long before = l.balance(miner.address);
        check("payment accepted", l.addTx(from.pay(to.address, SM, fee, l.nextSeq(from.address))) == Ledger.TxResult.ACCEPTED);
        SelfTest.mine(l, miner);
        return l.balance(miner.address) - before;
    }

    public static void main(String[] a) throws Exception {
        System.setProperty("speso.pbkdf2", "1000");
        for (int i = 0; i < 3; i++) pub[i] = SelfTest.wallet();
        for (int i = 0; i < 5; i++) h[i] = SelfTest.wallet();
        System.setProperty("speso.publishers", pub[0].address + "," + pub[1].address + "," + pub[2].address);
        System.setProperty("speso.pubthreshold", "2");
        System.setProperty("speso.initbits", "6");
        System.setProperty("speso.retarget", "6");
        System.setProperty("speso.blockms", "1000");
        System.setProperty("speso.supplybase", "600");
        System.setProperty("speso.oraclewindow", "12");
        SM = Params.SPESMILO;
        long MINTED = 600 * SM;                      // 12 blocks x 50
        long[] boom = SelfTest.with(EconomyIndex.BASELINE, 0, 500);
        long[] bust = SelfTest.with(SelfTest.with(EconomyIndex.BASELINE, 0, -200), 4, 8000);
        long fee = 4;

        SelfTest.section("The ceiling falls with the economy and never rises above the base");
        check("the strictly deflationary policy is the default", !Params.ELASTIC);
        check("score 100: the ceiling is the base supply", Params.supplyTarget(10_000) == 600 * SM);
        check("score 125: the ceiling falls to 480", Params.supplyTarget(12_500) == 480 * SM);
        check("score 80 (recession): the ceiling stays at the base, it does not rise to 750", Params.supplyTarget(8_000) == 600 * SM);
        check("a market quote below the peg lowers the ceiling", Params.adjustedTarget(10_000, 5_000) == 300 * SM);
        check("a market quote above the peg cannot lift it past the base", Params.adjustedTarget(10_000, 15_000) == 600 * SM);

        SelfTest.section("Boom: above the ceiling nothing is minted and every fee is destroyed");
        Ledger d = setup();
        check("the supply sits exactly at the cap, nothing burned yet", d.supply() == MINTED && d.burned() == 0);
        for (Wallet p : new Wallet[]{pub[0], pub[1]}) SelfTest.attestAs(d, p, boom);
        for (Wallet w : h) SelfTest.reportAs(d, w, boom);
        for (int i = 0; i < 4; i++) SelfTest.mine(d, h[3]);
        check("the economy strengthened: score is above 100", d.tip().score > 10_000);
        check("so the ceiling fell below the supply", d.effectiveTarget() < d.supply());
        long sBefore = d.supply(), bBefore = d.burned();
        long minerGot = payAndMine(d, h[1], h[2], fee, h[0]);
        check("the whole fee is burned", d.burned() == bBefore + fee);
        check("the miner gets nothing from it and no reward is minted", minerGot == 0);
        check("the supply fell by exactly the fee", d.supply() == sBefore - fee);
        boolean monotonic = true, burnedGrows = true;
        for (int i = 0; i < 4; i++) {
            long s = d.supply(), b = d.burned();
            payAndMine(d, h[1 + i % 3], h[4], Params.MIN_FEE, h[0]);
            monotonic &= d.supply() <= s;
            burnedGrows &= d.burned() > b;
        }
        check("supply never rose across 4 more blocks that carried payments", monotonic);
        check("the burned total rose in every one of them", burnedGrows);
        check("supply still equals the sum of balances", d.supply() == d.sumBalances());
        check("nothing was minted after the cap: supply + burned == everything ever minted", d.supply() + d.burned() == MINTED);

        SelfTest.section("Recession: the supply does not grow, the value falls");
        Ledger r = setup();
        for (Wallet p : new Wallet[]{pub[0], pub[1]}) SelfTest.attestAs(r, p, bust);
        for (Wallet w : h) SelfTest.reportAs(r, w, bust);
        long maxSupply = r.supply();
        for (int i = 0; i < 6; i++) { SelfTest.mine(r, h[i % 5]); maxSupply = Math.max(maxSupply, r.supply()); }
        check("the economy weakened: score is below 100", r.tip().score < 10_000);
        check("the ceiling is still the base", r.effectiveTarget() == 600 * SM);
        check("not one spesmilo was minted (the elastic variant would have)", maxSupply == MINTED && r.supply() == MINTED);

        SelfTest.section("The price side: a stronger economy makes a spesmilo worth more");
        long gbu = 10 * SM;
        check("in a boom the same 10 GBU costs fewer spesoj than at score 100", Quote.cost(gbu, d.tip().score) < Quote.cost(gbu, 10_000));
        check("in a recession it costs more", Quote.cost(gbu, r.tip().score) > Quote.cost(gbu, 10_000));

        SelfTest.section("Slashed stake is destroyed and counted");
        Ledger v = setup();
        SelfTest.attestAs(v, pub[0], EconomyIndex.BASELINE); SelfTest.attestAs(v, pub[1], EconomyIndex.BASELINE);
        SelfTest.reportAs(v, h[0], bust);
        SelfTest.mine(v, pub[2]);
        check("a contradicting report is slashed and the slash is in the burned total", v.burned() > 0);
        check("supply still equals the sum of balances", v.supply() == v.sumBalances());

        System.out.println(SelfTest.failures == 0 ? "\nDEFLATION: ALL TESTS PASSED" : "\n" + SelfTest.failures + " FAILED");
        System.exit(SelfTest.failures == 0 ? 0 : 1);
    }
}

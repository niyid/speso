package com.techducat.speso;

import java.util.*;

/**
 * Run: java -cp out com.techducat.speso.AnchoredTest
 *
 * The oracle's two keys with real publisher addresses, and what the merge of the two code lines added on top of
 * SelfTest: strict-majority publisher threshold, the mainnet launch guard, slashing of a stake reporter that
 * contradicts the publishers (so a veto costs something), publisher liveness and transaction lookup.
 * Params are static, so this needs its own JVM: the publisher addresses are generated first and handed over
 * through system properties before Params is touched. Three publishers, two must agree.
 */
public final class AnchoredTest {
    static Wallet[] pub = new Wallet[3], h = new Wallet[5];
    static long SM, FEE;

    static void check(String n, boolean c) { SelfTest.check(n, c); }

    /** 12 blocks rotate through 3 publishers + 5 holders: supply hits the 600 cap, everyone has a balance. */
    static Ledger setup() {
        Ledger l = new Ledger();
        Wallet[] order = {pub[0], pub[1], pub[2], h[0], h[1], h[2], h[3], h[4]};
        for (int i = 0; i < 12; i++) SelfTest.mine(l, order[i % 8]);
        return l;
    }

    static void attest(Ledger l, Wallet p, long[] ind) { SelfTest.attestAs(l, p, ind); }
    static void report(Ledger l, Wallet w, long[] ind) { SelfTest.reportAs(l, w, ind); }
    static void allHoldersReport(Ledger l, long[] ind) { for (Wallet w : h) report(l, w, ind); }

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
        SM = Params.SPESMILO; FEE = Params.MIN_FEE;

        long[] boom = SelfTest.with(EconomyIndex.BASELINE, 0, 500);
        long[] bust = SelfTest.with(SelfTest.with(EconomyIndex.BASELINE, 0, -200), 4, 8000);

        SelfTest.section("Configuration and launch guard");
        boolean weak = false;
        try { Params.checkPublisherConfig(3, 1); } catch (IllegalStateException e) { weak = true; }
        check("a threshold that is not a strict majority is refused", weak);
        boolean unreachable = false;
        try { Params.checkPublisherConfig(3, 4); } catch (IllegalStateException e) { unreachable = true; }
        check("a threshold above the number of publishers is refused", unreachable);
        check("the three publishers are registered, threshold 2", Params.PUBLISHERS.size() == 3 && Params.PUBLISHER_THRESHOLD == 2);
        boolean noPubs = false, noAck = false, testOk = true, mainOk = true;
        try { Params.checkLaunch("mainnet", false, true); } catch (IllegalStateException e) { noPubs = true; }
        try { Params.checkLaunch("mainnet", true, false); } catch (IllegalStateException e) { noAck = true; }
        try { Params.checkLaunch("testnet", false, false); } catch (IllegalStateException e) { testOk = false; }
        try { Params.checkLaunch("mainnet", true, true); } catch (IllegalStateException e) { mainOk = false; }
        check("mainnet is refused without publishers", noPubs);
        check("mainnet is refused without the unaudited acknowledgement", noAck);
        check("testnet starts without either; mainnet starts with both", testOk && mainOk);
        check("the network defaults to testnet", Params.NETWORK.equals("testnet") && !Params.MAINNET);

        SelfTest.section("Neither key can set the number alone");
        Ledger l = setup();
        check("score starts at baseline, supply == balances", l.tip().score == 10_000 && l.supply() == l.sumBalances());
        allHoldersReport(l, boom);
        SelfTest.mine(l, h[3]);
        check("every holder reports boom, no publisher attests: score frozen", l.tip().score == 10_000);
        check("status explains why", l.oracleStatus().contains("publishers below quorum"));
        attest(l, pub[0], boom);
        SelfTest.mine(l, h[3]);
        check("one publisher (below threshold) still cannot move it", l.tip().score == 10_000);
        Ledger e = setup();
        attest(e, pub[0], boom); attest(e, pub[1], boom);
        SelfTest.mine(e, h[0]);
        check("two publishers with a silent stake cannot move it either", e.tip().score == 10_000);
        check("status says stake has not ratified", e.oracleStatus().contains("no stake quorum"));

        SelfTest.section("Both keys agree: the score moves");
        attest(l, pub[1], boom);
        SelfTest.mine(l, h[3]);
        long s1 = l.tip().score;
        check("threshold met and stake ratifies: score moves toward the attested reading", s1 > 10_000 && s1 <= 10_200);
        int guard = 0;
        while (l.tip().score != EconomyIndex.score(boom) && guard++ < 11) SelfTest.mine(l, h[guard % 5]);
        check("converges to the attested reading", l.tip().score == EconomyIndex.score(boom));
        check("supply still equals sum of balances", l.supply() == l.sumBalances());

        SelfTest.section("A stake majority that contradicts the publishers is frozen out and pays for it");
        Ledger d = setup();
        attest(d, pub[0], EconomyIndex.BASELINE); attest(d, pub[1], EconomyIndex.BASELINE);
        allHoldersReport(d, bust);
        long before = d.balance(h[0].address);
        SelfTest.mine(d, pub[2]);
        long afterFee = before - FEE;
        check("score does not move (veto freezes it)", d.tip().score == 10_000);
        check("status reports the veto", d.oracleStatus().contains("stake vetoes the publishers"));
        long slashed = d.balance(h[0].address);
        check("the contradicting holder lost 2% of its balance", slashed == afterFee - afterFee * Params.SLASH_PCT / 100);
        check("slashed coins are burned, not paid out", d.supply() == d.sumBalances());
        SelfTest.mine(d, pub[2]);
        check("it is judged once, in the block where the report landed", d.balance(h[0].address) == slashed);

        SelfTest.section("Nobody is slashed without a publisher consensus");
        Ledger n = setup();
        allHoldersReport(n, bust);                      // stake alone says bust: no attestations to contradict
        long nb = n.balance(h[0].address);
        SelfTest.mine(n, pub[2]);
        check("a stake majority cannot slash the minority (or itself)", n.balance(h[0].address) == nb - FEE);

        SelfTest.section("A corrupt publisher minority cannot steer");
        Ledger m = setup();
        attest(m, pub[0], bust); attest(m, pub[1], boom); attest(m, pub[2], boom);
        allHoldersReport(m, boom);
        SelfTest.mine(m, h[0]);
        check("1 liar + 2 honest publishers: score follows the honest pair", m.tip().score > 10_000);
        Ledger f = setup();
        attest(f, pub[0], bust); attest(f, pub[1], boom);
        allHoldersReport(f, boom);
        SelfTest.mine(f, h[0]);
        check("2 live publishers who disagree: frozen, not steered", f.tip().score == 10_000 && f.oracleStatus().contains("publishers disagree"));

        SelfTest.section("Publisher liveness and transaction lookup");
        Ledger r = setup();
        attest(r, pub[0], boom);
        SelfTest.mine(r, h[0]);
        check("a publisher with a live attestation is reported live, a silent one is not",
                r.hasAttestation(pub[0].address) && !r.hasAttestation(pub[2].address));
        Transaction t = h[1].pay(h[2].address, SM, FEE, r.nextSeq(h[1].address));
        check("tx accepted", r.addTx(t) == Ledger.TxResult.ACCEPTED);
        long[] pend = r.findTx(t.id());
        check("findTx: pending in the mempool", pend[0] == 1);
        SelfTest.mine(r, h[0]);
        long[] done = r.findTx(t.id());
        check("findTx: confirmed at the tip's height", done[0] == 2 && done[1] == r.tip().index);
        check("findTx: an unknown id is unknown", r.findTx("0000000000000000")[0] == 0);

        SelfTest.section("The market quote reaches the target shown to thin wallets");
        Ledger q = setup();
        SelfTest.quoteAs(q, pub[0], 5_000); SelfTest.quoteAs(q, pub[1], 5_000);      // market says 0.5 GBU per spesmilo
        SelfTest.mine(q, h[0]);
        check("market below the peg: effective target < score-only target",
                q.effectiveTarget() < Params.supplyTarget(q.tip().score));
        check("no quote: effective target equals the score-only target", setup().effectiveTarget() == Params.supplyTarget(10_000));

        SelfTest.section("Block timestamps");
        check("a mined block is strictly later than the median of its predecessors",
                r.tip().time > Ledger.medianTimePast(r.tip().prev));

        System.out.println(SelfTest.failures == 0 ? "\nANCHORED: ALL TESTS PASSED" : "\n" + SelfTest.failures + " FAILED");
        System.exit(SelfTest.failures == 0 ? 0 : 1);
    }
}

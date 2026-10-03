package com.techducat.speso;

import java.math.BigInteger;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * One node of the ledger's linked list.
 *
 * `prev` points to the previous Block in memory; `prevHash` is the same link made
 * tamper-evident: change any old block and every later hash stops matching.
 *
 * `indicators` are the economy readings in force for this block. They are NOT chosen by the
 * miner: the Ledger derives them from the oracle reports on chain, and rejects any block
 * whose indicators differ from that derivation.
 *
 * `bits` is the required proof-of-work difficulty (leading zero bits of the hash). It is
 * likewise not chosen by the miner; the retargeting rule dictates it.
 *
 * Wire format (fields '#', indicators ',', txs ';', tx fields '|'):
 *   index#prevHash#time#bits#nonce#miner#ind,ind,...#tx;tx;...
 * The hash is never transmitted; every receiver recomputes it.
 */
final class Block {
    final int index;
    final String prevHash;
    final long time;
    final int bits;
    long nonce;
    final String miner;
    final long[] indicators;
    final long score;
    final List<Transaction> txs;

    Block prev;            // in-memory link (set when the block joins a chain)
    String hash;
    BigInteger cumWork;    // total work of the chain ending at this block (set on validation)

    Block(int index, String prevHash, long time, int bits, long nonce, String miner, long[] indicators, List<Transaction> txs) {
        if (indicators.length != EconomyIndex.N) throw new IllegalArgumentException("bad indicators");
        this.index = index; this.prevHash = prevHash; this.time = time; this.bits = bits; this.nonce = nonce;
        this.miner = miner; this.indicators = indicators; this.txs = txs;
        this.score = EconomyIndex.score(indicators);
        this.hash = computeHash();
    }

    private String txString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < txs.size(); i++) { if (i > 0) sb.append(';'); sb.append(txs.get(i).encode()); }
        return sb.toString();
    }

    private String head() { return index + "#" + prevHash + "#" + time + "#" + bits + "#"; }
    private String tail() { return "#" + miner + "#" + EconomyIndex.encode(indicators) + "#" + txString(); }

    String encode() { return head() + nonce + tail(); }

    String computeHash() { return Crypto.sha256(encode()); }

    BigInteger work() { return BigInteger.ONE.shiftLeft(bits); }

    static int zeroBits(String hex) {
        int n = 0;
        for (int i = 0; i < hex.length(); i++) {
            int v = Character.digit(hex.charAt(i), 16);
            if (v == 0) { n += 4; continue; }
            n += Integer.numberOfLeadingZeros(v) - 28;
            break;
        }
        return n;
    }

    boolean meetsDifficulty() { return zeroBits(hash) >= bits; }

    /** Search nonces until the hash meets `bits`. Returns false if `abort` fires first. */
    boolean solve(BooleanSupplier abort) {
        String h = head(), t = tail();
        for (long n = 0; ; n++) {
            String candidate = Crypto.sha256(h + n + t);
            if (zeroBits(candidate) >= bits) { nonce = n; hash = candidate; return true; }
            if ((n & 0x3FF) == 0x3FF && abort.getAsBoolean()) return false;
        }
    }

    static Block decode(String s) {
        String[] p = s.split("#", -1);
        if (p.length != 8) throw new IllegalArgumentException("bad block");
        String[] ip = p[6].split(",");
        if (ip.length != EconomyIndex.N) throw new IllegalArgumentException("bad indicators");
        long[] ind = new long[ip.length];
        for (int i = 0; i < ip.length; i++) ind[i] = Long.parseLong(ip[i]);
        List<Transaction> txs = new ArrayList<>();
        if (!p[7].isEmpty()) {
            String[] tp = p[7].split(";");
            if (tp.length > Params.MAX_TXS) throw new IllegalArgumentException("too many txs");
            for (String t : tp) txs.add(Transaction.decode(t));
        }
        return new Block(Integer.parseInt(p[0]), p[1], Long.parseLong(p[2]), Integer.parseInt(p[3]),
                Long.parseLong(p[4]), p[5], ind, txs);
    }

    /** Everyone starts from the same hard-coded block. */
    static Block genesis() {
        Block g = new Block(0, "0", 0L, Params.INIT_BITS, 0L, "genesis:" + Params.NETWORK_ID, EconomyIndex.BASELINE.clone(), new ArrayList<>());
        g.cumWork = BigInteger.ONE;
        return g;
    }
}

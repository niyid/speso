# The Speso

A peer-to-peer currency whose value is driven by a score for the global economy.
A 2002 idea, finished as documentation. Plain Java 17+, no dependencies, deliberately rudimentary.

**Name and unit.** Named for the *speso* proposed in 1907 by René de Saussure. In the original, the
**speso** is the tiny base unit ("purposely made very small to avoid fractions") and the
**spesmilo** (symbol **₷**, U+20B7) is 1000 spesoj. The same here: the chain stores integer spesoj,
people deal in ₷ with up to 3 decimals. The original spesmilo was pinned to 0.733 g of gold.
This one is pinned to the world economy.

## Build, test, run

Sources live under `src/main/java/com/techducat/speso/`, tests under `src/test/java/com/techducat/speso/`.

```sh
./build.sh                                          # javac if present, else the jdk.compiler module
java -cp out com.techducat.speso.SelfTest           # 119 checks: attacks on every subsystem

# every node of one network needs the SAME publisher list (their wallet addresses); it is part of the network id
export SPESO_PASSWORD='something long'
PUB='-Dspeso.publishers=<addrP1>,<addrP2>,<addrP3>'
java $PUB -cp out com.techducat.speso.Main --i-understand-unaudited --port 7000 --data nodeA --mine --publish indicators.txt
java $PUB -cp out com.techducat.speso.Main --i-understand-unaudited --port 7001 --data nodeB --peer 127.0.0.1:7000 --mine --feed indicators.txt
```

`--i-understand-unaudited` is mandatory. `--publish file` makes a node a data **publisher** (its wallet address must be in
`-Dspeso.publishers`); `--feed file` makes it a stake **reporter**. Console: `me`, `balance`, `send`, `sendgbu`, `rate`, `chain`,
`status`, `peers`, `connect`, `sync`, `mine`, `stopmine`, `quit`. A feed file may carry `market=1.02` (the market value of one
spesmilo in GBU); publishers sign it as a market quote. For quick experiments add
`-Dspeso.initbits=10 -Dspeso.blockms=1500 -Dspeso.retarget=10` to every node.

## Files

| File | Role |
|---|---|
| `Params.java` | Every consensus constant; speso/spesmilo conversion and formatting |
| `Block.java` | Linked-list node: txs, economy snapshot, difficulty, hash link, solver |
| `Ledger.java` | Chain, account state, oracle median, monetary policy, retarget, fork choice, mempool |
| `Transaction.java` | Signed envelope: pay, GBU-denominated pay, oracle report |
| `EconomyIndex.java` | The score function; feed parsing; report validation |
| `Node.java` | P2P: authenticated handshake, rate limits and bans, incremental sync, mining, reporting |
| `Store.java` | Append-only chain file + peer list |
| `Wallet.java`, `Crypto.java` | Encrypted key file, ECDSA, SHA-256 |
| `Main.java`, `SelfTest.java` | CLI; tests |

## Double-spend prevention

1. **Sequence numbers.** Each account's transactions are numbered 0, 1, 2... Only the next number is valid,
   so two payments can't share one, and a replay is dead. The signature covers the number.
2. Balance checked when applied, in order.
3. Mempool validated against confirmed state plus everything pending: first seen wins.
4. Blocks are verified by replaying their transactions on a copy of the state.
5. Fork choice is **most cumulative proof-of-work** (not longest), so difficulty changes can't fool it.
   On a reorg, transactions from abandoned blocks return to the mempool if still valid.

## The oracle: two keys

The oracle proves *agreement*, not *truth*, so no single group may be able to set the number.

- **Key 1, publishers.** A fixed set of named addresses (`-Dspeso.publishers`, hashed into the genesis block) signs
  **attestations** (`A` transactions) of the five indicators. The per-indicator median is the candidate value, and counts only if
  at least `speso.pubthreshold` publishers (default: strict majority) agree with it within tolerance.
- **Key 2, stake.** Any holder may publish a **report**. The stake-weighted median must come from reporters holding at least 1/3 of
  the supply and must lie within tolerance of the publishers' value. Stake **ratifies or vetoes**; it cannot set.
- The block's indicators must equal the candidate, moved by at most 2% in score. No quorum on either key: the score freezes.
  No publishers configured: the score is frozen at baseline (it never falls back to stake-only).

| Attacker | What they can do |
|---|---|
| Stake majority, publishers honest | Veto: freeze the score. Cannot move it. |
| Publisher quorum, stake honest | Freeze it (stake will not ratify). Cannot move it. |
| One publisher plus 100% of stake | Nothing (below publisher quorum). |
| Publisher quorum **and** stake majority colluding | Set any number. This is the residual trust. |

Reporters near the ratified value share 20% of each block reward. Publishers are a role, not a bankroll: their transactions may
carry a zero fee. Every signature also covers the network id, so a transaction signed for one network is invalid on another.

## Making the value mean something

- **Elastic supply.** Target supply = `21,000,000 ₷ x 100 / score`. Below target the block reward (max ₷ 50) is minted, never
  past the target. Above target nothing is minted and **all fees are burned**.
- **Peg feedback.** Publishers may also sign a **market quote** (`Q`): the market value of one spesmilo in GBU. When a quorum of
  them agree within 5%, the supply target is scaled by market/peg, clamped to 50%..150%. A market below the peg stops minting and
  burns fees; one above it lets supply grow. This is a feedback signal, not enforcement.
- **GBU-denominated payments** (`sendgbu`): the sender signs "pay X GBU, at most M spesoj"; the chain converts at the previous
  block's rate, so a merchant can price in GBU and receive the right number of spesmiloj.

## Hardening

| Was | Now |
|---|---|
| Fixed difficulty | Retargets every 20 blocks (±1–2 bits, clamped to 4x), timestamps checked |
| Integer amounts | Integer spesoj, shown as ₷ with 3 decimals; fractions of a speso rejected |
| Unencrypted wallet | PBKDF2-HMAC-SHA256 (200k) + AES-256-GCM; wrong password/tampering fails |
| No persistence | Append-only `chain.dat`, fully re-validated on load, corrupt tail cut off; peers saved |
| Whole-chain sync | Compares work, finds common ancestor by locator, downloads 100 blocks at a time |
| No peer authentication | Signed challenge-response on every connection gives each peer a cryptographic ID |
| No DoS protection | Line-length and reply caps, timeouts, bounded thread pools, token bucket per IP, ban scores per IP and ID, peer cap, mempool and per-account caps, minimum fee, cheapest-check-first validation |

## What this still does NOT solve

- **Truth.** Two keys remove the single point of control, but the number is still only as honest as the publishers, who are
  named, trusted parties chosen at genesis. If a publisher quorum and a stake majority collude they set the number. Publishers
  should read independent sources; several publishers copying one source are one point of failure. There is no slashing.
  The publisher side feeds from a hand-edited file; nothing here pulls from IMF or World Bank.
- **Value.** The protocol steers value (elastic supply, peg feedback, GBU settlement). It cannot make any market pay the peg
  price. Contraction is one-sided: it stops minting and burns fees; it never confiscates balances.
- **Audit.** This code has not been audited by anyone, and the tests are written by the same hands as the code. The CLI refuses to
  start without `--i-understand-unaudited`. It must not hold anything of real value.
- Not tested: a live multi-process network with real publishers running `--publish` across machines (the ledger rules and the
  two-node sync are tested; the publisher thread itself is not exercised end to end).
- Changed formats: signatures and the genesis block now bind to the network id, so chains and transactions from the previous
  version do not load.
- Peer IDs are free to make (Sybil), hence limits per IP. Nothing is encrypted on the wire. A peer's listening port is
  self-reported. Reorgs replay history (O(chain)); each block copies the account state (O(accounts)). No median-time-past rule.
  Mempool isn't persisted. One key per wallet, no HD derivation, password via environment variable or console.

<!-- usability:start -->
## Usability test

Tested with JDK 21 using `build.sh`; the three test suites pass. One mining node on testnet plus the thin wallet (`Cli`), with `-Dspeso.initbits=10 -Dspeso.blockms=1500 -Dspeso.retarget=10`. The Gradle build and the live World Bank / IMF feed were not exercised.

Video: [`usability/speso-usability-test.mp4`](usability/speso-usability-test.mp4)

**What worked**

- The whole flow worked first time: create wallet, fund, send, GBU quote, GBU payment, confirmation.
- Wallet creation warns plainly that there is no recovery.
- Errors for fractions, wrong password and a missing wallet say exactly what is wrong.

**Rough edges**

- Node unreachable prints `error: null`, with no hint to check the RPC URL.
- `missing argument` does not say which argument, or show that command's usage.
- An overspend rejection lists four possible causes in one string.
- `status` mixes raw units (`score: 10000`) with formatted values; the oracle line is long and jargon-heavy.
- The node console exits when stdin closes, so running it in the background needs a held-open stdin.

| # | Screen | What it shows |
|---|---|---|
| 1 | [Discoverability](usability/screens/01-usage.png) | Running the wallet with no arguments prints one dense usage line. |
| 2 | [Create a wallet](usability/screens/02-new.png) | Wallet creation warns plainly that there is no recovery. |
| 3 | [Get your address](usability/screens/03-me.png) | Your address needs no password. |
| 4 | [Check the node](usability/screens/04-status.png) | status prints raw field names and units (score: 10000, supply: 100000). |
| 5 | [Read the rate](usability/screens/05-rate.png) | rate shows 1 Sm = 1.0000 GBU, the five indicators and the oracle state. |
| 6 | [Check balance](usability/screens/06-balance_alice_empty.png) | A new wallet starts at zero. |
| 7 | [Price in GBU](usability/screens/07-quote.png) | quote shows the cost now and the worst case after 1, 3 and 6 blocks. |
| 8 | [Send payment](usability/screens/08-send.png) | Funding Alice from the miner wallet. |
| 9 | [Track it](usability/screens/09-tx_short_id.png) | tx reports the funding payment confirmed, with height and confirmations. |
| 10 | [Balance updated](usability/screens/10-balance_alice.png) | Alice now holds Sm 5. |
| 11 | [GBU payment](usability/screens/11-sendgbu_auto.png) | sendgbu with the 'auto' ceiling guards against a falling score. |
| 12 | [Receiver balance](usability/screens/12-balance_bob.png) | Bob received Sm 2. |
| 13 | [Pending state](usability/screens/13-send_while_paused.png) | With mining paused, a payment waits in the mempool. |
| 14 | [Pending state](usability/screens/14-tx_pending.png) | tx reports 'pending'. |
| 15 | [Pending state](usability/screens/15-status_mempool.png) | status shows mempool: 1. |
| 16 | [Confirmed](usability/screens/16-tx_confirmed.png) | Mining resumed: the same payment confirms at the next block. |
| 17 | [Node console](usability/screens/17-node_console.png) | The node's own console: help, chain, balance. |
| 18 | [Error: overspend](usability/screens/18-err_overspend.png) | Rejected, but the message lists four possible causes. |
| 19 | [Error: bad address](usability/screens/19-err_badaddr.png) | Short and clear, though it does not say what a valid address looks like. |
| 20 | [Error: fraction](usability/screens/20-err_fraction.png) | The best message in the tool: it says what is wrong and the limit. |
| 21 | [Error: missing args](usability/screens/21-err_missing.png) | 'missing argument' does not say which one, or show usage. |
| 22 | [Error: no wallet](usability/screens/22-err_nowallet.png) | Clear. |
| 23 | [Error: wrong password](usability/screens/23-err_wrongpw.png) | Clear. |
| 24 | [Error: node down](usability/screens/24-err_nonode.png) | 'error: null' is the one genuinely unhelpful message. |
<!-- usability:end -->

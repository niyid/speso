# The Speso

A peer-to-peer currency whose value is driven by a score for the global economy.
A 2002 idea, finished as documentation. Plain Java 17+, no dependencies, deliberately rudimentary.

**Name and unit.** Named for the *speso* proposed in 1907 by René de Saussure. In the original, the
**speso** is the tiny base unit ("purposely made very small to avoid fractions") and the
**spesmilo** (symbol **₷**, U+20B7) is 1000 spesoj. The same here: the chain stores integer spesoj,
people deal in ₷ with up to 3 decimals. The original spesmilo was pinned to 0.733 g of gold.
This one is pinned to the world economy. (Terminals that cannot show ₷ print `Sm` instead.)

**Status: unaudited. Do not use to hold anything of value.** The default network is `testnet`.
See [SECURITY.md](SECURITY.md).

## Deflation

Deflation was part of the idea from the start. Three things make the Speso deflationary, and two honest limits say how far it goes.

1. **A ceiling that only goes down.** The supply ceiling is `21,000,000 ₷ x 100 / score`, and it is never above `21,000,000 ₷`
   (`Params.supplyTarget`). A stronger economy lowers it. A weaker one does not raise it: the *value* of a spesmilo falls instead of
   the supply growing. (`-Dspeso.elastic=true` restores the older counter-cyclical rule, where a score below 100 lifts the ceiling.)
2. **No issuance above the ceiling, and fees are destroyed.** Above it nothing is minted and every transaction fee is burned, so the supply
   can only shrink. Stake slashed for contradicting the publishers is burned too. `rate` and `GET /v1/rate` show the running total
   (`burned since genesis`).
3. **The price side.** One spesmilo is worth `score / 100` GBU, so a growing economy makes each ₷ worth more GBU and the same goods cost
   fewer ₷. `sendgbu` and `quote` price in GBU for exactly this reason.

**Limits.**
- *The supply barely shrinks by itself.* The minimum fee is one speso (₷ 0.001). A score of 116 lowers the ceiling by about ₷ 2.9 million,
  which would take about 2.9 billion minimum-fee transactions to burn. In practice the ceiling works by **stopping issuance**; the strong
  deflationary effect is on the price.
- *It is not perpetual.* The score is built from growth *rates* around a baseline. If the world grows at the baseline rate the score sits at
  100 and the value is flat; only growth above it, or a falling stress reading, makes the spesmilo appreciate.
- *Booms are partly undone.* If the ceiling later recovers, mining resumes up to it, so coins burned below the ceiling are issued again.

## Build, test, run

Sources live under `src/main/java/com/techducat/speso/`, tests under `src/test/java/com/techducat/speso/`.

```sh
./build.sh      # javac if present, else the jdk.compiler module -> out/
./test.sh       # SelfTest + AnchoredTest + DeflationTest + ToolsTest, each in its own JVM (Params are static): 253 checks
```

### A node (full node + miner + optional RPC and automatic oracle feed)

```sh
# every node of one network needs the SAME publisher list (their wallet addresses); it is part of the network id
export SPESO_PASSWORD='something long'       # wallet password (or type it; or --plain-wallet)
PUB='-Dspeso.publishers=<addrP1>,<addrP2>,<addrP3>'
java $PUB -cp out com.techducat.speso.Main --i-understand-unaudited --port 7000 --data nodeA --mine --rpc 7100 --publish indicators.txt
java $PUB -cp out com.techducat.speso.Main --i-understand-unaudited --port 7001 --data nodeB --peer 127.0.0.1:7000 --mine --feed indicators.txt
```

`--i-understand-unaudited` is mandatory. `--publish file` makes a node a data **publisher** (its wallet address must be in
`-Dspeso.publishers`); `--feed file` makes it a stake **reporter**. A feed file may carry `market=1.02` (the market value of
one spesmilo in GBU); publishers sign it as a market quote. `--allow <peerId>` restricts a node to listed peer identities.
For quick experiments add `-Dspeso.initbits=10 -Dspeso.blockms=1500 -Dspeso.retarget=10` to every node.

Console: `me`, `balance`, `send`, `sendgbu <addr> <gbu> <max> [fee]`, `quote <gbu>`, `rate`, `publishers`, `chain`,
`status`, `peers`, `connect`, `sync`, `mine`, `stopmine`, `quit`. If stdin closes (nohup, systemd) the node keeps running.

**Networks.** `-Dspeso.network=testnet` (default) or `mainnet`. The name is part of the network id, so a transaction or
handshake made for one network is invalid on every other. `mainnet` refuses to start without `-Dspeso.publishers` (an oracle
without publishers is frozen) and without `--i-understand-unaudited`.

### Thin wallet (`Cli`): no chain, no mining, talks to any node's RPC

```sh
java -cp out com.techducat.speso.Cli --wallet alice.dat new
java -cp out com.techducat.speso.Cli --wallet alice.dat me
java -cp out com.techducat.speso.Cli --rpc http://127.0.0.1:7100 status
java -cp out com.techducat.speso.Cli --wallet alice.dat send <addr> 5
java -cp out com.techducat.speso.Cli --wallet alice.dat sendgbu <addr> 2 auto     # auto = a safe cost ceiling
java -cp out com.techducat.speso.Cli tx <id>                                      # pending / confirmed / unknown
```

Keys never leave the wallet: it signs locally, for the network id its node reports, and refuses a node on another network.
The RPC URL comes from `--rpc`, else `$SPESO_RPC`, else `http://127.0.0.1:7100`. Run `Cli` with no arguments for the list of commands.

### RPC (`--rpc <port> [--rpc-bind addr]`)

JSON over HTTP, default bind `127.0.0.1`, no TLS, no authentication (put a reverse proxy in front to expose it).
64 KB request limit, 8 worker threads (overload is shed), token bucket per client IP (429).

| Endpoint | Returns |
|---|---|
| `GET /v1/status` | network, network id, height, tip, score, supply, mempool, peers, mining, oracle |
| `GET /v1/rate` | score, GBU per spesmilo, indicators, supply vs target (target includes the market quote), difficulty |
| `GET /v1/quote?gbu=10` | cost now, worst case after 1/3/6 blocks, suggested ceiling |
| `GET /v1/account/{addr}` | balance and the next sequence number to sign with (pending included) |
| `GET /v1/publishers` | registered publishers and whether each has a live attestation |
| `GET /v1/block/{height}` | block summary with tx ids |
| `GET /v1/tx/{id}` | `pending` / `confirmed` (+ height, confirmations) / `unknown` |
| `POST /v1/tx` | body `{"tx":"<encoded signed tx>"}` -> `{"id":...}`; 400 invalid, 409 rejected (with the specific reason) |

### Automated publisher feed (`--auto-feed`)

```sh
java $PUB -cp out com.techducat.speso.Main --i-understand-unaudited --port 7000 --data pub1 --mine --rpc 7100 --auto-feed [--feed-config feed.properties] [--feed-hours 6]
java -cp out com.techducat.speso.Feed --probe                # fetch + cross-check + print, write nothing
java -cp out com.techducat.speso.Feed --out indicators.txt   # one-shot
```

Pulls **world aggregates** from the World Bank (`api.worldbank.org/v2`) and the IMF WEO (`api.imf.org/external/sdmx/3.0`,
dataflow `IMF.RES/WEO`, world code `G001`), cross-checks them, writes the five-indicator file (default
`<data>/indicators.txt`), and the node publishes it: as a **publisher** if its wallet is in `-Dspeso.publishers`, else as a
stake **reporter**. The wallet must hold enough to pay report fees (publishers pay none).

| Indicator | World Bank | IMF WEO | Tolerance |
|---|---|---|---|
| gdp | `NY.GDP.MKTP.KD.ZG` | `NGDP_RPCH` | 1.0 pt |
| inflation | `FP.CPI.TOTL.ZG` | `PCPIPCH` | 2.0 pt |
| unemployment | `SL.UEM.TOTL.ZS` | none (single source) | n/a |
| trade | `NE.EXP.GNFS.KD.ZG` | `TX_RPCH` | 3.0 pt |
| stress | none | none | held at baseline (see below) |

Rules, all failing toward silence (the oracle freezes without a publisher quorum, which is safe): both sources must answer
for the same year (latest year both have, no later than last year) and agree within tolerance, or nothing is published;
the published value is their mean; data older than `maxlag` years is refused; a feed file that stops being refreshed is
ignored after two refresh intervals, by publishers and reporters alike; every problem found is reported at once. Override
anything in `feed.properties` (see `feed.properties.example`). The feed does not produce a `market=` quote; a publisher adds
that line to the file by hand.

**Read this before trusting it:**
- *The two sources measure differently.* The World Bank weights world growth by market exchange rates, the IMF by
  purchasing power, so they legitimately differ by up to about a point; the tolerances are wide for that reason.
  The cross-check catches outages and gross errors, not small differences, and the mean is a blend of two methods.
- *The data is annual and lags.* The score will sit on one reading for months and then step, smoothed by the 2%/block cap.
- *Publishers using the same two upstreams are not independent.* Several signers of one source is one point of failure.
  For real independence, publishers should use different sources (other statistics offices, OECD) via the config file.
- *Stress is a placeholder.* There is no World Bank or IMF series for it. It is held at baseline (zero effect on the
  score) unless a number is configured, and every publisher must configure the same number.
- *Unverified defaults:* the `trade` series and the IMF world code were chosen from documentation and memory, and the
  unemployment IMF series is deliberately absent. Run `--probe` against the live APIs and adjust before use.

## Verification status

| Piece | How it was checked |
|---|---|
| Ledger, two-key oracle, slashing, deflation, peg feedback, RPC, thin wallet, feed logic | 253 automated checks: `SelfTest` 119, `AnchoredTest` 32, `DeflationTest` 28, `ToolsTest` 74 |
| Feed against World Bank / IMF **reply formats** | Fixtures in the author's reading of the formats, served by a local fake server |
| Whole pipeline across real processes | One node (publisher, reporter and miner, threshold 1) and thin wallets in separate JVMs: feed file -> attestation -> score -> payments, pending/confirmed tracking, market quote -> lower supply target (see the usability test below) |
| **Real World Bank / IMF endpoints** | **Not contacted** (the build environment could not reach them). First live run is yours: use `--probe`. |
| **Several publishers on several machines** | **Not run.** The ledger rules for three publishers are tested; the multi-machine setup is not. |
| **Gradle build** | **Not run** (Gradle was not available). `build.sh` is the tested path. Treat `build.gradle` as unverified. |

## Files

| File | Role |
|---|---|
| `Params.java` | Every consensus constant, network identity, publisher set, launch guard; speso/spesmilo conversion |
| `Block.java` | Linked-list node: txs, economy snapshot, difficulty, hash link, solver |
| `Ledger.java` | Chain, account state, two-key oracle, slashing, monetary policy, retarget, fork choice, mempool |
| `Transaction.java` | Signed envelope: pay, GBU-denominated pay, stake report, publisher attestation, market quote |
| `EconomyIndex.java` | The score function; feed parsing; report validation and tolerances |
| `Node.java` | P2P: authenticated handshake, rate limits and bans, incremental sync, mining, reporting, publishing |
| `Store.java` | Append-only chain file + peer list |
| `Wallet.java`, `Crypto.java` | Encrypted key file, ECDSA, SHA-256 |
| `Rpc.java`, `RpcClient.java`, `Json.java` | HTTP/JSON server for thin wallets; its client; a small strict JSON parser |
| `Cli.java`, `Quote.java` | Thin wallet; GBU price quotes and worst-case ceilings |
| `Feed.java` | World Bank + IMF fetch, cross-check, five-indicator file |
| `Main.java` | Full node CLI |
| `SelfTest.java`, `AnchoredTest.java`, `DeflationTest.java`, `ToolsTest.java` | Tests; four JVMs because `Params` are static |

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
  at least `speso.pubthreshold` publishers (a strict majority; weaker settings are refused at startup) agree with it within tolerance.
- **Key 2, stake.** Any holder may publish a **report**. The stake-weighted median must come from reporters holding at least 1/3 of
  the supply and must lie within tolerance of the publishers' value. Stake **ratifies or vetoes**; it cannot set.
- The block's indicators must equal the candidate, moved by at most 2% in score. No quorum on either key: the score freezes.
  No publishers configured: the score is frozen at baseline (it never falls back to stake-only).
- **A veto costs something.** A stake report that lands in a block and contradicts the publishers' agreed value by more than a
  wider band (`EconomyIndex.ANCHOR_TOL`) burns `speso.slashpct` (default 2%) of the reporter's balance. It is judged once, in the block
  where the report lands. With no publisher consensus nobody is slashed, so a stake majority can never slash the minority.

| Attacker | What they can do |
|---|---|
| Stake majority, publishers honest | Veto: freeze the score, at 2% of each contradicting reporter's balance. Cannot move it. |
| Publisher quorum, stake honest | Freeze it (stake will not ratify). Cannot move it. |
| One publisher plus 100% of stake | Nothing (below publisher quorum). |
| Publisher quorum **and** stake majority colluding | Set any number. This is the residual trust. |

Reporters near the ratified value share 20% of each block reward. Publishers are a role, not a bankroll: their transactions may
carry a zero fee and they are not paid from the bonus. Every signature also covers the network id, so a transaction signed for one
network is invalid on another.

## Making the value mean something

- **Deflationary supply.** Target supply = `21,000,000 ₷ x 100 / score`, capped at `21,000,000 ₷` (see Deflation above). Below target
  the block reward (max ₷ 50) is minted, never past the target. Above target nothing is minted and **all fees are burned**.
- **Peg feedback.** Publishers may also sign a **market quote** (`Q`): the market value of one spesmilo in GBU. When a quorum of
  them agree within 5%, the supply target is scaled by market/peg, clamped to 50%..150% and never above the 21,000,000 ₷ base. A market
  below the peg stops minting and burns fees; one above it can only lift the ceiling back toward the base. This is a feedback signal, not enforcement. `rate` and `GET /v1/rate` show the
  resulting target.
- **GBU-denominated payments** (`sendgbu`): the sender signs "pay X GBU, at most M spesoj"; the chain converts at the previous
  block's rate, so a merchant can price in GBU and receive the right number of spesmiloj. `quote` shows the cost now and the
  worst case after 1, 3 and 6 blocks; `auto` as the ceiling uses the 3-block worst case.

## Hardening

| Was | Now |
|---|---|
| Fixed difficulty | Retargets every 20 blocks (±1–2 bits, clamped to 4x); timestamps must be after the median of the last 11 blocks and not in the future |
| Integer amounts | Integer spesoj, shown as ₷ with 3 decimals; fractions of a speso rejected |
| Unencrypted wallet | PBKDF2-HMAC-SHA256 (200k) + AES-256-GCM; wrong password/tampering fails; file created `rw-------` from the first byte |
| No persistence | Append-only `chain.dat`, fully re-validated on load, corrupt tail cut off; peers saved |
| Whole-chain sync | Compares work, finds common ancestor by locator, downloads 100 blocks at a time |
| No peer authentication | Signed challenge-response, bound to the network id, on every connection gives each peer a cryptographic ID |
| No DoS protection | Line-length and reply caps, timeouts, bounded thread pools, token bucket per IP, ban scores per IP and ID, peer cap, mempool and per-account caps, minimum fee, cheapest-check-first validation |
| Stake alone sets the number | Two keys; a veto costs 2%; a feed that goes stale means silence, not old numbers |
| Careless launch | Mainnet needs publishers and `--i-understand-unaudited`; a weak publisher threshold is refused |
| Cryptic errors | Rejections say why (wrong sequence and the next valid one, insufficient funds with the amounts); an unreachable node names the URL tried |

## What this still does NOT solve

- **Truth.** Two keys remove the single point of control, but the number is still only as honest as the publishers, who are
  named, trusted parties chosen at genesis. If a publisher quorum and a stake majority collude they set the number. Publishers
  should read independent sources; several publishers copying one source are one point of failure. Publishers are not slashed.
  The automatic feed (World Bank + IMF) is untested against the live endpoints.
- **Value.** The protocol steers value (a deflationary ceiling, peg feedback, GBU settlement). It cannot make any market pay the peg
  price. Contraction is one-sided and slow: it stops minting and burns fees; it never confiscates balances (slashing aside).
- **Audit.** This code has not been audited by anyone, and the tests are written by the same hands as the code. The node refuses to
  start without `--i-understand-unaudited`. It must not hold anything of real value.
- Not tested: a live multi-machine network with several real publishers.
- Changed formats: signatures and the genesis block bind to the network id (name, publishers, slash rate and the other consensus
  settings), so chains and transactions from earlier versions do not load.
- Peer IDs are free to make (Sybil), hence limits per IP. Nothing is encrypted on the wire; the RPC has no TLS or authentication.
  A peer's listening port is self-reported. Reorgs replay history (O(chain)); each block copies the account state (O(accounts)).
  Mempool isn't persisted. No commit-reveal for reports, so lazy copying is possible. One key per wallet, no HD derivation,
  password via environment variable or console.

<!-- usability:start -->
## Usability test

Tested with JDK 21 using `build.sh` on this code: all three test suites pass (224 checks). One testnet node (a single publisher with threshold 1, which also reports and mines) and thin wallets in separate processes, with `-Dspeso.initbits=10 -Dspeso.blockms=1500 -Dspeso.retarget=10`. Commands are shown as `speso-wallet` for `java -cp out com.techducat.speso.Cli`. Recorded on the previous release; the deflation change since adds a `burned since genesis` line to `rate`. Not exercised: several publishers on several machines, the live World Bank and IMF endpoints, and the Gradle build.

Video: [`usability/speso-usability-test.mp4`](usability/speso-usability-test.mp4)

**What worked**

- The whole flow works: create a wallet, fund it, send, price a payment in GBU, pay in GBU, track a payment from pending to confirmed.
- A publisher's market quote changes the supply target as designed (market 0.90 against a rate of 1.116 cuts the target from about Sm 18.8M to Sm 15.1M).
- The launch guards work: `mainnet` without publishers, and any launch without `--i-understand-unaudited`, are refused.
- Wallet creation warns plainly that there is no recovery; errors for fractions, a wrong password and a missing wallet say exactly what is wrong.

**Found and fixed during the test**

- A thin wallet could not pay on a network with publishers: its signature was bound to a network id that its own process could not compute. It now signs for the id its node reports. The in-process tests could not see this; only the run across separate processes did.
- An unreachable node printed `error: null`. It now names the URL it tried and how to change it.
- `missing argument` now names the command and shows its usage; the no-argument usage is one aligned line per command.
- An overspend rejection listed four possible causes. It now gives the one that applies, with the amounts.
- `status` dumped raw fields (`score: 10000`). It now prints readable lines.
- The node console quit when stdin closed, so it could not run under nohup or systemd. It now keeps running.
- A bad address now says what an address is; a refused launch now exits non-zero.

**Still rough**

- The oracle status line is still long and jargon-heavy for a first-time user.
- Terminals that cannot show the spesmilo sign print `Sm`.
- There is no launcher script: the commands are `java -cp out com.techducat.speso.Cli` and `...Main`.

| # | Screen | What it shows |
|---|---|---|
| 1 | [Discoverability](usability/screens/01-usage.png) | No arguments: every command on its own line, with what it does. |
| 2 | [Create a wallet](usability/screens/02-new.png) | Wallet creation warns plainly that there is no recovery. |
| 3 | [Get your address](usability/screens/03-me.png) | Your address needs no password. |
| 4 | [Check the node](usability/screens/04-status.png) | status: network, height, rate, supply and oracle, in readable units. |
| 5 | [Read the rate](usability/screens/05-rate.png) | rate: score 111.6 means 1 Sm = 1.1160 GBU, with the five indicators. |
| 6 | [Oracle publishers](usability/screens/06-publishers.png) | publishers: the registered publisher set and whether each is live. |
| 7 | [Check balance](usability/screens/07-balance_alice_empty.png) | A new wallet starts at zero. |
| 8 | [Price in GBU](usability/screens/08-quote.png) | quote: cost now and the worst case after 1, 3 and 6 blocks. |
| 9 | [Send payment](usability/screens/09-send.png) | Funding Alice from the node's wallet. |
| 10 | [Track it](usability/screens/10-tx_confirmed.png) | tx: confirmed, with height and confirmations. |
| 11 | [Balance updated](usability/screens/11-balance_alice.png) | Alice now holds Sm 5. |
| 12 | [GBU payment](usability/screens/12-sendgbu_auto.png) | sendgbu with the 'auto' ceiling: a safe price limit chosen from the 3-block worst case. |
| 13 | [Receiver balance](usability/screens/13-balance_bob.png) | Bob received 2 GBU, which at the block's rate is Sm 1.793. |
| 14 | [Pending state](usability/screens/14-send_while_paused.png) | With mining paused, a payment waits in the mempool. |
| 15 | [Pending state](usability/screens/15-tx_pending.png) | tx reports 'pending'. |
| 16 | [Pending state](usability/screens/16-status_mempool.png) | status shows mempool 1 and mining no. |
| 17 | [Confirmed](usability/screens/17-tx_confirmed_after.png) | Mining resumed: the same payment confirms. |
| 18 | [Peg feedback](usability/screens/18-rate_market.png) | A publisher adds market=0.90: the supply target falls from about Sm 18.8M to Sm 15.1M. |
| 19 | [Node console](usability/screens/19-node_console.png) | The node's own console: publishers, quote, chain, balance. |
| 20 | [Error: overspend](usability/screens/20-err_overspend.png) | Rejected with the specific cause and the amounts. |
| 21 | [Error: bad address](usability/screens/21-err_badaddr.png) | Says what an address is and how to get yours. |
| 22 | [Error: fraction](usability/screens/22-err_fraction.png) | Says what is wrong and the limit. |
| 23 | [Error: missing args](usability/screens/23-err_missing.png) | Names the command and shows its usage. |
| 24 | [Error: no wallet](usability/screens/24-err_nowallet.png) | Clear. |
| 25 | [Error: wrong password](usability/screens/25-err_wrongpw.png) | Clear. |
| 26 | [Error: node down](usability/screens/26-err_nonode.png) | Says which URL it tried and how to point it elsewhere. |
| 27 | [Launch guard](usability/screens/27-launch_guard.png) | mainnet is refused without publishers, with a non-zero exit code. |
| 28 | [Launch guard](usability/screens/28-launch_no_ack.png) | Without --i-understand-unaudited the node refuses to start (exit 1). |
<!-- usability:end -->

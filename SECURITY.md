# Security notes

**Status: unaudited. Do not use to hold anything of value.** Default network is `testnet`; `mainnet` needs
`-Dspeso.publishers=...`. Every launch needs `--i-understand-unaudited`.

## What an auditor should look at first

1. `Ledger.State.apply / oracle / slash / payout` and `Ledger.validate`: all consensus-critical state transitions,
   including integer overflow (`Params.mulDiv*`, `MAX_AMOUNT`) and determinism (no iteration-order dependence).
2. `Transaction.payload / decode / verify`: signing format, network binding, parsing ambiguities (`|`, `#`, `;`).
3. `Ledger.reorg / replay`, `Store`: persistence and fork handling.
4. `Ledger.State.slash`, `Ledger.effectiveTarget` (the peg-feedback target) and `Ledger.rejectReason`.
5. `Node.handle`: parsing of untrusted input, DoS limits, handshake (`authMessage`).
6. `Wallet`: key encryption (PBKDF2 200k + AES-GCM), file permissions, password handling.
7. `Crypto`: ECDSA P-256 via the JDK. DER signatures are not canonical; the tx id excludes the signature for that reason.

## Threat model

| Threat | Mitigation | Residual risk |
|---|---|---|
| Double spend / replay | Per-account sequence numbers, PoW fork choice by cumulative work | 51% attack on a small network |
| Cross-network replay | Network id (name, publishers, slash rate and the other consensus settings) inside the signed payload and handshake; a thin wallet signs for the id its node reports | none known |
| Oracle manipulation by stake | Publishers set the value, stake can only ratify or veto, a contradicting report is slashed (2%) | Colluding or corrupt publisher quorum plus a stake majority |
| Oracle manipulation by miners | Block indicators are derived, never chosen | none known |
| Timestamp manipulation | Median-time-past, future limit, 4x retarget clamp | none known |
| DoS | Line/reply caps, rate limits, bans, bounded pools and mempool | Sybil with many IPs |
| Wallet theft | Encrypted key file | Weak password; malware; no HSM or HD keys |

## RPC and feed (added)

| Threat | Mitigation | Residual risk |
|---|---|---|
| RPC used to steal keys | No endpoint accepts a key; clients sign locally | none by design |
| Malicious node lies to a thin wallet | Client checks the network; signed GBU ceiling bounds price risk | Wrong balance or nonce, censorship; use your own node |
| RPC flooding | 64 KB body cap, 8 threads with load shedding, per-IP token bucket, loopback by default | Many source IPs; no TLS or auth, so do not expose it raw |
| Poisoned or hijacked data source | Two sources must agree for the same year, tolerance, age limit; any doubt means silence | Both upstreams wrong the same way; TLS trust is the JDK's; a MITM on a plain-http override |
| All publishers share upstreams | Documented; per-indicator source overrides | Correlated failure is not detected |
| Stale publisher | Feed file ignored after 2 refresh intervals | Reports already on chain live for the oracle window |

## Known gaps (not fixed)

- The reply formats of the real World Bank and IMF endpoints were not exercised from the build environment (fixtures only).
- Gradle build is unverified.
- Never run: several publishers on several machines. One publisher plus thin wallets in separate processes was run live.
- The slash rate is a consensus setting (`speso.slashpct`); every node of a network must use the same value (it is part of the network id).

- No independent review, no formal verification, no fuzzing campaign.
- Publishers are trusted, not verified; they are not slashed. Stake reporters are slashed only against a publisher consensus.
- Handshake does not bind to the server identity (relay is possible, but nothing sensitive is sent).
- No commit-reveal for reports; lazy copying is possible.
- Unbounded history replay on reorg; no checkpoints; state copied per block.

# FIX-201 — the audit of `634983a..99d8d04`, per commit

Status: CLAIMED, under review. The verdicts below are what the BACKLOG row of FIX-201 says the audit found
(2026-11-15); the audit left no per-commit table, so this one is rebuilt from the row and the rows it created.
The reviewer's pass re-derives each line; its verdict is recorded at the end.

A fix is lost when its assertion no longer exists anywhere on main — per commit, not per file.
Paths in the diffs are the old ones (`io/flottila`, modules `flottila-*`); main is `platform/<module>/…/dev/rail8/…`.

## Code and test commits (19) — diffs in `agent/work/FIX-201/<hash>-….diff`

| commit | subject | claimed | row |
|---|---|---|---|
| 0adcbed | an Error can no longer escape Delivery.apply() from the ingress save | LOST | FIX-204 |
| 19ee0fa | FIX-131 pin the stitched timeline a redelivered message leaves | LOST | FIX-207 |
| 232ed9d | give a swallowed interrupt back to the caller of closeAll | CARRIED | — |
| 261c657 | FIX-123 pin the persist warning's sentence | CARRIED | — |
| 27d4b9b | pin the orphaned flush window that fires early, and say so in flush() | LOST | FIX-205 |
| 47c9b85 | the recorder's own failure can no longer replace the throwable it rethrows | CARRIED | — |
| 4cbc121 | FIX-62 unstarted clears the flows it discarded out of built | LOST | FIX-206 |
| 7dc0555 | ci(ledger): guard the ledger tables on cell shape, never on pipe count | CARRIED | — |
| 94b6d02 | one producer that will not close no longer strands the others | CARRIED | — |
| 9a9c916 | FIX-133 Recorder catches Throwable, so a crashed step leaves a row | CARRIED | — |
| 9c13830 | FIX-128 exercise a non-zero warmupFailed in the bench record | LOST | FIX-208 |
| 9ce63c7 | FIX-59 validate the S3 prefix, FIX-60 bound the archive fetch | LOST | FIX-196 |
| a31a26d | FIX-132 ledger rows made well-formed, by cell shape not pipe count | LOST | FIX-197 |
| a35e778 | FIX-54, FIX-56, FIX-57 pin the texts their assertions could not fail on | CARRIED | — |
| bf29933 | FIX-121 the TrailTest warning counts stop reading the whole JVM | CARRIED | — |
| e65f616 | FIX-130 the last bare-id log filter in TrailTest | CARRIED | — |
| e835f43 | FIX-71 BatchingWriter catches Error, and a refused window commits | LOST | FIX-195 (landed a6b12011) |
| e8bc787 | FIX-134 put warmedUp in the bench record | LOST | FIX-208 |
| eb15a31 | a failing park no longer replaces the failure it was recovering from | LOST | FIX-203 |

Claimed totals: 9 CARRIED, 10 LOST, 0 superseded. The split CARRIED/LOST above is inferred from the rows the
audit created; the audit's own per-commit lines are not on file.

## Doc-only commits, audited (9) — diffs in `agent/work/FIX-201-docs/<hash>.diff`

Claimed totals: 1 CARRIED, 8 LOST, 0 contradicted; rows FIX-210 and FIX-211 new, the rest absorbed into
FIX-203, FIX-205 and FIX-206. Which commit carries which verdict is not on file; inferred where the row names it.

| commit | subject | claimed | row |
|---|---|---|---|
| 45e9dd4 | docs(backlog): FIX-62 integrated as 4cbc121 | ? | FIX-206 ? |
| 59bbad3 | docs(findings): F-30's permit hold is bounded, not a permanent leak | LOST | FIX-211 |
| 6a0ee21 | docs(agent): make FIX-139's red mean something | ? | ? |
| 7ccbaad | docs(core): say that park can throw before it returns a future | ? | FIX-203 ? |
| 8105b0f | docs(findings): F-30 settles D-049 (d), and measures how far its pattern reaches | LOST | FIX-211 |
| 99d8d04 | docs(core,runtime): record why the eight narrow catches are right | LOST | FIX-210 |
| c42887a | docs(core): takeLocked's loop now carries the invariant that makes it a loop | ? | FIX-205 ? |
| cbd563b | docs(agent): integrated-tree evidence, and packets for FIX-139 and FIX-140 | ? | ? |
| e885ed8 | docs: brainstorming prompt for CONSIDER-13, the message log | ? | ? |

## Doc-only commits, claimed superseded without an audit (7) — diffs in `agent/work/FIX-201-docs/superseded/<hash>.diff`

The row calls all seven "superseded handoff or journal states". Three of them touch `docs/DECISIONS.md`,
`docs/ARCHITECTURE.md` and findings, which a later handoff does not supersede: to be judged.

| commit | subject | files beyond HANDOFF / PROGRESS / BACKLOG / NOTES |
|---|---|---|
| 07bbac6 | docs: fold six stray PROGRESS entries into the journal | — |
| 45d4100 | docs(handoff): bring it back inside the 20-line limit | — |
| 585b6f4 | docs: CONSIDER-11 §6, D-103, D-104, F-27, and the records for three tasks | ARCHITECTURE, DECISIONS, findings/F-27 |
| 63e2b9c | chore(handoff): state after the design ledger and the FIX-62 integration | — |
| 7863381 | docs(handoff): state after FIX-133 and FIX-134 | — |
| 94fceab | docs: message identity (D-107..D-110, F-29), the error-rate finding, and the rows they spawned | ARCHITECTURE, DECISIONS, F-28, F-29, packets, reviewer memory |
| a0b3f74 | docs: CONSIDER-11 design — F-26, the §5 http.status correction and D-102 | ARCHITECTURE, DECISIONS, findings/F-26 |

## Review

Not yet reviewed.

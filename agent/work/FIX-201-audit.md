# FIX-201 — the audit of `634983a..99d8d04`, per commit

Status: REVIEWED AND CORRECTED (2026-11-15). The audit left no per-commit table; its claims were rebuilt from the
BACKLOG row, then re-derived by two reviewers and a second pass. Round 1 found the claims wrong in both halves;
round 2 found the document hunks of the code commits unaudited. The reviewers' hand-backs are kept as received in
`agent/work/FIX-201-audit-review.md`. The verdicts below are the reviewed ones: the three tables per commit judge
code and tests (and, for the doc-only commits, their documents); the net diff per document and the two row tables
after them cover the documents of all 35 commits. Every loss names the row that carries it.

A record is lost when what it says exists nowhere on main — per commit and per hunk, not per file. The archive
`agent/archive/branch-design/` counts as "on main, archived". Paths in the diffs are the old ones (`io/flottila`,
modules `flottila-*`); main is `platform/<module>/…/dev/rail8/…`. Every id of the branch (D-n, F-n, FIX-n) names
something else on main: a row cites the commit's sha, never the branch's id.

## Code and test commits (19) — diffs in `agent/work/FIX-201/<hash>-….diff`

| commit | subject | verdict | row |
|---|---|---|---|
| 0adcbed | an Error can no longer escape Delivery.apply() from the ingress save | LOST | FIX-204 |
| 19ee0fa | FIX-131 pin the stitched timeline a redelivered message leaves | LOST | FIX-207 |
| 232ed9d | give a swallowed interrupt back to the caller of closeAll | CARRIED | — |
| 261c657 | FIX-123 pin the persist warning's sentence | CARRIED | — |
| 27d4b9b | pin the orphaned flush window that fires early, and say so in flush() | LOST | FIX-205 |
| 47c9b85 | the recorder's own failure can no longer replace the throwable it rethrows | CARRIED | — |
| 4cbc121 | FIX-62 unstarted clears the flows it discarded out of built | LOST | FIX-206 |
| 7dc0555 | ci(ledger): guard the ledger tables on cell shape, never on pipe count | PARTLY CARRIED — the guard and its 19 test cases are on main; the two ci.yml steps are not | FIX-199 |
| 94b6d02 | one producer that will not close no longer strands the others | CARRIED | — |
| 9a9c916 | FIX-133 Recorder catches Throwable, so a crashed step leaves a row | PARTLY CARRIED — Recorder and four RecorderTest cases are on main; its rewrite of RedeliveredTimelineTest's case 2 is not | FIX-207 |
| 9c13830 | FIX-128 exercise a non-zero warmupFailed in the bench record | LOST | FIX-208 |
| 9ce63c7 | FIX-59 validate the S3 prefix, FIX-60 bound the archive fetch | LOST | FIX-196 |
| a31a26d | FIX-132 ledger rows made well-formed, by cell shape not pipe count | LOST | FIX-197 |
| a35e778 | FIX-54, FIX-56, FIX-57 pin the texts their assertions could not fail on | PARTLY CARRIED — FIX-54's assertions are on main and FIX-56 needed no change; FIX-57's three exact-text assertions and the three rows' closures are not | FIX-57 (ready); FIX-54 and FIX-56 closed again |
| bf29933 | FIX-121 the TrailTest warning counts stop reading the whole JVM | CARRIED | — |
| e65f616 | FIX-130 the last bare-id log filter in TrailTest | CARRIED | — |
| e835f43 | FIX-71 BatchingWriter catches Error, and a refused window commits | LOST; re-applied by FIX-195 (done) in part — three of its cases are missing by name | FIX-205 judges the three |
| e8bc787 | FIX-134 put warmedUp in the bench record | LOST | FIX-208 |
| eb15a31 | a failing park no longer replaces the failure it was recovering from | LOST | FIX-203 |

Totals: 6 CARRIED, 3 PARTLY CARRIED, 10 LOST (the audit claimed 9 and 10).

## Doc-only commits, audited (9) — diffs in `agent/work/FIX-201-docs/<hash>.diff`

| commit | subject | verdict | row |
|---|---|---|---|
| 45e9dd4 | docs(backlog): FIX-62 integrated as 4cbc121 | LOST — the row's evidence cell; right today only because the code was reverted too | FIX-206 |
| 59bbad3 | docs(findings): F-30's permit hold is bounded, not a permanent leak | PARTLY LOST — the correction is nowhere on main; its packets go with their tasks | FIX-211; FIX-204, FIX-203 |
| 6a0ee21 | docs(agent): make FIX-139's red mean something | SUPERSEDED — a one-off instruction for a task that is done; main's standing rule says the opposite on purpose (`.claude/agents/test-author.md` :21-23) | — |
| 7ccbaad | docs(core): say that park can throw before it returns a future | PARTLY LOST — the Parking Javadoc, the citations and the ParkingTest comment; the branch's catch-width rule (its D-113) with ten verdicts; two live defects (its FIX-149, FIX-150); a NOTES fact | FIX-203; FIX-210; FIX-238, FIX-239; the note is back in `agent/NOTES.md` |
| 8105b0f | docs(findings): F-30 settles D-049 (d), and measures how far its pattern reaches | PARTLY LOST — the finding and its sweep of 165 clauses; the packets go with their tasks | FIX-211; FIX-204, FIX-205 |
| 99d8d04 | docs(core,runtime): record why the eight narrow catches are right | LOST — the eight comments; the sites are at the stated widths | FIX-210 |
| c42887a | docs(core): takeLocked's loop now carries the invariant that makes it a loop | PARTLY LOST — the Javadoc and the invariant; the branch's rows FIX-144 (a flaky self-test) and FIX-148 | FIX-205; FIX-240 |
| cbd563b | docs(agent): integrated-tree evidence, and packets for FIX-139 and FIX-140 | SUPERSEDED / CARRIED — the packet's rule is in `ci/ledger_check.py`, FIX-140's code is carried, the rest is branch state | — |
| e885ed8 | docs: brainstorming prompt for CONSIDER-13, the message log | CARRIED — the file is on main with the same text | — |

Totals: 1 CARRIED, 2 SUPERSEDED, 6 LOST or PARTLY LOST (the audit claimed 1 CARRIED, 8 LOST, 0 contradicted).

## Doc-only commits the audit called superseded (7) — diffs in `agent/work/FIX-201-docs/superseded/<hash>.diff`

| commit | subject | verdict | row |
|---|---|---|---|
| 07bbac6 | docs: fold six stray PROGRESS entries into the journal | SUPERSEDED — main kept its journal; the six entries are in it | — |
| 45d4100 | docs(handoff): bring it back inside the 20-line limit | SUPERSEDED — handoff state | — |
| 63e2b9c | chore(handoff): state after the design ledger and the FIX-62 integration | SUPERSEDED — handoff state | — |
| 7863381 | docs(handoff): state after FIX-133 and FIX-134 | SUPERSEDED — handoff state | — |
| 585b6f4 | docs: CONSIDER-11 §6, D-103, D-104, F-27, and the records for three tasks | PARTLY — branch D-104 is main's D-146; the §6 draft, branch D-103 and F-27 are archived and CONSIDER-15's input; the branch's row FIX-122 (testkit PIT 74 %) was lost; one fact of its journal goes to FIX-196 | CONSIDER-15; FIX-242; FIX-196 |
| 94fceab | docs: message identity (D-107..D-110, F-29), the error-rate finding, and the rows they spawned | PARTLY — branch D-105..D-110 and F-29 are archived, D-108's keying is main's D-140; the owner's decisions among them were not adopted and D-107 contradicts D-136; branch F-28 (the error rate) is nowhere on main; three packets are on main, the fourth's task is carried; a reviewer-memory hunk is lost | CONSIDER-25; FIX-244; two of the memory's facts are back in `agent/NOTES.md` |
| a0b3f74 | docs: CONSIDER-11 design — F-26, the §5 http.status correction and D-102 | PARTLY — branch D-102 is CONSIDER-15's input; F-26's correction of the section 5 table was not carried | CONSIDER-15; FIX-241 |

## Found beside the commits

- Ids of the branch cited in carried code (`Producers.java:15`, `:59`, `:62` "FIX-141, F-30"; `Recorder.java:87`, `:91`
  "D-111"; `ci/ledger_check.py:2` "FIX-139"): row FIX-243. The same in rows FIX-197, FIX-203, FIX-207, FIX-208:
  corrected in the rows.
- The closures of FIX-54 and FIX-56, made on the branch with a review PASS and lost at the merge: restored in the rows.
- Commit 0adcbed is not code-only (round 2): its DECISIONS hunk amends D-056 and its finding hunk is the fourth
  correction of the branch's F-30. Rows FIX-204 and FIX-211 carry them.

## The branch's net diff, per document (`git diff 634983a 99d8d04`, 39 files outside `src/`)

Round 2 found that the tables above judged the 19 code commits by code and tests only. Their document hunks were
then audited as one net diff per document, which covers every document hunk of all 35 commits at once. The branch's
own text of any file is `git show 99d8d04:<path>` (the journal is `PROGRESS.md` at the root there); 99d8d04 is in
main's history.

| document | what the branch changed | fate on main |
|---|---|---|
| `PROGRESS.md` (+492), `docs/PROGRESS.md` (−56) | 25 entries dated 2026-11-1x; 07bbac6 folded the stray file in | SUPERSEDED as a journal — main kept `docs/PROGRESS.md` and archived the older entries. 16 headings of the branch are in neither file (`agent/work/FIX-201-net/PROGRESS-missing-headings.txt`): WF-1 is on main under another heading; the other 15 are the entries of tasks whose substance is carried or rowed in the tables here. DROPPED: their evidence lines name the branch's tree, logs and counts |
| `agent/BACKLOG.md` | 48 rows spawned, 27 changed | per row, the two tables below |
| `agent/HANDOFF.md` | state | SUPERSEDED — handoff state |
| `agent/NOTES.md` (+290) | 275 lines main lacks, two whole sections among them | LOST — row FIX-253; the lines are in `agent/work/FIX-201-net/NOTES-missing.txt` |
| `docs/DECISIONS.md` | rows D-102 to D-113; D-056 amended; pipe escapes in D-040, D-044, D-053, D-057, D-080 | D-102 to D-110: archived (`agent/archive/branch-design/decisions-extract.md`); D-104 is main's D-146, D-108's keying is D-140, D-102, D-103, D-105 are CONSIDER-15's input, the owner's D-106, D-107, D-109, D-110 are CONSIDER-25. D-111: in no file — FIX-243. D-112: in no file — FIX-205. D-113: in no file — FIX-210. D-056's amendment — FIX-204. The escapes — FIX-197 |
| `docs/ARCHITECTURE.md` (+34) | §5 `http.status` moved from `headers` to `props`; §6 "User-defined error handling"; §6 a handled run is a successful run; three changelog lines | not adopted; the whole file is archived (`agent/archive/branch-design/ARCHITECTURE-branch.md`). §5 — FIX-241. §6 — CONSIDER-15's input (`agent/work/CONSIDER-15-designer.md`) |
| `docs/findings/F-26.md` to `F-30.md` | five findings | F-26, F-27, F-29: archived under their branch ids; F-26's correction — FIX-241. F-28 — FIX-244. F-30 — FIX-211 |
| `agent/packets/` (21 files) | the branch's task packets | 11 are on main with the same text (CONSIDER-13 ×3, FIX-123, 128, 131, 133, 134, 59-60, 62, 71). 10 exist only on the branch, because main gave the id to another task and its packet took the path (FIX-130, 136, 137, 138, 139, 140, 141, 142, 143, 146): their tasks are carried or rowed above; the decisions two of them hold (the packets of 0adcbed and 27d4b9b) are in FIX-204 and FIX-205 |
| `ci/ledger_check.py`, `ci/test_ledger_check.py` | the ledger guard | CARRIED (7dc0555) |
| `.github/workflows/ci.yml` (+4) | the guard's two steps | LOST — FIX-199 |
| `ci/coverage-baseline.json` | one number | SUPERSEDED — main's baseline is its own |
| `.claude/agent-memory/` (reviewer +9, test-author 5 lines) | two agents' memories | DROPPED — see below |

## The rows the branch spawned (48)

Texts: `git show 99d8d04:agent/BACKLOG.md`. The ids are the branch's; on main most of them name other tasks.

| branch row | at the tip | fate on main |
|---|---|---|
| CONSIDER-11a to 11g (7) | ready; 11g blocked | SUPERSEDED — the implementation rows of the branch's error-handling design, which main did not adopt; CONSIDER-15 decides the design and spawns its own rows |
| CONSIDER-12, 12a, 12b, 12c (4) | pending; 12a ready | SUPERSEDED — the implementation rows of the branch's D-107 and D-108; 12a's keying is main's D-140, the rest waits for the owner in CONSIDER-25 |
| CONSIDER-13 | blocked | ROWED — main's OPS-2 and D-147 (the message log); its three packets are on main |
| FIX-121 | done | CARRIED — bf29933 |
| FIX-122 | ready | ROWED — FIX-242 |
| FIX-123 | done | CARRIED — 261c657 |
| FIX-124 | ready | SUPERSEDED — `.gitignore:16-17` ignores `.jqwik-database` |
| FIX-125 | done, refuted | DROPPED — closed on the branch without a code change: the NPE of `Map.copyOf(kept)` is unreachable; the inductive argument is in the branch's NOTES (FIX-253) |
| FIX-126 | ready | ROWED — FIX-235 (1) |
| FIX-127 | ready | ROWED — FIX-245 |
| FIX-128 | done | LOST — 9c13830; FIX-208 |
| FIX-129 | ready | ROWED — FIX-246 |
| FIX-130 | done | CARRIED — e65f616 |
| FIX-131 | done | LOST — 19ee0fa; FIX-207 |
| FIX-132 | done | LOST — a31a26d; FIX-197 |
| FIX-133 | done | PARTLY CARRIED — 9a9c916; FIX-207 |
| FIX-134 | done | LOST — e8bc787; FIX-208 |
| FIX-135 | ready | ROWED — FIX-247 |
| FIX-136 | done | LOST — 0adcbed; FIX-204 |
| FIX-137 | done | LOST — 27d4b9b; FIX-205 |
| FIX-138 | done | LOST — c42887a; FIX-205 |
| FIX-139 | done | PARTLY CARRIED — 7dc0555; FIX-199 |
| FIX-140 | done | CARRIED — 47c9b85 |
| FIX-141 | done | CARRIED — 94b6d02 |
| FIX-142 | done | LOST — eb15a31; FIX-203 |
| FIX-143 | done | LOST — 99d8d04; FIX-210 |
| FIX-144 | ready | ROWED — FIX-240 |
| FIX-145 | done | CARRIED — 232ed9d |
| FIX-146 | done | SUPERSEDED — 07bbac6; main kept its own journal (FIX-198 is main's row about it) |
| FIX-147 | done | LOST — 7ccbaad; FIX-203 |
| FIX-148 | ready | ROWED — FIX-240 |
| FIX-149 | ready | ROWED — FIX-238 |
| FIX-150 | ready | ROWED — FIX-239 |
| FIX-151 | ready | ROWED — FIX-248 |
| FIX-152 | ready | SUPERSEDED — it corrects line cites in the branch's D-111, D-113 and a row that are not on main; FIX-210 and FIX-243 re-record them anchored to names |
| FIX-153 | ready | ROWED — FIX-249 |
| FIX-154 | ready | ROWED — FIX-250 |
| FIX-155 | ready | ROWED — FIX-251 |
| FIX-156 | ready | ROWED — FIX-252 |

Whether the five open faults were still present was read on main by a scout on 2026-11-15 (FlowWorker.java:681-704,
Producers.java:55-72, FlowCloseTest.java:147-153, Trail.java:32-33 and :177-178, no logging handler in any main
source); the rows FIX-245 to FIX-250 carry the lines.

## The rows the branch changed (27)

| rows | what the branch changed | fate on main |
|---|---|---|
| FIX-16, 18, 19, 22, 23, 24, 25, 26, 27, 58, 64, 74, 75, 120 (14) | a triage note of 2026-11-14 in the description ("AUDIT: LIVE", or re-anchored lines only) | DROPPED — each note reads LIVE or only re-anchors lines, and the anchors are the branch's tree, which the rename, the restructure and the later fixes moved; the rows are open on main as they were |
| FIX-20, FIX-63, FIX-73 | the same triage, read as half done, done in its production half, probably stale | KEPT — a note on each of main's rows |
| FIX-10, FIX-15 | pipe escapes (a31a26d) | LOST — FIX-197 |
| FIX-54, FIX-56 | done | closures restored on main (round 1) |
| FIX-57 | done | LOST — a35e778; the row is `ready` on main and says so |
| FIX-59, FIX-60 | done | LOST — 9ce63c7; FIX-196 re-applies and closes them; noted on the rows |
| FIX-62 | done | LOST — 4cbc121; FIX-206 re-applies and closes it; noted on the row |
| FIX-71 | done | LOST — e835f43; re-applied by FIX-195 (done); the row is closed on main through it |
| CONSIDER-11 | done: the owner signed off the §6 section | the item is main's CONSIDER-15 (renumbered at the merge), open: the design was not adopted and is its input; main's CONSIDER-11 is another item |

## Dropped, with the reason

- 07bbac6's note "never pair two lists by their order": a remark without its context; not restored.
- The agent-memory hunks (reviewer +9 lines, test-author 5; among them 7ccbaad's and 94fceab's): an agent's memory is
  written by that agent, never by the session; the general facts of the hunks are in `agent/NOTES.md`, the rest is in
  `git diff 634983a 99d8d04 -- .claude/agent-memory`.
- The 15 journal entries, the 14 triage notes and the branch's row FIX-125: reasons in the tables above.
- The branch's HANDOFF: state.

## Not checked by any pass

Named so that nobody reads these tables as more than they are: 27d4b9b's test content line by line; 9ce63c7 and
19ee0fa beyond test names and production signatures; whether main pins the three missing behaviours of e835f43
under other wording (row FIX-205 decides it); the CONSIDER-13 draft's text against OPS-2; the bodies of the ten
packets that exist only on the branch, beyond the two decisions named above; the 275 NOTES lines one by one
(row FIX-253 does that); the argument that closed the branch's row FIX-125, against main's Deployments (it was made on
the branch's tree); the branch's triage notes against main's code, except the three kept; on main, for FIX-245:
Node.start, Main and whether `cancelContained`'s caller holds the lock.

## Review

Round 1 (two reviewers, class B): FAIL in both halves — the totals, three commits claimed CARRIED that are carried
in part, losses without a row. Second pass on what round 1 left: branch F-28 lost; the owner's decisions archived
but not adopted, one in conflict with main; three small notes lost. Round 2: FAIL — every sentence of round 1 and of
the second pass had become true, but the document hunks of the code commits and the rows the branch spawned had
been judged by nobody; answered by the net diff per document and the two row tables above.
Round 3: FAIL — one sentence: row FIX-204's summary of D-056's amendment named the wrong catches and gave the
opposite reason for refusing the multicatch; rewritten from the branch's text. Round 4: PASS.

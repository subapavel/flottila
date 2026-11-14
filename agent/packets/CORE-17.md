# CORE-17 — Postgres pools by role (F-27 I4) [C]

## Design (designer draft 2026-11-11, approved by the architect; D-130 draft — review at hand-over)
Today one pool of FLOTTILA_PG_POOL_SIZE (16) at Node.java:210-212 serves intake, work, background and admin.
- P1 Two pools. intake = PgIntake (groups, XACT_STATUS) + the Journal's PgSegmentRows (Node.java:238); PgDurability.check runs
  on it. work = everything else (PgWorkQueue, PgMessageStore, PgDlq, TieredBlobStore, NOTIFY sends, Retention, sweeper rows,
  deployments, JournalKeys, health select 1, every admin read). Membership and LISTEN stay dedicated. The ACK path touches only
  the intake pool (wake/announce after commit stay fire-and-forget on work).
- P2 NodeConfig.Pg gains last component `int intakePoolSize`; 9/8/6-arg ctors use `intakeDefault(poolSize) =
  max(1, min(5, poolSize / 2))`; `workPoolSize() = poolSize - intakePoolSize`. Node.start builds both like today's pool and closes
  both where `pool` closes now, each even if the other's close fails. No new class/interface/wrapper.
- P3 `connectOptions(Pg, String role, String nodeId)` = connectOptions(pg) (still the one source of pins + TLS) +
  application_name `flottila-<role>@<nodeId>`, roles intake, work, membership, listen.
- P4 Keys: FLOTTILA_PG_POOL_SIZE = total pooled connections of both pools, 2..1024 (16; floor rises from 1).
  FLOTTILA_PG_POOL_INTAKE 1..1023, < POOL_SIZE; unset → formula (16 → 5/11, 8 → 4/4, 4 → 2/2, 2 → 1/1).
  FLOTTILA_INTAKE_GROUP_CONCURRENCY 1..64, bound max(1, POOL_INTAKE − 1); unset min(4, bound). Per node 18 connections as today.
- P5 Refusals at start naming the key(s): POOL_SIZE non-number/<2/>1024; POOL_INTAKE non-number/out of 1..1023; POOL_INTAKE ≥
  POOL_SIZE (names both: the work pool keeps ≥ 1); CONCURRENCY above the bound (names it and POOL_INTAKE). Pg ctor refuses nothing new.
- P6 Docs: D-130 (amends D-109's bound "over POOL_SIZE"); ARCHITECTURE §4 new paragraph "Postgres pools by role", §4 durability
  / Pickup / §7 table :314 / §9 :368 wording "the pool" → per role; changelog row 3.9.
## Architect decisions on the designer's open points
- O1 two roles (F-27 I4 = intake apart from everything else); FLOTTILA_PG_POOL_BACKGROUND later is additive.
- O2 PoolOptions wait queue / acquire timeout stay at the client defaults as today → FIX-172 (finite wait queue and acquire
  timeout, refused waiter semantics); verify the defaults there with javap. Not in CORE-17.
- O3 floor 2 kept; NodeFailStopIT/PgTlsIT at size 2 (work pool of 1) are the detector of a nested acquire.
- O4 measurement: A/B/A/B, A = 32840d4 (one shared pool), B = CORE-17 at defaults (5+11), base-run.sh settings, r3000 recording
  none and r2000 recording all; indicative (owner 2026-11-11), a change counts only beyond the same-commit spread; worse → finding.
  Bench JSON gains intakePoolSize.
## Red tests (test-author)
NodeConfigPoolsTest (defaultsSplitFiveEleven; derivedIntakeDefault 2,3,4,8,10,16,1024 → 1,1,2,4,5,5,5; explicitIntake 32/9 →
9/23; refusesNamingTheKey for every P5 case); NodeConfigIntakeGroupTest:50,69-98 expectations rewritten (bound over the intake
pool — architect-approved wrong expectation); NodeConnectOptionsTest.everyRoleCarriesThePinsTlsAndItsName (4 roles);
NodePoolRolesIT.everySessionNamesItsRole (4/2: intake ≤2, work ≤2, membership 1, listen 1, all @node);
NodePoolRolesIT.aSaturatedWorkPoolDoesNotDelayASave (parking flow, `lock table dlq in access exclusive mode` held, 3 POSTs, until
2 flottila-work sessions wait on a Lock, probe POST 202 + its messages row committed, still 2 waiting, rollback → 4 dlq rows,
close → 0 connections). No sleeps.

## Red approved (2026-11-11)
NodeConfigPoolsTest :42 :54 :75 :103 :109; NodeConfigIntakeGroupTest :52 :73 :95 (wrong expectation by design: bound over the
intake pool); NodeConnectOptionsTest:78 (toJson = connectOptions(pg) + application_name only); NodePoolRolesIT :170 :197.
Red: compile (CORE-17-red.log); shims: unit 7 failures (-red-shim-unit.log), IT intake-on-work 503 at PT5S, shared pool, no
intake close (-red-it-*.log); scratch reference green 3× (-ref-it-*.log). Accepted unpinned: intake ≤ 2 upper bound, "close
both even if one fails", 18 per node at 16 (arithmetic), docs, bench.

## Review r1 FAIL → fix round (architect, 2026-11-11)
Approved red/added tests: NodeConfigPoolsTest:126 anInProcessNodeRefusesASplitThatLeavesAPoolNoConnection (red observed, CORE-17-r2-red-unit.log),
:144 anInProcessNodeAcceptsEverySplitWithAConnectionInBothPools; NodeConfigPoolsInProcessTest:44 anInProcessNodeDefaultsItsGroupsToTheWorkedValues;
NodePoolRolesIT:198 theListenSessionIsTheOneThatListens (red under the membership/listen swap, CORE-17-r2-red-it-swap.log).
Also approved (written by the implementer in r1): NodeConfigPoolsInProcessTest, NodePoolRolesJournalRowsIT.
Fix: R1 NodeConfig compact ctor refuses intake < 1 || intake >= poolSize naming both keys; intakeDefault javadoc "for poolSize ≥ 2"; D-130 P5 sentence per review.
R3 §4 sentence on every journal segment row. R2 the O4 A/B measurement in bench/RESULTS.md (verifier, after the fix).

## Round 3 (architect, 2026-11-11): default total 21 (F-33)
O4 A/B + diagnostics (agent/work/CORE-17-ab/, -diag/): 16 = 5 + 11 grows the r2000-all backlog; 21 = 5 + 16 flat. Decision: default FLOTTILA_PG_POOL_SIZE 21.
Approved wrong-expectation fixes (red observed, CORE-17-r3-red.log, -red-bench.log): NodeConfigPoolsTest defaultsSplitFiveSixteen, explicitIntake (20 of 21),
derivedIntakeDefault row 21/5/16, null-total refusal {null,21}; NodeConfigIntakeGroupTest:74 text; NodeBenchMainIT:206, NodeBenchAckCommitsIT:69 poolSize 21.
Also approved wrong-expectation fixes (r3, implementer, env default pinned): NodeConfigTest:66 defaults, LedgerMainPartsTest:65 (16 → 21).
M8 (PgDurability.check on the work pool) accepted as equivalent: both pools share every connect option but application_name.
Review r2 FAIL: (1) NodeTakeoverLedgerIT 10/11 red on the tree → FIX-156 first, then CORE-17 6/6 alone + green verify; (2) RESULTS entry; (3) D-130 P5 sentence (done).

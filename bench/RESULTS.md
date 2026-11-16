# Benchmark results

Every measured number with machine label, date, configuration and commit. A number that is not
in this file does not exist (`AGENTS.md`, Evidence vocabulary). Written by the architect from
verifier output; newest section first.

## #119: recursion, `limit`, `..`, the caps and the OPTIMISED gain (ADR-0019 #119, ADR-0006, ADR-0011), 2026-11-16

- Machine: `claude-cloud-4vcpu` (`nproc` 4), Intel(R) Xeon(R) Processor @ 2.10GHz, kernel
  6.18.44-fc-v470, Java HotSpot 25.0.5.0.3+1-LTS-2, `jq-1.8.2`. Commit `746b6a7` (impl/119).
  `stackBytes` from #113's committed constants: `b` 192, reserve 286 720 (#199 has not merged).
  Load average 0.52 at the start (16:48:37 UTC) and 0.97 at the end (17:08:31 UTC). Nothing else
  ran. The verifier's logs, in the impl/119 worktree: `.logs/119-env.log`, `.logs/119-jmh.log`,
  `.logs/119-jmh.json` (54 rows) and `.logs/119-jqref.log`.
- JMH: `java -jar bench/jmh/target/benchmarks.jar Diagnostics -bm avgt -f 2 -wi 3 -w 1s -i 5 -r 1s
  -prof gc -foe true -jvmArgs '-Xms2g -Xmx2g'`, exit 0, with JAVA_TOOL_OPTIONS holding only the
  proxy and truststore flags. One operation is `Gates.once` under `Limits.embedded()` with `-c`,
  with the mode set by ADR-0020's reflective hook.
- Every `# outcome` line is `bytes written`: no cell ended on a termination. The 1e6 recursion cells
  write 13 bytes, which is `499999500000` and LF, jq-ref's answer.
- The recursion shapes carry the prefix `[.[]] as $a | ($a|length) as $L |` (owner, Q1a; F-111).
- jq-ref: `bench/jq-ref.sh`, which reports the per-document time with K = 1 and the start-up time
  subtracted, as `per_document_us`. It gives no time for Eu at 1e6.
- Each number is the JMH average per operation, in ms, ± JMH's 99.9 % error, and
  `gc.alloc.rate.norm` in MiB per operation.
- Before JMH, cold single-shots in a fresh 2 GiB JVM ended at 2.1 s at most (recur-M CONSERVATIVE)
  with a peak heap of 282 MiB at most (recur-N). Eu is no longer quadratic. In OPTIMISED, 1e4 took
  280 ms, 1e5 269 ms and 1e6 1 421 ms, all cold. The log is `.logs/119-shots2-*.log` in the impl/119 worktree.

**Table A: recursion, `limit` and `..`**

| cell | OPTIMISED ms/op | CONSERVATIVE ms/op | alloc MiB/op O / C | jq-ref ms/doc |
|---|---|---|---|---|
| `recur-T.1e6` | 224 ± 42.786 | 1 552 ± 211 | 244.5 / 557.3 | 1 294 |
| `recur-E.1e6` | 172 ± 12.733 | 1 459 ± 110 | 168.2 / 458.1 | 943 |
| `recur-Eu.1e6` | 1 107 ± 188 | 1 414 ± 183 | 343.7 / 458.1 | — |
| `recur-M.1e6` | 225 ± 23.816 | 1 573 ± 341 | 244.5 / 557.3 | 1 438 |
| `recur-N.1e6` | 1 540 ± 166 | 1 545 ± 148 | 351.3 / 351.3 | 963 |
| `limit-iter.1e6` | 75.906 ± 14.880 | 119 ± 20.100 | 53.8 / 137.7 | 767 |
| `limit-recurse.1e6` | 70.794 ± 5.535 | 908 ± 81.091 | 122.1 / 267.0 | 832 |
| `dotdot.1k` | 0.006 ± 0.000 | 0.007 ± 0.001 | 0.0 / 0.0 | 0.496 |
| `dotdot.100k` | 0.578 ± 0.060 | 0.691 ± 0.065 | 0.9 / 1.3 | 5.365 |
| `dotdot.10m` | 96.730 ± 14.358 | 110 ± 12.803 | 108.3 / 133.1 | 698 |
| `dotdot-deep.1k` | 0.035 ± 0.003 | 0.045 ± 0.005 | 0.1 / 0.1 | 0.666 |
| `dotdot-deep.9999` | 0.919 ± 0.061 | 1.175 ± 0.099 | 1.9 / 2.1 | 4.307 |

- In OPTIMISED, T (TAIL_SELF), M (TAIL_GROUP) and E (specialised, `copyOf=`) are 5.5 to 6.4 times
  faster than jq-ref. N (RECURSIVE) takes 1.6 times jq-ref's time. Eu (unspecialised) takes 1.1 s
  and has no reference time.

**Table B: the caps** (`cap-inline` k = 15, `cap-inline-over` k = 16,
`cap-specialise` m = 1 637, `cap-specialise-over` m = 1 638)

| cell | OPTIMISED ms/op | CONSERVATIVE ms/op | alloc MiB/op O / C | jq-ref ms/doc |
|---|---|---|---|---|
| `cap-inline.1k` | 63.671 ± 5.060 | 49.130 ± 6.947 | 40.9 / 33.6 | 1.901 |
| `cap-inline-over.1k` | 76.397 ± 10.866 | 51.647 ± 8.270 | 44.0 / 36.4 | 5.200 |
| `cap-specialise.1k` | 42.750 ± 4.953 | 32.552 ± 2.791 | 55.8 / 52.9 | 42.838 |
| `cap-specialise-over.1k` | 50.149 ± 7.714 | 31.336 ± 2.392 | 67.3 / 53.0 | 38.816 |

- Table B's number is one whole operation: the compile and the run.
  - The cap cells are compile-bound: jqj at 31 to 76 ms against jq-ref's 2 to 43 ms.
  - The over-cap inline row per leaf step, with the compile included as an upper bound:
    2^15 = 32 768 leaf steps, so 2.33 µs per step in OPTIMISED and 1.58 µs in CONSERVATIVE. At
    the cap (2^14 steps) the figures are 3.89 µs and 3.00 µs.
  - OPTIMISED costs 13 to 60 % more than CONSERVATIVE on all four cap cells. That is the price of
    inlining and specialising at the cap.

**Table C: the OPTIMISED gain** (every Gates cell except G7, plus every cell above; G4 at 10⁴ only;
the gain is CONSERVATIVE / OPTIMISED)

| cell | OPTIMISED ms/op | CONSERVATIVE ms/op | gain C/O |
|---|---|---|---|
| `recur-T.1e6` | 224 | 1 552 | 6.92 |
| `recur-E.1e6` | 172 | 1 459 | 8.48 |
| `recur-Eu.1e6` | 1 107 | 1 414 | 1.28 |
| `recur-M.1e6` | 225 | 1 573 | 6.98 |
| `recur-N.1e6` | 1 540 | 1 545 | 1.00 |
| `limit-iter.1e6` | 75.906 | 119 | 1.57 |
| `limit-recurse.1e6` | 70.794 | 908 | 12.82 |
| `dotdot.1k` | 0.006 | 0.007 | 1.25 |
| `dotdot.100k` | 0.578 | 0.691 | 1.20 |
| `dotdot.10m` | 96.730 | 110 | 1.14 |
| `dotdot-deep.9999` | 0.919 | 1.175 | 1.28 |
| `dotdot-deep.1k` | 0.035 | 0.045 | 1.27 |
| `cap-inline.1k` | 63.671 | 49.130 | 0.77 |
| `cap-inline-over.1k` | 76.397 | 51.647 | 0.68 |
| `cap-specialise.1k` | 42.750 | 32.552 | 0.76 |
| `cap-specialise-over.1k` | 50.149 | 31.336 | 0.62 |
| `G1.1k` | 0.002 | 0.002 | 0.99 |
| `G1.100k` | 0.173 | 0.207 | 1.19 |
| `G1.10m` | 17.617 | 18.774 | 1.07 |
| `G2.1k` | 0.004 | 0.004 | 1.00 |
| `G2.100k` | 0.371 | 0.416 | 1.12 |
| `G2.10m` | 34.398 | 40.034 | 1.16 |
| `G3.100k` | 2.924 | 3.039 | 1.04 |
| `G3.10m` | 1 580 | 1 582 | 1.00 |
| `G4.1e4` | 232 | 221 | 0.95 |
| `G6.compact.10m` | 36.241 | 37.384 | 1.03 |
| `G6.pretty.10m` | 51.353 | 46.088 | 0.90 |

- The largest gains: `limit-recurse` 12.8×, the specialised and tail recursions 6.9 to 8.5×,
  `limit-iter` 1.6×. N gains nothing (1.00), as ADR-0006 expects of a non-tail recursion. The Gates
  cells lie between 0.90 and 1.19. The caps lose (0.62 to 0.77), because the operation includes
  the compile.
- NOT_RUN, owed by spec 3: `walk` and `[paths]`.

## #113: the calibration matrix, `b` 192 and reserve 286 720 (ADR-0046 #113, K1–K8, L1–L4), 2026-11-15

- Machine: `claude-cloud-4vcpu` (`nproc` 4), Temurin 25.0.5 at `/opt/temurin-25` (the record's
  `jdk` row: Oracle Corporation 25.0.5.0.3+1-LTS-2, `cpus` 4), the only Maven on the host during
  the committed run. Guard pages 98 304 B (the record's `guard` row). The record has no `machine`
  row: the harness writes `time-ms`, `jdk` and `cpus` only (a carry for #199's re-run).
- Command, each run: `./mvnw -o -B -pl jq-core -am test -Pcalibration-matrix -Dchecks.skip=true
  -Djacoco.skip=true -Dtest=CalibrationTest#matrixCellsSwitchBeforeTheBackstop`, detached. The 234
  cells: 13 shapes × `-Xint`, C1 (`-Xbatch -XX:TieredStopAtLevel=1`) and warm × platform 512 KiB,
  platform 1 MiB and virtual on `-Xss512k` × CONSERVATIVE and OPTIMISED; `-Xint` and C1 children
  4 at a time, warm children one at a time after them. Every later run seeded from the e6f4cc0 record.
- The four runs, each record kept (cells are `-Xint` and C1 `U*cold`/`U*linked`, 156 per run):

  | run | commit | record | progress log | time | max-b | reserve | outcome |
  |---|---|---|---|---|---|---|---|
  | e6f4cc0 | `e6f4cc0`, b 96, reserve 131 072 | `.logs/113-calibration.tsv` | `.logs/113-calibration-progress.log` (unstamped) | 5 547.8 s | 224 | 569 344 | warm rows in the maxima (K1); the seed |
  | k5 | `078dd0a`, b 192, reserve 286 720 | `.logs/113-calibration-k5.tsv` | `.logs/113-calibration-progress-k5.log` (unstamped) | 2 924.9 s | 192 | 286 720 | 3 C1 U* above e6f4cc0's: +1, +7, +31 (L1) |
  | l | `061f5cf` | `.logs/113-calibration-l.tsv` | `.logs/113-calibration-progress-l.log` | 2 784.0 s | 192 | 286 720 | EXECUTED_FAIL: passes ran 4 frames deeper than U*+1; 82 U* lower, 43 C1 cells complete at U*+1 |
  | m | `dd089ac` | `jq-core/src/test/resources/stack/calibration.tsv` (`.logs/113-calibration-m.tsv`) | `.logs/113-calibration-progress-m.log` | 2 635.6 s | 192 | 286 720 | VERIFIED_PASS (`.logs/113-matrix5.log`); all 156 U* equal e6f4cc0's |

- Per tier, from the stamped progress logs (wall: first child start to last child end;
  child-seconds: the sum of each child's start to end, an `-Xint` cold bisection counted from its
  start to its last probe). `-Xint` and C1 share the pool, so their walls overlap.

  | run | `-Xint` wall | `-Xint` child-s (78 linked + 78 cold) | C1 wall | C1 child-s (234) | warm wall = child-s (234) |
  |---|---|---|---|---|---|
  | l | 1 130 s | 1 515 s | 1 140 s | 3 022 s | 1 644 s |
  | m | 951 s | 1 057 s | 959 s | 2 763 s | 1 676 s |

- The committed record (m): `max-b` 192 from the largest `b_cell`, 189 (C1 platform 512 KiB
  CONSERVATIVE W, rounded up to 8 B). `reserve` 286 720 = ceil4K(220 832 + 29 988) + 32 768: the
  largest intercept 220 832 is `-Xint` virtual 512 KiB OPTIMISED ND, the largest cold term 29 988
  `-Xint` platform 1 MiB CONSERVATIVE collect-descent (K3). Every C1 cell's 3 children agree (spread
  0 in all 78), each at its fixed point (L1); all 78 warm cells hold, 3 children × 60 runs at the
  thread's size. `StackUnits` carries b 192 and reserve 280 KiB (286 720).
- Pre-checks before m, children started by hand with ChildJvm's flags (`.logs/113-precheck2.log`,
  3 C1 children each): C1 platform 1 MiB OPTIMISED collect-descent 5818 (passes 5819,5818,5818),
  C1 virtual 512 KiB CONSERVATIVE W 2131 (2162,2131,2131), C1 virtual 512 KiB CONSERVATIVE collect
  5113 (5120,5113,5113), and one `-Xint` linked child of platform 512 KiB OPTIMISED ND 12671
  (12671,12671), each ending STACK at U*+1.
- Side probe, ND OPTIMISED `-Xint` linked on platform 256 KiB and 384 KiB at `078dd0a`
  (`.logs/113-side-probe.log`, one child each, 310 s and 321 s): U* 4 715 and 8 693 against
  512 KiB's 12 671, a slope of 33 B per unit at 256 to 512 KiB, so the 220 832 intercept comes from
  the slope, and the next run takes its intercept from its lowest pair (L2, #199).
- In-process and untagged checks at `dd089ac` (`.logs/113-fixpoint-green.log`): `CalibrationTest`
  13 tests, 21.85 s; `CalibrationRecordTest` 3.
- The 10 000-deep native check (D4), `nativesCoverTheDeepestEmit`: VERIFIED_PASS
  (`.logs/113-fixpoint-green.log` at `dd089ac`; again in the ticket gate, `.logs/113-gate-surefire.xml`).
  On DEEP, each native's first use in its own `-Xint` child on a 512 KiB main thread with 512 KiB
  declared, both modes: `tojson|length` 19998, `[.,.]|sort|length` 2, `def d: [.[]?|d]|sort;
  d|tojson|length` 19998, reference jq's values (diff/deep.rec deep/native-*).
- Tagged, at `24e8f1c` (`./mvnw -o -B -pl jq-core -am test -Pcalibration-matrix
  -Dtest=CalibrationTest#edgeIsExact+heapPerActivationIsRecorded`, `.logs/113-tagged.log`,
  `.logs/113-tagged-surefire.xml`): `edgeIsExact` VERIFIED_PASS, 574.6 s (the `-Xint` 512 KiB cold
  edge of each shape and mode gives the reference result at U* and ends STACK, or STACK after a
  prefix, at U*+1, each the first run of a fresh child); `heapPerActivationIsRecorded`
  VERIFIED_PASS, 25.1 s.
- Heap bytes per live call activation (`.logs/113-calibration-heap.tsv`): one child, platform
  512 KiB, each held generator of `CalibrationCell.HELD` run to n = 100 000 and 2n = 200 000 live
  activations (both give n twice, as jq-ref), the used heap read at the first emit after two
  `System.gc()`; bytes = Δused / Δn. The child's `maxMemory` is 3 586 129 920 B.

  | held filter | mode | stackBytes 0 | stackBytes 512 KiB | 4 194 304 × bytes (512 KiB) |
  |---|---|---|---|---|
  | F-held-try16 | OPTIMISED | 888 | 887 | 3 720 347 648 |
  | F-held-try16 | CONSERVATIVE | 887 | 887 | 3 720 347 648 |
  | C-held | OPTIMISED | 88 | 88 | 369 098 752 |
  | C-held | CONSERVATIVE | 87 | 87 | 364 904 448 |
  | F-held | OPTIMISED | 120 | 120 | 503 316 480 |
  | F-held | CONSERVATIVE | 115 | 119 | 499 122 176 |

  F-held-try16 at `Limits`' default depth of 4 194 304 would need more heap than this child's
  `maxMemory`; the default stays open for the owner. The harness records only the slope, not the
  used heap at n and 2n.

## #111: provisional bytes per unit `b` and reserve (ADR-0046 #111 amendment), 2026-11-15

- Machine: the implementer's container, 4 vCPU, glibc 2.39 (Ubuntu 2.39-0ubuntu8.9), Temurin
  25.0.5 at `/opt/temurin-25`. Commit `8938e6a` on `impl/111` (spec/106 tip `d36c2a2` merged), no
  tracked change, classes from `./mvnw -o -B -q -pl jq-core -am compile -Dchecks.skip=true`.
  Stand-in `b` at that commit: 100 (it sets the resolution of the declared stack only).
- Command: `bench/stack-units/measure-b.sh > .logs/111-measure-b.log`. Compile mode: OPTIMISED, the
  engine's default (the public builder sets no other); CONSERVATIVE and virtual threads are #113's
  (NOT_RUN here).
- Method (in the script's header): one fresh JVM per cell, `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`
  and `_JAVA_OPTIONS` unset, no agent. The case runs on the launcher's main thread, sized by `-Xss`:
  it is the process's first thread, so glibc's stack cache cannot hand it a larger stack an ended
  thread left (the grilling-adviser's case: a 512 KiB thread after a 1 MiB one held 10 729 frames).
  Proof per cell: a plain recursion to StackOverflowError on that thread first, and each 512 KiB /
  1 MiB pair must hold 1.5 to 3.5 times the frames, or the run stops INVALID. Under `-Xint` the
  proof is 4 772 frames at 512 KiB (the adviser's fresh 512 KiB thread: 4 771) and 10 730 at 1 MiB;
  C1 10 194 and 23 300; warm counts move with the compilation of the recursion (6 958 to 23 300).
  Then a binary search for the largest `U_f` at which the case completes (the next ends STACK), and
  per cell `b = ceil((S - 128 KiB) / (U_f + D_f))`; per pair the slope `ceil(512 KiB / ΔU_f)`. C1:
  `-Xbatch -XX:TieredStopAtLevel=1`; C1 and warm: 30 warm-up runs first. All 24 cells proven.

  | tier | S | collect | reduce | first-or | hold |
  |---|---|---|---|---|---|
  | -Xint | 512 KiB | 65 | 61 | 80 | 72 |
  | C1 only | 512 KiB | 77 | 79 | 91 | 77 |
  | warm | 512 KiB | 78 | 80 | 87 | 78 |
  | -Xint | 1 MiB | 67 | 63 | 83 | 75 |
  | C1 only | 1 MiB | 80 | 82 | 94 | 80 |
  | warm | 1 MiB | 81 | 82 | 92 | 61 |
  | -Xint | slope | 69 | 65 | 85 | 77 |
  | C1 only | slope | 82 | 85 | 96 | 83 |
  | warm | slope | 83 | 83 | 96 | 53 |

- Largest: 96 (the C1 and warm first-or slopes; largest cell 94, C1 first-or 1 MiB). Provisional
  `b` = 96, already a multiple of 8. Reserve 128 KiB (96 KiB of guard pages, 32 KiB for natives), a
  source constant.


## #109: the compile-mode oracle's trampoline run in ticket-gate step 1 (F-58), 2026-11-15

- Machine: the implementer's container, 4 vCPU (`nproc` 4), the `claude-cloud-4vcpu` class, unverified
  as the same host. Branch `impl/109` @ `b89af88` (spec/106 tip `d89de59` merged); compile.txt holds
  conservative lines only, so before the after-merge regen the Survey judges every case under
  `trampoline` with an optimised run of its own.
- Gate step 1 (`gate-ticket.sh 109 …`, `.logs/109-gate2.log`): verify rc 0, 247 351 ms. In it, from
  `.logs/109-verify.log`: `CompileModeOracleTest` 5 307 tests, 5.301 s; `CompileModeSurveyTest` 7 123
  tests, 5.794 s.
- A/B, one run each, `./mvnw -o -B -Pcompile-oracle -pl oracle -am test
  -Dtest=CompileModeOracleTest,CompileModeSurveyTest -Djacoco.skip=true`: modes conservative and
  trampoline 8.629 + 5.841 = 14.470 s (`.logs/109-timing-both.log`; an earlier run, log not kept,
  14.120 s); `MODES` cut to conservative by a local edit, reverted, 8.618 + 1.149 = 9.767 s
  (`.logs/109-timing-conservative.log`). Added time of the trampoline run: 4.4 to 4.7 s, under 2 % of
  step 1. One or two runs per side, so no spread; after the regen the Oracle test judges both modes
  from one optimised run, which should cut it (NOT_RUN).

## Spec 56 gate run at bba3de4 (F-84), 2026-11-13

- Machine: `claude-cloud-4vcpu`. Command: `gate-spec.sh 56` on `spec/56-core-language-2a` @ `bba3de4`:
  `gate-spec: 56 passed`, rc 0, 3 440 s. Logs `.logs/spec56-steps.tsv`, `.logs/spec56-gate.log`,
  `.logs/spec56-pit.log`.
- Step times (s): clean-verify 238.3, record 62.6, pit 2858.8, jmh 211.9, regen 1.2, census 1.1; every rc 0.
- PIT `Completed in` per module (s): query-core 149 (307 mutants), jq-core 1945 (3439), jq-cli 212
  (3868), oracle 319 (168). Sum of the four 2 625 s (43.75 min); the `pit` step 2 858.8 s (47.6 min);
  F-84's target is 45 min.
- Own-report statuses (TIMED_OUT / MEMORY_ERROR): query-core 1 / 0, jq-core 56 / 5, jq-cli 29 / 2,
  oracle 0 / 0.
- `ratchet/mutation.tsv` (surviving / generated): jq-core 63 / 3439 (was 17 / 1029), query-core 0 / 307
  (was 0 / 20).
- `ratchet/bench.tsv` (old -> new): G1.1k 2.654 -> 2.186 us/op; G1.100k 240.996 -> 194.822 us/op;
  G1.10m 27 868.501 -> 19 146.306 us/op; G2.1k new 4.355 us/op; G2.100k new 427.370 us/op; G2.10m new
  41 236.547 us/op; G3.100k new 2 915.774 us/op; G3.10m new 1 550 401.627 us/op; G4.1e4 new
  236 819.173 us/op; G6.compact.10m 56 541.427 -> 40 057.006 us/op; G6.pretty.10m 65 583.134 ->
  49 179.404 us/op; G7.engine.1k 20.847 -> 48.475 ms/op (up 2.3 times); G7.process.1k 140.424 ->
  156.885 ms/op.

## #85: the walk-side key proof against 2fe9654 and jq-ref (ADR-0044, ADR-0019 bar 10 %), 2026-11-13

- Machine: `claude-cloud-4vcpu` (4 × Intel Xeon Processor @ 2.10 GHz, Linux 6.18.44-fc-v445), JDK
  25.0.5.0.3 (Java HotSpot 64-Bit Server VM), JMH 1.37, jq-ref jq-1.8.2. Load average 2.08 before,
  2.61 between and 5.39 after the two runs: other worktrees ran, so the errors are wide.
- #85: `d83af2d`. Baseline: `2fe9654` exported with `git archive`, with the branch's `bench/jmh`
  sources and pom. Each built with `./mvnw -o -B -Pbench -pl bench/jmh -am package -DskipTests`.
- Command: `java -jar <jar> KeyProofCost -foe true -prof gc -rf json -rff <json>`, ADR-0019's
  operation and settings (2 forks, 3 x 1 s warm-up, 5 x 1 s, `-Xms2g -Xmx2g`, compact output); the
  branch first, then the baseline; then G1.10m twice more on each, alternately. Logs
  `.logs/85-jmh3-{branch,base}.{log,json}`, `.logs/85-jmh3-g1-{branch,base}-{1,2}.log`,
  `.logs/85-jmh3-jqref.log`.
- `KeyProofCost`: G1 `.a.b.c`, G2 and G6's `-c .` on `Inputs.orders` at each size; `recs20` is an
  array of 20-key records (`KeyProofCost.recs20`, padded to the size); `wide` is ReaderCost's flat
  78 841-key object (#82) and an LF. jq-ref per document: best of 5 minus the best of 5 on
  `/dev/null`, one document (K = 1).

| µs/op (± 99.9 %) | #85 | 2fe9654 | change | B/op #85 / 2fe9654 |
| --- | --- | --- | --- | --- |
| G1.1k | 2.245 ± 0.161 | 2.264 ± 0.157 | −0.8 % | 368 / 368 |
| G1.100k | 214.005 ± 13.138 | 210.440 ± 13.590 | +1.7 % | 369 / 369 |
| G1.10m | 20 268.381 ± 3 023.874 | 19 270.525 ± 1 790.882 | +5.2 % | 621 / 613 |
| G1.10m, pair 2 | 19 646.403 ± 972.016 | 20 823.424 ± 1 601.337 | −5.7 % | 616 / 624 |
| G1.10m, pair 3 | 19 881.885 ± 2 294.841 | 20 492.129 ± 2 017.598 | −3.0 % | 618 / 621 |
| G2.1k | 4.739 ± 0.539 | 4.647 ± 0.487 | +2.0 % | 4 696 / 4 920 |
| G2.100k | 476.548 ± 71.160 | 436.782 ± 61.144 | +9.1 % | 418 448 / 444 137 |
| G2.10m | 45 321.492 ± 5 734.008 | 46 271.393 ± 4 383.439 | −2.1 % | 40 311 400 / 42 792 117 |
| G6 `-c .` 1k | 4.907 ± 0.454 | 5.118 ± 0.419 | −4.1 % | 192 / 212 |
| G6 `-c .` 100k | 438.063 ± 37.986 | 496.562 ± 55.217 | −11.8 % | 195 / 208 |
| G6 `-c .` 10m | 41 627.509 ± 2 748.468 | 46 528.887 ± 3 290.923 | −10.5 % | 629 / 702 |
| recs20 `-c .` 1k | 5.354 ± 0.420 | 19.904 ± 2.143 | −73.1 % | 192 / 192 |
| recs20 `-c .` 100k | 477.470 ± 48.761 | 1 680.599 ± 90.391 | −71.6 % | 195 / 356 |
| recs20 `-c .` 10m | 43 346.612 ± 1 930.151 | 144 720.413 ± 11 386.679 | −70.0 % | 639 / 1 279 |
| recs20 `.[]\|.[]` 1k | 7.579 ± 1.013 | 21.714 ± 2.542 | −65.1 % | 3 088 / 2 792 |
| recs20 `.[]\|.[]` 100k | 673.387 ± 63.676 | 1 878.497 ± 152.024 | −64.2 % | 240 546 / 216 605 |
| recs20 `.[]\|.[]` 10m | 59 950.808 ± 4 471.934 | 167 809.499 ± 21 354.755 | −64.3 % | 21 154 249 / 19 039 542 |

| wide (78 841 keys) | #85 µs/op | jq-ref µs per document | #85 / jq-ref |
| --- | --- | --- | --- |
| `.[]` | 9 026.280 ± 1 522.706 | 51 065 | 0.18 |
| `-c .` | 6 835.715 ± 471.581 | 46 876 | 0.15 |

- No cell is over the 10 % bar. G2.100k is the highest at +9.1 %, inside both errors; it was −8.0 %
  and +6.3 % in this ticket's two earlier rounds (`.logs/85-jmh-*.log` at `fd81c29`,
  `.logs/85-jmh2-*.log` at `e152ad0`), and G2 walks no read object. G1.10m over its three pairs:
  −1.3 % (means 19 932 against 20 195).
- 2fe9654's wide cells are n² and were not run here: `-c .` took 39.9 s on `25ef36c` (ADR-0044).
- recs20 `.[]|.[]` allocates the walk `.[]` holds for each record (+11 % B/op); the Printer keeps
  no walk object. Round 1 found the Printer making one per object (G6.10m 9 923 691 B/op against
  668), removed by `e152ad0`; round 2 a record per proof (recs20 `-c .` 10m 907 225 B/op), removed
  by `d83af2d`. Their times were within the bar, so only the B/op moved.
- recs20 jq-ref at 10m, same session: `-c .` 444 269 µs, `.[]|.[]` 414 664 µs per document.

## #82: the distinct-key proof's reader cost (ADR-0002 as amended by F-40), 2026-11-13

Ported unchanged by #85 from `t/82-distinct-keys` at `a01fda2`, which is deleted at #85's merge
(ADR-0002's withdrawn amendment and ADR-0044 cite this section); `ReaderCost.java` came with it.

- Machine: `claude-cloud-4vcpu` (4 vCPU, Linux 6.18.44-fc-v445), JDK 25.0.5.0.3 (HotSpot 64-Bit
  Server VM), JMH 1.37. `uptime` before the first run: load average 1.67, 1.40, 0.90; no other
  build of this agent ran during a measurement, other worktrees' load was not checked further.
- Baseline: `5ab4aa7` (the tip before #82; the reader is unchanged up to `23c8009`), exported with
  `git archive`, plus `ReaderCost.java`. #82: `500fcaa` plus the same `ReaderCost.java` (committed
  with this section). Each built with `./mvnw -o -B -Pbench -pl bench/jmh -am package -DskipTests`.
- Command: `java -jar <jar> ReaderCost -foe true -prof gc -rf json -rff <json>`, the class's
  settings (Spike B's: 2 forks, 3 x 1 s warm-up, 5 x 1 s, `-Xms2g -Xmx2g`), run alternately
  baseline, #82, baseline, #82. Logs `.logs/82-jmh-{base,new}-{1,2}.{log,json}`.
- `ReaderCost.reader` is one `Workspace.read` of the document, tape and table warmed. The Spike B
  documents are `Docs.make(size, "middle")` of `spike/field-read`, ported: 969 / 107 765 /
  11 206 791 bytes, 185 / 19 997 / 2 049 843 entries, root objects of 8 / 770 / 78 841 keys (the
  same counts as "Spike B" below). `flat-78841` is `{"k000000":0,…}`, 1 250 347 bytes. With #82
  the roots of 100 KB, 10 MB and flat-78841 are tag 10, the 1 KB root (8 keys) tag 9 (scratch
  probe over the #82 jar).

| µs/op | baseline 1 | baseline 2 | #82 1 | #82 2 | #82 / baseline (means) |
| --- | --- | --- | --- | --- | --- |
| spikeB-1k | 2.128 ± 0.279 | 1.911 ± 0.252 | 1.954 ± 0.195 | 1.897 ± 0.175 | 0.95 |
| spikeB-100k | 241.563 ± 25.476 | 215.372 ± 32.261 | 265.939 ± 39.481 | 244.061 ± 51.870 | 1.12 |
| spikeB-10m | 24 198.839 ± 2 102.878 | 22 734.112 ± 2 662.731 | 28 795.355 ± 3 771.406 | 30 138.253 ± 1 515.417 | 1.26 |
| flat-78841 | 1 593.367 ± 284.133 | 1 536.638 ± 263.769 | 4 083.801 ± 322.124 | 4 011.661 ± 460.328 | 2.59 |

`gc.alloc.rate.norm` per read, baseline / #82: 48 / 48 B at 1 KB, 50 / 50 B at 100 KB, 237–246 /
279–286 B at 10 MB, 91 / 108 B on flat-78841.

**Over the owner's ~10 % line** at 10 MB (+26 %; +19 % and +33 % per pair) and at 100 KB (+12 %;
+10 % and +13 % per pair, inside each run's error). By the amendment's cost bar #82 stops
`ready-for-human`. The
same 78 841 keys cost +2.5 ms dense (flat) and +6.0 ms spread over Spike B's 11 MB and 16 MB of
tape, so the proof's second walk over memory that is cold by the object's close looks like more
than half of the 10 MB cost (an inference from these two rows, not a profile).

Table size (scratch simulation, `.logs/82-probe-sim.log`): random 32-bit home slots, linear
probing, more than 8 probes ends the proof. Distinct key sets that failed, by slots per key:

| keys (trials) | 2 per key | 4 per key | 8 per key |
| --- | --- | --- | --- |
| 9 (20 000) | 0 % | 0 % | 0 % |
| 40 (20 000) | 0.45 % | 0.005 % | 0 % |
| 770 (20 000) | 43.8 % | 0.48 % | 0 % |
| 5 000 (20 000) | 65.0 % | 0.91 % | 0.015 % |
| 78 841 (200) | 100 % | 11.0 % | 0 % |
| 300 000 (200) | 100 % | 100 % | 100 % |

#82's reader takes 8 slots per key, rounded up to a power of two, at most 2^20: a 4 MiB table for
78 841 keys, cleared after each proof.

## Bench recording G2, G3, G4 and the tick-loop diagnostic (ticket #74, ADR-0019, F-33), 2026-11-13

- Machine: `claude-cloud-4vcpu` (4 × Intel Xeon @ 2.10 GHz, Linux 6.18.44-fc-v445), Java 25.0.5.0.3,
  jq-ref jq-1.8.2. Commit measured: `45a4978` (scores), `eddb639` plus `Ticks` (diagnostic; no
  measured code changed). JMH with the class annotations, 2 GiB heap. Load average 1.4–4.0
  (scores) and 3.9–7.0 (diagnostic): other writers ran, so the errors are wide. Logs (implementer
  worktree of #74): `.logs/74-jmh.log`/`.json`, `.logs/74-jq-ref.log`, `.logs/74-ticks*.log`.
- jq-ref per document from `bench/jq-ref.sh` in the same session (K copies; start-up 2.04–2.43 ms).

| scenario | jqj (± 99.9 %) | B/op | jq-ref per doc (K) | ratio |
| --- | --- | --- | --- | --- |
| G2.1k | 4.871 ± 0.854 us/op | 4 920 | 34.911 us (10000) | 0.140 |
| G2.100k | 461.512 ± 70.814 us/op | 444 127 | 3737.030 us (100) | 0.123 |
| G2.10m | 47096.244 ± 9634.524 us/op | 42 792 122 | 548171 us (1) | 0.086 |
| G3.100k | 3085.177 ± 359.099 us/op | 3 677 606 | 10046.310 us (100) | 0.307 |
| G3.10m | 1780700.734 ± 251428.130 us/op | 505 813 593 | 2901609 us (1) | 0.614 |
| G4.1e4 | 318281.415 ± 36281.656 us/op | 201 482 326 | 5640.850 us (100) | 56.4 |

- G4.1e4 is over 1.0× (reported to the owner at 2a's end): `. + [$x]` copies the array each step
  until spec 3's in-place updates; its 5.0×10⁷ ticks match ADR-0017's slot-copy estimate.
- G2 `-c` at 10 MB in a 64 MiB fork (`-Xms64m -Xmx64m`, the `G2_10m` method): completes,
  49454.588 ± 4299.078 us/op, 42 792 136 B/op, 630 GCs over 714 ms.
- Tick-loop diagnostic (ADR-0019), mean per tick (ticks counted by bisecting the step budget,
  `bench/.../Ticks.java`; JMH re-run in the same session):

| scenario | ticks/op | us/op | ns/tick |
| --- | --- | --- | --- |
| G2.1k | 131 | 4.685 ± 0.473 | 35.8 |
| G2.100k | 12 166 | 433.318 ± 46.040 | 35.6 |
| G2.10m | 1 173 032 | 43863.410 ± 5170.671 | 37.4 |
| G3.100k | 134 665 | 3026.556 ± 637.036 | 22.5 |
| G3.10m | 19 504 189 | 2084021.741 ± 525378.997 | 106.9 |
| G4.1e4 | 50 015 096 | 273786.876 ± 65717.589 | 5.5 |

- A 4096-tick refuel window lasts 22 us (G4) to 438 us (G3.10m), under ADR-0019's 16 ms.
  NOT_RUN: the longest gap between two ticks at one site and the 1 % refuel-cost bound (both need
  a hook in query-core's `RunState`; carried to spec 6).

## Cold parse of the prelude, `builtin.jq` (ticket #71, ADR-0042), 2026-11-13

- Machine: `claude-cloud-4vcpu` (4 vCPU, Linux 6.18.44-fc-v445), JDK 25.0.5.0.3 (HotSpot 64-Bit
  Server VM), JMH 1.37. Commit measured: `5ab488a` plus the uncommitted `PreludeParse` benchmark
  (committed with this section). Other worktrees' load was not checked.
- Command: `java -jar bench/jmh/target/benchmarks.jar PreludeParse -foe true -rf json -rff
  .logs/71-prelude-parse.json` (the class's settings: single shot, 10 forks, each a fresh JVM, no
  warm-up, 1 measurement of batch size 1, `-Xms2g -Xmx2g`). Log `.logs/71-prelude-parse.log`. The
  shot is one `Parser.parse` of the prelude's text, read in setup; no parser class is loaded before it.

| method | what is timed | shots | mean | min | p50 | max |
| --- | --- | --- | --- | --- | --- | --- |
| `prelude_cold_parse` | `Parser.parse(builtin.jq)` in a fresh JVM | 10 | 39.009 ± 9.615 ms (99.9 %) | 30.161 ms | 39.905 ms | 51.977 ms |

## Fixed charges of bytes built, CONTAINER and STRING (ticket #64, ADR-0035, F-39), 2026-11-13

- Machine: `claude-cloud-4vcpu` (4 vCPU, Linux 6.18.44-fc-v445), JDK 25.0.5.0.3 (HotSpot 64-Bit
  Server VM), JMH 1.37. Commit measured: `20e4d59` plus the uncommitted `BuiltSizes` benchmark
  (committed with this section). Other worktrees' load was not checked.
- Command: `java -jar bench/jmh/target/benchmarks.jar BuiltSizes -prof gc -foe true -rf json -rff
  .logs/64-builtsizes.json` (the class's settings: 1 fork, 3 x 1 s warm-up, 3 x 1 s measurement,
  default heap). Log `.logs/64-builtsizes.log`. Each method builds one empty value; the result is
  `gc.alloc.rate.norm`, the bytes allocated per value besides its elements, entries or bytes.

| method | what is built | gc.alloc.rate.norm |
| --- | --- | --- |
| `sizes_array` | `new BuiltArray(new Object[0], 0)` | 40.000 ± 0.001 B/op |
| `sizes_shaped` | `new BuiltObject(shape, new Object[0])` | 48.000 ± 0.001 B/op |
| `sizes_growable` | `BuiltObject.ofPairs(new Object[0])` | 64.000 ± 0.001 B/op |
| `sizes_string` | `BuiltString.of(new byte[0])` | 40.000 ± 0.001 B/op |

`Charges.CONTAINER` is the largest container, 64 B; `Charges.STRING` is 40 B.

## Ticket-gate steps 1 and 2 and PIT per 1000 seeds (ticket #40, F-19), 2026-11-12

- Machine: `claude-cloud-4vcpu` (4 vCPU, 15 GiB, Linux 6.18.44-fc-v378), `java version
  "25.0.5.0.3" 2026-10-25 LTS`, jq-ref `jq-1.8.2`. Date 2026-11-12, 02:31Z to 03:39Z. Nothing else of
  this ticket's agent ran during a measurement; other worktrees' load was not checked, `uptime` before
  the parallel start read 1.30 (1 min, the tail of the run before it).
- Commit measured: `eee8bb3`, the tip of `spec/22-walking-skeleton`, every other ticket of spec 1
  merged. Each run used a fresh detached worktree of it (no `target/`), removed afterwards.
- Gate command, per run in a fresh worktree: `scripts/gate-ticket.sh <label> jq-core,oracle
  3e9a9d3^1`, `3e9a9d3^1` = `7e7aac5`, the commit before #35's merge. Eight main source files changed
  between it and the tip in jq-core and oracle (`JsonStrings`, `Printer`, `BuiltString`, `ObjectView`,
  `StringView`, `JsonReader`, `Workspace`, `DiffReport`), so PIT mutates 642 mutants in jq-core and
  661 in oracle. `jq-cli` is not in the module list (`Input.java` changed there).
  Times are the wall-clock ms the gate writes to `.logs/<label>-steps.tsv`; copies in
  `.logs/40-<label>-steps.tsv`, gate output in `.logs/40-<label>-gate.log`, step logs in
  `.logs/40-<label>-wtlogs/`. Every step 1 and 2 exited 0.
- Passing cases at the tip (`ratchet/oracle/*.txt`): **jqtest 10, differential 629 (of which
  `gen/values-v1` 472, hand-written 157), json 125**. The `values-v1` file holds seeds [0, 2100); 472
  pass, the rest are run only by the Survey, which PIT excludes. Required cases that `verify` ran:
  `DifferentialOracleTest` 630 (629 + 1), `JsonOracleTest` 126, `JqTestOracleTest` 11 (each one case
  more than the list); their elapsed time was 0.478 s, 0.345 s, 0.041 s.
- There is no compile-mode oracle yet (no test class, no `compile.txt`): step 1 measures the
  interpreter's oracles only.

**Step 1 (`./mvnw -B -Pbench verify`) and step 2 (PIT), alone and three gates in parallel**

| run | step 1 verify | target | step 2 PIT | target |
| --- | --- | --- | --- | --- |
| alone, run 1 (`m40a1`, 02:31Z) | 30.1 s | 60 s (three in parallel) | 741.8 s (12 min 22 s) | 3 min (three in parallel) |
| alone, run 2 (`m40a2`, 02:44Z) | 27.4 s | 60 s | 728.0 s (12 min 8 s) | 3 min |
| three in parallel, gate 1 (`m40b1`) | 70.3 s | 60 s | 1052.9 s (17 min 33 s) | 3 min |
| three in parallel, gate 2 (`m40b2`) | 71.4 s | 60 s | 1077.1 s (17 min 57 s) | 3 min |
| three in parallel, gate 3 (`m40b3`) | 70.2 s | 60 s | 1072.2 s (17 min 52 s) | 3 min |

- The three gates started together at 02:57:36Z and the last ended at 03:16:49Z (whole gate
  1129.0 s, 1152.6 s, 1146.9 s). Three distinct labels, three worktrees, one shared `~/.m2`.
- **Step 1 is over its 60 s target with three in parallel (70.2 to 71.4 s), under it alone (27.4 to
  30.1 s).** The oracle tests themselves are about 2.7 s of it (the three required-case classes above
  plus the three Surveys, 0.2 s to 1.1 s each, in `m40a1`); the rest is the reactor build (alone: jq-core 7.5 s, jq-cli
  5.5 s, oracle 7.2 s, bench/jmh 3.1 s, query-core 3.8 s). ADR-0014/ADR-0020's named fallback is the
  compile-mode oracle moving to the spec gate; none exists in step 1 yet, so it would not shorten this
  number. The architect decides.
- **Step 2 is over its 3-minute target alone (728.0 and 741.8 s, 4.0 to 4.1 times the target) and
  with three in parallel (1052.9 to 1077.1 s, about 5.9 to 6.0 times).** PIT per module, alone:
  jq-core 205 s and 193 s, oracle 510 s and 511 s (`Completed in …`, `.logs/40-m40a*-wtlogs/m40a*-pit.log`);
  in parallel jq-core 324 to 332 s, oracle 675 to 688 s. The oracle module's PIT mutates the same
  jq-core classes against the oracle module's 232 test classes (661 mutants, 49 482 to 50 399 test
  runs), after jq-core's own run (642 mutants, 44 050 to 44 194 test runs).

**PIT cost per 1000 passing `values-v1` seeds**

How: the same gate on the same commit, with the 472 `gen/values-v1/*` lines deleted from
`ratchet/oracle/differential.txt` in the scratch worktree only (never committed, `ratchet/` untouched
in the ticket), so PIT runs 157 differential cases instead of 629 and nothing else changes. The
step-2 time with the seeds minus the step-2 time without them, divided by 472, scaled to 1000. The
runs `m40c1`/`m40c2` stopped at step `lf` (the edit is an unstaged change); steps 1 and 2 had run.

| run | step 2 PIT | jq-core module | oracle module |
| --- | --- | --- | --- |
| with 472 seeds, `m40a1` / `m40a2` | 741.8 s / 728.0 s | 205 s / 193 s | 510 s / 511 s |
| without them, `m40c1` / `m40c2` | 532.0 s / 537.4 s | 202 s / 191 s | 304 s / 323 s |

- Mean with 734.9 s, mean without 534.7 s: the 472 seeds add **200.2 s**, **0.424 s per seed, 424 s
  per 1000 passing seeds (7 min 4 s)**. Range over the four pairs: 190.6 s to 209.8 s, 404 s to 444 s
  per 1000. The scale is a straight line from 472 seeds to 1000; it was not measured at 1000 (only 472
  pass). The extra cost is all in the oracle module (about 197 s; jq-core's module is the same with or
  without them).
- Against the target: 424 s per 1000 seeds alone is 2.4 times the whole 3-minute budget, and the
  step without any seed is already 534.7 s alone (3.0 times it). So the seeds are not the only cause
  here: ADR-0020's first fallback (group the seeds per unit, or keep them out of PIT) would at most
  take 734.9 s down to 534.7 s on this ticket, which is still over 3 minutes. The next fallback in
  ADR-0020 and ADR-0014 is PIT leaving the ticket gate, by a finding. The architect decides.
- Not isolated: the per-seed cost with three gates in parallel.

**Recording `values-v1` (F-19: about 9.1 s to record and about 11 s to verify per 1000 seeds)**

`scripts/oracle-record.sh` in a fresh worktree of the tip, three runs each, wall clock including the
script's `javac` of the recorder and the JVM start (`.logs/40-rec.tsv`, `.logs/40-rec-*.log`):

| command | runs | against F-19 |
| --- | --- | --- |
| `--generate values-v1 0 1000` (1000 cases, 0 dropped) | 10.09 s, 9.97 s, 10.13 s | about 9.1 s per 1000 |
| `--verify …/gen/values-v1.rec` of those 1000 | 10.19 s, 10.31 s, 10.65 s | about 11 s per 1000 |
| `--verify …/gen/values-v1.rec` as committed (2100 seeds) | 18.61 s, 18.64 s, 18.43 s | about 11 s per 1000 (so 23 s) |

Both are far under the 10-minute bound the ticket set for re-measuring.

**Recording `core-v1` (ticket #58, ADR-0033), 2026-11-12**

`scripts/oracle-record.sh` in the `t/58-core-v1` worktree (52e54b1 merged with `spec/56-core-language-2a` at
56174c7), the cloud container (4 vCPU Intel Xeon @ 2.30 GHz, load average 1.4–2.1), jq-ref 1.8.2, three runs
each, wall clock including the script's `javac` and the JVM start (`.logs/58-record.log`, `.logs/58-record3.log`).
All 2100 seeds recorded, 0 dropped; the three recordings are byte-identical.

| command | runs | per 1000 seeds |
| --- | --- | --- |
| `--generate core-v1 0 2100` (2100 cases, 0 dropped) | 14.68 s, 16.20 s, 15.77 s | about 7.3 s |
| `--verify …/gen/core-v1.rec` (2100 seeds) | 15.31 s, 15.86 s, 15.63 s | about 7.4 s |

## Bench recording G1, G6, G7: jqj against jq-ref (ticket #39), 2026-11-12

- Machine: `claude-cloud-4vcpu` (4 vCPU Intel Xeon Processor @ 2.10GHz, Linux 6.18.44-fc-v378),
  `java version "25.0.5.0.3" 2026-10-25 LTS`, jq-ref `jq-1.8.2`. Date 2026-11-12, session 02:09Z to
  02:11Z, no other command of this ticket's agent running (other worktrees' load not checked). Commit `2c0f444` on `t/39-bench-recording` (the commit that adds
  `BenchCasesTest` and the G6/G7 cases; no code under measurement changed after the #38 tip `93840f6`).
- Commands, from the repo root after `./mvnw -B -Pbench -DskipTests package` (`.logs/39-package.log`),
  in one shell script (`.logs/39-session-*`): `java -jar bench/jmh/target/benchmarks.jar -foe true -prof gc
  -rf json` (`.logs/39-jmh-session.log`, `.logs/39-jmh-result.json`), then `bench/jq-ref.sh`
  (`.logs/39-jq-ref-session.log`), then the small-heap forks below. G7's process half ran
  `jq-cli/target/jqj -c .a` through the default `jqj.launcher`.
- Flag: `-foe <bool>` (fail on error) is in the jar's own `-h` (`.logs/39-jmh-help.log`); rc 0 with it
  on means no scenario threw. All seven ids ran with the annotations' settings (avgt, 2 forks, 3 × 1 s
  warm-up, 5 × 1 s measurement, 2 GiB heap; G7 single-shot, 10 forks, no warm-up).
- Inputs as in the #30 section below (`orders`, 1 KiB / 100 KiB / 10 MiB). jq-ref as ADR-0019 says
  (best of 5, K copies, minus start-up).
- G7.engine.1k: `.a` on the 1 KB document runs; JMH with `-foe true` exited 0 over its 10 forks.
- Factor = jqj JMH score / jq-ref per document, in the same unit. A factor above 1.0 would break gate
  check 5 in spec 6; **none of the five G1/G6 cells is above 1.0**. G7 has no factor (ADR-0019); the
  ratio is shown for the record only.

| id | jqj (JMH, ± 99.9 % error) | jq-ref per document | factor jqj/jq-ref |
| --- | --- | --- | --- |
| G1.1k | 2.492 ± 0.136 µs | 21.746 µs | 0.115 |
| G1.100k | 219.652 ± 16.790 µs | 2.348 ms | 0.094 |
| G1.10m | 23.639 ± 2.048 ms | 391.4 ms | 0.060 |
| G6.compact.10m | 53.416 ± 4.304 ms | 538.9 ms | 0.099 |
| G6.pretty.10m | 60.352 ± 3.167 ms | 687.8 ms | 0.088 |
| G7.engine.1k | 18.289 ± 4.126 ms (single shot, 10 forks) | 22.144 µs | no factor (ratio 826) |
| G7.process.1k | 120.493 ± 11.990 ms (single shot, 10 forks) | 2.387 ms (one process) | no factor (ratio 50.5) |

jq-ref columns behind the table (`bench/jq-ref.sh`): K, best, start-up (ms) = G1.1k 10000, 219.889,
2.432; G1.100k 100, 237.046, 2.293; G1.10m 1, 393.728, 2.359; G6.compact.10m 1, 541.317, 2.430;
G6.pretty.10m 1, 690.264, 2.422; G7.engine.1k 10000, 223.836, 2.400; G7.process.1k one process, 2.387.
Allocation (`-prof gc`, B/op): G1.1k 320.0, G1.100k 321.5, G1.10m 547.0, G6.compact.10m 627.9,
G6.pretty.10m 674.2, G7.engine.1k 2 214 792, G7.process.1k 1 317 285. Gate check 4's flat allocation
reads G1.10m − G1.1k = 227 B/op against the limit of 1 KiB (observed here, not asserted by any test).

**Small-heap forks of gate check 4** (`java -jar bench/jmh/target/benchmarks.jar 'G1_10m|G6_compact_10m'
-foe true -jvmArgs "-Xms64m -Xmx64m" -prof gc -rf json`; `.logs/39-jmh-smallheap.log`, the log's
"VM options" line reads `-Xms64m -Xmx64m`): both forks complete, rc 0, no exception.

| id | 64 MiB fork | same cell at 2 GiB | factor of the 64 MiB fork |
| --- | --- | --- | --- |
| G1.10m | 24.282 ± 1.425 ms, 551.2 B/op | 23.639 ms | 0.062 |
| G6.compact.10m | 51.777 ± 1.718 ms, 616.1 B/op | 53.416 ms | 0.096 |

The factor of the 64 MiB rows uses the jq-ref time of the table above. Gate check 4 also names G2
here; G2 enters the bench in spec 2.

## Bench module and jq-ref.sh: jq-ref per document (ticket #30), 2026-11-11

- Machine: `claude-cloud-4vcpu` (4 vCPU Intel Xeon Processor @ 2.10GHz, Linux 6.18.44-fc-v378),
  `java version "25.0.5.0.3" 2026-10-25 LTS`, jq-ref `jq-1.8.2`. Date 2026-11-11T17:06Z.
- Commit: `44de834` on `t/30-bench-module`. Command: `java -jar bench/jmh/target/benchmarks.jar -prof
  gc -rf json`, then `bench/jq-ref.sh` straight after in the same shell (`.logs/30-jmh-session.log`,
  `.logs/30-jq-ref-session.log`).
- JMH against the Stubs: the six API scenarios end every fork in `JqCompileException` (`jqj stub:
  JqEngine.compile …`); `G7.process.1k` ends in `IOException`, because `jq-cli/target/jqj` does not
  exist yet. No jqj number exists.
- Inputs: `orders` of `Inputs` (bench module). Sizes are 1024-based: `1k` = 1 KiB = 1 024 bytes,
  `100k` = 100 KiB = 102 400 bytes, `10m` = 10 MiB = 10 485 760 bytes, exactly; SHA-256 pinned in
  `InputsTest`. They are not the inputs of issue #12, so the rows below do not compare
  with the "Reference times" table under it.
- jq-ref per ADR-0019: one discarded run, best of 5, K concatenated copies, minus the best of 5
  start-up runs of the same filter on `/dev/null`, divided by K. `G7.engine.1k` uses that rule on
  `-c .a`; `G7.process.1k` is one whole process on one 1 KiB document, nothing subtracted.
- Columns: K is the number of concatenated copies per run; best and start-up are wall-clock
  milliseconds of one jq-ref process (best of 5); per document is (best − start-up) / K, printed in
  µs or ms as marked (the script prints µs). jqj's JMH units, once it has numbers: µs/op for the
  `avgt` scenarios (G1, G6), ms/op for the single-shot G7.

| id | filter | K | best | start-up | per document |
| --- | --- | --- | --- | --- | --- |
| G1.1k | `-c .a.b.c` | 10000 | 214.853 ms | 2.311 ms | 21.25 µs |
| G1.100k | `-c .a.b.c` | 100 | 244.976 ms | 2.348 ms | 2.426 ms |
| G1.10m | `-c .a.b.c` | 1 | 407.432 ms | 2.343 ms | 405.1 ms |
| G6.compact.10m | `-c .` | 1 | 548.518 ms | 2.367 ms | 546.2 ms |
| G6.pretty.10m | `.` | 1 | 689.134 ms | 2.423 ms | 686.7 ms |
| G7.engine.1k | `-c .a` | 10000 | 225.628 ms | 2.442 ms | 22.32 µs |
| G7.process.1k | `-c .a` | 1 | 2.352 ms | – | 2.352 ms (one process) |

**JMH flag spelling (ADR-0019, "UNVERIFIED" there).** Checked on JMH 1.37 from the jar's own `-h`,
`-lrf` and `-lprof` (`.logs/30-jmh-help.log`; Context7 is not offered in the implementer's session).
`-bm avgt -f 2 -wi 3 -w 1s -i 5 -r 1s -prof gc -rf json` is the right spelling: `-bm` takes
`avgt` and `ss`, `-w`/`-r` take a `<time>` such as `1s`, `-rf` lists `json`, `-lprof` lists `gc`.
A run of `G1_1k` with exactly those flags printed "Warmup: 3 iterations, 1 s each", "Measurement:
5 iterations, 1 s each", "Average time" and 2 forks (`.logs/30-jmh-flags.log`). Heap: `-jvmArgs`
(`-jvmArgsAppend`, `-jvmArgsPrepend`); the class's annotations carry these settings and G7's (`ss`,
10 forks, no warm-up, one iteration), and a flag given on the command line overrides them for every
benchmark, G7 included.

**How to run** (from the repo root, after `./mvnw -B -Pbench package`):

- Gate scenarios: `java -jar bench/jmh/target/benchmarks.jar -prof gc -rf json`, then
  `bench/jq-ref.sh` straight after, in the same shell.
- Small-heap fork of gate check 4 (G1 and G6 `-c` at 10 MiB; no extra ids, the same methods):
  `java -jar bench/jmh/target/benchmarks.jar 'G1_10m|G6_compact_10m' -jvmArgs "-Xms64m -Xmx64m" -prof gc -rf json`.
  NOT_RUN in #30 (the Stubs end every fork before a read); recorded in #39 (section above). Add
  `-foe true` to every recording run: without it a throwing benchmark exits 0.

## Build skeleton: one build alone and three in parallel (ticket #23), 2026-11-11

- Machine: `claude-cloud-4vcpu` (4 vCPU Intel Xeon @ 2.10GHz, 15 GB RAM, Linux 6.18), JDK 25.0.5,
  Maven 3.9.16 through the wrapper, `~/.m2` warm (shared, `file-lock`).
- Commit: `9c5c9f9` on `t/23-build-skeleton`. Command: `./mvnw -B -Pbench verify` (the whole
  reactor of ADR-0014 step 1: word check, enforcer, compile with `-Xlint:all -Werror`, tests,
  JaCoCo, licence check, jars, aggregate report), wall clock from the shell. No `clean` between
  runs after the first (the warm-up, from a fresh clone, is the clean figure). Three separate
  local clones of that commit, so the `target/` directories do not collide.
- The modules hold one placeholder class each, so these are the floor of the build, not a
  forecast. The numbers say how much three at a time cost, not how long a real gate takes.

| run | seconds |
| --- | --- |
| clean, alone (warm-up) | 7.4 |
| alone, 3 runs | 6.7, 6.8, 6.5 |
| three in parallel, round 1 | 14.9, 16.7, 17.0 |
| three in parallel, round 2 | 14.7, 15.0, 15.1 |
| three in parallel, round 3 | 14.7, 15.1, 15.1 |

Alone about 6.7 s; three at a time about 15 s each (2.2×), under ADR-0014's 60 s target for
step 1. PIT, the LF check and the trailer check were not part of these runs. PIT of the
placeholder in `oracle` with `crossModule`, whole `-am` reactor: 9.5 s (`.logs/23-pit-oracle.log`,
single run, not repeated three at a time; the first PIT run in `query-core` took 24.9 s because
it downloaded the plugin).

## Reference times and limits (issue #12), 2026-11-11

Observed by the grilling-adviser on `claude-cloud-4vcpu`, jq 1.8.2 (the reference jq), JDK 25.0.5,
joni 2.2.9. Inputs synthetic (ADR-0019's generators). Logs posted on issue #12. Bind ADR-0019.

**Reference jq, time per document** (best of 5 minus the best start-up run, divided by K):

| cell | K | per document |
| --- | --- | --- |
| G1 1K / 100K / 10M | 10000 / 100 / 1 | 16.46 µs / 1.670 ms / 376.2 ms |
| G2 1K / 100K / 10M | 10000 / 100 / 1 | 26.26 µs / 3.542 ms / 495.4 ms |
| G3 1K / 100K / 10M | 10000 / 100 / 1 | 95.48 µs / 10.26 ms / 1907 ms |
| G4 10⁴ / 10⁵ / 10⁶ | 100 / 10 / 1 | 5.714 ms / 49.63 ms / 660.1 ms |
| G5 1K / 100K / 10M | 10000 / 100 / 1 | 80.79 µs / 8.018 ms / 1196 ms |
| G6 `-c` 1K / 100K / 10M | 10000 / 100 / 1 | 32.27 µs / 3.837 ms / 476.0 ms |
| G6 pretty 10M | 1 | 560.8 ms |

- Start-up (`jq -n 1`): best 2.072 ms, median 2.214 ms. One process on one 1K document: 2.078 ms.
- Between two sessions the reference's best-of-5 moved by −29 % to +10 % (why the ratchet holds
  jqj's time, not the factor).
- JVM hello-world start: 22.9–24.0 ms (G7 has no factor).

**joni, `("a"*n+"b") | test("^(a+)+$")`** (one JVM in order; fresh JVM per n):

| n | warm median (min–max), one JVM | warm median (min–max), fresh JVM |
| --- | --- | --- |
| 22 | 203.9 ms (196.8–240.5) | 195.7 ms (146.1–198.4) |
| 23 | 436.4 ms (414.9–453.1) | 414.3 ms (396.5–421.4) |
| 24 | 828.3 ms (702.2–843.1) | 786.7 ms (582.4–840.0) |
| 25 | 1592 ms (1277–1809) | 1578 ms (1387–1730) |

n = 23 cold 442.5 ms, warm 436.4 ms → max(2 × warm, cold) = 872 ms → `regexMatchTime` 1 s.

**Small heap, reading a 10 MB document** (Spike B reader, tape 16 MiB):

| heap | G1 | Serial | Parallel |
| --- | --- | --- | --- |
| 64 MiB | both pass | both pass | both pass |
| 48 MiB | both pass | `Docs.make` OOM | both OOM |
| 40 MiB | `Docs.make` OOM | both OOM | both OOM |

The writer, G2 and the JMH harness in a small-heap fork: NOT_RUN.

**Spike E (hybrid recursion, medians of 15 reps, three rounds):** `each` 1000 levels 0.46–0.58 ms,
10⁶ levels 92–154 ms; `tail` 1000 levels 0.16–0.31 ms, 10⁶ levels 38–59 ms. Single runs reached
277 ms at 10⁶.

## Spike A extension: nested drivers (issue #7), 2026-11-10

- Same machine and JVM as "Spike A": OpenJDK 25.0.5, 4 vCPU, `-Xms2g -Xmx2g`, default 1 MB stack.
- Code: branch `spike/recursion-depth` @ `7fd4ea6`, variant `hybrid`, run with `-Dlimit=256`,
  `-Dbounce=64` and `-Dresume=false|true`. Raw output is in
  `spike/recursion/results/7-nested-drivers-v2.log` there.
- The first run (`0c6bffe`, `7-nested-drivers.log`) is superseded. In form B it dropped a result
  that `cps()` delivered before the first drive, so shape E gave `WRONG(32640)`. The
  grilling-adviser found this.
- Filter `nested`:

  ```
  def s: if . == $L then 0 else $a[.] + (. + 1 | s) end;
  def g: if . == $L then 0 else (. + 1 | g) as $x | $x + (($L - 1000) | s) end;
  0 | g
  ```

  It is a non-tail recursion of depth n whose continuation starts a second recursion of depth 1000
  at every level.
- The two forms of hand-over from the Java stack to the trampoline:
  - **Form A**, Spike A's `hybrid`: the caller's sink runs inside the driver.
  - **Form B**: the driver stops at each result and returns it to its starter. The starter emits
    the result from its own frame, then resumes the driver.

Cold JVM, one run each (ms; the last column is the most drivers live at once):

| form | nested n=2000 | nested n=10⁴ | E 10⁶ | N 10⁶ | T 10⁶ | M 10⁶ | drivers |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A | SOE after 909 | SOE after 3214 | ok, 257 | ok, 699 | ok, 154 | ok, 188 | 201 / 202 on nested |
| B | ok, 983 | ok, 4049 | ok, 312 | ok, 746 | ok, 183 | ok, 164 | 1 |

Warm medians in ms, with min–max: 20 warm-up runs at n=1000, then 5 reps per n.

| shape | form | n=1000 | n=10⁵ | n=10⁶ |
| --- | --- | --- | --- | --- |
| T | A | 0.39 (0.31–0.41) | 14.94 (13.71–23.91) | 90.89 (43.98–146.25) |
| T | B | 0.31 (0.30–0.33) | 13.57 (9.30–20.18) | 76.78 (47.06–151.48) |
| E | A | 0.77 (0.72–0.81) | 21.84 (8.14–30.39) | 177.46 (164.97–284.57) |
| E | B | 0.89 (0.79–1.19) | 34.70 (11.57–37.05) | 227.96 (104.74–293.53) |
| M | A | 0.35 (0.34–0.55) | 12.10 (9.63–24.44) | 108.84 (73.63–158.33) |
| M | B | 0.54 (0.47–0.61) | 17.57 (15.18–31.98) | 90.35 (55.51–148.59) |
| N | A | 0.58 (0.54–0.60) | 33.89 (29.32–58.78) | 412.82 (248.48–601.41) |
| N | B | 0.56 (0.53–0.78) | 29.36 (23.95–55.92) | 450.80 (295.42–519.97) |

Form A overflows on `nested`, and form B completes it. On the four Spike A shapes, form B is
within the noise except E, the shape with many results per driver, which is about 1.3× at 10⁶.
ADR-0011 takes form B.

## Tape reader for milestone 1 (issue #5), 2026-11-10

- Same machine, JVM and JMH settings as "Spike B" (2 forks × 5 × 1 s, 3 × 1 s warm-up, `-prof gc`).
  Code: branch `spike/field-read` @ `011d4cf`, `spike/fieldread/src/spike/{TapeM1,M1Bench,CheckM1}.java`;
  raw output `spike/fieldread/results/5-m1.*` and `5-checkm1.log` there. Documents: `Docs.make`,
  field in the middle.
- `spikeBTape` = the Spike B tape (reused). `m1NoDup` = the #5 round 1 layout (container offset
  at the closing bracket) plus UTF-8 classification (invalid UTF-8 or DEL → escaped-string tag)
  and the 10 000 nesting limit. `m1Dup` = `m1NoDup` plus duplicate-key detection: linear compares
  up to 8 keys, a reused open-addressing table with an FNV hash above 8 keys.
- `CheckM1`: same entry counts as Spike B (185 / 19 997 / 2 049 843), `.target` found; duplicates
  detected below and above 8 keys; depth 10 000 accepted, 10 001 rejected; invalid UTF-8 → tag 6.

| µs/op (B/op) | 1 KB | 100 KB | 10 MB |
| --- | --- | --- | --- |
| spikeBTape | 2.094 ± 0.166 (0.015) | 239.457 ± 17.252 (1.7) | 33 422.727 ± 2 321.770 (229) |
| m1NoDup | 2.118 ± 0.148 (0.015) | 246.242 ± 21.131 (1.7) | 34 308.973 ± 2 804.665 (236) |
| m1Dup | 2.800 ± 0.122 (0.020) | 325.073 ± 26.434 (2.3) | 48 892.116 ± 5 878.920 (334) |

Duplicate detection in the reader: +32 % at 1 KB and 100 KB, +43 % at 10 MB over `m1NoDup`.
The root object has 8 / 770 / 78 841 keys in the three documents (counted over the same tape), so
the 10 MB cost is mostly one very wide object. `TapeM1`'s detector shares one table stamp across
nesting levels and compares raw key bytes; it is a timing prototype, not a template.
UTF-8 classification and the depth limit: +1–3 %, inside the error.

## Spike B — reading one field (issue #4), 2026-11-10

- Machine: Claude Code Cloud VM, 4 vCPU Intel Xeon @ 2.10GHz, 15 GB RAM, Linux 6.18.
- JVM: JDK 25.0.5 HotSpot, `-Xms2g -Xmx2g`, otherwise defaults.
- Code: branch `spike/field-read` @ `3a75399`, `spike/fieldread/run.sh`; raw output in
  `spike/fieldread/results/` there.
- JMH 1.37, `Mode.AverageTime`, 2 forks × 5 iterations × 1 s after 3 × 1 s warm-up, `-prof gc`
  (allocation = `gc.alloc.rate.norm`); error column is JMH's 99.9% interval.
- Documents (`Docs.make`): root object of records, 969 B / 107 765 B / 11 206 791 B, `.target`
  at the start, middle or end of the root object. Variants checked equal to `jq-ref -c .target`
  on all nine.
- Reference: `jq-ref` 1.8.2 `-c .target <file>`, best of 5 wall-clock runs incl. process start
  (start-up alone, `jq-ref -n 1`: 2.36 ms): 1 KB 2.20–2.27 ms, 100 KB 5.00–5.07 ms, 10 MB
  451.15–465.00 ms; max RSS 12.7 MB / 12.7 MB / 173.7 MB.

- Tape size (corrected 2026-11-10, `Count.java` over the same `Tape`/`Docs`, log
  `.logs/4-tapecount.log`): 185 / 19 997 / 2 049 843 entries for the 1 KB / 100 KB / 10 MB
  documents = 1 480 B / 159 976 B / 16 398 744 B of tape, 1.53× / 1.48× / 1.46× the input. The
  33.5 MB allocated by `tapeFresh` at 10 MB is the doubling from 1024 entries (2 097 152 capacity
  plus every smaller array), not the tape size.

Per read, µs/op and bytes allocated/op:

| document | tapeReused | tapeFresh | validateOnly | skipAll | stopFirst |
| --- | --- | --- | --- | --- | --- |
| 1KB start | 2.10 ± 0.14 µs, 0 B | 3.67 ± 0.37 µs, 8256 B | 1.91 ± 0.10 µs, 0 B | 1.45 ± 0.19 µs, 0 B | 0.02 ± 0.00 µs, 0 B |
| 1KB middle | 2.02 ± 0.11 µs, 0 B | 3.85 ± 0.22 µs, 8256 B | 1.83 ± 0.12 µs, 0 B | 1.35 ± 0.15 µs, 0 B | 0.62 ± 0.06 µs, 0 B |
| 1KB end | 2.09 ± 0.13 µs, 0 B | 3.72 ± 0.24 µs, 8256 B | 1.85 ± 0.09 µs, 0 B | 1.33 ± 0.18 µs, 0 B | 1.35 ± 0.15 µs, 0 B |
| 100KB start | 249.16 ± 28.58 µs, 2 B | 354.98 ± 36.84 µs, 516242 B | 212.06 ± 13.15 µs, 1 B | 152.63 ± 10.29 µs, 1 B | 0.02 ± 0.00 µs, 0 B |
| 100KB middle | 233.46 ± 16.91 µs, 2 B | 327.33 ± 17.67 µs, 516242 B | 214.26 ± 27.48 µs, 1 B | 146.04 ± 7.96 µs, 1 B | 86.57 ± 9.50 µs, 1 B |
| 100KB end | 240.97 ± 20.97 µs, 2 B | 328.21 ± 27.79 µs, 516242 B | 223.70 ± 22.24 µs, 2 B | 145.79 ± 5.34 µs, 1 B | 158.39 ± 18.68 µs, 1 B |
| 10MB start | 34869.82 ± 1782.14 µs, 238 B | 41112.84 ± 2379.01 µs, 33546762 B | 23145.91 ± 2334.44 µs, 159 B | 14601.04 ± 801.09 µs, 101 B | 0.02 ± 0.00 µs, 0 B |
| 10MB middle | 33292.47 ± 1327.09 µs, 227 B | 40120.80 ± 2940.32 µs, 33546756 B | 22266.71 ± 446.87 µs, 153 B | 14498.29 ± 633.05 µs, 100 B | 8206.26 ± 1058.41 µs, 57 B |
| 10MB end | 34993.23 ± 1212.77 µs, 240 B | 40188.93 ± 2348.23 µs, 33546757 B | 24373.64 ± 2135.80 µs, 168 B | 16364.04 ± 1128.59 µs, 113 B | 15927.43 ± 987.10 µs, 110 B |

## Spike A follow-up — hybrid stack + trampoline (issue #3), 2026-11-10

- Same machine, JVM and method as "Spike A" below. Code: branch `spike/recursion-depth` @ `4dd57d0`,
  variant `hybrid` (`-Dvariant=hybrid -Dbounce=64 -Dlimit=K`): tail calls as jumps (as
  `tailloop`), other calls on the Java stack (1 MB) up to K nested calls, a call beyond K runs on
  the CPS trampoline and hands its results back to the direct caller. Raw output
  `spike/recursion/results/3-spike-hybrid.log` there.

Time (ms, median; 5 reps, 15 at N=10⁶), T / E / M / N:

| K | N=1000 | N=10⁴ | N=10⁵ | N=10⁶ |
| --- | --- | --- | --- | --- |
| 256 | 0.31 / 0.89 / 0.31 / 0.61 | 2.62 / 3.30 / 2.83 / 4.36 | 8.99 / 11.12 / 13.63 / 44.49 | 47.07 / 132.17 / 52.34 / 350.12 |
| 1000 | 0.30 / SOE / 0.33 / SOE | 2.52 / SOE / 2.84 / SOE | 8.81 / SOE / 11.96 / 30.24 | 43.90 / SOE / 44.93 / 383.47 |

Cold first run (fresh JVM): K=256 all four ok at 10³ and 10⁴; K=1000 E and N SOE at 10³ and 10⁴.
K=256 at N=10⁶, cold: 131.19 / 262.98 / 141.72 / 622.94 ms, max RSS 160 / 305 / 191 / 365 MB.
Baselines measured in the same run at N=1000 (15 reps): stack T 0.21, M 0.23, N 0.27; tailloop T
0.29, M 0.31, N SOE (it was ok at 0.28 in the earlier run); bigstack 1 GB E 1.09, N 0.86, and at
N=10⁶ E 151.12, N 285.26.

## Spike A — recursion depth (issue #3), 2026-11-10

- Machine: Claude Code Cloud VM, 4 vCPU Intel Xeon @ 2.10GHz, 15 GB RAM, Linux 6.18.
- JVM: JDK 25.0.5 HotSpot, `-Xms2g -Xmx2g`, otherwise defaults (`ThreadStackSize` 1024 KB).
- Code: branch `spike/recursion-depth` @ `48b6c4b`, `spike/recursion/run.sh`; raw output
  `spike/recursion/results/*.log` there. Not JMH: a hand loop, 20 warm-up runs at N=1000, then
  the median of 5 (N ≤ 10⁵) or 15 (N = 10⁶) runs; spread is wide (see min/max in the logs).
- Reference: `/usr/local/bin/jq-ref` 1.8.2, best of 3 wall-clock runs incl. process start and
  parse (`jq-ref length` on the N=10⁶ file: 230 ms), max RSS via `wait4`.
- Filters (`spike/recursion/filters/`): T self tail call, E self call inside "for each result",
  M mutual tail calls, N non-tail; N = depth = input length.

Time at N=10⁶ (ms; spike = evaluation only, values are `Long`/`Object[]`, no JSON reading):

| variant | T | E | M | N |
| --- | --- | --- | --- | --- |
| jq-ref 1.8.2 (whole process) | 1636.8 | 1249.1 | 1702.2 | 987.5 |
| stack (1 MB) | SOE | SOE | SOE | SOE |
| virtual thread | SOE | SOE | SOE | SOE |
| tailloop | 50.61 | SOE | 44.30 | SOE |
| tramp, bounce 0 | 341.43 | 257.70 | 345.15 | 226.52 |
| tramp, bounce 64 | 196.72 | 191.61 | 230.92 | 327.92 |
| bigstack 1 GB | 126.51 | 168.26 | 135.87 | 116.17 |

Time at N=1000, warm (ms): stack 0.21 / SOE / 0.24 / 0.32; tailloop 0.31 / SOE / 0.34 / 0.28;
tramp(0) 0.94 / 0.93 / 1.09 / 0.51; tramp(64) 1.00 / 0.89 / 0.90 / 0.67; bigstack 0.68 / 1.00 /
0.68 / 1.00 (includes starting the thread); virtual 0.55 / SOE / 0.35 / 0.54.

Max depth after warm-up (binary search, ±1%): stack 1 MB and virtual thread T ~10 681, E ~3 907,
M ~2 030 (virtual 2 015), N ~11 780 (virtual 11 719); bigstack 64 MB T ~757 812, E ~464 843,
M ~695 312, N ~835 937; 256 MB T ≥ 4 000 000, E ~1 859 375, M ~2 781 250, N ~3 343 750;
1 GB all ≥ 4 000 000. Cold first run (fresh JVM), stack variant: T, M, N ok at 10³ and SOE at 10⁴;
E SOE at 10³.

Max RSS at N=10⁶ (MB), cold single run: jq-ref 93 / 93 / 151 / 267; tailloop 162 / – / 191 / –;
tramp(0) 605 / 478 / 542 / 364; tramp(64) 454 / 288 / 488 / 364; bigstack 304 / 413 / 450 / 313;
a JVM failing fast with SOE: 76.


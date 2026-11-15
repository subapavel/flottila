# Benchmark results

Every measured number lives here with machine, date, configuration and commit. A number that is
not in this file does not exist. Empty until the first JMH harness ticket.

## Build times (baseline for ADR-0017, grilling #13)

Machine `claude-cloud-4vcpu` (Claude Code cloud VM, 4 vCPU, 15 GB), 2026-10-30, JDK 25.0.5 (Oracle),
Maven 3.9.11, offline with a warm local repo. Scratch reactor (`xdm`, `structure`,
`structure-import`, `service` on Quarkus 3.40.1.1, `bench/jmh`), not a repo commit. Wall seconds,
two runs each.

| Command | Run 1 | Run 2 |
| --- | --- | --- |
| `-q -pl engine/xdm -am verify` | 3.7 | 3.6 |
| `-q -pl engine/structure-import -am verify` | 3.8 | 3.8 |
| `-q -pl service -am verify` | 14.3 | 14.3 |
| `-q verify` (full) | 14.9 | 14.3 |
| `-q -T 4 verify` (full) | 13.7 | 13.7 |
| `-B -q -DskipTests verify` (ticket gate step 2, with Spotless, enforcer, licences) | 8.4 | 8.5 |
| `-B -q verify -Pbench` (full + JMH jar) | 16.1 | 16.0 |
| scoped engine build, 3 copies in parallel | 8.7–9.9 each | |
| `-pl service -am verify`, 3 copies in parallel (`quarkus.http.test-port=0`) | 34.5–35.1 each | |
| `quarkus:dev` via `-pl service -am`, start to first answer | 10.3 | 8.3 |
| `-pl service -am verify` from an empty local repo, 3 in parallel (Google mirror) | 75–76 each | |

Budget: an engine ticket gate under 10 s with three in parallel; one that builds `service` under 40 s.

## Oracle and ratchet trial (grilling #14)

Machine `claude-cloud-4vcpu`, 2026-10-30, JDK 25.0.5, Maven 3.9.11, warm scratch repo. Scratch
reactor with two small modules (`engine/demo`, `engine/oracle`), not a repo commit. Wall seconds,
`clean verify`, not comparable with the table above (that one was without `clean`).

| Command | Time |
| --- | --- |
| `-q -pl engine/demo -am clean verify`, JaCoCo on | 8.5 |
| same, `-Djacoco.skip=true` | 6.9 |
| full `clean verify`, JaCoCo on | 12.5 |
| full `clean verify`, JaCoCo off | 10.5 |
| PIT `mutationCoverage` on `engine/demo` (14 mutants), mvn wall / PIT itself | 9.3 / 5 |
| `java ratchet.java check` (prototype) | 0.9–1.1 |
| `vitest run` vs `vitest run --coverage`, tiny `ui/` project | 1.1–1.2 vs 1.2–1.4 |

Budget (ADR-0019): an engine ticket gate including `engine/oracle` under 30 s with three in
parallel; one that builds `service` under 60 s. Not yet measured on the real modules: the skeleton
ticket measures it here.

## Ticket-gate budget after spec 1 (#21, story 33)

Machine `claude-cloud-4vcpu` (`nproc` 4), 2026-11-02, JDK 25 (Temurin, `/opt/temurin-25`), commit
68c81fc on `spec/21-walking-skeleton`. Real modules, detached worktrees sharing `~/.m2`, without
`clean`. Wall seconds. The verifier measured these; the raw timings are summarised here and the
logs were not kept.

| Command | Wall s |
| --- | --- |
| step 1, engine ticket (`-B -pl engine/xdm,engine/oracle -am verify`), 3 in parallel, warm-up | 114.8 / 114.8 / 116.5 |
| same, measured | 109.8 / 110.1 / 109.9 |
| step 1, service ticket (`-B -pl service,engine/oracle -am verify`), alone | 79.5 |
| step 2 (`-B -q -DskipTests verify`), alone | 23.5 |

Budget (WORKFLOW §6, ADR-0019): engine under 30 s with three in parallel, service under 60 s,
step 2 about 15 s. **All three are missed** (F-13).

## UDF trial (grilling #16, ADR-0020)

Machine `claude-cloud-4vcpu`, 2026-10-30, JDK 25.0.5, scratch reactors (not a repo commit), test
JVMs with `-Xmx512m`. Rough `System.nanoTime` timings, no JMH: these are orders of magnitude, not
baselines.

| Measurement | Result |
| --- | --- |
| in-process javac, first compile in a fresh JVM (engine module path) | 581–780 ms |
| in-process javac, warm, median of compiles 2–10 | 60–71 ms |
| warm compile of a 10-method UDF file | 111–147 ms |
| load a library + link 4 `MethodHandle`s | 1–3 ms |
| single-value UDF call via `invokeExact`, 1M calls warm | ~17 ns/call (direct call ~25 ns, noise) |
| Quarkus 3.40.1.1 fast-jar, first compile after start (server / client wall) | 604 / 655 ms |
| Quarkus fast-jar, warm, median of 10 (server / client wall) | 55 / 76 ms |
| ModuleLayer alternative: resolve + define + link, two layers | 14–22 ms |
| Class-File API instrumentation (guard at entry + back edges), per class | 1.2–1.6 ms |
| tight loop, per iteration: none / interrupt-check guard / ThreadLocal+nanoTime deadline | 0.95 / 0.95 / 28 ns |
| interrupted instrumented loop stops after | 0.6 ms |
| allow-list instruction walk, per class | 28–390 µs |
| child JVM start, run one UDF, exit (median of 5; `TieredStopAtLevel=1` same) | 64–75 ms |
| `destroyForcibly` on a spinning child JVM | 4–6 ms |
| class loader unload after the last reference (5× `System.gc()`) | ~340 ms |

## Interpreter and bytecode trial (grilling #17, ADR-0021)

Machine `claude-cloud-4vcpu` (4 vCPU Xeon 2.10 GHz, 15 GB), 2026-10-31, JDK 25.0.5, JMH 1.37.
Scratch reactor (`proto` with a toy queue block, StAX projector, hand-written UTF-8 writer and one
`orders`-shaped mapping: 1:1, two-input `Lift` concat, `removeContexts`, `splitByValue` + one-input
`Lift` with a UDF, unbounded targets), not a repo commit. Back ends: **a** tree-walking `switch`
interpreter, **b** closure-compiled interpreter, **c** Class-File API hidden class. JMH
`-f 1 -wi 3 -i 5 -w 1s -r 1s -prof gc`, three rounds; cells give the range of the three scores.
Toy numbers: the shapes and ratios inform ADR-0021, they are not baselines.

| Benchmark | a | b | c | Unit |
| --- | --- | --- | --- | --- |
| small 8.1 kB, parse + map + serialise | 111–123 | 109–112 | 96–104 | µs/op |
| small, map + serialise | 34.4–37.6 | 27.7–29.3 | 14.3–15.4 | µs/op |
| small, parse + map + serialise, alloc | 142,121 | 123,153 | 122,153 | B/op |
| small, map + serialise, alloc | 35,312 | 16,344 | 15,344 | B/op |
| large 10.54 MB, map + serialise | 64–73 | 43–46 | 32–39 | ms/op |
| large, map + serialise, alloc | 45.8 M | 21.1 M | 19.7 M | B/op |
| large, parse + map + serialise, 4 s iterations, one round | 323 ± 279 | 273 ± 317 | 183 ± 45 | ms/op |

| Measurement | Result |
| --- | --- |
| c with a no-op tracer compiled in / without interruption checks / default, small map-only | 13.5–15.1 / 13.4–14.4 / 14.3–15.4 µs/op (within noise) |
| UDF call, trivial body: direct / generated `invokeExact` on class-data condy / interpreter `invokeExact` on a field | 0.54–0.61 / 0.73–0.84 / 3.07–3.42 ns/op |
| UDF call, 80 B allocating body with guard loop: all call paths | 30–39 ns/op (the body dominates) |
| JMH noise: c small full, 3 runs back to back | 105.8 ± 31.4 / 112.0 ± 79.3 / 122.5 ± 129.3 µs/op (15.8 % spread) |
| bytecode compile, cold (first in a fresh JVM) / warm, median of 10 JVMs × 20 | 32.0 ms (26.3–46.7) / 2.3 ms |
| closure compile, warm | 0.05 ms |
| hidden class (no `STRONG`) unloaded after dropping references | after 1 `System.gc()` in 100/100, 7.4–19 ms |
| start to first mapped output, fresh JVM, median of 5: a / b / c | 98–109 / 122–124 / 129–136 ms |
| same with `-XX:AOTCache` (JEP 514 one-step training, 1.25 s, 13.2–13.6 MB cache) | 69 / 75 / 85 ms |
| five-variant byte-identity test (5 small seeds, 2 large 10.5 MB), surefire / `mvn -pl proto verify` wall | 2.1–3.7 s / 5.1–5.6 s |

Parsing takes about 85 % of the small message end to end. The large parse + map + serialise run
is GC-bound (bimodal iterations), so its error stays large even with 4 s iterations.

## Build skeleton (#22, ADR-0017, ADR-0019)

Machine `claude-cloud-4vcpu` (4 vCPU, JDK 25.0.5, Maven 3.9.11 via `./mvnw`, warm `~/.m2`), 2026-10-31.
Built from branch `t/22-build-skeleton` on top of `16ca7c2`; the skeleton has no production code, so
these are build-overhead baselines (plugins, Spotless, enforcer, licences, JaCoCo, Quarkus augmentation),
not test times. The first real tests will move them. Wall seconds, `-B -q`.

| Command | Time |
| --- | --- |
| ticket gate step 1, engine: `-pl engine/xdm,engine/oracle -am verify` (whole engine), single | 8.9, 9.6 |
| same, 3 copies of the repo in parallel (3 worktrees) | 21.0, 21.8, 21.8 |
| ticket gate step 1, service: `-pl service,engine/oracle -am verify`, single | 20.2, 20.8 |
| same, 3 copies in parallel | 53.6, 54.3, 54.3 |
| ticket gate step 2: `-DskipTests verify` (whole reactor incl. Quarkus build) | 14.8, 15.4 |
| full `-Pbench clean verify` | 19.6, 20.6 |
| `java scripts/ratchet.java check` | 0.9, 0.9 |
| `scripts/gate-spec.sh` end to end (all steps, JMH not run) | 99.9 |

Budget (ADR-0019): engine ticket gate under 30 s with three in parallel: 21.8 s, met. Service ticket gate
under 60 s with three in parallel: 54.3 s, met with 6 s to spare and with no `@QuarkusTest` yet, so the first
service tickets will probably break it. Step 2 is 15 s, not the "about 8 s" of ADR-0019: the real reactor has
13 modules and a Quarkus augmentation (about 10 s of it).

## Playwright 1.63.0 and the cloud image's Chromium (#22)

Checked 2026-10-31 on `claude-cloud-4vcpu` (and on the GitHub runner, see below).

| | Expected by `playwright-core` 1.63.0 (`browsers.json`) | Installed in `/opt/pw-browsers` |
| --- | --- | --- |
| chromium | build 1243 (Chrome for Testing 153.0.8010.12) | `chromium-1243` (153.0.8010.12) |
| chromium headless shell | build 1243 | `chromium_headless_shell-1243` (153.0.8010.12) |
| ffmpeg | 1011 | `ffmpeg-1011` |

Playwright 1.63.0 and the image match. The "mismatch" seen in the #14 trial (Vitest browser mode wanting
build 1248) came from a second Playwright copy: `@vitest/browser-playwright` 5.0.5 takes `playwright` as a
peer dependency (`*`), and with only `@playwright/test` 1.63.0 declared npm resolved the peer to **1.64.1**
and hoisted it, so the Vitest provider launched the 1248 headless shell. Observed in `npm ls playwright-core`
and in the CI log of the first run (`npx playwright install` fetched 1243, Vitest looked for 1248).

Resolution: `ui/package.json` declares `playwright` 1.63.0 as an exact devDependency, so there is one
Playwright copy. Then Vitest browser mode, `npm run e2e` and `npm run e2e:stack` all find the image's
Chromium with no path and no environment variable, and CI's `npx playwright install chromium` fetches the
same 1243. The optional `PW_CHROMIUM_EXECUTABLE` that #22 allowed for a mismatch is therefore not needed and
not added; `deploy/claude-cloud/setup.sh` is unchanged. Playwright is not downgraded.

## Spec 5 gate: `ui/tree-20128/scroll` (#104, GATE check 7, tree)

Machine `claude-cloud-4vcpu`, 2026-11-06, commit 7fb5576 on `spec/104-mapping-editor`, Playwright 1.63.0 with
the image's headless Chromium (60 Hz frame cap), `npm run e2e -- tree-20128`, three runs in a row
(`ui/.logs/104-gate7-{1,2,3}.log` in the gate worktree). The scenario: 20,128 fields per side, fully
expanded, every leaf linked, scrolled top to bottom for 3 s, one step per animation frame.

| run | p95 frame (ms) | max frame (ms) | max rows in DOM |
| --- | --- | --- | --- |
| 1 | 16.8 | 133.3 | 32 |
| 2 | 16.8 | 116.6 | 32 |
| 3 | 16.7 | 100.0 | 32 |

Median p95: 16.8 ms against the GATE target of ≤ 16.7 ms. At a 60 Hz cap one frame is 16.67 ms, so the
lowest p95 this measurement can show is one vsync. 16.8 ms is that vsync plus timer jitter, not a dropped
frame: a dropped frame would push the interval to 33.3 ms, and fewer than 5 % of frames do that. The
same 16.7–16.8 ms was measured before the editor tickets touched the tree (#111, #114). The target as written
sits at the measurement's floor; finding #128 asks the owner how to read it. JMH: NOT_RUN (no
`bench/jmh` jar until spec 6).

## Spec 6 first measurement (#189)

Three runs of spec-gate step 5 (build `./mvnw -B -Pbench -pl bench/jmh -am package -DskipTests`; JMH
`-f 3 -wi 3 -i 5 -w 1s -r 1s -prof gc`; `java scripts/ratchet.java bench`), machine label `claude-cloud-4vcpu`,
2026-11-11. Nothing is written to `ratchet/bench.tsv`; the spec gate writes the first rows.

| run | commit | date (UTC) | host |
| --- | --- | --- | --- |
| local 1 | 5a2515f | 01:19 | **not recorded**: the container restarted before a host file was written; the CPU is not known |
| local 2 | 5a2515f | 01:28 | Intel Xeon Processor @ 2.10 GHz (family 6, model 207), 4 CPUs, MemTotal 16,472,084 kB |
| remote | 001da99 | 01:04 | Intel Xeon Processor @ 2.10 GHz, 4 CPUs, MemTotal 16,472,400 kB (comment on #189) |

The remote run measured 001da99, the local runs 5a2515f; the engine code is the same, 5a2515f only adds
tooling (merge of #192). Scores are the `ratchet.java bench` lines (the median of the fork means); the JMH JSON
of the local runs was used only to check them (same order of magnitude and ranking, raw fork-mean differs
by the median rule). The remote run did not include the tree scenario.

| scenario | run 1 | run 2 | remote | median | spread | target | judged on the median |
| --- | ---: | ---: | ---: | ---: | ---: | --- | --- |
| `compile/orders` (ms/op) | 0.6397 | 1.115 | 1.114 | 1.114 | 43 % | <= 5 | met |
| `contexts/small/latency` (us/op) | 222.9 | 412.2 | 342.7 | 342.7 | 55 % | <= 500 | met |
| `orders/small/latency` (us/op) | 249.3 | 386.2 | 378.5 | 378.5 | 36 % | <= 500 | met |
| `wide/small/latency` (us/op) | 104.5 | 172.9 | 159.9 | 159.9 | 43 % | <= 500 | met |
| `contexts/large/throughput` (MB/s) | 36.7 | 22.75 | 25.2 | 25.2 | 55 % | >= 20 | met |
| `orders/large/throughput` (MB/s) | 34.69 | 18.79 | 22.34 | 22.34 | 71 % | >= 20 | met |
| `wide/large/throughput` (MB/s) | 135.1 | 81.82 | 85.99 | 85.99 | 62 % | >= 20 | met |
| `contexts/large/alloc` (B/op) | 700,600,000 | 700,600,000 | 700,600,000 | 700,600,000 | 0 % | none | - |
| `orders/large/alloc` (B/op) | 793,300,000 | 793,300,000 | 793,300,000 | 793,300,000 | 0 % | none | - |
| `wide/large/alloc` (B/op) | 46,470,000 | 46,480,000 | 46,470,000 | 46,470,000 | 0 % | none | - |
| `contexts/small/alloc` (B/op) | 670,300 | 670,400 | 670,300 | 670,300 | 0 % | none | - |
| `orders/small/alloc` (B/op) | 730,600 | 735,700 | 736,700 | 735,700 | 1 % | none | - |
| `wide/small/alloc` (B/op) | 104,800 | 104,800 | 104,800 | 104,800 | 0 % | none | - |
| `contexts/large/heap` | completed | completed | completed | - | - | completes at 128 MB | met |
| `orders/large/heap` | completed | completed | completed | - | - | completes at 128 MB | met |
| `wide/large/heap` | completed | completed | completed | - | - | completes at 128 MB | met |
| `ui/tree-20128/scroll` (ms, p95 frame) | 16.7 | 16.8 | no run | 16.75 (two runs) | 1 % | < 33.3 | met |

Spread is (max - min) / median. Each local tree run repeated the scenario three times with an identical score
(16.7 and 16.8); no remote tree run exists, so the tree median is of two runs. All 3 forks of each `large/heap`
benchmark completed in every run (30 forks per run). The remote JMH run took 00:04:51; the local gate budget
was not timed, so WORKFLOW section 6 is not compared.

### Reading

- Run 2 judged `orders/large/throughput` at 18.79 MB/s, a missed target (`RATCHET FAIL`); runs 1 and remote met
  it. The owner decided on 2026-11-11 that a target is judged on the median of three runs (#193); on the
  median, 22.34 MB/s, every target is met. No target is missed.
- Allocation per 10 MB message (input about 10.49 MB): `orders` 793.3 MB, about 76 times the input;
  `contexts` 700.6 MB, about 67 times; `wide` 46.5 MB, about 4.4 times. It is stable across hosts (spread
  0 %), so it is an engine property, not noise. Follow-up: #194 (diagnose and reduce the orders allocation).
- The time scores spread by 36 to 71 % across hosts on the same label, beyond the 20 % ratchet tolerance:
  F-36.

## Orders allocation (#194)

Machine `claude-cloud-4vcpu`: Intel Xeon Processor @ 2.10 GHz (family 6, model 207), 4 CPUs, MemTotal
16,472,084 kB, JDK 25.0.5, JMH 1.37, 2026-11-11. Branch `t/194-orders-allocation` on `spec/181-speed`. A ticket
run is informative only and moves no baseline (ADR-0021, Notes of 2026-11-11, point 4).

**Profile.** JFR `jdk.ObjectAllocationInNewTLAB` and `jdk.ObjectAllocationOutsideTLAB` (weight: TLAB or object
size) over eight runs of the large input in one JVM, grouped by the first engine frame on the stack. The JMH
`-prof jfr` default, `jdk.ObjectAllocationSample`, was biased here: it put 34 % (about 270 MB) on the StAX parse
(`XmlProjection.walk`), while a StAX pass reading every leaf allocates 28 MB (`ThreadMXBean`).

| site (first engine frame) | before | after | cause before |
| --- | ---: | ---: | --- |
| `XmlSerializer` | 50.1 % | 26.9 % | `OutputStreamWriter`: a `char[]` and a `HeapCharBuffer` per `write` of a name, character or text run |
| `formatNum` (`NumberPattern`, `FormatNumber`, `JavaPattern`, `XsDecimal`) | 26.1 % | 22.4 % | the pattern compiled to a picture, and the picture's sub-pictures matched by regex, per value |
| `dateTrans` (`FormatDate`, `DatePattern`) | 15.2 % | 6.5 % | the pattern compiled, the picture tokenised and a parse `Pattern` compiled per value; a `Formatter` per padded component |
| `XmlProjection` | 3.7 % | 18.7 % | the values themselves (`getElementText`) |
| `EngineFacade` (output `toByteArray`) | 1.4 % | 7.2 % | the output copied out of its `ByteArrayOutputStream` |

The shares are of about 794 MB per run before and about 142 MB after. What remains after is mostly the output
`ByteArrayOutputStream`'s growth (20.6 %, under `XmlSerializer.drain`) and copy, the parsed values, and the
per-value `BigDecimal` work of `formatNum`.

**Change.** The serializer encodes UTF-8 into one 8 KB buffer, with the writer's bytes kept (a surrogate pair
split between writes, `?` for a lone surrogate). `FormatNumber.Picture` and `FormatDate.Picture` hold a picture
analysed once. The binders of `formatNum` and the date family compile a `Literal` pattern once, when the call
binds. Date components are padded into the writer's builder. The output of all six bench inputs is
byte-identical to the base's (SHA-256 of each).

**Numbers.** Full run `-f 3 -wi 3 -i 5 -w 1s -r 1s -prof gc` on f5482d0, judged by `java scripts/ratchet.java bench`
(`RATCHET BENCH OK`, every `large/heap` completed), against the #189 medians:

| scenario | #189 median | #194 | change |
| --- | ---: | ---: | ---: |
| `orders/large/alloc` (B/op) | 793,300,000 | 142,100,000 | ÷ 5.58 |
| `orders/small/alloc` (B/op) | 735,700 | 152,200 | ÷ 4.83 |
| `orders/large/throughput` (MB/s) | 22.34 | 57.2 | + 156 % |
| `orders/small/latency` (us/op) | 378.5 | 157.5 | - 58 % |
| `contexts/large/alloc` (B/op) | 700,600,000 | 197,300,000 | ÷ 3.55 |
| `contexts/small/alloc` (B/op) | 670,300 | 203,000 | ÷ 3.30 |
| `contexts/large/throughput` (MB/s) | 25.2 | 48.66 | + 93 % |
| `contexts/small/latency` (us/op) | 342.7 | 192.3 | - 44 % |
| `wide/large/alloc` (B/op) | 46,470,000 | 12,100,000 | ÷ 3.84 |
| `wide/small/alloc` (B/op) | 104,800 | 77,370 | ÷ 1.35 |
| `wide/large/throughput` (MB/s) | 85.99 | 95.56 | + 11 % |
| `wide/small/latency` (us/op) | 159.9 | 152.8 | - 4 % |
| `compile/orders` (ms/op) | 1.114 | 1.16 | + 4 % |

The #194 targets: `orders/large/alloc` ≤ 210,000,000 B/op met; `orders/small/alloc` down by at least the same
factor (÷ 3.78) met; `orders/large/throughput` ≥ 30 MB/s met on this host. An earlier full run on ca28817,
whose engine differs only in how `FormatDate` pads (allocation the same), gave 62.52 MB/s and 142,100,000 B/op.

## Spec 6 gate (#181)

The spec gate's step 5 on commit a7b9c8b (the ratchet regenerated on 088fe30), 2026-11-11 (the result files are
from 11:34 UTC), machine label `claude-cloud-4vcpu`; host Intel Xeon @ 2.10 GHz, 4 CPUs, MemTotal 16,472,084 kB.
JMH `-f 3 -wi 3 -i 5 -w 1s -r 1s -prof gc`, the three heap forks (`contexts`, `orders`, `wide` `large/heap`, `-Xmx128m`)
completed, and `java scripts/ratchet.java bench` printed `RATCHET BENCH OK` with no re-run. The tree scenario's p95
is 16.7 ms (three runs: 16.8, 16.7, 16.7; the median is 16.7). These are the first `ratchet/bench.tsv` baselines;
F-36: baselines are written only by the spec gate.

| scenario (the `bench.tsv` row) | baseline | target |
| --- | --- | --- |
| `orders/small/latency` | 166.6 us/op | ≤ 500 us/op |
| `orders/small/alloc` | 152,200 B/op | none |
| `orders/large/throughput` | 53.56 MB/s | ≥ 20 MB/s |
| `orders/large/alloc` | 142,500,000 B/op | none |
| `contexts/small/latency` | 191 us/op | ≤ 500 us/op |
| `contexts/small/alloc` | 203,000 B/op | none |
| `contexts/large/throughput` | 45.66 MB/s | ≥ 20 MB/s |
| `contexts/large/alloc` | 197,300,000 B/op | none |
| `wide/small/latency` | 156.3 us/op | ≤ 500 us/op |
| `wide/small/alloc` | 77,420 B/op | none |
| `wide/large/throughput` | 91.35 MB/s | ≥ 20 MB/s |
| `wide/large/alloc` | 12,100,000 B/op | none |
| `compile/orders` | 1.228 ms/op | ≤ 5 ms/op |
| `ui/tree-20128/scroll` | 16.7 ms | < 33.3 ms |

Sources: `181-bench.log`, `181-jmh.json` and `181-tree-20128.json` of the gate run, and `ratchet/bench.tsv`.

## Milestone 1 gate, second run (#226)

The spec gate's step 5 on commit 53415fbf, 2026-11-14, machine label `claude-cloud-4vcpu`; host Intel Xeon @ 2.30GHz,
4 CPUs, MemTotal 16,473,776 kB. Configuration: the closure-compiled interpreter, as `gate-spec.sh` step 5 runs it:
JMH `-f 3 -wi 3 -i 5 -w 1s -r 1s -prof gc`. The three heap forks (`contexts`, `orders`, `wide` `large/heap`, `-Xmx128m`)
completed, and `java scripts/ratchet.java bench` printed `RATCHET BENCH OK`. The tree scenario's p95 is 16.7 ms (three
runs: 16.7, 16.7, 16.8; the median is 16.7).

| scenario (the `bench.tsv` row) | JMH value | GATE check 7 target |
| --- | --- | --- |
| `orders/small/latency` | 109.186 us/op (`bench.tsv` 109.6) | ≤ 500 us/op |
| `orders/large/throughput` | 128.790 ms/op, 78.27 MB/s; heap fork completed | ≥ 20 MB/s, completed with -Xmx128m |
| `contexts/small/latency` | 137.285 us/op (`bench.tsv` 137.4) | ≤ 500 us/op |
| `contexts/large/throughput` | 160.825 ms/op, 61.81 MB/s; heap fork completed | ≥ 20 MB/s, completed with -Xmx128m |
| `wide/small/latency` | 99.710 us/op (`bench.tsv` 99.86) | ≤ 500 us/op |
| `wide/large/throughput` | 72.927 ms/op, 136.7 MB/s; heap fork completed | ≥ 20 MB/s, completed with -Xmx128m |
| `compile/orders` | 0.816 ms/op (`bench.tsv` 0.8047) | ≤ 5 ms/op |
| `ui/tree-20128/scroll` | p95 16.7 ms | < 33.3 ms |

Allocation rows (`gc.alloc.rate.norm`; the ratchet row is the rounded value):

| row | JMH value | baseline |
| --- | --- | --- |
| `orders/small/alloc` | 155,112.770 B/op (155,200) | 152,200 |
| `orders/large/alloc` | 142,240,154.880 B/op (142,100,000) | 142,500,000 |
| `contexts/small/alloc` | 203,064.968 B/op (203,100) | 203,000 |
| `contexts/large/alloc` | 197,270,150.400 B/op (197,300,000) | 197,300,000 |
| `wide/small/alloc` | 77,339.273 B/op (77,320) | 77,420 |
| `wide/large/alloc` | 12,155,775.424 B/op (12,150,000) | 12,100,000 |

The ratchet judged GAIN on `compile/orders` (1.228 to 0.8047 ms/op), `contexts/large/throughput` (45.66 to 61.81 MB/s),
`contexts/small/latency` (191 to 137.4 us/op), `orders/small/latency` (166.6 to 109.6 us/op), `wide/large/throughput`
(113.2 to 136.7 MB/s) and `wide/small/latency` (156.3 to 99.86 us/op); `orders/large/throughput` (78.27 MB/s against 65.88)
was ok. These rises in `ratchet/bench.tsv` were measured but not committed: the ratchet is regenerated on an
integration branch, not on the gate branch.

Sources: `gate-bench.log`, `gate-jmh.log`, `gate-jmh.json` and `gate-tree-20128-{1,2,3}.log` of the gate run, and the
uncommitted diff of `ratchet/bench.tsv`.

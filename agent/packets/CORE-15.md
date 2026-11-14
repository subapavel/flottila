# CORE-15 — open-loop load harness (class B; GATE.md §CORE-GATE condition 2, P2-14 (b))

Architect 2026-11-05. Base: 4ba1919 (CORE-6b). Owner 2026-11-05: wants to know early whether the core goes the right way —
after this row, one preview run at 5 000 msg/s on this VM goes to RESULTS (not the gate, labelled preview).
Code: flottila-bench NodeBenchMain (scenarios SYNC|ACK|WORKER :110, closed-loop `load` :693-719, in-process raw echo
:304-305, report JSON ~:326, processCpuNanos :668, landed probe :654, bench_work_log), bench/run.sh (BENCH_SCENARIOS :128-158,
run_node :108-126, summary :97). Tests: NodeBenchMainTest, NodeBenchMainHarnessTest, NodeBenchMainSettleTest (fake world).

## Scope
1. **Open-loop mode** in NodeBenchMain: `--rate <msg/s>` (1..100 000), `--ramp <ISO duration>` (default PT1M, PT0S..PT10M),
   `--window <ISO duration>` (default PT10M, PT1S..PT1H). With `--rate`, scenario ack or sync runs open loop; without it the
   closed loop is unchanged. Request i is scheduled at a time fixed in advance (ramp linear from 0 to rate, then constant), sent
   when due whatever the answers; never waits for an earlier answer. In-flight bounded by `--max-outstanding` (default
   rate × 2 s, finite); a request due while the bound is full is not sent and counted `clientSaturated` (the run is then invalid
   and says so). Only requests scheduled inside the window count in the window figures.
2. **Fields** (JSON `open` object): offeredRate, scheduled, sent, achievedRate (sent in window / window seconds), accepted (202),
   refused by status (map; 503 separate), timeouts, errors, clientSaturated; ACK latency p50/p99/p999/max **from each request's
   scheduled send time** (no coordinated omission); partner receipts (the echo counts the requests it answers); backlog =
   accepted − receipts sampled at window start and end and each second (series), backlogGrowth and its % of accepted; CPU ms per
   process over the window: bench JVM (client + node + echo, as today), node via the cpu-sampling endpoint when on, Postgres from a
   cgroup or pid given by `--pg-cpu-source` (cgroup cpu.stat or /proc/<pid>/stat) — a field it cannot measure is null with
   a `cpuNotes` reason, never 0; a `valid` flag (false on clientSaturated or achievedRate off by > 1 %).
3. **Self-test** `--scenario echo` (open loop straight at the raw echo, no node, no Postgres needed): the harness's own ceiling.
4. **run.sh**: scenario `open` with BENCH_RATE, BENCH_RAMP, BENCH_WINDOW, BENCH_OPEN_SCENARIO (ack default), BENCH_PG_CPU;
   summary prints the open fields. Unknown values refused as today.
5. **Docs**: RESULTS.md gains a "Harness: open loop (CORE-15)" method note (what each field means, scheduled-time latency, valid).

## Red tests (test-author)
Scheduler unit (fake clock/timer, no sleeps): ramp then constant gives exact scheduled offsets and counts (rate × window in the
window); a stalled target never delays a later send; latency measured from scheduled time (an answer 30 ms late on a send due
at t → 30 ms + service); saturation counted, not sent, valid false; window-only accounting. Options: bounds and defaults refused/
accepted as above; `--rate` absent = closed loop unchanged (existing tests green). Backlog: accepted − receipts series from a
stub partner. CPU: an unreadable source → null + reason. IT (real time, short): `--scenario echo --rate 2000 --ramp PT1S
--window PT5S` gives achievedRate within 1 % of 2000, every field present, valid true; IT ack open loop against Testcontainers
Postgres at a low rate: accepted = sent, receipts reach accepted after settle, fields present. BenchRunScriptTest: `open` wiring.
Green unchanged: every existing bench test. Red = compile error or failing assertion, agent/work/CORE-15-red.log.

## Constraints
Bench module only (no change to api/core/runtime); no sleeps in unit tests (fake clock); the IT measures, its pass is a count
within 1 %, not a timing sleep; sizes long; every bound finite; CPU never reported as 0 when unknown. Floors no decrease.

## Checks
`mvn -B -ntp -q -pl flottila-bench -am verify -Pit`, reactor `verify -Pit`, coverage check; logs agent/work/CORE-15-*.log.

## Red approved (architect 2026-11-05)
Red observed: test-compile 98 missing-symbol errors on the assumed API; shim 27/54 semantic reds (agent/work/CORE-15-red.log).
Tests (flottila-bench): OpenLoopScheduleTest, OpenLoopTest (FakeClock/FakeTimer), NodeBenchMainOpenOptionsTest, CpuSourceTest,
NodeBenchOpenEchoIT, NodeBenchOpenAckIT (compile-red only: Docker was down — the implementer starts the services per NOTES "Build
and VM" and must see it run), BenchRunScriptOpenTest. API accepted as the test-author named it (OpenLoop.Schedule, OpenLoop,
Target, NodeBenchMain.Open, Scenario.ECHO, CpuSource, JSON `open` fields).
Architect decisions on the open readings: achievedRate counts sends whose actual time is inside the window (accepted); backlog
growth % is over the window's accepted count; 503 counted in refused503, not in the map; `worker` with `--rate` and `echo`
without `--rate` refused (IAE naming the option); BENCH_RATE default 5000 and passed through (NodeBenchMain validates) — the
implementer pins these three in its own cases. No jqwik in bench (not on the classpath; accepted).
Architect (implementer handback): accepted — validity against the scheduled rate (scheduled / window s; equals the offered rate
when rate × window is whole); node CPU meaningful only over minutes (5 s sampler); LatencyStats rank ceil(p·n/100) fixed; the
open exit code 3 only on window errors/timeouts. For the reviewer: exact latency samples, 8 B per window request (24 MB at the
gate cell, ~2.9 GB at the 100 000/s × PT1H maximum) — bound or histogram?

## Review PASS (2026-11-05)
Notes for the preview and CORE-GATE packets: client `--concurrency` above the flow's in-flight limit (or `--max-in-flight` set),
both recorded — else 503 cannot occur and overload hides as client queueing; record the harness ceiling first (`--scenario echo
--rate 5000 --ramp PT1M --window PT10M`) beside the ack run; Postgres CPU from its systemd cgroup, not one pid. Rest → FIX-123.

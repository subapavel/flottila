# ISSUE-3 — GATE check 6 "CPU time per step" cannot hold on virtual threads (F-21)
Task: GATE (via FIX-106)
Date: 2026-10-30

## What is needed
A decision on docs/GATE.md check 6, which asks for "wall and CPU time per step". The JVM (JDK 25.0.5) cannot measure a virtual
thread's CPU time, and every step runs on the message's virtual thread (the threading rule), so the timeline shows wall time per
step and `cpuNanos: null` (F-21, probes in it). Options:
1. Accept check 6 as "wall time per step; `cpuNanos` present and null where the JVM cannot measure a virtual thread (F-21)".
2. Option 1 now, plus FIX-108 after the gate: JFR CPU-time sampling (JEP 509) aggregated per flow and step ("slowest steps" by CPU),
   statistical (2–4 ms period), never a per-message figure.
3. Keep check 6 as written: needs a non-public scheduler hook or a native agent — against the "one plain JDK virtual thread" rule.

## Why the agent cannot decide it
It changes a hand-over gate condition the owner agreed (docs/GATE.md, 2026-10-17).

## What was tried
ThreadMXBean on a virtual thread (-1 for every reading), the carrier's thread clock through FFM (readable, silently counts other
threads' CPU after an unmount), JFR jdk.CPUTimeSample (attributes to the virtual thread, statistical). Details in F-21.

## Default if the owner does not answer
Work continues: FIX-106 records CPU as unknown (no fake 0) and FIX-108 stays pending. The default does not make check 6 met — the
gate reviewer cannot relax an agreed condition (ISSUE-2 precedent): check 6 stays NOT_MET on CPU, and the GATE row is `blocked` on
this issue once every other check holds. Option 1 or 2 → check 6 is met at once (GATE.md wording + changelog); option 3 → a design
task outside the threading rule.

## Owner's answer
2026-10-30, in the session: "ISSUE-3: 2" — option 2. Check 6 now reads wall time per step with `cpuNanos` present and
null where the JVM cannot measure (docs/GATE.md, changelog row); FIX-108 (JFR CPU sampling per flow and step) is ready after the gate.
Status: answered.

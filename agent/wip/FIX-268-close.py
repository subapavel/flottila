# FIX-268 - DRAFT, NOT APPLIED. The architect's edits for after a MET verdict of the gate-reviewer on check 7 and
# on the core gate's condition 6 (agent/packets/FIX-268.md, "Evidence and the judge"). Written 2026-11-17 while the
# measurement (agent/work/FIX-268-evidence.txt, commit 77b2c587, every part VERIFIED_PASS) waited for its judge;
# the session stopped before the gate-reviewer ran, so nothing below is a verdict. Before use: replace GREEN_JOBS
# (two places) by `ci` run 255's jobs as read on GitHub, and run it with the argument `dry` first.
# Usage, from the repository root: python3 agent/wip/FIX-268-close.py [dry]
import sys
dry = len(sys.argv) > 1 and sys.argv[1] == 'dry'
def rep(t, old, new):
    assert t.count(old) == 1, (t.count(old), old[:80])
    return t.replace(old, new)
out = {}

# 1. HANDOVER.md: the state paragraph and row 7
p = 'HANDOVER.md'
t = open(p).read()
t = rep(t, """Check 7 is not measured since
2026-11-15: replay left Rail8 (D-156) and part of the check's evidence was that operation's (row 7 in section 4 says what stands). FIX-268 measures
it again; whether the verdict stands until then is the owner's to say (ISSUE-10 item 1). Check 6 reads""",
"""Check 7 was measured again on
2026-11-17 on commit 77b2c587 (FIX-268), after replay left Rail8 (D-156) and `/retry` changed (D-136, FIX-275): met, as the check reads today
(row 7 in section 4). Whether the stronger form measured beside it - a partner that answers and fails - becomes the check's text is the owner's to
say (ISSUE-10 item 1). Check 6 reads""")
old7 = [l for l in t.split('\n') if l.startswith('| 7 | DLQ, retry | ')]
assert len(old7) == 1 and old7[0].endswith('| not measured since D-156 |')
new7 = ("| 7 | DLQ, retry | measured again 2026-11-17 on commit 77b2c587 (FIX-268), on the compose stack with the `kafka` profile off "
  "(`agent/work/FIX-268-stack.log`; summary `agent/work/FIX-268-evidence.txt`). Partner stopped: an async message is retried and parked - "
  "its DLQ row at step `partner` after 3 s, attempts 1 and 2 FAILED there (:186-210). Partner back: `POST /admin/dlq/{id}/retry` answers 202 "
  "with the same id and attempt 3; the timeline lists attempts 1 and 2 FAILED, then 3 OK to the final step, under the one id; the DLQ is "
  "empty; a second `/retry` answers 404 (:211-261). Beside the check, with a partner that answers 503 and then 200: 9 sends, the same "
  "`x-message-id` on every one (:262-331). The node's log: 0 ERROR lines. `ci` run 255 on the same commit: GREEN_JOBS. The gate's run of "
  "2026-10-30 showed a replay from step 3 (GATE2-stack.log:198) and a retry that was a new copy under a new id (GATE3-stack.log:66-81): "
  "the first has left Rail8 (D-156, F-55), the second restarts the message under its own id since D-136 "
  "| met (gate-reviewer, FIX-268, 2026-11-17) |")
t = t.replace(old7[0], new7)
out[p] = t

# 2. bench/RESULTS.md: the core gate's first section
p = 'bench/RESULTS.md'
t = open(p).read()
head = "# Bench results — Phase 2 (P2-08), Phase 1 (gate P1-10, P1-01, P1-02) and Phase 0 (gate, spikes, baselines)\n\n"
assert t.startswith(head)
sec = """## Core gate, condition 6 — no Kafka in the core — commit 77b2c587 — cloud VM, 4 vCPU (FIX-268)

Date 2026-11-17. Commit 77b2c587af1e83f3fb34bc9580c533445e64e2d6. Machine: cloud VM, 4 cores, Linux 6.18, Docker 29.9.2, Java 25.
This is the core gate's first entry in this file. It measures condition 6 of `docs/GATE.md` and nothing else: conditions 1 to 5 and
7 are not measured by it, and no throughput or latency figure is taken here. Verdict of the gate-reviewer on this condition: met.
Evidence: `agent/work/FIX-268-evidence.txt` (parts A.1 to A.4, B.1, B.2) and the logs it names.

- **With the compose `kafka` profile off, on the stack** (`deploy/compose`, started as its README says; `agent/work/FIX-268-stack.log`):
  9 services, neither `kafka` nor `clickhouse` among them (:139-153). Sync: `POST :8080/in` 200 with the partner's reply; async:
  `POST :8082/invoice/async` 202, final step `partner` OK (:158-185). DLQ retry: partner stopped, the message parked after its retries;
  partner started, `POST /admin/dlq/{id}/retry` 202, the same id delivered on attempt 3, the DLQ empty (:186-261).
- **The end-to-end tests, no Kafka container while they ran** (`-Pit -pl :rail8-runtime -am verify`, the classes named; exit 0):
  `NodeIT` 40 tests (sync, partner down then retry, the rows of each attempt), `InvoiceAsyncIT` 3, `AtLeastOnceIntakeIT` 3 (ingest),
  `AdminDlqRestartIT` 7, `ComposeStackTest` 24 (`kafkaOnlyUnderTheKafkaProfile`, `theDefaultUpStartsNeitherKafkaNorClickhouse`); 0
  failures, errors, skipped. The containers the run started, from its own log: localstack 1, postgres:18.6 4, valkey 1, ryuk 1.
  A 5-second `docker ps -a` watch beside it: `grep -ci kafka` 0.
- **No Kafka client on a core module's classpath**: `NoKafkaClientTest` 1 test, 0 failures in each of `rail8-api`, `rail8-core`,
  `rail8-runtime`, the components `http`, `mock`, `sftp` and the steps `xslt`, `validate`.
- `ci` run 255 on the same commit (the whole reactor with `-Pit`): GREEN_JOBS.

"""
for l in sec.split('\n'): assert len(l) <= 140, (len(l), l)
out[p] = head + sec + t[len(head):]

if not dry:
    for p, t in out.items(): open(p, 'w').write(t)
print('ok', 'dry' if dry else 'written', list(out))

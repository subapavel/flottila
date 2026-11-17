# Hand-over — "Pavel can test the first real iFlow"

Gate: `docs/archive/gate-2026-10-17.md` (agreed 2026-10-17). Gate commit: `fea6c5c` (2026-10-30). Evidence: the GATE section of
`bench/RESULTS.md`, CI runs 145 (push) and 146 (dispatch: build, stack, bench, mutation, DCO) on that commit, and the
session's verifier logs (`target/GATE2-*.log`, `target/GATE3-stack.log` for the evidence re-run, named per check below). Verdict: the gate-reviewer's, in `docs/archive/PROGRESS-until-2026-11-03.md`
(GATE entry).

**State in one line:** GATE_MET on 2026-10-30: checks 1–9 held on the gate commit. Check 7 is not measured since
2026-11-15: replay left Rail8 (D-156) and part of the check's evidence was that operation's (row 7 in section 4 says what stands). FIX-268 measures
it again; whether the verdict stands until then is the owner's to say (ISSUE-10 item 1). Check 6 reads "wall time per step, `cpuNanos` null where the JVM cannot
measure a virtual thread" since the owner answered ISSUE-3 with option 2 (2026-10-30); CPU per flow and step by JFR sampling is FIX-108.

## 1. Start the stack

```sh
cd deploy/compose
export RAIL8_ADMIN_TOKEN=$(openssl rand -hex 16)    # required
export RAIL8_S3_SECRET_KEY=$(openssl rand -hex 16)  # required
docker compose up -d --build                           # returns once the node is healthy
docker compose ps -a                                    # every service healthy, deploy and sftp-keys exited (0)
curl localhost:8081/health
curl -H "Authorization: Bearer $RAIL8_ADMIN_TOKEN" localhost:8081/admin/packages      # demo 0.1.0
docker compose --profile history up -d clickhouse      # optional, nothing feeds it yet
docker compose --profile history --profile kafka down -v   # stop, remove every volume
```

Services: `rail8` (the node), `postgres`, `valkey`, `s3` (SeaweedFS), `sftp` + `sftp-keys`,
`echo` (the partner), `netns` (holds the published ports and the network namespace that the node and the echo share; FIX-105),
`deploy` (one-shot `rail8 deploy` of `examples/demo`); `kafka` is the optional add-on under `--profile kafka` (CORE-9). Ports: 8080 sync door, 8082 async door, 8081 admin, all bound to
127.0.0.1. The repo-root `docker-compose.yml` is the quickstart variant (`docs/QUICKSTART.md`). More detail:
`deploy/compose/README.md`.

## 2. Run the flows by hand

```sh
A="Authorization: Bearer $RAIL8_ADMIN_TOKEN"
INV=../../examples/demo/fixtures/sample-invoice.xml

# Flow A sync (invoice-sync): 200 with the partner's reply
curl -X POST -H 'Content-Type: application/xml' --data-binary @$INV localhost:8080/in
# Flow A async (invoice-async): 202 after the durable save; the worker delivers it
curl -X POST -H 'Content-Type: application/xml' --data-binary @$INV localhost:8082/invoice/async
# Flow B (sftp-invoice): drop a file. The poller picks it up within 10 s, saves it, deletes it and delivers it
docker compose cp ../../examples/demo/fixtures/inbox/invoice-4711.xml sftp:/home/demo/inbox/
docker compose logs echo

# Snapshots
curl -H "$A" 'localhost:8081/admin/messages?key=invoiceId:4711'             # find by business key
curl -H "$A" localhost:8081/admin/messages/<id>                             # timeline: steps, wallNanos, cpuNanos (null, F-21), versions
curl -H "$A" localhost:8081/admin/messages/<id>/steps/3/body                # the payload after step 3
# DLQ and retry
docker compose stop echo; curl -X POST -H 'Content-Type: application/xml' --data-binary @$INV localhost:8082/invoice/async
curl -H "$A" localhost:8081/admin/dlq                                       # the row appears (gate run: 1.1 s, step 5 `partner`; since CORE-14a after one scheduled retry, at step 1)
docker compose start echo; curl -X POST -H "$A" localhost:8081/admin/dlq/<dlq-id>/retry    # 202, the same id once more (D-136)
# Kill test (check 5): drop files, then `docker kill -s KILL compose-rail8-1` and `docker compose up -d`
```

## 3. Run the tests

```sh
bin/rail8 validate examples/demo        # exit 0
bin/rail8 test examples/demo            # 6 cases: 6 passed, in-process, also run by CI
mvn -B -ntp -f platform/pom.xml verify -Pit   # unit + Testcontainers ITs, ArchUnit, JaCoCo floors (Docker needed)
python3 ci/coverage_check.py               # the no-decrease check against ci/coverage-baseline.json
bench/run.sh                               # the bench harness (native Postgres); numbers go to bench/RESULTS.md
```

## 4. Check by check (gate commit fea6c5c)

| # | check | evidence | result |
|---|---|---|---|
| 1 | stack, /health within 60 s | target/GATE2-stack.log "## up": 11 services healthy or exited (0); CI `stack` job 123931660052 waits on /health with a 60 s bound (green) | met |
| 2 | deploy from git, validate, version per message | `bin/rail8 validate examples/demo` exit 0 (target/GATE3-stack.log:1-7); deploy exit 0, /admin/packages demo 0.1.0 (sha256 archive), timeline `versions` `demo@0.1.0` (GATE2-stack.log:156,183,194) | met |
| 3 | invoice-sync | 200 with the PartnerInvoice body, timeline steps 0–5 OK | met |
| 4 | invoice-async | 202, final step `partner` OK, work 0 | met |
| 5 | sftp-invoice + kill | one file: inbox empty in ~6 s, sftp-invoice steps 0–4 OK, echo received it (GATE3-stack.log:49-65). Kill: 5 rounds × 50 files, node killed mid-round: 50/50 distinct ingress per round, DLQ 0, work 0, inbox empty, echo never restarted; outbound resends seen only as echo totals (at-least-once). No kill landed in the save-to-delete window (no dedupe line in run 2; run 1 on 5505b0f logged one); that window rests on SftpIngressDedupeIT (GATE2-stack.log:257-363) | met |
| 6 | timeline, body per step, search by key | `?key=invoiceId:4711` finds the message; timeline steps 0–5 with wallNanos; `/steps/3/body` and `/steps/5/body` 200 with the payload as it was after each step (GATE3-stack.log:17-48). `cpuNanos` null on every step, as the check now reads (F-21, ISSUE-3 option 2) | met |
| 7 | DLQ, retry | not measured since D-156 (2026-11-15). The gate's run showed a replay from step 3 (GATE2-stack.log:198), an operation that has left Rail8 (F-55), and a retry that was then a new copy under a new id (GATE3-stack.log:66-81); since D-136 `/retry` restarts the message under its own id. What stands of that run: the DLQ row at step 5 `partner` after 1.11 s (GATE2:367) / ~3 s (GATE3:66-81) (FIX-107). FIX-268 measures the check again | not measured since D-156 |
| 8 | `rail8 test examples/demo` | 6 passed (target/GATE2-check8.log; CI build job steps 5–6) | met |
| 9 | evidence | bench/RESULTS.md GATE section: ledger 2000×3, sync overhead, durable ACK, 50 MB, CI coverage + PIT | met (ACK p99 recorded, target missed: F-18) |

GATE run 1 (on 5505b0f, target/GATE-stack.log) found three defects, fixed before this run: the echo lost its network namespace
when the node was killed (FIX-105); step 0 showed CPU 0 and every other step null (FIX-106, F-21); with the partner down a message
parked at the wrong step after 154 s, because a checked ConnectException slipped past Retry and Parking (FIX-107).

## 5. Deviations from the architecture of the time (findings)

F-1 testkit cannot depend on core · F-2 the at-least-once durable save does not depend on the recording level · F-3 no
RequestScope wraps a delivery yet · F-4 runtime wires the shipped components through `Main` · F-5 a redeploy stops the old
consumer first · F-6 at-least-once bodies durable in Postgres until S3 lands · F-7 a parked body was node-local (closed by
D-062) · F-8 replay from n ≥ 2 read node-local bodies (closed by D-066) · F-9 XSLT run-time document reads are not confined ·
F-10 snapshot rows wait for their bodies · F-11 replay and retry run the flow version deployed now · F-12 S3 is a start
dependency of a deployed node · F-13 a closing trail drops whole timelines on writer overflow · F-14 durable ACK < 5 ms p99
not reachable with two commits on this VM · F-15 the sftp design (MINA SSHD, platform-thread wrapper, runtime-held lease) · F-16
the CLI runs flow tests on api + testkit · F-17 the scheduler MXBean does not see a VT blocked on a monitor · F-18 the
one-transaction ACK misses 5 ms at c=1 and costs throughput under load · F-19 sftp endpoint server/directory options · F-20
retention by age in keyset batches, not partitions · F-21 no CPU time on virtual threads. Each file is in `docs/archive/findings/`,
with a changelog row in `docs/archive/architecture-until-2026-11-17.md`.

## 6. Decisions marked "draft — review at hand-over" (docs/DECISIONS.md)

D-026 (api draft types: Ack, Endpoint…), D-040 (v1 file model), D-043 (Step SPI in api), D-049 (delivery modes; its open
points: reply-direction header allow-list, refusing `payload: metadata-only` for at-least-once at load time, Q-2 bytes),
D-055 – D-063 (worker lease and attempts, stop/cancel, admin API, durable bodies, deploy, package identity, persist semantics,
durable DLQ bodies, redeploy), D-066, D-067 (durable step bodies, admin write routes), D-070 (deployment record and archive
store), D-074 – D-076 (WorkQueue fencing, Postgres WorkQueue, sftp consumer), D-078 – D-084 (package folders, CLI, test runner,
`secret://` from env, one-transaction ACK, poller leases, ingress dedupe), D-085 (gate compose stack), D-086 – D-088 (demo package and sftp options, package dependencies, retention), D-089
(off-node package dependencies: `rail8 lock`, `--deps`), D-090 (the compose network-namespace holder), D-134 (Kafka under compose profile `kafka`, off by default), D-135 (scheduled retries, then park), D-136 (`/retry` keeps the id; its `/replay` copy left with D-156), the removal of the public constant `Envelope.REPLAY_OF` from `rail8-api` (FIX-265, 2026-11-17; F-55 - replay itself left by the owner's D-156, the api's loss of the constant is the draft), D-139 (the journal's peer link between nodes: keys, wire format, key rotation; F-35). D-077 (Temurin base images: the JDK is GPLv2 with the Classpath Exception, used as the runtime and not linked) is a
licence point for the owner. Also left for the owner: D-064/65B/67A/72/73 and the CONSIDER-1..3, 5..8 rows in docs/archive/agent/BACKLOG.md.

## 7. Open issues

- ISSUE-3 was answered 2026-10-30 (option 2): check 6 accepts wall time with `cpuNanos` null; FIX-108 adds JFR CPU sampling.
- **ISSUE-1**: Context7 quota. The javap fallback is in use; an API key would restore documentation lookups.
- ISSUE-2 was answered 2026-10-28 (options 1 + 2: the < 5 ms is a tuning target; the one-transaction ACK shipped as P2-08).

## 8. Next three tasks

P2-02b2 (`rail8 lock`, off-node validate/test of a package with dependencies, D-089) is done.

FIX-110 (off-node validate holds a package's dependencies for one validation, nothing deployed or started, any `--env`)
is done as well.

1. **FIX-108**: CPU per flow and step by JFR sampling (ISSUE-3 option 2).
2. **P2-02c**: undeploy, `DELETE /admin/packages/{name}`, with a 409 while another package pins it.
3. **P2-07b**: payload reads of a body removed by retention answer 409 (the row's replay half left with D-156).

Then FIX-109 (interrupt edges), P2-07c (`payloads`) and the open work (GitHub Issues).

## 9. Stretch (GATE.md) — not reached

P2-S1 (snapshots to the Kafka `monitoring` topic) and P2-S2 (a ClickHouse Kafka-engine table) are both pending. The ClickHouse
container is present under the `history` profile and nothing feeds it.
[2026-11-15: both stretch items are superseded. Kafka is an optional add-on (D-134); ClickHouse is to be fed by the node's mover with log records (CORE-10, OPS-2, pending; D-147).]

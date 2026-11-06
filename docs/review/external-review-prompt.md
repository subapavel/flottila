# Prompt: independent architecture review of Flottila (multi-node HA and Kubernetes)

You are a principal engineer in distributed systems and messaging middleware. You have shipped and run production integration
platforms (SAP PI/PO or CPI, MuleSoft, Camel, Kafka- or Postgres-backed queues) on bare metal and on Kubernetes. Your task is to
find **holes in the design and the code**: places where a message can be lost, duplicated beyond what is promised, stuck, or
delivered out of order. Also find places where the system does not survive a node, a database, a network or the platform failing.
Where you find a hole, **propose the improvement**. Be concrete, sceptical and evidence-based. Generic best-practice lists are worth
nothing here.

## 1. Access and method

- Repository: `https://github.com/subapavel/flottila`, branch **`claude/brave-hopper-5vwaju`**. Everything in it is in English.
- If you cannot open the repository or a file, say so and ask for the file. **Never guess what a file contains.**
- Treat documents as claims and code as fact. When a document and the code disagree, that itself is a finding.
- Cite evidence as `path:line` or `docs/ARCHITECTURE.md §n`. Mark each finding **VERIFIED** (you read the code path) or
  **SUSPECTED** (inferred from documents or a partial read).
- Read in this order:
  1. `CLAUDE.md`: the non-negotiable design rules.
  2. `docs/CORE-REWORK.md`: the current rework and the owner's latest decisions. This overrides older parts of ARCHITECTURE.md.
  3. `docs/ARCHITECTURE.md`: the changelog first, then §3 topology, §4 runtime core, §7 components/queues/persistence, §9
     configuration and security.
  4. `docs/DECISIONS.md`: at least D-082, D-100, D-101, D-102, D-105, D-106, D-108..D-113.
  5. `docs/findings/F-27.md` (the measured at-least-once hand-over) and `docs/findings/F-28.md` (node lease and self-fencing).
  6. `docs/GATE.md` §CORE-GATE (the targets).
  7. `bench/RESULTS.md`: the newest sections first.
  8. `agent/BACKLOG.md`: what is planned. CORE-5, P2-15b/c, P2-16, CORE-7, CORE-17 and FIX-133..135 matter most.
- Code to read for the critical paths:
  - `flottila-runtime/src/main/java/io/flottila/runtime/store/PgIntake.java` (group commit, durable save before the 202)
  - `.../runtime/journal/*` (the node-local body journal, segments, sweeper)
  - `.../runtime/queue/PgWorkQueue.java` (claim, TAKE, complete, hold)
  - `.../runtime/node/Node.java`, `NodeConfig.java`, `PgMembership.java` (lifecycle, membership)
  - `flottila-core/src/main/java/io/flottila/core/node/NodeLease.java` (fence)
  - `.../core/flow/FlowWorker.java`, `FlowBuilder.java` (worker runs, fence points, the at-least-once send)
  - `deploy/postgres/V*.sql` (schema)
  - `deploy/compose`, `deploy/quickstart` (the deployment shapes that exist today)

## 2. What the system is (short; verify it)

Flottila is an open-source (Apache 2.0) enterprise integration platform where **monitoring is the product**: every message, step
and payload is captured, searchable and replayable. Stack: Java 25, Vert.x 5.1, PostgreSQL 16, ClickHouse via pg_clickhouse,
Valkey, S3 as a cold tier only. Kafka is **optional**.

- **Threading:** one virtual thread per in-flight message, bounded per flow by a semaphore over requests and bytes. Verticles own
  sockets and timers only.
- **State:** PostgreSQL is the single source of truth: work queue, DLQ, idempotent ids, aggregator and resequencer state, ordered
  queues. **No message bodies in Postgres.** Bodies live in a **node-local, encrypted, append-only body journal**. Postgres rows
  reference it as node + segment + offset.
- **At-least-once path:**
  1. HTTP in.
  2. Journal force (fdatasync).
  3. Group-committed Postgres transaction: message row, work item, body reference.
  4. HTTP 202.
  5. The node's worker claims the item (`UPDATE … FOR UPDATE SKIP LOCKED`), reads the body from its own journal, sends to the
     partner, then completes the item (one DELETE).
- **Journal copies = 1 today.** A node's journaled items wait for that node to come back. Replication to a peer is planned
  (CORE-5). Until then a peer never claims another node's journaled items (`body_node` check in the claim).
- **Node lease (P2-15a, just merged):**
  - Lease row: each node has a row `nodes(node, epoch, lease_until)`. Lease L = 30 s, renewed every R = 5 s on a dedicated
    connection. A renew that is still unanswered at L/2 is abandoned and the connection reopened.
  - Fence: the node's fence is open until `s + L/2`, where `s` is the monotonic reading just before the last successful renew was
    sent. A closed fence stops claims, at-least-once sends, parks and settles.
  - Takeover: a peer may release an expired row only if its own fence stayed open for the last L. A released node's items are
    taken with `doubts + 1` (in-doubt is derived, nothing is written per send). The rerun keeps the same message id and counts as
    an attempt; past maxAttempts the item is parked.
  - Superseded node: it fails-stop (exits) and relies on systemd or Kubernetes to restart it.
  - Rejoin: a restarted node reruns its own items only after the old incarnation's `lease_until`.
- **Next (P2-15b, in design):**
  - The send is bounded by the fence through an api `Deadline` handed to producers.
  - Every http send carries `x-message-id` (the message id).
  - Split child ids become `<parent>.<n>`.
- **Measured** on one 4 vCPU VM, one node, client, node, partner and Postgres on the same box, recording off:
  - At 2 000 msg/s offered: no 503s, backlog flat, ACK p50 13 ms / p99 48 ms. This is one run; the host may have changed since the
    previous run.
  - The ceiling is about 1 800–2 000 msg/s and CPU-bound: Postgres about 1.3 cores, plus the bench JVM.
  - About 2 commits per message: claim and delete. P2-16 (the item written already leased to the intake node, no claim) and CORE-7
    (batched completion) are meant to remove most of that.
- **CORE-GATE targets** on that VM:
  - durable ACK p99 < 5 ms at c=1;
  - 5 000 msg/s open loop for 10 min, no 503, backlog growth < 1 %, ACK p99 < 50 ms;
  - sync overhead < 1 ms at c=1;
  - kill -9 ledger with no loss.

## 3. Constraints you must respect (or challenge explicitly)

These are owner decisions. Do not quietly design around them. If you think one of them is wrong, open a separate "Challenge"
item with the argument and the evidence.

- Postgres is the single source of truth. Kafka is optional.
- No bodies in Postgres. Bytes exist once; everything else is a reference.
- No Vert.x clustering. No Apache Camel. No native code in-process.
- **Kubernetes is an environment, never a requirement.** The same image runs on systemd, Compose and Kubernetes.
- Every node is identical; scaling is "run another one". Flows are grouped into pools that are deployed on every node of the pool.
- No message is refused for its size. Every other limit is finite.
- State whose loss loses messages goes to Postgres. State whose loss only changes tempo may live in memory or Valkey.
- Dependency licences must be Apache 2.0, MIT, BSD, MPL 2.0 or EPL. Nothing AGPL, GPL, BSL or SSPL.

## 4. Already known; do not re-report unless you show it is worse than stated

- F-28 assumptions: the monotonic clock stops during a VM suspend; a forward step of the database wall clock larger than L/2 voids
  the margin; the rejoin margin.
- FIX-133: a claim racing the chain end after a LEAVE.
- FIX-134: a graceful restart waits up to L because the node keeps its row on close.
- FIX-135: the fence is read before a claim's TAKE and before a park's DLQ write, so a fence can close in between.
- Single journal copy until CORE-5.
- The ACK latency target is not yet met. The planned fixes are CORE-17 (connection pool by role) and D-111 (overlap the
  statements with the journal force).
- A send started before the fence may outlive it until P2-15b.

## 5. Focus areas

For each area, either name what breaks (with a step-by-step scenario) or state briefly that you checked it and found it sound.

**A. Multi-node production HA (highest priority)**
1. **Node crash, restart and replacement.** What happens when a node dies and never comes back: disk lost, VM deleted, pod
   rescheduled without its volume? Today its journaled bodies exist only there. What does an operator see and do, and is anything
   silently lost or stuck forever? Is the CORE-5 plan (replication to a peer, ACK per Q-5) sound? Propose the replication protocol
   you would use: synchronous peer copy before the 202, quorum, or object store, with its latency cost.
2. **Fencing and split brain.** Check the timing argument in F-28 against the code. Look for interleavings where two nodes act on
   the same item, a node keeps sending after it should have stopped, or a released node renews back. Is a lease without a fencing
   token at the partner enough for at-least-once? Where would a fencing token (epoch, attempts) be needed: in Postgres writes, in
   the partner call?
3. **Postgres failover.** Streaming replica plus failover (Patroni, CloudNativePG, RDS or Aurora). What happens to:
   - group-committed intake transactions that were acknowledged with 202 but not yet replicated (asynchronous replication);
   - in-flight claims and leases (`lease_until` computed by `now()` on the old primary, then compared on the new one);
   - the dedicated lease connection and the pools;
   - `pg_xact_status` in-doubt resolution (D-082/D-109) after the xid space moves to a new primary?

   Is synchronous replication required for the promise, and what does it cost?
4. **Network partitions:** node ↔ Postgres, node ↔ partner, node ↔ node (once CORE-5 exists). Who stops, who takes over, can
   anything be lost or doubled past what the D-112 in-doubt rule allows?
5. **Ordering (EOIO / ordered queues, resequencer, aggregator)** across nodes and across a takeover.
6. **Duplicates.** At-least-once delivery relies on the partner deduping by `x-message-id`. Is the id stable across every path that
   can resend: rerun, replay, retry, split, aggregate? Which shipped components cannot carry it?
7. **Scale-out limits.** Postgres as the queue for N nodes: claim contention, `SKIP LOCKED` behaviour under depth, vacuum and
   bloat of `work`, WAL volume, connection count per node × N. At what N or what msg/s does one Postgres become the wall, and what
   is the plan then: partitioning, a per-pool database, Kafka as an option?

**B. Kubernetes**
1. **Workload type.** The journal is node-local and the node id must be stable for rejoin. Deployment or StatefulSet? PVC per
   pod, storage class, zone affinity, what happens on reschedule to another zone, `ReadWriteOnce` semantics, volume attach delays
   versus L = 30 s.
2. **Lifecycle.**
   - SIGTERM drain against `terminationGracePeriodSeconds`.
   - `preStop`.
   - Readiness and liveness probes: what each should check, and a probe that kills a node only because Postgres is slow.
   - Fail-stop exit codes against `restartPolicy` and CrashLoopBackOff.
   - Rolling updates and `maxUnavailable`/`maxSurge` with journal copies = 1 (a rolling update makes each node's items wait).
   - PodDisruptionBudget, node drain, cluster autoscaler scale-in. Does scale-in strand bodies?
3. **Timing under Kubernetes.**
   - CPU limits and CFS throttling (stalls of hundreds of milliseconds) against renew R and fence L/2.
   - Node pressure eviction.
   - Live migration or VM pause of the worker node.
   - Clock sync on nodes.
4. **Networking.**
   - Service and load balancer in front of the HTTP ingress.
   - Session affinity, which should not be needed.
   - Node-to-node traffic for CORE-5 (headless service, mTLS).
   - NetworkPolicy.
5. **Configuration and secrets.** The journal encryption key (D-106: key file or env, opt-in generate) as a Kubernetes Secret,
   rotation, and what happens to a journal when the key is lost.
6. **Operators and managed services.** CloudNativePG, managed Postgres, ClickHouse operator, Valkey. Which of them change the
   failure behaviour above?

**C. Everything else you notice** in correctness, durability, backpressure, resource bounds, security (node-to-node,
multi-tenant isolation) and observability cost ("monitoring is the product" at 5 000 msg/s). Stay concrete.

## 6. Output format

1. **Executive summary.** At most 10 lines: the three most dangerous holes and the three highest-value improvements.
2. **Findings table**, sorted by severity, with these columns:
   - `ID`
   - `Area` (A1…C)
   - `Severity`:
     - **Critical**: an acknowledged message can be lost or a duplicate escapes dedupe;
     - **High**: stuck messages or outage without operator recourse;
     - **Medium**: degraded HA or performance;
     - **Low**: hygiene.
   - `Likelihood`
   - `VERIFIED/SUSPECTED`
   - `Evidence` (`path:line`, §)
3. **One section per finding**, with:
   - a step-by-step failure scenario (the exact interleaving or event sequence);
   - the impact;
   - the proposed fix: smallest change first, the ideal one second;
   - the cost (latency, throughput, complexity);
   - whether it is reversible;
   - **the test that would prove it**: the name and shape of a failing test, a jqwik property, a Testcontainers IT, a chaos or
     Kubernetes scenario.
4. **Improvement proposals** that are not holes (architecture, performance, operability), ranked by value against cost, each with
   the measurement that would confirm it.
5. **Kubernetes reference deployment:** the manifests outline you recommend (workload kind, volumes, probes, PDB, grace period,
   resources, anti-affinity, topology spread), with a one-line reason for each choice. Also say what must change in the code for it
   to be safe.
6. **Challenges** to owner constraints, if any, with your argument.
7. **What is sound.** Design decisions you checked and would keep, in one line each, so they are not changed by mistake.
8. **Open questions** you could not settle from the repository.

Rules for the answer:
- Prefer depth on the top 15 findings over breadth.
- No finding without a concrete scenario.
- No proposal without its cost.
- Do not restate the documents back.
- If something in section 2 of this prompt contradicts the repository, the repository wins: say so.

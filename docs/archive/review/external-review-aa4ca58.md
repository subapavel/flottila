# Flottila — independent HA and Kubernetes review

Review date: 2026-11-06. Snapshot label: `aa4ca58`, from the supplied ZIP, not independently verified against a Git remote. ZIP SHA-256: `8e0cceee9be27a8177f79044588dc33c1fffc0ac084afc567cb934be8a32d88c`.

## 1. Executive summary

1. The most dangerous durability gap is PostgreSQL promotion: the startup check accepts asynchronous replication, and duplicate-only ACKs have a separate suspected hole even under strict synchronous replication (F3.1–F3.2).
2. The proposed message-ID dedupe contract does not cover manual recovery IDs or multiple legitimate sends within one message (F4.1–F4.2).
3. Normal journal retention can block the next restart; accepting a real loss can leave an invisible ordered-queue barrier (F7.1–F7.2).
4. Highest-value improvement: make ACK durability a tested end-to-end contract, including duplicate-only groups, canceled replication waits and database promotion.
5. Next: define stable operation identity and durable retry ownership before relying on P2-15b's header.
6. Next: fix recovery-pin consistency and implement bounded worker drain plus truthful readiness before rolling deployments.
7. CORE-5 remains necessary for node/disk-loss HA; a StatefulSet does not replicate a journal.
8. This is a source/document review: 13 findings, 9 VERIFIED code paths and 4 SUSPECTED scenarios/design gaps; no build, unit suite, chaos run or production benchmark was executed.

## 2. Findings, sorted by severity

Severity is the prompt's impact scale, not a claim that every scenario is reproduced. Likelihood is conditional on the event described. Each detailed finding below also carries the workspace severity and a proving spike.

| ID | Area | Severity | Likelihood | VERIFIED/SUSPECTED | Finding and evidence |
|---|---|---|---|---|---|
| F3.1 | A3 | Critical | High with async promotion | VERIFIED | ACKed work absent on promoted replica; `runtime/node/Node.java:348`, `runtime/store/PgIntake.java:305` |
| F3.2 | A3, A6 | Critical | Canceled SyncRep wait + retry + promotion | SUSPECTED | Duplicate-only 202 lacks replication barrier; `runtime/store/PgIntake.java:304`, `:318`, `:393` |
| F4.1 | A6 | Critical | Plausible on failed DLQ cleanup | VERIFIED | Manual retry changes dedupe identity; `core/flow/Replay.java:150`, `:158`; `runtime/admin/AdminServer.java:587` |
| F4.2 | A6 | Critical | Depends on receiver dedupe scope | SUSPECTED | Several sends share one proposed key; `agent/packets/P2-15b.md:24`; `components/http/HttpComponent.java:373` |
| F7.5 | A1, B1 | Critical | Low, fresh directory + power failure | SUSPECTED | Missing parent-directory durability barrier; `runtime/journal/Journal.java:378`; `runtime/journal/JournalStart.java:212` |
| F7.1 | A1, B2 | High | High after expiry and restart | VERIFIED | Normal sweep causes startup refusal; `runtime/journal/PgSegmentRows.java:39`, `:53`; `runtime/journal/Journal.java:392` |
| F7.2 | A1, A5 | High | High after accepted ordered-body loss | VERIFIED | Invisible lost work blocks its key; `runtime/journal/PgSegmentRows.java:70`; `runtime/queue/PgWorkQueue.java:73`, `:81`, `:121` |
| F4.3 | A2, B2 | High | In-flight work during shutdown | VERIFIED | Worker drain future discarded; `runtime/deploy/Deployments.java:358`, `:603`; `runtime/node/Node.java:407` |
| F7.4 | A5 | High | Deterministic if used as EOIO | VERIFIED | Parking releases the ordered key; `core/flow/FlowWorker.java:373`; ARCHITECTURE §4 |
| F4.4 | A4, B2 | Medium | Lease-specific outage or drain | VERIFIED | Fenced/draining node can be healthy; `runtime/node/Node.java:264`; `runtime/admin/AdminServer.java:485` |
| F7.3 | A3 | Medium | Divergent xid history | SUSPECTED | One xid status error blocks all sweeping; `runtime/store/PgIntake.java:215`; `runtime/journal/SegmentSweeper.java:116` |
| F9.1 | B6, C | Medium | TLS-only Postgres installation | VERIFIED | Runtime database TLS unconfigurable; `runtime/node/Node.java:308`; `runtime/node/NodeConfig.java:216` |
| F9.2 | A1, B2 | Medium | Two-node manual undeploy, dev/CI | VERIFIED | Remote accepts survive deleted deployment record; `runtime/deploy/Deployments.java:482`, `:502`, `:630` |

Table path shorthand: `runtime/…` = `flottila-runtime/src/main/java/io/flottila/runtime/…`; `core/…` = `flottila-core/src/main/java/io/flottila/core/…`; `components/http/…` = `flottila-components/http/src/main/java/io/flottila/components/http/…`. All are under `snapshots/aa4ca58/`. The detailed findings use full paths.

## 3. Scope, evidence and corrections to the brief

I read CLAUDE's design rules, CORE-REWORK, the architecture changelog and the requested sections individually, the named decisions, F-27/F-28, CORE-GATE, newest benchmark entries, and the relevant backlog/code. The source documents were not edited. The extracted archive was used as reference; no attached autonomous implementation/commit instructions were executed.

The snapshot's architecture is v3.4, not this workspace's original v3.2. Its open-decisions list is §14, not §22. D-113 is not a committed entry in `docs/DECISIONS.md`; its draft is in `agent/packets/P2-15b.md:28`. The benchmark uses PostgreSQL 16, while Compose pins PostgreSQL 17.11. ClickHouse ingest, revised recording, Kafka removal and journal replication remain pending. The CORE-GATE body setting is metadata/recording **off in the current harness** (`bench/RESULTS.md`, harness notes), so it does not establish the future “metadata of every step always” cost.

The named code paths and tests were inspected, but tests were not run. VERIFIED means a traced code path, as requested; it does not mean a reproduced failure. Filesystem power-loss behavior, the synchronous-promotion experiment, xid divergence and partner-specific dedupe behavior require the proposed spikes.

### Focus-area disposition

| Requested area | Result |
|---|---|
| A1 crash/restart/replacement | Single-copy limitation is known. New recovery/operability defects are F7.1, F7.2 and conditional F7.5. A different node ID with a fresh volume does not rescue the old node's queue. |
| A2 fencing/split brain | Dedicated connection, epoch-guarded renewal and expiry recheck are sound under F-28's stated assumptions. F4.3 adds planned-shutdown uncertainty. A local request reset cannot undo an already accepted partner operation. |
| A3 Postgres failover | F3.1, F3.2 and F7.3. Preserve membership, work, completion and dedupe history together; retrying connections is not a durability protocol. |
| A4 partitions | Node↔PG closes the local fence; a pool/lease asymmetry exposes F4.4. Partner response loss remains in doubt. CORE-5 must refuse/delay ACK when its configured copy guarantee cannot be met; no silent downgrade. |
| A5 ordering | Existing visible queue-head selection and attempt-token settlement are sound. F7.4 prevents an EOIO claim today. Source-sequence ordering, aggregation and resequencing are not implemented end to end and cannot be certified from this archive. |
| A6 duplicates | Worker reruns preserve message ID; step retries reuse their envelope. Current HTTP producer does not inject it, split IDs are fresh, and their fixes are already pending. Manual retry/new replay identity and multi-send scope need F4.1/F4.2. SFTP is consumer-only; mock is not a production wire contract. No aggregator output-ID implementation was found. |
| A7 scale-out | No measured maximum node count is justified. The one-node preview is not a replicated, multi-node or recording-on capacity result. See the measurement plan below. |
| B1 workload/storage | StatefulSet, stable identity and retained PVCs; volume reattachment is not a 30-second guarantee and zonal volumes constrain rescheduling. |
| B2 lifecycle | F4.3/F4.4; a larger grace period alone cannot fix discarded drain futures. PDB is not a body evacuation mechanism. |
| B3 timing | CPU stalls below the fence margin reduce availability; long stalls self-fence. Suspend/clock-step hazards are already in F-28. Test throttling, eviction and migration against the actual infrastructure. |
| B4 networking | Ingress needs no session affinity for ordinary HTTP. Until remote reads exist, admin body/replay requests may need owner-aware routing. Peer replication requires authenticated identities and bounded transfer/backpressure. |
| B5 secrets | External persistent master key, separate backup and tested restoration. Kubernetes Secret replacement alone is not a rewrap protocol. |
| B6 managed services | Their actual durability/promotion policies matter; their product names do not prove the ACK guarantee. F9.1 currently blocks direct TLS-only PG use. |
| C other | No multi-tenant isolation claim is justified: Node uses tenant `default`. Size streaming is already deferred by D-110. Pending recording work needs a separate cost gate. |

## 4. Improvements ranked by value against cost

| Rank | Proposal | Cost/tradeoff | Measurement that decides |
|---|---|---|---|
| 1 | Add a durability ledger spanning intake, duplicate success, partner receipt, settlement and promotion. Keep proposed cases independent of elapsed sleeps. | Primary/standby and fault-control test fixtures; longer integration runs. | Every 202 ID is recoverable or explicitly completed after each injected fault; no unexplained IDs, not just matching aggregate counts. |
| 2 | Complete P2-16 local hand-off and CORE-7 batched completion with per-row tokens intact. | Larger uncertain-completion batches after a crash; more batch/reconciliation logic. | Commits/message and WAL bytes/message fall while partner ledger, ordering and stale-holder tests remain unchanged. |
| 3 | Complete CORE-17 role pools and FIX-93 finite wait queues. Reserve membership independently as today. | More total connections if merely added; divide a fixed budget instead. | Intake p99 and bounded queued requests under worker, retention and admin load; no connection-growth surprise at N nodes. |
| 4 | Expose oldest work age by owner/key, lost/held counts, fence state, last renewal age, open uncertain saves, reclaim lag and journal free headroom. | Bounded aggregate queries/metrics; avoid per-message labels. | A dead owner, lost body or poisoned sweep is distinguishable in one operator view, and an alert fires before capacity refusal. |
| 5 | Run a production-shaped capacity matrix after the core correctness fixes. | Dedicated machines and repeated sustained runs. | N=1/2/4/8, fixed total offered rate then increasing rate; 132 B/10 KiB/large bodies; hot and dispersed keys; recording off versus every-step metadata and kept inputs; synchronous PG and two body copies. |
| 6 | Apply measured per-table vacuum/fillfactor/index changes before adding queue partitions. FIX-131 already tracks the keyed index. | More maintenance CPU and index/WAL bytes; fillfactor reserves disk. | Work dead/live tuples, vacuum lag, buffers per claim, p99 and WAL/commit. F-27's old JIT/depth collapse was already addressed; do not present it as current evidence. |

**Connection ceiling:** current steady state is approximately `N × (poolSize + 1)` PostgreSQL connections, plus migration/admin/operational reserve; defaults give about `17N`. A future dedicated LISTEN connection adds another per node. For an illustrative budget of 100 with 20 reserved, 16+1 allows only four fully allocated nodes. This is a connection-budget example, not a measured throughput limit (`Node.java:196`, `:227`; `NodeConfig.java:409`).

**Queue throughput ceiling:** the newest r2000 run reached about 2,000 completions/s with ACK p99 47.98 ms on one shared 4-vCPU host, recording off (`bench/RESULTS.md:3`). Neither a maximum N nor a 5,000/s production guarantee follows. Measure statement/pool contention, checkpoint/WAL rates, keyed fairness and vacuum before choosing partitioning. A database per independent pool is a later isolation option, with migration/routing/operations cost. Kafka remains an optional high-volume hand-over with its own durability and dedupe work; it cannot recover a missing journal body.

**Recording capacity:** at 5,000 msg/s, `k` recorded steps produce `432 million × k` metadata rows/day before aggregation/retention. At 10 KiB kept input per message, raw body ingress is about 48.8 MiB/s; a nominal 16 GiB journal holds only about 5.6 minutes if nothing can drain, before reserves and format overhead. These are arithmetic budgets, not measured compression figures. Size disks from arrival bytes × outage duration × copies plus headroom. CORE-10 must prove idempotent ingest and durable archive confirmation before local bytes are released.

## 5. Kubernetes reference deployment outline

This is a reviewable manifest outline, **not a ready-to-apply production manifest**. The image lacks the proposed `/readyz`, `/livez` and drain semantics today. No Kubernetes objects were created.

| Object/field | Recommended outline | Reason and cost |
|---|---|---|
| Workload | `apps/v1 StatefulSet`; stable ordinal; set FLOTTILA_NODE_ID from pod name; one journal per ordinal. Start with explicit replica count, not automatic scale-in. | Preserves the node/journal pairing. Multiple current nodes give ingress capacity, not cross-node recovery of a lost single-copy journal. |
| Storage | `volumeClaimTemplates` mounted at `/var/lib/flottila`; durable CSI block volume; `ReadWriteOncePod` where supported; retain PVCs on scale-down/deletion and use a retaining PV policy. | Retention preserves bytes; it does not make another ordinal consume them. RWO alone permits several pods on one node; RWOP tightens attachment ownership. [StatefulSet storage](https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/), [RWOP](https://kubernetes.io/docs/tasks/administer-cluster/change-pv-access-mode-readwriteoncepod/). |
| Storage placement | Topology-aware provisioning; reserve enough capacity in the volume's zone. Document the storage class's detach/attach and zone-loss behavior. | An ordinary zonal volume cannot simply attach in another zone. Cross-zone body availability requires a suitable volume service or CORE-5 replicas. |
| Startup probe | New local startup endpoint becomes ready after journal recovery, key loading, membership and package restoration; budget from recovery measurements. | Avoid killing a valid slow recovery. Code change required. |
| Readiness probe | New bounded `/readyz`: lifecycle RUNNING, open fence, usable journal and required deployments; immediately false on drain. | Removes a node unable to process its newly accepted work; fixes F4.4. |
| Liveness probe | New `/livez` checks local progress/deadlock/fatal state; no direct PG/ClickHouse dependency. | A shared backend outage must not restart every JVM. Startup probes defer other probes until initialization completes. [Probe semantics](https://kubernetes.io/docs/concepts/workloads/pods/probes/). |
| Termination | Keep Java as PID 1, as in the image. Implement bounded worker drain first. Set grace to endpoint propagation + worker drain + flush + safety margin; use 90 s as a test starting value, not a proven bound. | Fixes F4.3; total shutdown can exceed FLOTTILA_STOP_DEADLINE alone. |
| preStop | After a drain hook exists, trigger readiness-off/intake-stop and wait only a bounded propagation interval. SIGTERM must also work without the hook. | A hook is not a second grace period: it consumes the same termination budget. [Termination lifecycle](https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/). |
| Restart policy | `Always`; exit 3 remains fail-stop. Alert on repeated supersession, key errors and journal recovery refusal. | Restarting a permanent key/identity error indefinitely does not restore messages. |
| Rolling update | One ordinal at a time with OrderedReady/rolling update; verify revision-specific drain/recovery. Do not apply Deployment maxSurge assumptions. | A single-copy ordinal's backlog waits for that ordinal; stable identity and readiness matter more than adding a temporary pod. |
| Disruption budget | For at least two nodes, PDB `maxUnavailable: 1`; for one node, choose consciously whether to block voluntary eviction. | Limits cooperative voluntary disruptions, not node failure, all rollout paths or permanent scale-in. [Disruptions](https://kubernetes.io/docs/concepts/workloads/pods/disruptions/). |
| Placement | Anti-affinity across worker hosts; topology spread across zones where storage permits. Place CORE-5 copy holders in distinct failure domains. | More capacity reservation/possible unschedulability, but avoids counting two correlated copies as HA. |
| Resources | Reserve CPU from measured load; avoid a tight CPU limit on renewal-sensitive nodes unless throttling tests pass. Finite memory limit with heap, direct buffers, native memory and page-cache budget; persistent-volume capacity above journal bound plus reserves. | Lower consolidation density, fewer avoidable fence closures/OOMs. No universal CPU or heap value follows from the shared-host bench. |
| Network | Ingress Service/LB per flow port, `sessionAffinity: None`; separate restricted admin Service; headless service for future peer discovery. Default-deny NetworkPolicy with explicit DNS, PG, archive, partner and peer paths. | Preserves ordinary ingress balancing while protecting admin/replication surfaces. NetworkPolicy does not encrypt traffic. |
| Secrets | Mount a separately backed-up master key read-only through FLOTTILA_JOURNAL_KEY_FILE; do not use opt-in generated keys for a replicated production deployment. Protect admin/PG/peer credentials separately. | Losing the master key makes otherwise intact journal bytes unreadable. Kubernetes Secret storage needs its own access and at-rest protection. |
| Scale-down | Require owner-specific work/DLQ/kept-ref inspection and durable evacuation before removing an ordinal; keep its PVC until verified. | A PDB or retained PVC alone does not complete messages for an absent worker. Disable automatic scale-in until that protocol exists. |

**Code prerequisites:** F7.1/F7.2 recovery and loss handling; F4.3/F4.4 drain/probes; F9.1 TLS; a durable copy/retirement protocol for disk-loss HA and scale-in; stable operation identity; tested database failover. Schema migrations V11/V12 explicitly require a stop-all transition from incompatible older nodes (`deploy/postgres/V12__journal.sql:1`); rolling deployment must not assume every schema upgrade supports mixed versions.

**Key rotation:** D-106 proposes rewrapping `journal_keys`, not rewriting every payload. Define a coordinated transition allowing old/new wrapping keys during rollout, retain the old key until every node and backup restore path can read the rewrapped keys, and rehearse rollback. Replacing the mounted Secret first is not sufficient. Cost is key-version metadata, orchestration and backup lifecycle work.

**Managed services:** Patroni defaults to asynchronous replication; its synchronous and strict modes have distinct availability and durability behavior, including the cancellation caveat behind F3.2. CloudNativePG's durability and failover policies must be selected explicitly, including what happens when replicas disappear. [Patroni modes](https://patroni.readthedocs.io/en/latest/replication_modes.html), [CloudNativePG failover](https://cloudnative-pg.io/docs/current/failover/). RDS Multi-AZ DB **instances** use synchronous replication; Aurora has a different shared distributed-storage model. Validate the precise product/topology rather than treating Aurora, RDS readers and streaming standbys as interchangeable. [RDS instance HA](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Concepts.MultiAZSingleStandby.html), [Aurora HA](https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/Concepts.AuroraHighAvailability.html). A ClickHouse operator does not decide when Flottila may drop its last journal copy; that remains the mover protocol. Valkey failover must affect tempo/poller ownership only; it cannot become the authoritative body/work store.

## 6. Explicit challenges to accepted decisions

### Q-5: peer receipt is a weaker guarantee than two durable copies

CORE-REWORK §5 chooses local force plus peer receipt, not peer force. Keep that choice visible. If A's disk is permanently lost while B's copy is only volatile, a subsequent B host reset before flush loses an acknowledged body. Those failures need not be simultaneous. If receipt means application memory rather than completed file write, a process kill may be enough. The brief's explanation “before either flushes” is inaccurate because the local copy already flushed.

**Recommendation:** offer a named receipt-only profile with a precise volatile window, and a production HA profile requiring two durable copies in separate failure domains. This explicitly challenges Q-5; it does not silently replace it.

**Proposed CORE-5 protocol:** authenticate peer identities; frame byte-identical records by owner, journal UUID, segment, offset and length plus integrity data. A peer validates/idempotently appends contiguous bytes, forces the required file/directory state and returns a durable-through offset. The owner overlaps peer transfer/force with local force, then commits PG references and the eligible holder set only after both promises succeed. Handle holes, retransmission, stale journal incarnations and interrupted large records explicitly. Publish a replacement copy only after catch-up is durable; retain an existing copy while replacement or reference transactions are unresolved. Fence placement/retirement changes in PG and coordinate sweeping on every holder. The proposed protocol is not implemented in this snapshot.

**Cost:** about one peer RTT plus peer batch flush on the peer leg; the body phase is the slower of that leg and local force when overlapped, followed by the PG commit. Two full body copies, replication bandwidth, repair bandwidth and protocol state. A 2-of-3 body-storage policy can improve availability at additional storage/placement complexity, but quorum intersection alone does not find the holder: PG must retain an authoritative eligible set. S3 hot-path writes would change the owner's cold-tier-only decision and add object request latency/cost; I do not recommend introducing that change without a separate decision.

**Proving test:** accepted-ID ledger under A disk deletion followed by B host reset before flush; distinguish process kill, host reset and volume loss. Also test peer partitions, false receipts, duplicate/out-of-order frames, corruption, re-replication interrupted by a second loss, and ACKs racing placement changes. Cost and durability must be measured with synchronous PG as well as body replication.

### D-057: automatic crash recovery on a changed deployment can escape the old partner's dedupe

D-057 explicitly permits items admitted under v1 to run under v2; this is not an accidental code mismatch. A v1 send to X can succeed without settling, then v2 can send the same ID to Y after restart. X's dedupe protects nothing at Y. `FlowWorker.java:351` rebuilds ingress and uses the current pipeline; the queue selects by flow, not version.

**Recommendation:** pin automatic recovery to a deployment/operation generation, or require explicit disposition before changing targets with unsettled work. Keep intentional manual replay as a distinct operation. Cost: retaining/building old generations and more complicated draining. Test a successful-but-unsettled v1 send to X followed by a v2 target change; automatic recovery must not silently invoke Y.

### D-066 and D-109: recovery identity and no-write duplicates need exceptions

F4.1 challenges retry-as-new-message for uncertain recovery; F3.2 challenges duplicate-only success without a durability barrier. Both have specific failure scenarios, costs and tests in the detailed findings. Neither requires abandoning Postgres, adding Kafka to the core or placing bodies in the database.

### D-101/P2-15b: a local send deadline cannot revoke a remote side effect

Resetting an HTTP request after its fence closes can end the sender's wait; the partner may already have accepted and continue processing it. A later attempt can overlap the remote effect even without a crash. Keep the deadline and self-fence, but state the guarantee as bounded local initiation/wait plus cooperative receiver idempotency. If receivers need strict ordering or reject stale ownership, they must atomically enforce the relevant operation identity and fencing/version token themselves. Attempts tokens are useful for PG settlement; adding an attempt number to the dedupe key would make every retry distinct. Cost: a receiver contract and durable dedupe state. Test a partner that commits after the client reset and before/after the rerun.

## 7. What is sound

- Local journal force precedes intake commit; ingress metadata and work share a transaction.
- Duplicate-key conflict handling is scoped by flow and grouped neighbors are isolated; retain this behavior while fixing the durability certificate.
- Same-body-node claim restriction is correct until a readable replicated copy exists.
- Work attempts fence stale completion, renewal and hold within an authoritative database history.
- Dedicated membership renewal avoids ordinary pool acquisition starvation; epoch and expiry checks prevent stale renewal/release under the stated assumptions.
- Segment sweep uses lock plus later-snapshot recheck; keep that protocol when adding copies.
- Journal identity, locking and explicit digest-bound ACCEPT_LOSS are preferable to silently inventing an empty replacement.
- The benchmark distinguishes previews from CORE-GATE and records host uncertainty; keep that evidence standard.
- Kubernetes remains optional; the needed identity, durability and lifecycle contracts also apply to systemd and Compose.

## 8. Open questions

1. Which production failure envelope is promised: process failure, host failure, permanent disk loss, zone loss, and how many successive failures before repair?
2. Which PG manager/storage topology and promotion/degraded-mode policy must the platform support?
3. Does ordered delivery mean source sequence, intake commit order or work sequence, and what is the explicit skip policy after a lost/parked item?
4. What is the receiver's durable dedupe namespace and retention period, including multi-step sends, manual recovery, splits and version changes?
5. Which filesystem/storage classes and power-loss guarantees are supported for the journal?
6. How will pool-wide configuration convergence, deployment retirement and tenant isolation be implemented before those capabilities are advertised?
7. What maximum outage duration must recorded payloads survive locally, and how is the master-key/PG/journal backup set restored consistently?

## Detailed findings

The following section files are the canonical findings, saved as the review progressed. They contain each scenario, impact, smallest and ideal fixes, cost, reversibility and proving spike. Their content is appended below for a standalone report.

- [§3 topology](C:/Users/pavol/flottila/review/03-topology-aa4ca58.md)
- [§4 runtime](C:/Users/pavol/flottila/review/04-runtime-aa4ca58.md)
- [§7 persistence](C:/Users/pavol/flottila/review/07-persistence-aa4ca58.md)
- [§9 configuration](C:/Users/pavol/flottila/review/09-configuration-aa4ca58.md)


## Appendix — detailed findings

## Review of §3 — System topology

Session date: 2026-11-06  
Snapshot: `flottila-aa4ca58.zip`; architecture v3.4, overridden by CORE-REWORK.  
Evidence paths are relative to `snapshots/aa4ca58/`. VERIFIED means the code path was read, not that a chaos test ran.

### Findings

#### F3.1 — The startup durability check accepts a database that can lose acknowledged work on promotion

- **Assumption in the document:** ARCHITECTURE §3 specifies streaming replication plus failover; the durable ACK promises survival of a crash. Node's explanation says it refuses `local` because that setting does not wait for a standby.
- **How it fails:** (1) Start with `synchronous_commit=on` and asynchronous replication, with no synchronous standby configured. The check passes. (2) Force the local journal and commit intake; return 202. (3) Lose the primary before its WAL reaches the standby. (4) Promote that standby. The acknowledged `messages`, `work`, and dedupe rows are absent. The remaining journal bytes do not reconstruct the missing message metadata or queue row. Conversely, losing a completed DELETE can resurrect work that already reached the partner. This is a database durability gap, independent of the acknowledged single-journal-copy limitation.
- **Severity:** blocker for a zero-loss failover promise; **Critical** in the requested scale. **Likelihood:** configuration-dependent, high with an ordinary asynchronous standby. **Status:** VERIFIED.
- **Evidence:** `flottila-runtime/src/main/java/io/flottila/runtime/node/Node.java:124`, `:348` checks only `SHOW synchronous_commit`; `flottila-runtime/src/main/java/io/flottila/runtime/store/PgIntake.java:305`, `:400` answer after the database commit. PostgreSQL explains that streaming replication is asynchronous by default and promotion can lose committed transactions: [PostgreSQL 16 replication](https://www.postgresql.org/docs/16/warm-standby.html#SYNCHRONOUS-REPLICATION). `remote_write`, also accepted here, does not protect against loss of the standby OS cache: [commit settings](https://www.postgresql.org/docs/16/runtime-config-wal.html#GUC-SYNCHRONOUS-COMMIT).
- **Spike:** `acknowledgedLedgerSurvivesPrimaryPromotion`: pause standby receipt, accept a uniquely marked batch, remove the primary, promote, and compare accepted IDs with surviving work/completions. Repeat with synchronous replication; commits must wait or fail while no eligible durable replica exists. Include lost completion and dedupe rows, not only intake. NOT_RUN.
- **If confirmed, the decision changes to:** Define separate local-durability and zero-loss-HA profiles, and make the latter require a failover policy that preserves the committed WAL prefix.
- **Proposed fix:** Smallest: document and expose the effective replication guarantee; validate synchronous-standby configuration in the HA profile and revalidate after connection replacement. Ideal: test the supported manager's promotion, old-primary fencing, synchronous-commit and degraded-mode policies together. A startup setting check alone cannot enforce the manager's future choices. Require durable standby flush, not `remote_write`, for host-failure durability.
- **Cost:** A synchronous commit adds replica RTT and standby flush/tail latency to intake groups, claims and completion transactions. Strict mode trades write availability for durability when no synchronous replica is available. Measure the actual network/storage placement; the single-box figures do not include this cost.
- **Reversible:** Configuration is reversible, but weakening the durability contract requires an explicit operator choice.
- **Asked Pavel:** No SAP fact required.

#### F3.2 — A duplicate-only intake can acknowledge an unreplicated commit even in strict synchronous mode

- **Assumption in the document:** D-084/D-109 answer an existing dedupe key with its first message ID; that answer inherits the original save's durability.
- **How it fails:** (1) Stop the synchronous standby receiving WAL. (2) Original keyed intake commits locally and waits in PostgreSQL's SyncRep wait. (3) Terminate that backend during the wait: its local commit survives, its locks are eventually released, and its success reply is suppressed. (4) Retry the same key on the surviving primary, alone in a group. The existing row is now visible. (5) No member writes application rows, so PgIntake rolls back its duplicate-only transaction and answers the original ID with 202. (6) Lose the primary and promote the designated standby before catch-up. That acknowledged ID has neither dedupe nor work rows there. The duplicate must arrive after cancellation/termination, not while the original still holds its locks.
- **Severity:** blocker for the HA durable-ACK promise; **Critical**. **Likelihood:** conditional on interrupted synchronous replication followed by retry and failover. **Status:** SUSPECTED integration behavior; both code paths verified, no primary/standby experiment executed.
- **Evidence:** `flottila-runtime/src/main/java/io/flottila/runtime/store/PgIntake.java:304` rolls back a no-write group; `:318` detects it; `:393` still returns the first ID. PostgreSQL implements the retained local commit and canceled replication wait in [PostgreSQL 16 syncrep.c](https://github.com/postgres/postgres/blob/REL_16_STABLE/src/backend/replication/syncrep.c); [xact.c](https://github.com/postgres/postgres/blob/REL_16_STABLE/src/backend/access/transam/xact.c) holds locks during the original wait and requires WAL-producing commit work for its synchronization wait. [Patroni's strict-mode caveat](https://patroni.readthedocs.io/en/latest/replication_modes.html#synchronous-mode) explicitly describes visibility of locally committed but unreplicated transactions after backend cancellation.
- **Spike:** `duplicateAckRequiresReplicationAfterCanceledOriginalCommit`: real primary/standby, strict synchronous settings; pause receipt, await original backend's SyncRep wait, terminate it, verify local visibility, submit the duplicate alone, then destroy primary and promote. Current behavior should demonstrate 202 without surviving rows; fixed behavior must wait or fail until the duplicate's durability barrier succeeds. NOT_RUN.
- **If confirmed, the decision changes to:** A duplicate success must certify a replicated WAL prefix containing the original save; row visibility alone is not that certificate.
- **Proposed fix:** Smallest: a duplicate-only group performs a real WAL-logged barrier write and awaits its successful synchronous commit before answering. Merely replacing rollback with a read-only commit is insufficient. Ideal: an explicit durable intake receipt protocol with tested cancellation, lost-reply and failover semantics; also test how the client handles PostgreSQL's WARNING when a replication wait is canceled by query cancellation.
- **Cost:** One synchronous write/commit per duplicate-only group, additional WAL and possible dedupe-row contention. Groups already committing new messages can cover the earlier WAL prefix through that commit. This is a deliberate exception to D-109's duplicate-writes-nothing rule.
- **Reversible:** Implementation is reversible; the durability promise should not be weakened silently.
- **Asked Pavel:** No SAP fact required; this challenges the no-write duplicate optimization explicitly.

### Not a problem (checked and fine)

- Intake forces the journal before building the committing transaction and answers only after commit; this is a sound local durability boundary (`PgIntake.java:239`, `:297`, `:400`).
- The newest benchmark reports one r2000 run and does not attribute its improvement to the lease change; that restraint is appropriate (`bench/RESULTS.md:3`).

### Questions still open for Pavel

- Which database promotion policy is the supported production contract: zero acknowledged-transaction loss, or an explicitly stated RPO?


## Review of §4 — Runtime core and delivery lifecycle

Session date: 2026-11-06  
Snapshot: `flottila-aa4ca58.zip`; architecture v3.4, overridden by CORE-REWORK.  
Evidence paths are relative to `snapshots/aa4ca58/`. VERIFIED means code inspected; all proposed spikes are NOT_RUN.

### Findings

#### F4.1 — Retrying a DLQ item changes the identity needed to deduplicate an uncertain send

- **Assumption in the document:** CORE-REWORK O-9/Q-7 uses a stable message ID to make uncertain sends safe to repeat. D-066 deliberately treats manual retry as a new message; this older behavior leaves a hole that P2-15b's outgoing header alone does not close.
- **How it fails:** (1) An operator retries a parked message; the API creates ID R1. (2) The partner successfully applies R1. (3) Removing the original DLQ row fails, or the process dies before removal. (4) The operator retries the still-present row; this run gets R2. (5) Even a partner implementing the planned `x-message-id` dedupe accepts R2 as a different operation. A successful send followed by a failed DLQ DELETE suffices; no node crash is necessary.
- **Severity:** major under the current replay contract; **Critical** under the requested dedupe impact scale. **Likelihood:** plausible during database/network trouble. **Status:** VERIFIED.
- **Evidence:** `flottila-runtime/src/main/java/io/flottila/runtime/admin/AdminServer.java:585` only guards retries in this process, `:587` generates a new ID; `flottila-core/src/main/java/io/flottila/core/flow/Replay.java:150` starts with that ID, `:153` sends, `:158` removes the original row afterward, and `:160` reports failure to remove it. `agent/packets/P2-15b.md:24` specifies the envelope ID as the dedupe header.
- **Spike:** `retryAfterFailedDlqRemovalKeepsOperationIdentity`: use a partner ledger deduping by operation ID; let the first retry succeed and fail its DLQ removal; restart and retry again. Require one business effect and an eventual resolved row. Also test concurrent retries through two nodes once remote body reads exist. NOT_RUN.
- **If confirmed, the decision changes to:** Separate an execution/trace ID from the stable identity of an operation being recovered; an intentional replay may create a new operation, an uncertain retry must not silently do so.
- **Proposed fix:** Smallest: durably assign and reuse the retry's operation identity before sending, with a database claim for the original DLQ row. Ideal: schedule manual recovery through the normal durable work/fence path and distinguish “recover this operation” from “send a new copy” in the API.
- **Cost:** A durable state transition for a manual retry, plus API/UI semantics; no extra transaction for every normal message. Old receipts and retained dedupe keys need a compatibility policy.
- **Reversible:** New API options are reversible; partner-facing identity semantics need versioning.
- **Asked Pavel:** This is an explicit challenge to D-066's retry-as-new-message behavior, not a claim that the code accidentally violates that decision.

#### F4.2 — One message ID is insufficient as the dedupe key for several sends to the same receiver

- **Assumption in the document:** P2-15b sends `e.id()` as `x-message-id`, which the partner uses to deduplicate; flows may contain several `to` steps.
- **How it fails:** (1) A flow makes two legitimate writes to the same partner/dedupe namespace. (2) The first succeeds. (3) The second carries the same message ID, although it is a different step and may carry different bytes. (4) A receiver following the proposed message-ID dedupe rule drops it or returns the first result. Stable child IDs do not distinguish several sends by the same child either.
- **Severity:** blocker for message-ID-only dedupe of multi-send flows; **Critical** conditional impact. **Likelihood:** partner-contract dependent. **Status:** SUSPECTED design gap in the pending P2-15b protocol, not a claim that its header is shipped.
- **Evidence:** `agent/packets/P2-15b.md:24` specifies the header; `flottila-core/src/main/java/io/flottila/core/flow/FlowBuilder.java:461` builds each `to` step; `flottila-components/http/src/main/java/io/flottila/components/http/HttpComponent.java:373` preserves the message ID after a reply. No operation/step namespace accompanies the proposed dedupe key.
- **Spike:** `twoSendStepsHaveDistinctStableOperationKeys`: send twice to one deduping endpoint, with different step bodies; rerun the original message after an uncertain final completion. Require exactly two business effects, not one or four. Repeat under split and after deployment-version changes. NOT_RUN.
- **If confirmed, the decision changes to:** Define receiver-visible identity for each intended side effect, stable across retries and distinct across separate effects.
- **Proposed fix:** Smallest: document receiver dedupe scope and supply a stable operation key distinct from the tracing message ID. Ideal: derive it from logical message, stable step identity and split lineage, with explicit version/replay semantics. Never add the retry attempt to that key; that would defeat dedupe.
- **Cost:** Small headers/metadata, but a partner-contract change and durable identity design. No per-send database write is inherently necessary.
- **Reversible:** Wire-contract changes require compatibility handling.
- **Asked Pavel:** Receiver dedupe scope is unspecified; no SAP behavior is assumed.

#### F4.3 — Graceful shutdown closes dependencies before worker settlement

- **Assumption in the document:** ARCHITECTURE §3 promises SIGTERM drain; a planned shutdown should let already-running acknowledged work settle within its budget.
- **How it fails:** (1) A worker is sending an acknowledged message. (2) SIGTERM stops claims. (3) Flow.close calls worker.close but discards its completion future. (4) Node.close closes membership, journal, message store and pool. (5) The partner succeeds, but the worker can no longer settle its work row, or JVM shutdown ends it. (6) Restart repeats the send. This can happen with ample Kubernetes termination grace; the code never waits for the worker to use it.
- **Severity:** major; **High**. **Likelihood:** common with in-flight work during rolling restarts. **Status:** VERIFIED.
- **Evidence:** `flottila-runtime/src/main/java/io/flottila/runtime/deploy/Deployments.java:358` discards the worker future; `:603` waits for delivery and replay shutdown, not workers; `flottila-runtime/src/main/java/io/flottila/runtime/node/Node.java:407` proceeds to close dependencies; `flottila-core/src/main/java/io/flottila/core/flow/FlowWorker.java:365` still needs stores before settlement. Existing FIX-68 covers lost trail rows; this adds the partner-success/unsettled-work consequence. It is distinct from FIX-134's retained membership-row delay.
- **Spike:** `NodeShutdownWaitsForWorkerSettlementIT`: hold an outbound reply, begin Node.close, require the node to keep the pool/journal usable and shutdown pending; release the reply and require work deletion before shutdown returns. Separately expire the drain budget and verify an explicit in-doubt exit. NOT_RUN.
- **If confirmed, the decision changes to:** Node shutdown must await bounded worker settlement before closing worker dependencies.
- **Proposed fix:** Smallest: a node-shutdown-only wait on worker drains; do not make every hot-swap close wait indiscriminately. Ideal: a lifecycle coordinator sets readiness down, stops intake, drains while renewing membership, flushes storage, then releases membership and closes clients.
- **Cost:** Shutdown takes up to its configured drain/flush budget; no normal message-path round trip.
- **Reversible:** Yes.
- **Asked Pavel:** No missing domain fact.

#### F4.4 — Health remains green when the node cannot process its own queue

- **Assumption in the document:** Health endpoints support load balancing and Kubernetes operation; membership fencing stops an unsafe node's work.
- **How it fails:** (1) Stall the dedicated lease connection while keeping pooled `select 1` and intake working. (2) The fence closes after L/2. (3) Workers stop claiming, but ingress continues returning 202. (4) `/health` remains 200, so a load balancer sends more work to the fenced node. Draining is also absent from health; clearing the local deployment list can make an empty node look healthy while it stops.
- **Severity:** minor; **Medium**. **Likelihood:** plausible on lease-row contention, a connection-specific failure, or shutdown. **Status:** VERIFIED.
- **Evidence:** `flottila-runtime/src/main/java/io/flottila/runtime/node/Node.java:264` registers postgres, deployments and journal only; `flottila-runtime/src/main/java/io/flottila/runtime/admin/AdminServer.java:485` derives status from those checks; `flottila-core/src/main/java/io/flottila/core/flow/FlowWorker.java:147` rejects claims when fenced; `flottila-runtime/src/main/java/io/flottila/runtime/deploy/Deployments.java:600`, `:649` lacks a draining health state. FIX-23 already records the separate unbounded probe problem.
- **Spike:** `fencedNodeIsNotReadyButRemainsLive`: hold the membership row locked, keep other database queries usable, advance beyond the fence, and require readiness 503 with liveness 200. Hold shutdown midway and require readiness to drop before listeners stop. NOT_RUN.
- **If confirmed, the decision changes to:** Processing readiness, process liveness and startup completion are different signals.
- **Proposed fix:** Smallest: readiness includes fence, local journal admission capability and an explicit lifecycle state. Ideal: distinct bounded startup/readiness/liveness endpoints; liveness must not turn a shared database slowdown into a fleet restart.
- **Cost:** Local checks and bounded probe queries; lower available ingress capacity while fenced, intentionally.
- **Reversible:** Yes.
- **Asked Pavel:** No missing domain fact.

### Not a problem (checked and fine)

- Membership renewals have a dedicated connection, and a stale epoch cannot renew a rejoined row.
- Releasing an expired peer rechecks expiry in the write; an old list of expired peers is not itself authorization to release.
- F-28 explicitly lists suspend, database clock steps and the rejoin margin. Those are existing assumptions, not rediscovered findings.
- The outgoing deadline/header/split-ID work is pending, and this review does not report their mere absence as new defects.

### Questions still open for Pavel

- What stable operation identity and retention period will receivers use for dedupe, including manual recovery and several sends within one flow?


## Review of §7 — Components, queues and persistence

Session date: 2026-11-06  
Snapshot: `flottila-aa4ca58.zip`; architecture v3.4, overridden by CORE-REWORK.  
Evidence paths are relative to `snapshots/aa4ca58/`. Spikes below are proposed, not executed.

### Findings

#### F7.1 — Normal body retention makes a subsequent journal restart fail

- **Assumption in the document:** D-105 permits kept bodies to expire after `FLOTTILA_JOURNAL_KEEP_MAX_AGE`; restart should reject actual lost durable bodies.
- **How it fails:** (1) Finish a recorded message. (2) Age its kept ref beyond KEEP_MAX_AGE, with no work or DLQ pin. (3) Sweep the segment: its database row and file disappear, but `body_refs` remains. (4) Restart. Recovery treats that old ref as a required pin, finds no segment, and refuses startup with an ACCEPT_LOSS digest. Routine retention now blocks a rolling update and labels intentional expiry as loss.
- **Severity:** major; **High**. **Likelihood:** high after retained bodies age and the node restarts. **Status:** VERIFIED.
- **Evidence:** `flottila-runtime/src/main/java/io/flottila/runtime/journal/PgSegmentRows.java:39` includes all unarchived/unlost refs in recovery pins, whereas `:53` excludes expired non-DLQ refs from sweep pins and `:62` deletes segment rows; `flottila-runtime/src/main/java/io/flottila/runtime/journal/Journal.java:390` and `flottila-runtime/src/main/java/io/flottila/runtime/journal/JournalStart.java:129` enforce recovery pins. `flottila-runtime/src/test/java/io/flottila/runtime/journal/SegmentSweepIT.java:563` already asserts that an expired ref survives its segment's removal, but does not then reopen the journal.
- **Spike:** `aNormallySweptKeptRefDoesNotRefuseRestart`: persist, finish, advance injected time, sweep, close and reopen the same journal; startup must succeed and replay must report retention removal. Control case: remove a still-registered segment file; startup must still refuse. NOT_RUN.
- **If confirmed, the decision changes to:** Recovery and sweeping must agree on whether a reference still requires physical bytes.
- **Proposed fix:** Smallest: exclude intentionally retired segment references from recovery checks while retaining checks for work and registered segments whose files disappeared. Ideal: persist an explicit retention disposition, so intentional expiry and corruption are distinguishable without inference.
- **Cost:** A recovery-query join or explicit retirement update; no extra ACK round trip.
- **Reversible:** Yes; preserve conservative checks on real work pins.
- **Asked Pavel:** No missing domain fact.

#### F7.2 — Accepting a body loss leaves an invisible barrier in an ordered queue

- **Assumption in the document:** D-105's explicit ACCEPT_LOSS lets the operator recover from a named loss; lost work is described as held for human attention.
- **How it fails:** (1) An ordinary, unheld item loses its body. (2) The operator accepts its precise loss digest. (3) The row receives `lost_at`, so claims permanently skip it. (4) It has no `held_at`, so the held-work list and release endpoint cannot find or resolve it. (5) Later items with the same key remain behind this earlier row forever. Logs report loss, but the available work-management API offers no resolution.
- **Severity:** major; **High**. **Likelihood:** high when ACCEPT_LOSS is used on unheld ordered work. **Status:** VERIFIED.
- **Evidence:** `flottila-runtime/src/main/java/io/flottila/runtime/journal/PgSegmentRows.java:70` marks only `lost_at`; `flottila-runtime/src/main/java/io/flottila/runtime/queue/PgWorkQueue.java:73` excludes it, `:81` keeps it as an ordering barrier, `:121` lists only held rows and `:125` releases only those rows. The held-work routes are `flottila-runtime/src/main/java/io/flottila/runtime/admin/AdminServer.java:320` and `:548`.
- **Spike:** `acceptedLossIsListedAndItsOrderedKeyCanBeResolved`: enqueue two same-key items; lose the first; accept the loss; assert that the first is explicitly listed and the second blocked. Restore the body, or explicitly discard the barrier, and assert the documented resolution. NOT_RUN.
- **If confirmed, the decision changes to:** Loss is an explicit operator state with a visible ordering consequence and an audited resolution.
- **Proposed fix:** Smallest: add lost-work listing and explicit restore/discard operations; do not make ordinary release send an unreadable body. Ideal: one work-state model for waiting, held, lost and resolved work, with counts and alerts.
- **Cost:** Admin/API/query work, optionally a partial index; no per-message hot-path statement.
- **Reversible:** Listing is reversible; discarding an ordered barrier is irreversible and must be a deliberate operator action.
- **Asked Pavel:** No missing domain fact; the business choice to skip a lost ordered item remains the operator's.

#### F7.3 — A future transaction ID after failover can stop all segment sweeping

- **Assumption in the document:** D-082/D-109 resolve uncertain intake commits using `pg_xact_status`; sweeping can continue safely around unresolved saves.
- **How it fails:** (1) An intake COMMIT reply is lost; the node retains its xid and open body references. (2) Promote a standby whose next xid is behind that xid, or reconnect to a restored database with that property. (3) The one batched status query raises an error for the future xid. (4) Every sweep calls this resolution first and aborts before reclaiming any unrelated segment. Repeated failures can fill the journal and refuse new traffic. Exact reachability/duration depends on the promotion history; this is not a claim that every failover moves xid backwards.
- **Severity:** minor; **Medium**. **Likelihood:** conditional on xid/WAL divergence. **Status:** SUSPECTED overall; exception propagation is verified in code.
- **Evidence:** `flottila-runtime/src/main/java/io/flottila/runtime/store/PgIntake.java:103`, `:210`, `:215`, `:440`; `flottila-runtime/src/main/java/io/flottila/runtime/journal/SegmentSweeper.java:101`, `:116`. PostgreSQL 16 explicitly errors on a future xid in [xid8funcs.c](https://github.com/postgres/postgres/blob/REL_16_STABLE/src/backend/utils/adt/xid8funcs.c). Status is a recent-transaction lookup, not a cross-cluster commit certificate: [transaction-status documentation](https://www.postgresql.org/docs/16/functions-info.html#FUNCTIONS-PG-SNAPSHOT).
- **Spike:** `futureXidDoesNotBlockUnrelatedSegmentCollection`: make one uncertain save's status query return SQLSTATE 22023 while another segment is reclaimable; assert the uncertain segment stays pinned and the other is reclaimed. A second promotion test must establish the real xid scenario, including connection failover and a later xid collision. NOT_RUN.
- **If confirmed, the decision changes to:** Uncertain saves remain quarantined individually; their resolution cannot block unrelated garbage collection.
- **Proposed fix:** Smallest: isolate lookup failures, retain unresolved pins and expose their age/count while sweeping the rest. Ideal: reconcile an application intake/group identity against the authoritative database after a topology change, recording enough provenance to avoid treating a reused xid as the old commit. Do not classify an error as an abort.
- **Cost:** Extra bookkeeping and exceptional-path queries; no new normal ACK round trip for the isolation fix.
- **Reversible:** Yes; provenance schema/protocol additions need migration.
- **Asked Pavel:** No SAP fact required; production failover topology is not in the archive.

#### F7.4 — A parked item releases its ordered key instead of blocking it

- **Assumption in the document:** ARCHITECTURE §4 says failed ordered work blocks the queue by default, or parks-and-continues by configuration. `partitionBy` alone currently supplies only serialization of live work.
- **How it fails:** (1) Save ordered messages A then B for one key. (2) A fails and is successfully parked. (3) FlowWorker treats parking as completion and deletes A, potentially taking B in the same transaction. (4) B reaches the partner before an operator repairs A. Held-work blocking applies only when parking itself fails; it does not implement the advertised default failure policy.
- **Severity:** major if deployed as EOIO; **High**. **Likelihood:** deterministic on a parked first item. **Status:** VERIFIED code/document mismatch; the XI component and full EOIO contract are not shipped here.
- **Evidence:** `docs/ARCHITECTURE.md §4` (XI quality of service); `flottila-core/src/main/java/io/flottila/core/flow/FlowWorker.java:363`, `:373`; `flottila-runtime/src/main/java/io/flottila/runtime/queue/PgWorkQueue.java:114`, `:197`; `flottila-core/src/main/java/io/flottila/core/flow/WorkerPolicy.java:17` has no block-on-failure choice.
- **Spike:** `parkedHeadBlocksItsOrderedSuccessor`: two same-key items on two workers; park A; assert B cannot send until A is repaired or deliberately skipped. Repeat across a restart and under the fast path. NOT_RUN.
- **If confirmed, the decision changes to:** Advertise current keyed processing precisely; require durable block-on-failure before claiming EOIO.
- **Proposed fix:** Smallest: reject or clearly exclude EOIO configuration until implemented. Ideal: retain an explicit ordered barrier when parking, and atomically resolve it with retry success or audited skip.
- **Cost:** Failed keys stop until resolved; a state transition and associated operator workflow. No new statement is required on every successful send.
- **Reversible:** Configuration/documentation is reversible; changing existing ordered-flow behavior requires migration and an explicit policy.
- **Asked Pavel:** No assumptions about SAP transport behavior were needed.

#### F7.5 — A newly created journal directory's parent is outside the durability barrier

- **Assumption in the document:** A forced body and committed intake survive host failure, including on first start.
- **How it fails:** (1) First start recursively creates the journal directory. (2) NODE, the segment and their immediate directory are forced; intake returns 202. (3) Power is lost before the newly created journal-directory entry is durable in its parent. On a filesystem permitting that crash state, the reachable journal path disappears despite successful forces inside it. This is a filesystem-dependent risk, not a demonstrated loss on the deployment's actual volume.
- **Severity:** blocker if reproduced on supported storage; **Critical** conditional impact. **Likelihood:** low, first-start/power-loss window. **Status:** SUSPECTED; missing parent sync verified, crash manifestation untested.
- **Evidence:** `flottila-runtime/src/main/java/io/flottila/runtime/journal/Journal.java:378` creates directories; `:396` forces inside the new directory. `flottila-runtime/src/main/java/io/flottila/runtime/journal/JournalStart.java:212` forces NODE's containing directory. Generated-key setup similarly creates then forces its own directory (`MasterKeySource.java:157`, `:174`). The containing-directory-entry requirement is explained by [Linux fsync(2)](https://man7.org/linux/man-pages/man2/fsync.2.html).
- **Spike:** `firstAckSurvivesFreshDirectoryPowerLoss`: on the supported filesystem/storage class, create the data directory from scratch and cut power or replay crash write prefixes immediately after first 202; remount and verify the journal. Also instrument directory forces to require each newly created ancestor's parent before readiness. A process kill alone is insufficient. NOT_RUN.
- **If confirmed, the decision changes to:** Directory provisioning is part of the first-start durability protocol.
- **Proposed fix:** Smallest: durably pre-provision the journal directory before the process starts. Ideal: a shared directory-creation helper forces the required parent entries for NODE, keys and journal setup.
- **Cost:** Startup-only filesystem operations; no steady-state ACK latency.
- **Reversible:** Yes.
- **Asked Pavel:** The supported filesystem and volume crash guarantees are not specified in the snapshot.

### Not a problem (checked and fine)

- A node cannot claim a journal body held only by a different node (`PgWorkQueue.java:75`); this prevents a false takeover from becoming an unreadable send.
- Claim/complete/hold use the attempts token; a stale completion cannot delete a row after a newer hand-out (`PgWorkQueue.java:111`).
- Sweep locks segment rows and rechecks pins under a later statement snapshot (`PgSegmentRows.java:59`, `:169`).
- The journal writer forces the channels touched by its round before completing appends (`Journal.java:778`, `:838`).

### Questions still open for Pavel

- Does ordering mean source sequence, intake commit order, or queue sequence? Concurrent uncommitted intake can allocate a lower `work.seq` that a worker cannot yet see; no source-sequence protocol was found. This needs a defined contract before asserting global EOIO.


## Review of §9 — Configuration, deployment and security

Session date: 2026-11-06  
Snapshot: `flottila-aa4ca58.zip`; architecture v3.4, overridden by CORE-REWORK.  
Evidence paths are relative to `snapshots/aa4ca58/`. All spikes are NOT_RUN.

### Findings

#### F9.1 — PostgreSQL TLS cannot be configured on the runtime or membership connection

- **Assumption in the document:** ARCHITECTURE §9 requires TLS with hostname verification; the same image should work with managed or operator-hosted databases.
- **How it fails:** (1) Configure a normal TLS-only PostgreSQL access policy. (2) Start Flottila with the supplied host/user/password settings. (3) Its explicit PgConnectOptions contain no SSL mode or trust configuration, so the default non-TLS runtime connection fails. Permitting plaintext to make it start exposes metadata and SQL results in transit instead. An external TLS proxy is a possible workaround, not a capability of this code.
- **Severity:** major for TLS-required deployments; **Medium** in the requested availability scale. **Likelihood:** high for a TLS-only deployment. **Status:** VERIFIED.
- **Evidence:** `flottila-runtime/src/main/java/io/flottila/runtime/node/NodeConfig.java:216` defines only host/port/database/user/password/pool size; `flottila-runtime/src/main/java/io/flottila/runtime/node/Node.java:308` constructs options without TLS, reused by membership at `:227`. The migration URL at `:219` also lacks an explicit trust/hostname policy. Vert.x's [PostgreSQL client documentation](https://vertx.io/docs/vertx-pg-client/java/) documents default SSL mode DISABLE. The code does not use PgConnectOptions.fromEnv, so adding PGSSLMODE alone is not a solution.
- **Spike:** `allDatabaseRolesUseVerifiedTls`: run migrations, intake and membership against TLS-only Postgres; correct CA/hostname succeeds, wrong CA or hostname fails. Cover failover to the next endpoint. NOT_RUN.
- **If confirmed, the decision changes to:** Database trust and hostname verification are part of deployed-node configuration for every connection role.
- **Proposed fix:** Smallest: add CA and hostname-verified TLS settings shared by pool and membership, with matching JDBC migration settings. Ideal: secure deployed defaults and tested certificate rotation/reconnect behavior.
- **Cost:** Connection-handshake latency, encryption CPU and certificate management; no extra SQL round trip per message.
- **Reversible:** Configuration is reversible; production downgrade should be explicit.
- **Asked Pavel:** No missing SAP fact.

#### F9.2 — Undeploy can orphan messages subsequently accepted by another node

- **Assumption in the document:** Removing a deployment is refused while its flows have queued work. §9 also acknowledges that other nodes keep the old package until restart; the interaction defeats the queue-safety check.
- **How it fails:** (1) A and B run one package and its queue is empty. (2) Undeploy through A: its empty-queue check passes and it deletes the shared deployment record. (3) B still accepts a message and returns 202; hold its worker before completion. (4) Restart B with its intact journal. (5) Restore finds no deployment, so no worker processes the accepted item; an empty deployment list is healthy. Redeploying the package is the recovery path.
- **Severity:** minor in today's dev/CI scope; **Medium**. **Likelihood:** deterministic in this two-node interleaving. **Status:** VERIFIED. Manual undeploy is refused in deployed production environments; this is not presented as a currently reachable production admin route.
- **Evidence:** `flottila-runtime/src/main/java/io/flottila/runtime/deploy/Deployments.java:482` checks/deletes, `:502` retires only local flows, `:630` restores the remaining records, and `:649` treats no deployments as healthy; `docs/ARCHITECTURE.md §9` explicitly documents the remote-node lag.
- **Spike:** `TwoNodeUndeployCannotOrphanAcceptedWorkIT`: undeploy through A, submit through B, restart B; submission must have been refused or a durable deployment generation must remain available to settle it. NOT_RUN.
- **If confirmed, the decision changes to:** Delete a deployment only after all relevant intake has stopped and accepted work has a recoverable worker generation.
- **Proposed fix:** Smallest: refuse multi-node undeploy unless every participating node is stopped/coordinated. Ideal: durable retiring generation, pool-wide intake stop acknowledgement and queue/drain verification before record deletion.
- **Cost:** Deployment coordination and longer undeploy latency; no normal-message transaction is inherently needed.
- **Reversible:** Yes; retiring-state schema needs migration.
- **Asked Pavel:** No missing domain fact.

### Not a problem (checked and fine)

- Journal identity is checked against its persistent directory and database registration; a changed or missing journal is not silently treated as an empty healthy replacement.
- The Compose stack is explicitly a development demo and persists `/var/lib/flottila`; its single-node settings are not presented here as a production design defect.
- D-106 requires a persistent external master key or explicitly opted-in single-node generation. Losing that key is not repaired by adding a PVC or database replica.

### Questions still open for Pavel

- Which pool-wide deploy coordinator and retirement contract will be supported before production multi-node rollout?


## Review verification record

Compared all 1,135 extracted files byte-for-byte by SHA-256 with their ZIP entries: no differences. Checked 36 explicitly qualified source-line references for existing files and valid line numbers, and checked all 13 findings for the required template fields. Source behavior and key citations were also inspected manually. These document checks are not application tests; all proposed reproduction and chaos tests remain NOT_RUN.

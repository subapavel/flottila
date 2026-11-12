# CORE-5 — body journal copies (class C; the owner's HA condition)

Row: agent/BACKLOG.md CORE-5. Designer draft 2026-11-12 (Opus, xhigh), decided below. Draft — review at hand-over (WORKFLOW §6).

## Shape (designer, accepted)
S1 Transport: internal runtime package `io.flottila.runtime.journal.peer`, not a component (carries journal bytes, not Envelopes; needs Journal,
   SegmentRows, membership; no partner, nothing to mock per env). Vert.x NetServer/NetClient TCP. Address V18 `nodes.peer_address` written at join;
   the renew stays one statement and also returns live peers' addresses. Two connections per ordered pair: `repl` (owner→holder), `read`.
S2 Framing v1 `u32 len·u8 type·u64 seq·payload·tag[16]`; tag = HMAC-SHA256/128; session key = HKDF(env master key (D-106), "flottila-peer-v1",
   both nonces). HELLO(ver,node,epoch,journal id,nonce,proof), OPEN(owner,n,header), DATA(owner,n,pos,≤4 MiB), SEAL(owner,n,pos,footer+trailer),
   RECEIPT(owner,n,end), HAVE[(owner,n,end)], REFUSE(owner,n,FULL|FAILED), READ(id,owner,n,pos,len) → BYTES(id,≤1 MiB) | MISS.
   Bad tag, oversized frame, pos ≠ holder's end → link closed.
S3 ACK (Q-5): the writer round writes locally, hands the same bytes as DATA to each holder (non-blocking), forces; a promise completes in position
   order once forcedEnd(n) ≥ end ∧ every holder's received(n) ≥ end. `_PENDING_BYTES` counts reserved-and-not-(forced ∧ received) → a lagging
   peer is today's append wait → JournalBusy 503. Receipt = the holder's pwrite returned (survives a holder kill -9); the holder forces after
   its receipt, never on the ACK path. OPEN's receipt after file + header + force + dir force.
S4 Lost peer (link closed, no receipt progress for FLOTTILA_PEER_TIMEOUT on the injected Timer, or REFUSE): unreceived records of n fail retryable
   `JournalPeerLostException` (503 + Retry-After); n sealed early; the next segment opens with another live peer or alone (degrade: one ERROR,
   health `journal` DEGRADED); reconnect 100 ms doubling to 5 s.
S5 Identity: a copy is byte-identical (header, records, the owner's footer via SEAL), addressed (owner, n), `replicas/<owner>/<n>.seg`, counted in
   the holder's MAX_BYTES; OPEN refused FULL above the intake edge. Only the owner writes a footer. Placement: rendezvous hash over live peers,
   sticky per owner. Spill streams DATA while written (8 MiB unreceived window); persist = local force ∧ SEAL receipt.
S6 `copies` (owner at [0]): may change at pre-open until the first record; fixed first record → seal; after that only re-replication's CAS.
   Invariant: every listed holder received every committed record.
S7 Takeover: ELIGIBLE and the fast path also take a row whose `body_node` has no live `nodes` row (released, D-112) when the claimer is in that
   segment's copies. A holder never takes a live owner's rows; no live holder → items wait. Releasing node wakes workers and announces `*`.
   Read order: own journal → own replica → READ from a live holder (owner first) → retryable RemoteBodyException. Reader checks CRC/AEAD.
S8 Re-replication pass every _REPLICATE_INTERVAL: source O if live else first live holder; segments with fewer live holders than COPIES; target
   rendezvous over live non-holders; stream [0,end) or from HAVE end; ≤ _REPLICATE_RATE, oldest first; target forces + dir-forces, then
   `update … set copies = live ∪ {target} where copies = $expected`; a lost CAS leaves an unlisted copy, unlinked by its holder.
S9 Holder housekeeping: unsealed replicas CRC-scanned and cut at start; a committed pin past a cut drops that holder from the copies; unlisted
   replicas unlinked; a dead owner's segments swept by a live holder after release + _SWEEP_GRACE and no `flottila-%@O` session left (D-115).
S10 Notices: COPIES=1 today's ONE_COPY notice; COPIES=2 without a live peer "one copy until a peer joins" + DEGRADED; /health journal: configured
   copies, live holders, under-replicated segments and bytes; KEY_GENERATE with COPIES>1 refuses start (D-106).
S11 Keys (refused at start naming the key): FLOTTILA_JOURNAL_COPIES 1..2 (unset: 1 dev/ci, 2 deployed); FLOTTILA_PEER_BIND 0.0.0.0, _PORT 8083
   (0 = any; 8082 is the compose async door), _HOST (default the node id); FLOTTILA_PEER_TIMEOUT PT2S (PT0.1S..PT30S); FLOTTILA_PEER_READS 16
   (1..256 per peer); FLOTTILA_JOURNAL_REPLICATE_RATE 64 MiB/s (1 MiB..1 GiB/s); FLOTTILA_JOURNAL_REPLICATE_INTERVAL PT10S (PT1S..PT10M).

## Decisions (architect)
P1 Split: 5a link + node-to-node read; 5b replication + ACK; 5c takeover + two-node kill -9; 5d re-replication; 5e sweep/start; 5f P2-10
   interleavings after CORE-GATE (designer C5: P2-10 waits for the gate, the gate waits for CORE-5).
P2 Finding F-35 (written with 5a): node-to-node journal traffic is internal, not "contact with the outside world"; the Component SPI rule keeps
   its meaning for partners; §4 "nodes share state only through Postgres and Valkey" gains the peer link; copies are one logical object (owner,n).
P3 Owner questions kept open, defaults chosen so work continues (all draft): O1 HMAC under the env master key, mTLS later (certificates would be
   an ISSUE); O2 COPIES 1..2, default 2 deployed; O3 degrade (not refuse) on a lost peer; O4 a node back with an empty disk still needs
   ACCEPT_LOSS in v1; O5 CORE-GATE measures COPIES 2.
P4 Before 5a's red tests: javap on the resolved vertx-core 5.1 jar — NetServer/NetClient connect/listen, NetSocket write/pause/resume from a
   virtual thread, TLS options; FileChannel positional write; pg_stat_activity application_name visibility without pg_monitor.

## Acceptance 5a (red first)
A1 PeerLinkTest: a HELLO without the environment's key (wrong proof) is refused and the link closed; a tampered frame (one tag bit) closes the
   link; an oversized frame closes the link.
A2 PeerReadIT: a body whose segment lies in another live node's journal is read through its link (two in-process nodes, one Postgres);
   a large body streams under flow control (bounded heap); reads above FLOTTILA_PEER_READS per peer wait, never fail; a dead owner without a
   copy fails retryable (RemoteBodyException), never wrong bytes.
A3 MembershipIT: the renew answers live peers' addresses in one statement; V18 `nodes.peer_address` written at join.
A4 NodeConfig: FLOTTILA_PEER_* and FLOTTILA_JOURNAL_COPIES defaults and bounds, refusals naming the key; KEY_GENERATE with COPIES>1 refused.

## Architecture advisor (Fable 5.2, D-137) — SOUND WITH CHANGES; disposition (architect)
W1 reconnect reconciliation: ACCEPT → 5b. After HELLO, HAVE reconciles every listed (owner, journal, n): owner streams [Y,X)+SEAL; a holder ahead of
   the same journal's owner truncates to X; the link closes only on a protocol error. Tests: owner restart with a shorter and a longer holder.
W2 copies vs first record: ACCEPT → 5b. PEER_TIMEOUT covers OPEN; no promise of n completes before the committed row's copies ⊆ nodes that
   receipted OPEN. Test: holder lost between pre-open and the first record.
W3 replica identity: ACCEPT → 5a (frames) + 5b (disk). Address (owner, journal id, n) in OPEN/DATA/SEAL/HAVE/READ and on disk
   `replicas/<owner>/<journal>/<n>.seg`; resume only after a prefix check (header + running CRC to the HAVE end).
W4 unlink vs CAS: ACCEPT → 5d. One per-(owner, journal, n) lock on the holder for inbound stream, CAS and unlink; unlink only with no inbound
   session and after _SWEEP_GRACE. Test: unlink racing a re-replication CAS.
W5 holder force failure: ACCEPT → 5b. A failed holder force removes that holder by CAS before REFUSE(FAILED); the owner treats it as a lost peer;
   the force lag is bounded and shown in /health.
W6 source with an empty disk: ACCEPT → 5d/5e. Source = any live listed holder whose HAVE covers the end; a node drops itself from `copies` at
   start for every own segment it lacks.
W7 HELLO: ACCEPT → 5a. The proof binds both nonces, both node ids, the epoch and the direction; HELLO carries a key fingerprint; current and
   previous master key accepted (rolling rotation, D-106).
Alternatives: ArchUnit rule confining io.vertx.core.net.Net* to `journal.peer` (and the admin/door servers that exist) — ACCEPT in 5a, F-35 is the
   finding-first; TLS wrap — later, no frame change; announce `*` when the next row's body_node is not live — ACCEPT → 5c; MIN_COPIES reserved
   (1 only) — ACCEPT → 5b.
Risks: kill -9 tests in separate JVMs (5c, not in-process); the sequence "kill holder, restart, kill owner before re-replication ends" → items
   wait, none lost (5c/5d); ELIGIBLE join measured by EXPLAIN in 5c (FIX-130); disk sizing 2× per node and segment churn stated in the docs (5b).
Open questions (advisor's answers adopted, still draft for the owner):
 O1 HMAC v1 + W7; F-35 states that record metadata travels in clear (bodies are AEAD); TLS later as a wrap.
 O2 COPIES 1..2; no quorum ACK; `copies[]` and HAVE stay n-ary.
 O3 degrade; /health counts bytes acknowledged with one copy; the DEGRADED notice says those items wait for this node if it goes down.
 O4 not ACCEPT_LOSS: `FLOTTILA_JOURNAL_REJOIN=<old journal id>` (5e) gives up only pins with no live copy, keeps the rows, drops self from
    copies (W6), re-replication fetches back; `replace` must not delete rows that list live copies.
 O5 CORE-GATE conditions 1–3 stay at copies 1 (GATE.md, the owner's text); a reported, non-gating two-node COPIES 2 run of conditions 1–2;
    proposed to the owner: a functional gate condition, the 5c two-process kill -9 ledger (2000/2000, either node).

## Red approved 5a (architect, 2026-11-12)
API (test-author's proposal, accepted): `PeerConfig(bind, port, host, timeout, reads)`, `PeerIdentity(node, epoch, journal UUID, List<SecretKey>
masters current-first)`, `PeerServer.listen(Vertx, PeerConfig, PeerIdentity, Journal, Timer)` → Future (port(), close()), `PeerReads` implements
blob `RemoteBodies.open(BlobKey, owner, journal, JournalRef)`, `PeerHandshake.dialer/listener` (hello/proof/accept; PeerRefusedException),
`TieredBlobStore(…, RemoteBodies)`, `PgMembership(SqlConnection, peerAddress)`, `Renewal.peers()`, `NodeConfig.peer()`, `journalCopies()`.
Tests: PeerLinkTest :197 :214 :229 :250 :271 :300 :316 :334 :355; PeerReadIT :189 :216 :261 :343; PeerReadHeapTest:62 (-Xmx48m fork);
PgMembershipPeersIT :103 :117; NodeConfigPeerTest :42 :50 :69 :96 :102 :112; MigrationsIT:587 (+ 17→18 pins, PgDeploymentStoreIT:101,
PgMessageStoreIT:77, BenchMainIT:37); archtest ONLY_THE_PEER_LINK_OPENS_RAW_TCP (new rule, green today; ViolationTest :85-:87 fixtures).
Red: compile-red runtime (agent/work/CORE-5a-red-runtime.log), MigrationsIT 13/15 (CORE-5a-red-migrations.log).
O6 key rotation order: a listener accepts a dialer's key only if it knows it, so a rolling rotation adds the new key as the second entry on every
   node first, then promotes it. The docs (D-row with 5a) state this procedure; the reverse order is not supported and not tested.

## Owner 2026-11-12
The owner accepted the advisor's recommendations O1–O5, including the functional gate condition: D-138; GATE.md §CORE-GATE condition 4b
(two processes, copies 2, kill -9 of either node, survivor takes over, no loss, 3 of 3 runs per node); condition 7 needs CORE-5a..5e.

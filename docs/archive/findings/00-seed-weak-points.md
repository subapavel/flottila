> Carried over from the September 2026 review workspace. Section numbers refer to architecture **v3.2** (`docs/archive/architecture-v3.2.md`). Items 1, 2 and 9 were resolved by the §4 review (v3.3 §4, §5, §8); the rest are open suspicions to confirm or refute against real code, using `TEMPLATE.md`.

# Seed list — suspected weak points (start here, then find your own)

These came out of the design conversation itself. They are suspicions, not findings. Confirm, refute, or refine each with the template; then look for what this list misses.

| # | Section | Suspicion |
|---|---|---|
| 1 | §10.3 | The monitoring buffer (Postgres table → S3 files → ClickHouse S3Queue) predates Kafka being in the core. A Kafka topic consumed by ClickHouse's Kafka engine is probably the right design; 20 M messages/day through a Postgres buffer table may hurt in month three. |
| 2 | §4.7 | "One virtual thread per in-flight message, created when the previous one awaits" is what the Vert.x docs say for VIRTUAL_THREAD verticles; nobody has measured it with 256 in flight, a slow Postgres, and a blocking XSLT on a worker pool in the mix. |
| 3 | §10.4 | "Lossless" XML → JSON projection for XPath search: mixed content, namespaces, attribute order, CDATA, entities. And the XPath → JSON-path translation has edges (positional predicates, `//`, axes). |
| 4 | §9.2 | pg_clickhouse is two months old. Pushdown of JSON subcolumn predicates, connection handling under UI concurrency, auth mapping, behaviour when ClickHouse is down. |
| 5 | §7.5 | Per-extension-version class loaders leak on undeploy through the usual suspects: JDBC `DriverManager`, logging frameworks, ThreadLocals, GraalJS contexts, static caches. |
| 6 | §16.6, §8 | "Warm start under 30 s with 1,000 flows": GraalJS compiled code cannot be cached in Valkey; Groovy could be cached as bytecode. If a pod compiles 400 scripts on start, the HPA story is fiction. |
| 7 | §5.3, §15 | XI 3.0 is SAP-proprietary and not publicly specified. The adapter means reverse engineering the SOAP envelope, ack messages and error semantics from traffic. Largest technical and legal risk in the migration story; the document treats it as "a normal component". |
| 8 | §8 | Poller leases as Valkey TTL locks without fencing tokens: two pollers for a few seconds after a GC pause is possible (the Redlock argument). Fencing tokens checked at the file operation, or leases back in Postgres. |
| 9 | §4.3, §9.3 | Content-addressed payloads with per-tenant encryption: hash over plaintext with ciphertext stored once per hash works; hash over ciphertext with random IVs kills deduplication. The document doesn't say which. |
| 10 | §5.1, §5.2 | "Durable before ack" through Kafka with `acks=all` on three brokers versus the < 5 ms p99 target that came from Postgres batching. Which path does an XI EO message take, and does it meet the number? |
| 11 | §4.3, §6.7 | The deploy-time body-size check can only cover declared maxima; chunked uploads have no size until they end, so runtime enforcement is needed regardless. The document implies more than it delivers. |
| 12 | §3, §3.1 | Contributor experience: Postgres + Kafka + ClickHouse + Valkey + S3 as the minimum is a hard first `git clone`. No single-process dev mode with embedded or containerised stand-ins is described. |

Suggested order for sessions: §4 (runtime core), §5 (delivery), §10 (monitoring), §8 (caching), §7 (extensions), §9 (data stores), §14–15 (packages, migration), §12–13 (security, API), §16–17 (topology, operations), §18–19 (testing, AI), then §1–3 and §6 last.

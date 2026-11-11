# ISSUE-5 — OPEN — managed cloud Postgres and the `ha` durability profile (FIX-136, D-114, D-115)
Task: FIX-136
Date: 2026-11-11

## What is needed
The owner's choice for managed cloud Postgres (a D-115 candidate). RDS Multi-AZ instances and Cloud SQL HA replicate
below Postgres: `synchronous_standby_names` is empty and `pg_stat_replication` shows no sync standby, so FIX-136's `ha`
profile refuses them. Options:
1. Keep `ha` strict; a managed deployment sets `FLOTTILA_PG_DURABILITY=local` and the provider's RPO is the operator's.
2. A third value (e.g. `managed`) that skips the standby checks, states the provider's guarantee as the operator's
   attestation at start, and keeps the session pin.
3. Decide together with the D-115 failover manager choice.

## Why the agent cannot decide it
D-114/D-115 are owner decisions (RPO and the supported Postgres manager); this is a promise about message loss.

## What was tried
FIX-136 implements option 1's behaviour (strict `ha`, explicit `local`) and names managed PG in the docs; nothing that
option 2 would need is closed off.

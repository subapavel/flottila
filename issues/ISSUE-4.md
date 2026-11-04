# ISSUE-4 — the body journal's master key `FLOTTILA_JOURNAL_KEY` until the wallet exists (CORE-3, D-105)
Task: CORE-3 (implemented in CORE-4c)
Date: 2026-11-03

## What is needed
The owner's decision on where the journal's master key comes from. The body journal (D-105) encrypts every record with a
per-tenant key; the tenant keys are stored wrapped in `journal_keys` and unwrapped with one 256-bit master key. Options:
1. `FLOTTILA_JOURNAL_KEY` (base64) as a required environment secret in every environment until the wallet (P2-04 follow-up)
   holds it; the node refuses to start without it, or when it cannot unwrap the keys of pinned records. Rotation = a new
   master that rewraps `journal_keys`.
2. As 1, but dev and test may start without it with an explicit `FLOTTILA_JOURNAL_KEY_FILE` the node creates once in its
   data dir (lost data dir = unreadable journal, which is lost anyway).
3. Wait for the wallet before CORE-4c.

## Why the agent cannot decide it
Credentials (CLAUDE.md: money, credentials, licences → ISSUE).

## What was tried
None; design only. Working assumption for CORE-4a/4b (no Postgres, keys passed in by tests): none needed. CORE-4c waits.

# CORE-12 — ARCHITECTURE rewritten to the core-rework brief (class B, docs only)

Row: agent/BACKLOG.md CORE-12. Brief: docs/CORE-REWORK.md (O-1..O-13, Q-1..Q-9). Done-when: no ARCHITECTURE sentence
contradicts the brief. No code, no tests (docs task: the red is the contradiction list below, checked by the reviewer).

## Contradiction list (scout, after ee1066b) — every line must be resolved
Built (state as is, present tense): :88, :158, :166, :197, :245, :247, :305, :318 (/xi part), :323, :333 ("in the intake's
transaction with its body"), :494 (§12 runtime stores), §13 :516/:518, §14 :532 (/xi part).
Pending (future tense with "(CORE-n, pending)"): :87, :207, :318 (ingest), :333 (Kafka monitoring topic), §13 :520, §14 :532
→ the mover into ClickHouse (Q-3, CORE-10); :195, §6 :264 → retry on a schedule then park (Q-7, CORE-14); :325 → bodies table
dropped (CORE-11); :331 → step bodies and sync recordings off the hot path, no fsync, no wait (Q-6, CORE-8) and levels (Q-9,
CORE-13); §6 :278 → levels none/inputs/changes + errors/outputs (Q-9, CORE-13); :340/§14 :536 "≤ ~256 KB inline" → rows ≤ 8 MiB
ZSTD(1), larger as ordered rows (D-108, CORE-2 done; ingest CORE-10 pending); :341 S3 only as ClickHouse cold tier (O-4).
Partial: :317 batched completion (CORE-7 done; F-31: rows still deleted per message — keep that caveat).
Kafka elsewhere (:192, :345/:349 Kafka lag, §11 :450/:484 test stack): add-on wording or remove.
Missing (add briefly where the section belongs): §3/§4 a short paragraph naming the node-local body journal (O-6) and copies per
environment (O-7, CORE-5 pending); Q-8 journal full → 503 (CORE-10 pending); retention split per Q-9 (CORE-13 pending).

## Rules
R1 Each sentence changed keeps the existing D-/F- references; add the brief id (O-n/Q-n) and the row.
R2 One changelog row 3.14 (below 3.13, four cells) and one finding docs/findings/F-34.md (format of F-33) listing every
   changed line group with the brief id it now follows — the finding is the "a finding" of the row.
R3 Do not touch other docs; do not invent mechanisms beyond the brief; say "pending" whenever the BACKLOG row is not done.
R4 Read ARCHITECTURE by line ranges, never whole.

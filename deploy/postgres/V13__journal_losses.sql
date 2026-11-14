-- CORE-4c3 (D-105, CORE-3 item 9 and "Review r2 additions"): losses an operator accepted with FLOTTILA_JOURNAL_ACCEPT_LOSS.
-- A work item whose body was given up gets lost_at (null = not lost): it is held, never claimed, never run, never parked,
-- and it pins no segment; body_refs.lost_at (V12) is the same mark for a kept ref. Nothing is deleted by an acceptance.

alter table work add column lost_at timestamptz;

-- each accepted loss once: the digest over the sorted given-up items of that journal, as the refusal printed it
create table journal_losses (
    node        text        not null,
    journal_id  uuid        not null,
    digest      text        not null,
    accepted_at timestamptz not null,
    primary key (node, journal_id, digest)
);

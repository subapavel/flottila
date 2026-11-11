-- FIX-141 (D-126 L4, draft): the audit trail of operator actions, append-only — no update or delete path in code. One row per
-- action: when, who (the actor the admin request declared in X-Flottila-Actor, 'unknown' without one: self-declared under the
-- shared admin token, not authenticated), what (e.g. work.discard), on what (e.g. work/<seq>) and a detail document (for a
-- discard: flow, messageId, partitionKey, lostAt and the remote address). No bytes: a detail names, it never copies a body.
create table audit (
    id      bigserial   primary key,
    at      timestamptz not null,
    actor   text        not null,
    action  text        not null,
    subject text        not null,
    detail  jsonb       not null
);
-- The lost rows by seq, partial as work_held (V5): GET /admin/work/lost pages them without scanning work.
create index work_lost on work (seq) where lost_at is not null;

-- The marks of the flows the pool retires. A redeploy whose version no longer runs an at-least-once flow of the record it
-- replaces, or an undeploy, marks each such flow ("<package>/<flow>", the id its work rows carry) in the transaction that writes or
-- deletes the record, with a copy of the record it drains under: version, repository, commit, content, pins and deployed_at.
-- While its mark exists a flow is still admitted, so a message a node accepts for it meanwhile is kept and run by its drain. A
-- mark ends once no work row of its flow is left, under the flow's exclusive advisory lock, and a deploy that runs the flow
-- at-least-once again deletes it.
create table retiring (
    flow text primary key,
    package text not null,
    version text not null,
    repository text not null,
    commit text not null check (commit ~ '^[0-9a-f]{40}$'),
    content text not null check (content ~ '^sha256:[0-9a-f]{64}$'),
    dependencies jsonb not null,
    deployed_at timestamptz not null,
    marked_at timestamptz not null
);

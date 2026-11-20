-- The pool's deploys. One row holds the pool's generation: every deploy and undeploy raises it by one in the transaction that
-- writes or deletes its package's record, under the pool's advisory lock (one deploy or undeploy at a time in the pool). A node
-- compares the generation with the one it last applied and reads the records again when it moved.
-- Each record names the flows its version runs at-least-once, by qualified id ("<package>/<flow>"). Rows written before this
-- version name none: they are deleted, and their packages are deployed again. Every node of the pool stops before the upgrade.
create table deploy_pool (
    id boolean primary key default true check (id),
    generation bigint not null check (generation >= 0)
);
insert into deploy_pool (generation) values (0);
delete from deployments;
alter table deployments add column at_least_once text[] not null;

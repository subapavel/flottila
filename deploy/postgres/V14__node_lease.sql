-- P2-15a (D-101 (1), F-28): the node lease. One row per node: its epoch, bumped by every join (a restart is a new
-- incarnation), and its lease, renewed every FLOTTILA_NODE_LEASE_RENEW on a dedicated connection; lease_until null = released
-- by a peer that saw it expired (or never live again). A node's work item is claimable by another node only while its holder
-- has no live row (lease_until not null); a holder with no row at all counts as gone.
create table nodes (
    node        text        primary key,
    epoch       bigint      not null,
    lease_until timestamptz
);

-- every hand-out of a row that was handed out before and not settled (locked_by still set) counts a doubt: the earlier holder
-- may have sent before it died (in doubt is derived, nothing is written per send). No new index on work.
alter table work add column doubts int not null default 0;

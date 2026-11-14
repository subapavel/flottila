-- CORE-5a (packet S1, draft): where a node's peer link listens, `host:port` (FLOTTILA_PEER_HOST and the port the link bound),
-- written by the node's join and overwritten by its next one. The renew answers the live peers' addresses in its one statement,
-- so a node learns where to READ another node's journal from with the renew it sends anyway. Null for a node that joined before
-- V18 (until its next join) and for a membership without an address: such a node is live but not readable over the link.
alter table nodes add column peer_address text;

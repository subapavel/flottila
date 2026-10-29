-- P2-02b (D-087): the pins of a deployed package, the closure of its dependencies as resolved at its deploy, each
-- {"name", "version", "archiveSha256"} in the order resolved. Saved in the deployment's one upsert, so the commit point is
-- unchanged; a row written before V9 has none. A node never prunes an archive directory that a row pins, and its restore
-- fetches a pin from the archive store by its sha256.
alter table deployments add column dependencies jsonb not null default '[]'::jsonb;

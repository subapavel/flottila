-- P1-09c3a (D-070): the deployment record, one row per package, shared by every node of the pool. It holds no bytes: the
-- package archive is stored once in the archive store (S3 in a deployed environment) under its SHA-256, and a node restores a
-- package by fetching that object and checking its digest. The row is written once a deploy's flows started (its commit
-- point) and replaced whole by the next deploy of the package; nothing deletes archives in Phase 1.
create table deployments (package text primary key,
                          version text not null,
                          revision text,
                          archive_sha256 text not null check (archive_sha256 ~ '^[0-9a-f]{64}$'),
                          deployed_at timestamptz not null);

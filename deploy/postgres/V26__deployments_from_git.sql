-- A package is deployed from a commit of its git repository. Its row names the repository, the commit and the content digest
-- of the package's files, and each pin of its dependencies names the same: {"name", "version", "content", "repository",
-- "commit"}. A node restores a package by reading that commit from its git cache, or fetching it, and checking the content.
-- The archive's SHA-256 and the free-form revision go; so do the rows that named an archive, which only development stacks
-- hold: they deploy again from git.
delete from deployments;
alter table deployments drop column revision,
                        drop column archive_sha256,
                        add column repository text not null,
                        add column commit text not null check (commit ~ '^[0-9a-f]{40}$'),
                        add column content text not null check (content ~ '^sha256:[0-9a-f]{64}$');

# Deployment from git

A node runs the packages that one commit of a git repository holds. A deploy names that commit, and
the record of every deployed package names the repository, the commit and the folder its files came
from. No package archive is uploaded, and none is written to or read from S3.

This document is the agreed design. Today a package reaches a node as a zip uploaded through the admin
API (`PUT /admin/packages/{name}`, `rail8 deploy`), and the node keeps each archive in S3 or a local
folder, under its SHA-256. All of that goes. Undeploy, the redeploy of only the flows that changed, the
rollback of a failed start and the package checks stay, on the git path.

It is built in four stages; none reworks what an earlier one built ("What each stage builds", at the
end).

## Rules

- The UI edits files in a working folder and never changes a running node. The only way into operation
  is a commit in git.
- A node pool reads one repository, named in the node's configuration. A deploy names only a commit, so
  whoever holds the admin token cannot point a node at another host.
- The unit of a deploy is the commit: every package in the pool's folder of that commit. A package
  whose files did not change keeps running untouched, and within a changed package only the flows
  whose hash changed stop and start.
- Every problem of a deploy is answered before anything stops.
- A package is removed only when the deploy names it.
- The bytes that count are git's: a package's content digest is computed over the files as the commit
  holds them.
- Git's credentials live in the node's environment only: never in git, in Postgres, in a URI, in a log
  or in an answer.
- The git cache on a node is not state. Deleting it loses nothing; the node fetches again.

## The repository of a node pool

The nodes of a pool read the same repository through the same settings:

| Setting | What it is |
| --- | --- |
| `RAIL8_GIT_REPO` | The repository's URI. `https://` in a deployed environment; `file://` also in `dev` and `ci`. A URI with user information (`https://user:token@host/…`) is refused at the node's start. Without this setting the node refuses every deploy and lists every recorded package `FAILED`. |
| `RAIL8_GIT_PATH` | The folder of packages in the repository, relative, with no `..`; the root by default. |
| `RAIL8_GIT_REF` | A branch or tag, such as `refs/heads/release`. Required in a deployed environment, optional in `dev` and `ci`. When it is set, a deployed commit must be reachable from it. |
| `RAIL8_GIT_USER`, `RAIL8_GIT_TOKEN` | The user name and a read-only access token for an `https://` repository; both or neither. |
| `RAIL8_GIT_TIMEOUT` | The longest a fetch may take; 60 s by default, 1 s to 10 min. |
| `RAIL8_GIT_MAX_BYTES` | The largest the node's git cache may grow; 1 GiB by default, 1 MiB to 64 GiB. |

The node keeps a bare repository as its cache in `<dataDir>/git`, read and written with JGit (pure
Java, Eclipse Distribution Licence 1.0, a BSD-3-Clause licence); the node image needs no git binary. A
fetch brings the full history of what it fetches, never a shallow copy. The cache keeps a reference to
every commit a record names, so a force-push on the server or a cleanup of the cache never drops one.
Its size is checked before and after each fetch; a cache over its limit refuses the fetch and names the
setting.

### The token rules

- `RAIL8_GIT_REPO` carries no user information; the token is sent only as the `https` credentials of
  that repository's host.
- A redirect is followed only to the same scheme and host. A redirect anywhere else is refused, and the
  credentials are never sent there.
- Every git failure passes through one formatter. It names the repository without credentials, the
  commit or ref, and one reason from a fixed list: not found, not reachable from the pool's ref,
  credentials refused, timed out, cache full, the transport failed. It never passes on the transport's
  own message.
- Every fetch has the finite timeout, and the cache the finite size, above.

## What a commit holds

A package is unchanged: a folder holding `package.yaml` (its name, a semver version and its
dependencies at exact versions) and `package.lock`.

- The packages of a commit are the immediate subfolders of `RAIL8_GIT_PATH` that hold `package.yaml`,
  or the path itself when it holds one. A package is known by the name in its manifest, not by its
  folder's name. Two folders whose manifests give one name are refused.
- A package's tree holds regular files only: a symbolic link or a submodule is refused. A package holds
  at most 10 000 files and at most `RAIL8_MAX_PACKAGE_BYTES` in all; a commit holds at most 1 000
  packages.
- Dependencies resolve only among the packages of the same commit. Every entry of a package's
  `package.lock` must name a package of the commit with that name, version and content digest, as
  `rail8 lock` resolves them in the working folder. Nothing depends on the order of deploys.
- A repository of packages carries a `.gitattributes` holding `* -text`, so git never changes line
  endings and the working folder holds the committed bytes. Without it, a lock written on a laptop that
  converts line endings does not match the commit, and the deploy is refused with both digests named.

## A deploy

`POST /admin/deploy` with the body `{"commit": "<40 hex>", "remove": ["<name>", …]}`. In `dev` and `ci`
the commit may also be a ref (`HEAD`, `main`, `refs/tags/v1`), resolved in the repository at the fetch.
`remove` is optional. With `?check=true` the node answers the plan and every problem, and changes
nothing. The actor is the request's `X-Rail8-Actor` header, as for every audited act.

`rail8 deploy <commit|ref> [--check] [--remove <name>]…` sends it and prints the answer.

The order:

1. **Refusals before any fetch.** A bad body or commit id, a ref in a deployed environment (400); a
   deployed environment while other nodes cannot yet follow a deploy (403, "Several nodes" below); a
   closing node (503); another deploy running (409).
2. **Fetch.** The commit is fetched into the cache, within the timeout and the size, and, when
   `RAIL8_GIT_REF` is set, checked reachable from it (422 otherwise).
3. **Write.** Every package of the commit is written to `<dataDir>/packages/<name>/<content hex>/`,
   unless that directory is one the node keeps and its files are intact.
4. **Plan.** Each package is added (no record), changed (another content digest, or a dependency that
   changed), unchanged, or removed (a record, and no folder in the commit).
5. **Check.** Every package of the commit, the unchanged ones included, is checked whole with today's
   checks (schemas, components, endpoints, dependencies, listeners, calls between flows), the commit's
   packages standing for its dependencies. Every removed package must be named in `remove` (409, naming
   it, otherwise), and none of its at-least-once flows may hold items in the work queue. Every problem
   is one line of one answer (422), and nothing has stopped. A check ends here.
6. **Apply.** The added and changed packages are applied one by one, a dependency before its
   dependents, each through today's swap: its changed flows stop, its new flows start, its record is
   written (the package's commit point), and a failed start rolls that package back. The first failed
   start ends the deploy: the packages applied before it stay on the new commit, and the rest are not
   tried.
7. **Remove.** The removed packages are undeployed, dependents first.
8. **Re-stamp.** Once every package reached the commit, the records of the unchanged packages are
   written with the new commit. Their flows keep running.
9. **Audit.** One audit row: the actor, `deploy`, the commit, and each package's outcome.

The answer names the commit and, for each package, its version, its change (added, changed, unchanged,
removed), its outcome (reached the commit, rolled back, not tried) and the flows added, changed and
removed. A deploy that ended at a failed start answers 500 with the same list.

Undeploy is a commit without the package's folder, deployed with the package named in `remove`.
Rollback is a deploy of an earlier commit.

While the records name more than one commit, `GET /admin/packages` shows each package's commit and the
node's health is `DEGRADED`, naming the commits. Deploying one commit again, the earlier one or a fixed
one, ends it.

## The record

One row per package in `deployments`: the name, the version, the repository's URI (without
credentials), the commit, the folder in the repository, the content digest, `deployed_at`, and the
pins of its dependencies (name, version, content digest). A running flow names the commit it was built
from; an unchanged flow keeps its stamp, since its files did not change.

The migration drops the archive's SHA-256 and the free-form revision, and deletes the rows that named
an archive: those exist only in development stacks, which deploy again from git.

## Restore at a node's start

At its start, before its admin port opens, a node installs each record:

- A record from another repository or folder than the node's settings name is listed `FAILED`,
  naming both.
- The record's commit is read from the cache. A commit the cache lacks is fetched; reachability is not
  checked at a restore, so a ref that moved since the deploy does not stop a node from starting.
- The package's folder is written, its content digest checked against the record, and the package
  installed as today.
- A pin whose directory is absent is written from the dependent's own recorded commit, where its
  content was checked at the deploy.
- A package that cannot be fetched, or whose digest differs, is listed `FAILED` with the reason; the
  node starts and the others run.

## Several nodes

One deploy runs at a time in a pool: a deploy holds a Postgres advisory lock for its whole length, and a
second deploy on any node of the pool is 409. At its end the deploy raises the pool's generation, a
number in Postgres. Every other node reads the generation on its injected timer and, when it moved,
reconciles what it runs to the records: it installs a record whose content differs from what it runs,
as a restore does, and undeploys a package that has no record. It writes no record. A node that cannot
reconcile is `DEGRADED`, naming the packages, and tries again at the next tick.

Until this is built, a deployed environment refuses deploys, as today, and a running node learns of a
deploy on another node only at its next start.

## Environments

Environments are not branches. The same commit moves from dev to test to prod, each environment a pool
of its own with its own Postgres and its own `RAIL8_GIT_REF`: for example `main` for test, and for prod
a protected release branch that a reviewed pull request moves forward to the tested commit. Rail8
checks only that the commit is reachable from the ref; protecting the branch is the git host's work.

What differs between environments is already in the files: an endpoint's `environments.<env>.config`
overlays and `secret://` names, read from each node's own environment.

## The local run and the quickstart

- The working folder is a git repository. The UI saves files into it; the developer commits, then runs
  `rail8 deploy HEAD`.
- The quickstart makes its working folder a git repository holding the `.gitattributes` above and a
  copy of the demo package, and commits it. Its node mounts that folder read-only and reads it as
  `file:///workspace`, with `RAIL8_GIT_PATH` empty: the demo is a sibling folder there. Its steps commit
  before every deploy.
- A worktree, whose `.git` is a file, is not supported: the main repository is mounted.
- `bin/run-adapter` needs the adapter's folder in a git repository and deploys its `HEAD`. It prints
  one line when the package folder differs from `HEAD`: uncommitted changes are not deployed.
- `deploy/compose` and CI read the Rail8 repository itself, with `RAIL8_GIT_PATH` set to
  `examples/demo`. CI's checkout has the full history.

## `rail8 test` and `rail8 validate`

Both keep working on folders, in process. `Deployments` deploys a folder: the commit-level deploy above
calls it for each package with the package's git origin, and `rail8 test` calls it with none, which is
allowed only in `dev` and `ci` and writes no record. No admin route and no command reaches a deploy
without a git origin.

## The git seam

Only the runtime reads git, through one interface; a fake of it and the JGit implementation pass the
same contract suite. Blocking: called on a virtual thread.

```java
/** The repository of the node pool, read through the node's cache. */
public interface PackageRepository {
    /** The repository as records name it, without credentials, and the pool's folder in it. */
    URI repo();
    String path();

    /** Fetches and answers the commit a deploy names: a 40-hex id, or in dev and ci a ref;
     *  reachable from the pool's ref when one is set. */
    Commit forDeploy(String commitOrRef) throws GitFailure;

    /** Answers a recorded commit, fetching only when the cache lacks it; no reachability check. */
    Commit recorded(String commit) throws GitFailure;
}

/** One commit's packages under the pool's folder. */
public interface Commit {
    String id();

    /** Each package's folder in the repository and its manifest's name; two folders of one name are refused. */
    List<PackageFolder> packages() throws GitFailure;

    /** Writes one package's files into an empty directory; refuses a symbolic link, a submodule and a tree over the limits. */
    void write(PackageFolder folder, Path dir) throws GitFailure;
}

record PackageFolder(String path, String name) {}
```

`GitFailure` carries one reason of the formatter's list, and its message is the formatter's.

## What goes away

- `PUT` and `DELETE /admin/packages/{name}`; the deploy is `POST /admin/deploy`.
- `ArchiveStore`, `S3ArchiveStore`, `DirArchiveStore`, `PackageArchive` and `ArchiveName`.
- The settings `RAIL8_ARCHIVE_*` and `RAIL8_S3_*`, and the AWS SDK in the runtime: nothing else uses
  them. The body tier planned for S3 brings what it needs.
- `deploy/quickstart/deploy.sh`, and the zip code of `rail8 deploy`.

## Limits

| Limit | Value |
| --- | --- |
| One fetch | `RAIL8_GIT_TIMEOUT`, 60 s by default |
| The git cache | `RAIL8_GIT_MAX_BYTES`, 1 GiB by default |
| One package | `RAIL8_MAX_PACKAGE_BYTES` in all, 10 000 files |
| One commit | 1 000 packages |
| A deploy's body | 64 KiB, at most 1 000 names in `remove` |

## Tests

- The contract suite of `PackageRepository`, run on the fake and on JGit over a `file://` repository the
  test builds: packages found by manifest name, two of one name refused, symbolic links and submodules
  refused, the limits, reachability from the ref, a ref refused in a deployed environment, a recorded
  commit fetched when the cache lacks it, and the token rules (user information refused, the formatter
  never showing credentials or the transport's message, a redirect to another host refused).
- Deploys over a JGit `file://` repository, with a fake clock: a first deploy; a redeploy that swaps only
  the changed flows; an unchanged dependent of a changed dependency, checked and kept restorable; a
  removal named and one not named; a removed package whose queue holds items; a rollback by an earlier
  commit; a failed start in the middle of a commit, with the answer and the health naming both commits;
  `?check=true` changing nothing; a lock written over converted line endings, refused with both digests.
- Restore: from the cache; with a commit fetched; with the server unreachable (`FAILED`); a record from
  another repository (`FAILED`, naming both).
- Several nodes over one Postgres, on a fake clock: a second deploy refused while one runs; the other
  node reconciling at its next tick, installing and undeploying; a node that cannot reconcile
  `DEGRADED`.

## Choices the owner may still change

These were decided on the owner's behalf while the owner was away, and stand until the owner says
otherwise:

- One repository holds a landscape's packages as sibling folders, rather than one repository per
  package.
- The same commit is promoted from pool to pool, each gated by its ref, rather than a branch per
  environment.
- The unit of a deploy is the whole commit, rather than one package of it.
- The local run needs a commit too.
- A node learns of a new commit by a command, now; a node that follows its ref by itself may come
  later as a timer calling the same deploy.
- The token lives in the node's environment, now; the secret store planned for step 2 takes it later.

## What each stage builds

1. **The git seam.** `PackageRepository` on JGit, its fake and its contract suite, the `RAIL8_GIT_*`
   settings, the token rules and the limits. Nothing calls it yet.
2. **A deploy from a folder.** `Deployments` deploys a folder instead of a zip; the upload unpacks into
   a folder first, and `rail8 test` deploys folders in process. Nothing changes for a caller.
3. **Deploy and restore from git.** The record and its migration, `POST /admin/deploy` with its order,
   the restore from git, directories keyed by content digest, and the removals above. Every caller in
   the repository moves in the same change, so that each keeps working: `rail8 deploy` sends a commit,
   and CI, both compose files, `bin/run-adapter` and the quickstart deploy from git, as "The local run
   and the quickstart" says. A deployed environment still refuses deploys.
4. **Several nodes.** The pool's lock, its generation and the reconcile; a deployed environment then
   takes deploys of commits reachable from its ref.

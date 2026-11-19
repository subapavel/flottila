# Deployment from git

A node runs packages as one commit of each package's git repository holds them. A deploy names a
package repository and a commit, and the record of the deployed package names both. No package archive
is uploaded, and none is written to or read from S3.

This document is the agreed design. Today a package reaches a node as a zip uploaded through the admin
API (`PUT /admin/packages/{name}`, `rail8 deploy`), and the node keeps each archive in S3 or a local
folder, under its SHA-256. All of that goes. Undeploy, the redeploy of only the flows that changed, the
rollback of a failed start and the package checks stay.

It is built in five stages; none reworks what an earlier one built ("What each stage builds", at the
end).

## Rules

- The UI edits files in a working folder and never changes a running node. The only way into operation
  is a commit in git.
- Every package has a git repository of its own. The unit of a deploy is one commit of one package
  repository.
- A node pool reads its package repositories under one base, set in the node's configuration. A deploy
  names a repository by its name under that base, never by a URI, so whoever holds the admin token
  cannot point a node at another host.
- A dependency is pinned by its repository and commit, and its files come from that commit.
- Every problem of a deploy is answered before anything stops.
- The bytes that count are git's: a package's content digest is computed over the files its commit
  holds, as committed.
- Git's credentials live in the node's environment only: never in git, in Postgres, in a URI, in a log
  or in an answer.
- The git cache on a node is not state. Deleting it loses nothing; the node fetches again.

## Package repositories

A pool's package repositories live under one base: a group on GitLab, an organisation on GitHub, a
project in Azure DevOps, or a folder of repositories in a local run. A repository is named by one path
segment under the base (`orders`, `common`): the name `git clone` gives its folder. Its URI is the base
followed by that name.

The package lies at `RAIL8_GIT_PATH` in each repository, the repository's root by default. That folder
holds `package.yaml` and `package.lock`, and every other file of the package, in subfolders as deep as
it likes. A subfolder may carry a `folder.yaml` with a namespace, as it does today: a namespace of SAP
PO carries over as a folder. A package is known by the name in its manifest, which may differ from its
repository's name. On a pool, a package name belongs to one repository: a deploy of a package of the
same name from another repository is refused until the first is undeployed.

| Setting | What it is |
| --- | --- |
| `RAIL8_GIT_BASE` | The URI the pool's repositories live under, such as `https://gitlab.example.com/integration/`. `https://` in a deployed environment; `file://` also in `dev` and `ci`. A URI with user information (`https://user:token@host/…`) is refused at the node's start. Without this setting the node refuses every deploy and lists every recorded package `FAILED`. |
| `RAIL8_GIT_PATH` | The package's folder in each repository, relative, with no `..`; the root by default. |
| `RAIL8_GIT_REF` | A branch or tag name, such as `refs/heads/release`, the same in every repository. Required in a deployed environment, optional in `dev` and `ci`. When it is set, every commit a deploy fetches must be reachable from it in its repository. |
| `RAIL8_GIT_USER`, `RAIL8_GIT_TOKEN` | The user name and a read-only access token for every repository under an `https://` base; both or neither. |
| `RAIL8_GIT_TIMEOUT` | The longest a fetch may take; 60 s by default, 1 s to 10 min. |
| `RAIL8_GIT_MAX_BYTES` | The largest the node's git cache may grow; 1 GiB by default, 1 MiB to 64 GiB. |

The node keeps a bare repository per package repository as its cache, under `<dataDir>/git`, read and
written with JGit (pure Java, Eclipse Distribution Licence 1.0, a BSD-3-Clause licence); the node image
needs no git binary. A fetch brings the full history of what it fetches, never a shallow copy, and a
source whose history is shallow is refused as such. The cache keeps a reference to every commit a
record or a pin names, so a force-push on the server or a cleanup of the cache never drops one. Its
size is checked before a fetch and while the fetch receives; a fetch that would pass the limit stops
and names the setting.

A commit stays deployable and restorable only while its repository holds it. The pool's ref is a
protected branch: nobody force-pushes it or deletes it, and a deployed commit is never rewritten out of
its history.

### The token rules

- `RAIL8_GIT_BASE` carries no user information. The token is held in memory and sent only as the
  `https` credentials of the base's host; it is never written into the cache's configuration.
- A redirect is followed only to the same scheme and host. A redirect anywhere else is refused, and the
  credentials are never sent there.
- Every git failure passes through one formatter. It names the repository by its name, the commit or
  ref, and one reason from a fixed list: not found, not reachable from the pool's ref, history
  incomplete, credentials refused, timed out, cache full, the transport failed. It never passes on the
  transport's own message or its causes. Answers, logs, audit rows and the listing of a `FAILED`
  package carry only the formatter's text.
- Every fetch has the finite timeout, and the cache the finite size, above.

## What a commit holds

- The package's folder holds regular files only: a symbolic link or a submodule is refused. A package
  holds at most 10 000 files and at most `RAIL8_MAX_PACKAGE_BYTES` in all, checked from the commit's
  tree before anything is written.
- The content digest is computed as today, over the files the commit holds under the package's folder,
  with the bytes git stores. Untracked files and `.git` never count.
- A repository of packages carries a `.gitattributes` holding `* -text`, so git never changes line
  endings and a working folder holds the committed bytes.

## Dependencies

`package.yaml` names each dependency at an exact version, as today. `package.lock` pins each direct
dependency by its name, its version, its content digest, its repository and its commit. A lock written
before this change, with a dependency and no repository or commit, is refused, naming `rail8 lock`.

- `rail8 lock` finds a dependency among the working folder's package folders by its manifest's name, as
  today. It takes the repository from the name of the dependency's git working tree, the commit from its
  `HEAD`, and the content from that commit. A dependency whose files differ from its `HEAD` is refused:
  commit it first.
- A deploy resolves each dependency of the closure from the repository and commit its lock pins, never
  from a version found by name. A directory the node keeps with that content is used as it is;
  otherwise the commit is fetched, under the same rule of reachability as the package's own, written,
  and its content checked against the pin.
- A dependency's files come from its pinned commit, whatever version of it the pool runs, so packages
  deploy in any order. Calls between flows go to the flows that run, as today.
- `rail8 validate` and `rail8 test` read a dependency's files from its pinned commit in its working
  tree's repository, as the node will, and the package itself from its working folder.

## A deploy

`POST /admin/deploy` with the body `{"repository": "<name>", "commit": "<40 hex>"}`. In `dev` and `ci`
the commit may also be a ref (`HEAD`, `main`, `refs/tags/v1`), resolved in the repository at the fetch.
With `?check=true` the node answers what the deploy would do and every problem, and changes nothing.
The actor is the request's `X-Rail8-Actor` header, as for every audited act.

`rail8 deploy <repository> <commit|ref> [--check]` sends it and prints the answer.

The order:

1. **Refusals before any fetch.** A bad body, repository name or commit id, or a ref in a deployed
   environment (400); a deployed environment while other nodes cannot yet follow a deploy (403,
   "Several nodes" below); a closing node (503); another deploy running (409).
2. **Fetch.** The commit is fetched within the timeout and the cache's size. When `RAIL8_GIT_REF` is
   set, the ref is fetched too, its tip resolved once, and the commit proved an ancestor of that tip
   (422 otherwise; history the cache lacks is refused as incomplete, never as unapproved).
3. **Write.** The package's folder is written to `<dataDir>/packages/<name>/<content hex>/`, unless
   that directory is one the node keeps and its files are intact. A package of the same name recorded
   from another repository is refused (409).
4. **Dependencies.** Every pin of the closure is resolved, fetched and written as "Dependencies" says.
5. **Check.** The package is checked whole with today's checks: schemas, components, endpoints,
   dependencies, listeners, calls between flows, and the queue of each at-least-once flow it removes.
   Every problem is one line of one answer (422), and nothing has stopped. A check ends here.
6. **Apply.** Today's swap: the changed and removed flows stop, the added and changed ones start, the
   record is written (the commit point), and a failed start rolls back.
7. **Audit.** One audit row: the actor, `deploy`, the repository and the commit, and the outcome.

The answer is today's deploy answer, with the repository and the commit in place of the revision and
the archive.

**Undeploy** stays `DELETE /admin/packages/{name}`, with today's checks: a package another package's
record pins, or whose at-least-once flows hold queued items, is not undeployed. **Rollback** is a
deploy of an earlier commit of the same repository.

## The record

One row per package in `deployments`: the name, the version, the repository's name, the commit, the
content digest, `deployed_at`, and the pins of its dependencies (name, version, content digest,
repository, commit). A running flow names the commit it was built from: a flow a redeploy kept,
because its files did not change, keeps the commit it was built from while the record names the newer
one.

The migration drops the archive's SHA-256 and the free-form revision, and deletes the rows that named
an archive: those exist only in development stacks, which deploy again from git.

## Restore at a node's start

At its start, before its admin port opens, a node installs each record:

- The record's commit, and every pin's, is read from the cache first, so a warm cache restores with
  git unreachable. A commit the cache lacks is fetched. Reachability is not checked at a restore: the
  commit was approved when it was deployed, and a ref that moved since does not stop a node from
  starting.
- The package's folder is written and its content checked against the record; each pin's folder is
  written from the pin's repository and commit and checked against the pin. The package is installed
  as today.
- A package that cannot be fetched, or whose content differs from its record (a node configured with
  another base or path), is listed `FAILED` with the reason; the node starts, and the others run.

## Several nodes

- **One deploy at a time in a pool.** A deploy or an undeploy holds a Postgres advisory lock for its
  whole length; a second one on any node of the pool is 409.
- **No stale plan.** At its end the deploy raises the pool's generation, a number in Postgres. A node
  that takes the lock while the generation is ahead of the one it last reconciled reconciles first,
  then plans.
- **Following.** Every node reads the generation on its injected timer and, when it moved, reconciles
  what it runs to the records: it installs a record whose content differs from what it runs, as a
  restore does, and undeploys a package that has no record. It writes no record. At every tick it
  tries again a package it lists `FAILED` because its commit could not be fetched. A node that cannot
  reconcile is `DEGRADED`, naming the packages, and tries again at the next tick.
- **Retiring.** A flow that an undeploy or a redeploy removes stops taking messages on every node at
  once, and its worker runs on until no node's work queue holds an item of it; only then does it stop
  and, for an undeploy, the package's record go. A message accepted while the removal spreads is never
  stranded.

Until this is built, a deployed environment refuses deploys and undeploys, as today, and a running
node learns of a deploy on another node only at its next start.

## Environments

Environments are not branches. The same commit of a package repository moves from dev to test to prod,
each environment a pool of its own with its own Postgres and its own `RAIL8_GIT_REF`: for example
`main` for test, and for prod a protected `release` branch that a reviewed pull request moves forward
to the tested commit. Protecting that branch in every repository is the git host's work, set once for
the group. A package's dependencies reach prod the same way: their pinned commits must be on their own
release branches first. Rail8 checks only that each commit is reachable from the ref.

What differs between environments is already in the files: an endpoint's `environments.<env>.config`
overlays and `secret://` names, read from each node's own environment.

## The local run and the quickstart

- The working folder holds a clone of each package repository, named as the repository. The UI saves
  files into it; the developer commits, then runs `rail8 deploy <repository> HEAD`.
- The quickstart makes its working folder hold the demo as a git repository, `demo`, with the
  `.gitattributes` above, and commits it. Its node mounts the working folder read-only and reads it as
  the base `file:///workspace/`; the cache is in the node's data volume. Its steps commit before every
  deploy.
- A repository in the working folder is a full clone with its `.git` folder; a worktree or a copy
  whose `.git` points outside the mount is not supported.
- `bin/run-adapter` reads the adapter's own repository, with `RAIL8_GIT_PATH` set to its package
  folder, and deploys its `HEAD`. It prints one line when that folder differs from `HEAD`: uncommitted
  changes are not deployed.
- `deploy/compose` and CI mount the Rail8 repository as the repository `rail8` under a `file://` base,
  with `RAIL8_GIT_PATH` set to `examples/demo`. The checkout they mount has the full history.

## `rail8 test` and `rail8 validate`

Both keep working on folders, in process. `Deployments` deploys a folder: the git deploy above calls it
with the package's git origin, and `rail8 test` calls it with none, which is allowed only in `dev` and
`ci` and writes no record. `rail8 test` deploys a copy of each folder that it makes, owns and deletes,
so an edit in the working folder during a run changes nothing in it. No admin route and no command
reaches a deploy without a git origin.

## The git seam

Only the runtime reads git, through one interface; a fake of it and the JGit implementation pass the
same contract suite. Blocking: called on a virtual thread.

```java
/** The package repositories of the node pool, read through the node's cache. */
public interface PackageRepositories {
    /** The base the repositories live under, without credentials, and the package's folder in each. */
    URI base();
    String path();

    /** Fetches and answers a commit a deploy names, for the package or a pin: a 40-hex id, or in dev
     *  and ci a ref; proved reachable from the pool's ref when one is set. */
    Commit forDeploy(String repository, String commitOrRef) throws GitFailure;

    /** Answers a commit a record or a pin names, from the cache, fetching only when the cache lacks it;
     *  no reachability check. */
    Commit recorded(String repository, String commit) throws GitFailure;
}

/** The package's folder in one commit of one repository. */
public interface Commit {
    String repository();
    String id();

    /** Writes the package folder's files, as committed, into an empty directory; refuses a folder with
     *  no package.yaml, a symbolic link, a submodule and a tree over the limits. */
    void write(Path dir) throws GitFailure;
}
```

`GitFailure` carries one reason of the formatter's list, and its message is the formatter's.

## What goes away

- `PUT /admin/packages/{name}`; the deploy is `POST /admin/deploy`.
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
| One deploy | the package and at most 100 packages in its dependency closure |
| A repository's name | one segment of at most 100 letters, digits, `.`, `_` and `-`, not `.` or `..` |
| A deploy's body | 4 KiB |

## Tests

- The contract suite of `PackageRepositories`, run on the fake and on JGit over `file://` repositories
  the test builds: the package folder found at the path, a folder with no `package.yaml`, symbolic links
  and submodules refused, the limits, reachability from the ref, a commit on another branch refused, a
  shallow source refused as incomplete, a ref refused in a deployed environment, a recorded commit read
  from the cache with the source gone and fetched when the cache lacks it, and the token rules (user
  information refused, the formatter never showing credentials or the transport's message, an
  authentication failure, a redirect to another host refused).
- `rail8 lock`: a dependency pinned by repository and commit; a dependency with uncommitted changes
  refused; a lock without repository or commit refused at a deploy.
- Deploys over JGit `file://` repositories, with a fake clock: a first deploy; a redeploy that swaps only
  the changed flows; a dependency fetched from its pinned commit while another version of it runs; a
  rollback by an earlier commit; a package name from a second repository refused; an undeploy refused
  while pinned and while its queue holds items; `?check=true` changing nothing.
- Restore: from a warm cache with the repositories gone; with a commit fetched; a pin restored from its
  own commit while its package runs a newer one; with the server unreachable (`FAILED`); with another
  base (`FAILED`).
- Several nodes over one Postgres, on a fake clock: a second deploy refused while one runs; a node
  behind the generation reconciling before it plans; the other node installing and undeploying at its
  next tick; a `FAILED` package restored at a later tick once its repository answers; a removed flow
  that takes no new message on any node and drains before it stops.

## The owner's choices

The owner chose:

- A git repository per package, rather than one repository for many packages.
- The same commit promoted from pool to pool, each gated by its ref, rather than a branch per
  environment.
- A deploy of one commit of one package repository.
- A commit even for the local run.
- A command that names the commit; a node that follows its ref by itself may come later as a timer
  calling the same deploy.
- The token in the node's environment now; the secret store planned for step 2 takes it later.

These were decided on the owner's behalf, and stand until the owner says otherwise:

- One base per pool, with repositories named by one segment under it; several bases may come later.
- One read-only token for the base, rather than one per repository.
- A dependency's files come from the commit its lock pins, whatever version of it runs.
- Until sign-in comes, whoever holds the admin token may deploy any package of the base; who may change
  a package and its release branch is the git host's to decide.

## What each stage builds

1. **The git seam.** `PackageRepositories` on JGit, its fake and its contract suite, the `RAIL8_GIT_*`
   settings, the token rules and the limits. Nothing calls it yet.
2. **A deploy from a folder.** `Deployments` deploys a folder instead of a zip; the upload unpacks into
   a folder first, and `rail8 test` deploys copies of folders in process. Nothing changes for a caller.
3. **A lock that pins commits.** `package.lock` pins each dependency's repository and commit;
   `rail8 lock` fills them in, and `rail8 validate` and `rail8 test` read dependencies from their pinned
   commits. The node reads the new lock and goes on resolving by content until the next stage.
4. **Deploy and restore from git.** The record and its migration, `POST /admin/deploy` with its order,
   dependencies from their pins, the restore from git, directories keyed by content digest, and the
   removals above. Every caller in the repository moves in the same change, so that each keeps
   working: `rail8 deploy` sends a repository and a commit, and CI, both compose files,
   `bin/run-adapter` and the quickstart deploy from git, as "The local run and the quickstart" says. A
   deployed environment still refuses deploys.
5. **Several nodes.** The pool's lock, the catching up before a plan, the generation, following and
   retiring; a deployed environment then takes deploys of commits reachable from its ref, and
   undeploys.

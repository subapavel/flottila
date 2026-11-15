# One Maven reactor with a wrapper, a separate npm build for `ui/`, three CI jobs and scripted gates

Status: accepted (2026-10-30, grilling #13); refined by ADR-0019 (ratchet commands, floors, PIT scope, ticket-gate budget) and ADR-0018 (`engine/oracle` in ticket-gate step 1, no jqwik)

ADR-0016 fixes the modules. This ADR fixes how they build, where the UI is built and served,
what CI runs, and the exact commands of the ticket gate and the spec gate (WORKFLOW §6). Nothing
here is committed in Phase 0. The build skeleton is the first ticket of milestone 1's first spec,
and this ADR is its specification.

## Maven

- One reactor, one version for all modules: `0.1.0-SNAPSHOT`. Nothing is published in
  milestone 1. Releasing to Central is a later spec (map #1, fog).
- **Maven Wrapper** 3.3.4 (`only-script`), pinned to **Maven 3.9.11**. Every command is
  `./mvnw …`. The enforcer requires `[3.9.11,4)` and Java `[25,)`. Maven 4 (still an RC) and
  3.10 come later as their own upgrade, with measurements.
- `.mvn/maven.config` turns on the resolver's inter-process locking:
  `-Daether.syncContext.named.factory=file-lock -Daether.syncContext.named.nameMapper=file-gav`.
  Worktrees share one `~/.m2/repository`. Maven 3.9.11's default, `rwlock-local`, locks only
  inside one JVM.
- **The repository points at Central.** The environments use the Google mirror of Central
  (`maven-central.storage-download.googleapis.com/maven2`), because Central rate-limits shared
  IPs (HTTP 429 to the VM, blocks of up to 24 h since May 2026). On the VM,
  `deploy/claude-cloud/setup.sh` writes `~/.m2/settings.xml` and exports `MVNW_REPOURL`. CI
  does the same in a workflow step.
- **Root POM**, the parent of everything:
  - maven-compiler 3.16.0 with `release 25`, `-Xlint:all -Werror`. Only the modules that read
    json-sKema or XmlSchema (automatic modules, F-7) add `-Xlint:-requires-automatic`. No `--enable-preview`
    in milestone 1;
  - maven-enforcer 3.6.3: Java and Maven versions, `dependencyConvergence`,
    `bannedDependencies`. It bans `org.openjdk.jmh` everywhere but `bench/jmh`, `net.sf.saxon`
    outside test scope, and the licence-excepted artifacts below outside test scope;
  - Spotless 3.10.4 with google-java-format **1.36.1** (`check` at `verify`). gjf 1.37.0
    breaks Spotless's glue (`NoSuchMethodError … Style.valueOf`), so the pin stays until
    Spotless supports it;
  - license-maven-plugin 2.7.1 `add-third-party` with `force=true`, `failOnBlacklist`,
    `failOnMissing`, `includedLicenses` = the `CLAUDE.md` list and `licenseMerges` for name
    variants. Only `system` scope is excluded. Exceptions are by `groupId:artifactId`, in the
    filter file `.mvn/licence-filters.txt` (`exclude gaPattern …`):
    - `io.smallrye.classfile:jdk-classfile-backport` (GPL-2.0 with CPE);
    - `org.graalvm.sdk:nativeimage` and `org.graalvm.sdk:word` (UPL-1.0);
    - `aopalliance:aopalliance` (public domain);
    - `javax.annotation:javax.annotation-api` (CDDL + GPL-2.0 with CPE);
    - `bench/jmh` has its own filter file that excepts `org.openjdk.jmh:*` only. Saxon-HE
      (MPL 2.0) is on the list and needs no exception.
  - A dependency of an excepted artifact is still checked. Maven plugins are tools, not
    dependencies: any OSI licence, never bundled.
- **npm licences** (owner ruling #39): the `ui` job runs a licence check over the whole
  `package-lock.json`, dev dependencies included, against the `CLAUDE.md` list. It fails on any
  other licence. The exceptions are per package, all of them dev-only build tools or data that are
  never bundled into the editor:
  - `argparse` (Python-2.0, PSF-2.0), through `js-yaml` in openapi-typescript and
    json-schema-to-typescript;
  - `caniuse-lite` (CC-BY-4.0), browser data for browserslist;
  - `minimatch` (BlueOak-1.0.0), glob tooling.
  A new exception needs an owner ruling.
- **Tests**: JUnit **6.1.3**, the version the Quarkus 3.40.1.1 BOM manages, imported through
  `junit-bom` in the root. Also AssertJ 3.27.7 and ArchUnit 1.5.1. There is no mocking library:
  ports have hand-written fakes. PIT runs on JUnit 6 (ADR-0019); jqwik is not used (ADR-0018).
- **`engine/pom.xml`**: surefire 3.6.1 on the module path (its default), plus the
  `io.quarkus*`, `jakarta.*` and `com.fasterxml*` bans. No `--add-modules kotlin.stdlib` (F-3).
- **`service/pom.xml`**: imports the Quarkus 3.40.1.1 BOM (the only place), `quarkus-junit`
  (not `quarkus-junit5`). `application.properties` sets `quarkus.http.test-port=0`, so parallel
  worktrees do not clash, and `quarkus.smallrye-openapi.store-schema-directory=../contract`.
  Every build writes `contract/openapi.json`, and `openapi.yaml` too, which is gitignored
  because it cannot be switched off. `quarkus-maven-plugin` is in the root's
  `pluginManagement`, so `./mvnw quarkus:dev` also works from the root.
- **`bench/jmh`**: only in profile `bench`. JMH 1.37 is shaded into `benchmarks.jar`
  (`org.openjdk.jmh.Main`). ADR-0021 sets the benchmarks.

## UI

- `ui/` is built by npm alone. Maven never calls npm, and the Java build needs no Node.
- **Dev**: `./mvnw -pl service -am quarkus:dev` (port 8080) plus `npm run dev` in `ui/` (5173).
  Vite's `server.proxy` sends `/api` to `127.0.0.1:8080`, so the browser sees one origin and
  needs no CORS (ADR-0014). No `install` is needed: dev mode resolves the engine from the
  reactor (`-am`).
- **Stack**: `npm ci && npm run build`, then `./mvnw -B -Pui -pl service -am clean package
  -DskipTests`. The `ui` profile adds `ui/dist` as `META-INF/resources`, so the jar serves the
  editor and `/api` from one origin. `clean` is mandatory with `-Pui`: otherwise the UI stays
  in `target/classes` and lands in the next jar. `npm run e2e:stack` starts this jar over
  `corpus/`.

## CI (GitHub Actions)

`.github/workflows/ci.yml` runs on `pull_request` and on `push` to `main` and to `t/**` (F-4), with `concurrency`
per ref and `cancel-in-progress`. It runs on `ubuntu-24.04`, with `actions/checkout@v7`,
`setup-java@v6` (Temurin 25, `cache: maven`, mirror step) and `setup-node@v7` (Node 22, npm
cache).

| Job | Steps |
| --- | --- |
| `backend` | `./mvnw -B -Pbench verify`; `git diff --exit-code contract/` |
| `windows` (`windows-latest`, #42) | `./mvnw.cmd -B -q -DskipTests verify` (ticket-gate row 2) |
| `ui` | `npm ci`; `npx playwright install --with-deps chromium` (from the pinned `@playwright/test` 1.63.0); `gen:types` + `git diff --exit-code src/generated`; `typecheck`, `lint`, `test`, `test:browser`, `e2e`; `build` → artefact `ui-dist` |
| `stack` (needs both) | download `ui-dist`; `./mvnw -B -Pui -pl service -am package -DskipTests`; `npm run e2e:stack` |

- `nightly.yml` (cron and `workflow_dispatch`): PIT on the class-C modules. #14 sets its scope
  and floors.
- `bench.yml` (`workflow_dispatch` only): JMH on a runner, for information. It is never a gate,
  because shared runners are too noisy for a regression threshold.
- Branch protection on `main` requires `backend`, `windows`, `ui` and `stack` (`windows`: owner
  ruling 2026-11-02, #48).

## Gates

Logs go to `.logs/<ticket>-<check>.log` (gitignored) and `ui/.logs/…`, not to `target/`,
because `./mvnw clean` deletes the root `target/`. `scripts/gate-ticket.sh <ticket> <modules>`
and `scripts/gate-spec.sh <spec>` run the steps below in order, and the verifier runs the
scripts.

**Ticket gate** (the ticket's worktree):

1. `./mvnw -B -pl <modules> -am verify`: the touched and upstream modules' tests, and for
   `service` ArchUnit and `@QuarkusTest`;
2. `./mvnw -B -q -DskipTests verify`: the whole reactor compiles, with Spotless, enforcer and
   licences;
   2b. `scripts/check-lf-checkout.sh`: every tracked file checks out byte-identical with
   `core.autocrlf=true` (#49); CI runs it in `backend`;
3. `git diff --exit-code contract/`, when the service or `contract/` changed;
4. in `ui/`, when it changed: `typecheck`, `lint`, `test`, `test:browser`, `e2e`, and
   `gen:types` + an empty diff;
5. the ratchet check. #14 defines the command.

**Spec gate** (the integration branch, one at a time):

1. `./mvnw -B clean verify -Pbench` (oracles and JaCoCo included) and an empty `contract/` diff;
2. the whole `ui` job, including `build`;
3. the stack: `-Pui` jar and `e2e:stack`;
4. ratchet regeneration (#14);
5. the JMH scenarios (ADR-0021) into `bench/RESULTS.md`, machine label `claude-cloud-4vcpu`.
   Regression is checked there against the baseline with the same label, never on CI.

The spec gate runs these in the order 1, 2, 5, 4, 3, not as numbered. Regen (4) reads the JMH
results of 5 and the JaCoCo and PIT reports of 1. Step 3's `clean`, which is mandatory with
`-Pui`, deletes those reports. The spec-1 review found this (#21).

## Concurrency (`AGENTS.md`)

At most three writers and `CLAUDE_CODE_MAX_CONCURRENT_SUBAGENTS=4` stay. The verifier runs at
most one service build at a time; engine scoped builds may overlap. Dev mode (port 8080) is
never part of a gate.

## Considered options

- **Quinoa 2.9.3**: rejected. It works on Quarkus 3.40.1.1 (observed), but:
  - its dev proxy loops on `/api` until `ignored-path-prefixes=/api` is set;
  - it runs `npm install`/`npm ci` itself on every clean `target/`;
  - it is built against 3.37;
  - `just-build` does not match its docs;
  - it adds 1–1.5 s to every package.
- **No wrapper, the machine's `mvn`**: rejected. CI runners and the VM would drift.
- **The mirror in a committed settings file** (`-s` in `.mvn/maven.config`): rejected. It would
  replace every developer's own settings.
- **JMH regression on CI**: rejected, because shared runners are too noisy.
- **A separate `architecture-tests` module**: rejected (ADR-0016).
- **Error Prone, SpotBugs, Checkstyle in milestone 1**: deferred. A mechanical rule becomes a
  check after `/retro` (WORKFLOW §12).
- **JUnit 5 in the engine**: rejected. The Quarkus BOM brings JUnit 6, and two platforms in
  one reactor would have to be kept apart.

## Consequences

- Observed on 2026-10-30 on the VM (4 vCPU, JDK 25.0.5, Maven 3.9.11), in a scratch reactor
  (`xdm`, `structure`, `structure-import`, `service`, `ui`, `bench/jmh`), not committed.
  Offline, warm repo, wall time:

  | Build | Time |
  | --- | --- |
  | `-pl engine/xdm -am verify` | 3.7–4.1 s |
  | `-pl engine/structure-import -am verify` | 3.8 s |
  | ticket gate step 2 (whole reactor, no tests) | 8.4 s |
  | `-pl service -am verify` | 14.3 s |
  | full `verify` | 14.5 s |
  | full `verify -Pbench` | 16 s |
  | `-T 4` | barely helps (13.7 s) |

  - Three engine scoped builds in parallel: 9–10 s each. Three service builds in parallel:
    35 s each.
  - Three builds downloading into one empty local repo at once passed, with every jar's SHA-1
    intact.
  - Dev mode answered after 8–10 s without `install`.
  - `openapi.json` came out byte-identical from two builds.
  - The licence check failed on an LGPL jar and on JMH, and passed with the exceptions.
  - The enforcer failed on JMH and on compile-scope Saxon.
  - ArchUnit is covered in ADR-0016.
- Not tried in the scratch reactor, so the skeleton ticket observes them first:
  - `dependencyConvergence` over the Quarkus tree. If the tree trips it, the rule moves to
    `engine/pom.xml` with a note here;
  - the enforcer ban on the licence-excepted artifacts outside test scope;
  - the CI workflows themselves.
- The figures go to `bench/RESULTS.md` as the baseline of build times. "A scoped build in
  seconds" means an engine ticket gate under 10 s with three in parallel, and one with the
  service under 40 s.
- The owner pastes the new `deploy/claude-cloud/setup.sh` into the environment's setup script.
  Until then an agent uses a scratch `settings.xml` (`agent/NOTES.md`).

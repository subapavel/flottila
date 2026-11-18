# Workflow

The way of working is the repository's: `AGENTS.md` at the root. This file keeps the mapping's
checks, ratchet and CI as the earlier process ran them. Read the roles in it (verifier, architect,
ticket and spec gates, integration branch) as "the session": it runs the commands itself. On every
change only the build with its tests blocks (`.github/workflows/mapping-ci.yml`); coverage floors,
mutation tests, the ratchet, the stack suite and Windows run nightly as a report
(`.github/workflows/mapping-nightly.yml`).

## 6. Checks, ratchet, CI

Decided by ADR-0017 (build, CI, gates) and ADR-0016 (modules). Commands run in `mapping/` of
the worktree; the Maven wrapper is at the repository's root, so from `mapping/` it is `../mvnw`.
The mapping studio is part of the Rail8 UI, the repository's `ui/` (from `mapping/` it is `../ui`).
Logs go to `.logs/<ticket>-<check>.log` and `../ui/.logs/<ticket>-<check>.log`, never to `target/`
(`../mvnw clean` deletes it). The milestone-1 skeleton adds
`scripts/gate-spec.sh <spec>`. The ticket gate below is run by hand, in this order.

**Ticket gate** (verifier, per ticket, in its worktree; `<modules>` = the touched module
directories, comma-separated, e.g. `engine/xdm,engine/ir`):

| # | Command | Log | When |
| --- | --- | --- | --- |
| 1 | `../mvnw -B -pl <modules>,engine/oracle -am verify` | `.logs/<t>-build.log` | always: tests and JaCoCo floors of touched and upstream modules, the whole golden corpus and the differential oracle (ADR-0018); `service` adds ArchUnit and `@QuarkusTest` |
| 1b | `../mvnw -B -pl <class-C modules among <modules>> -am test-compile org.pitest:pitest-maven:mutationCoverage`, then `scripts/pit-summary.sh ratchet/mutation.tsv <modules>` | `.logs/<t>-pit.log` | a class-C engine module is touched (#67): a score below its `ratchet/` floor is `EXECUTED_FAIL`; none touched prints `NOT_RUN`. The last lines of the script are `module killed/generated score% floor N% ok|DROP`, one per module |
| 2 | `../mvnw -B -q -DskipTests verify` | `.logs/<t>-compile-all.log` | always: the whole reactor compiles; Spotless, enforcer, licences |
| 2b | `scripts/check-lf-checkout.sh` | `.logs/<t>-lf-checkout.log` | always: every tracked file checks out byte-identical on Windows (`.gitattributes`, #49) |
| 3 | `git diff --exit-code contract/` | — | the service or `contract/` changed |
| 4 | in `../ui`: `npm run licences`, `typecheck`, `lint`, `test:coverage`, `test:browser`, `e2e`; `npm run gen:types && git diff --exit-code src/mapping/generated` | `../ui/.logs/<t>-<check>.log` | `../ui` or `contract/` changed |
| 5 | `java scripts/ratchet.java check` | `.logs/<t>-ratchet.log` | always: no listed case failing or missing, no coverage below `ratchet/` of the integration branch; unbuilt modules print `NOT_RUN` (ADR-0019) |

Budget on the VM (4 vCPU), three gates in parallel: an engine ticket about 110 s for step 1 (it
builds the whole engine because of `engine/oracle`), a service ticket about 80 s alone; step 2 about
24 s alone (measured on the spec-1 code, `bench/RESULTS.md`, F-13; the earlier 30 s, 60 s and 15 s
came from #22 on a scratch reactor, F-5). Note of 2026-11-10 (owner, #181): cutting this time is a
process spec after milestone 1, not part of speed work. The verifier runs at
most one gate that builds `service` at a time.

**Spec gate** (verifier, integration branch, one at a time):

1. `../mvnw -B clean verify -Pbench` (all tests, oracles, JaCoCo floors, bench compiles) and
   `git diff --exit-code contract/`;
   1b. PIT on the class-C modules the spec touched:
   `../mvnw -B -pl <class-C modules> -am test-compile org.pitest:pitest-maven:mutationCoverage`
   (`pit.skip` skips the modules without a mutation floor, F-5) (floor 80 %,
   ADR-0019) → `.logs/<spec>-pit.log`; `gate-spec.sh` ends with one line per module
   (`module killed/generated score% floor N% ok|DROP`) and the corpus and differential case counts (#67); above 10 minutes it narrows to the modules class-C tickets
   touched;
2. in `../ui`: `npm ci`, every step of ticket-gate row 4, `npm run build`;
3. the stack: `../mvnw -B -pl service -am package -DskipTests`, then `npm run e2e:stack` in `../ui`;
4. `java scripts/ratchet.java regen --all`: every report must be present, a drop fails, the
   verifier alone commits `ratchet/` to the integration branch;
5. the JMH scenarios (ADR-0021):
   `java -jar bench/jmh/target/benchmarks.jar -foe true -f 3 -wi 3 -i 5 -w 1s -r 1s -prof gc -rf json -rff .logs/<spec>-jmh.json`
   (about 5–6 minutes; large throughput in forks with a 1 GB heap, and `<mapping>/large/heap`
   in its own fork with `-Xmx128m`; one invocation suffices, `-f` keeps the fork's `jvmArgsAppend` (#185); ADR-0021 note of
   2026-11-10), plus the `ui/tree-20128/scroll` Playwright scenario (`../ui/e2e/tree-20128.spec.ts`).
   - The verifier names the machine: `MACHINE_LABEL=claude-cloud-4vcpu scripts/gate-spec.sh <spec>`.
     Only with that label, and `nproc` = 4, does step 5 judge
     (`java scripts/ratchet.java bench .logs/<spec>-jmh.json`) and step 4 write `bench.tsv`.
     Without it, step 5 measures and prints, the judgement is `NOT_RUN`, and nothing is written to
     `ratchet/` or `bench/RESULTS.md`.
   - Results go to `bench/RESULTS.md` with machine label `claude-cloud-4vcpu`, the date and the
     commit, written by the architect from the verifier's output. The score is the median of the
     per-fork means.
   - A scenario worse than `ratchet/bench.tsv` by more than its tolerance (20 % for time and
     throughput, 10 % for B/op), or a missed target, is measured twice more: the judgement prints
     the benchmarks as one include regex, the gate re-runs only them into `.logs/<spec>-jmh-2.json`
     and `.logs/<spec>-jmh-3.json` and judges the median of the three (ADR-0021, Notes of
     2026-11-11). The gate fails if the median is still worse, if a GATE check 7 target is missed,
     or if a `bench.tsv` row has no result. A regression the median still shows goes to the owner
     as a finding, because it can come from the host (F-36).
   - `regen --all` reads the final results too, so the verifier runs this step before
     step 4. A baseline moves only on a gain beyond the tolerance (ADR-0019).

Run order: 1, 1b, 2, 5, 4, 3. Regen reads the reports of 1, 1b and 2 (the UI's coverage).

**CI** (GitHub Actions, the repository's `.github/workflows/mapping-ci.yml`, every PR and push to
`main` that touches `mapping/**`, the root `pom.xml`, `.mvn/**`, `mvnw*`, the root `.gitattributes`, the
workflow or the nightly workflow - and, until MAP-12c, four scheduled runs a day of the backend job alone;
the old repository required its four jobs on `main`, here `mapping-ci` is not a required check (D-164 (6))
and the required checks are the owner's to set). Its steps run in `mapping/` with the repository's wrapper (`../mvnw`). The commits' sign-off is the
root `ci.yml`'s `dco` job:

- `mapping · backend`: every script test in `scripts/test/` (`function-families*-test.sh`, #69;
  `ratchet-mutation-test.sh`, `ratchet-moved-test.sh`, `ratchet-bench-test.sh`, `ratchet-baseline-test.sh`,
  `gate-spec-bench-test.sh`, and those that need no JDK: `pit-summary-test.sh`, `gate-spec-default-test.sh`, `gate-log-test.sh`,
  `handover-test.sh`, `ratchet-ci-test.sh`, `ratchet-ci-wiring-test.sh`), then `../mvnw -B -Pbench verify`,
  `git diff --exit-code contract/`, `scripts/check-lf-checkout.sh` and last `scripts/ratchet-ci.sh push`: the
  ratchet check against the last commit it passed on (ADR-0019, "On GitHub"; it reports until the date in
  `scripts/ratchet-enforced-from`, then it fails the job);
- `mapping · windows`: `../mvnw.cmd -B -q -DskipTests verify` (the whole reactor compiles; Spotless, enforcer, licences);
- the Rail8 UI with the studio is checked by the repository's `ui-ci.yml`: licences, generated types
  with an empty diff, `typecheck`, `lint`, `test`, `test:browser`, `build`;
- `mapping-nightly.yml` (`ui · end-to-end`): `npm run test:coverage` and `npm run e2e` in `ui/`;
  (`mapping · stack`): the service's jar, then `npm run e2e:stack` in `ui/`.
- `mapping-nightly.yml` (`mapping · mutation`): PIT on `main` over the class-C modules `xdm`, `ir`, `runtime`, `serializer`,
  `mapping`, `xslt-export`, `api`, `udf-api`, `udf`, failing below 80 % (ADR-0019), then `scripts/ratchet-ci.sh nightly`: each module's surviving mutants against `ratchet/mutation.tsv`; it commits nothing. `mapping-bench.yml` (`mapping · jmh`): manual JMH,
  informative only; benchmark regression is a spec-gate check on the VM, never on CI.
- Runners use Temurin 25 and Node 22 and fetch Maven artefacts through the Google mirror of
  Central with the Maven cache (ADR-0017).

**Ratchet** (`ratchet/`, ADR-0019, format in `ratchet/README.md`): oracle pass lists, coverage
and mutation per module, benchmark baselines. Every push is checked on GitHub against the last
commit the check passed on, and the verifier regenerates the files at every task commit that
touches `mapping/` (ADR-0019, "On GitHub"; the repository's D-164). The old gates checked against
the integration branch's copy and regenerated at the spec gate (rows 1b and 5 above). A drop is a failure; a rise is recorded at regeneration. Writers never touch it.

**Floors** (fixed in the build, ADR-0019): JaCoCo lines ≥ 90 % and branches ≥ 80 % on `xdm`, `ir`,
`runtime`, `serializer`, `mapping`, `xslt-export`, `api`, `udf-api`, `udf`; lines ≥ 80 % on `structure`,
`structure-import`, `service`; PIT ≥ 80 % on the nine class-C modules; Vitest per-glob lines
≥ 90 % on `../ui/src/mapping/model/**` and `../ui/src/mapping/studio/store/**` (`npm run test:coverage`
= `vitest run --coverage`).

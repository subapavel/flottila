# Build, CI and gates

Status: accepted (2026-11-10, owner, issue #14 "How is the repo laid out and built?"); replaces `docs/WORKFLOW.md` §6 together with ADR-0020; amended by ADR-0020 (issue #15); amended by ADR-0023 (issue #17: the fallbacks fire at any spec gate); amended by F-19 (spec 1 grilling: who measures the gate steps); amended by F-27 (PIT leaves the ticket gate); amended by F-28 (owner at PR #48: step-1 target, spec-gate PIT target, trailer waiver); amended by F-46 (#89: the ticket gate's step 1 adds `-Pcompile-oracle`); F-38 require path amended by #84; amended by F-84 (spec-gate PIT: the two slow test classes leave PIT's test set, oracle's classpath join, `threads`)

- **Toolchain:**
  - Maven wrapper 3.3.4 (`only-script`) with Maven 3.9.x, `--release 25`, `-Xlint:all -Werror`, no
    `--enable-preview`, and `<proc>full</proc>` for JMH's annotation processor.
  - The Google mirror lives in `~/.m2` and `MVNW_REPOURL`, never in the repo.
  - Plugins and test libraries are pinned by the build skeleton ticket at the versions current on
    Maven Central that day, each recorded as a line in the evidence comment (WORKFLOW §7). The
    adviser saw these on 2026-11-10: Maven 3.9.16, compiler 3.16.0, enforcer 3.7.0, surefire 3.6.1,
    JaCoCo 0.8.16, pitest-maven 1.30.2, pitest-junit5-plugin 1.2.3, spotless 3.11.0,
    license-maven-plugin 2.8.0, JUnit 6.1.3 and AssertJ 3.27.7.
  - There is no mocking library.
- **Coverage and mutation of `query-core` and `jq-core` come from every test in the reactor,
  oracle cases included.** JaCoCo `report-aggregate` runs in `oracle`. PIT `crossModule` there mutates the
  upstream modules but runs only `oracle`'s own tests (pitest-maven 1.30.2,
  `findOccupiedTestPackages`), so the unit tests of `query-core` and `jq-core` join through
  `additionalClasspathElements` and `targetTests`, or through test-jars; the skeleton ticket proves
  which. **F-84 (#190, #191, #192): the unit tests' join through `additionalClasspathElements` is gone; `ratchet.java` unions each module's own kills with the crossModule kills instead. oracle's run mutates only the mutants each module's own run left alive (every status outside ratchet.java's killed set), through the `pit-filter` module's `OWN_SURVIVORS` feature, and it does not mutate `oracle` or `jq-cli` classes (`excludedClasses`). Owner, 2026-11-14.**
  `ratchet.java` checks the floors on the aggregate. The floor numbers and the class-C list belong
  to "How do the oracles and the ratchet work?". PIT on JDK 25 with JUnit 6 and `crossModule` is
  NOT_RUN; the skeleton ticket runs it first.
- **The ticket gate** is `scripts/gate-ticket.sh <ticket> <modules> [ref]`. Every step logs to
  `.logs/<ticket>-<check>.log`.
  1. `./mvnw -B -Pbench,compile-oracle verify` (the profile since #89, F-46). The oracle needs every module, so this is the whole reactor. The
     target is under 60 s with three gates in parallel. **F-28 (owner): under 120 s with three gates
     in parallel; above that, first a step-1 profile, then no `-Pbench` unless the ticket names
     `bench`, by a finding with no owner round.**
  2. PIT on only the classes the ticket changed (`-DtargetClasses` from `git diff <ref>`), against
     the floor. `failWhenNoMutations` is off, for tickets that change only records or interfaces.
     The target is under 3 minutes with three gates in parallel. If spec 1's last ticket, or any
     later spec gate, measures more, PIT leaves the ticket gate by a finding, with no new owner
     round (ADR-0023, F-17). **Applied by F-27 (#40, #44): the ticket gate no longer runs PIT;
     PIT runs only in the spec gate.**
  3. The LF check, the trailer check and the word check, each under about 2 s. The trailer check
     skips only the full SHAs in `scripts/trailers-waiver.txt`; a new line there is owner-only (F-28).
  4. `java scripts/ratchet.java check --require-compile` (the flag since #89: a missing compile report fails; CI's plain `check` prints NOT_RUN).

  Spec 1's last ticket measures each step alone and with three gates in parallel, once cases pass,
  and records the numbers in `bench/RESULTS.md` (F-19).
- **The spec gate** is `scripts/gate-spec.sh <spec>`:
  1. `clean verify -Pbench`;
  2. PIT on whole class-C modules (with oracle's crossModule run, #45). **Target (F-28): under 45 min
     alone on `claude-cloud-4vcpu`; above that, ADR-0020's seed fallback by a finding.** **F-84: the seed fallback does not reach a module's own tests; there, the slow test classes leave PIT's test set first, then `threads` 2.**
  3. JMH (`benchmarks.jar -prof gc -rf json '\.Gates\.'`): the `Gates` benchmarks only, so a
     diagnostic class never reaches `bench.tsv` (#102, ADR-0019, ADR-0024);
  4. `ratchet.java regen --all`.
- **CI** (`ci.yml`) runs on pull requests and on pushes to `main` and `spec/**`. It cancels per
  ref, runs on ubuntu-24.04 with Temurin 25, and times out after 30 minutes. Its jobs:
  - `trailers`: the script tests and a range check, with no JDK;
  - `build`: jq-ref 1.8.2 checked by sha256, then `./mvnw -B -Pbench verify`, the word check and the
    LF check;
  - `windows`: `mvnw.cmd -B -q -DskipTests verify`, which proves only that checkout and build work.

  `trailers`, `build` and `windows` become required on `main` from spec 1: the owner sets this in
  branch protection when the skeleton ticket adds `ci.yml` (`main` has no required check today). `nightly.yml` runs PIT on
  the class-C modules and uploads the reports, committing nothing. `bench.yml` is manual and
  informative only.
- **Scripts ported from r8-mapping:** `gate-ticket.sh`, `gate-spec.sh`, `ratchet.java` and
  `pit-summary.sh`, with their tests. Their contract, UI, stack and moved-coverage parts are
  dropped. The oracle names are jqtest, differential, compile, backend and json
  (ADR-0020). One file lists the class-C
  modules, and all three scripts read it. `function-families.java` is not ported.
- **The VM setup** (`deploy/claude-cloud/setup.sh`) adds `javap`, `jdeps` and `jlink` to its
  alternatives and checks jq-ref's sha256.
- **Left to "Interpreter or bytecode first, and what speed must milestone 1 show?":** the JMH
  arguments (forks, heap), the scenario list, and which backends the oracle and the module-path
  cases run.

## Considered options

- **PIT on the whole touched module per ticket** (R8's step 1b): a guessed 5–15 min per
  gate, three at a time.
- **No PIT in the ticket gate:** weak tests would show only at the spec gate.
- **Coverage from each module's own tests:** backend and builtin tickets would hand-write tests up
  to the floor, and a class A/B builtin whose red is oracle cases would score 0 %.
- **Rail8's separate compile-all step:** it repeats step 1.
- **Maven 4, `-T 4`, JMH on CI, Error Prone/SpotBugs/Checkstyle, a committed mirror:** unmeasured
  or rejected for R8 ADR-0017's reasons.

## Amendment (ADR-0020, issue #15)

- Ticket gate: `gate-ticket.sh` passes the ticket's required case ids to `-Doracle.require` from
  `ORACLE_REQUIRE`, its only require path (F-38 as amended by #84), runs
  `oracle-record.sh --verify` on the `.rec` files the ticket changed, and reads the floors from
  `quality/floors.tsv`.
- CI `build` adds `java scripts/ratchet.java check` and `oracle-record.sh --verify` on all
  recordings.
- The spec gate's step 4 is `regen --all`. The verifier also runs `regen --oracles` after every merge.
- Benchmarks: ADR-0019 sets the JMH arguments and scenarios. The oracle and the module-path cases
  run on the interpreter in milestone 1 (ADR-0018).

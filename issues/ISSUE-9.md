# ISSUE-9 — the mapping engine's licences, and which root rules bind `mapping/`
Task: BRAND-3
Date: 2026-11-15 (F-54, D-153)

## What is needed
The owner's word on each item below, and one patch applied by the owner. The mapping engine is in
this repository with its history (D-150). Its own rule file allowed more licences than the root's,
and its service is built on other rules than the platform's design rules. A licence is the owner's
word (CLAUDE.md, Working rules) and no session edits a rule file, so every item states the default
that holds until the answer. The facts are in `agent/work/BRAND-3-scout-2.md`, blocks C and E.

A. Licences. The root list is Apache 2.0, MIT, BSD, MPL 2.0, EPL; "AGPL, GPL, BSL, SSPL → `ISSUE`,
never linked".
1. Four more licences are on the mapping's list, accepted by the owner in its repository: ISC and
   0BSD (the editor's npm packages, `d3-*` and `tslib` — the mapping's ADR-0012), MIT-0
   (`reactive-streams`) and EDL 1.0 (`jakarta.activation`, `jakarta.xml.bind`) from the Quarkus
   dependency tree (ADR-0014). Default: left as imported, for `mapping/` only; nothing outside
   `mapping/` takes a dependency under them.
2. The mapping also has exceptions per artifact (its ADR-0017; `mapping/licence-filters.txt`,
   `mapping/bench/jmh/licence-filters.txt`, `mapping/ui/scripts/licence-exceptions.json`). Five Java
   artifacts are allowed at test scope only — the mapping's enforcer bans each at compile, runtime
   and provided scope (`mapping/pom.xml:207-222`), so by the POM none is in a built jar:
   `io.smallrye.classfile:jdk-classfile-backport` (GPL-2.0 with the Classpath Exception),
   `javax.annotation:javax.annotation-api` (CDDL or GPL-2.0 with the Classpath Exception),
   `org.graalvm.sdk:nativeimage` and `:word` (UPL-1.0), `aopalliance` (public domain). JMH (GPL-2.0
   with the Classpath Exception) is in `mapping/bench/jmh` only. Three npm tools are dev-only
   (`argparse` Python-2.0, `caniuse-lite` CC-BY-4.0, `minimatch` BlueOak-1.0.0 — ruling #39 of the
   old repository). The filter file says "the owner accepted"; ADR-0017 cites no ruling for the Java
   artifacts. The question: does the root's "GPL … never linked" cover GPL-2.0 with the Classpath
   Exception at test scope and in a benchmark module? Default: the exceptions are left as imported —
   neither accepted nor removed by a session — for `mapping/` only, and frozen: an addition is an
   issue. The mapping's build runs its enforcer and its licence check (seen in
   `agent/work/BRAND-3-s3-verify-counts.txt`); what is in a built jar was not inspected.
   While this item is open, nothing built from `mapping/` (a jar, an image, the UI bundle, a Maven
   deploy) is published, attached to a release or started from `deploy/`. That is today's state —
   the mapping's workflows publish nothing — made a rule (D-153).

B. Rules.
3. The root's design rules were written for the platform: Vert.x 5.1, a socket only through the
   Component SPI, no message refused for its size, one virtual thread per message. The mapping's
   service is Quarkus 3.40 on Vert.x 4.5, has its own HTTP listener on 127.0.0.1, reads a request
   body whole and refuses one above 10 MB, and has no authentication (the mapping's ADR-0014,
   ADR-0024). Its engine modules depend on none of that (F-54). Default (D-153, ARCHITECTURE §12):
   the root's design rules bind the whole repository except `mapping/`; `mapping/` is bound by its
   ADRs and `mapping/CLAUDE.md`; nothing outside `mapping/` depends on, packages or starts anything
   built from it until the embedding is designed (MAP-1, a finding and the owner's brainstorm
   first); until then work under `mapping/` closes, fixes and observes (MAP-2, -3, -4, -6, -7) and
   adds no dependency, licence exception, listener or endpoint, and its next specs (MAP-5) start
   after the owner's session on each. An alternative the owner may prefer: the engine's modules
   (`mapping/engine/**`, no Quarkus) under the root's rules and licence list now, the wider list for
   the service, the UI and the bench only — whether the engine's dependency closure passes the root
   list today is not checked (MAP-1's first step).
4. The root's test rules that name platform parts and tools — the coverage floors per module, PIT on
   `api` and `core`, jqwik for the batcher, admission and the WorkQueue, `bench/RESULTS.md`, and "a
   blocked platform thread in a test is a failure", which is the platform's threading model —
   against the mapping's own: the oracle and the ratchet, no jqwik (its ADR-0017 to ADR-0019), a
   service on worker threads (its ADR-0014, ADR-0020), two POSIX-only test classes with cases under
   `assumeTrue`. Default (D-153): every other line of the
   root's Tests, Working rules and Context hygiene binds both areas; the lines named here are the
   platform's and the mapping's own stand in their place; the `assumeTrue` cases that exist stay and
   no new skipped case is added.

C. Rule files and the session's set-up.
5. `mapping/CLAUDE.md` was cut to the engine's technical rules (37 lines): the process lines went,
   because the process is the root's (D-151); nothing was added but the pointer to D-153's freeze
   and standstill (item 3). The file as it was is
   `mapping/docs/archive/CLAUDE-until-2026-11-15.md`. Say if a rule was dropped that should hold.
6. The mapping's cloud set-up script (`deploy/claude-cloud/setup.sh`, removed; in the history) did
   three things the root's `agent/cloud/setup.sh` does not: it wrote a mirror of Maven Central into
   `~/.m2/settings.xml` (Central had answered 429 to the VM — the mapping's ADR-0017), exported
   `MVNW_REPOURL`, and enabled the Context7 server in the session's own settings. None was carried
   over: the builds of both areas pass here without the mirror, and a session does not change its
   own settings. Say if any of the three should come back.
7. `.claude/agents/implementer.md:38` tells the implementer to run `mvn -B -ntp verify`. Run at the
   root, that now builds both areas. Packets name the command with its pom (`-f platform/pom.xml`,
   `-f mapping/pom.xml`) and the packet is what the implementer follows; the agent's file is the
   owner's. Say if the line should name the pom. (It is context of a hunk of the OPS-1 patch, so it
   is not in the patch below.)

D. The patch. `git apply agent/patches/BRAND-3-steering.patch` — the root `CLAUDE.md` gains one
paragraph: under `mapping/` the Design rules do not apply until the embedding is designed;
`mapping/CLAUDE.md` holds the engine's technical rules and its licence list with the four licences
of item 1 for `mapping/` only; the exceptions of item 2 are not ruled by it; Tests, Working rules
and Context hygiene bind both areas. It states the licence list of item 1 and the scope of items 3
and 4, and nothing else: the separation, the standstill and the licence freeze are not in it —
D-153 is their rule, before and after the patch is applied. An answer that differs changes the
paragraph. It applies before or after `agent/patches/OPS-1-steering.patch` (ISSUE-8). No session
applies it; until the owner does, the root `CLAUDE.md` is silent on `mapping/` and D-153 is the
rule for the scope too.

## Why the agent cannot decide it
Licences are the owner's (CLAUDE.md, Working rules: money, credentials, licences → an issue), and a
rule file is edited by the owner only. Which of the root's design rules reach the mapping decides
what the embedding may look like — an expensive-to-reverse choice that is the owner's to make with
MAP-1, not a session's.

## What was tried
The mapping's ADR-0012, ADR-0014, ADR-0017, its POM and its filter files were read (scout,
2026-11-15; the opponent's check of the POM, `agent/work/BRAND-3-s3-advisor.md`). No licence was
added to or removed from any list by a session. Nothing is blocked now; MAP-1 (how a flow runs a
mapping) does not start before items 1-3 are answered, because a platform module that depends on an
engine module takes the engine's dependency closure with it.

## Default if the owner does not answer
The defaults stated on each item: the mapping keeps its list and its own licence check, frozen; the
platform's list is unchanged; nothing outside `mapping/` depends on it and nothing built from it is
published; the patch stays unapplied.

## Owner's answer
2026-11-15, in the session, on A: "pokud je potřeba upravit licence, tak je upravíme, v čem se pravidla
liší?" - if the licences need changing they will be changed; how do the rules differ? The differences
were given in the session, items 1 to 4 above in plain words. Asked back, two yes/no, not answered yet:
(a) the four licences of item 1 for the whole repository, not for `mapping/` only; (b) GPL-2.0 with the
Classpath Exception, CDDL and UPL at test scope and in the benchmark module only, never in shipped
code. The architect recommended yes to both. Until both are answered the defaults above hold.
If (a) is yes the patch of D is written again - its sentence says "for `mapping/` only" - so the owner
was told to apply `agent/patches/OPS-1-steering.patch` now and this one after the answer.
The architect's advice on the required checks for the `mapping · …` jobs, given the same day: not
while work is pushed directly to `main`. The workflow runs only on changes under `mapping/`, so as
required checks its jobs would never report on any other push.

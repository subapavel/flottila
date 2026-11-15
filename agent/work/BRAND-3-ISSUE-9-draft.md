# ISSUE-9 — the mapping engine's licences, and which root rules bind `mapping/`
Task: BRAND-3
Date: 2026-11-15

## What is needed
The owner's word on each line below, and one patch applied by the owner. The mapping engine is in
this repository with its history (D-150). Its own rule file allowed more licences than the root's,
and its service is built on other rules than the platform's design rules. A licence is the owner's
word (CLAUDE.md, Working rules) and no session edits a rule file, so every line states the default
that holds until the answer. The facts are in `agent/work/BRAND-3-scout-2.md`, blocks C and E.

A. Licences. The root list is Apache 2.0, MIT, BSD, MPL 2.0, EPL; "AGPL, GPL, BSL, SSPL → `ISSUE`,
never linked".
1. Four more licences are on the mapping's list, accepted by the owner in its repository: ISC and
   0BSD (the editor's npm packages, `d3-*` and `tslib` — the mapping's ADR-0012), MIT-0
   (`reactive-streams`) and EDL 1.0 (`jakarta.activation`, `jakarta.xml.bind`) from the Quarkus
   dependency tree (ADR-0014). Default: they hold under `mapping/` only; no module under `platform/`
   takes a dependency under them.
2. The mapping also has exceptions per artifact (its ADR-0017, `.mvn/licence-filters.txt`,
   `mapping/bench/jmh/licence-filters.txt`, `mapping/ui/scripts/licence-exceptions.json`):
   `io.smallrye.classfile:jdk-classfile-backport` (GPL-2.0 with the Classpath Exception; in the
   service's Quarkus tree, outside test scope), `javax.annotation:javax.annotation-api` (CDDL or
   GPL-2.0 with the Classpath Exception), `org.graalvm.sdk:nativeimage` and `:word` (UPL-1.0),
   `aopalliance` (public domain), JMH (GPL-2.0 with the Classpath Exception, bench only) and three
   dev-only npm tools (`argparse` Python-2.0, `caniuse-lite` CC-BY-4.0, `minimatch` BlueOak-1.0.0 —
   ruling #39 of the old repository). The filter file says "the owner accepted"; ADR-0017 cites no
   ruling for the Java artifacts. The question: does the root's "GPL … never linked" cover GPL-2.0
   with the Classpath Exception? Default: the exceptions stand as the mapping's files state them, for
   `mapping/` only, enforced by the mapping's own licence check; nothing under `platform/` links an
   artifact under any of them.

B. Rules.
3. The root's design rules were written for the platform: Vert.x 5.1, a socket only through the
   Component SPI, no message refused for its size, one virtual thread per message. The mapping's
   service is Quarkus 3.40 on Vert.x 4.5, has its own HTTP listener on 127.0.0.1, reads a request
   body whole and refuses one above 10 MB, and has no authentication (the mapping's ADR-0014,
   ADR-0024). Its engine modules depend on none of that. Default (D-153): the root's design rules
   bind `platform/`; `mapping/` is bound by its ADRs; no platform module depends on a mapping module
   and no node ships the mapping's service until the embedding is designed (MAP-1, a finding first —
   ARCHITECTURE §12).
4. The root's test rules that name platform parts and tools — the coverage floors per module, PIT on
   `api` and `core`, jqwik for the batcher, admission and the WorkQueue — against the mapping's own:
   the oracle and the ratchet, no jqwik (its ADR-0017, ADR-0018), two POSIX-only cases under
   `assumeTrue`. Default: the general rules hold everywhere (red first; no weakened, disabled or
   sleeping test; no claim without an observation); floors and tools are each area's own.

C. The patch. `git apply agent/patches/BRAND-3-steering.patch` — the root `CLAUDE.md` gains one
paragraph: work under `mapping/` also follows `mapping/CLAUDE.md`, the engine's technical rules and
its licence list, which adds the four licences of line 1 for `mapping/` only. It states the defaults
of lines 1 and 3 and nothing else; an answer that differs changes the paragraph. It applies before or
after `agent/patches/OPS-1-steering.patch` (ISSUE-8). No session applies it.

## Why the agent cannot decide it
Licences are the owner's (CLAUDE.md, Working rules: money, credentials, licences → an issue), and a
rule file is edited by the owner only. Which of the root's design rules reach the mapping decides
what the embedding may look like — an expensive-to-reverse choice that is the owner's to make with
MAP-1, not a session's.

## What was tried
The mapping's ADR-0012, ADR-0014, ADR-0017 and its filter files were read (scout, 2026-11-15). No
licence was added to or removed from any list by a session. Nothing is blocked now; MAP-1 (how a
flow runs a mapping) does not start before lines 1-3 are answered, because a platform module that
depends on an engine module takes the engine's dependency closure with it.

## Default if the owner does not answer
The defaults stated on each line: the mapping keeps its list and its own licence check; the
platform's list is unchanged; the two areas are built by separate workflows and neither depends on
the other; the patch stays unapplied.

## Owner's answer

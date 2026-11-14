# test-author memory

Durable facts only (AGENTS.md, Memory). No ticket state. Under 120 lines.

## Carried over from r8-mapping (2026-11-10; observed there, re-verify on first use here)

- `-Xlint:all -Werror` applies to test sources: no unchecked casts, no generic varargs without
  `@SafeVarargs`, no static call through an instance.
- A test-compile failure in a module skips every module that depends on it in the same run.
- Bash refuses some compound commands (heredocs, `git` in loops) in a worktree: write files with
  the Write tool, run plain commands.
- Validating expectations before the code exists: compile the tests against stand-in types plus a
  reference implementation in the scratchpad (never committed).
- A red that can fail for an unrelated reason is not the red: also show the behaviour red.
- Pin a literal expected value; never recompute it in the test the way the code computes it.
- (#24) `assertThatThrownBy(..).as(..)` loses the description when nothing is thrown; use `assertThatExceptionOfType(T).as(..).isThrownBy(..)`.
- (#24) JDK 25 `Class.toGenericString()` prints sealed/non-sealed/enum/record; record equals/hashCode/toString are `public final`; enum `values()`/`valueOf` are not synthetic, `$values`/`$VALUES` are.
- (#24) A surefire second execution with `useModulePath=true` runs a jq-cli test with jq-core as a named module.
- (#24) To prove red tests can go green without touching the branch: `cp -r` the worktree to the scratchpad, `rm` its `.git` file, write a stand-in, build with `-Dchecks.skip=true`.
- (#24) Stub tests lose entries as bodies are built; deleting an entry with its Stub is not weakening a test — say so in the test's Javadoc.
- (#24) A null-argument test must set every other argument so that only the null guard can produce the expected exception.
- (#32) A stale-view test whose callback touches a view is vacuous: the throw comes from the callback, not the accessor under test. Make later callback steps touch nothing.
- (#32) A guard-killing input can be absorbed by EOF (`"\u12x"`, `[tru]`): prove each kill with a hand mutant and add a twin that does not end at EOF.
- (#32) In bash, `printf '-x'` fails (use `printf --`); `\0002` is not NUL (use `\x00`). A scratchpad reference implementation validates red tests in about 10 minutes.
- (#32) jq's depth rule: reject `[`/`{` when the parser stack (open containers plus open key values) already holds 10 000 (F-22).
- (#33) Search the whole repo for older tests that use the construct you are building as their Stub witness (here `compile(".")`, Main on a `.` case); list them as conflicts in the hand-back.
- (#33) AssertJ 3.27.7 deprecates `hasCauseReference` (-Werror rejects it); a required-case file lives at oracle/src/test/resources/require/<ticket>.txt.
- (#34) Every row of an expected-value table needs its own recording or cited jq-ref run; a whitespace-skip set needs the closing byte at a block cut followed by each whitespace byte.
- (#34) Case ids are `<family>/<name>` (ADR-0024's `bench.G1.1k` is `bench/G1.1k`); long inputs go inline as escaped `stdin` lines.
- (#61) jq-ref's syntax-error carets cover exactly the unexpected token: an oracle for lexer splits.
- (#61) `new BigDecimal(lexeme)` accepts `.5`, `1.` and `1.e2`; it matched jq-ref on 1000 seeded literals.
- (#61) The test-author writes `require/<ticket>.txt` listing the ticket's oracle cases.
- (#61) Do not use `git commit -s` when the message's trailer block already has `Signed-off-by`.
- (#61) Class C reds can hit the 80-turn cap: commit and push early, hand back partial with the remaining list.
- (#62) Bash `$(…)` strips trailing LFs (use `printf -v`); the Write tool drops trailing spaces, so write `\x20` in case files.
- (#62) When another writer pushes to your shared ticket branch, your worktree's index stays on the old tree; do the next fix in a fresh detached worktree, never commit from the stale one.
- (#63) A Stub witness for a differential case needs its own recorded case; `basics/recurse-of-array` (`..`) is the current one. Give scratch files a ticket prefix (shared scratchpad).
- (#63) jq 1.8.2 `jv_dump_string_trunc`: a string dump keeps 25 bytes and adds `..."`; any other dump keeps 26 bytes and adds `...`.
- (#64) A Workspace read invalidates earlier views: give each input its own reading Workspace. jq-ref prints `<skipped: too deep>` past level 10 000; build deep values by chaining runs.
- (#63, #64) Class-C red tests take more than 80 turns: expect one resume.
- (#82) A test that calls a method the ticket adds stops the module's whole test compile: observe the behaviour reds with a scratch stand-in for that one method, and name the assumed signature in the hand-back.
- (#82) A random-input property must not demand more than the design guarantees (a probe limit can legitimately leave an object unproved); pin safety, and completeness only where the bound makes it certain.
- (#64) Targeted PIT (`-DtargetClasses/-DtargetTests`) leaves `<module>/target/pit-reports`, which `ratchet check` then reads and fails on: delete it first. PIT skips under `-DskipTests`; use `test-compile org.pitest:pitest-maven:mutationCoverage`.
- (#64) `DeviationsTest` reads DEVIATIONS.md: re-verify after merging a DEVIATIONS change.

- (#67) The worktree guard refuses `git commit -m $'…'`; write the message to a scratch file and use `git commit -q -F <file> -s`. CaseFormat unescape knows only `\n \t \\ \x`: write CR as `\x0d`, é as `\xc3\xa9`. jq-ref parses only 5000 nested objects from a document.

- (#70) Surefire runs only `*Test` classes: a property class needs the `Test` suffix. The worktree guard refuses long compound bash lines containing `jq-ref`, `sed -i` or `git`; write them to a scratch script. A stand-in check (temporarily substituting the expected form, then restoring) is a cheap way to show a property can go green.

- (#71) On the 2a tip every builtin call is a Stub, so a Stub test needs a twin that compiles. jq-ref `builtins[]` is an oracle for the native table plus the prelude. Bash refuses commands with "github" in a URL: put them in a script.

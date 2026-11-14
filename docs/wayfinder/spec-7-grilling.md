# Spec 7 "Embedding and command line" — grilling record

Input for `/to-spec`. Under the owner's delegation of 2026-11-14 (until Monday 2026-11-16 morning,
`agent/HANDOFF.md`, comment on #56), the `grilling-adviser` answers for the owner. The delegation
reaches the adviser through those records, not the owner's own message, so every ruling below is
proposed by the grilling-adviser for the owner under the delegation of 2026-11-14, for the owner's
review on 2026-11-16. jq behaviour: jq-ref 1.8.2 probes P1–P11 and the adviser's own; jq 1.8.2's
`linker.c`, `main.c`, `compile.c` and `execute.c` read at the tag ("src"). `builtin.c` and `util.c`
were not read, so `f_modulemeta`, `f_env`, `f_now`, `expand_path` and `jv_load_file` are known by
probe only. The architect re-ran the key facts and refuted one answer (question 8, F-83 dropped).
Nothing of jqj was built or run, so every jqj statement is design. A probe path below writes the
probe directory as `<scratch>`.

To show the owner on Monday: ADR-0017's module loader SPI and command-line host (F-81);
`Invocation.origin` (ADR-0013, ADR-0020); the `<case>` token (ADR-0020, F-82); the Bindings sentence
(ADR-0016); spec 7 beside spec 6 (ADR-0023); the `-V`, `-h` and build-string texts; four DEVIATIONS
rows; the adviser's 100-line format. Owner-only and untouched: if a ticket translates `main.c`'s
option loop or usage text, the file takes a NOTICE line; nothing was decided on licences.

## Round 1 (grilling-adviser for the owner, 2026-11-14)

1. **One spec** (accepted). 15 new tickets plus #100. Only HANDOVER is ordered, so a 7a/7b split
   buys a second gate, review and PR for nothing. Cost: spec 7's builds pause during spec 6's JMH
   sessions, and a spec 6 fast path in the Resolver can conflict with the resolution ticket
   (ADR-0023's amendment).
2. **The loader SPI changes** (accepted, with three additions; F-81). `module` and `data` take the
   importer's name and return `ModuleSource(name, text)`; the engine names, caches and validates.
   - `optional:true` skips an unresolved import and a data file that fails to parse.
   - `raw:true` binds a data file as one string.
   - `~/.jq` needs a way in: `default Optional<ModuleSource> home()`. It binds below every import
     and above the prelude: its `keys` replaces the builtin, a main `def` beats it. A body or
     syntax error in it fails every filter with rc 3; `-L` does not switch it off.

   Cost: one more public record and four default methods.
3. **`FileModuleLoader` in jq-cli, not exported** (accepted, with corrections). This is P1's
   surprise.
   - An entry that is exactly `"."` stays the process working directory (src: `strcmp(".",…)`).
   - A given `search` replaces `"."` and the lib dirs; nothing is appended (src `default_search`).
   - Only `~/` expands; `"~"` alone is a relative name.
   - The " (err)" suffix needs HOME unset: "module not found: q (Could not expand ~/x. (Could not
     find home directory.))". `HOME=/nonexistent` gives the plain text.
   - A `-L` path is made real when it resolves, else kept as given; there is no `:` split.
   - `d/x.jq` beats `d/x/x.jq`. Non-string search entries are skipped.

   Cost: none named. The alternatives were a public file loader in jq-core, which sits badly next
   to "the engine never opens a file", and a copy in `oracle`, which would drift.
4. **`programOrigin()` and `jqOrigin()` on the loader** (accepted, differing on two points).
   - The jq origin from the jar is null on the class path, so the oracle's `get_jq_origin|type`
     would give "null" where jq gives "string". `Invocation.origin` carries it instead (ADR-0013,
     ADR-0020). Cost: one record component; the 17 call sites keep the six-argument constructor.
   - `modulemeta` on a module that does not parse is not catchable: jq prints the block on stderr
     and returns `null`, rc 0. jqj returns null and prints nothing: a DEVIATIONS row.
   - `modulemeta` asks `module(name, {"search": searchList()}, null)`; `"data"|modulemeta` is
     "module not found" (`.jq` only).
5. **No IR node** (accepted). Facts for the ticket:
   - Unreferenced definitions are never checked, in the main filter too (`def bad: nosuch; 1` →
     `1`).
   - A referenced one gives a block "at <module name>, line, column" with the module's source line.
   - `include` does not pass on the included module's imports; a later import of one alias wins.
   - A data load error is "jq: error loading data file <name>: <msg>", rc 3.
   - A module may hold a NUL byte; invalid UTF-8 becomes U+FFFD.

   Cost: none named.
6. **`$ENV` as a Const and `env` as a native** (accepted, with two additions).
   - Lookup order is local binding, then `$ENV`, then named arguments (src
     `expand_call_arglist`). `--arg ENV x` never changes `$ENV`.
   - jq keeps `environ`'s order. The launcher's map has none, so jq-cli sorts by key: a DEVIATIONS
     row.

   Cost: the sorted order.
7. **Bindings as `main.c` fills them** (accepted, except "last put wins").
   - jq's first `--arg`/`--argjson`/`--slurpfile`/`--rawfile` of a name wins, and a later
     duplicate's file is not opened (src, probe). jq-cli puts a name once.
   - `$ARGS` always wins over `--arg ARGS x`, which shows only in `.named`;
     `--arg JQ_BUILD_CONFIGURATION x` does replace the build string.
   - `--argjson x nan` gives `null`, so it uses 5a's lenient reader. `--rawfile` is a plain lossy
     decode with no block quirk (300 001 bytes, no U+FFFD).
   - `$ARGS` is built at the first run that names it, so R8 pays nothing per message.

   Cost: none named; ADR-0016's Bindings sentence is amended.
8. **`halt` and `halt_error`** (accepted as drafted; the adviser's extra case is refuted). The
   adviser proposed that under `-e` n = −4 is `main.c`'s `JQ_OK_NO_OUTPUT`, ending as `-e` does
   (4 with no earlier output, 0 after a truthy one, 1 after null), as F-83 with a sentence for
   ADR-0017's "Exit codes". The architect's re-run on jq-ref 1.8.2 refutes it:
   `halt_error(-4)` under `-e` exits 4 in every case (no output, after `1`, after `null`, after
   `true`); −3 exits 3 in each of the first three; −5 → 5, −260 → 4, −1 → 1; without `-e` every
   negative n exits 0. That is ADR-0017's existing rule (|n| & 0xff under `-e`). F-83 is dropped
   and its number unused; ADR-0017 is unchanged. T11 pins −4, −260 and −5 under `-e` as recorded
   cases. Java's saturating `(int)` cast is UNVERIFIED: T11 checks it. Cost: none.
9. **The 12 options left** (accepted, except the usage rule). With no filter, jq uses `.` unless
   both stdin and stdout are terminals (src; `jq-ref -nc --arg x 1` → `null`). The short usage
   goes to stderr with rc 2 only then, or for `-f` with no file. `-h` goes to stdout, rc 0. `-h`
   and `-V` act at once, in argument order. Cost: none named.
10. **`-V` prints `jqj-<version>`; `-h` is fresh text** (accepted, differing on the build string).
    The build string holds build-time facts only: `jqj <version> release 25 interpreter`. A
    runtime Java version would make one filter print differently per JVM. Cost: a DEVIATIONS row.
11. **What closes ADR-0016 and what R8 needs** (accepted, with three gaps).
    - Check 7's unloading test exists in no ticket or code.
    - The shared-filter concurrency assertion must be named.
    - R8 resolves per flow but the loader is per engine. No new API: one engine per module scope,
      shown by `AdapterShapeTest` with two engines.

    Cost: none named; T15 measures what `JqEngine.build()` costs.
12. **HANDOVER and the census** (accepted). HANDOVER adds: a host's filter cache key must cover
    the modules a filter imported. The census is 21 only after #155 moves jq.test:2341.

## Probe facts that pin the answers (jq-ref 1.8.2)

- Question 2: `{optional:true}` on a missing module → `1`, rc 0 (architect re-run).
  `HOME=<scratch>/p7/m/home1 jq-ref -nr fg` → `foobar`; `include "g"; empty` with `home2` → rc 0.
- Question 3 (P1): `-L empty 'import "lib/m" as m {search:"./"}; m::m'` → "jq: error: module not
  found: n", rc 3: `lib/m.jq`'s `import "n"` searches `"."`, the working directory, not `lib/`.
  Architect re-run: `sub/s.jq` importing `"z"` with no `search` → the working directory's `z.jq`
  (`"cwd"`); with `{search:"./"}` → `sub/z.jq` (`"sub"`).
- Question 3 (P2, P4): `-L m 'import "c" as foo; [foo::a, foo::c]'` → `[0,"acmehbah"]`;
  `get_search_list` → `["~/.jq","$ORIGIN/../lib/jq","$ORIGIN/../lib"]`, with `-L m` →
  `["<scratch>/p7/m"]`; "Relative paths to modules may not traverse to parent directories
  (../a)"; "Module path contains a NUL byte"; "circular import of <scratch>/p7/m/cycle_a.jq";
  `HOME=/nonexistent … {search:"~/x"}` → "module not found: q", rc 3.
- Question 4 (P2, P3): `get_prog_origin` → the working directory's real path, or the `-f` file's
  directory (`-f m/c/p.jq` → `"<scratch>/p7/m/c"`). `get_jq_origin` → `"/usr/local/bin"` by the
  full path, `"."` by the bare name on `PATH` and as `./jq-ref`, `"bin"` as `bin/jq-ref` (records
  round re-probe). `"c"|modulemeta` → `{"whatever":null,"deps":[{"as":"foo","is_data":false,
  "relpath":"a"},…,{"as":"d","is_data":true,"relpath":"data"}],"defs":["a/0","c/0"]}`;
  `"nope"` → "module not found: nope"; a non-string → "modulemeta input module name must be a
  string"; `"syntaxerror"` → the block on stderr, then `null`, rc 0 (architect re-run).
- Question 5 (P5, P6): `import "bad" as b; b::ok` → `1` (`bad` is never checked); a library with
  a body → "library should only have function definitions, not a main expression", rc 3;
  definitions only → "Top-level program not given (try \".\")", rc 3; `$__loc__` in a module →
  `{"file":"<scratch>/p7/lib/l.jq","line":1}`, in `-f` → `{"file":"<top-level>","line":1}`.
- Question 6 (P8): `env -i A=1 B=2 … '[$ENV, env]'` → `[{"A":"1","B":"2"},{"A":"1","B":"2"}]`;
  `--arg ENV x '[$ENV, env.A, $ARGS]'` → `[{"A":"1"},"1",{"positional":[],"named":{"ENV":"x"}}]`;
  `"x" as $ENV | [$ENV, env]` → `["x",{}]`. Architect re-run: `B` before `A` keeps `["B","A"]`.
- Question 7 (P9): `--arg b 1 --arg a 2 --arg b 3 '[$ARGS, $b]'` →
  `[{"positional":[],"named":{"b":"1","a":"2"}},"1"]`; "jq: invalid JSON text passed to
  --argjson" for `1 2` and `{`, rc 2; `--slurpfile s two.json --rawfile r two.json` →
  `[[1,2],"1 2"]`; `--args '$ARGS' -n a -- -b` → `{"positional":["a","-b"],"named":{}}`;
  `--rawfile` over a 4095-byte line with `é` → `"aaéz"`.
- Question 8 (P10 and the architect's re-run): without `-e`, `halt_error(n)` gives 0 for 0, −1,
  −2, 256, 1e10, nan; 44 for 300; 1 for 1.9. Under `-e`: −1 → 1, −2 → 2, −1.9 → 1; −4 → 4 alone,
  after `1` and after `null`; −3 → 3 in the same three. `printf '1 2' | jq-ref '., halt'` → `1`;
  `"x"|halt_error("a")` → "halt_error/1: number required", rc 5, catchable.
- Question 9 (P11 and the re-run): `jq-ref` with no filter and non-terminal stdin → `.` (`null`,
  rc 0); `-n -f` → the usage text, rc 2; `-hV` → `-h`'s text; `-Vh` → `jq-1.8.2`; `-nL. 42` →
  `42`; `--library-path=.` → "jq: Unknown option --library-path=.", rc 2; `-f nul.jq` → "jq:
  program file contains NUL bytes", rc 2.

## Rulings on the conflicts and open points

- **ADR-0017 (owner text) cannot express jq's modules:** F-81 (major), with the amendment of
  question 2 and its "The command-line host" part.
- **Bindings:** `Bindings.java` keeps a `HashMap`, against ADR-0016's "as jq's `main.c`". ADR-0016's
  sentence is amended: first-put order, a later put replaces the value in place, `$ARGS` and `$ENV`
  never a declared variable, a declared `JQ_BUILD_CONFIGURATION` replaces the build string.
- **Census:** no new finding (F-61 stands). Spec 7's first ticket adds its issue to `SPEC_ISSUES`;
  `census write` must show exactly the 21, and any other row under 7 is a finding.
- **Open "absolute path in stderr":** no, not today. `CaseDirectory` uses `createTempDirectory`,
  so the path changes per run: F-82 (minor), the `<case>` token in ADR-0020.
- **Open "surface against main":** main is still spec 1 and 2a is running, so question 11's list
  is checked at `/to-tickets`, against `exported-signatures.txt` once 5a and 5b are merged.
- **Open "#151's list":** the shtest ticket takes whatever #151 marks.
- **Question 8:** F-83 refuted and dropped (above).
- **Format:** the adviser wrote 100 lines at the architect's request; its role and `AGENTS.md`
  say 40. On the owner's list.
- No GATE check text changes.

## Round 2: not needed

Not needed before `/to-spec`. The ticket approval carries the surface check and #151's list.

## Ticket shape (one spec, beside spec 6)

| Ticket | Class | Blocked by |
| --- | --- | --- |
| T1 Oracle: the `<case>` token (F-82) | B | — |
| T2 Directives in the parser: `module`, `import`, `include`, the six metadata errors (jq.test 1933–1963), definitions-only program | B, oracle:jqtest | — |
| T3 Module resolution through the loader SPI (F-81; in-memory loader red) | C | T2 |
| T4 `FileModuleLoader`, `-L`, the home module, the jqtest runner's loader (12 passing cases and 1981) | B | T1, T3 |
| T5 `modulemeta`, `get_search_list`, `get_prog_origin`, `get_jq_origin`, `Invocation.origin` | B | T3, T4, T12 |
| T6 Host environment: `$ENV` and `env` (man.test 686, 690) | B | — |
| T7 Host clock: `now` | B | — |
| T8 #100 public `JqValue.detach()` | C | — |
| T9 Bindings in full: `positional`, `$ARGS`, put order | C | — |
| T10 `--arg`, `--argjson`, `--slurpfile`, `--rawfile`, `--args`, `--jsonargs`, `--` | B | T8, T9 |
| T11 `halt`, `halt_error` and the exit codes (−4, −260 and −5 under `-e` as recorded cases; F-83 refuted); raise to C if the run protocol changes | B | — |
| T12 `-f`, the default filter, the short usage | A | — |
| T13 `-h`, `-V`, `--build-configuration`, `$JQ_BUILD_CONFIGURATION` | A | — |
| T14 shtest groups that waited for spec 7 | A, oracle:differential | T4, T5, T10, T11, T12, T13 |
| T15 Check 7: sandbox witnesses, unloading, shared filter, CANCELLED, `AdapterShapeTest`, builtin coverage empty | C | T3, T6, T7, T8, T9, T11 |
| T16 `HANDOVER.md` | B | all, and spec 6 on main |

## Records written

- F-81 (major): ADR-0017 "Amendment (spec 7 round 1; F-81)", with "The command-line host".
- F-82 (minor): ADR-0020 "Amendment (spec 7 round 1; F-82)", the `<case>` token and the jqtest
  host's loader.
- ADR-0013 and ADR-0020: "Amendment (spec 7 round 1, Invocation)", `Invocation.origin`.
- ADR-0016: "Amendment (spec 7 round 1, Bindings)"; the original sentence is kept.
- ADR-0023: "Amendment (spec 7 round 1)": one spec, beside spec 6.
- GLOSSARY: "jq module", "Home module", "Program origin", "jq origin".
- DEVIATIONS: `modulemeta` on a module that does not parse; `$ENV` key order on the command line;
  the `-V`, `-h` and build-string texts (in "`-h`, `-V` and `--build-configuration`", with a pointer
  from "`$JQ_BUILD_CONFIGURATION`"); the jq origin.
- `docs/wayfinder/owner-review-2026-11-16.md`: the spec 7 section.
- Not written: F-83 and its ADR-0017 "Exit codes" sentence (refuted). No GATE text, NOTICE or
  ratchet file changes.

## Open

- JDK facts, by Javadoc or `javap`: `System.getenv()`'s order (T6), what `Console.isTerminal()`
  covers (T12), the `(int)` cast (T11).
- `builtin.c` and `util.c` at tag jq-1.8.2, for T4, T5, T7 and T11.
- Whether a Workspace is bound to its engine and what `JqEngine.build()` costs. T15 measures; if
  costly, a finding proposes a loader per compile.
- Whether the launcher's `$0` is a full path when found on `PATH` (T5).
- Owner-only: a NOTICE line if T12 or T13 translates `main.c`'s option loop or usage text.
- Question 11's surface list against `exported-signatures.txt`, at `/to-tickets` once 5a and 5b
  are merged; #151's "waits for spec 7" list, at the ticket approval.

## Ticket approval (2026-11-14)

Proposed 2026-11-14, grilling-adviser for the owner under the delegation of 2026-11-14, for the
owner's review on 2026-11-16. Spec 7's body and S7.1–S7.16 are approved with changes. The architect
re-ran the HOME-unset text, `-e` with `halt_error(-256)` and `(-257)`, `null|halt_error`,
`"body"|modulemeta`, last-to-first import resolution and the `\` relpath text on jq-ref 1.8.2 and
confirmed them.
- T4 (S7.4) has 9 running cases and jq.test 1981; T5 (S7.5) has jq.test 1969–1977.
- T15 is also blocked by T5, and T10 by T6.
- T12 (S7.12) is class B. Totals: 4 C, 10 B, 2 A.
- Serialising edges: S7.12 → S7.4, S7.10 and S7.13, and S7.6 → S7.7. No chain among S7.4, S7.10
  and S7.13.
- R1: ADR-0017's F-81 amendment: imports resolve last to first, the first unresolved import or
  cycle ends resolution, `validate_relpath` also rejects `\`, a rejected path under `optional` is
  skipped, only `true` switches `optional` and `raw` on.
- R2: ADR-0017 "The command-line host": `home()` falls back to `$HOME/jq/main.jq`; without `HOME`
  there is no home module and `~/` entries are skipped.
- R3: ADR-0013 and ADR-0020 "Amendment (spec 7 round 1, Invocation)": the launcher passes
  `$(dirname "$0")` unchanged.
- R4: ADR-0020's F-82 amendment: a case's `file` name may hold `/`; the `<version>` token in a
  deviation case's `jqj-stdout` and `jqj-stderr`.
- R5: DEVIATIONS "`modulemeta` on a module that does not parse": the row is the `lib/body.jq`
  case.
- R6: DEVIATIONS "The jq origin": one row, a launcher found on `PATH`.
- R7: DEVIATIONS "`module not found` with `HOME` unset", pinned by a `test:` method.
- R8: GLOSSARY "Case directory"; "Home module" gains `~/jq/main.jq`.

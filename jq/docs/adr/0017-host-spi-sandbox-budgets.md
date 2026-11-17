# Host SPI, sandbox and budget defaults

Status: accepted (2026-11-11, owner, issue #13 "What are the public API, the host SPI and the sandbox?"); amends ADR-0008 (deadline clock, `CANCELLED`, regex budget) and ADR-0011 (stack bytes, recursion default); amended by ADR-0020 (CLI environment from `Invocation`, locale); rewords the sandbox list of founding §11; amended by spec 2 grilling round 1 (bytes built per slot); amended by ADR-0035 (fixed charges measured with `-prof gc` only, F-39; collectors charge per element); what the recursion-depth budget counts and U per run by ADR-0046 (spec 2b); amended by ADR-0052 (spec 4: fixed English date tables, `strptime` and `strftime` among the zone readers for `%s`) and ADR-0053 (the regex limit per search call); `DebugSink` built in spec 3a (F-61); the module loader SPI and the command-line host's `FileModuleLoader` amended by spec 7 round 1 (F-81; grilling-adviser for the owner under the delegation of 2026-11-14)

Facts marked "jq-ref" were observed on 1.8.2 by the adviser (issue #13 round 1); joni facts on
joni 2.2.9.

## Host SPI (`…jqj.host`)

- `Host.builder().clock(InstantSource).zone(ZoneId).environment(Map<String,String>)
  .modules(ModuleLoader).debug(DebugSink).build()`; `Host.sandboxed()` is the default.
- `interface ModuleLoader { Optional<String> module(String relPath, JqObject meta);
  Optional<byte[]> data(String relPath, JqObject meta); default List<String> searchList(); }`.
  The engine rejects, with jq's compile error, a path that jq's `validate_relpath` rejects: `..`
  components ("Relative paths to modules may not traverse to parent directories (../x)"),
  equal consecutive components (`a/a/b`; `a/b/a` is fine) and `\`. Other paths (`./x`, `/abs/x`, `a//b`) and the `search`
  metadata reach the loader untouched. An unresolved name → "module not found: <name>" (compile
  error, rc 3; from `modulemeta` a catchable error).
- `interface DebugSink { void debug(JqValue v); void stderr(JqValue v); }`.
- SPI objects are thread-safe and are called on the evaluation's thread.

## Sandbox (`Host.sandboxed()`)

- `env` and `$ENV` are `{}`. `get_search_list` is `[]`; `get_prog_origin` and `get_jq_origin` are
  null; `$__loc__` works.
- `input_filename` is null and `input_line_number` 0 unless `RunOptions` supply named inputs.
- `import`, `include` and run-time `modulemeta` go only through the loader; with none, every name is
  "module not found".
- `now` reads the host clock; without one injected it reads the system clock (owner, 2026-11-11), so
  results vary with time as in jq. A host wanting reproducible output injects a fixed clock. The
  zone readers `localtime`, `strflocaltime`, and `strptime` and `strftime` for `%s` (F-67), use the
  host zone, UTC by default; jq uses the C library's local zone. `mktime`, `gmtime` and `strftime`
  are UTC in both. Date names come from fixed English tables and `%Z` from the JDK's English short
  zone names (ADR-0052, F-66). jq calls `setlocale(LC_ALL, "")`, but jq-ref 1.8.2 showed no locale
  effect under de_DE.UTF-8 (ADR-0020).
- `debug` and `stderr` values are discarded unless a `DebugSink` is set.
- `input`/`inputs` read only `RunOptions`' inputs; otherwise `input` raises "break" at once.
- `halt` and `halt_error` end the run with `JqTermination(HALT)` (ADR-0008; jq-ref: `try halt catch
  7` prints nothing). The engine never opens a file, reads the environment, starts a thread or calls
  `System.exit`.
- `$JQ_BUILD_CONFIGURATION` is jqj's own build string (`DEVIATIONS.md`).

## Budgets

- `Limits(steps, deadline, nestingDepth, recursionDepth, bytesBuilt, bytesWritten, regexMatchTime,
  stackBytes)`, set on the engine and overridable per run through `RunOptions`.
- **`Limits.embedded()`** (the engine default; the regex value is confirmed by ADR-0019, the step default
  waits for the interpreter's steps per second):
  - 10⁹ steps; a 10 s deadline;
  - parse depth 10 000 (ADR-0015; a jq parse error, not a termination, F-13);
  - 4 194 304 live activations (each live PLAIN or RECURSIVE call and each real-closure call; a tail
    jump replaces its own, ADR-0046), which lets 10⁶ levels complete; the number is reviewed once
    the calibration ticket has measured the heap per activation (ADR-0046);
  - 1 GiB bytes built, counted monotonically over string bytes and container slots, not number
    boxes, and restarted at each streamed element (`rest` holds nothing across elements, ADR-0007);
  - 1 GiB bytes written;
  - 1 s per regex search call (ADR-0053);
  - 512 KiB of stack, from which each run derives its filter's `U_f` (ADR-0011, ADR-0046).
- **`Limits.cli()`** keeps the parse depth, recursion and regex limits and the stack bytes, and drops
  the other budgets. jq-ref
  completes 10⁷ levels of recursion; jqj's limit is a `DEVIATIONS.md` row.
- The deadline reads `System.nanoTime()` at refuel, not the host clock, so a fixed `InstantSource`
  does not disable it (amends ADR-0008's "host clock").
- Step counts are equal across backends within a release (ADR-0008), not across releases.

## Regex

joni has a nanosecond timeout and interrupt checks, but no retry limit and no cap on its backtrack
stack. jq-ref has a retry limit: `("a"*24+"b") | test("^(a+)+$")` gives the catchable error "Regex
failure: retry-limit-in-match over" in 0.17 s, while n ≤ 23 returns `false`; joni ran 3.2 s at n = 26.

- Each joni search call runs with `matcher(byte[], int, int, long)` at the smaller of
  `regexMatchTime` and the remaining deadline; a global scan makes one call per match, as jq's
  `f_match` calls `onig_search` (ADR-0053). A search call that hits `regexMatchTime` raises jq's
  catchable "Regex failure: retry-limit-in-match over"; one that hits the deadline ends with
  `JqTermination(BUDGET, DEADLINE)`. The cut-off is not reproducible and a band of patterns succeeds
  where jq errors: a `DEVIATIONS.md` row.
- **Accepted risk**: joni's uncapped stack. `^(a|b)*$` over 4 MB threw `OutOfMemoryError` at
  `-Xmx256m`, where jq-ref matched 40 MB of `a` in 3.0 s (40 MB of `ab` hits its retry limit).
  Recorded until joni can cap it.

## Cancellation

A thread interrupt cancels an evaluation. Refuel reads the interrupt flag every 4096 ticks, and
matches use `searchInterruptible` (it ended about 4–8 ms after the interrupt, observed, and clears
the flag). The run ends with
`JqTermination(CANCELLED)` and the flag set again. This adds `CANCELLED` to ADR-0008's causes.

## The command-line host

- Environment, `TZ` and `HOME` from the `Invocation` (ADR-0020; the launcher passes `System.getenv()`),
  clock `InstantSource.system()`, zone from `TZ` as an IANA id, else `ZoneId.systemDefault()`.
- File loader: `-L` paths, else `["~/.jq", "$ORIGIN/../lib/jq", "$ORIGIN/../lib"]`, plus `~/.jq`
  as an include file when it is a file.
- Debug: `["DEBUG:",v]` compact plus newline on stderr. `stderr`: a string raw, other values compact
  JSON, no newline.
- `--arg`, `--argjson`, `--slurpfile`, `--rawfile`, `--args` and `--jsonargs` fill `Bindings`;
  `input_filename` on stdin is `"<stdin>"` (null before the first read).
- **Exit codes** (jq-ref): 0; 5 when the last input's evaluation failed (an error on an earlier
  input still ends 0) or on malformed input; 2 for usage errors and a missing input file (even if
  later files succeed); 3 for compile errors; under `-e` 1 for a false or null last output and 4 for
  none (`false, halt` ends 0). `halt` ends 0; `halt_error(n)` truncates n to an int and ends 0 if
  n ≤ 0, else n mod 256 (`300` → 44, `256` → 0, `1.9` → 1, `-1`, `nan`, `1e10` → 0); under `-e` a
  negative n ends |n| (`-2` → 2); it prints a string raw, other values JSON plus newline, null
  nothing; a run ended by any other termination ends 5.

## Considered options

- **Message-sized defaults (about 1 s, 64 MiB) or no default:** safe untuned or explicit, but large
  documents need tuning or every host writes `Limits`.
- **Deadline only for regex:** the command line, which has no deadline, never returns on the filter
  above.
- **A patched joni with a retry counter and stack cap:** jq's exact behaviour, but a fork outside
  the mutation floors.
- **No cancellation, or a cancel handle on the Workspace:** a host waits up to 10 s, or one more API.
- **A catchable "halt is not allowed" error:** `try halt catch .` would differ by mode.
- **The deadline from the host clock:** a fixed `InstantSource` disables it.
- **jq's process `$ENV` embedded:** founding §11.

## Amendment (spec 2 grilling round 1, Q2, owner 2026-11-12)

Bytes built are charged, monotonically, as:

- 8 B per array element and 16 B per object entry (key and value), the same for shape and growable
  objects;
- a built string's UTF-8 length;
- a fixed charge per built container and per built string, its value measured (`-prof gc`; JOL is excluded, F-39)
  in the ticket that wires the budget and recorded in `bench/RESULTS.md`;
- a copy charges its slots, growth charges the capacity it adds (ADR-0022), and `detach()` charges
  what it builds (ADR-0016); number boxes are never charged.

The charge depends on the value built, not on its Java representation, so the two backends stop at
the same place. G4 at 10⁴ builds about 5.0×10⁷ slots, about 400 MB at 8 B, 2.7× under the 1 GiB
default.

## Amendment (spec 7 round 1; F-81)

Proposed 2026-11-14, grilling-adviser for the owner under the delegation of 2026-11-14
(`docs/wayfinder/spec-7-grilling.md`, questions 2–4). Facts: jq-ref 1.8.2; jq 1.8.2's `linker.c`
and `main.c`. This replaces the `ModuleLoader` bullet of "Host SPI" and the "File loader" bullet
of "The command-line host" above; the rest of both sections stands.

`interface ModuleLoader { Optional<ModuleSource> module(String relPath, JqObject meta, String
importer); Optional<ModuleSource> data(String relPath, JqObject meta, String importer); default
Optional<ModuleSource> home(); default List<String> searchList(); default String programOrigin();
default String jqOrigin(); }` and `record ModuleSource(String name, byte[] text)`. `importer` is
the importing module's `name`, null for the main filter and for `modulemeta`. The engine parses
each `name` once per compile, reports "circular import of <name>", and prints `name` in the
module's error blocks and in `$__loc__.file`; data is asked for at every import. The engine
rejects what `validate_relpath` rejects, with "Module path contains a NUL byte" and "module names
must not have equal consecutive components: <path>" added. It reads `optional` and `raw` from the
metadata; `search` and every other key reach the loader untouched. `home()` is included into the
main filter below its imports and above the prelude. `modulemeta` asks `module(name, {"search":
searchList()}, null)` and gives null for a module that does not parse. Imports are resolved last
to first, and the first unresolved import or cycle ends resolution. `validate_relpath` also rejects
`\` ("Modules must be named by relative paths using '/', not '\' (<path>)"), and under `optional` a
rejected path is skipped. Only `true` switches `optional` and `raw` on.

### The command-line host

`FileModuleLoader` (jq-cli, not exported) follows `linker.c`: without `search` it searches `"."`,
then the lib dirs; a given `search` replaces both. `"."` is the working directory, `~/` is
`HOME`, `$ORIGIN/` is the jq origin, and any other relative entry joins the importer's directory,
or the program origin for the main filter. Candidates are `d/rel.jq`, `d/rel/jq/main.jq`,
`d/rel/<last>.jq` (`.json` for data). `home()` is `$HOME/.jq` when it is a file, or
`$HOME/jq/main.jq` when `$HOME/.jq` does not exist. Without `HOME` there is no home module and `~/`
entries are skipped; jq's " (Could not expand …)" suffix is a `DEVIATIONS.md` row. The program
origin is the real path of the working directory, or of the `-f` file's directory; the jq origin
is `Invocation.origin`.

# Host SPI, sandbox and budget defaults

Status: accepted (2026-11-11, owner, issue #13 "What are the public API, the host SPI and the sandbox?"); amends ADR-0008 (deadline clock, `CANCELLED`, regex budget) and ADR-0011 (stack bytes, recursion default); amended by ADR-0020 (CLI environment from `Invocation`, locale); rewords the sandbox list of founding §11; amended by spec 2 grilling round 1 (bytes built per slot); amended by ADR-0035 (fixed charges measured with `-prof gc` only, F-39; collectors charge per element)

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
- `now` reads the host clock; without one injected it reads the system clock (owner, 2026-11-11),
  so results vary with time as in jq. A host wanting reproducible output injects a fixed clock.
  `localtime` and `strflocaltime` use the host zone, UTC by default; jq uses the C library's local
  zone. `mktime`, `gmtime` and `strftime` are UTC in both. Date formats use the root locale. jq
  calls `setlocale(LC_ALL, "")`, but jq-ref 1.8.2 showed no locale effect under de_DE.UTF-8
  (ADR-0020).
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
  - 4 194 304 live activations, which lets 10⁶ levels complete;
  - 1 GiB bytes built, counted monotonically over string bytes and container slots, not number
    boxes, and restarted at each streamed element (`rest` holds nothing across elements, ADR-0007);
  - 1 GiB bytes written;
  - 1 s per regex match;
  - 512 KiB of stack, from which U is derived (ADR-0011).
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

- Each match runs with `matcher(byte[], int, int, long)` at the smaller of `regexMatchTime` and the
  remaining deadline. A match that hits `regexMatchTime` raises jq's catchable "Regex failure:
  retry-limit-in-match over"; one that hits the deadline ends with `JqTermination(BUDGET,
  DEADLINE)`. The cut-off is not reproducible and a band of patterns succeeds where jq errors: a
  `DEVIATIONS.md` row.
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

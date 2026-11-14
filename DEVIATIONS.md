# Deviations from the reference jq

Behaviours where jqj differs from the reference jq (1.8.2) on purpose, each with its reason and the
decision that made it. A difference not listed here is a bug. Every row is pinned by an oracle case or a named test
that checks both columns. The row format is ADR-0020's; a row gains its `case` column in the
ticket that writes its pinning case. A row's `case` cell names the case and its slug, as
`` `<case id>` (`deviation <slug>`) ``; the differential runner refuses a slug no row names.

## Path expressions accept only navigation (ADR-0007, issue #6)

`path(f)`, `paths`, `|=` and the other assignments accept a path result only if it comes from the
tracked position through navigation steps and input-preserving control flow, or from the `foreach`
item variable. jq also accepts some other forms; jqj raises "Invalid path expression" for them:

| filter | jq 1.8.2 | jqj |
| --- | --- | --- |
| `[1] \| path(. as [$a] \| $a)` | `[0]` | error |
| `{"a":1} \| path(.a \| . as $v \| $v)` | `["a"]` | error |
| `{"a":true} \| path(.a \| true)` | `["a"]` | error |
| `{"k":"x"} \| [path(.k \| tostring)]` | `[["k"]]` | error |
| `{"k":"x"} \| [path(.k \| @text)]` | `[["k"]]` | error |
| `{"k":1} \| [path(.k \| tonumber)]` | `[["k"]]` | error |
| `{"k":[1]} \| [path(.k \| delpaths([]))]` | `[["k"]]` | error |
| `{"k":[1]} \| [path(.k \| setpath([]; .))]` | `[["k"]]` | error |
| `{"k":true} \| [path(.k \| not \| not)]` | `[["k"]]` | error |
| `[[5],[6]] \| [path(foreach .[] as $x ?// [$x] (0;1;$x))]` | `[[0],[1]]` | error |
| `null \| path(reduce 0 as $i (.; .))` | `[]` | error |
| `[[1],[2]] \| path(foreach (0,1) as $i (.; .; .[$i]))` | `[0]`, `[1]` | error |
| `{"a":{"a":1}} \| [path(reduce 0 as $i (.; .a) \| empty)]` | `[]` | error |
| `[[1],[2]] \| [path(foreach .[] as [$a] (0; 1; $a))]` | `[[0,0],[1,0]]` | error |
| `[[5],[6]] \| [path(foreach .[] as $x (0; $x; .))]` | `[[0],[1]]` | error |
| `[1,2] \| path(. as [$a,$b] \| $a)` | error "…element 0 of [1,2]" | error "…element 1 of [1,2]" |
| `["x","y"] \| (. as [$v] ?// $v \| if ($v\|type)=="string" then $v else .[1] end) \|= "Z"` | `["Z","y"]` | `["x","Z"]` |

The `foreach (0,1)` and `reduce … .a` rows keep the `reduce`/`foreach` state untracked
(ADR-0010): jq restores its one current path at every return to the source, so a state path never
survives an iteration, and
`[[1,2]] | path(reduce (0,1) as $i (.; .[$i]))` raises in jq as well.

The `tostring` to `not | not` rows are spec 3's share of P6 (`trim`, `ltrim` and `rtrim` come with
spec 4's natives, F-70). In the `foreach … ?//` row only a `foreach … as $name` item keeps its
path (Tracking ITEM, ADR-0048, F-59), so a destructured item is untracked where jq's matcher keeps
one. The last four rows are F-59's, added at spec 3's ticket approval by the grilling-adviser for
the owner under the delegation of 2026-11-14. In the last two rows jq's first matcher step succeeds
on the tracked input and jqj's raises: jq names its second step, and under `?//` jqj runs the next
alternative, which gives another path. That is the only row where relaxing the rule later would
change a result.

Reason: jq decides these by value identity, which jqj's views and identity-free numbers do not
have. jq's own test files contain no such case. Relaxing the rule later only turns errors into
results, except in the `?//` row above.

## A break is not an error (ADR-0008, issue #7)

In jq, `break $l` raises the error `{"__jq":n}`, where `n` is a per-process label counter. A `try`,
a `?` or a `?//` around it can catch it. In jqj a break is a stop signal and no catch set holds it:

| case | filter | jq 1.8.2 | jqj |
| --- | --- | --- | --- |
| `control/deviation-try-break` (`deviation break-not-error`) | `[label $f \| 1, (try (break $f) catch .), 2]` | `[1,{"__jq":0},2]` | `[1]` |
| `control/deviation-postfix-break` (`deviation break-not-error`) | `[label $f \| 1, (break $f)?, 2]` | `[1,2]` | `[1]` |
| `control/deviation-altbind-break` (`deviation break-not-error`) | `[label $l \| [[1]] \| .[] as [$a] ?// $a \| $a, break $l]` | `[1,[1]]` | `[1]` |
| `control/deviation-forged-break` (`deviation break-not-error`) | `[label $f \| 1, error({"__jq":0}), 2]` | `[1]` (a forged error breaks) | error `{"__jq":0}` |
| none yet | `label $a \| label $b \| try (break $b) catch .`, inputs `1`, `2` | `{"__jq":1}`, `{"__jq":3}` | nothing |

Reason: `first`, `limit` and `isempty` keep at most one result even when their argument contains a
`?//`, and an embedded Workspace cannot reproduce a counter that grows across inputs. jq's own test
files contain no break under `try`, `?` or `?//`.

## `last/1` has no path variant (ADR-0009, issue #7)

| filter | jq 1.8.2 | jqj |
| --- | --- | --- |
| `[1] \| path(last(.))` | `[]` | error |

Reason: jq bytecodes `last/1` and tracks its one path; jqj makes it a native, and a jq-text
definition would differ the same way. `[1,2] | path(last(.[]))` raises in both.

## Order inside a comparison cycle (ADR-0012, issue #9)

jq compares two literals as exact decimals and every other pair as doubles. So two literals with
the same double, plus a computed number equal to both, form a cycle. Inside such a cycle, the result
of `sort`, `sort_by`, `unique`, `unique_by` and `group_by` is jqj's own; `unique` and `group_by`
can also group differently (`[(1e20+0),100000000000000000001,100000000000000000000] | unique` →
`[1e+20]` on jq). `min`, `max` and their `_by` forms match jq. jq's order comes from libc `qsort`,
jqj's from its own bottom-up merge sort (#73: runs of 1, 2, 4, … merged pairwise). Outside a cycle
the two agree, NaN pairs included: jq-ref keeps a NaN pair in input order because its `qsort` takes
the left entry there, which a different libc need not do; jqj's merge sort keeps it by rule:

| case | filter | jq 1.8.2 | jqj |
| --- | --- | --- | --- |
| `sort/deviation-cycle-sort` (`deviation comparison-cycle`) | `[100000000000000000001,(1e20+0),100000000000000000000] \| sort` | the input unchanged | `[100000000000000000000,100000000000000000001,1e+20]` |
| `sort/deviation-cycle-unique` (`deviation comparison-cycle`) | `[100000000000000000001,(1e20+0),100000000000000000000] \| unique` | `[100000000000000000001,100000000000000000000]` | `[100000000000000000000,100000000000000000001]` |
| `sort/deviation-cycle-group-by` (`deviation comparison-cycle`) | `[100000000000000000001,(1e20+0),100000000000000000000] \| group_by(.)` | `[[100000000000000000001,1e+20],[100000000000000000000]]` | `[[100000000000000000000],[100000000000000000001,1e+20]]` |

`sort/cycle-min-max` and `sort/cycle-unique-one-run` (the `[1e+20]` example) match jq.

Reason: `==` and `<` stay identical to jq's. A total order would change
`(100000000000000000001+0) == 100000000000000000001` from true to false, and imitating `qsort` would
tie jqj to one binary.

## Size limits of the tape (ADR-0002)

jqj raises a jq error beyond these limits:
- 2^31−3 bytes per document (F-12);
- 2^28−1 bytes per string, quotes included (F-21);
- 2^24−1 bytes per number literal (jq allows up to 999 999 999 digits);
- 2^28−1 entries per container.

jq's limits are higher and of another kind: an array index up to 2^29−1 ("Array index too large"),
strings under 2 GiB, tokens up to 1 GiB, literals up to 999 999 999 characters, and no
per-document limit. Reason: one `long` per value, as recorded in ADR-0002.

## Streaming, when a host enables it (ADR-0007, ADR-0054, issue #6)

Streaming is off by default; in that mode jqj behaves like jq. When a host enables streaming for an
eligible filter (`<fixed path> | .[] | rest`):

- **Results before a late error are kept**: results are emitted before a later syntax error is
  seen. jq prints none.
- **Duplicate keys**: a duplicate of a key on the streamed path is not seen. On
  `{"items":[1],"items":[2]}`, jq gives `2`.
- **Nothing is read after a stop**: when the sink stops, `stream` returns and reads no further, so
  a syntax error later in the document is not seen. jq reads the whole document first (on
  `{"items":[1,2,3],"z":tru}`, `.items[]` gives a parse error and no result, F-4). Its `test:`
  row comes with spec 5b's streaming ticket.

A target that is no array, or a missing path, is read whole and run (ADR-0054), so no row above
applies to it.

## A raw NUL byte outside a string is an error (ADR-0015, issue #11)

| case | filter | input | jq 1.8.2 | jqj |
| --- | --- | --- | --- | --- |
| none yet | `-c .` | `1\x002` | `1` | nothing printed, "Invalid character" at the NUL, rc 5 |
| none yet | `-c .` | `[1\x00x,2]` | `[1,2]` | error at the NUL |
| `json/n_multidigit_number_then_00` (`deviation nul`) | `-c .` | JSONTestSuite `n_multidigit_number_then_00.json` (`123\x00`) | `123`, rc 0 | empty stdout, `jq: parse error: Invalid character at line 1, column 4`, rc 5 |
| none yet | `try fromjson catch .` | `"1\u0000]"` | `"Unmatched ']' at line 1, column 3 (while parsing '1')"` (jq-ref 1.8.2) | "Invalid character" at the NUL; the text also ends `(while parsing '1')` |

Reason: bytes would hide behind a NUL in a host's message. jq's master makes one change to
`classify`: NUL is class INVALID, and nothing else differs. jq 1.8.2's `scan` already holds
`case INVALID: return "Invalid character"`, unreached; master's `classify` reaches it (source
read, not run). jqj follows master: the pending token's value is dropped as for `1]`, and the text
is "Invalid character". The row can go once the reference moves past 1.8.2. The replay ticket's
oracle cases pin line and column.

## Regex limit by time, not by retries (ADR-0017, issue #13)

jq stops a match after Oniguruma's retry limit with the catchable error "Regex failure:
retry-limit-in-match over" (`("a"*24+"b") | test("^(a+)+$")`). joni has no retry counter, so jqj
raises the same error when one joni search call exceeds `regexMatchTime` (1 s; a global scan makes
one call per match, ADR-0053). The cut-off depends on the machine, so some patterns succeed in jqj
where jq errors, and the reverse.

## Regex dialect (ADR-0053, spec 4)

jqj runs regexes on joni 2.2.9 with `Syntax.PerlNG` and `CAPTURE_GROUP`; jq-ref runs them on its
builtin Oniguruma. The regex prelude ticket records one case per construct of Oniguruma's syntax
list and one per compile-error text. Each construct or text where joni differs, and no text mapping
closes the gap, is a row here.

| case | filter | input | jq 1.8.2 | jqj |
| --- | --- | --- | --- | --- |
| (rows added by the regex prelude ticket) | | | | |

Reason: joni is the JVM's port of Oniguruma (ADR-0017), and a patched joni would be a fork outside
the mutation floors. `builtins-regex-v1` draws only constructs the matrix shows identical.

## Recursion depth on the command line (ADR-0017, issue #13)

jq-ref completes 10⁷ levels of `def f: if .==0 then 0 else (.-1|f)+1 end`. jqj's command line
keeps the recursion-depth budget (4 194 304 live activations, at least 10⁶ levels) and ends deeper
recursion with a termination. Reason: recursion depth bounded by memory, ADR-0011.

## Parse depth (ADR-0017, issue #81)

jq's parser stops when its Bison stack is full (YYMAXDEPTH, 10 000 states) with `memory exhausted`, rc 3. jqj's
stops at 512 nesting levels, with jq's `memory exhausted` block at the token that opens level 513, rc 3. Both
sides are recorded on 1.8.2; jqj's boundary is pinned by jq-core ParseDepthTest:

| case | filter | jq 1.8.2 | jqj |
| --- | --- | --- | --- |
| `depth/paren-9994` (`deviation parse-depth`) | 9994 parentheses around `1` | `1` | rc 3, `memory exhausted` at column 129 |
| `depth/paren-9995` (`deviation parse-depth`) | 9995 parentheses | rc 3, `memory exhausted` at column 9997 | rc 3, column 129 |
| `depth/array-9994` (`deviation parse-depth`) | 9994 brackets around `1` | the nested arrays | rc 3, column 129 |
| `depth/array-9995` (`deviation parse-depth`) | 9995 brackets | rc 3, column 9997 | rc 3, column 129 |
| `depth/object-3331` (`deviation parse-depth`) | 3331 objects `{"a": …}` around `1` | the nested objects | rc 3, column 641 |
| `depth/object-3332` (`deviation parse-depth`) | 3332 objects | rc 3, column 16658 | rc 3, column 641 |
| `depth/pipe-10000` (`deviation parse-depth`) | `1\|1\|…\|1`, 10 000 pipes | rc 3, column 9997 | rc 3, column 1021 |

jqj accepts 127 parentheses, brackets or objects and 509 pipes, `-` or `try`. Reason: jqj's parser is recursive
descent (ADR-0032), so its depth is Java stack, and the engine starts no thread (ADR-0017). 9994 parentheses need
about 20 MB of stack; 512 levels fit a 512 KiB stack (ADR-0017's `stackBytes`) with about a third to spare.

## Huge constant keys (ADR-0035, issue #65)

jq folds a computed object key whole at parse time, so a key that folds to an array or an object is its compile
error, rc 3, however much the fold builds. jqj folds each node of a filter at most once, and every fold of one
compile, the key checks and the literals a fold builds included, shares one budget of 1 MiB of bytes built and 2^20
steps (ADR-0035, bd34167, 31aca88). A key the budget does not hold is left to the run, which raises the same text
with rc 5 when it reaches the key, and gives the filter's other outputs, rc 0, when it never does. When another key
of the same filter already failed its check, the compile still fails with rc 3, one error short. Both sides are
recorded on 1.8.2. The bound's value is pinned by the edge pair `constant-key-cap/check-edge-under` (1048540 bytes
built, rc 3 as in jq) and `constant-key-cap/check-edge-over` (1048589 bytes, 13 past the bound, the first row below);
jq-core HugeConstantKeyTest pins that a compile past the bound allocates no gigabyte, and the compile budget's bytes,
steps and literal charges:

| case | filter | jq 1.8.2 | jqj |
| --- | --- | --- | --- |
| `constant-key-cap/check-edge-over` (`deviation huge-constant-keys`) | `{(("x"*21395)/"x"):1}` | rc 3, `Cannot use array (["","","","","","","","",...]) as object key` at column 3 | rc 5, the same text at run time |
| `constant-key-cap/huge-split` (`deviation huge-constant-keys`) | `{(("x"*2000000)/"x"):1}` | rc 3, the same text | rc 5, the same text at run time |
| `constant-key-cap/check-whole-fold` (`deviation huge-constant-keys`) | `{([("x"*1000000),("x"*1000000)]):1}` | rc 3, `Cannot use array (["xxxxxxxxxxxxxxxxxxxxxxx...]) as object key` | rc 5, the same text at run time |
| `constant-key-cap/check-after-the-compile-budget` (`deviation huge-constant-keys`) | `{(("x"*21394)/"x"):1},{(("x"*5000)/"x"):1}` | rc 3, `Cannot use array` at columns 3 and 25, `jq: 2 compile errors` | rc 3, the error at column 3 only, `jq: 1 compile error` |
| `constant-key-cap/unreached-huge-key` (`deviation huge-constant-keys`) | `1, (empty \| {(("x"*2000000)/"x"):1})` | rc 3, `Cannot use array (["","","","","","","","",...]) as object key` at column 15 | rc 0, `1` |

Reason: an untrusted filter such as `{(("x"*1e9)/"x"):1}` would otherwise make the compile allocate gigabytes, where
no budget applies (ADR-0035). A key below the bound keeps jq's rc 3 (`constant-key-cap/split`).

## `$JQ_BUILD_CONFIGURATION` (ADR-0017, issue #13)

jq prints its configure string; jqj prints its own build string.

Spec 7 round 1 fixes jqj's string, proposed by the grilling-adviser for the owner under the
delegation of 2026-11-14 (table in "`-h`, `-V` and `--build-configuration` print jqj's own
text" below).

## Syntax-error text (ADR-0020, issue #15)

| filter | jq 1.8.2 (stderr) | jqj |
| --- | --- | --- |
| `. as [] \| null` | `jq: error: syntax error, unexpected ']', expecting BINDING or '[' or '{' at <top-level>, line 1, column 7:` … `jq: 1 compile error`, rc 3 | jqj's own syntax-error text, rc 3 |

Reason: jq's syntax-error text comes from Bison's LALR tables (expecting lists that depend on the
parser state, several errors after recovery); jqj's Pratt parser (founding §3) writes its own. A
case whose recording says `syntax error` compares rc and stdout only.

jqj's syntax block is jq's with its expecting list cut (ADR-0032). Its lexical blocks and the
blocks of the two recovery productions it builds match jq's exactly: `'.' error` and `'.' IDENT
error` (`try .["field"] instead of .field for unusually named fields`), and `error ':' DictExpr`
(`May need parentheses around object key expression`). Where jq recovers by a production jqj does
not build yet, jqj stops, and jq's blocks after that point are missing. These are the messages
`Possibly unterminated 'if' statement` (`if . then 1`), `Possibly unterminated 'try' statement`
(`try 1 catch ]`), `break requires a label to break to` (`break 1`) and an object pattern's `May
need parentheses around object key expression` (`. as {1+2: $x} | 1`). They are also the silent
`'(' error ')'`, `'[' error ']'`, `Term '[' error ']'` and `'{' error '}'`, after which jq goes on
and reports later errors (`(1 2) | 3 | 4 5`). jqj's blocks are then a prefix of jq's.
ParserEdgesTest pins jqj's blocks for each example.

## `-h`, `-V` and `--build-configuration` print jqj's own text (ADR-0023, issue #17)

| flag | jq 1.8.2 | jqj |
| --- | --- | --- |
| `-h` / `--help` | jq's usage text | jqj's usage text (written by spec 7) |
| `-V` / `--version` | `jq-1.8.2` | jqj's version (written by spec 7) |
| `--build-configuration` | jq's build string, as `$JQ_BUILD_CONFIGURATION` | jqj's build string |

Reason: these flags describe the program, not a filter's result; jqj is not jq. jq's rc-2 usage
lines (`jq: Unknown option …`, `Use jq --help for help with command-line options,`) stay
byte-identical. shtest's cases for these flags become deviation cases.

Spec 7 round 1 fixes the texts, proposed by the grilling-adviser for the owner under the
delegation of 2026-11-14 (`docs/wayfinder/spec-7-grilling.md`, question 10). jq-ref 1.8.2
observed; jqj intended:

| case | flag | jq 1.8.2 | jqj |
| --- | --- | --- | --- |
| none yet | `-V` / `--version` | `jq-1.8.2` | `jqj-<version>` |
| none yet | `--build-configuration`, `$JQ_BUILD_CONFIGURATION` | `--host=x86_64-linux-gnu --disable-docs --with-oniguruma=builtin --enable-static --enable-all-static 'CFLAGS=-O2 -pthread -fstack-protector-all' host_alias=x86_64-linux-gnu CC=x86_64-linux-gnu-gcc LDFLAGS=-s CPP=x86_64-linux-gnu-cpp` | `jqj <version> release 25 interpreter` |
| none yet | `-h` / `--help` | jq's usage text, rc 0 | jqj's own text in `main.c`'s layout with `--explain` added, written fresh, rc 0 |

The build string holds build-time facts only: a runtime Java version would make one filter print
differently per JVM.

## Long comma chains (ADR-0034, issue #63)

| case | filter | jq 1.8.2 | jqj |
| --- | --- | --- | --- |
| `depth/comma-10922` (`deviation long-comma-chains`) | `1,1,…,1`, 10 922 commas, on `null` | rc 3, `jq: error: function compiled to 65536 bytes which is too long` | the 10 923 ones, rc 0 |

jq-ref compiles 10 921 commas (`depth/comma-10921`, matched) and rejects one more with its own bytecode limit: a
function's code must fit 65 536 bytes. jqj compiles to an IR and an interpreter tree with no such limit, and a long
chain is bounded at run time by the step budget instead (one tick per right branch, F-32; jq-core
CommaChainTest pins chains of 10⁴ and 10⁵).

## Long path chains (F-42, F-44, issue #70)

| case | filter | jq 1.8.2 | jqj |
| --- | --- | --- | --- |
| jq-core `LongPathChainTest` | `.a.a…`, 10⁵ steps of `.a`, on `null` | rc 3, `jq: error: function compiled to 300002 bytes which is too long` | `null`, on a 512 KiB stack |
| jq-core `LongPathChainTest` | `.[0][0]…`, 10⁵ steps of `[0]`, on `null` | rc 3, the same message | `null`, on a 512 KiB stack |

jq-ref 1.8.2 (observed 2026-11-13, the filter read with `-f`) prints `null` for 21 844 steps of `.a` and for 21 844
of `[0]`, and rejects one more of either with the bytecode limit of "Long comma chains". jqj lowers a postfix chain
by a loop and pipes a chain of more than 64 nodes balanced (`Resolver.PIPE_CHAIN`); a run of more than 512 steps of
one result or none (field reads, constant indexes and constant slices) becomes one step that takes them in turn
(F-44). Its cost in ticks is the nested chain's. `LongPathChainTest` pins 10⁵ steps of each of `.a`, `[0]`, `["k"]`,
`[1:2]`, `[0]??`, `[]?` and `[]` on a 512 KiB stack (ADR-0017's `stackBytes`), 21 844 and 10⁵ steps of `.a` on the
test thread, and the ticks of the fused and balanced chains. ResolverTest pins that 10⁵ indexes by a variable,
`[$k]`, are piped balanced, 19 levels deep. A step whose key or bounds are computed, such as `[.a]`, `[.a:]` or
`."a\(.b)"`, binds them around the chain before it, so such a chain is as deep as it is long ("IR depth").

## Long definition runs (F-44, issue #70)

| case | filter | jq 1.8.2 | jqj |
| --- | --- | --- | --- |
| jq-core `LongDefRunTest` | `def f0: 0; …; def f9992: 9992; [f0, f9992]`, 9 993 definitions, on `null` | rc 3, `jq: error: memory exhausted at <top-level>, line 1, column 167660` | `[0,9992]`, rc 0 |

jq-ref 1.8.2 (observed 2026-11-13, the filter read with `-f`) prints `[0,9991]` for 9 992 such definitions and
rejects one more, rc 3. jqj compiles past that count: `LongDefRunTest` pins 9 993 and 10⁵ definitions compiling
and running on a 512 KiB stack.

## Long operator runs (ADR-0043, issue #94)

| case | filter | jq 1.8.2 | jqj |
| --- | --- | --- | --- |
| jq-core `OperatorChainTest` | `.a // .a // …`, 20 000 terms, on `null` | rc 3, `jq: error: memory exhausted at <top-level>, line 1, column 29989` | `null`, rc 0 |
| jq-core `OperatorChainTest` | `.a+.a+…`, 20 000 terms, on `null` | rc 3, `jq: error: function compiled to 199995 bytes which is too long` | `null`, rc 0 |
| jq-core `OperatorChainTest` | `.a and .a and …`, 20 000 terms, on `null` | rc 3, `jq: error: function compiled to 399985 bytes which is too long` | `false`, rc 0 |
| jq-core `OperatorChainTest` | `.a or .a or …`, 20 000 terms, on `null` | rc 3, the same message | `false`, rc 0 |
| jq-core `OperatorChainTest` | `.a == .a and .a == .a and …`, 20 000 terms, on `null` | rc 3, `jq: error: function compiled to 599985 bytes which is too long` | `true`, rc 0 |

jq-ref 1.8.2 (observed 2026-11-13, the filter read with `-nc -f`) runs 6 554 terms of `.a+` (6 553 after
`{"a":1} |`, as `IrDepthTest` writes them), 3 277 of `.a and` or `.a or`, 2 185 of `.a == .a and` and 2 185 of `.a //`, and
rejects one more with the bytecode limit of "Long comma chains", rc 3; 20 000 terms of `.a //`, which nests to the
right, fill its Bison stack first ("Parse depth"). jqj compiles to an IR with no such limit, so an operator run past
jq's limits runs, as a long comma chain does. A run of one arithmetic or comparison operator whose nested form has
more than `Resolver.BIND_RUN` = 64 Binds is one Binds, and a run of more than 64 terms of `and`, `or` or `//` is joined
balanced, about 2·log₂ n levels deep (ADR-0043); the parser reads a run of `//` by a loop, so it takes no nesting level
per term. Each run ticks as its nested form does (jq-core `WideConstructStepsTest`). `IrDepthTest` pins each run at
jq-ref's limit on a 512 KiB stack in a fresh JVM under three JIT tiers, and `OperatorChainTest` 20 000 terms on a
512 KiB stack and 200 000 of each operator but `//` on a 16 MiB one.

## IR depth (F-44, ADR-0040, issue #70)

| case | filter | jq 1.8.2 | jqj |
| --- | --- | --- | --- |
| jq-core `IrDepthTest` | `def f1: [[…[.]…]]; def f2: [[…[f1]…]]; …`, 100 brackets each, 512 brackets around `.` in all: 513 levels, on `null` | 512 nested arrays around `null`, rc 0 | a Stub |
| jq-core `IrDepthTest` | the same with 30 000 brackets: 30 001 levels | rc 0, printed 10 001 arrays deep and then `<skipped: too deep>` | a Stub |
| jq-core `IrDepthTest` | `def g1: …; …; def g5: …;` as above, 500 brackets in all, then `def v1($a): g5; def v2($a): v1(.); …; v500(.)`: 500 nested expansions, each binding `$a` | 500 nested arrays around `null`, rc 0 | a Stub |
| jq-core `IrDepthTest` | `.[.a:][.a:]…`, 2 000 steps, on `null` | `null`, rc 0 | a Stub |
| jq-core `IrDepthTest` | `.[.a][.a]…`, 10 000 steps, on `null` | rc 5, `jq: error (at <unknown>): Cannot index null with null (null)` | a Stub |
| jq-core `IrDepthTest` | `.a+.a-.a+…`, 400 terms, the operators alternating, on `null` | rc 5, `jq: error (at <unknown>): null (null) and null (null) cannot be subtracted` | a Stub |

jq-ref 1.8.2 (observed 2026-11-13, the filter read with `-f`) compiles every row and gives its result. jqj stubs a
filter of more than `Resolver.MAX_IR_DEPTH` = 512 levels of built IR, the leaf counted, as it stubs the 512th nested
expansion (ADR-0040 "Inline cap"). The depth is the whole IR's, measured once the lowering is done and wherever the
Resolver built a node (ADR-0040 "What counts"): an Ast's own node, the Bind of each value parameter around an
expansion's body, the Bind of a computed key or bound around the chain before it, and the Pipe trees of a chain. So
the bound stubs a construct whose IR is as deep as it is wide, up to `Resolver.BIND_RUN` = 64 nested Binds: an
operator between two filters binds both, two levels a term, and one between variables; `and`, `or` and `//` are a
level a term up to 64 terms; an object's computed value is a level and its computed key two; an interpolation, a
pattern's variable and a value parameter are a level each. Past it none stacks (ADR-0043): an object, an
interpolation, a pattern (`?//` alternatives among them), a value parameter's expansion (#93) and a run of one
arithmetic or comparison operator (#94) lower to one Binds, a level whatever its width, its values in the nested
form's order, and a run of `and`, `or` or `//` is joined balanced, about 2·log₂ n levels ("Long operator runs").
`BIND_RUN` counts Binds, not parts: a part passed as it is, a Const, a Var or an Identity, takes none. `IrDepthTest`
runs each construct at 600 parts on a 16 MiB stack and at jq-ref 1.8.2's widest (4 997 computed values, 4 998 keys
without values, 4 995 computed keys, 4 368 interpolations, 9 361 variables or keys of a pattern, two alternatives of
3 276 variables, 4 095 value parameters, and the operator runs of "Long operator runs"; one more is rc 3 there) on a
512 KiB stack in a fresh JVM, each with jq-ref's result. Nested constructs still stack: a construct under the bound
is its nested Binds, and one past it a level or two (its Binds, then the value it sits in), so nested wide objects
take a few levels each; so do nested definitions and nested value parameters, whose Stub stays, and a chain that
alternates operators, such as `.a+.a-.a+…`, which is runs of one operator each (ResolverTest, IrDepthTest). The
Binder and the Resolver walk an operator chain by a loop (#86, #94), so a chain of any length past the bound is this
Stub, not a StackOverflowError (jq-core `OperatorChainTest`: 20 000 alternating terms on a 512 KiB stack in a fresh
JVM, 200 000 on a 16 MiB one). The parser's 512 nesting levels bound one body ("Parse
depth"); definitions nest one body in another, so without the bound the IR, and the Resolver's recursion over it,
would be as deep as the inline cap lets them. 30 001 levels overflowed a 512 KiB stack (ADR-0017's `stackBytes`).
`IrDepthTest` pins 512 levels running and 513, 30 001 levels and the 500 nested expansions a Stub on a 512 KiB stack
in a fresh JVM, also with C1 only, and every row a Stub on a 16 MiB stack. Spec 2b's calls revisit the bound.

## Array length limit (ADR-0035, issue #64)

jq 1.8.2 raises "Array index too large" when an array grows past an index of 2^29−1, as `jv_array_append` does
when it collects one more result (`jv_array_set`, src/jv.c:1020). jqj ends a collection that would pass
`Integer.MAX_VALUE − 8` elements with exit 5 and "Bytes-built budget exceeded". It does so even when the bytes-built
budget is unlimited, as on the command line. Reason: a JVM array holds no more elements than that. Also, a collector
charges each element to the bytes-built budget before storing it (ADR-0035), so a collection that large exhausts any
real budget first. A termination of its own would add a public cause for a case no run can reach. query-core's tests
of `CollectOp.grown` pin jqj's side.

## libm last digits (ADR-0051, spec 4)

The 36 computed math builtins print jqj's own last digits, the same on every platform:
`StrictMath` for `acos`, `asin`, `atan`, `atan2`, `cbrt`, `cos`, `cosh`, `exp`, `expm1`, `hypot`,
`log`, `log10`, `log1p`, `pow`, `sin`, `sinh`, `tan` and `tanh`; definitions over `StrictMath` for
`acosh`, `asinh`, `atanh`, `exp2`, `exp10` and `log2`; Arm's port for `erf`; published formulas, or
the owner's answer on issue #105, for `erfc`, `gamma`, `lgamma`, `lgamma_r`, `tgamma`, `j0`, `j1`,
`jn`, `y0`, `y1` and `yn`. jq-ref's digits are glibc's on its CPU and change with the CPU's
features (F-65). Each function's row gives its measured bound in ulp over its recorded sample; the
math tickets add the rows.

| case | filter | input | jq 1.8.2 | jqj |
| --- | --- | --- | --- | --- |
| none yet | `27\|cbrt` | `null` | `3.0000000000000004` | `StrictMath`'s digits, recorded by the computed-math ticket |
| `test:` the bound test (named by its ticket) | each of the 36 over its recorded sample | — | glibc's digits | within the function's measured ulp bound |

Reason: only jq-ref's own build on its own CPU gives jq-ref's digits. `Math`'s digits are not fixed
across platforms, and glibc's code is LGPL. jq.test rounds every transcendental it checks
(lines 847–857).

## Zone abbreviations (ADR-0052, spec 4)

`%Z` prints the JDK's English short name for the zone at that instant, or `±hh[mm]` where the JDK
has only `GMT±hh:mm`. Offsets come from the JDK's tzdb (F-66). jq-ref prints tzdata's abbreviation
from the system's zoneinfo. They differ in three ways:
- LMT eras: the JDK never names one `LMT`;
- zones whose tzdata abbreviation is not the JDK's English short name; the host-zone ticket lists
  them by running jq-ref under each `TZ`;
- the tzdata version: the JDK's tzdb (2026e on JDK 25.0.5) against jq-ref's zoneinfo, for offsets
  as well as names.

| case | filter | input | jq 1.8.2 | jqj |
| --- | --- | --- | --- | --- |
| none yet | `strflocaltime("%Z %z")` on an instant of an LMT era (P4) | — | `LMT +0918` | the JDK's name for that offset, recorded by the host-zone ticket |

An unknown `TZ` is UTC with an empty name (ADR-0052). If jqj does not mirror jq-ref's "unknown
system failure" there, the host-zone ticket adds its row here.

Reason: the JDK supplies offsets and English names, not tzdata's abbreviations (F-66). A TZif name
reader would be a second zone source and can come later without rework (ADR-0052).

## `modulemeta` on a module that does not parse (ADR-0017, F-81, spec 7)

Proposed by the grilling-adviser for the owner under the delegation of 2026-11-14
(`docs/wayfinder/spec-7-grilling.md`, question 4). jq-ref 1.8.2 observed; jqj intended:

| case | filter | input | jq 1.8.2 | jqj |
| --- | --- | --- | --- | --- |
| none yet | `-L lib -nc modulemeta`, `lib/body.jq` holding `def x: 1; x` | `"body"` | stderr `jq: error: jq: error: library should only have function definitions, not a main expression`; stdout `null`, rc 0 | stdout `null`, stderr empty, rc 0 |

A module with a syntax error behaves alike, but its case would compare rc and stdout only
(ADR-0020).

jq's block is not catchable: `try` does not see it. Reason: the engine writes to no stream of its
own (ADR-0017), and a compile-error block in the middle of a run has no channel in the host SPI.

## `$ENV` key order on the command line (ADR-0017, spec 7)

Proposed by the grilling-adviser for the owner under the delegation of 2026-11-14
(`docs/wayfinder/spec-7-grilling.md`, question 6). jq-ref 1.8.2 observed; jqj intended:

| case | filter | environment | jq 1.8.2 | jqj |
| --- | --- | --- | --- | --- |
| none yet | `-nc '$ENV \| keys_unsorted'` | exactly `B=2 A=1` (`env -i`) | `["B","A"]` (`environ`'s order) | `["A","B"]` |

`env` gives the same object. Reason: the launcher passes `System.getenv()`, whose map has no
order, so jq-cli sorts the environment by key. Differential cases print `$ENV.X`, `env.X` or
`keys` only.

## The jq origin on the command line (ADR-0013, ADR-0020, F-81, spec 7)

Proposed by the grilling-adviser for the owner under the delegation of 2026-11-14
(`docs/wayfinder/spec-7-grilling.md`, question 4). jq-ref 1.8.2 observed; jqj intended:

| case | how the program is started | jq 1.8.2 `get_jq_origin` | jqj |
| --- | --- | --- | --- |
| none yet | by its bare name, found on `PATH` | `"."` | the directory `PATH` found the launcher in |

Started by its full path, as `./jqj` or as `bin/jqj`, jqj gives what jq gives: the launcher passes
`$(dirname "$0")` unchanged. A script found on `PATH` gets its found path as `$0`, where a binary
gets its bare name. jq's origin is `dirname(argv[0])`; jqj's is `Invocation.origin`, which the
launcher sets to `$(dirname "$0")`. Reason: a shell launcher cannot see the bare name it was
called by, and the found path keeps `$ORIGIN/../lib/jq` beside the program, not the caller.
Differential cases print only `type` or a suffix of an origin.

## `module not found` with `HOME` unset (ADR-0017, F-81, spec 7)

Proposed by the grilling-adviser for the owner under the delegation of 2026-11-14
(`docs/wayfinder/spec-7-grilling.md`, "Ticket approval (2026-11-14)"). jq-ref 1.8.2 observed; jqj
intended:

| case | filter | environment | jq 1.8.2 | jqj |
| --- | --- | --- | --- | --- |
| none yet | `-n 'import "q" as q; .'` | no `HOME` | `jq: error: module not found: q (Could not expand ~/.jq. (Could not find home directory.))`, rc 3 | `jq: error: module not found: q`, rc 3 |

Reason: the loader SPI gives no reason for a miss (F-81), and a reason channel would widen the
public SPI for one environment. The recorder always sets `HOME`, so the row is pinned by a `test:`
method.

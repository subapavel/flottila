# jqj

Runs a jq filter over JSON bytes on the JVM: the filter is compiled once and then run on each
input without turning the input into an object tree.

Seeded from the founding conversation (2026-11-10) and sharpened by Phase 0 (ADR-0002 to
ADR-0023). `/domain-modeling` keeps it current; a term marked _(open)_ is waiting for a decision.

## Language

**Filter**:
A jq program: text that, given one input value, produces zero, one or many results.
_Avoid_: query, expression (for the whole program), script

**Result**:
One value a filter produces for one input.
_Avoid_: output (that is the bytes the writer produces), item

**Builtin**:
A function every filter can call without defining it.

**Prelude**:
The builtins that are themselves written in jq and loaded by the resolver.
_Avoid_: standard library, builtin.jq (except when naming jq's file)

**Native builtin**:
A builtin implemented in Java and called from the IR as an intrinsic.
_Avoid_: C builtin, primitive

**Host-facing builtin**: A builtin that reads the host or the run instead of its input alone (the
run's inputs, the environment, the clock, the module loader, the debug sink) or that ends the run:
`input`, `inputs`, `env`, `halt`, `halt_error`, `input_filename`, `input_line_number`,
`get_search_list`, `get_prog_origin`, `get_jq_origin`, `modulemeta`, `now`, `debug`, `stderr`. It is
in no builtin family and gets hand-written cases (GATE check 2, ADR-0023). The zone readers
(`localtime`, `strflocaltime`, and `strptime` and `strftime` for `%s`) read the host zone but are in
the dates family. _Avoid_: system builtin, I/O builtin

**Reference jq**:
The pinned release of the original jq whose binary decides what a filter does.
_Avoid_: real jq, upstream jq, jq-ref (that is the binary's path)

**Deviation**:
A behaviour where jqj differs from the reference jq on purpose, listed in `DEVIATIONS.md` with
its reason.
_Avoid_: incompatibility, known issue

**Interpolation**:
The `\(f)` in a string literal: `f` runs on the input, and each result, printed as `tostring`
prints it, is spliced into the string.
_Avoid_: template

**Escape run**:
The longest run of escape sequences in a filter's string literal; jq checks it as one JSON string.
Not an **Escape** of a built value.

**Error block**:
One compile error as jq prints it: message, line, column, the source line and carets (ADR-0031).

**AST**:
The parser's output: one record per construct of jq's grammar with its source span, sugar kept;
only the resolver reads it (ADR-0032).
_Avoid_: parse tree, syntax tree

**Resolver**:
The pass from AST to IR: binds names, loads the prelude, rewrites sugar; a construct it does not
lower yet is a Stub.
_Avoid_: lowering pass

**Syntax error**:
A compile error at the first token jq's grammar rejects, `syntax error, unexpected <token name>`,
in jqj's own text (ADR-0020).

**Recovery message**:
A compile error jq adds after a syntax error at a known place, such as "May need parentheses around
object key expression"; its text matches jq.

**Slot**:
The place a `Bind`, a `Binds` (one per value), an `AltBind`, a `Reduce` or `Foreach` (its state slot) or a parameter keeps a value while its scope runs; each has its own (ADR-0034, ADR-0038, ADR-0041, ADR-0043).

**Downstream error**:
An error raised by what runs on a Try's result after its body emitted it. That Try lets it through; an outer Try, or an AltBind inside the body, may still catch it (ADR-0039).

**Wrap depth**:
How many Try results an error is still inside on its way out; a Try catches only an error at depth zero (ADR-0039).
_Avoid_: downstream flag.

**Live definition**:
A definition the main filter reaches through calls, closure arguments included; jq checks and compiles only these (ADR-0040).
_Avoid_: used definition.

**Compile level**:
The main filter, a live definition's body, or a closure argument of a jq-defined function. Its binding errors are reported, and those of its inner levels only when it has none (ADR-0040).
_Avoid_: scope (that is lexical).

**Inline cap**:
How many nodes one compile may lower from definition bodies and closures. A call whose expansion
would pass it or the IR depth bound, or would be the 512th nested expansion, is a PLAIN call to a
function lowered once (ADR-0040 #114, F-54).

**Value parameter**:
A `$name` parameter: it wraps the body in `name as $name | …` and binds per result, and `name` stays a filter parameter (ADR-0040).
_Avoid_: variable parameter.

**Loop state**:
The value a `reduce` or `foreach` carries from one source binding to the next: an init result, then each update result in turn, or null after an update that gave none (ADR-0041).
_Avoid_: accumulator (a kind of Hold), fold (that is parse-time).

**State slot**:
The slot a Reduce or Foreach keeps its loop state in. Its one Advance takes the value out, leaving null, and stores each update result back (ADR-0041).

**Native table**:
Every builtin signature jq writes in C or bytecode, plus jqj's replacing natives, each built or unbuilt; looked up after the user's definitions and before the prelude (ADR-0042).

**Unbuilt native**:
An entry of the native table that its spec has not built yet. A call binds, and lowering it is a Stub (ADR-0042).
_Avoid_: missing builtin.

**Replaced definition**:
A prelude definition whose name and arity a native also has; the native wins (F-31, ADR-0042).

**Pattern**:
The left side of `as`, which takes a value apart into variables: `$name`, an array pattern or an object pattern. An absent part is null; a non-container part raises (ADR-0038).
_Avoid_: matcher (jq's internal word).

**Alternative**:
One pattern of a `?//` chain. The next one runs when it, the body or anything downstream raises; the last one's error propagates (ADR-0038).

**Group slot**:
A slot an `AltBind` keeps for one name across all its alternatives, set at each match from that alternative's own slot, or null (ADR-0038).

**Parameter slot**:
A slot a host variable takes, set from the Bindings before the run and bound by no node (ADR-0038).
_Avoid_: register, local (that is the JVM's).

**Quiet variant**:
The instance of a navigation step that `?` selects: where the step would raise its own error it
gives no result, while errors from its target or key still propagate (ADR-0034).
_Avoid_: safe variant, optional operation.

**Key list**:
The keys of an object construction in source order, with a repeated key keeping its first place and its last value, as jq builds it (ADR-0034, #64).
_Avoid_: key set (it has an order).

**Distinct-key object**:
A read object whose key proof found no repeated key, so its walk needs no plan and takes each key entry once; there is no tag for it (ADR-0044, #85).
_Avoid_: dedup flag, unique object, tag 10 (it stays reserved).

**Key proof**:
What every walk of a read object, and Equality's key index, does first: it finds which key entries repeat an earlier key, pairwise up to 8 keys, then in a reused table, and by a sort when the table's probe budget runs out (ADR-0044).
_Avoid_: dedup, key check.

**Key index**:
Equality's view of a read right side for one object comparison: its key proof, looked up once per left key within that call, kept as the tape index of each key's last right value; it is no walk (ADR-0044 as amended, #95).
_Avoid_: key map, lookup table (alone).

**Object walk**:
A read object's members as its key proof gives them, each key at its first place with its last value, valid for that one walk. Every walker takes a read object's members through the walk, except the Printer: it enters through `KeyProof.plan`, the proof's pairs without the walk, and no other walker may (ADR-0044 as amended).
_Avoid_: object cursor, members view.

**Repeated key**:
A key equal, by its decoded bytes, to an earlier key of the same object; jq keeps the first place with the last value.
_Avoid_: duplicate key (in new text).

**Fold**:
Computing an operator on constant operands while parsing, as jq's `constant_fold` does; one that
raises folds to no constant, and its error comes at run time (ADR-0034, #65). There is no IR fold
(F-57).
_Avoid_: constant propagation.

**Object merge**:
`*` on two objects: each entry of the right one into the left, merged again where both values are objects; past 10 000 levels it raises "Object merge too deep" (ADR-0029).
_Avoid_: deep add.

**Value order**:
jq's order of all values (`jv_cmp`): null, false, true, numbers, strings by UTF-8 bytes, arrays element by element, objects by sorted key list then values; not transitive across literal and computed numbers (ADR-0012, ADR-0037).
_Avoid_: total order, natural order.

**Truthy**:
Neither false nor null; what If, Alt, `and` and `or` test (ADR-0037).

**Comparison depth**:
How many containers enclose a pair being compared; past 10 000 jq raises its too-deep error (ADR-0029).
_Avoid_: nesting depth (that is the parse limit).

**Constant key**:
An object-construction key known at parse time: a computed key jq folds to a constant (a number, string or container); a non-string one is a parser-action error (ADR-0036).
_Avoid_: static key, literal key (a folded key is not a literal).

## Values

**Document**:
One JSON value held as its UTF-8 bytes plus its tape.
_Avoid_: message (Rail8's word), payload, tree

**Tape**:
The flat index of a document: one entry per JSON value, object keys included, giving its type and
where it lies in the bytes; a container's entry also says how many entries it holds, so the whole
container can be stepped over at once.
_Avoid_: index (alone), DOM, token stream

**View**:
A value that is a position in a document. It is valid only while its document is.
_Avoid_: reference, pointer, lazy value

**Stale view**:
A view whose document was closed or replaced by a later read, a failed read included. Using it is
an error (`STALE_VIEW`), never a wrong value.
_Avoid_: dangling view, expired value

**Built value**:
A value the filter constructed, independent of any document. Immutable once handed on.
_Avoid_: heap value, materialised value

**Detach**:
To copy a view into a built value so it can outlive its document.
_Avoid_: materialise, clone

**Owned document**:
A document a host reads through the engine, with its own read-only tape; valid until the host
closes it, and shareable between steps and threads.
_Avoid_: shared doc

**Workspace document**:
A document read into a Workspace's reused tape; valid until that Workspace's next main read or its
close.

**Slurped document**:
The one document `-s` makes over the whole input stream, with a synthetic root array.

**Element document**:
The document one streamed element is read into, on the Workspace's reused element tape, with its
root at index 0; it goes stale when `rest` returns for that element (ADR-0054).
_Avoid_: chunk, item

**Literal number**:
A number read from a document, written in a filter or made by `tonumber`. It keeps its exact
decimal value until arithmetic touches it, and prints in its canonical form.
_Avoid_: untouched number, raw number

**Computed number**:
A number made by arithmetic or a math builtin: a double, printed in jq's shortest-digit layout.
_Avoid_: double (that is the Java type)

**Canonical form**:
The text jq prints for a literal number (`1e2` → `1E+2`; `1.10` stays): decNumber's
to-scientific-string after the exponent limits, or ±1.7976931348623157e+308 for an Infinity literal.
_Avoid_: normalised form

**Adjusted exponent**:
The exponent of a number's first significant digit (`123e45` → 47). A literal prints in exponent
form when its exponent is positive or its adjusted exponent is below −6.

**Exponent limits**:
jq's decNumber range for a literal. An adjusted exponent above 999 999 999 gives an Infinity
literal; a non-zero value below 1E−1147483646 is rounded HALF_UP to a multiple of it; a zero's
exponent is clamped into [−1 147 483 646, 999 999 999].

**Infinity literal**:
A literal past the upper exponent limit. It prints ±1.7976931348623157e+308, its double is
±Infinity, and it has no decimal value (ADR-0028).
_Avoid_: overflow number

**Shape**:
The shared key list of the objects built at one construction site.
_Avoid_: hidden class, schema

**Growable object**:
A built object whose keys are made at run time, so it keeps its own key list instead of a shape.
_Avoid_: dictionary, map object, hash object

**Ownership marker**:
The `owner` field of a built array, object or string: the owning activation (a `reduce` or
`foreach` run, or an update) that made the value. A write in place needs it to match and the site
to hold the in-place licence (a string append and an update's own rebuild need the match only;
ADR-0022).
_Avoid_: reference count, transient, lock

**Own owner**:
The `Owner` an update allocates at its first copy for its own rebuild. It needs no licence, the
conservative compile keeps it, and it is dropped when the update ends (ADR-0022).

**In-place licence**:
The analysis fact that a site's target is the only use of an owning activation's state, so it may
be written in place when its owner matches (ADR-0022).

**Sole-use chain**:
The way an activation's state goes through its body, from input to result, read once and kept
nowhere else.

**Borrow**:
A read of the state whose result keeps no part of it.

**Retire**:
To give an owning activation a fresh owner when one of its values leaves the sole-use chain, so
every value it made so far is copied before any later write.

**Copy-only**:
The engine-internal switch under which no owner ever matches; the ownership tests' baseline.

**Escape**:
The moment a built value becomes reachable from somewhere other than the operation that owns it:
bound to a variable, stored in another value, emitted, captured, raised, passed to `debug`, or read
a second time by its owner.

## Compiling

**IR**:
The one intermediate form of a filter that both backends read: one closed family of
format-neutral nodes and the functions they call. Everything a format adds reaches it as an
operation.
_Avoid_: AST (that is the parser's output), bytecode (that is jq's own or the JVM's)

**Operation**:
What an intrinsic IR node calls: a navigation step or a native builtin of one format, described by
the facts the analyses need: its call form says how many results, and its `Facts` its path rule,
whether it is pure, whether it reads the input stream (`reads`; only `input`), and whether it keeps
an argument (ADR-0048, F-79).
_Avoid_: primitive, opcode

**Navigation step**:
An operation that goes from a value to a part of it (field, index, slice, iterate). Only steps
extend a path.

**Path rule**:
What an operation does to the path in path mode, a field of its `Facts`: NONE (untracked), SAME
(passes it on), KEY, INDEX, ALL, SLICE, DYNAMIC (by the key's kind) or KEY_LIST (`getpath`)
(ADR-0048).
_Avoid_: step kind.

**Closure**:
A filter argument of a jq function, run in the caller's scope each time the callee calls it.
_Avoid_: lambda, callback

**Recursive group**:
The functions that can reach each other through calls.

**Specialisation**:
Making a copy of a lifted function's group, PLAIN ones included, for one outside argument tuple of
its invariant parameters, so those closures disappear. A lifted function is lowered once by the
Resolver; specialisation may copy it (ADR-0045 #117).

**Outside call**:
A Call into a lifted function's group from a body outside it: main, another function or a copy. Its
arguments for the invariant parameters are the tuple that specialisation copies for (ADR-0045 #117).
_Avoid_: entry call, external call.

**Invariant parameter**:
A closure parameter that every call inside its group passes on as a bare ParamCall of the matching
parameter, one per function. Every closure parameter of a PLAIN function is one. Specialisation
replaces it (ADR-0045 #117).
_Avoid_: unchanged closure.

**Tail call**:
A call in a tail position of a function body, into the caller's recursive group. Every left context
on the way to it has at most one result (F-56). Each closure argument is either a closure parameter
passed on bare, or holds no free slot, free Break or ParamCall. It runs as a pending jump. A call
from main, or from inside a closure argument's body, never is one (ADR-0006, ADR-0047 #118).
_Avoid_: tail position (the place, not the call).

**Call kind**:
A Call's fact: PLAIN, RECURSIVE, TAIL_SELF (a tail call of the caller itself) or TAIL_GROUP (a tail
call of another function in its group). The conservative compile stores no tail kind. `explain()`
prints it as `call=` (ADR-0047 #118).
_Avoid_: call type.

**Binds**:
One IR node for a run of binds: `Binds(source, values, slots, body)`, n ≥ 2, means
`Bind(v1, s1, … Bind(vn, sn, body))`, the first outermost. Slot k is in scope in the later values
and in the body. Each value has its own Tracking (ADR-0048). It ticks and charges nothing of its own
(ADR-0043).
_Avoid_: n-ary Bind

**Tail emitter**:
A node that, by its kind alone, gives one result at most and never breaks out: Identity, Const,
Var, an Intrinsic of a one-result call form, or a Pipe, If, Bind or Binds made of tail emitters. A
Binds takes a tail value's result, and an If a tail condition's, before the rest runs, so the stack
does not grow with them. It is a local fast path of the push form and the floor beneath ADR-0008's
value form in both compile modes (ADR-0043, ADR-0047).
_Avoid_: tail call (a call that runs as a jump), value form

**Last read**:
The only read of a variable's value in its binding, after which the value may be moved instead of
copied.

**Path shape**:
How an update or `path()` runs: along a fixed path, while walking the value, or by tracking every
path.

**Hold**:
A place that keeps a value while later steps run (a variable, an accumulator, an array being
built, an error payload). It is every place a value stays live across a node that may read input,
that node's own `.` included, so a pipe's right side holds its input (F-77). A view of an input
document is detached when it enters a hold that spans a later read. A generator resumed after its
consumer read holds its `.`, and a Path holds its keys.
_Avoid_: pin, retain

**Capture**:
A declaration outside a lifted function that its text or its callees reach, passed by every call as
a trailing parameter: a variable or a label's token as a value, a filter parameter as a closure
(ADR-0045).
_Avoid_: free variable, upvalue

**Defining frame**:
The frame a closure argument was written in. An invocation of it that binds slots links it from
cell 0 (ADR-0045).
_Avoid_: environment, parent scope

**Lifted function**:
A definition lowered once by the Resolver into the Program's function list and run by a Call;
specialisation may copy it (ADR-0045 #117). It is one of:
- a definition of a recursive group, a prelude definition's included;
- a definition whose call would pass the inline cap or the IR depth bound;
- a definition whose call would be the 512th nested expansion.

Every later call of it is a Call (ADR-0045, ADR-0042, ADR-0040 #114).
_Avoid_: compiled def

**Expansion measure**:
The nodes and levels an expansion would lower, closures and nested expansions included, known
before it is lowered. It decides between inlining and a call (ADR-0040 #114).
_Avoid_: inline cost

**Holder**:
The sink a tail emitter runs into. It keeps the one result and answers it with a stop, so the tail
run's false means a result came and its true means none (ADR-0043, F-85). It is no **Hold**: the
result is taken as soon as the run returns.
_Avoid_: buffer, box

**May read input**:
The fact that a node may run an operation whose `reads` fact is set, itself, through a call or
through a closure passed to it; a `ParamCall` may read only when a call site passes a reading
closure for its parameter. An emit to a consumer that may read counts as a read (ADR-0007).

**Root use**:
Whether a filter may read its input as the elements of one array, one at a time (ELEMENTS), or
needs it whole (WHOLE, the safe value); ADR-0054's syntactic test.

**Analyses gain**:
A filter's conservative-compile time over its optimised-compile time, measured in the same session.
It holds no reference time, so it is no speed factor (ADR-0019 #119).
_Avoid_: optimisation factor.

**Analysis**:
A pass over the IR that proves a property a backend can use: cardinality, path shape, root use,
touched fields.

**May break out**:
The fact that a run of an IR node may end by a Break to a label activation it did not start: it
holds a Break to a label outside it, any ParamCall, or a Call to a function that may. Such a node
never runs in the value form, and its safe value is true (ADR-0007 #115).

**Freeze id**:
A node's pre-order number in its body (main or one function), from 0; the key of every fact
(ADR-0045, ADR-0007).
_Avoid_: node index.

**Cardinality**:
How many results an IR node can produce for one input: none, exactly one, at most one, or many. An
error or a break is not a result. One and at most one also promise that nothing runs after the
result (F-56). The fixpoint starts below none and one, at no result and no normal end; a fact stores that
as none (ADR-0007 #112).

**Backend**:
What turns IR into something that runs. There are two.

**Interpreter backend**:
The backend that turns each IR node into an object and runs the tree.
_Avoid_: tier 0, tree walker

**Bytecode backend**:
The backend that generates one JVM class per filter with the Class-File API.
_Avoid_: compiler (the whole pipeline is the compiler), JIT (that is HotSpot's)

**Compiled filter**:
The immutable, thread-safe result of compiling a filter, whichever backend made it.
_Avoid_: program, plan

**Calibration cell**:
One shape × tier × thread × compile mode, run in a fresh child JVM that proves its thread's size;
`b` is the largest adjacent-pair slope, and the reserve the largest lowest-pair intercept plus the
largest cold term, over the `-Xint` and C1 cells; warm cells prove the result (ADR-0046 #113, #199,
K1).

**Lowest pair**:
The 256 KiB and 512 KiB platform cells of one tier, mode and shape. Its slope and cells give the
intercepts, so a slope that falls with depth cannot inflate the reserve (ADR-0046 #199).
_Avoid_: base pair, first chord.

**Unit edge**:
`U*`, the largest `U_f` at which a calibration cell completes; at `U*+1` it ends at the stack
backstop (ADR-0046 #113).
_Avoid_: max depth.

**Specialisation cap**:
How many nodes specialisation may copy in one compile, `SPECIALISE_CAP`; a group and its argument
tuple are copied whole or not at all (ADR-0045). A filter may demand more than the cap; ADR-0045 #117
names the corpus filters refused by design and the bench filters at the cap by design (F-112).
_Avoid_: inline cap.

## Running

**Activation**:
One run of a construct that holds state for that run. Name the sense where two meet: a **call
activation** is one live `Call` or real-closure `ParamCall`, which the recursion-depth budget counts
and a tail jump replaces (ADR-0046); an **owning activation** is a `reduce`, `foreach` or update run
(ADR-0022); a **label activation** is ADR-0008's.
_Avoid_: invocation, call instance, "level" for the budget's count, bare "activation" where two
senses meet.

**Frame**:
The slot cells one call activation owns while it runs: bind slots, value and closure parameters,
label tokens and hidden slots: an owner, item paths, walk state and a suspended call's input path
(ADR-0045, ADR-0010). The JVM's is always a **Java frame**.
_Avoid_: stack frame, activation record, environment, locals, heap frame, slot array.

**Hand-back**:
The trampoline's stop at a result for its starter, keeping the form that produced it so the run can resume there (ADR-0046).
_Avoid_: yield.

**Heap form**:
The resumable state of one node kind on the trampoline: what the push form keeps in its Java frame across a child's run or an emit, kept on the heap instead. It ticks, charges and counts as the push form does, so any body can run there (ADR-0046). A value-form Call on the trampoline ends its activation at its first result (ADR-0046 #198).
_Avoid_: continuation, heap frame.

**Starter**:
The call or run that starts a trampoline. It emits each handed-back result from its own frame, then resumes the trampoline with the emit's answer or error (ADR-0011, ADR-0046).
_Avoid_: driver owner.

**Unit limit**:
The stack units one filter may use in one run, `U_f`: the declared stack bytes less the guard
reserve, in units, less the filter's call-free depth (ADR-0046). At or below zero, main and every
body run on the trampoline.
_Avoid_: K.

**Call-free depth**:
The most frames, in stack units, that main or any body nests when each of its calls counts as a
starter's frames, `D_f`. It is stored with the compiled filter, per backend (ADR-0046).

**Leaf-emit depth**:
The frames a function or closure body nests from its root to an emit that no call's result leads
to. They stay live under the caller's rise, so every call into the body is charged them (ADR-0046
#111).
_Avoid_: hold (a Hold keeps a value).

**Pending jump**:
What a tail call leaves in the run state: the jump op itself, the jumping activation's frame, the
input, and the input's path in a path variant. Only the entry loop running in that same frame takes
it; it builds the callee's frame from it and re-enters (ADR-0047 #118, "Where a jump is taken").

**Value form**:
Running a node as `eval`, which returns its one result or none; only the optimised compile uses it,
where cardinality is at most one and the node may not break out (ADR-0047).
_Avoid_: tail emitter.

**Run form**:
Which of the two forms a node runs by, push or value. `Executable.form` reports it, and `explain()`
prints it as `form=`. It is fixed per node from the facts. The tail emitter is part of the push form,
not a form of its own (ADR-0047 #116).
_Avoid_: form (a generator's command line), tail form.

**Emit**:
To hand one result to the next stage. The answer says whether to keep going.
_Avoid_: yield, return, push (as a noun for the call)

**Short literal**:
A literal number whose token is canonical, has no exponent and is at most 15 bytes (ADR-0012). Its
double orders it as its decimal does, and reading its double ticks nothing (#97). An exact compare
still ticks each of its tokens (ADR-0037).
_Avoid_: small number

**Sink**:
What receives emitted results.

**Path mode**:
Running a filter's second variant, in which each result comes with the path that led to it or is
untracked, as `path()`, `paths` and updates need.

**Untracked**:
A path-mode result with no path. Used as a path, it raises "Invalid path expression".

**Path variant**:
The second instance of a node, which runs it in path mode. Only the nodes a TRACKED `PathOf` or
`Update` can reach have one, `Call` and `ParamCall` included, and it runs in the push form only
(ADR-0010).
_Avoid_: path twin, path-mode node.

**Tracking**:
The mark on a `Bind`, each value of a `Binds` and an `AltBind`'s value that tells a path variant how
to run the bound value: VALUE in value mode, PATH in path mode with its paths dropped, ITEM as PATH
with the path kept as an item path (ADR-0048).
_Avoid_: pattern mark, path flag.

**Item path**:
The path of a `foreach … as $name` item, kept in a hidden frame slot so that the item's `Var`
passes it on in path mode, as `limit`, `skip` and `nth` need (ADR-0010, ADR-0048).

**Walk state**:
What a FIXED or WALK update keeps while it rebuilds: one cursor per `.[]` level it has entered,
none for a chain without `.[]`, in the Update's hidden frame slots, set at its entry and cleared at
its end (F-62).
_Avoid_: path stack.

**Delete list**:
The paths of a MODIFY update whose right side gave no result; one `delpaths` removes them after the
last path (ADR-0010).

**Stop**:
An emit answered with false: the producer emits nothing more and returns.
_Avoid_: cancel, abort

**Label token**:
The number one activation of a `label` gets. Its breaks hand it back, and they are no error.
_Avoid_: label name

**Catch set**:
The errors a node turns into a run of its handler. A break and a termination are in none.

**Termination**:
An end of the evaluation that no filter can catch: halt, a budget, a stale view or the stack
backstop.
_Avoid_: fatal error

**Tick**:
One count against the step budget: at a site the IR defines, or per element or KiB inside a native
loop.

**Run state**:
The protocol state of one running evaluation in `query-core` (fuel, deadline, budget counts, and
later labels and the break target), owned by its Workspace and passed first to every node and
operation (ADR-0030).
_Avoid_: run context, context

**Refuel**:
A tick's cold path when fuel runs out, every 4096 ticks: it charges the overdraft to the step
budget, reads the clock against the deadline, checks the thread interrupt and grants the next
ticks.

**Rise**:
The frames a body nests above a call's results, up to its own emit; part of that call's stack
units (ADR-0046 #111).
_Avoid_: ascent.

**Stack units**:
The Java stack a call is charged for, one unit per push-form frame: its descent, its rise and its
callee's leaf-emit depth, fixed when the tree is built. A call whose units reach the unit limit
starts the trampoline (ADR-0046).

**Starter units**:
`T`, the units a starter adds for its own frames, from `Trampoline.run` to its emit or its driver's
deepest heap form, natives excluded (ADR-0046 #111).

**Trampoline**:
The interpreter's resumable loop that runs recursion beyond the stack-unit limit on the heap. It
returns each result to the call that started it.

**Workspace**:
The reusable scratch memory of one evaluation (tape array, output buffer, regex pattern
cache), passed in by the caller and never shared between two running evaluations.
_Avoid_: context (that word is taken by jq's `.`), thread-local, arena (that is the JDK's)

**Budget**:
A hard limit on one evaluation: recursion depth, bytes built, bytes written, steps or time. Going
over is a termination that carries a jq error message. The parse depth limit and the regex match
time are limits too, but raise jq's catchable errors (F-13, ADR-0017).
_Avoid_: quota, timeout (one budget among several)

**Host streaming**:
Running a filter of the form `<path>[] | rest` element by element, without holding the whole
document, when `rest` never looks back at it. Each element is an element document; a target that is
no array, or a missing path, is read whole (ADR-0054).
_Avoid_: automatic streaming (the host switches it on, F-4); `--stream` (that is jq's event mode,
a different thing)

**Walking skeleton**:
Milestone 1's first spec: the thinnest path through every layer, `.`, field chains such as
`.a.b.c`, `|` and parentheses over one document, under the full gates (ADR-0023).

**Tooling ticket**:
A ticket of the walking skeleton that builds the build, the oracle runners, the ratchet, the gate
scripts, the bench module or CI, before the language exists.
_Avoid_: skeleton ticket, build skeleton ticket

**Stub**:
A body that ends with a `JqCompileException` (a `JqSyntaxException` for malformed input) whose
message starts with `jqj stub: `, until the spec that owns it. The runners score its cases FAIL by
that prefix; no test asserts the text after it.

**Generator**:
A frozen function from a seed to one oracle case, named `<name>-v<N>`; changing any byte it
produces makes a new name.
_Avoid_: fuzzer

**Seed**:
The number that alone decides a generated case; the last part of its id.

**Form**:
One of a generator's fixed command lines, chosen by the seed.
_Avoid_: mode (that is a compile mode)

**Dropped seed**:
A seed left out because jq-ref gave rc 3 or timed out on it, listed in the case file's header.

**Unlimited**:
The value of a budget that never runs out: its largest value.
_Avoid_: none, infinite, 0, −1

**Host**:
The program embedding the engine. It supplies the clock, the time zone, the environment, the
module loader for `import` and the debug sink; extra inputs come per run.
_Avoid_: runtime, container, platform

**jq module**:
A text of directives and definitions that `import` or `include` names, or a JSON text bound by
`import … as $name` (a data module). The host's module loader finds and names it (ADR-0017, F-81).
_Avoid_: library; bare "module" where a Maven or JPMS module could be meant.

**Home module**:
What a loader's `home()` gives; on the command line `~/.jq` when it is a file, else
`~/jq/main.jq`.
_Avoid_: rc file.

**Program origin**, **jq origin**:
The directory a main filter's relative `search` joins, and the directory `$ORIGIN/` stands for;
null in the sandbox.

**Executable**:
What a backend makes of one filter's IR, and what a compiled filter runs: in milestone 1, the
interpreter tree.
_Avoid_: program, plan

**Read block**:
One read of jq's input loop: the bytes through the next LF, or 4091 bytes plus the rest of a
UTF-8 character cut there (F-25). `(at <file>:N)` counts the read blocks that end in LF, up to the
block that holds the completing byte. jqj does not read by blocks: it computes their ends only
where jq shows them (`(at <file>:N)`, `input_line_number`, `-R`, the drop after a caught `input`
error), and `-R` decodes each block on its own (F-75).
_Avoid_: chunk, line

**Completing byte**:
The byte at which jq's parser holds a whole top-level value: a container's closing bracket, a
string's closing quote, or the byte after a number or literal (the end of input when there is
none).

**Escape sequence**:
A backslash and what follows it inside a JSON string (`\n`, `\u00e9`); a string holding one is
tag 6.
_Avoid_: escape (that is a built value becoming reachable)

**Engine**:
The immutable, thread-safe object a host builds once with its limits and host; it compiles filters
and reads documents, and keeps no filter.
_Avoid_: runtime, context

**Bindings**:
The `$name` values and `$ARGS` a host gives one run.
_Avoid_: args, params

**Extra inputs**:
The documents a run's `input` and `inputs` read, given in its run options as a `JsonInputs`; the
command line's main loop reads the same `JsonInputs` through `Workspace.read(JsonInputs)` (F-71).

**Sandbox defaults**:
What a filter may not do when embedded unless the host allows it: read the environment, name a
file, halt the JVM, import from disk, read the system time zone (ADR-0017).

**Input stream**:
The concatenated bytes of all input files or standard input, read by one parser state.
_Avoid_: stdin (one source among several)

**Detecting byte**:
The byte at which jq's parser notices an error; a message's column points at it.

**Malformed document**:
Input bytes that jq's grammar rejects at some byte.
_Avoid_: invalid document

**Error replay**:
Re-reading a malformed document with the translated jq parser (`JvParser`'s replay mode) to
produce jq's message, line and column. It starts at the first byte after the last emitted value,
in a fresh parser state with jq's line, column, BOM and seq state.

**Lenient token**:
A number token jq accepts that is not strict JSON: `+1`, `.5`, `1.`, `007`, NaN and Infinity
spellings. Checked at read and decoded at access; never CANONICAL or FITS_LONG.

**Whole-container copy**:
Writing an untouched container by copying its input bytes once one walk has proved it safe.
_Avoid_: raw copy

## Verification

**Oracle**:
An independent source of the expected output for a case: jq's own test files, the reference jq
binary, the other backend or the other compile mode. A JSON conformance suite supplies inputs; the
reference jq supplies their verdicts.

**Case**:
One oracle entry with a stable id: a filter, an input, flags and the expected outputs.
_Avoid_: test (that is a JUnit method), example

**Case census**:
The assignment of every jq-test case to the spec that makes it pass, from the builtins and
constructs its filter reaches in the reference jq. Each spec gate requires the cases the census
gives it.
_Avoid_: case budget, target count

**Case directory**:
The fresh directory a differential case runs in, holding its files. Its real path is written
`<case>` in recordings and comparisons (F-82).
_Avoid_: temp dir

**Disagreement**:
A compile-mode case two of whose runs (optimised, conservative, `trampoline`) both end without a
termination but differ in exit code, stdout or stderr. It stops the next merge (F-29, ADR-0020
amendment for #60; the `trampoline` run, F-58).
_Avoid_: mismatch

**Differential oracle**:
Running a case in jqj and in the reference jq binary and requiring the same exit code, stdout and
stderr, byte for byte (syntax-error text excepted, ADR-0020).

**Backend oracle**:
Running a case in both backends and requiring identical output. It starts with the bytecode
backend (ADR-0018).

**Compile-mode oracle**:
Running a case under the optimised and the conservative compile of one backend, and the
`trampoline` run (the conservative compile with `stackBytes` 0, F-58), and requiring the same
output and error.

**Optimised compile**:
Compiling with the facts the analyses prove; the default compile.

**Conservative compile**:
Compiling with every analysis fact at its safe value: cardinality many, every path TRACKED, a
detach of every `input` document at its read (F-77), no specialisation, no in-place licence.

**Recording**:
The reference jq's exit code, stdout and stderr for one case, committed beside it and written only by the
record command.
_Avoid_: snapshot, golden file

**Required case**:
A case the JUnit run asserts: it is in the ratchet, or the ticket names it at its gate.

**Guard**:
A required case or test that passes before its ticket and is kept so that the ticket cannot break
it; it is not the ticket's red. Not a WALK's guard (ADR-0007).
_Avoid_: regression test (alone)

**Survey**:
The run of every case that is not required; it writes the oracle report and fails only on a crash.

**Oracle report**:
Each case's verdict (PASS, FAIL, STALE, CRASH) per oracle and backend; the ratchet reads it.

**Deviation case**:
A differential case that carries jqj's column of a `DEVIATIONS.md` row; the reference jq's column is
its recording.

**Retired case**:
A ratcheted id whose source file changed with a reference bump.

**Reference profile**:
The reference jq's build options (decNumber, Oniguruma, builtins), which jqj mirrors.

**Ratchet**:
A committed record of what passes (oracle cases, coverage, mutation scores, benchmark
baselines). Merges may only keep or raise it. The verifier regenerates the oracle pass lists after
every merge and the rest once per spec; nobody edits it by hand.

**Floor**:
A fixed minimum of coverage or mutation score below which the build fails, whatever the ratchet
says.

**Benchmark scenario**:
One measured quantity of one filter on one input size, such as the throughput of field extract on
a 10 MB document. Its baseline is a line of the ratchet.

**Gate scenario**:
A benchmark scenario whose speed factor gate check 5 bounds (G1–G6); cold start (G7) is recorded and
ratcheted with no factor.
_Avoid_: headline benchmark

**Diagnostic scenario**:
A benchmark scenario that informs one named decision; recorded, with no factor.

**Reference time**:
The reference jq's time per document on the same machine in the same session, its start-up
subtracted.

**Speed factor**:
jqj's time per document divided by the reference time.

**Machine label**:
The name of a kind of machine (`claude-cloud-4vcpu`), recorded with its CPU, kernel and JDK.

**Spike**:
A throwaway experiment that measures one risky assumption before a decision is made. Its code is
never merged; its numbers go to `bench/RESULTS.md`.
_Avoid_: prototype (in this repo that word names the wayfinder ticket type), proof of concept

## Way of working

**Spec**:
A GitHub issue (`spec`) describing one deliverable from the user's side, parent of its tickets.

**Ticket**:
A tracer-bullet slice of a spec, sized for one fresh context, with blocking edges and one class.

**Seam**:
An outer interface of a part of the system where a test gives input and checks output without
knowing the inside. A spec names its seams before its tickets exist.
_Avoid_: test boundary, integration point

**Frontier**:
The open, unblocked, unclaimed tickets of a spec or map.

**Integration branch**:
`spec/<issue>-<slug>`, where a spec's tickets are merged one by one before one PR to `main`.

**Class-C module**:
A module on a hard path, listed in `quality/floors.tsv` (ADR-0020; today `query-core` and
`jq-core`). Mutation floors apply to it, and the spec gate
runs PIT on all of it (the ticket gate no longer does, F-27).
_Avoid_: core module

**Word check**:
The check that no file of `query-core` contains the word jq or json; the project name jqj passes.

**Ticket gate**:
The automatic checks the verifier runs on one ticket's worktree before it merges: the
whole-reactor build with every oracle, PIT on the changed classes, the LF, trailer and word checks,
and the ratchet check (ADR-0014).
_Avoid_: CI (CI runs the build and script checks on every push; the gate adds PIT and the ratchet), pre-merge review

**Spec gate**:
The full checks the verifier runs on an integration branch before the spec's PR: every build,
test and oracle, the benchmarks and the ratchet regeneration.
_Avoid_: full build (only one part of it)

**Milestone gate**:
The check, by the gate-reviewer on one commit of `main`, that every condition of `docs/GATE.md`
has observed evidence.

**Finding**:
A written contradiction between practice and an ADR or the architecture, with the smallest
change that keeps the documented intent.

**Evidence word**:
One of `VERIFIED_PASS`, `EXECUTED_FAIL`, `NOT_RUN`, `ENVIRONMENT_BLOCKED`; every reported check
carries one.

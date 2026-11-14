# Operation call forms

Status: accepted (2026-11-10, owner, issue #7 "What is the execution protocol?"); completes ADR-0005, amends ADR-0006; amended by ADR-0022 (issue #8: `keepsArgs`, in-place operations); amended by ADR-0030 (run state, #59); completed by ADR-0034 (sealed CallForm, NONE and END, #63)

An `Operation` (ADR-0005) is called through a functional interface that matches its cardinality.

- **One or at most one result:** `Fn0(ws, in)`, `Fn1(ws, in, a)`, `Fn2(ws, in, a, b)` and
  `FnN(ws, in, Object[])`. Each returns the value, or `NONE` for no result.
- **Many results:** a resumable cursor rather than a sink:
  `Many { long first(ws, in, args); long next(ws, in, args, long state); Object value(ws, in, long state); Object key(ws, in, long state); }`,
  where the state `END` is `-1`. Direct code runs it as a loop that ticks at each step. The CPS
  trampoline (ADR-0011) can stop and resume it.
- **Tape steps:** a navigation step may also offer `TapeStep { int step(Object doc, int cursor, Object key); }`,
  so bytecode can walk an `int` cursor (ADR-0003).
- **Key lists:** `getpath` is a navigation step that extends a path by a key list. jq.test requires
  `getpath(["a",0,"b"]) |= 5`, and on jq-ref `path(getpath(["a",0], ["b","c"]))` gives both paths.
  Unlike the other steps, it does not raise at the step on an untracked value:
  `{"a":1} | [path(getpath(["a"]) | empty)]` gives `[]`.
- **Quiet variants:** the quiet variant used by `?` is a second instance of the same form. In path
  mode it still raises path-validity errors. On jq-ref, `[path(1|.a?)]` exits with rc 5 while
  `[path(1|try .a)]` and `[path(1|(.a)?)]` give `[]`, so in path mode `.a?` is not `Try(.a)`
  (F-11).
- **Bytecode** loads instances as class-data constants
  (`MethodHandles.Lookup.defineHiddenClassWithClassData`, loaded by `MethodHandles.classDataAt`,
  present in JDK 25.0.5 per `javap`) and calls them through their interface. No MethodHandle is
  invoked.
- **Natives** (amends ADR-0006): `last/1` joins `range/2`, `inputs` and `flatten`. jq 1.8.2
  bytecodes it in `builtin.c`, so `[last(empty)]` gives `[]` and a `reduce` definition would give
  `[null]`. It has no path variant: `[1,2] | path(last(.[]))` raises on jq-ref too, but
  `[1] | path(last(.))` gives `[]` there and an error in jqj (`DEVIATIONS.md`).

## Considered options

- **MethodHandles for every arity:** one type fits all, but `invokeExact` on a non-constant handle
  is slow in the interpreter.
- **A sink form for many results:** the trampoline cannot resume it.

## Amendment (ADR-0022, issue #8)

An operation's in-place variant is called like its value form with the owner and the target first.
It is never called at a site without the in-place licence.

# Recursion: weighted stack units, a resumable trampoline only in the interpreter

Status: accepted (2026-11-10, owner, issue #7 "What is the execution protocol?"); details the hybrid chosen on issue #3; amended by ADR-0017 (issue #13) and ADR-0018 (issue #12); amended by F-30 (U calibrated in spec 2 without path-mode `..`)

Tail calls are jumps. Other recursive calls run on the Java stack until the stack units reach a
limit U, and beyond U they run on a CPS trampoline. A memory-bounded recursion-depth budget limits
the depth. `StackOverflowError` is only a backstop.

- **Tail calls:** `TAIL_SELF` is a loop that reuses the frame. `TAIL_GROUP` is a call loop, and the
  bytecode backend emits one method per group with an `int` switch on the function, under the
  method-size limit.
- **Stack units:** each `RECURSIVE` call and each real-closure `ParamCall` adds `w(site)` units on
  entry and takes them off on return. `w(site)` is the number of Java frames the backend nests
  from the callee's entry to the site, fixed when the tree is built. Starting a trampoline also
  adds units. At `units ≥ U` the call runs on the trampoline.
- **The trampoline is resumable.** The driver stops at each result and returns it to the call that
  started it. That call emits the result from its own frame, then resumes the driver, so its sink
  never runs on top of a driver. Spike A's extension (`spike/recursion-depth` @ `7fd4ea6`,
  `results/7-nested-drivers-v2.log`) measured both forms with limit 256, bounce 64, a 1 MB stack
  and a cold JVM. The `nested` shape is a recursion of depth n whose continuation starts a second
  recursion of depth 1000 at every level.
  - **Sink called inside the driver (form A):** one nested driver per direct level that receives a
    result. 201 and 202 fitted before `StackOverflowError`, at n = 2000 and n = 10 000.
  - **Driver returning each result (form B):** at most one driver live at a time. Starters still
    nest, at most once per direct level, below U. n = 2000 took
    983 ms and n = 10 000 took 4049 ms. T, E, M and N completed at 10⁶ in both forms.
  - **Cost of form B:** warm medians at 10⁶ were T 91 → 77, M 109 → 90, N 413 → 451 and E 177 →
    228 ms, with overlapping ranges. E has many results per driver and is the worst case, at about
    1.3×. The first measurement (`0c6bffe`) dropped a result in form B; the adviser found it on E,
    and it is fixed in `7fd4ea6`.
  - Numbers are in `bench/RESULTS.md`.
- **U is a constant per release,** set by a calibration test. The test runs cold and warm, on
  stacks of 512 KB and one other size, on both backends and on a virtual thread, over the T, E, M
  and N shapes, a wide body, a closure chain, the nested-driver shape and path-mode `..` on a
  10 000-deep document.
  - A host may declare the stack bytes one evaluation may use. U is then derived after taking off
    a measured fixed reserve: the adviser read about 96 KiB of guard pages per stack from this JVM's
    flags (to be recorded by the calibration test), so U does not scale linearly.
  - The field belongs to "What are the public API, the host SPI and the sandbox?".
- **CPS lives only in the interpreter.** A compiled filter keeps the interpreter tree of its
  recursive groups and closure bodies. A bytecode call beyond U is handed to the interpreter with
  the function id, the input, the value arguments, the closure arguments, the label tokens and
  the caller's `Sink`. In TRACKED path mode it also passes the input's `Path` and the `PathSink`.
  - A closure argument is a `Filter` that exposes its IR node id and its captured values.
  - The speed of recursion beyond U at interpreter speed is not measured yet.
- **The recursion-depth budget** counts live activations, on the Java stack and on the trampoline
  together. Its default lets the 10⁶ levels that jq-ref runs complete. The number is set in "What
  are the public API, …".
- **The backstop:** `StackOverflowError` is caught only at the evaluation entry and becomes
  `Termination(STACK)` (ADR-0008).

## Considered options

- **A fixed K = 256 calls:** the stack per call varied 5.8× by shape (2 030 to 11 780 levels
  warm on 1 MB), so a wide body overflows before K.
- **A U scaled linearly with the host's stack:** it overstates U on small stacks, because of the
  fixed reserve.
- **CPS also in bytecode:** fastest deep recursion, but it needs a second generated variant of
  every recursive group, doubled again for path mode.
- **A sink called inside the driver:** one nested driver per direct level; 201 fitted in 1 MB
  before the overflow (measured above).
- **Recursion depth charged to the nesting-depth or bytes-built budget:** the reader stops at
  10 000 levels while recursion needs 10⁶, and frames are freed on return.
- **A big-stack thread beyond U:** it was faster on N (285 against 350 ms at 10⁶), but costs a
  thread per deep evaluation, the stack size is only a hint, and the owner chose the hybrid.

## Amendment (ADR-0017, issue #13)

The host declares the stack bytes through `Limits.stackBytes` (default 512 KiB). The default
recursion-depth budget is 4 194 304 live activations, which lets 10⁶ levels complete; the
calibration test confirms the activations per level. `Limits.cli()` keeps it, so jq-ref's 10⁷
levels are a `DEVIATIONS.md` row.

## Amendment (ADR-0018, issue #12)

Milestone 1 has the interpreter only. The calibration test runs on the interpreter in spec 2. The
bytecode spec runs it again on both backends, and the release takes the smaller U.

## Amendment (F-30, spec 2)

Spec 2 calibrates U over every shape above except path-mode `..`, which spec 3's path-mode ticket
adds before re-running the calibration; U may only fall.
